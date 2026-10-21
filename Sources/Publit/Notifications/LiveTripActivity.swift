import Foundation
#if canImport(ActivityKit)
import ActivityKit
#endif

/// Starts, updates and ends the Live Activity of a saved trip.
/// Updates happen when the app refreshes the trip (see `LiveRefresh`), and only when something
/// visible changed; the countdown itself runs on the lock screen without updates.
@MainActor
enum LiveTripActivity {
    /// iOS keeps a Live Activity for up to 8 hours.
    static let maxLead: TimeInterval = 8 * 3600

    static var isAvailable: Bool { ActivityAuthorizationInfo().areActivitiesEnabled }

    /// How long "Arrived" stays on the lock screen after the arrival.
    static let afterArrival: TimeInterval = 3 * 60
    /// When the app goes to the background, the activity is ended with this much room after the
    /// expected arrival: without the app nothing updates it any more, and iOS would otherwise
    /// keep a finished trip on the lock screen for hours.
    static let backgroundGrace: TimeInterval = 5 * 60

    /// The running (not ended) activity of a trip.
    static func current(_ tripId: String) -> Activity<TripActivityAttributes>? {
        Activity<TripActivityAttributes>.activities.first {
            $0.attributes.tripId == tripId && ($0.activityState == .active || $0.activityState == .stale)
        }
    }

    static func isOver(_ trip: SavedTrip, now: Date = .now) -> Bool {
        now >= trip.endTime.addingTimeInterval(afterArrival)
    }

    static func isRunning(_ tripId: String) -> Bool { current(tripId) != nil }

    static func state(for trip: SavedTrip, now: Date = .now) -> TripActivityAttributes.ContentState {
        let s = TripStatus.at(now, trip.itinerary, countdown: false)
        let legs = trip.itinerary.legs
        let leg = legs.first { $0.isTransit && $0.endTime > now } ?? trip.itinerary.firstTransitLeg
        // The departure whose platform matters: the vehicle not yet left, else the next one.
        let riding = leg.map { $0.startTime <= now } ?? false
        let nextIndex = legs.firstIndex { $0.isTransit && $0.startTime > now }
        let departing = nextIndex.map { legs[$0] }
        var state = TripActivityAttributes.ContentState(
            phase: s.phase.rawValue, headline: s.headline, detail: s.detail, nextEvent: s.nextEvent,
            delayMinutes: s.delayMinutes, arrival: trip.endTime, lineName: leg?.lineName,
            lineColor: leg?.lineColors.background, lineTextColor: leg?.lineColors.text, updatedAt: now)
        if let departing, s.phase != .cancelled, s.phase != .arrived {
            state.platform = TransitLabels.clean(departing.from.track).map { L10n.f("Pl. %@", $0) }
            state.platformChanged = departing.from.platformChange != nil ? true : nil
            if riding {
                state.then = L10n.f("Then: %@", L10n.f("%@ at %@", departing.lineName, Fmt.time(departing.startTime, tz: departing.from.tz)))
            }
        }
        return state
    }

    enum StartResult { case started, tooEarly, unavailable, failed(String) }

    @discardableResult
    static func start(_ trip: SavedTrip, hasTicket: Bool, now: Date = .now) -> StartResult {
        guard isAvailable else { return .unavailable }
        guard trip.startTime.timeIntervalSince(now) < maxLead else { return .tooEarly }
        if let running = current(trip.id) {
            Task { await running.update(content(trip, now: now)) }
            return .started
        }
        let attrs = TripActivityAttributes(tripId: trip.id, title: trip.title, hasTicket: hasTicket)
        do {
            _ = try Activity.request(attributes: attrs, content: content(trip, now: now), pushType: nil)
            return .started
        } catch {
            return .failed(error.localizedDescription)
        }
    }

    static func update(_ trip: SavedTrip, now: Date = .now) async {
        guard let a = current(trip.id) else { return }
        if now >= trip.endTime {
            // Arrived: shown briefly, then gone.
            let gone = trip.endTime.addingTimeInterval(afterArrival)
            await a.end(content(trip, now: now), dismissalPolicy: gone > now ? .after(gone) : .immediate)
        } else {
            let new = content(trip, now: now)
            // Same text, times and colours: no redraw (it costs battery on the lock screen).
            guard !same(a.content.state, new.state) else { return }
            await a.update(new)
        }
    }

    static func end(_ tripId: String) async {
        for a in Activity<TripActivityAttributes>.activities where a.attributes.tripId == tripId {
            await a.end(nil, dismissalPolicy: .immediate)
        }
    }

    /// App goes to the background: each running activity ends now, with its last state, and
    /// disappears by itself shortly after the arrival. Its countdown keeps running on the lock screen.
    /// Activities of removed trips and of trips already over go at once.
    static func handOver(_ lookup: (String) -> SavedTrip?, now: Date = .now) async {
        for a in Activity<TripActivityAttributes>.activities where a.activityState == .active || a.activityState == .stale {
            guard let trip = lookup(a.attributes.tripId), !isOver(trip, now: now) else {
                await a.end(nil, dismissalPolicy: .immediate)
                continue
            }
            let gone = trip.endTime.addingTimeInterval(now >= trip.endTime ? afterArrival : backgroundGrace)
            await a.end(content(trip, now: now), dismissalPolicy: .after(gone))
        }
    }

    /// App back in the foreground: activities handed over in the background are replaced by
    /// running ones again (so live times, delays and platforms update), finished ones removed.
    /// Returns the trips whose activity has to be started again.
    static func resume(_ lookup: (String) -> SavedTrip?, now: Date = .now) async -> [SavedTrip] {
        var restart: [SavedTrip] = []
        for a in Activity<TripActivityAttributes>.activities {
            let trip = lookup(a.attributes.tripId)
            switch a.activityState {
            case .active, .stale:
                if trip.map({ isOver($0, now: now) }) ?? true { await a.end(nil, dismissalPolicy: .immediate) }
            case .ended:
                await a.end(nil, dismissalPolicy: .immediate)
                if let trip, now < trip.endTime, !restart.contains(where: { $0.id == trip.id }) { restart.append(trip) }
            default:
                break
            }
        }
        return restart
    }

    static func same(_ a: TripActivityAttributes.ContentState, _ b: TripActivityAttributes.ContentState) -> Bool {
        var b = b
        b.updatedAt = a.updatedAt
        return a == b
    }

    private static func content(_ trip: SavedTrip, now: Date) -> ActivityContent<TripActivityAttributes.ContentState> {
        ActivityContent(state: state(for: trip, now: now), staleDate: trip.endTime.addingTimeInterval(afterArrival))
    }
}
