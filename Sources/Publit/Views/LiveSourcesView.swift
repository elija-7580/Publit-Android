import SwiftUI

/// Settings → Live data sources: which operators Publit asks besides Transitous, and for what.
struct LiveSourcesView: View {
    private struct Source: Identifiable {
        let name: String
        let area: String
        let what: String
        var id: String { name }
    }

    private let sources = [
        Source(name: "Transitous", area: L10n.t("Europe and beyond"), what: L10n.t("Routes, timetables and live times where operators publish them")),
        Source(name: "Wiener Linien", area: L10n.t("Vienna"), what: L10n.t("Live departures, disruptions, lifts out of service")),
        Source(name: "ÖBB", area: L10n.t("Austria"), what: L10n.t("Trains, and buses and trams where reported: delays, cancellations, construction work")),
        Source(name: "search.ch", area: L10n.t("Switzerland"), what: L10n.t("Delays, cancellations and track changes of trains, trams, buses and boats")),
        Source(name: "VRR", area: L10n.t("North Rhine-Westphalia"), what: L10n.t("Live times of buses, trams and trains where Transitous has none")),
        Source(name: "VBB", area: L10n.t("Berlin and Brandenburg"), what: L10n.t("Live times of buses, trams and trains where Transitous has none")),
        Source(name: "RMV", area: L10n.t("Hesse"), what: L10n.t("Live times of buses, trams and trains where Transitous has none")),
        Source(name: "VBN", area: L10n.t("Lower Saxony and Bremen"), what: L10n.t("Live times of buses, trams and trains where Transitous has none")),
        Source(name: "NAH.SH", area: L10n.t("Schleswig-Holstein"), what: L10n.t("Live times of buses, trams and trains where Transitous has none")),
        Source(name: "VRN", area: L10n.t("Rhineland-Palatinate"), what: L10n.t("Live times of buses, trams and trains where Transitous has none")),
        Source(name: "ACTV", area: L10n.t("Venice"), what: L10n.t("Live times of waterbuses and buses")),
        Source(name: "SL", area: L10n.t("Stockholm"), what: L10n.t("Live departures and disruptions")),
        Source(name: "TfL", area: L10n.t("London"), what: L10n.t("Line status and disruptions (Powered by TfL Open Data)")),
    ]

    var body: some View {
        List {
            Section {
                ForEach(sources) { s in
                    VStack(alignment: .leading, spacing: 3) {
                        HStack {
                            Text(verbatim: s.name).font(.body.weight(.semibold))
                            Spacer()
                            Text(verbatim: s.area).font(.subheadline).foregroundStyle(.secondary)
                        }
                        Text(verbatim: s.what).font(.caption).foregroundStyle(.secondary)
                    }
                    .padding(.vertical, 2)
                    .accessibilityElement(children: .combine)
                }
            } footer: {
                Text("The extra operators only get the stop, line and time of a ride, never your location or account.")
            }
        }
        .readableWidth()
        .inkList()
        .navigationTitle("Live data sources")
        .navigationBarTitleDisplayMode(.inline)
    }
}
