import Foundation
import SkipFuse
import Observation

/// A connection the user saved (e.g. "Friday 18:12 to Bari airport"). Stored on the device only.
struct SavedTrip: Codable, Identifiable, Hashable, Sendable {
    let id: String
    var itinerary: Itinerary
    var fromName: String
    var toName: String
    let savedAt: Date
    var refreshedAt: Date?
    /// "Remind me to leave" is on: notification plus Live Activity.
    var followLive: Bool? = nil

    var startTime: Date { itinerary.startTime }
    var endTime: Date { itinerary.endTime }
    var title: String { "\(fromName) → \(toName)" }
    /// This phone only: opens the saved trip (Live Activity, reminders).
    var deepLink: URL { DeepLink.trip(id).url }
    /// The route itself, for other people and other devices.
    var shared: SharedRoute { SharedRoute(itinerary, fromName: fromName, toName: toName) }

    static func == (a: SavedTrip, b: SavedTrip) -> Bool {
        // followLive too: otherwise SwiftUI may skip redrawing a trip whose reminder was switched.
        a.id == b.id && a.itinerary.id == b.itinerary.id && a.refreshedAt == b.refreshedAt && a.followLive == b.followLive
    }
    func hash(into h: inout Hasher) { h.combine(id) }
}

@MainActor
@Observable
final class TripStore {
    private(set) var trips: [SavedTrip] = []
    @ObservationIgnored private let file: JSONFile<[SavedTrip]>
    @ObservationIgnored private let activeFile: JSONFile<String>
    private(set) var activeTripId: String?
    /// Unsaved on the open trip screen: still on the device until that screen closes,
    /// so an accidental tap can be undone. Not persisted: after a relaunch the trip is simply still saved.
    private(set) var pendingRemoval: Set<String> = []

    init(directory: URL? = nil) {
        file = JSONFile(name: "trips.json", directory: directory)
        activeFile = JSONFile(name: "active-trip.json", directory: directory)
        trips = file.load() ?? []
        let active = activeFile.load() ?? ""
        activeTripId = trips.contains { $0.id == active } ? active : nil
    }

    var activeTrip: SavedTrip? {
        guard let id = activeTripId, let trip = trip(id), trip.endTime.addingTimeInterval(15 * 60) >= .now else { return nil }
        return trip
    }

    /// Only one manually started trip is active at a time. Returns the previous ID.
    @discardableResult
    func start(_ id: String) -> String? {
        guard trips.contains(where: { $0.id == id }) else { return nil }
        let previous = activeTripId
        if let previous, previous != id { setFollowLive(previous, false) }
        activeTripId = id
        setFollowLive(id, true)
        activeFile.saveInBackground(id)
        return previous
    }

    func stop(_ id: String) {
        guard activeTripId == id else { return }
        activeTripId = nil
        setFollowLive(id, false)
        activeFile.saveInBackground("")
    }

    /// Lead time before departure from which a saved trip shows in the live bar.
    static let liveLead: TimeInterval = 30 * 60

    /// The trip for the live bar: the one started by hand, otherwise a saved trip that is under way
    /// or leaves within `liveLead`. Up to 5 min after arrival it still shows "Arrived".
    func current(now: Date = .now) -> SavedTrip? {
        if let id = activeTripId, let t = trip(id), t.endTime.addingTimeInterval(5 * 60) >= now,
           t.startTime.addingTimeInterval(-3 * 3600) <= now, !pendingRemoval.contains(id) { return t }
        return trips
            .filter { !pendingRemoval.contains($0.id) && $0.startTime.addingTimeInterval(-Self.liveLead) <= now
                && $0.endTime.addingTimeInterval(5 * 60) >= now }
            .min { $0.startTime < $1.startTime }
    }

    /// Not yet arrived, soonest first.
    func upcoming(now: Date = .now) -> [SavedTrip] {
        trips.filter { $0.endTime >= now }.sorted { $0.startTime < $1.startTime }
    }

    /// Already arrived, most recent first.
    func past(now: Date = .now) -> [SavedTrip] {
        trips.filter { $0.endTime < now }.sorted { $0.startTime > $1.startTime }
    }

    func trip(_ id: String) -> SavedTrip? { trips.first { $0.id == id } }

    /// The saved trip for a route shown in the planner, matched by its vehicles and times.
    func saved(_ itinerary: Itinerary) -> SavedTrip? {
        let key = Self.matchKey(itinerary)
        return trips.first { $0.itinerary.id == itinerary.id || Self.matchKey($0.itinerary) == key }
    }

    @discardableResult
    func save(_ itinerary: Itinerary, from: String, to: String, now: Date = .now) -> SavedTrip {
        if let existing = saved(itinerary) { return existing }
        let t = SavedTrip(id: UUID().uuidString, itinerary: itinerary, fromName: from, toName: to, savedAt: now)
        trips.append(t)
        persist()
        return t
    }

    func wantsLiveActivity(_ id: String) -> Bool { trip(id)?.followLive == true }

    func setFollowLive(_ id: String, _ on: Bool) {
        guard let i = trips.firstIndex(where: { $0.id == id }) else { return }
        trips[i].followLive = on
        persist()
    }

    func isPendingRemoval(_ id: String) -> Bool { pendingRemoval.contains(id) }

    /// "Saved" tapped again: mark only; `discardPending` deletes when the screen closes.
    func markForRemoval(_ id: String) {
        guard trip(id) != nil else { return }
        pendingRemoval.insert(id)
    }

    /// Saved again (or used for a reminder, calendar or ticket) before the screen closed.
    func keep(_ id: String) {
        if pendingRemoval.contains(id) { pendingRemoval.remove(id) }
    }

    /// Trips unsaved on a screen that has now closed. Returns their IDs for clean-up.
    @discardableResult
    func removePending() -> [String] {
        // Assigning, even an empty set, would redraw every screen that shows a Saved button.
        guard !pendingRemoval.isEmpty else { return [] }
        let ids = pendingRemoval.filter { trip($0) != nil }.sorted()
        pendingRemoval.removeAll()
        for id in ids { remove(id) }
        return ids
    }

    func remove(_ id: String) {
        if pendingRemoval.contains(id) { pendingRemoval.remove(id) }
        if activeTripId == id { stop(id) }
        trips.removeAll { $0.id == id }
        persist()
    }

    /// A new connection for a trip under way (missed, cancelled, off the path): same trip, new route
    /// from where you are. The guide goes on with it.
    func reroute(_ id: String, with itinerary: Itinerary, from name: String, at date: Date = .now) {
        guard let i = trips.firstIndex(where: { $0.id == id }) else { return }
        trips[i].itinerary = itinerary
        trips[i].fromName = name
        trips[i].refreshedAt = date
        persist()
    }

    /// Replace the stored route with a fresh real-time version.
    func update(_ id: String, with itinerary: Itinerary, at date: Date = .now) {
        guard let i = trips.firstIndex(where: { $0.id == id }) else { return }
        trips[i].itinerary = Self.keepingSteps(of: trips[i].itinerary,
                                               in: Self.keepingFares(of: trips[i].itinerary, in: itinerary))
        trips[i].refreshedAt = date
        persistSoon()
    }

    /// Live refreshes come without fares (smaller answers); the fares saved with the trip stay.
    nonisolated static func keepingFares(of old: Itinerary, in new: Itinerary) -> Itinerary {
        guard new.fareTransfers == nil, old.fareTransfers != nil, old.legs.count == new.legs.count else { return new }
        var fresh = new
        fresh.fareTransfers = old.fareTransfers
        for j in fresh.legs.indices {
            fresh.legs[j].fareTransferIndex = old.legs[j].fareTransferIndex
            fresh.legs[j].effectiveFareLegIndex = old.legs[j].effectiveFareLegIndex
        }
        return fresh
    }

    /// Walking directions stay when a refresh comes without them.
    nonisolated static func keepingSteps(of old: Itinerary, in new: Itinerary) -> Itinerary {
        guard old.legs.count == new.legs.count else { return new }
        var fresh = new
        for j in fresh.legs.indices where fresh.legs[j].steps == nil && !fresh.legs[j].isTransit {
            fresh.legs[j].steps = old.legs[j].steps
        }
        return fresh
    }

    /// Same vehicles from the same stops on the same scheduled times.
    nonisolated static func matchKey(_ it: Itinerary) -> String {
        let legs = it.transitLegs.map { "\($0.tripId ?? $0.lineName)@\($0.from.name)@\(Int($0.scheduledStartTime.timeIntervalSince1970))" }
        return legs.isEmpty ? "walk@\(Int(it.startTime.timeIntervalSince1970))@\(it.legs.last?.to.name ?? "")" : legs.joined(separator: ">")
    }

    private func persist() {
        pendingSave?.cancel(); pendingSave = nil
        file.saveInBackground(trips)
    }

    /// Live refreshes arrive for every open trip and the Live Activity: written once after they settle.
    @ObservationIgnored private var pendingSave: Task<Void, Never>?

    private func persistSoon() {
        guard pendingSave == nil else { return }
        pendingSave = Task { [weak self] in
            try? await Task.sleep(for: .seconds(2))
            guard !Task.isCancelled, let self else { return }
            self.pendingSave = nil
            self.file.saveInBackground(self.trips)
        }
    }

    /// Writes a pending live refresh now (app going to the background).
    func flushPendingSave() {
        guard pendingSave != nil else { return }
        persist()
    }
}
