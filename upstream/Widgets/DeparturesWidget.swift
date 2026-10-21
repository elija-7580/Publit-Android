import AppIntents
import SwiftUI
import WidgetKit

/// Widget settings: which stop to show.
struct DeparturesWidgetIntent: WidgetConfigurationIntent {
    static var title: LocalizedStringResource = "Departures"
    static var description = IntentDescription("Next departures at a stop.")

    @Parameter(title: "Stop")
    var stop: StopEntity?
}

struct WidgetDeparture: Identifiable, Hashable, RouteStyled {
    let id: String
    let lineName: String
    let mode: String
    let background: String
    let foreground: String
    let service: String?
    var routeShortName: String? { lineName }
    var displayName: String? { service }
    var routeColor: String? { background }
    var routeTextColor: String? { foreground }
    let headsign: String
    let circular: Bool
    let time: Date
    let delayMinutes: Int
    let realTime: Bool
    let cancelled: Bool
    let tz: String?

    init(_ st: StopTime) {
        id = st.id
        lineName = st.lineName
        mode = st.mode
        background = st.lineColors.background
        foreground = st.lineColors.text
        service = st.serviceDetail
        headsign = st.shownDirection ?? ""
        circular = st.isCircular
        time = st.departureTime
        delayMinutes = st.delayMinutes
        realTime = st.realTime
        cancelled = st.isCancelled
        tz = st.place.tz
    }
}

struct DeparturesEntry: TimelineEntry {
    enum State { case ok, needsStop, failed }
    let date: Date
    let stop: StopEntity?
    let departures: [WidgetDeparture]
    var state: State = .ok
    var savedDataFrom: Date? = nil

    /// Departures not yet gone at this entry's time.
    var upcoming: [WidgetDeparture] { departures.filter { $0.time > date.addingTimeInterval(-30) } }
}

struct DeparturesProvider: AppIntentTimelineProvider {
    func placeholder(in context: Context) -> DeparturesEntry {
        DeparturesEntry(date: .now, stop: nil, departures: [])
    }

    func snapshot(for configuration: DeparturesWidgetIntent, in context: Context) async -> DeparturesEntry {
        guard let stop = configuration.stop else { return DeparturesEntry(date: .now, stop: nil, departures: [], state: .needsStop) }
        return await load(stop, now: .now)
    }

    func timeline(for configuration: DeparturesWidgetIntent, in context: Context) async -> Timeline<DeparturesEntry> {
        let now = Date()
        guard let stop = configuration.stop else {
            return Timeline(entries: [DeparturesEntry(date: now, stop: nil, departures: [], state: .needsStop)], policy: .never)
        }
        let first = await load(stop, now: now)
        guard first.state == .ok else {
            return Timeline(entries: [first], policy: .after(now.addingTimeInterval(10 * 60)))
        }
        // One entry per minute so the countdowns move; WidgetKit asks again after 15 minutes.
        let entries = (0..<20).map { i in
            DeparturesEntry(date: now.addingTimeInterval(Double(i) * 60), stop: stop, departures: first.departures, savedDataFrom: first.savedDataFrom)
        }
        return Timeline(entries: entries, policy: .after(now.addingTimeInterval(15 * 60)))
    }

    private func load(_ stop: StopEntity, now: Date) async -> DeparturesEntry {
        do {
            let res = try await TransitousClient.shared.departures(stopId: stop.stopId, count: 30, radius: 150)
            let list = StopTime.upcoming(res.stopTimes, now: now).prefix(12).map(WidgetDeparture.init)
            let from = await Connectivity.shared.offlineDataFrom
            return DeparturesEntry(date: now, stop: stop, departures: Array(list), savedDataFrom: from)
        } catch {
            return DeparturesEntry(date: now, stop: stop, departures: [], state: .failed)
        }
    }
}

struct DeparturesWidget: Widget {
    var body: some WidgetConfiguration {
        AppIntentConfiguration(kind: "departures", intent: DeparturesWidgetIntent.self, provider: DeparturesProvider()) { entry in
            DeparturesWidgetView(entry: entry)
                .containerBackground(.fill.tertiary, for: .widget)
                .widgetURL(entry.stop?.deepLink)
        }
        .configurationDisplayName("Departures")
        .description("Next departures at a stop of your choice.")
        .supportedFamilies([.systemSmall, .systemMedium, .systemLarge, .accessoryRectangular])
    }
}

struct DeparturesWidgetView: View {
    let entry: DeparturesEntry
    @Environment(\.widgetFamily) private var systemFamily
    var previewFamily: WidgetFamily? = nil
    private var family: WidgetFamily { previewFamily ?? systemFamily }

    private var rows: Int {
        switch family {
        case .systemSmall: return 2
        case .systemMedium: return 2
        case .systemLarge: return 5
        default: return 2
        }
    }

    var body: some View {
        switch entry.state {
        case .needsStop:
            message(L10n.t("Choose a stop"), detail: "Touch and hold the widget, then Edit Widget.", symbol: "tram")
        case .failed where entry.departures.isEmpty:
            message(entry.stop?.name ?? "", detail: "Departures unavailable", symbol: "wifi.slash")
        default:
            if family == .accessoryRectangular { compact } else { board }
        }
    }

    private var board: some View {
        VStack(alignment: .leading, spacing: family == .systemSmall ? 6 : 7) {
            Text(entry.stop?.name ?? "")
                .font(.caption.weight(.semibold))
                .foregroundStyle(.secondary)
                .lineLimit(1)
            let list = entry.upcoming.prefix(rows)
            if list.isEmpty {
                Text("No departures soon").font(.subheadline).foregroundStyle(.secondary)
            }
            ForEach(list) { d in row(d) }
            if let from = entry.savedDataFrom {
                Text("Saved data: \(Fmt.time(from))").font(.caption2).foregroundStyle(.secondary).lineLimit(1)
            }
            Spacer(minLength: 0)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
    }

    private func row(_ d: WidgetDeparture) -> some View {
        VStack(alignment: .leading, spacing: 3) {
            HStack(spacing: 6) {
                LineBadge(d, compact: true, detailed: true)
                Spacer(minLength: 2)
                Text(Fmt.time(d.time, tz: d.tz))
                    .font(.caption.weight(.semibold).monospacedDigit())
                    .strikethrough(d.cancelled)
                    .foregroundStyle(d.cancelled ? Color.danger : Color.primary)
                    .lineLimit(1).fixedSize()
            }
            DirectionLabel(direction: d.headsign, circular: d.circular, font: .caption)
                .lineLimit(2)
        }
    }

    private var compact: some View {
        VStack(alignment: .leading, spacing: 3) {
            Text(entry.stop?.name ?? "").font(.caption.weight(.semibold)).lineLimit(1)
            if let d = entry.upcoming.first {
                HStack(spacing: 4) {
                    Text(verbatim: d.serviceDetail ?? d.lineName).font(.caption.weight(.bold))
                    Text(Fmt.time(d.time, tz: d.tz)).font(.caption.monospacedDigit())
                        .strikethrough(d.cancelled)
                }
                DirectionLabel(direction: d.headsign, circular: d.circular, font: .caption2).lineLimit(1)
            }
        }
    }

    private func message(_ title: String, detail: LocalizedStringKey, symbol: String) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Image(systemName: symbol).font(.title3).foregroundStyle(.secondary)
            Text(title).font(.subheadline.weight(.semibold)).lineLimit(2)
            Text(detail).font(.caption).foregroundStyle(.secondary).lineLimit(3)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
    }
}

#Preview(as: .systemMedium) {
    DeparturesWidget()
} timeline: {
    DeparturesEntry(date: .now, stop: StopEntity(stopId: "x", name: "Marienplatz", lat: 48.137, lon: 11.575), departures: [])
}
