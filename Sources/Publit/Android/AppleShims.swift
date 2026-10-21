#if os(Android)
import Foundation

// Android: the few CoreLocation/MapKit value types the shared Publit code passes around.
// They behave like Apple's; the platform services behind them live in the other files of this folder.

typealias CLLocationDegrees = Double
typealias CLLocationDistance = Double
typealias CLLocationAccuracy = Double
typealias CLLocationSpeed = Double
typealias CLLocationDirection = Double

struct CLLocationCoordinate2D: Equatable, Hashable, Sendable {
    var latitude: CLLocationDegrees
    var longitude: CLLocationDegrees
    init() { latitude = 0; longitude = 0 }
    init(latitude: CLLocationDegrees, longitude: CLLocationDegrees) {
        self.latitude = latitude
        self.longitude = longitude
    }
}

func CLLocationCoordinate2DIsValid(_ c: CLLocationCoordinate2D) -> Bool {
    c.latitude.isFinite && c.longitude.isFinite && abs(c.latitude) <= 90 && abs(c.longitude) <= 180
}

let kCLLocationAccuracyBest: CLLocationAccuracy = -1
let kCLLocationAccuracyHundredMeters: CLLocationAccuracy = 100

enum CLAuthorizationStatus: Int32, Sendable {
    case notDetermined = 0, restricted = 1, denied = 2, authorizedAlways = 3, authorizedWhenInUse = 4
}

final class CLLocation: @unchecked Sendable {
    let coordinate: CLLocationCoordinate2D
    let horizontalAccuracy: CLLocationAccuracy
    let altitude: Double
    let speed: CLLocationSpeed
    let course: CLLocationDirection
    let timestamp: Date

    init(latitude: CLLocationDegrees, longitude: CLLocationDegrees) {
        self.init(coordinate: CLLocationCoordinate2D(latitude: latitude, longitude: longitude), altitude: 0,
                  horizontalAccuracy: 0, verticalAccuracy: -1, course: -1, speed: -1, timestamp: Date())
    }

    init(coordinate: CLLocationCoordinate2D, altitude: Double, horizontalAccuracy: CLLocationAccuracy,
                verticalAccuracy: CLLocationAccuracy, course: CLLocationDirection, speed: CLLocationSpeed, timestamp: Date) {
        self.coordinate = coordinate
        self.altitude = altitude
        self.horizontalAccuracy = horizontalAccuracy
        self.course = course
        self.speed = speed
        self.timestamp = timestamp
    }

    /// Great-circle distance in metres (as CoreLocation).
    func distance(from other: CLLocation) -> CLLocationDistance {
        let r = 6_371_000.0
        let p1 = coordinate.latitude * .pi / 180, p2 = other.coordinate.latitude * .pi / 180
        let dp = p2 - p1, dl = (other.coordinate.longitude - coordinate.longitude) * .pi / 180
        let a = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
        return 2 * r * atan2(sqrt(a), sqrt(1 - a))
    }
}

struct MKCoordinateSpan: Equatable, Sendable {
    var latitudeDelta: CLLocationDegrees
    var longitudeDelta: CLLocationDegrees
    init() { latitudeDelta = 0; longitudeDelta = 0 }
    init(latitudeDelta: CLLocationDegrees, longitudeDelta: CLLocationDegrees) {
        self.latitudeDelta = latitudeDelta
        self.longitudeDelta = longitudeDelta
    }
}

struct MKCoordinateRegion: Equatable, Sendable {
    var center: CLLocationCoordinate2D
    var span: MKCoordinateSpan
    init() { center = CLLocationCoordinate2D(); span = MKCoordinateSpan() }
    init(center: CLLocationCoordinate2D, span: MKCoordinateSpan) {
        self.center = center
        self.span = span
    }
    init(center: CLLocationCoordinate2D, latitudinalMeters: CLLocationDistance, longitudinalMeters: CLLocationDistance) {
        self.center = center
        let lat = latitudinalMeters / 111_320
        let lon = longitudinalMeters / max(1, 111_320 * cos(center.latitude * .pi / 180))
        self.span = MKCoordinateSpan(latitudeDelta: lat, longitudeDelta: lon)
    }
}
#endif
