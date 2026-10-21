import Foundation

/// Starting and ending a trip by hand: the live bar and trip screen switch to the guide,
/// the Live Activity starts and a get-off reminder is set for each ride.
@MainActor
enum TripSession {
    /// Returns a note for the user about the lock screen.
    @discardableResult
    static func start(_ trip: SavedTrip, in trips: TripStore, hasTicket: Bool) async -> String {
        let previous = trips.start(trip.id)
        if let previous, previous != trip.id {
            LeaveReminder.cancel(previous)
            if let old = trips.trip(previous) { GetOffReminder.cancelAll(old.itinerary) }
            await LiveTripActivity.end(previous)
        }
        let note: String
        switch LiveTripActivity.start(trip, hasTicket: hasTicket) {
        case .started: note = L10n.t("Follow it on the lock screen.")
        case .tooEarly: note = L10n.t("Live updates on the lock screen start 8 hours before.")
        case .unavailable, .failed: note = L10n.t("Live tracking is active in the app. Lock screen updates may be unavailable.")
        }
        await GetOffReminder.scheduleAll(trip.itinerary)
        return note
    }

    /// Switches a running trip to a new connection: reminders and the Live Activity follow.
    static func reroute(_ trip: SavedTrip, to itinerary: Itinerary, in trips: TripStore) async {
        LeaveReminder.cancel(trip.id)
        GetOffReminder.cancelAll(trip.itinerary)
        trips.reroute(trip.id, with: itinerary, from: L10n.t("Current location"))
        guard let updated = trips.trip(trip.id) else { return }
        await LiveTripActivity.update(updated)
        await GetOffReminder.scheduleAll(updated.itinerary)
    }

    static func stop(_ trip: SavedTrip, in trips: TripStore) async {
        trips.stop(trip.id)
        LeaveReminder.cancel(trip.id)
        GetOffReminder.cancelAll(trip.itinerary)
        await LiveTripActivity.end(trip.id)
    }
}
