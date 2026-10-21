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

    convenience init(latitude: CLLocationDegrees, longitude: CLLocationDegrees) {
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

// MapKit's flat map geometry (Web Mercator, the same world size Apple uses), so the camera
// helpers of the trip screens compile unchanged; the Android map reads its camera from GoogleCamera.
let MKMapSizeWorldSide: Double = 268_435_456

struct MKMapPoint: Equatable, Sendable {
    var x: Double
    var y: Double
    init(x: Double, y: Double) { self.x = x; self.y = y }
    init(_ c: CLLocationCoordinate2D) {
        x = (c.longitude + 180) / 360 * MKMapSizeWorldSide
        let s = sin(max(-85.05, min(85.05, c.latitude)) * .pi / 180)
        y = (0.5 - log((1 + s) / (1 - s)) / (4 * .pi)) * MKMapSizeWorldSide
    }
    var coordinate: CLLocationCoordinate2D {
        let lon = x / MKMapSizeWorldSide * 360 - 180
        let n = .pi - 2 * .pi * y / MKMapSizeWorldSide
        let lat = atan(sinh(n)) * 180 / .pi
        return CLLocationCoordinate2D(latitude: lat, longitude: lon)
    }
}

struct MKMapSize: Equatable, Sendable {
    var width: Double
    var height: Double
    init(width: Double, height: Double) { self.width = width; self.height = height }
}

struct MKMapRect: Equatable, Sendable {
    var origin: MKMapPoint
    var size: MKMapSize
    init(origin: MKMapPoint, size: MKMapSize) { self.origin = origin; self.size = size }
    init(x: Double, y: Double, width: Double, height: Double) {
        origin = MKMapPoint(x: x, y: y); size = MKMapSize(width: width, height: height)
    }
    static let null = MKMapRect(x: .infinity, y: .infinity, width: 0, height: 0)
    var isNull: Bool { origin.x.isInfinite || origin.y.isInfinite }
    var width: Double { size.width }
    var height: Double { size.height }
    var minX: Double { origin.x }
    var minY: Double { origin.y }
    var maxX: Double { origin.x + size.width }
    var maxY: Double { origin.y + size.height }
    var midX: Double { origin.x + size.width / 2 }
    var midY: Double { origin.y + size.height / 2 }
    func union(_ o: MKMapRect) -> MKMapRect {
        if isNull { return o }
        if o.isNull { return self }
        let x0 = min(minX, o.minX), y0 = min(minY, o.minY)
        return MKMapRect(x: x0, y: y0, width: max(maxX, o.maxX) - x0, height: max(maxY, o.maxY) - y0)
    }
    func insetBy(dx: Double, dy: Double) -> MKMapRect {
        if isNull { return self }
        return MKMapRect(x: minX + dx, y: minY + dy, width: max(0, width - 2 * dx), height: max(0, height - 2 * dy))
    }
}

func MKMapPointsPerMeterAtLatitude(_ latitude: CLLocationDegrees) -> Double {
    MKMapSizeWorldSide / (40_075_016.686 * cos(latitude * .pi / 180))
}

struct MapCamera: Equatable, Sendable {
    var centerCoordinate: CLLocationCoordinate2D
    var distance: Double
    var heading: Double = 0
    var pitch: Double = 0
    init(centerCoordinate: CLLocationCoordinate2D, distance: Double, heading: Double = 0, pitch: Double = 0) {
        self.centerCoordinate = centerCoordinate; self.distance = distance; self.heading = heading; self.pitch = pitch
    }
}

/// Only stored and compared on Android: the MapLibre map takes its camera from GoogleCamera.
indirect enum MapCameraPosition: Equatable, Sendable {
    case automatic
    case rect(MKMapRect)
    case region(MKCoordinateRegion)
    case camera(MapCamera)
    case userLocation(followsHeading: Bool, fallback: MapCameraPosition)
    static func userLocation(fallback: MapCameraPosition) -> MapCameraPosition {
        .userLocation(followsHeading: false, fallback: fallback)
    }
}
#endif
