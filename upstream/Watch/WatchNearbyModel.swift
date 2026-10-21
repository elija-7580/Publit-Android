import CoreLocation
import Foundation
import Observation

/// Departures around the watch, with the phone's radius steps (500 m, 1.5 km, 5 km): a ferry
/// 3 km away is listed with its distance instead of an empty screen.
@MainActor
@Observable
final class WatchNearbyModel: NSObject, CLLocationManagerDelegate {
    enum Status: Equatable { case idle, locating, loading, loaded, noLocation, denied, failed }

    private(set) var status: Status = .idle
    private(set) var groups: [StopGroup] = []
    private(set) var farDistance: Double?
    private(set) var updated: Date?

    @ObservationIgnored private let manager = CLLocationManager()
    @ObservationIgnored private var waiting: [CheckedContinuation<LatLon?, Never>] = []
    @ObservationIgnored private var loading = false

    /// Stops shown at once; the watch screen is small.
    static let maxStops = 6
    static let perStop = 4

    override init() {
        super.init()
        manager.delegate = self
        manager.desiredAccuracy = kCLLocationAccuracyHundredMeters
    }

    func refresh() async {
        guard !loading else { return }
        loading = true
        defer { loading = false }
        if groups.isEmpty { status = .locating }
        guard let here = await locate() else {
            let auth = manager.authorizationStatus
            status = auth == .denied || auth == .restricted ? .denied : (groups.isEmpty ? .noLocation : .loaded)
            return
        }
        if groups.isEmpty { status = .loading }
        do {
            var res = StopTimesResponse(stopTimes: [], place: nil, nextPageCursor: nil)
            for r in Reach.nearbyRadii where res.stopTimes.isEmpty {
                res = try await TransitousClient.shared.departures(near: here, radius: r, count: 40)
            }
            let found = StopGrouping.group(StopTime.upcoming(res.stopTimes), around: here)
            groups = Array(found.sorted { ($0.distance ?? 0) < ($1.distance ?? 0) }.prefix(Self.maxStops))
            farDistance = groups.compactMap(\.distance).min().flatMap { $0 >= Reach.farDistance ? $0 : nil }
            updated = Date()
            status = .loaded
        } catch {
            status = groups.isEmpty ? .failed : .loaded
        }
    }

    // MARK: Location

    private func locate() async -> LatLon? {
        await withCheckedContinuation { c in
            waiting.append(c)
            guard waiting.count == 1 else { return }
            switch manager.authorizationStatus {
            case .notDetermined: manager.requestWhenInUseAuthorization()
            case .denied, .restricted: deliver(nil)
            default: manager.requestLocation()
            }
        }
    }

    private func deliver(_ position: LatLon?) {
        let all = waiting
        waiting = []
        for c in all { c.resume(returning: position) }
    }

    nonisolated func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        guard let c = locations.last?.coordinate else { return }
        let p = LatLon(lat: c.latitude, lon: c.longitude)
        Task { @MainActor in self.deliver(p) }
    }

    nonisolated func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        Task { @MainActor in self.deliver(nil) }
    }

    nonisolated func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        let auth = manager.authorizationStatus
        Task { @MainActor in
            guard !self.waiting.isEmpty else { return }
            switch auth {
            case .notDetermined: break
            case .denied, .restricted: self.deliver(nil)
            default: self.manager.requestLocation()
            }
        }
    }
}
