import SwiftUI

struct DepartureRow: View {
    let stopTime: StopTime

    #if os(Android)

    var typeSize: DynamicTypeSize { AndroidEnvironment.dynamicTypeSize }

    #else

    @Environment(\.dynamicTypeSize) var typeSize

    #endif
    var body: some View {
        // Countdowns are in whole minutes: all rows read one shared minute clock and redraw
        // together on the minute (no timeline per row).
        row(now: Ticker.minute.now)
    }

    private func row(now: Date) -> some View {
        Group {
            if typeSize.isAccessibilitySize {
                VStack(alignment: .leading, spacing: 10) {
                    identity
                    DirectionLabel(direction: stopTime.shownDirection, circular: stopTime.isCircular)
                    platform
                    serviceNote
                    time(now: now)
                }
            } else {
                HStack(alignment: .center, spacing: 12) {
                    VStack(alignment: .leading, spacing: 6) {
                        identity
                        DirectionLabel(direction: stopTime.shownDirection, circular: stopTime.isCircular)
                        platform
                        serviceNote
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    time(now: now)
                }
            }
        }
        .padding(.vertical, 6)
        .alignmentGuide(.listRowSeparatorLeading) { _ in 0 }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(accessibilityText)
        .accessibilityIdentifier("departureRow")
    }

    private var identity: some View {
        HStack(alignment: .firstTextBaseline, spacing: 8) {
            LineBadge(stopTime)
            if let detail = stopTime.serviceDetail {
                Text(verbatim: detail).font(.caption.weight(.semibold))
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
    }

    @ViewBuilder
    private var platform: some View {
        if let track = TransitLabels.clean(stopTime.place.track) {
            Text(L10n.f("Platform %@", track)).font(.caption).foregroundStyle(.secondary)
        }
    }

    @ViewBuilder
    private var serviceNote: some View {
        if stopTime.isReplacementBus { Label("Rail replacement bus", systemImage: "bus.fill").font(.caption.weight(.semibold)).foregroundStyle(Color.delay) }
        if let alert = stopTime.serviceAlerts.first {
            Label(alert.headerText, systemImage: "exclamationmark.triangle.fill")
                .font(.caption).foregroundStyle(alert.priority == 0 ? Color.danger : Color.delay)
                .fixedSize(horizontal: false, vertical: true)
        }
    }

    private func time(now: Date) -> some View {
        DepartureTimeView(actual: stopTime.departureTime, delayMinutes: stopTime.delayMinutes,
                          realTime: stopTime.realTime && !Connectivity.shared.isOffline,
                          cancelled: stopTime.isCancelled, tz: stopTime.place.tz, now: now)
    }

    private var accessibilityText: String {
        var parts = ["\(ModeStyle.label(stopTime.mode)) \(stopTime.lineName)"]
        if let detail = stopTime.serviceDetail { parts.append(detail) }
        if stopTime.isCircular { parts.append(L10n.t("Circle line")) }
        else if let direction = stopTime.directionName { parts.append(L10n.f("Towards %@", direction)) }
        else { parts.append(L10n.t("Direction unavailable")) }
        if stopTime.isReplacementBus { parts.append(L10n.t("Rail replacement bus")) }
        parts.append(contentsOf: stopTime.serviceAlerts.map(\.headerText))
        parts.append(L10n.f("at %@", Fmt.time(stopTime.departureTime, tz: stopTime.place.tz)))
        if stopTime.isCancelled { parts.append(L10n.t("cancelled")) }
        else if stopTime.realTime {
            parts.append(stopTime.delayMinutes > 0 ? L10n.f("live, %lld minutes late", stopTime.delayMinutes) : L10n.t("live, on time"))
        }
        else { parts.append(L10n.t("timetable time, no live data")) }
        if let track = stopTime.place.track, !track.isEmpty { parts.append(L10n.f("platform %@", track)) }
        return parts.joined(separator: ", ")
    }
}
