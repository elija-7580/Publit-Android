import SwiftUI

import SkipFuse

/// A logger for the Publit module.
let logger: Logger = Logger(subsystem: "app.publit", category: "Publit")

/// Android: the app's root, loaded by `MainActivity` (Android/app/src/main/kotlin/Main.kt).
/// It replaces the iOS `@main struct PublitApp: App` of the iOS app one to one: the same stores,
/// environments, launch steps and deep links.
/* SKIP @bridge */public struct PublitRootView: View {
    @State var app = AppState.shared
    @State var location = LocationManager.shared
    @State var favorites = FavoritesStore()
    @State var trips = TripStore()
    @State var tickets = TicketStore()
    @State var passes = PassStore()
    @State var history = SearchHistoryStore.shared

    /* SKIP @bridge */public init() {
        PublitAppDelegate.shared.startOnce()
    }

    @ViewBuilder
    private var appContent: some View {
        RootView()
    }

    public var body: some View {
        appContent
            .environment(app)
            .environment(location)
            .environment(favorites)
            .environment(trips)
            .environment(tickets)
            .environment(passes)
            .environment(history)
            .tint(Color.ink)
            .toggleStyle(InkSwitchStyle())
            .onOpenURL { app.open($0) }
            .onAppear {
                if UserDefaults.standard.string(forKey: StartTab.defaultsKey) == StartTab.plan.rawValue {
                    app.tab = .plan
                }
                // First launch: the onboarding asks for the location with a reason first.
                if !OnboardingView.needed { location.start() }
                // Tickets of the first ticket version become passes (once per ticket).
                passes.importLegacy(tickets.tickets, imageDirectory: tickets.imageDirectory)
                NotificationRouter.shared.open = { [app] url in app.open(url) }
                IntentBridge.connect { [app] url in app.open(url) }
                PublitShortcuts.updateAppShortcutParameters()
            }
            .onChange(of: favorites.stops.count) { PublitShortcuts.updateAppShortcutParameters() }
    }
}

/// Android application callbacks (from `AndroidAppMain`/`MainActivity` in Main.kt).
/* SKIP @bridge */public final class PublitAppDelegate: @unchecked Sendable {
    /* SKIP @bridge */public static let shared = PublitAppDelegate()
    private var started = false

    private init() {}

    func startOnce() {
        guard !started else { return }
        started = true
        HangLog.shared.start()
        CrashReports.shared.start()
        // Offline copies older than a week are no use for departures or routes.
        DispatchQueue.global(qos: .utility).async { TransitousClient.shared.cache.prune(olderThan: 7 * 24 * 3600) }
    }

    /* SKIP @bridge */public func onInit() { startOnce() }
    /* SKIP @bridge */public func onLaunch() {}
    /* SKIP @bridge */public func onResume() {}
    /* SKIP @bridge */public func onPause() {}
    /* SKIP @bridge */public func onStop() {}
    /* SKIP @bridge */public func onDestroy() {}
    /* SKIP @bridge */public func onLowMemory() {
        HangLog.shared.report("MEMORY", "Android warned that memory is low.")
        TransitousClient.shared.releaseMemory(background: false)
    }

    /// Android intent with a URL (publit:// deep links, shared files, notification taps).
    /* SKIP @bridge */public func onOpenURL(_ url: String) {
        guard let u = URL(string: url) else { return }
        Task { @MainActor in AppState.shared.open(u) }
    }

    /// WorkManager background check of saved lines (the iOS `.backgroundTask(.appRefresh)`).
    /* SKIP @bridge */public func backgroundRefresh() async {
        await LineAlertMonitor.check(minInterval: 10 * 60)
    }
}

struct RootView: View {
    @Environment(AppState.self) var app
    @Environment(TripStore.self) var trips
    @Environment(PassStore.self) var passes
    @Environment(FavoritesStore.self) var favorites
    @Environment(\.scenePhase) var scenePhase
    #if os(Android)
    var dynamicTypeSize: DynamicTypeSize { AndroidEnvironment.dynamicTypeSize }
    #else
    @Environment(\.dynamicTypeSize) var dynamicTypeSize
    #endif
    @Environment(LocationManager.self) var location
    /// The launch animation, while the first departures load underneath.
    @State var splash = true
    @State var onboarding = OnboardingView.needed
    @AppStorage(OnboardingView.doneKey) var onboardingDone = false

    var body: some View {
        tabs
            .overlay {
                if splash {
                    LaunchSplash()
                        .allowsHitTesting(false)
                        .transition(.opacity)
                }
            }
            .task { await endSplash() }
            .fullScreenCover(isPresented: $onboarding) { OnboardingView() }
            .onChange(of: onboardingDone) { _, done in if done { onboarding = false } }
    }

    /// At least 0.7 s (the animation), at most 2 s, ended earlier by the first board.
    private func endSplash() async {
        let start = Date()
        try? await Task.sleep(for: .milliseconds(700))
        while !app.firstBoardReady, app.tab == .nearby, Date().timeIntervalSince(start) < 2 {
            try? await Task.sleep(for: .milliseconds(100))
        }
        withAnimation(.easeOut(duration: 0.3)) { splash = false }
    }

    @ViewBuilder
    private var tabs: some View {
        @Bindable var app = app
        TabView(selection: $app.tab) {
            NavigationStack { NearbyView() }
                .liveTripBar()
                .tabItem { Label("Nearby", systemImage: "location.circle") }
                .tag(AppTab.nearby)
            NavigationStack { PlanView() }
                .liveTripBar()
                .tabItem { Label("Plan", systemImage: "calendar") }
                .tag(AppTab.plan)
            NavigationStack { PassWalletView() }
                .liveTripBar()
                .tabItem { Label("Tickets", systemImage: "ticket") }
                .tag(AppTab.tickets)
            NavigationStack { FavoritesView() }
                .liveTripBar()
                .tabItem { Label("Saved", systemImage: "star") }
                .tag(AppTab.favorites)
        }
        .sheet(item: $app.sharedTicketFile) { file in
            PassImportView(sharedURL: file.url)
        }
        // Full screen, not a sheet: a sheet could still be swiped away while pulling the trip panel
        // down ("the live view is suddenly gone"). It closes with Minimize or Done only.
        .fullScreenCover(item: tripLink) { id in
            NavigationStack {
                SavedTripView(tripId: id.value)
                    .toolbar {
                        // A started trip keeps running when the screen closes: it moves down into the live bar.
                        if trips.activeTrip?.id == id.value {
                            ToolbarItem(placement: .topBarLeading) {
                                Button { app.deepLink = nil } label: {
                                    Label(L10n.t("Minimize"), systemImage: "chevron.down")
                                }
                                .accessibilityIdentifier("minimizeTrip")
                            }
                        } else {
                            ToolbarItem(placement: .confirmationAction) { Button("Done") { app.deepLink = nil } }
                        }
                    }
            }
            // Swiping the trip panel all the way down closed the whole screen ("the live trip
            // disappears"); a started trip closes with Minimize only.
            .interactiveDismissDisabled(trips.activeTrip?.id == id.value)
        }
        .sheet(item: ticketLink) { link in
            NavigationStack {
                Group {
                    switch link {
                    case .ticket(let id?):
                        if let pass = passes.pass(linkedTo: id) { PassDetailView(passId: pass.id) } else { TripPassesView() }
                    case .tripTickets(let tripId):
                        TripPassesView(tripId: tripId, at: trips.trip(tripId)?.startTime ?? .now)
                    default:
                        TripPassesView()
                    }
                }
                .toolbar {
                    ToolbarItem(placement: .confirmationAction) { Button("Done") { app.deepLink = nil } }
                }
            }
            .dynamicTypeSize(dynamicTypeSize)
        }
        .sheet(item: routeLink) { ref in
            NavigationStack {
                SharedRouteView(route: ref.route)
                    .toolbar {
                        ToolbarItem(placement: .confirmationAction) { Button("Done") { app.deepLink = nil } }
                    }
            }
        }
        .sheet(item: stopLink) { stop in
            NavigationStack {
                StopDeparturesView(stopId: stop.id, name: stop.name, coordinate: stop.coordinate)
                    .toolbar {
                        ToolbarItem(placement: .confirmationAction) { Button("Done") { app.deepLink = nil } }
                    }
            }
        }
        .onChange(of: app.deepLink) { _, link in
            guard case .go(let role) = link else { return }
            app.deepLink = nil
            if let place = favorites.place(for: role) {
                app.goNow(to: place, source: .favorite)
            } else {
                app.tab = .favorites // Home/Work not set yet: that is where it is set.
            }
        }
        .onChange(of: app.tab) { _, tab in HangLog.shared.note("tab \(tab)") }
        .task(id: scenePhase) {
            switch scenePhase {
            case .active: HangLog.shared.note("app active"); HangLog.shared.scene(active: true)
            case .inactive: HangLog.shared.note("app inactive")
            case .background:
                HangLog.shared.note("app background"); HangLog.shared.scene(active: false)
                trips.flushPendingSave()
                TransitousClient.shared.releaseMemory(background: true)
            @unknown default: break
            }
            if scenePhase == .background {
                LineAlertMonitor.scheduleBackgroundCheck()
                location.pause()
                // Nothing updates a Live Activity without the app: it goes by itself after the arrival.
                await LiveTripActivity.handOver { trips.trip($0) }
            }
            // Keep running Live Activities current while the app is open.
            guard scenePhase == .active else { return }
            location.start()
            for trip in await LiveTripActivity.resume({ trips.trip($0) }) {
                LiveTripActivity.start(trip, hasTicket: !passes.passes(forTrip: trip.id, at: trip.startTime).isEmpty)
            }
            await LineAlertMonitor.check()
            while !Task.isCancelled {
                let wait = await refreshLiveTrips()
                try? await Task.sleep(for: .seconds(wait))
            }
        }
    }

    private struct ID: Identifiable { let value: String; var id: String { value } }

    private struct StopRef: Identifiable {
        let id: String, name: String, coordinate: LatLon
    }

    private var stopLink: Binding<StopRef?> {
        Binding(get: {
            if case .stop(let id, let name, let lat, let lon) = app.deepLink {
                return StopRef(id: id, name: name, coordinate: LatLon(lat: lat, lon: lon))
            }
            return nil
        }, set: { if $0 == nil { app.deepLink = nil } })
    }

    private struct RouteRef: Identifiable { let route: SharedRoute; var id: String { route.query } }

    private var routeLink: Binding<RouteRef?> {
        Binding(get: {
            if case .route(let r) = app.deepLink { return RouteRef(route: r) }
            return nil
        }, set: { if $0 == nil { app.deepLink = nil } })
    }

    private var tripLink: Binding<ID?> {
        Binding(get: {
            if case .trip(let id) = app.deepLink { return ID(value: id) }
            return nil
        }, set: { if $0 == nil { app.deepLink = nil } })
    }

    private var ticketLink: Binding<DeepLink?> {
        Binding(get: {
            switch app.deepLink {
            case .ticket, .tripTickets: return Features.passes ? app.deepLink : nil
            default: return nil
            }
        }, set: { if $0 == nil { app.deepLink = nil } })
    }

    /// Refreshes the trips with a running Live Activity; returns the seconds until the next refresh.
    private func refreshLiveTrips() async -> TimeInterval {
        let now = Date()
        for trip in trips.upcoming(now: now.addingTimeInterval(-15 * 60)) where LiveTripActivity.isRunning(trip.id) || trips.activeTripId == trip.id {
            if let fresh = try? await TripRefreshCoordinator.shared.refresh(trip.itinerary.id) {
                trips.update(trip.id, with: fresh)
                if let updated = trips.trip(trip.id) {
                    await LeaveReminder.follow(trip, to: updated)
                    await GetOffReminder.follow(from: trip.itinerary, to: updated.itinerary)
                    await TripChangeAlert.follow(trip.id, from: trip.itinerary, to: updated.itinerary)
                    await ArrivalUpdates.follow(updated)
                }
            }
            if let t = trips.trip(trip.id) { await LiveTripActivity.update(t) }
        }
        // Trips with a leave reminder that start within 8 hours get their Live Activity now.
        for trip in trips.upcoming(now: now) where !LiveTripActivity.isRunning(trip.id) {
            if trips.wantsLiveActivity(trip.id), trip.startTime.timeIntervalSince(now) < LiveTripActivity.maxLead {
                LiveTripActivity.start(trip, hasTicket: !passes.passes(forTrip: trip.id, at: trip.startTime).isEmpty)
            }
        }
        let live = trips.upcoming(now: Date().addingTimeInterval(-15 * 60)).filter { LiveTripActivity.isRunning($0.id) || trips.activeTripId == $0.id }
        return LiveRefresh.interval(live.map(\.itinerary))
    }
}
