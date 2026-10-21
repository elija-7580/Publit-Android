#if canImport(CoreLocation)
import CoreLocation
#endif
import Observation
import SkipFuse

#if os(Android)
/// Android: the same API as the iOS location manager, fed by Android's location service
/// (Droid.kt: fused/GPS/network provider, compass from the rotation vector sensor).
@MainActor
@Observable
final class LocationManager {
    static let shared = LocationManager()

    private(set) var location: CLLocation?
    private(set) var hasFix = false
    private(set) var status: CLAuthorizationStatus
    private(set) var heading: Double?
    private var precise = false
    private var updating = false

    init() {
        status = Self.status(Droid.string("locationPermission").flatMap(Int.init) ?? 0)
    }

    private static func status(_ code: Int) -> CLAuthorizationStatus {
        switch code {
        case 1: return .denied
        case 2: return .authorizedWhenInUse
        default: return .notDetermined
        }
    }

    func receive(_ fix: CLLocation) {
        guard updating || location == nil else { return }
        location = fix
        if !hasFix { hasFix = true }
    }

    func receiveHeading(_ value: Double?) {
        if heading != value { heading = value }
    }

    func receivePermission(_ code: Int) {
        status = Self.status(code)
        if status == .authorizedWhenInUse { startUpdates() }
    }

    var coordinate: LatLon? {
        location.map { LatLon(lat: $0.coordinate.latitude, lon: $0.coordinate.longitude) }
    }

    func freshCoordinate(maxAge: TimeInterval = 60, wait: TimeInterval = 6) async -> LatLon? {
        guard !isDenied else { return coordinate }
        if status != .notDetermined { startUpdates() }
        let start = Date()
        while Date().timeIntervalSince(start) < wait {
            if let l = location, Date().timeIntervalSince(l.timestamp) < maxAge { break }
            try? await Task.sleep(for: .milliseconds(150))
            if Task.isCancelled { break }
        }
        return coordinate
    }

    var isDenied: Bool { status == .denied || status == .restricted }

    func start() {
        if status == .notDetermined {
            Droid.call("requestLocationPermission")
        } else if !isDenied {
            startUpdates()
        }
    }

    private func startUpdates() {
        updating = true
        Droid.call("startLocation", precise)
    }

    func setPrecise(_ precise: Bool) {
        guard self.precise != precise else { return }
        self.precise = precise
        if updating { Droid.call("startLocation", precise) }
    }

    func setHeading(_ on: Bool) {
        Droid.call("setHeading", on)
        if !on, heading != nil { heading = nil }
    }

    var accuracy: Double { location.map { $0.horizontalAccuracy >= 0 ? $0.horizontalAccuracy : 1000 } ?? 1000 }

    func pause() {
        updating = false
        Droid.call("stopLocation")
        Droid.call("setHeading", false)
    }
}
#else
@MainActor
@Observable
final class LocationManager: NSObject, CLLocationManagerDelegate {
    /// Every fix (about every 50 m). Read it in tasks and actions, not in a view body:
    /// a body that reads it is redrawn on every fix.
    private(set) var location: CLLocation?
    /// Changes once, when the first fix arrives. Safe to read in a view body.
    private(set) var hasFix = false
    private(set) var status: CLAuthorizationStatus
    /// Where the top of the phone points (degrees from north), while the trip guide asks for it.
    /// Read it only in small views: it changes often.
    private(set) var heading: Double?

    private let manager: CLLocationManager

    override init() {
        let m = CLLocationManager()
        manager = m
        status = m.authorizationStatus
        super.init()
        m.delegate = self
        m.desiredAccuracy = kCLLocationAccuracyHundredMeters
        m.distanceFilter = 50
        useRecentFix(m.location)
    }

    /// The system's last fix, when it is recent and good enough: routes and departures can start
    /// right away instead of waiting a few seconds for GPS.
    private func useRecentFix(_ fix: CLLocation?) {
        guard location == nil, let fix, fix.horizontalAccuracy >= 0, fix.horizontalAccuracy <= 1000,
              Date().timeIntervalSince(fix.timestamp) < 5 * 60 else { return }
        location = fix
        if !hasFix { hasFix = true }
    }

    var coordinate: LatLon? {
        location.map { LatLon(lat: $0.coordinate.latitude, lon: $0.coordinate.longitude) }
    }

    /// The location now: the last fix if it is recent, otherwise the next one (waits up to `wait`
    /// seconds, then takes what there is). After the app was in the background the last fix can be
    /// hours old and far away.
    func freshCoordinate(maxAge: TimeInterval = 60, wait: TimeInterval = 6) async -> LatLon? {
        guard !isDenied else { return coordinate }
        if status != .notDetermined { manager.startUpdatingLocation() }
        let start = Date()
        while Date().timeIntervalSince(start) < wait {
            if let l = location, Date().timeIntervalSince(l.timestamp) < maxAge { break }
            try? await Task.sleep(for: .milliseconds(150))
            if Task.isCancelled { break }
        }
        return coordinate
    }

    var isDenied: Bool { status == .denied || status == .restricted }

    func start() {
        if status == .notDetermined {
            manager.requestWhenInUseAuthorization()
        } else if !isDenied {
            useRecentFix(manager.location)
            manager.startUpdatingLocation()
        }
    }

    /// Walking directions need a fix every few metres; elsewhere every 50 m is plenty.
    func setPrecise(_ precise: Bool) {
        manager.desiredAccuracy = precise ? kCLLocationAccuracyBest : kCLLocationAccuracyHundredMeters
        manager.distanceFilter = precise ? 5 : 50
    }

    /// The compass, for "which way now" in the trip guide. Off elsewhere (it costs battery).
    func setHeading(_ on: Bool) {
        if on, CLLocationManager.headingAvailable() {
            manager.headingFilter = 8
            manager.startUpdatingHeading()
        } else {
            manager.stopUpdatingHeading()
            if heading != nil { heading = nil }
        }
    }

    /// Metres of uncertainty of the last fix (large when unknown).
    var accuracy: Double { location.map { $0.horizontalAccuracy >= 0 ? $0.horizontalAccuracy : 1000 } ?? 1000 }

    /// In the background nothing shows the location: GPS off until the app is open again.
    func pause() {
        manager.stopUpdatingLocation()
        manager.stopUpdatingHeading()
    }

    nonisolated func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        guard let last = locations.last else { return }
        Task { @MainActor in
            self.location = last
            if !self.hasFix { self.hasFix = true }
        }
    }

    nonisolated func locationManager(_ manager: CLLocationManager, didUpdateHeading newHeading: CLHeading) {
        // A negative accuracy means the compass is not usable (interference, needs calibrating).
        let value: Double? = newHeading.headingAccuracy < 0 ? nil
            : (newHeading.trueHeading >= 0 ? newHeading.trueHeading : newHeading.magneticHeading)
        Task { @MainActor in
            if self.heading != value { self.heading = value }
        }
    }

    nonisolated func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        let s = manager.authorizationStatus
        Task { @MainActor in
            self.status = s
            if s == .authorizedWhenInUse || s == .authorizedAlways {
                self.useRecentFix(self.manager.location)
                self.manager.startUpdatingLocation()
            }
        }
    }

    nonisolated func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {}
}

#endif

extension LatLon {
    var clCoordinate: CLLocationCoordinate2D { CLLocationCoordinate2D(latitude: lat, longitude: lon) }
}

extension EncodedPolyline {
    var clCoordinates: [CLLocationCoordinate2D] { mapPoints.map(\.clCoordinate) }
}
