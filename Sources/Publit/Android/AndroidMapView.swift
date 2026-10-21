#if os(Android)
import Foundation
import SwiftUI
import SkipFuse
import SkipBridge

// Publit's maps on Android: OpenStreetMap drawn by MapLibre (Skip/PublitMap.kt). Swift builds a spec
// (base settings as JSON plus one JSON payload per layer with a version), Kotlin only redraws layers
// whose version changed and reports the region and taps back through DroidEvents.mapEvent.

/// A map event from Kotlin.
struct MapEventMessage: Decodable {
    let t: String
    var lat: Double?
    var lon: Double?
    var dlat: Double?
    var dlon: Double?
    var layer: String?
    var id: String?
    var name: String?
    var category: String?
    var street: String?
    var houseNumber: String?
    var locality: String?
    var country: String?
    var refined: Bool?
}

@MainActor final class AndroidMapEvents {
    static let shared = AndroidMapEvents()
    private var handlers: [String: (MapEventMessage) -> Void] = [:]
    /// Encoded layer payloads per map: re-encoded only when the version changes.
    private var cache: [String: [String: (version: Int, json: String)]] = [:]

    func setHandler(_ mapId: String, _ handler: @escaping (MapEventMessage) -> Void) { handlers[mapId] = handler }

    func remove(_ mapId: String) {
        handlers[mapId] = nil
        cache[mapId] = nil
    }

    func receive(_ mapId: String, _ json: String) {
        guard let data = json.data(using: .utf8), let msg = try? JSONDecoder().decode(MapEventMessage.self, from: data) else { return }
        handlers[mapId]?(msg)
    }

    func payload(_ mapId: String, _ layer: String, version: Int, make: () -> String) -> String {
        if let hit = cache[mapId]?[layer], hit.version == version { return hit.json }
        let json = make()
        cache[mapId, default: [:]][layer] = (version, json)
        return json
    }
}

/// JSON text from plain values (dictionaries, arrays, numbers, strings, bools).
enum MapJSON {
    static func encode(_ value: Any) -> String {
        guard JSONSerialization.isValidJSONObject(value),
              let data = try? JSONSerialization.data(withJSONObject: value) else { return "[]" }
        return String(data: data, encoding: .utf8) ?? "[]"
    }

    static func points(_ pts: [LatLon]) -> [[Double]] {
        pts.filter(\.isValidCoordinate).map { [$0.lat, $0.lon] }
    }
}

/// Collects SwiftUI colours for one render; layers refer to them by index (Kotlin resolves them like SkipUI does).
final class MapColors {
    private(set) var colors: [Color] = []
    func index(_ c: Color) -> Int {
        colors.append(c)
        return colors.count - 1
    }
}

/// The MapLibre map as a SwiftUI view.
struct DroidMap: View {
    let mapId: String
    let base: [String: Any]
    /// Layer name, version, payload maker.
    let layers: [(name: String, version: Int, make: () -> String)]
    var colors: [Color] = []

    var body: some View {
        ComposeView { composer() }
    }

    private func composer() -> any JConvertible {
        let spec = try! AnyDynamicObject(className: "publit.module.MapSpec", arguments: [])
        for c in colors {
            if let java = c.Java_view as? JConvertible {
                do { try spec.color(java) as Void } catch { logger.error("map color: \(error)") }
            }
        }
        for l in layers {
            let json = AndroidMapEvents.shared.payload(mapId, l.name, version: l.version, make: l.make)
            do { try spec.layer(l.name, Int32(l.version), json) as Void } catch { logger.error("map layer: \(error)") }
        }
        return try! AnyDynamicObject(className: "publit.module.MapComposer", arguments: [mapId, MapJSON.encode(base), spec])
    }
}

// MARK: - Route and trip maps (the Google map API of the iOS app, drawn on OpenStreetMap)

struct GoogleMapView: View {
    var content: GoogleMapContent
    var camera: GoogleCamera
    @AppStorage("mapBase") var storedBase = MapBase.defaultValue
    @AppStorage("mapPOI") var pointsOfInterest = true
    @Environment(\.colorScheme) var colorScheme
    @State var mapId = UUID().uuidString
    @State var contentVersion = 0
    @State var shown: GoogleMapContent?
    @State var cameraVersion = 0
    @State var shownCamera: GoogleCamera?

    var body: some View {
        let colors = MapColors()
        var lines: [[String: Any]] = []
        for l in content.lines where l.points.count >= 2 {
            lines.append(["pts": MapJSON.points(l.points), "c": colors.index(l.color), "w": Double(l.width)])
        }
        var dots: [[String: Any]] = []
        var markers: [[String: Any]] = []
        for (i, p) in content.pins.enumerated() where p.coordinate.isValidCoordinate {
            switch p.kind {
            case .dot(let color, let border, let size, let borderWidth):
                dots.append(["lat": p.coordinate.lat, "lon": p.coordinate.lon, "c": colors.index(color), "b": colors.index(border),
                             "size": Double(size), "bw": Double(borderWidth)])
            case .marker(let title, let color):
                markers.append(["id": "m\(i)", "lat": p.coordinate.lat, "lon": p.coordinate.lon, "title": title,
                                "c": colors.index(color), "glyph": "mappin", "rank": i])
            }
        }
        let version = content == shown ? contentVersion : contentVersion + 1
        let camVersion = camera == shownCamera ? cameraVersion : cameraVersion + 1
        let base: [String: Any] = [
            "style": MapBase(storageValue: storedBase).androidStyle,
            "dark": colorScheme == .dark,
            "poi": pointsOfInterest,
            "camera": Self.cameraSpec(camera, version: camVersion),
        ]
        let linesJSON = MapJSON.encode(lines), dotsJSON = MapJSON.encode(dots), markersJSON = MapJSON.encode(markers)
        // The colours are resolved by Kotlin each render (dark mode), so their payloads are rebuilt each time too.
        return DroidMap(mapId: mapId, base: base, layers: [
            ("lines", version * 2 + (colorScheme == .dark ? 1 : 0), { linesJSON }),
            ("dots", version * 2 + (colorScheme == .dark ? 1 : 0), { dotsJSON }),
            ("markers", version * 2 + (colorScheme == .dark ? 1 : 0), { markersJSON }),
        ], colors: colors.colors)
        .onAppear { sync() }
        .onChange(of: content) { _, _ in sync() }
        .onChange(of: camera) { _, _ in sync() }
        .onDisappear { AndroidMapEvents.shared.remove(mapId) }
    }

    private func sync() {
        if content != shown { shown = content; contentVersion += 1 }
        if camera != shownCamera { shownCamera = camera; cameraVersion += 1 }
    }

    static func cameraSpec(_ camera: GoogleCamera, version: Int) -> [String: Any] {
        switch camera {
        case .fit(let pts):
            return ["v": version, "type": "fit", "pts": MapJSON.points(pts), "padding": 48]
        case .around(let p, let meters):
            return ["v": version, "type": "around", "lat": p.lat, "lon": p.lon, "meters": meters]
        case .follow(let heading, let fallback):
            return ["v": version, "type": "follow", "heading": heading, "pts": MapJSON.points(fallback), "padding": 48]
        }
    }
}

extension MapBase {
    /// The OpenStreetMap style for this look (Kotlin picks the dark variant itself).
    var androidStyle: String {
        switch self {
        case .standard: return "standard"
        case .muted: return "muted"
        case .satellite: return "satellite"
        case .imagery: return "imagery"
        }
    }
}

// MARK: - The live transit map (stations, lines, vehicles, sharing)

struct TransitMapView: View {
    let content: TransitMapContent
    let initialCenter: LatLon?
    var look = MapLook()
    var focus: MapFocus?
    let glide: TimeInterval
    let onRegion: (MKCoordinateRegion) -> Void
    let onSelect: (MapSelection) -> Void
    var onPick: ((_ place: GeocodeMatch, _ refined: Bool) -> Void)? = nil
    var onTapEmpty: (() -> Void)? = nil
    var recenter = 0
    var places: [GeocodeMatch] = []
    var placesVersion = 0

    @Environment(\.colorScheme) var colorScheme
    @State var mapId = UUID().uuidString
    @State var startCamera: [String: Any]? = nil

    var body: some View {
        let dark = colorScheme == .dark || look.base.isSatellite
        let c = content
        let focusMatch = focus?.match
        let base: [String: Any] = [
            "style": look.base.androidStyle,
            "dark": colorScheme == .dark,
            "poi": look.showsPointsOfInterest,
            "tilt3d": look.realistic3D,
            "glide": glide,
            "recenter": recenter,
            "camera": cameraSpec(),
        ]
        return DroidMap(mapId: mapId, base: base, layers: [
            ("lines", c.shapesVersion * 2 + (dark ? 1 : 0), { Self.shapesJSON(c.shapes, dark: dark) }),
            ("stations", c.stationsVersion, { Self.stationsJSON(c.stations) }),
            ("rentals", c.rentalsVersion, { Self.rentalsJSON(c.rentals) }),
            ("markers", placesVersion * 100_000 + (focus?.version ?? 0) * 2 + (focus == nil ? 0 : 1),
             { Self.markersJSON(places: places, focus: focusMatch) }),
            ("vehicles", c.vehiclesVersion, { Self.vehiclesJSON(c.vehicles) }),
        ])
        .onAppear {
            AndroidMapEvents.shared.setHandler(mapId) { handle($0) }
        }
        .onDisappear { AndroidMapEvents.shared.remove(mapId) }
    }

    /// Start at the given place, else follow the user; later moves come from search hits.
    private func cameraSpec() -> [String: Any] {
        if let focus, focus.moves {
            let m = focus.match
            let meters = MapSearch.meters(m)
            return ["v": focus.version + 1, "type": "around", "lat": m.lat - meters / 111_000 * 0.22, "lon": m.lon, "meters": meters]
        }
        if let c = initialCenter, c.isValidCoordinate {
            return ["v": 0, "type": "around", "lat": c.lat, "lon": c.lon, "meters": 1600]
        }
        return ["v": 0, "type": "follow", "heading": false, "pts": []]
    }

    private func handle(_ e: MapEventMessage) {
        switch e.t {
        case "region":
            guard let lat = e.lat, let lon = e.lon, let dlat = e.dlat, let dlon = e.dlon else { return }
            onRegion(MKCoordinateRegion(center: CLLocationCoordinate2D(latitude: lat, longitude: lon),
                                        span: MKCoordinateSpan(latitudeDelta: dlat, longitudeDelta: dlon)))
        case "select":
            guard let id = e.id, let sel = selection(layer: e.layer ?? "", id: id) else { return }
            onSelect(sel)
        case "pick":
            guard let lat = e.lat, let lon = e.lon else { return }
            let point = LatLon(lat: lat, lon: lon)
            let name = e.name.flatMap { $0.isEmpty ? nil : $0 } ?? L10n.t("Dropped pin")
            onPick?(.picked(name: name, at: point, street: e.street, houseNumber: e.houseNumber,
                            locality: e.locality, country: e.country, category: e.category), e.refined ?? false)
        case "empty":
            onTapEmpty?()
        default: break
        }
    }

    private func selection(layer: String, id: String) -> MapSelection? {
        switch layer {
        case "stations":
            return content.stations.first { $0.id == id }.map { .station($0) }
        case "vehicles":
            return content.vehicles.first { $0.id == id }.map { .vehicle($0) }
        case "markers":
            if let f = focus?.match, f.id == id { return .place(f) }
            return places.first { $0.id == id }.map { .place($0) }
        case "rentals":
            let r = content.rentals
            if id.hasPrefix("s|"), let s = r.stations.first(where: { "s|\($0.providerGroupId)|\($0.id)" == id }) {
                return .rentalStation(s, provider: r.groupName(s.providerGroupId))
            }
            if let v = r.vehicles.first(where: { "v|\($0.providerGroupId)|\($0.id)" == id }) {
                return .rentalVehicle(v, provider: r.groupName(v.providerGroupId))
            }
            return nil
        default:
            return nil
        }
    }

    // MARK: Payloads

    static func shapesJSON(_ shapes: [LineShape], dark: Bool) -> String {
        // All casings under all lines (Kotlin draws the casing layer first); bigger modes on top.
        let casing = dark ? "#141414E6" : "#FFFFFFF2"
        let sorted = shapes.sorted { LiveMap.rank($0.mode) > LiveMap.rank($1.mode) }
        let items: [[String: Any]] = sorted.compactMap { s in
            let pts = MapJSON.points(s.points)
            guard pts.count >= 2 else { return nil }
            return ["pts": pts, "c": "#" + LineColors.strokeHex(s.colorHex, dark: dark),
                    "w": Double(lineWidth(s.mode)), "cw": casing]
        }
        return MapJSON.encode(items)
    }

    static func lineWidth(_ mode: String) -> CGFloat {
        switch ModeGroup.of(mode) {
        case .train, .suburban, .subway: return 5
        case .tram: return 4
        default: return 3
        }
    }

    static func stationsJSON(_ stations: [MapStation]) -> String {
        MapJSON.encode(stations.filter { $0.coordinate.isValidCoordinate }.map { s -> [String: Any] in
            ["id": s.id, "lat": s.coordinate.lat, "lon": s.coordinate.lon, "label": MapStation.shortName(s.name),
             "mode": s.mainMode, "hex": ModeStyle.defaultHex(s.mainMode), "major": s.isMajor, "pole": s.isPole]
        })
    }

    static func vehiclesJSON(_ vehicles: [LiveVehicle]) -> String {
        MapJSON.encode(vehicles.filter { $0.coordinate.isValidCoordinate }.map { v -> [String: Any] in
            let colors = v.lineColors
            var o: [String: Any] = ["id": v.id, "lat": v.coordinate.lat, "lon": v.coordinate.lon, "label": v.lineName,
                                    "bg": colors.background, "fg": colors.text]
            if let h = v.heading, h.isFinite { o["heading"] = h }
            return o
        })
    }

    static func rentalsJSON(_ r: RentalsResponse) -> String {
        var items: [[String: Any]] = []
        for s in r.stations where s.coordinate.isValidCoordinate && s.isRenting != false {
            let kinds = (s.formFactors ?? []).map { RentalKind($0) }
            let kind = kinds.contains(.bicycle) ? RentalKind.bicycle : kinds.first ?? .other
            let text = s.numVehiclesAvailable.map { "\(kind.title) · \($0)" } ?? MapStation.shortName(s.name)
            items.append(["id": "s|\(s.providerGroupId)|\(s.id)", "lat": s.lat, "lon": s.lon, "kind": kind.rawValue,
                          "station": true, "text": text])
        }
        for v in r.vehicles where v.isAvailable && v.coordinate.isValidCoordinate {
            let kind = RentalKind(v.formFactor)
            let provider = r.groupName(v.providerGroupId)
            items.append(["id": "v|\(v.providerGroupId)|\(v.id)", "lat": v.lat, "lon": v.lon, "kind": kind.rawValue,
                          "station": false, "text": provider.isEmpty ? kind.title : provider])
        }
        return MapJSON.encode(items)
    }

    static func markersJSON(places: [GeocodeMatch], focus: GeocodeMatch?) -> String {
        var items: [[String: Any]] = places.enumerated().map { i, m in
            let category = PlaceCategory.of(m.category)
            return ["id": m.id, "lat": m.lat, "lon": m.lon, "title": m.name, "c": "#" + (category?.colorHex ?? "1C1C1E"),
                    "glyph": category?.symbol ?? "mappin", "rank": i + 1]
        }
        if let m = focus {
            let category = PlaceCategory.of(m.category)
            let glyph: String
            if let c = category { glyph = c.symbol } else {
                switch m.type {
                case "STOP": glyph = "tram.fill"
                case "ADDRESS": glyph = "house.fill"
                default: glyph = MapSearch.meters(m) >= 2500 ? "building.2.fill" : "mappin"
                }
            }
            items.append(["id": m.id, "lat": m.lat, "lon": m.lon, "title": m.name, "c": "#" + (category?.colorHex ?? "1C1C1E"),
                          "glyph": glyph, "rank": 0])
        }
        return MapJSON.encode(items)
    }
}
#endif
