import SwiftUI

/// A route opened from a link: fetched again from Transitous (live times), not from this device.
/// If it is one of the saved trips, that trip opens; if the link is too old, the same places can be searched again.
struct SharedRouteView: View {
    let route: SharedRoute
    @Environment(TripStore.self) var trips
    @Environment(AppState.self) var app
    @Environment(\.scenePhase) var scenePhase
    @State var itinerary: Itinerary?
    @State var failed = false

    var body: some View {
        Group {
            if let saved = savedTrip {
                SavedTripView(tripId: saved.id)
            } else if let itinerary {
                ItineraryDetailView(itinerary: itinerary, fromName: route.fromName, toName: route.toName,
                                    statusAt: status(itinerary))
                    .navigationTitle(route.toName)
            } else if failed || route.itineraryId == nil {
                ContentUnavailableView {
                    Label(L10n.t("This route could not be loaded."), systemImage: "point.topleft.down.to.point.bottomright.curvepath")
                } description: {
                    Text(verbatim: "\(route.fromName) → \(route.toName)\n")
                        + Text("It may be too old or no longer in the timetable. You can search for it again.")
                } actions: {
                    Button(L10n.t("Search this route")) { app.searchAgain(route) }
                        .buttonStyle(.ink)
                        .accessibilityIdentifier("searchSharedRoute")
                }
            } else {
                LogoLoader().frame(maxWidth: .infinity, maxHeight: .infinity)
            }
        }
        .screenTrail("Shared route")
        .navigationBarTitleDisplayMode(.inline)
        .task(id: "\(scenePhase)") {
            guard scenePhase == .active, savedTrip == nil, let id = route.itineraryId else { return }
            while !Task.isCancelled {
                do {
                    let fresh = try await TransitousClient.shared.refresh(itineraryId: itinerary?.id ?? id)
                    guard !Task.isCancelled else { return }
                    itinerary = fresh
                } catch is CancellationError { return } catch {
                    if itinerary == nil { failed = true; return }
                }
                guard let it = itinerary, it.endTime > .now, savedTrip == nil else { return }
                try? await Task.sleep(for: Energy.duration(90))
            }
        }
    }

    /// Live status line ("Leave in 6 min", "On time") while the route is not over.
    private func status(_ it: Itinerary) -> ((Date) -> TripStatus)? {
        guard it.endTime > .now else { return nil }
        return { TripStatus.at($0, it) }
    }

    /// The same route among the saved trips: by ID, by vehicles and times once fetched,
    /// or by places and departure (a calendar entry whose ID is too old).
    private var savedTrip: SavedTrip? {
        if let id = route.itineraryId, let t = trips.trips.first(where: { $0.itinerary.id == id }) { return t }
        if let itinerary, let t = trips.saved(itinerary) { return t }
        return trips.trips.first {
            $0.fromName == route.fromName && $0.toName == route.toName
                && abs(($0.itinerary.legs.first?.scheduledStartTime ?? $0.startTime).timeIntervalSince(route.departure)) < 60
        }
    }
}
