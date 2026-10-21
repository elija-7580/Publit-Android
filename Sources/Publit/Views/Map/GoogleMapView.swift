import SwiftUI
#if canImport(UIKit)
import UIKit
#endif
#if canImport(GoogleMaps)
import GoogleMaps
#endif

/// Which map the route and trip maps draw on (Settings → Map). Google needs the API key
/// (the same as for the place search, with "Maps SDK for iOS" enabled); without it Apple's map stays.
enum MapProvider: String, CaseIterable, Identifiable {
    case apple, google

    static let defaultsKey = "mapProvider"

    var id: String { rawValue }
    var title: String {
        switch self {
        case .apple: return L10n.t("Apple Maps")
        case .google: return "Google Maps"
        }
    }

    /// The SDK is in this build.
    static var googleAvailable: Bool {
        #if canImport(GoogleMaps) || os(Android)
        return true
        #else
        return false
        #endif
    }

    /// Google is chosen (stored value from `@AppStorage`) and can be used.
    static func usesGoogle(_ stored: String) -> Bool {
        #if os(Android)
        // Android: the route and trip maps always draw on OpenStreetMap (MapLibre), see AndroidMapView.swift.
        return true
        #else
        googleAvailable && stored == MapProvider.google.rawValue && GooglePlaces.apiKey != nil
        #endif
    }
}

/// What a Google map shows: lines and pins, drawn in this order.
struct GoogleMapContent: Equatable {
    struct Line: Equatable {
        var points: [LatLon]
        var color: Color
        var width: CGFloat
    }
    struct Pin: Equatable {
        enum Kind: Equatable {
            /// A map pin with a name (shown when tapped).
            case marker(title: String, color: Color)
            /// A round dot: vehicle, next turn, start.
            case dot(color: Color, border: Color, size: CGFloat, borderWidth: CGFloat)
        }
        var coordinate: LatLon
        var kind: Kind
    }
    var lines: [Line] = []
    var pins: [Pin] = []
}

/// Where a Google map looks.
enum GoogleCamera: Equatable {
    /// All these points, with a margin.
    case fit([LatLon])
    /// About this many metres around a point.
    case around(LatLon, meters: Double)
    /// Follows you, optionally turned the way you are heading; `fallback` until a fix comes in.
    case follow(heading: Bool, fallback: [LatLon])
}

#if canImport(GoogleMaps)
/// Publit's route and trip maps drawn with the Google Maps SDK.
struct GoogleMapView: UIViewRepresentable {
    var content: GoogleMapContent
    var camera: GoogleCamera
    @AppStorage("mapBase") var storedBase = MapBase.defaultValue
    @AppStorage("mapPOI") var pointsOfInterest = true
    @AppStorage("mapTraffic") var traffic = false
    @Environment(\.colorScheme) var colorScheme

    @MainActor private static var keyProvided = false

    /// The key is handed to the SDK once, before the first map is made.
    @MainActor static func provideKey() {
        guard !keyProvided, let key = GooglePlaces.apiKey else { return }
        GMSServices.provideAPIKey(key)
        keyProvided = true
    }

    func makeCoordinator() -> Coordinator { Coordinator() }

    func makeUIView(context: Context) -> FittingMapView {
        Self.provideKey()
        let map = FittingMapView(options: GMSMapViewOptions())
        map.isMyLocationEnabled = true
        map.settings.compassButton = true
        map.settings.myLocationButton = true
        map.settings.rotateGestures = true
        map.settings.tiltGestures = false
        map.coordinator = context.coordinator
        map.addObserver(context.coordinator, forKeyPath: "myLocation", options: [.new], context: nil)
        context.coordinator.map = map
        return map
    }

    static func dismantleUIView(_ map: FittingMapView, coordinator: Coordinator) {
        map.removeObserver(coordinator, forKeyPath: "myLocation")
    }

    func updateUIView(_ map: FittingMapView, context: Context) {
        let base = MapBase(storageValue: storedBase)
        switch base {
        case .standard, .muted: map.mapType = .normal
        case .satellite: map.mapType = .hybrid
        case .imagery: map.mapType = .satellite
        }
        map.isTrafficEnabled = traffic
        let style = Self.styleJSON(muted: base == .muted, pointsOfInterest: pointsOfInterest, dark: colorScheme == .dark)
        if context.coordinator.style != style {
            context.coordinator.style = style
            map.mapStyle = try? GMSMapStyle(jsonString: style)
        }
        let c = context.coordinator
        if c.content != content || c.dark != (colorScheme == .dark) {
            c.content = content
            c.dark = colorScheme == .dark
            c.draw(content, on: map, traits: map.traitCollection)
        }
        if c.camera != camera {
            let first = c.camera == nil
            c.camera = camera
            map.pending = (camera, !first)
            map.applyPendingCamera()
        }
    }

    /// Stops are Publit's own, so Google's transit stations stay off; other places follow the setting.
    static func styleJSON(muted: Bool, pointsOfInterest: Bool, dark: Bool) -> String {
        var rules = [##"{"featureType":"transit.station","stylers":[{"visibility":"off"}]}"##]
        if !pointsOfInterest { rules.append(##"{"featureType":"poi","stylers":[{"visibility":"off"}]}"##) }
        if muted { rules.append(##"{"stylers":[{"saturation":-70}]}"##) }
        if dark {
            rules += [
                ##"{"elementType":"geometry","stylers":[{"color":"#242f3e"}]}"##,
                ##"{"elementType":"labels.text.fill","stylers":[{"color":"#a3a9b5"}]}"##,
                ##"{"elementType":"labels.text.stroke","stylers":[{"color":"#1b2230"}]}"##,
                ##"{"featureType":"road","elementType":"geometry","stylers":[{"color":"#3a4352"}]}"##,
                ##"{"featureType":"road","elementType":"geometry.stroke","stylers":[{"color":"#2a3240"}]}"##,
                ##"{"featureType":"water","elementType":"geometry","stylers":[{"color":"#17263c"}]}"##,
                ##"{"featureType":"poi.park","elementType":"geometry","stylers":[{"color":"#263c3f"}]}"##,
            ]
        }
        return "[" + rules.joined(separator: ",") + "]"
    }

    @MainActor
    final class Coordinator: NSObject {
        weak var map: FittingMapView?
        var content: GoogleMapContent?
        var camera: GoogleCamera?
        var style: String?
        var dark = false
        private var overlays: [GMSOverlay] = []

        func draw(_ content: GoogleMapContent, on map: GMSMapView, traits: UITraitCollection) {
            overlays.forEach { $0.map = nil }
            overlays = []
            var z: Int32 = 1
            for line in content.lines where line.points.count > 1 {
                let path = GMSMutablePath()
                line.points.forEach { path.add(CLLocationCoordinate2D(latitude: $0.lat, longitude: $0.lon)) }
                let poly = GMSPolyline(path: path)
                poly.strokeColor = UIColor(line.color).resolvedColor(with: traits)
                poly.strokeWidth = line.width
                poly.zIndex = z
                z += 1
                poly.map = map
                overlays.append(poly)
            }
            for pin in content.pins {
                let marker = GMSMarker(position: CLLocationCoordinate2D(latitude: pin.coordinate.lat, longitude: pin.coordinate.lon))
                switch pin.kind {
                case let .marker(title, color):
                    marker.title = title.isEmpty ? nil : title
                    marker.icon = GMSMarker.markerImage(with: UIColor(color).resolvedColor(with: traits))
                case let .dot(color, border, size, borderWidth):
                    marker.icon = Self.dot(UIColor(color).resolvedColor(with: traits),
                                           border: UIColor(border).resolvedColor(with: traits), size: size, borderWidth: borderWidth)
                    marker.groundAnchor = CGPoint(x: 0.5, y: 0.5)
                }
                marker.zIndex = z
                z += 1
                marker.map = map
                overlays.append(marker)
            }
        }

        static func dot(_ fill: UIColor, border: UIColor, size: CGFloat, borderWidth: CGFloat) -> UIImage {
            let side = size + 4
            return UIGraphicsImageRenderer(size: CGSize(width: side, height: side)).image { ctx in
                let rect = CGRect(x: 2, y: 2, width: size, height: size)
                ctx.cgContext.setShadow(offset: CGSize(width: 0, height: 1), blur: 2, color: UIColor.black.withAlphaComponent(0.3).cgColor)
                border.setFill()
                ctx.cgContext.fillEllipse(in: rect)
                ctx.cgContext.setShadow(offset: .zero, blur: 0, color: nil)
                fill.setFill()
                ctx.cgContext.fillEllipse(in: rect.insetBy(dx: borderWidth, dy: borderWidth))
            }
        }

        /// While following, the camera moves with each new fix.
        nonisolated override func observeValue(forKeyPath keyPath: String?, of object: Any?,
                                               change: [NSKeyValueChangeKey: Any]?, context: UnsafeMutableRawPointer?) {
            guard keyPath == "myLocation" else { return }
            MainActor.assumeIsolated { self.follow() }
        }

        private func follow() {
            guard let map, case let .follow(heading, _)? = camera, let location = map.myLocation else { return }
            let bearing = heading && location.course >= 0 ? location.course : map.camera.bearing
            map.animate(to: GMSCameraPosition(target: location.coordinate, zoom: max(map.camera.zoom, 16), bearing: bearing, viewingAngle: 0))
        }
    }

    /// Fits the camera once the map has a size (SwiftUI lays it out after it is made).
    final class FittingMapView: GMSMapView {
        weak var coordinator: Coordinator?
        var pending: (camera: GoogleCamera, animated: Bool)?

        override func layoutSubviews() {
            super.layoutSubviews()
            applyPendingCamera()
        }

        func applyPendingCamera() {
            guard let (camera, animated) = pending, bounds.width > 0, bounds.height > 0 else { return }
            pending = nil
            let update: GMSCameraUpdate
            switch camera {
            case let .fit(points):
                guard let b = Self.bounds(points) else { return }
                update = GMSCameraUpdate.fit(b, withPadding: 40)
            case let .around(center, meters):
                let dLat = meters / 2 / 111_320
                let dLon = dLat / max(cos(center.lat * .pi / 180), 0.01)
                let b = GMSCoordinateBounds(coordinate: CLLocationCoordinate2D(latitude: center.lat - dLat, longitude: center.lon - dLon),
                                            coordinate: CLLocationCoordinate2D(latitude: center.lat + dLat, longitude: center.lon + dLon))
                update = GMSCameraUpdate.fit(b, withPadding: 0)
            case let .follow(_, fallback):
                if let here = myLocation {
                    update = GMSCameraUpdate.setTarget(here.coordinate, zoom: max(self.camera.zoom, 16))
                } else if let b = Self.bounds(fallback) {
                    update = GMSCameraUpdate.fit(b, withPadding: 40)
                } else { return }
            }
            if animated { animate(with: update) } else { moveCamera(update) }
        }

        static func bounds(_ points: [LatLon]) -> GMSCoordinateBounds? {
            guard let first = points.first else { return nil }
            var b = GMSCoordinateBounds(coordinate: CLLocationCoordinate2D(latitude: first.lat, longitude: first.lon),
                                        coordinate: CLLocationCoordinate2D(latitude: first.lat, longitude: first.lon))
            for p in points.dropFirst() { b = b.includingCoordinate(CLLocationCoordinate2D(latitude: p.lat, longitude: p.lon)) }
            return b
        }
    }
}
#endif
