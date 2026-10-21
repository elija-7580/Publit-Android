import SwiftUI

/// The nearest stops with their next departures: line, direction, minutes.
struct WatchNearbyView: View {
    let model: WatchNearbyModel
    @Environment(\.scenePhase) private var scenePhase

    var body: some View {
        content
            .navigationTitle(Text("Nearby"))
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button { Task { await model.refresh() } } label: { Image(systemName: "arrow.clockwise") }
                        .accessibilityLabel(Text("Refresh"))
                }
            }
            .task { await model.refresh() }
            .onChange(of: scenePhase) { _, phase in
                if phase == .active { Task { await model.refresh() } }
            }
    }

    @ViewBuilder
    private var content: some View {
        if model.groups.isEmpty {
            switch model.status {
            case .idle, .locating: message("Waiting for your location…", symbol: "location")
            case .loading: ProgressView()
            case .denied: message("Location is off", symbol: "location.slash")
            case .noLocation, .failed: retry
            case .loaded: message("No departures nearby", symbol: "tram")
            }
        } else {
            TimelineView(.everyMinute) { context in
                List {
                    if let far = model.farDistance {
                        Label(Reach.farNote(meters: far), systemImage: "figure.walk")
                            .font(.footnote)
                            .foregroundStyle(Color.delay)
                            .listRowBackground(Color.clear)
                    }
                    ForEach(model.groups) { group in
                        Section {
                            ForEach(group.departures.prefix(WatchNearbyModel.perStop)) { st in
                                WatchDepartureRow(stopTime: st, now: context.date)
                            }
                        } header: {
                            HStack(alignment: .firstTextBaseline) {
                                Text(verbatim: group.name).lineLimit(2)
                                Spacer(minLength: 4)
                                if let d = group.distance { Text(Fmt.distance(meters: d)).monospacedDigit() }
                            }
                        }
                    }
                    if let updated = model.updated {
                        Text(L10n.f("Updated %@", Fmt.time(updated)))
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                            .listRowBackground(Color.clear)
                    }
                }
            }
        }
    }

    private var retry: some View {
        VStack(spacing: 8) {
            Image(systemName: "wifi.exclamationmark").font(.title3).foregroundStyle(.secondary)
            Button("Try again") { Task { await model.refresh() } }
        }
    }

    private func message(_ text: LocalizedStringKey, symbol: String) -> some View {
        VStack(spacing: 6) {
            Image(systemName: symbol).font(.title3).foregroundStyle(.secondary)
            Text(text).font(.footnote).multilineTextAlignment(.center)
        }
        .padding()
    }
}

/// Line badge and direction on the left, minutes on the right.
struct WatchDepartureRow: View {
    let stopTime: StopTime
    let now: Date

    var body: some View {
        let minutes = Int(ceil(stopTime.departureTime.timeIntervalSince(now) / 60))
        HStack(alignment: .center, spacing: 6) {
            VStack(alignment: .leading, spacing: 3) {
                LineBadge(stopTime, compact: true)
                DirectionLabel(direction: stopTime.shownDirection, circular: stopTime.isCircular, font: .footnote)
                    .lineLimit(2)
            }
            Spacer(minLength: 2)
            VStack(alignment: .trailing, spacing: 1) {
                if stopTime.isCancelled {
                    Text(Fmt.time(stopTime.departureTime, tz: stopTime.place.tz))
                        .strikethrough(true)
                        .foregroundStyle(Color.danger)
                } else if minutes <= 0 {
                    Text("now").font(.headline)
                } else if minutes < 60 {
                    HStack(alignment: .firstTextBaseline, spacing: 1) {
                        Text(verbatim: "\(minutes)").font(.headline.monospacedDigit())
                            .foregroundStyle(stopTime.delayMinutes > 0 ? Color.delay : Color.primary)
                        Text("min").font(.caption2).foregroundStyle(.secondary)
                    }
                } else {
                    Text(Fmt.time(stopTime.departureTime, tz: stopTime.place.tz)).font(.headline.monospacedDigit())
                }
                if stopTime.realTime {
                    Image(systemName: "dot.radiowaves.left.and.right").font(.caption2).foregroundStyle(Color.live)
                        .accessibilityLabel(Text("Live"))
                }
            }
            .fixedSize()
        }
        .accessibilityElement(children: .combine)
    }
}
