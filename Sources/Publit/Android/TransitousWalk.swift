#if os(Android)
import Foundation

/// Android stand-in for MKDirections walking: the same OpenStreetMap footpaths Transitous uses
/// for the walks in its connections (a walk-only `plan`, cached by the client for 15 minutes).
enum TransitousWalk {
    struct Route {
        let points: [LatLon]
        let distance: Double
        let expectedTravelTime: TimeInterval
        let steps: [String]
    }

    static func route(from: LatLon, to: LatLon) async -> Route? {
        guard let walk = try? await TransitousClient.shared.streetRoutes(from: from, to: to, time: nil, arriveBy: false, mode: "WALK").first,
              let leg = walk.legs.first else { return nil }
        let points = walk.legs.flatMap { $0.legGeometry.mapPoints }
        let distance = walk.legs.reduce(0.0) { $0 + ($1.distance ?? 0) }
        let steps = walk.legs.flatMap { $0.steps ?? [] }.compactMap { s -> String? in
            guard let street = s.streetName, !street.isEmpty else { return nil }
            return street
        }
        _ = leg
        return Route(points: points, distance: distance, expectedTravelTime: TimeInterval(walk.duration), steps: steps)
    }
}

extension TransitousWalk.Route {
    /// Lets `WalkingTimes` read it like MKDirections.ETAResponse.
    var eta: TimeInterval { expectedTravelTime }
}
#endif
