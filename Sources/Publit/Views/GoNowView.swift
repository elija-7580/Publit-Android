import SwiftUI

/// Nearby → "Where to now?": best ways from here, leaving now, kept live.
struct GoNowView: View {
    @Environment(AppState.self) var app
    @Environment(LocationManager.self) var location
    @Environment(\.scenePhase) var scenePhase
    @State var isVisible = false
    @State var selectedId: String?

    var body: some View {
        let plan = app.goNow
        let its = plan.sortedItineraries
        let span = TimeInterval(its.map(\.duration).max() ?? 0)
        let badges = SmartRouting.isEnabled ? SmartRouting.badges(its, arriveBy: false) : [:]
        List {
            if !its.isEmpty {
                // Redrawn only when the routes change, not on every refresh of this list (long routes
                // with many points made opening one wait for the map).
                CompareMapView(itineraries: its, selectedId: selectedId ?? its.first?.routeKey)
                    .equatable()
                    .frame(height: 220)
                    .listRowInsets(EdgeInsets())
            }
            Section {
                RouteOptionsButton()
            }
            Section {
                OfflineNote()
                if plan.isLoading && its.isEmpty {
                    LogoLoader()
                } else if let msg = plan.errorMessage, its.isEmpty {
                    Text(msg).foregroundStyle(.secondary)
                }
                if let note = plan.walkNote, !its.isEmpty {
                    // Found only with a longer walk: say so, the walk is part of the decision.
                    Label(note, systemImage: "figure.walk")
                        .font(.footnote).foregroundStyle(Color.delay)
                        .fixedSize(horizontal: false, vertical: true)
                        .accessibilityElement(children: .combine)
                        .accessibilityIdentifier("longWalkNote")
                }
                ForEach(its) { it in
                    NavigationLink {
                        ItineraryDetailView(itinerary: it, fromName: L10n.t("Current location"), toName: plan.to?.title ?? "")
                    } label: {
                        VStack(alignment: .leading, spacing: 4) {
                            if let dep = it.firstDeparture {
                                Text(leaveText(it, firstDeparture: dep))
                                    .font(.caption.weight(.semibold))
                                    .foregroundStyle(it.startTime < .now.addingTimeInterval(120) ? Color.delay : .secondary)
                            } else {
                                Text("Walk all the way").font(.caption.weight(.semibold)).foregroundStyle(.secondary)
                            }
                            ItineraryRow(itinerary: it, windowStart: it.startTime, windowEnd: it.startTime.addingTimeInterval(span),
                                         badges: badges[it.routeKey] ?? [])
                        }
                    }
                }
                if plan.canLoadMore {
                    Button("Later connections") { Task { await plan.loadMore() } }
                }
            } header: {
                Text("From your location")
            } footer: {
                if let note = its.first.flatMap({ Fmt.foreignZoneNote(tz: $0.legs.first?.from.tz, at: $0.startTime) }) {
                    Label(note, systemImage: "clock")
                }
            }
            StreetComparisonSection(comparisons: plan.comparisons,
                                    bestTransit: its.filter { !$0.isDirect }.map(\.duration).min(),
                                    fromName: L10n.t("Current location"), toName: plan.to?.title ?? "")
        }
        .readableWidth()
        .inkList()
        .navigationTitle(plan.to?.title ?? L10n.t("Route"))
        .screenTrail("Go now")
        .navigationBarTitleDisplayMode(.inline)
        .refreshable { await plan.search(current: location.coordinate, locationDenied: location.isDenied, silent: true) }
        .onAppear { isVisible = true }
        .onDisappear { isVisible = false }
        .task(id: "\(plan.queryKey)|\(scenePhase)|\(isVisible)") {
            guard isVisible, scenePhase == .active else { return }
            await plan.search(current: location.coordinate, locationDenied: location.isDenied)
            while !Task.isCancelled {
                try? await Task.sleep(for: PlanView.refreshInterval)
                guard !Task.isCancelled else { break }
                await plan.search(current: location.coordinate, locationDenied: location.isDenied, silent: true)
            }
        }
        .onChange(of: location.hasFix) { _, hasFix in
            if hasFix, plan.itineraries.isEmpty { Task { await plan.search(current: location.coordinate) } }
        }
    }

    private func leaveText(_ it: Itinerary, firstDeparture: Date) -> String {
        let mins = Int(ceil(it.startTime.timeIntervalSinceNow / 60))
        return mins <= 0 ? L10n.t("Leave now") : L10n.f("Leave in %lld min", mins)
    }
}
