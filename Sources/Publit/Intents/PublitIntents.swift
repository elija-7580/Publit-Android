#if canImport(AppIntents)
import AppIntents
#endif
import SwiftUI

/// Passes Siri/Shortcuts actions to the running app (or holds them until it has launched).
@MainActor
enum IntentBridge {
    private static var handler: ((URL) -> Void)?
    private static var pending: URL?

    static func connect(_ open: @escaping (URL) -> Void) {
        handler = open
        if let url = pending { pending = nil; open(url) }
    }

    static func open(_ url: URL) {
        if let handler { handler(url) } else { pending = url }
    }
}

#if !os(Android)
/// "Next departures at Marienplatz": spoken answer plus a small board.
struct NextDeparturesIntent: AppIntent {
    static var title: LocalizedStringResource = "Next departures"
    static var description = IntentDescription("Tells you the next departures at a stop.")

    @Parameter(title: "Stop")
    var stop: StopEntity

    static var parameterSummary: some ParameterSummary {
        Summary("Next departures at \(\.$stop)")
    }

    func perform() async throws -> some IntentResult & ProvidesDialog & ShowsSnippetView {
        let res = try await TransitousClient.shared.departures(stopId: stop.stopId, count: 30,
                                                               radius: StopDeparturesView.stationRadius)
        let list = Array(StopTime.upcoming(res.stopTimes).prefix(4))
        var text = DepartureSpeech.sentence(list, stopName: stop.name)
        if let from = await Connectivity.shared.offlineDataFrom {
            text = L10n.f("Offline. Showing data from %@.", Fmt.time(from)) + " " + text
        }
        return .result(dialog: "\(text)", view: DeparturesSnippet(departures: list))
    }
}

enum DepartureSpeech {
    static func sentence(_ list: [StopTime], stopName: String, now: Date = .now) -> String {
        guard !list.isEmpty else { return L10n.f("No departures from %@ in the next hour.", stopName) }
        let parts = list.prefix(3).map { st -> String in
            let mins = Int(floor(st.departureTime.timeIntervalSince(now) / 60))
            let identity = st.serviceDetail ?? st.lineName
            let dest = st.shownDirection.map { L10n.f("%@ to %@", identity, $0) } ?? identity
            if st.isCancelled { return L10n.f("%@ is cancelled", dest) }
            return mins <= 0 ? L10n.f("%@ now", dest) : L10n.f("%@ in %lld min", dest, mins)
        }
        return L10n.f("At %@: %@.", stopName, parts.joined(separator: ", "))
    }
}

struct DeparturesSnippet: View {
    let departures: [StopTime]

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            ForEach(departures) { st in
                HStack(spacing: 8) {
                    LineBadge(st)
                    VStack(alignment: .leading, spacing: 4) {
                        if let detail = st.serviceDetail { Text(verbatim: detail).font(.caption) }
                        DirectionLabel(direction: st.shownDirection, circular: st.isCircular)
                    }
                    Spacer()
                    Text(Fmt.time(st.departureTime, tz: st.place.tz))
                        .monospacedDigit()
                        .foregroundStyle(st.realTime ? Color.live : .primary)
                        .strikethrough(st.isCancelled)
                }
                .font(.subheadline)
            }
        }
        .padding()
    }
}

/// "Take me home": opens Nearby with the best ways home, leaving now.
struct GoHomeIntent: AppIntent {
    static var title: LocalizedStringResource = "Route home"
    static var description = IntentDescription("Shows the best ways home from where you are, leaving now.")
    static var openAppWhenRun = true

    @MainActor
    func perform() async throws -> some IntentResult {
        IntentBridge.open(DeepLink.go(.home).url)
        return .result()
    }
}

struct GoToWorkIntent: AppIntent {
    static var title: LocalizedStringResource = "Route to work"
    static var description = IntentDescription("Shows the best ways to work from where you are, leaving now.")
    static var openAppWhenRun = true

    @MainActor
    func perform() async throws -> some IntentResult {
        IntentBridge.open(DeepLink.go(.work).url)
        return .result()
    }
}

/// "Show my ticket": opens the valid pass full screen at maximum brightness.
struct ShowTicketIntent: AppIntent {
    static var title: LocalizedStringResource = "Show ticket"
    static var description = IntentDescription("Opens your ticket full screen.")
    static var openAppWhenRun = true

    @MainActor
    func perform() async throws -> some IntentResult {
        IntentBridge.open(DeepLink.ticket(nil).url)
        return .result()
    }
}

struct PublitShortcuts: AppShortcutsProvider {
    static var appShortcuts: [AppShortcut] {
        AppShortcut(intent: NextDeparturesIntent(), phrases: [
            "Next departures in \(.applicationName)",
            "Next departures at \(\.$stop) in \(.applicationName)",
            "Departures at \(\.$stop) in \(.applicationName)",
        ], shortTitle: "Next departures", systemImageName: "tram")
        AppShortcut(intent: GoHomeIntent(), phrases: [
            "Take me home with \(.applicationName)",
            "Route home in \(.applicationName)",
        ], shortTitle: "Route home", systemImageName: "house")
        AppShortcut(intent: GoToWorkIntent(), phrases: [
            "Route to work in \(.applicationName)",
        ], shortTitle: "Route to work", systemImageName: "briefcase")
        // "Show ticket" returns with the ticket feature (Features.tickets).
    }
}
#else
/// Android: the iOS Siri shortcuts become launcher shortcuts (long-press the app icon):
/// route home, route to work and the departures of the first saved stops.
enum PublitShortcuts {
    @MainActor static func updateAppShortcutParameters() {
        var items: [[String: String]] = [
            ["id": "home", "label": L10n.t("Route home"), "url": DeepLink.go(.home).url.absoluteString, "icon": "home"],
            ["id": "work", "label": L10n.t("Route to work"), "url": DeepLink.go(.work).url.absoluteString, "icon": "work"],
        ]
        for stop in StopQuery.savedStops().prefix(2) {
            items.append(["id": "stop-" + stop.stopId, "label": stop.name, "url": stop.deepLink.absoluteString, "icon": "stop"])
        }
        guard let data = try? JSONSerialization.data(withJSONObject: items), let json = String(data: data, encoding: .utf8) else { return }
        Droid.call("setShortcuts", json)
    }
}
#endif
