import Foundation

/// The foreground activity refresher and an open trip screen share one request.
/// A very short completed-result window also absorbs slightly staggered refresh timers.
actor TripRefreshCoordinator {
    static let shared = TripRefreshCoordinator()
    private var inFlight: [String: Task<Itinerary, Error>] = [:]
    private var recent: [String: (Itinerary, Date)] = [:]
    private let load: @Sendable (String) async throws -> Itinerary

    init(load: @escaping @Sendable (String) async throws -> Itinerary = {
        try await TransitousClient.shared.refresh(itineraryId: $0)
    }) { self.load = load }

    func refresh(_ id: String, now: Date = .now) async throws -> Itinerary {
        if let task = inFlight[id] { return try await task.value }
        if let hit = recent[id], now.timeIntervalSince(hit.1) >= 0, now.timeIntervalSince(hit.1) < 3 { return hit.0 }
        let loader = load
        let task = Task { try await loader(id) }
        inFlight[id] = task
        defer { inFlight[id] = nil }
        let value = try await task.value
        recent = recent.filter { now.timeIntervalSince($0.value.1) < 3 }
        recent[id] = (value, now)
        return value
    }
}
