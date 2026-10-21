import SwiftUI

struct LineBadge: View {
    let name: String
    let mode: String
    let color: Color
    let textColor: Color
    let compact: Bool
    /// Raw background, for the edge check in dark and light mode.
    let hex: String
    @Environment(\.colorScheme) var scheme

    init(_ route: some RouteStyled, compact: Bool = false, detailed: Bool = false) {
        self.compact = compact
        name = detailed ? (route.serviceDetail ?? route.lineName) : route.lineName
        mode = route.mode
        color = route.lineColor
        textColor = route.lineTextColor
        hex = route.lineColors.background
    }

    var body: some View {
        HStack(spacing: 4) {
            Image(systemName: ModeStyle.symbol(mode))
                .font(.caption.weight(.semibold))
                .accessibilityHidden(true)
            Text(verbatim: name)
                .font(compact ? .caption.weight(.bold) : .subheadline.weight(.bold))
                .lineLimit(2)
                .fixedSize(horizontal: false, vertical: true)
        }
        .padding(.horizontal, compact ? 6 : 8)
        .padding(.vertical, compact ? 2 : 4)
        .frame(minWidth: 44)
        .foregroundStyle(textColor)
        .background(color, in: RoundedRectangle(cornerRadius: 6, style: .continuous))
        // Near-black badges on dark backgrounds (and white ones on light) get a visible edge;
        // the others the usual hairline.
        .overlay {
            if LineColors.blendsIn(hex, dark: scheme == .dark) {
                RoundedRectangle(cornerRadius: 6).strokeBorder(Color.primary.opacity(0.45), lineWidth: 1)
            } else {
                RoundedRectangle(cornerRadius: 6).stroke(Color.primary.opacity(0.12), lineWidth: 0.5)
            }
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("\(ModeStyle.label(mode)) \(name)")
    }
}

/// Clock time is primary; countdown, delay and provenance are always secondary.
struct DepartureTimeView: View {
    let actual: Date
    let delayMinutes: Int
    let realTime: Bool
    let cancelled: Bool
    var tz: String? = nil

    var now: Date = .now

    var body: some View {
        let minutes = Int(ceil(actual.timeIntervalSince(now) / 60))
        // Ink & Dot: the minutes are the figure; clock time, delay and live state small underneath.
        VStack(alignment: .trailing, spacing: 3) {
            if cancelled {
                Text(Fmt.time(actual, tz: tz))
                    .font(.title3.weight(.bold).monospacedDigit())
                    .foregroundStyle(Color.danger)
                    .strikethrough(true, color: Color.danger)
                    .lineLimit(1)
                Text("Cancelled").font(.caption.weight(.semibold)).foregroundStyle(Color.danger)
            } else {
                if minutes <= 0 {
                    // Same size as the minute figures below it in the list, so "now" does not look smaller than "1 min".
                    Text("now").font(.title.weight(.bold))
                } else if minutes < 60 {
                    HStack(alignment: .firstTextBaseline, spacing: 2) {
                        Text(verbatim: "\(minutes)").font(.title.weight(.bold).monospacedDigit())
                            .foregroundStyle(delayMinutes > 0 ? Color.delay : Color.primary)
                        Text("min").font(.caption.weight(.medium)).foregroundStyle(.secondary)
                    }
                } else {
                    Text(Fmt.time(actual, tz: tz)).font(.title3.weight(.bold).monospacedDigit()).lineLimit(1)
                }
                HStack(spacing: 4) {
                    if delayMinutes > 0 {
                        Text(L10n.f("+%lld min", delayMinutes))
                            .font(.caption.weight(.semibold).monospacedDigit()).foregroundStyle(Color.delay)
                    }
                    if minutes < 60 || delayMinutes > 0 {
                        Text(Fmt.time(actual, tz: tz)).font(.caption.weight(.medium).monospacedDigit())
                            .foregroundStyle(.secondary)
                    }
                }
                HStack(spacing: 3) {
                    Image(systemName: realTime ? "dot.radiowaves.left.and.right" : "calendar")
                    Text(realTime ? L10n.t("Live") : L10n.t("Timetable"))
                }
                .font(.caption2.weight(.medium))
                .foregroundStyle(realTime ? Color.live : Color.secondary)
            }
        }
        .fixedSize(horizontal: true, vertical: true)
    }
}

struct AttributionFooter: View {
    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Link("Routing and timetables: Transitous and its data sources", destination: AppInfo.sourcesURL)
            Link("Map data © OpenStreetMap contributors", destination: AppInfo.osmCopyrightURL)
        }
        .font(.footnote)
    }
}
