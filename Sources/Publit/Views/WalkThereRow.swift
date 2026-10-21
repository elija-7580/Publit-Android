import SwiftUI
#if canImport(MapKit)
import MapKit
#endif

/// "6 min walk · 450 m" to exactly this stop (the right side of the road), opening Apple Maps on foot.
struct WalkThereRow: View {
    let name: String
    let coordinate: LatLon
    @Environment(LocationManager.self) var location
    @State var seconds: TimeInterval?

    /// Farther than this, walking is not the way to get there.
    static let maxDistance = 4000.0

    var body: some View {
        if let here = location.coordinate {
            let meters = here.distance(to: coordinate)
            if meters <= Self.maxDistance, meters > 25 {
                Button { open() } label: {
                    HStack {
                        Label { Text(verbatim: text(meters)) } icon: { Image(systemName: "figure.walk") }
                        Spacer()
                        Image(systemName: "arrow.up.right").font(.footnote.weight(.semibold)).foregroundStyle(.secondary)
                    }
                }
                .accessibilityLabel(L10n.f("Walk there: %@", text(meters)))
                .accessibilityIdentifier("walkThere")
                // Asked again only after moving about 100 m.
                .task(id: "\((here.lat * 1000).rounded())|\((here.lon * 1000).rounded())") {
                    seconds = await WalkingTimes.shared.seconds(from: here, to: coordinate)
                }
            }
        }
    }

    private func text(_ meters: Double) -> String {
        // Without Apple's answer: about 4.5 km/h along streets (a third longer than the straight line).
        let minutes = Int(((seconds ?? meters * 1.3 / 1.25) / 60).rounded(.up))
        return L10n.f("%lld min walk · %@", max(1, minutes), Fmt.distance(meters: meters))
    }

    private func open() {
        DirectionsApp.current.open(coordinate, name: name, mode: .walking)
    }
}

/// Walking times from Apple Maps, remembered so scrolling and reopening a stop does not ask again
/// (Apple limits how often an app may ask).
actor WalkingTimes {
    static let shared = WalkingTimes()
    private var cache: [String: (TimeInterval, Date)] = [:]

    func seconds(from a: LatLon, to b: LatLon) async -> TimeInterval? {
        let key = "\((a.lat * 1000).rounded())|\((a.lon * 1000).rounded())>\(b.lat)|\(b.lon)"
        if let hit = cache[key], Date().timeIntervalSince(hit.1) < 600 { return hit.0 }
        let request = MKDirections.Request()
        request.source = MKMapItem(placemark: MKPlacemark(coordinate: a.clCoordinate))
        request.destination = MKMapItem(placemark: MKPlacemark(coordinate: b.clCoordinate))
        request.transportType = .walking
        guard let eta = try? await MKDirections(request: request).calculateETA() else { return nil }
        if cache.count > 200 { cache = [:] }
        cache[key] = (eta.expectedTravelTime, Date())
        return eta.expectedTravelTime
    }
}
