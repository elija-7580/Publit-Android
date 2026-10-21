import SwiftUI

/// Where to go, at a glance: the stop (and platform) to get on at, for a quick look before leaving.
struct BoardingLine: View {
    let itinerary: Itinerary

    var body: some View {
        if let leg = itinerary.firstTransitLeg {
            let walk = itinerary.legs.prefix { !$0.isTransit }.reduce(0) { $0 + $1.duration }
            HStack(alignment: .firstTextBaseline, spacing: 6) {
                Image(systemName: walk >= 60 ? "figure.walk" : "mappin.and.ellipse")
                    .foregroundStyle(.secondary)
                    .accessibilityHidden(true)
                Text(verbatim: Self.text(leg: leg, walkSeconds: walk))
                    .lineLimit(2)
            }
            .font(.footnote.weight(.medium))
            .foregroundStyle(.secondary)
            .accessibilityIdentifier("boardingLine")
        }
    }

    /// "Walk to Hauptbahnhof · Pl. 3 · 4 min" or "Board at Hauptbahnhof · Pl. 3".
    static func text(leg: Leg, walkSeconds: Int) -> String {
        var parts = [walkSeconds >= 60 ? L10n.f("Walk to %@", leg.from.name) : L10n.f("Board at %@", leg.from.name)]
        if let track = TransitLabels.clean(leg.from.track) { parts.append(L10n.f("Pl. %@", track)) }
        if walkSeconds >= 60 { parts.append(Fmt.duration(seconds: walkSeconds)) }
        return parts.joined(separator: " · ")
    }
}

/// Every boarding and the final stop in a few lines (route detail, above the full steps):
/// time, line, stop and platform, direction.
struct BoardingSteps: View {
    let itinerary: Itinerary

    var body: some View {
        let legs = itinerary.transitLegs
        if !legs.isEmpty {
            VStack(alignment: .leading, spacing: 10) {
                ForEach(Array(legs.enumerated()), id: \.offset) { _, leg in
                    row(time: leg.startTime, tz: leg.from.tz, badge: leg,
                        title: L10n.f("Board at %@", leg.from.name), track: leg.from.track,
                        detail: leg.shownDirection.map { L10n.f("→ %@", $0) })
                }
                if let last = legs.last {
                    row(time: last.endTime, tz: last.to.tz, badge: nil,
                        title: L10n.f("Get off at %@", last.to.name), track: last.to.track, detail: nil)
                }
            }
            .padding(.vertical, 2)
            .accessibilityIdentifier("boardingSteps")
        }
    }

    private func row(time: Date, tz: String?, badge: Leg?, title: String, track: String?, detail: String?) -> some View {
        HStack(alignment: .firstTextBaseline, spacing: 8) {
            Text(Fmt.time(time, tz: tz))
                .font(.subheadline.weight(.semibold).monospacedDigit())
                .frame(width: TimelineTime.width, alignment: .leading)
            // One column for the line badges, so every stop name starts at the same place.
            Group {
                if let badge {
                    LineBadge(badge, compact: true)
                } else {
                    Image(systemName: "flag.checkered").font(.subheadline).foregroundStyle(.secondary).accessibilityHidden(true)
                }
            }
            .frame(minWidth: 44, alignment: .leading)
            VStack(alignment: .leading, spacing: 1) {
                HStack(spacing: 4) {
                    Text(verbatim: title).font(.subheadline.weight(.medium)).lineLimit(2)
                        .fixedSize(horizontal: false, vertical: true)
                    if let t = TransitLabels.clean(track) {
                        Text(verbatim: "· " + L10n.f("Pl. %@", t)).font(.subheadline).foregroundStyle(.secondary)
                    }
                }
                if let detail {
                    Text(verbatim: detail).font(.caption).foregroundStyle(.secondary).lineLimit(1)
                }
            }
        }
        .accessibilityElement(children: .combine)
    }
}
