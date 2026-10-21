import SwiftUI
#if canImport(MapKit)
import MapKit
#endif
#if canImport(UIKit)
import UIKit
#endif

extension LiveVehicle: RouteStyled {
    var routeColor: String? { colorHex }
    var routeTextColor: String? { nil }
    var routeShortName: String? { name }
    var displayName: String? { nil }
}

/// Something tapped on the transit map.
enum MapSelection: Identifiable {
    case station(MapStation)
    case vehicle(LiveVehicle)
    case rentalStation(RentalsResponse.Station, provider: String)
    case rentalVehicle(RentalsResponse.Vehicle, provider: String)
    /// A hit of the map search (town, address, stop, place).
    case place(GeocodeMatch)

    var id: String {
        switch self {
        case .station(let s): return "s|\(s.id)"
        case .vehicle(let v): return "v|\(v.id)"
        case .rentalStation(let s, _): return "rs|\(s.id)"
        case .rentalVehicle(let v, _): return "rv|\(v.id)"
        case .place(let m): return "p|\(m.id)"
        }
    }
}

/// Where the map should fly after a search; a new version moves it again (also to the same place).
struct MapFocus: Equatable {
    let version: Int
    let match: GeocodeMatch
    /// False for a place picked on the map itself: it is already in view, the map only marks it.
    var moves = true
}

/// Map content with a version number each: the map view only diffs what changed.
struct TransitMapContent {
    var stations: [MapStation] = []
    var stationsVersion = 0
    var shapes: [LineShape] = []
    var shapesVersion = 0
    var vehicles: [LiveVehicle] = []
    var vehiclesVersion = 0
    var rentals: RentalsResponse = RentalsResponse()
    var rentalsVersion = 0
}

#if !os(Android)
/// MKMapView instead of SwiftUI's Map: hundreds of line pieces are drawn as one overlay per colour,
/// pins are reused, and moving vehicles only change their coordinate (no SwiftUI diffing per second).
struct TransitMapView: UIViewRepresentable {
    let content: TransitMapContent
    let initialCenter: LatLon?
    var look = MapLook()
    /// Search hit to show with a marker; nil removes the marker.
    var focus: MapFocus?
    /// Seconds a vehicle takes to glide to its next position (the refresh tick).
    let glide: TimeInterval
    let onRegion: (MKCoordinateRegion) -> Void
    let onSelect: (MapSelection) -> Void
    /// A place picked on the map: a tapped Apple Maps point of interest (museum, shop, beach …)
    /// or a long press anywhere. Called again with `refined` true and a better name once the address is known.
    var onPick: ((_ place: GeocodeMatch, _ refined: Bool) -> Void)? = nil
    /// A tap on empty map (no pin, station or place): closes what is open.
    var onTapEmpty: (() -> Void)? = nil
    /// Raised by the "My location" control: the map follows you again.
    var recenter = 0
    /// "Find nearby" hits (all restaurants, pharmacies … in view), as coloured Apple Maps style markers.
    var places: [GeocodeMatch] = []
    var placesVersion = 0

    func makeCoordinator() -> Coordinator { Coordinator(self) }

    func makeUIView(context: Context) -> MKMapView {
        let map = MKMapView()
        map.delegate = context.coordinator
        map.showsUserLocation = true
        map.showsCompass = true
        map.showsScale = true
        map.isRotateEnabled = true
        context.coordinator.applyLook(look, to: map, animated: false)
        map.register(StationPinView.self, forAnnotationViewWithReuseIdentifier: StationPinView.reuseId)
        map.register(VehiclePinView.self, forAnnotationViewWithReuseIdentifier: VehiclePinView.reuseId)
        map.register(RentalPinView.self, forAnnotationViewWithReuseIdentifier: RentalPinView.reuseId)
        map.register(MKMarkerAnnotationView.self, forAnnotationViewWithReuseIdentifier: SearchPin.reuseId)
        map.register(MKMarkerAnnotationView.self, forAnnotationViewWithReuseIdentifier: PlacePin.reuseId)
        map.register(MKMarkerAnnotationView.self, forAnnotationViewWithReuseIdentifier: MKMapViewDefaultClusterAnnotationViewReuseIdentifier)
        // Long press drops a pin anywhere ("I want to go there"), like in Apple Maps.
        let press = UILongPressGestureRecognizer(target: context.coordinator, action: #selector(Coordinator.longPressed(_:)))
        press.minimumPressDuration = 0.5
        map.addGestureRecognizer(press)
        let tap = UITapGestureRecognizer(target: context.coordinator, action: #selector(Coordinator.tapped(_:)))
        tap.cancelsTouchesInView = false
        tap.delegate = context.coordinator
        map.addGestureRecognizer(tap)
        if let c = initialCenter {
            map.setRegion(MKCoordinateRegion(center: c.clCoordinate, latitudinalMeters: 1600, longitudinalMeters: 1600), animated: false)
        } else {
            map.setUserTrackingMode(.follow, animated: false)
        }
        // "My location" is a SwiftUI map control (white, round), next to the layers button: see `recenter`.
        context.coordinator.recentered = recenter
        return map
    }

    func updateUIView(_ map: MKMapView, context: Context) {
        context.coordinator.parent = self
        context.coordinator.apply(content, to: map)
        context.coordinator.applyLook(look, to: map, animated: true)
        context.coordinator.applyFocus(focus, to: map)
        context.coordinator.applyPlaces(places, version: placesVersion, to: map)
        if recenter != context.coordinator.recentered {
            context.coordinator.recentered = recenter
            map.setUserTrackingMode(.follow, animated: true)
        }
    }

    /// Apple's map configuration for a look. Public transport POIs stay off: the stations are our own pins.
    static func configuration(_ look: MapLook) -> MKMapConfiguration {
        let elevation: MKMapConfiguration.ElevationStyle = look.realistic3D ? .realistic : .flat
        let poi: MKPointOfInterestFilter = look.showsPointsOfInterest ? MKPointOfInterestFilter(excluding: [.publicTransport]) : .excludingAll
        switch look.base {
        case .standard, .muted:
            let c = MKStandardMapConfiguration(elevationStyle: elevation, emphasisStyle: look.base == .muted ? .muted : .default)
            c.pointOfInterestFilter = poi
            c.showsTraffic = look.showsTraffic
            return c
        case .satellite:
            let c = MKHybridMapConfiguration(elevationStyle: elevation)
            c.pointOfInterestFilter = poi
            c.showsTraffic = look.showsTraffic
            return c
        case .imagery:
            return MKImageryMapConfiguration(elevationStyle: elevation)
        }
    }

    final class Coordinator: NSObject, MKMapViewDelegate, UIGestureRecognizerDelegate {
        var parent: TransitMapView
        private var versions = (stations: -1, shapes: -1, vehicles: -1, rentals: -1)
        private var stationPins: [String: StationPin] = [:]
        private var vehiclePins: [String: VehiclePin] = [:]
        private var rentalPins: [String: RentalPin] = [:]
        private var overlays: [MKOverlay] = []
        private var reportedRegion = false
        private var appliedLook: MapLook?
        private var focusVersion = 0
        private var searchPin: SearchPin?
        private var placePins: [String: PlacePin] = [:]
        private var placesVersion = 0
        /// When something on the map was last selected: a tap that selects is not a tap on empty map.
        private var lastSelect = Date.distantPast
        var recentered = 0

        init(_ parent: TransitMapView) { self.parent = parent }

        /// Swaps the configuration only when the look changed (a new one makes MapKit reload its tiles).
        func applyLook(_ look: MapLook, to map: MKMapView, animated: Bool) {
            guard look != appliedLook else { return }
            let before = appliedLook
            appliedLook = look
            map.preferredConfiguration = TransitMapView.configuration(look)
            // Apple's points of interest can be tapped when they are shown; transit stays our own pins.
            map.selectableMapFeatures = look.showsPointsOfInterest ? [.pointsOfInterest] : []
            // Names and pins stay readable on aerial imagery: dark style there, like Apple Maps.
            map.overrideUserInterfaceStyle = look.base.isSatellite ? .dark : .unspecified
            // Switching 3D on tilts the map so it shows; off lays it flat again.
            if let before, before.realistic3D != look.realistic3D, animated {
                let camera = map.camera.copy() as! MKMapCamera
                camera.pitch = look.realistic3D ? 55 : 0
                map.setCamera(camera, animated: true)
            }
        }

        /// Flies to a new search hit and marks it; the hit sits in the upper part, above a half-open sheet.
        func applyFocus(_ focus: MapFocus?, to map: MKMapView) {
            guard let focus else {
                if let old = searchPin { map.removeAnnotation(old); searchPin = nil }
                return
            }
            guard focus.version != focusVersion else { return }
            focusVersion = focus.version
            let m = focus.match
            if let old = searchPin { map.removeAnnotation(old) }
            let pin = SearchPin(m)
            searchPin = pin
            map.addAnnotation(pin)
            guard focus.moves else { return }
            let meters = MapSearch.meters(m)
            let center = CLLocationCoordinate2D(latitude: m.lat - meters / 111_000 * 0.22, longitude: m.lon)
            guard CLLocationCoordinate2DIsValid(center) else { return }
            map.setUserTrackingMode(.none, animated: false)
            map.setRegion(MKCoordinateRegion(center: center, latitudinalMeters: meters, longitudinalMeters: meters), animated: true)
        }

        /// Replaces the "Find nearby" markers when a new set arrives.
        func applyPlaces(_ places: [GeocodeMatch], version: Int, to map: MKMapView) {
            guard version != placesVersion else { return }
            placesVersion = version
            diff(places.map { ($0.id, $0) }, into: &placePins, map: map, make: PlacePin.init, update: { _, _ in })
        }

        func apply(_ c: TransitMapContent, to map: MKMapView) {
            if versions.stations != c.stationsVersion {
                versions.stations = c.stationsVersion
                diff(c.stations.filter { $0.coordinate.isValidCoordinate }.map { ($0.id, $0) }, into: &stationPins, map: map, make: StationPin.init, update: { pin, station in
                    pin.station = station
                    pin.coordinate = station.coordinate.clCoordinate
                    if let view = map.view(for: pin) as? StationPinView { view.show(station) }
                })
            }
            if versions.shapes != c.shapesVersion {
                versions.shapes = c.shapesVersion
                setShapes(c.shapes, on: map)
            }
            if versions.vehicles != c.vehiclesVersion {
                versions.vehicles = c.vehiclesVersion
                let glide = parent.glide
                diff(c.vehicles.filter { $0.coordinate.isValidCoordinate }.map { ($0.id, $0) }, into: &vehiclePins, map: map, make: VehiclePin.init) { pin, v in
                    let changedLook = pin.vehicle.name != v.name || pin.vehicle.colorHex != v.colorHex
                    pin.vehicle = v
                    let target = v.coordinate.clCoordinate
                    if abs(pin.coordinate.latitude - target.latitude) > 1e-7 || abs(pin.coordinate.longitude - target.longitude) > 1e-7 {
                        UIView.animate(withDuration: glide, delay: 0, options: [.curveLinear, .allowUserInteraction, .beginFromCurrentState]) {
                            pin.coordinate = target
                        }
                    }
                    if let view = map.view(for: pin) as? VehiclePinView { view.show(v, restyle: changedLook) }
                }
            }
            if versions.rentals != c.rentalsVersion {
                versions.rentals = c.rentalsVersion
                let r = c.rentals
                let items: [(String, RentalPin.Item)] =
                    r.stations.filter { $0.coordinate.isValidCoordinate && $0.isRenting != false }.map { ("s|\($0.providerGroupId)|\($0.id)", .station($0, provider: r.groupName($0.providerGroupId))) }
                    + r.vehicles.filter { $0.isAvailable && $0.coordinate.isValidCoordinate }.map { ("v|\($0.providerGroupId)|\($0.id)", .vehicle($0, provider: r.groupName($0.providerGroupId))) }
                diff(items, into: &rentalPins, map: map, make: RentalPin.init, update: { pin, item in
                    pin.update(item)
                    if let view = map.view(for: pin) as? RentalPinView { view.show(pin) }
                })
            }
        }

        /// Adds new pins, removes gone ones, updates the rest in place.
        private func diff<Value, Pin: MKAnnotation>(_ items: [(String, Value)], into pins: inout [String: Pin], map: MKMapView,
                                                     make: (Value) -> Pin, update: (Pin, Value) -> Void) {
            var keep = Set<String>()
            var added: [Pin] = []
            for (id, value) in items {
                keep.insert(id)
                if let pin = pins[id] { update(pin, value) } else {
                    let pin = make(value)
                    pins[id] = pin
                    added.append(pin)
                }
            }
            let gone = pins.filter { !keep.contains($0.key) }
            for k in gone.keys { pins[k] = nil }
            if !gone.isEmpty { map.removeAnnotations(Array(gone.values)) }
            if !added.isEmpty { map.addAnnotations(added) }
        }

        /// One overlay per colour and mode instead of one per piece of line.
        private func setShapes(_ shapes: [LineShape], on map: MKMapView) {
            var groups: [String: (mode: String, color: String, lines: [MKPolyline])] = [:]
            var order: [String] = []
            for s in shapes {
                let key = "\(LiveMap.rank(s.mode))|\(s.colorHex)"
                let coords = s.points.filter(\.isValidCoordinate).map(\.clCoordinate)
                guard coords.count >= 2 else { continue }
                let line = MKPolyline(coordinates: coords, count: coords.count)
                if groups[key] == nil { order.append(key); groups[key] = (s.mode, s.colorHex, []) }
                groups[key]?.lines.append(line)
            }
            // Like Apple Maps' transit view: every line on a light edge (casing), so lines lead and
            // streets stay in the background. All edges first, then the lines on top of them.
            let made: [(casing: LineOverlay, line: LineOverlay)] = order.compactMap { key in
                guard let g = groups[key] else { return nil }
                let casing = LineOverlay(g.lines)
                casing.colorHex = g.color
                casing.mode = g.mode
                casing.isCasing = true
                let multi = LineOverlay(g.lines)
                multi.colorHex = g.color
                multi.mode = g.mode
                return (casing, multi)
            }
            let fresh: [MKOverlay] = made.map(\.casing) + made.map(\.line)
            map.removeOverlays(overlays)
            map.addOverlays(fresh, level: .aboveRoads)
            overlays = fresh
        }

        // MARK: MKMapViewDelegate

        func mapView(_ mapView: MKMapView, regionDidChangeAnimated animated: Bool) {
            reportedRegion = true
            parent.onRegion(mapView.region)
        }

        /// The first region also when MapKit sends no region change for the start position
        /// (then nothing loaded until the map was moved).
        func mapViewDidFinishRenderingMap(_ mapView: MKMapView, fullyRendered: Bool) {
            guard !reportedRegion, mapView.bounds.width > 0 else { return }
            reportedRegion = true
            parent.onRegion(mapView.region)
        }

        func mapView(_ mapView: MKMapView, rendererFor overlay: MKOverlay) -> MKOverlayRenderer {
            guard let line = overlay as? LineOverlay else { return MKOverlayRenderer(overlay: overlay) }
            let r = MKMultiPolylineRenderer(multiPolyline: line)
            // Near-black lines are lifted on dark maps (satellite maps are dark too), pale ones darkened on light maps.
            let dark = mapView.traitCollection.userInterfaceStyle == .dark
            if line.isCasing {
                r.strokeColor = dark ? UIColor(white: 0.08, alpha: 0.9) : UIColor(white: 1, alpha: 0.95)
                r.lineWidth = LineOverlay.width(line.mode) + 3
            } else {
                r.strokeColor = UIColor(hex: LineColors.strokeHex(line.colorHex, dark: dark))
                r.lineWidth = LineOverlay.width(line.mode)
            }
            r.lineCap = .round
            r.lineJoin = .round
            return r
        }

        func mapView(_ mapView: MKMapView, viewFor annotation: MKAnnotation) -> MKAnnotationView? {
            switch annotation {
            case let pin as StationPin:
                let v = mapView.dequeueReusableAnnotationView(withIdentifier: StationPinView.reuseId, for: pin) as! StationPinView
                v.show(pin.station)
                return v
            case let pin as VehiclePin:
                let v = mapView.dequeueReusableAnnotationView(withIdentifier: VehiclePinView.reuseId, for: pin) as! VehiclePinView
                v.show(pin.vehicle, restyle: true)
                return v
            case let pin as RentalPin:
                let v = mapView.dequeueReusableAnnotationView(withIdentifier: RentalPinView.reuseId, for: pin) as! RentalPinView
                v.show(pin)
                return v
            case let pin as PlacePin:
                let v = mapView.dequeueReusableAnnotationView(withIdentifier: PlacePin.reuseId, for: pin) as! MKMarkerAnnotationView
                let category = PlaceCategory.of(pin.match.category)
                v.markerTintColor = category.map { UIColor(hex: $0.colorHex) } ?? UIColor(Color.ink)
                v.glyphTintColor = .white
                v.glyphImage = UIImage(systemName: category?.symbol ?? "mappin")
                v.titleVisibility = .adaptive
                v.subtitleVisibility = .hidden
                v.displayPriority = .defaultHigh
                v.zPriority = .init(rawValue: 600)
                v.collisionMode = .circle
                v.accessibilityLabel = pin.match.name
                return v
            case let pin as SearchPin:
                let v = mapView.dequeueReusableAnnotationView(withIdentifier: SearchPin.reuseId, for: pin) as! MKMarkerAnnotationView
                // Its category's colour like in Apple Maps; ink otherwise, not the yellow dot (that one only means "you" and "live").
                let category = PlaceCategory.of(pin.match.category)
                v.markerTintColor = category.map { UIColor(hex: $0.colorHex) } ?? UIColor(Color.ink)
                v.glyphTintColor = category == nil ? UIColor(Color.onInk) : .white
                v.glyphImage = UIImage(systemName: pin.glyph)
                v.titleVisibility = .visible
                v.displayPriority = .required
                v.zPriority = .max
                v.collisionMode = .none
                v.accessibilityLabel = pin.match.name
                return v
            case let cluster as MKClusterAnnotation:
                let v = mapView.dequeueReusableAnnotationView(withIdentifier: MKMapViewDefaultClusterAnnotationViewReuseIdentifier,
                                                              for: cluster) as! MKMarkerAnnotationView
                v.markerTintColor = RentalPinView.tint
                v.glyphText = "\(cluster.memberAnnotations.count)"
                v.displayPriority = .defaultLow
                return v
            default:
                return nil
            }
        }

        func mapView(_ mapView: MKMapView, didSelect annotation: MKAnnotation) {
            lastSelect = Date()
            defer { mapView.deselectAnnotation(annotation, animated: false) }
            switch annotation {
            case let feature as MKMapFeatureAnnotation: pick(feature)
            case let pin as StationPin: parent.onSelect(.station(pin.station))
            case let pin as VehiclePin: parent.onSelect(.vehicle(pin.vehicle))
            case let pin as SearchPin: parent.onSelect(.place(pin.match))
            case let pin as PlacePin: parent.onSelect(.place(pin.match))
            case let pin as RentalPin:
                switch pin.item {
                case .station(let s, let p): parent.onSelect(.rentalStation(s, provider: p))
                case .vehicle(let v, let p): parent.onSelect(.rentalVehicle(v, provider: p))
                }
            case let cluster as MKClusterAnnotation:
                let r = MKCoordinateRegion(center: cluster.coordinate, span: MKCoordinateSpan(
                    latitudeDelta: mapView.region.span.latitudeDelta / 3, longitudeDelta: mapView.region.span.longitudeDelta / 3))
                mapView.setRegion(r, animated: true)
            default: break
            }
        }

        // MARK: Picking places on the map

        private let geocoder = CLGeocoder()

        /// A tapped point of interest: shown at once under its map label, then with address and category.
        private func pick(_ feature: MKMapFeatureAnnotation) {
            let point = LatLon(lat: feature.coordinate.latitude, lon: feature.coordinate.longitude)
            guard point.isValidCoordinate else { return }
            let label: String? = (feature as MKAnnotation).title ?? nil
            let name = label.flatMap { $0.isEmpty ? nil : $0 } ?? L10n.t("Dropped pin")
            parent.onPick?(.picked(name: name, at: point), false)
            MKMapItemRequest(mapFeatureAnnotation: feature).getMapItem { [weak self] item, _ in
                guard let self, let item else { return }
                DispatchQueue.main.async {
                    self.parent.onPick?(Self.match(item, name: item.name ?? name, at: point), true)
                }
            }
        }

        @objc func tapped(_ gesture: UITapGestureRecognizer) {
            guard gesture.state == .ended, parent.onTapEmpty != nil else { return }
            // MapKit selects a tapped place a moment after the tap; wait for it before calling the map empty.
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.35) { [weak self] in
                guard let self, Date().timeIntervalSince(self.lastSelect) > 0.6 else { return }
                self.parent.onTapEmpty?()
            }
        }

        func gestureRecognizer(_ g: UIGestureRecognizer, shouldRecognizeSimultaneouslyWith other: UIGestureRecognizer) -> Bool { true }

        func gestureRecognizer(_ g: UIGestureRecognizer, shouldReceive touch: UITouch) -> Bool {
            guard g is UITapGestureRecognizer else { return true }
            var v: UIView? = touch.view
            while let view = v {
                if view is MKAnnotationView || view is UIControl { return false }
                v = view.superview
            }
            return true
        }

        @objc func longPressed(_ gesture: UILongPressGestureRecognizer) {
            guard gesture.state == .began, let map = gesture.view as? MKMapView else { return }
            let c = map.convert(gesture.location(in: map), toCoordinateFrom: map)
            let point = LatLon(lat: c.latitude, lon: c.longitude)
            guard point.isValidCoordinate else { return }
            UIImpactFeedbackGenerator(style: .medium).impactOccurred()
            parent.onPick?(.picked(name: L10n.t("Dropped pin"), at: point), false)
            geocoder.cancelGeocode()
            geocoder.reverseGeocodeLocation(CLLocation(latitude: c.latitude, longitude: c.longitude)) { [weak self] marks, _ in
                guard let self, let mark = marks?.first else { return }
                let name = [mark.thoroughfare, mark.subThoroughfare].compactMap { $0 }.joined(separator: " ")
                let match = GeocodeMatch.picked(name: name.isEmpty ? (mark.name ?? L10n.t("Dropped pin")) : name, at: point,
                                                locality: mark.locality, country: mark.isoCountryCode)
                DispatchQueue.main.async { self.parent.onPick?(match, true) }
            }
        }

        /// Apple's place in the same shape as a search hit; the position stays the tapped one, so the ID does not change.
        static func match(_ item: MKMapItem, name: String, at point: LatLon) -> GeocodeMatch {
            let mark = item.placemark
            return .picked(name: name, at: point, street: mark.thoroughfare, houseNumber: mark.subThoroughfare,
                           locality: mark.locality, country: mark.isoCountryCode,
                           category: item.pointOfInterestCategory.map(MapPick.category))
        }
    }
}

/// Apple's point-of-interest categories as short words for the place sheet ("museum", "beach").
enum MapPick {
    static func category(_ c: MKPointOfInterestCategory) -> String {
        var raw = c.rawValue
        if raw.hasPrefix("MKPOICategory") { raw.removeFirst("MKPOICategory".count) }
        return raw.lowercased()
    }
}

// MARK: - Annotations and overlays

final class LineOverlay: MKMultiPolyline {
    var colorHex = "4D4D59"
    var mode = "BUS"
    /// The light edge drawn under the line.
    var isCasing = false

    /// Bolder than before: the lines are what this map is about.
    static func width(_ mode: String) -> CGFloat {
        switch ModeGroup.of(mode) {
        case .train, .suburban, .subway: return 5
        case .tram: return 4
        default: return 3
        }
    }
}

final class StationPin: NSObject, MKAnnotation {
    var station: MapStation
    @objc dynamic var coordinate: CLLocationCoordinate2D
    var title: String? { station.name }
    init(_ s: MapStation) { station = s; coordinate = s.coordinate.clCoordinate }
}

final class VehiclePin: NSObject, MKAnnotation {
    @objc dynamic var coordinate: CLLocationCoordinate2D
    var vehicle: LiveVehicle
    var title: String? { vehicle.name }
    init(_ v: LiveVehicle) { vehicle = v; coordinate = v.coordinate.clCoordinate }
}

/// The place found by the map search.
final class SearchPin: NSObject, MKAnnotation {
    static let reuseId = "search"
    let match: GeocodeMatch
    let coordinate: CLLocationCoordinate2D
    var title: String? { match.name }
    var glyph: String {
        if let c = PlaceCategory.of(match.category) { return c.symbol }
        switch match.type {
        case "STOP": return "tram.fill"
        case "ADDRESS": return "house.fill"
        default: return MapSearch.meters(match) >= 2500 ? "building.2.fill" : "mappin"
        }
    }
    init(_ m: GeocodeMatch) { match = m; coordinate = CLLocationCoordinate2D(latitude: m.lat, longitude: m.lon) }
}

/// One hit of "Find nearby".
final class PlacePin: NSObject, MKAnnotation {
    static let reuseId = "place"
    let match: GeocodeMatch
    let coordinate: CLLocationCoordinate2D
    var title: String? { match.name }
    init(_ m: GeocodeMatch) { match = m; coordinate = CLLocationCoordinate2D(latitude: m.lat, longitude: m.lon) }
}

final class RentalPin: NSObject, MKAnnotation {
    enum Item {
        case station(RentalsResponse.Station, provider: String)
        case vehicle(RentalsResponse.Vehicle, provider: String)
    }
    var item: Item
    @objc dynamic var coordinate: CLLocationCoordinate2D
    var title: String? {
        switch item {
        case .station(let s, _): return s.name
        case .vehicle(_, let p): return p
        }
    }
    func update(_ item: Item) {
        self.item = item
        switch item {
        case .station(let s, _): coordinate = s.coordinate.clCoordinate
        case .vehicle(let v, _): coordinate = v.coordinate.clCoordinate
        }
    }

    init(_ item: Item) {
        self.item = item
        switch item {
        case .station(let s, _): coordinate = s.coordinate.clCoordinate
        case .vehicle(let v, _): coordinate = v.coordinate.clCoordinate
        }
    }
}

extension UIColor {
    convenience init(hex: String) {
        let rgb = LineColors.RGB(hex: hex) ?? LineColors.RGB(hex: "4D4D59")!
        self.init(red: rgb.r, green: rgb.g, blue: rgb.b, alpha: 1)
    }
}

/// Round mode icon; major stations also show their name. The label is part of the view's frame,
/// so MapKit hides overlapping names instead of drawing them on top of each other.
final class StationPinView: MKAnnotationView {
    static let reuseId = "station"
    private static var cache: [String: UIImage] = [:]
    private let iconView = UIImageView()
    private let label = UILabel()

    override init(annotation: MKAnnotation?, reuseIdentifier: String?) {
        super.init(annotation: annotation, reuseIdentifier: reuseIdentifier)
        collisionMode = .circle
        label.font = .systemFont(ofSize: 13, weight: .semibold)
        isAccessibilityElement = true
        accessibilityTraits = .button
        label.textColor = .label
        label.textAlignment = .center
        label.lineBreakMode = .byTruncatingTail
        label.layer.shadowRadius = 2
        label.layer.shadowOpacity = 1
        label.layer.shadowOffset = .zero
        addSubview(iconView)
        addSubview(label)
        updateColors()
        // Layer colours do not follow dark mode by themselves (satellite maps switch to dark).
        registerForTraitChanges([UITraitUserInterfaceStyle.self]) { (view: StationPinView, _: UITraitCollection) in view.updateColors() }
    }

    private func updateColors() {
        label.layer.shadowColor = UIColor.systemBackground.resolvedColor(with: traitCollection).cgColor
    }

    required init?(coder: NSCoder) { fatalError("not used") }

    func show(_ s: MapStation) {
        let size: CGFloat = s.isMajor ? 24 : (s.isPole ? 16 : 18)
        image = nil
        iconView.image = Self.icon(mode: s.mainMode, size: size)
        displayPriority = s.isMajor ? .defaultHigh : .defaultLow
        zPriority = s.isMajor ? .init(rawValue: 400) : .init(rawValue: 300)
        accessibilityLabel = s.name
        if s.isMajor || s.isPole {
            label.isHidden = false
            label.text = MapStation.shortName(s.name)
            let text = label.sizeThatFits(CGSize(width: 130, height: 20))
            let w = max(size, min(130, ceil(text.width)))
            let h = max(44, size + 4 + ceil(text.height))
            frame.size = CGSize(width: w, height: h)
            iconView.frame = CGRect(x: (w - size) / 2, y: 0, width: size, height: size)
            label.frame = CGRect(x: 0, y: size + 4, width: w, height: ceil(text.height))
            // Keep the icon (not the whole block) on the coordinate.
            centerOffset = CGPoint(x: 0, y: h / 2 - size / 2)
            collisionMode = .rectangle
        } else {
            label.isHidden = true
            label.text = nil
            frame.size = CGSize(width: 44, height: 44)
            iconView.frame = CGRect(x: (44 - size) / 2, y: (44 - size) / 2, width: size, height: size)
            centerOffset = .zero
            collisionMode = .circle
        }
    }

    static func icon(mode: String, size: CGFloat) -> UIImage {
        let key = "\(mode)|\(size)"
        if let hit = cache[key] { return hit }
        let img = UIGraphicsImageRenderer(size: CGSize(width: size, height: size)).image { ctx in
            let rect = CGRect(x: 0, y: 0, width: size, height: size)
            // A rounded square with the mode's symbol, as Apple Maps marks stations.
            let shape = UIBezierPath(roundedRect: rect.insetBy(dx: 1, dy: 1), cornerRadius: size * 0.3)
            UIColor(hex: ModeStyle.defaultHex(mode)).setFill()
            shape.fill()
            UIColor.white.setStroke()
            shape.lineWidth = 1.5
            shape.stroke()
            let cfg = UIImage.SymbolConfiguration(pointSize: size * 0.48, weight: .bold)
            if let sym = UIImage(systemName: ModeStyle.symbol(mode), withConfiguration: cfg)?.withTintColor(.white, renderingMode: .alwaysOriginal) {
                sym.draw(in: CGRect(x: (size - sym.size.width) / 2, y: (size - sym.size.height) / 2, width: sym.size.width, height: sym.size.height))
            }
        }
        cache[key] = img
        return img
    }
}

/// Line name on the line colour, with a small arrow for the direction of travel.
final class VehiclePinView: MKAnnotationView {
    static let reuseId = "vehicle"
    private let badge = UILabel()
    private let arrow = CAShapeLayer()

    override init(annotation: MKAnnotation?, reuseIdentifier: String?) {
        super.init(annotation: annotation, reuseIdentifier: reuseIdentifier)
        displayPriority = .required
        zPriority = .max
        collisionMode = .none
        badge.font = .systemFont(ofSize: 13, weight: .bold)
        badge.adjustsFontSizeToFitWidth = true
        badge.minimumScaleFactor = 0.85
        isAccessibilityElement = true
        accessibilityTraits = .button
        badge.textAlignment = .center
        badge.layer.cornerRadius = 5
        badge.layer.masksToBounds = true
        badge.layer.borderColor = UIColor.white.cgColor
        badge.layer.borderWidth = 1.5
        addSubview(badge)
        let path = UIBezierPath()
        path.move(to: CGPoint(x: 0, y: -5)); path.addLine(to: CGPoint(x: 4, y: 2)); path.addLine(to: CGPoint(x: -4, y: 2)); path.close()
        arrow.path = path.cgPath
        layer.addSublayer(arrow)
        updateColors()
        registerForTraitChanges([UITraitUserInterfaceStyle.self]) { (view: VehiclePinView, _: UITraitCollection) in view.updateColors() }
    }

    private func updateColors() {
        arrow.fillColor = UIColor.label.resolvedColor(with: traitCollection).cgColor
    }

    required init?(coder: NSCoder) { fatalError("not used") }

    func show(_ v: LiveVehicle, restyle: Bool) {
        if restyle {
            let colors = v.lineColors
            badge.text = v.lineName
            badge.backgroundColor = UIColor(hex: colors.background)
            badge.textColor = UIColor(hex: colors.text)
            let w = min(140, max(32, badge.intrinsicContentSize.width + 12))
            frame.size = CGSize(width: max(44, w), height: 44)
            badge.frame = CGRect(x: (bounds.width - w) / 2, y: 10, width: w, height: 24)
            centerOffset = .zero
            accessibilityLabel = "\(ModeStyle.label(v.mode)) \(v.lineName)"
        }
        // The arrow sits on the badge's edge in the direction of travel.
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        if let h = v.heading, h.isFinite {
            arrow.isHidden = false
            let rad = h * .pi / 180
            let r = max(bounds.width, bounds.height) / 2 + 4
            arrow.position = CGPoint(x: bounds.midX + CGFloat(sin(rad)) * r, y: bounds.midY - CGFloat(cos(rad)) * r)
            arrow.setAffineTransform(CGAffineTransform(rotationAngle: CGFloat(rad)))
        } else {
            arrow.isHidden = true
        }
        CATransaction.commit()
    }
}

/// Sharing station/vehicle with readable context and a 44pt-or-larger hit target.
final class RentalPinView: MKAnnotationView {
    static let reuseId = "rental"
    static let tint = UIColor(red: 0, green: 0.48, blue: 0.44, alpha: 1)
    private let iconView = UIImageView()
    private let label = UILabel()

    override init(annotation: MKAnnotation?, reuseIdentifier: String?) {
        super.init(annotation: annotation, reuseIdentifier: reuseIdentifier)
        clusteringIdentifier = "rental"
        displayPriority = .defaultHigh
        collisionMode = .rectangle
        isAccessibilityElement = true
        accessibilityTraits = .button
        iconView.contentMode = .scaleAspectFit
        iconView.tintColor = .white
        iconView.backgroundColor = Self.tint
        iconView.layer.borderColor = UIColor.white.cgColor
        iconView.layer.borderWidth = 1.5
        label.font = .systemFont(ofSize: 12, weight: .semibold)
        label.textColor = .label
        label.textAlignment = .center
        label.lineBreakMode = .byTruncatingTail
        label.backgroundColor = UIColor.systemBackground.withAlphaComponent(0.9)
        label.layer.cornerRadius = 4
        label.layer.masksToBounds = true
        addSubview(iconView); addSubview(label)
    }
    required init?(coder: NSCoder) { fatalError("not used") }

    func show(_ pin: RentalPin) {
        image = nil
        let kind: RentalKind, station: Bool, text: String, spoken: String
        switch pin.item {
        case .station(let s, let provider):
            let kinds = (s.formFactors ?? []).map { RentalKind($0) }
            kind = kinds.contains(.bicycle) ? .bicycle : kinds.first ?? .other
            station = true
            text = s.numVehiclesAvailable.map { "\(kind.title) · \($0)" } ?? MapStation.shortName(s.name)
            spoken = [s.name, provider, kind.title, s.numVehiclesAvailable.map { L10n.f("Available now: %lld", $0) }].compactMap { $0 }.joined(separator: ", ")
        case .vehicle(let v, let provider):
            kind = RentalKind(v.formFactor); station = false
            text = provider.isEmpty ? kind.title : provider
            spoken = [provider, kind.title, L10n.t("Available now")].filter { !$0.isEmpty }.joined(separator: ", ")
        }
        clusteringIdentifier = station ? "rental-station" : "rental-\(kind.rawValue)"
        iconView.image = UIImage(systemName: kind.symbol, withConfiguration: UIImage.SymbolConfiguration(pointSize: 17, weight: .semibold))
        iconView.layer.cornerRadius = station ? 8 : 16
        label.text = text
        let w = max(64, min(140, label.intrinsicContentSize.width + 10))
        frame.size = CGSize(width: w, height: 54)
        iconView.frame = CGRect(x: (w - 32) / 2, y: 0, width: 32, height: 32)
        label.frame = CGRect(x: 0, y: 35, width: w, height: 19)
        centerOffset = CGPoint(x: 0, y: 11)
        accessibilityLabel = spoken
        accessibilityHint = L10n.t("Show rental details")
    }
}

#endif
