import Foundation
import SkipFuse
import Observation

enum Endpoint: Hashable, Codable, Sendable {
    case currentLocation
    case place(SavedPlace)

    var title: String {
        switch self {
        case .currentLocation: return L10n.t("Current location")
        case .place(let p): return p.name
        }
    }

    var key: String {
        switch self {
        case .currentLocation: return "here"
        case .place(let p): return p.id
        }
    }

    func coordinate(current: LatLon?) -> LatLon? {
        switch self {
        case .currentLocation: return current
        case .place(let p): return p.coordinate
        }
    }
}

enum TimeMode: String, Codable, CaseIterable, Identifiable, Sendable {
    case now = "Now"
    case departAt = "Depart"
    case arriveBy = "Arrive"
    var id: String { rawValue }
}

@MainActor
@Observable
final class PlanModel {
    var from: Endpoint = .currentLocation
    var to: Endpoint? { didSet { if to == nil { searchStarted = false } } }
    /// Plan tab: routes load after "Search routes"; from then on, changes search again by themselves.
    /// Clearing the destination starts over.
    var searchStarted = false
    /// Where the destination was picked, kept in the history with an icon.
    var source: RecentSearch.Source = .search
    var timeMode: TimeMode = .now
    /// Set to about now (on opening, or picked as now): the departure time keeps following the clock,
    /// so coming back later shows routes from then, not from when the search was made.
    var date = Date() { didSet { dateFollowsNow = abs(date.timeIntervalSinceNow) < 90 } }
    private(set) var dateFollowsNow = true
    /// "Depart now": searched for the current time and refreshed live (delays, departed trips).
    var isLive: Bool { timeMode == .now || (timeMode == .departAt && dateFollowsNow) }
    /// When the routes on screen were fetched.
    private(set) var updatedAt: Date?

    /// Filters and comparison, shared by Plan and Nearby → "Where to now?" (set through AppState).
    var options = RouteOptions.load()

    private(set) var itineraries: [Itinerary] = []
    /// Walking, cycling and driving the whole way, when asked for (`options.compareStreetModes`).
    private(set) var comparisons: [StreetComparison] = []
    private(set) var isLoading = false
    private(set) var isLoadingMore = false
    private(set) var errorMessage: String?

    /// A message instead of routes (a shared place that could not be found).
    func showError(_ message: String) {
        itineraries = []
        errorMessage = message
    }
    /// Said above the routes when they were only found with a longer walk than usual (the stop is far).
    private(set) var walkNote: String?

    private var nextCursor: String?
    @ObservationIgnored private var lastRequest: (from: LatLon, to: LatLon, time: Date?, arriveBy: Bool, maxWalk: Int)?
    @ObservationIgnored private var lastOptions = RouteOptions()
    @ObservationIgnored private var token = UUID()
    @ObservationIgnored private var resultsKey: String?
    @ObservationIgnored private var completedAt: Date?
    /// Cycling and driving the whole way for `streetKey`, fetched after the routes.
    @ObservationIgnored private var streetRoutes: [Itinerary] = []
    @ObservationIgnored private var streetKey: String?
    @ObservationIgnored var history: SearchHistoryStore = .shared

    /// The routes in time order, always: the list reads like a timetable. What fits best
    /// (recommended, fastest, fewest changes, least walking) is marked, not sorted to the top.
    /// Smart routing drops routes another one beats on everything.
    var sortedItineraries: [Itinerary] {
        let list = SmartRouting.isEnabled ? SmartRouting.pruned(itineraries) : itineraries
        return list.enumerated().sorted { a, b in
            a.element.startTime != b.element.startTime ? a.element.startTime < b.element.startTime : a.offset < b.offset
        }.map(\.element)
    }

    var canLoadMore: Bool { nextCursor != nil && !itineraries.isEmpty }

    /// Changes whenever a new search is needed.
    var queryKey: String {
        let t = isLive ? "now" : String(Int(date.timeIntervalSince1970 / 60))
        return "\(from.key)|\(to?.key ?? "-")|\(timeMode.rawValue)|\(t)|\(options.key)"
    }

    /// What was offered, to learn from the route that gets saved (smart routing).
    private func offerForLearning() {
        SmartRouting.offered = itineraries
        SmartRouting.offeredArriveBy = timeMode == .arriveBy
    }

    func swap() {
        guard let t = to else { return }
        to = from
        from = t
    }

    /// - Parameter silent: refresh in place without a spinner (periodic live update).
    func search(current: LatLon?, locationDenied: Bool = false, silent: Bool = false) async {
        guard let to else {
            itineraries = []; comparisons = []; errorMessage = nil; return
        }
        guard let f = from.coordinate(current: current), let t = to.coordinate(current: current) else {
            itineraries = []; comparisons = []
            errorMessage = locationDenied
                ? L10n.t("Location is off. Choose a start point, or allow location access in Settings.")
                : L10n.t("Waiting for your location…")
            return
        }
        if !silent, resultsKey == queryKey, !itineraries.isEmpty,
           let completedAt, Date().timeIntervalSince(completedAt) < 45,
           let lastRequest, f.distance(to: lastRequest.from) < 150 { return }
        // Results of another query (new destination or time) must not stay on screen without a spinner.
        if !silent, resultsKey != queryKey { itineraries = []; comparisons = []; nextCursor = nil }
        // Following the clock: the picker shows the time the routes are for.
        if timeMode == .departAt, dateFollowsNow, abs(date.timeIntervalSinceNow) > 30 { date = .now }
        let searchedFrom = from
        let searchedMode = timeMode
        let searchedDate = date
        let myToken = UUID()
        token = myToken
        if !silent { isLoading = true }
        defer { if token == myToken { isLoading = false } }
        errorMessage = nil
        if !silent { walkNote = nil }
        let time: Date? = isLive ? nil : date
        let arriveBy = timeMode == .arriveBy
        let options = options
        do {
            var maxWalk = options.maxWalkMinutes * 60
            var res: PlanResponse
            if options.maxWalkMinutes == RouteOptions.defaultWalkMinutes {
                // Ask for a comfortable walk and the usual one at once; a long walk wins only when
                // it arrives clearly earlier (the server alone prefers walking 30 min to save one).
                async let shortRes = TransitousClient.shared.plan(from: f, to: t, time: time, arriveBy: arriveBy,
                                                                  maxWalk: Reach.shortWalkSeconds, options: options)
                async let longRes = TransitousClient.shared.plan(from: f, to: t, time: time, arriveBy: arriveBy,
                                                                 maxWalk: maxWalk, options: options)
                let long = try await longRes
                if let short = try? await shortRes, !short.itineraries.isEmpty {
                    res = long.replacingItineraries(Reach.preferShortWalks(short: short.itineraries, long: long.itineraries))
                } else {
                    res = long
                }
            } else {
                res = try await TransitousClient.shared.plan(from: f, to: t, time: time, arriveBy: arriveBy,
                                                             maxWalk: maxWalk, options: options)
            }
            try Task.checkCancellation()
            // Nothing within 30 min walk of a stop: try again with a longer walk (45 min, then 2 h),
            // so a stop beyond the usual limit shows up as a route with a long walk, not as "no connections".
            // Not when the user chose a shorter walk: that limit is theirs.
            if options.maxWalkMinutes == RouteOptions.defaultWalkMinutes {
                while res.itineraries.isEmpty, let wider = Reach.nextWalkStep(after: maxWalk) {
                    try Task.checkCancellation()
                    guard token == myToken else { return }
                    maxWalk = wider
                    res = try await TransitousClient.shared.plan(from: f, to: t, time: time, arriveBy: arriveBy,
                                                                 maxWalk: maxWalk, options: options)
                }
            }
            try Task.checkCancellation()
            guard token == myToken else { return }
            itineraries = Self.merge(transit: res.itineraries.withoutDetours, direct: res.direct)
            offerForLearning()
            walkNote = maxWalk > RouteOptions.defaultWalkMinutes * 60 ? Reach.longWalkNote(res.itineraries) : nil
            let key = queryKey
            if streetKey != key { streetRoutes = [] }
            comparisons = Self.comparisons(direct: res.direct + streetRoutes, listed: itineraries, enabled: options.compareStreetModes)
            resultsKey = queryKey
            completedAt = Date()
            updatedAt = completedAt
            nextCursor = arriveBy ? res.previousPageCursor : res.nextPageCursor
            lastRequest = (f, t, time, arriveBy, maxWalk)
            lastOptions = options
            if !silent { history.record(from: searchedFrom, to: to, mode: searchedMode, date: searchedDate, source: source) }
            if itineraries.isEmpty {
                errorMessage = options.isDefault ? L10n.t("No connections found.")
                    : options.maxWalkMinutes < RouteOptions.defaultWalkMinutes ? Reach.ownLimitNote(minutes: options.maxWalkMinutes)
                    : L10n.t("No connections found with these filters.")
                // Say so when the area has no timetable at all (Athens, Malta, Minsk …), not just no route.
                if let gap = await Self.coverageGap(from: f, to: t), token == myToken, itineraries.isEmpty {
                    errorMessage = gap
                }
            }
            // The routes are on screen; cycling and driving follow when the server has them.
            isLoading = false
            applyLiveSources(token: myToken)
            if !options.comparisonModes.isEmpty, streetKey != key {
                await loadStreetRoutes(from: f, to: t, time: time, arriveBy: arriveBy, walking: res.direct,
                                       modes: options.comparisonModes, key: key, token: myToken)
            }
        } catch {
            guard token == myToken, !Self.isCancellation(error) else { return }
            // A failed background refresh keeps the routes already on screen.
            if !silent || itineraries.isEmpty {
                itineraries = []
                comparisons = []
                if options.isSlowSearch, (error as? URLError)?.code == .timedOut {
                    errorMessage = L10n.t("This search took too long. Try again, or choose walking to the first stop.")
                } else {
                    errorMessage = error.localizedDescription
                }
            }
        }
    }

    /// Live times and disruptions from other operators (ÖBB, Wiener Linien, search.ch, VRR, ACTV, SL, TfL) where
    /// Transitous has none. The routes are already on screen; corrected copies replace them.
    private func applyLiveSources(token myToken: UUID) {
        let snapshot = itineraries
        guard LiveOverlay.enabled, !snapshot.isEmpty else { return }
        Task { [weak self] in
            let enriched = await LiveOverlay.apply(snapshot, budget: 5)
            guard let self, self.token == myToken else { return }
            self.itineraries = LiveOverlay.replace(self.itineraries, with: enriched)
        }
    }

    /// One request per mode, side by side: cycling comes back in about a second, driving
    /// through a big city can take ten. Each shows as soon as it arrives; a failed one is asked
    /// again on the next refresh, the others then come from the cache.
    private func loadStreetRoutes(from f: LatLon, to t: LatLon, time: Date?, arriveBy: Bool, walking: [Itinerary],
                                  modes: [String], key: String, token myToken: UUID) async {
        var complete = true
        await withTaskGroup(of: [Itinerary]?.self) { group in
            for mode in modes {
                group.addTask {
                    try? await TransitousClient.shared.streetRoutes(from: f, to: t, time: time, arriveBy: arriveBy, mode: mode)
                }
            }
            for await found in group {
                guard token == myToken, !Task.isCancelled else { complete = false; continue }
                guard let found else { complete = false; continue }
                streetRoutes += found
                comparisons = Self.comparisons(direct: walking + streetRoutes, listed: itineraries, enabled: true)
            }
        }
        if complete, token == myToken { streetKey = key }
    }

    func loadMore() async {
        guard let req = lastRequest, let cursor = nextCursor, !isLoadingMore else { return }
        let myToken = token
        isLoadingMore = true
        defer { isLoadingMore = false }
        do {
            let res = try await TransitousClient.shared.plan(from: req.from, to: req.to, time: req.time,
                                                             arriveBy: req.arriveBy, pageCursor: cursor, maxWalk: req.maxWalk,
                                                             options: lastOptions)
            // A new search started meanwhile: these results belong to the old query.
            guard token == myToken else { return }
            let known = Set(itineraries.map(\.id))
            let fresh = res.itineraries.withoutDetours.filter { !known.contains($0.id) }
            itineraries = (itineraries + fresh).sorted { $0.startTime < $1.startTime }
            offerForLearning()
            nextCursor = req.arriveBy ? res.previousPageCursor : res.nextPageCursor
        } catch {
            if !Self.isCancellation(error) { errorMessage = error.localizedDescription }
        }
    }

    /// Walking there is listed first when it is about as fast as the best ride, or the only way.
    nonisolated static func merge(transit: [Itinerary], direct: [Itinerary]) -> [Itinerary] {
        // Only walking joins the list; cycling and driving are shown as a comparison.
        let walking = direct.filter { let m = StreetComparison.streetMode($0); return m != "BIKE" && m != "CAR" }
        guard let walk = walking.min(by: { $0.duration < $1.duration }) else { return transit }
        guard let best = transit.map(\.duration).min() else { return [walk] }
        let worthIt = Double(walk.duration) <= Double(best) * 1.25 || walk.duration <= 10 * 60
        return worthIt ? [walk] + transit : transit
    }

    /// Street modes to compare. Walking is left out when it is already in the list of routes.
    nonisolated static func comparisons(direct: [Itinerary], listed: [Itinerary], enabled: Bool) -> [StreetComparison] {
        guard enabled else { return [] }
        let listedIds = Set(listed.map(\.id))
        let best = listed.filter { !$0.isDirect }.map(\.duration).min()
        return StreetComparison.from(direct).filter {
            !($0.mode == "WALK" && listedIds.contains($0.itinerary.id)) && $0.isRealistic(bestTransit: best)
        }
    }

    /// No route found: is there any departure within 2 km of the start and of the destination?
    /// Stops alone are not enough (Athens has stops but no trips in the data). An unanswered
    /// check counts as covered: no claim without evidence.
    nonisolated static func coverageGap(from: LatLon, to: LatLon) async -> String? {
        async let start = hasService(near: from)
        async let end = hasService(near: to)
        let (s, e) = await (start, end)
        return coverageMessage(startCovered: s, destinationCovered: e)
    }

    nonisolated static func hasService(near point: LatLon) async -> Bool {
        guard let res = try? await TransitousClient.shared.departures(near: point, radius: 2000, count: 1) else { return true }
        return !res.stopTimes.isEmpty
    }

    nonisolated static func coverageMessage(startCovered: Bool, destinationCovered: Bool) -> String? {
        switch (startCovered, destinationCovered) {
        case (false, false): return L10n.t("No timetable data for this area yet. Publit uses open timetables (Transitous), and none are published here.")
        case (false, true): return L10n.t("No timetable data around the start yet. Publit uses open timetables (Transitous), and none are published there.")
        case (true, false): return L10n.t("No timetable data around the destination yet. Publit uses open timetables (Transitous), and none are published there.")
        case (true, true): return nil
        }
    }

    private static func isCancellation(_ error: Error) -> Bool {
        if error is CancellationError { return true }
        if let e = error as? URLError, e.code == .cancelled { return true }
        return false
    }
}

struct SharedTicketFile: Identifiable {
    let id = UUID()
    let url: URL
}

enum AppTab: Hashable { case nearby, plan, tickets, favorites }

@MainActor
@Observable
final class AppState {
    var tab: AppTab = .nearby
    /// Plan tab: trips for later (tonight, next week).
    let plan: PlanModel = {
        let m = PlanModel()
        m.timeMode = .departAt
        return m
    }()
    /// Nearby tab: "from here, now".
    let goNow = PlanModel()
    /// Set by publit:// links (calendar events, Live Activity buttons).
    var deepLink: DeepLink?
    /// PDF/image handed to the app by iOS Open In / Copy to Publit.
    var sharedTicketFile: SharedTicketFile?
    /// Nearby shows "Where to now?" results (also opened by Siri: "Take me home").
    var showGoNow = false
    /// The first departure board is on screen (or could not load): the launch animation may end.
    var firstBoardReady = false
    /// Raised when a shared place arrives: sheets (settings, place search) close themselves.
    var dismissToken = 0

    func open(_ url: URL) {
        if url.isFileURL, Features.passes {
            deepLink = nil
            tab = .tickets
            sharedTicketFile = SharedTicketFile(url: url)
            return
        }
        guard let link = DeepLink(url: url) else { return }
        if !Features.passes, case .ticket = link { return }
        if !Features.passes, case .tripTickets = link { return }
        // The default ticket link opens the Tickets tab.
        if case .ticket(nil) = link { tab = .tickets; deepLink = nil; return }
        switch link {
        case .trip, .route: tab = .plan
        case .stop: tab = .nearby
        case .ticket, .tripTickets, .go: break
        case .place(let name, let lat, let lon):
            // Shared from Google Maps or another map app: the Plan tab with routes there from here, leaving now.
            deepLink = nil
            if let lat, let lon {
                routeShared(to: SavedPlace(id: "shared|\(lat),\(lon)", name: name, subtitle: nil, lat: lat, lon: lon, stopId: nil))
            } else {
                routeShared(to: nil)
                Task {
                    if let m = try? await TransitousClient.shared.geocode(name, near: nil).first {
                        routeShared(to: SavedPlace(match: m))
                    } else {
                        plan.showError(L10n.f("No place found for “%@”.", name))
                    }
                }
            }
            return
        }
        deepLink = link
    }

    /// A place shared from another app: always the Plan tab, from the current location, leaving now,
    /// with nothing left open on top (settings, place search, "Where to now?").
    /// `nil` while the place is still being looked up: the tab is already there.
    func routeShared(to place: SavedPlace?) {
        dismissToken &+= 1
        showGoNow = false
        tab = .plan
        guard let place else { return }
        plan.from = .currentLocation
        plan.to = .place(place)
        plan.source = .shared
        plan.timeMode = .now
        plan.date = .now
        plan.searchStarted = true
    }

    /// Routes from the current location to `place`, leaving now (Nearby → "Where to now?").
    func goNow(to place: SavedPlace, source: RecentSearch.Source = .map) {
        goNow.from = .currentLocation
        goNow.to = .place(place)
        goNow.source = source
        goNow.timeMode = .now
        tab = .nearby
        showGoNow = true
    }

    /// Route filters apply to Plan and to "Where to now?" alike, and are kept for the next launch.
    var routeOptions: RouteOptions { plan.options }

    func setRouteOptions(_ options: RouteOptions) {
        plan.options = options
        goNow.options = options
        options.save()
    }

    func route(to place: SavedPlace, source: RecentSearch.Source = .map) {
        if plan.from == .place(place) { plan.from = .currentLocation }
        plan.to = .place(place)
        plan.source = source
        tab = .plan
    }

    /// Plan tab from one stop to another (a stop picked on a line's trip).
    func route(from start: SavedPlace, to place: SavedPlace, source: RecentSearch.Source = .line) {
        plan.from = .place(start)
        plan.to = .place(place)
        plan.source = source
        tab = .plan
    }

    /// Plan tab with `place` as the start; the destination stays unless it is the same place.
    func route(from place: SavedPlace, source: RecentSearch.Source = .map) {
        if plan.to == .place(place) { plan.to = nil }
        plan.source = source
        plan.from = .place(place)
        tab = .plan
    }

    /// A shared route that can no longer be fetched: the same places in the Plan tab, at the shared time
    /// if it is still ahead, otherwise now.
    func searchAgain(_ r: SharedRoute, now: Date = .now) {
        let key = "\(Int(r.departure.timeIntervalSince1970))"
        plan.from = .place(SavedPlace(id: "shared-from-\(key)", name: r.fromName, subtitle: nil, lat: r.from.lat, lon: r.from.lon, stopId: nil))
        plan.to = .place(SavedPlace(id: "shared-to-\(key)", name: r.toName, subtitle: nil, lat: r.to.lat, lon: r.to.lon, stopId: nil))
        plan.timeMode = .departAt
        plan.date = max(r.departure, now)
        plan.searchStarted = true
        deepLink = nil
        tab = .plan
    }

    /// Preset state for UI tests and store screenshots (`PUBLIT_DEMO=munich`).
    func applyDemo(_ name: String?) {
        guard name == "munich" else { return }
        plan.from = .place(SavedPlace(id: "demo-marienplatz", name: "Marienplatz", subtitle: "München",
                                      lat: 48.1372, lon: 11.5755, stopId: nil))
        plan.to = .place(SavedPlace(id: "demo-muc", name: "Flughafen München", subtitle: nil,
                                    lat: 48.3537, lon: 11.7861, stopId: nil))
        plan.searchStarted = true
    }
}
