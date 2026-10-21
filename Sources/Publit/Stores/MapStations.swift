import Foundation

/// A pin on the station map: the platforms of one station together, or one street stop
/// (when zoomed in, bus and tram stops on either side of the road are separate pins).
struct MapStation: Identifiable, Hashable {
    let id: String
    let name: String
    let coordinate: LatLon
    let modes: [String]
    let importance: Double
    /// One street stop at its own position; its departures are only those leaving from there.
    var isPole = false

    var mainMode: String {
        let order = ["HIGHSPEED_RAIL", "LONG_DISTANCE", "NIGHT_RAIL", "REGIONAL_FAST_RAIL", "REGIONAL_RAIL", "RAIL", "SUBURBAN",
                     "SUBWAY", "METRO", "TRAM", "FERRY", "AIRPLANE", "FUNICULAR", "AERIAL_LIFT", "COACH", "BUS"]
        return order.first(where: modes.contains) ?? modes.first ?? "BUS"
    }
    var isMajor: Bool { modes.contains { $0 != "BUS" && $0 != "COACH" } && !isPole }

    /// Map label without a leading "City, " (the city is obvious on the map).
    static func shortName(_ name: String) -> String {
        guard let r = name.range(of: ", ") else { return name }
        let city = name[..<r.lowerBound], rest = name[r.upperBound...]
        guard city.count <= 24, rest.count >= 3 else { return name }
        return String(rest)
    }

    /// At most this many pins; MapKit annotations get slow beyond a few hundred.
    static let maxStations = 250
    /// Street stops get their own pins below this map span (degrees latitude, about 1.5 km).
    static let poleSpan = 0.014

    /// A stop where only buses or trams call.
    static func isPole(_ s: MapStop) -> Bool {
        guard let modes = s.modes, !modes.isEmpty else { return false }
        return modes.allSatisfy(StopGrouping.isStreetMode)
    }

    /// - Parameter separatePoles: street stops stay single pins at their own position.
    ///
    /// The result does not depend on the order of `stops`, and a pin sits on its most important
    /// platform (not on the average of what happens to be loaded), so pins do not jump while
    /// zooming or panning loads more or fewer platforms.
    static func group(_ stops: [MapStop], separatePoles: Bool = false) -> [MapStation] {
        // Name folding is the expensive part: do it once per distinct name.
        var keys: [String: String] = [:]
        for s in stops where keys[s.name] == nil { keys[s.name] = StopGrouping.normalizedName(s.name) }
        func key(_ name: String) -> String { keys[name] ?? StopGrouping.normalizedName(name) }
        func better(_ a: MapStop, _ b: MapStop) -> Bool {
            let ia = a.importance ?? 0, ib = b.importance ?? 0
            if ia != ib { return ia > ib }
            let pa = isPole(a), pb = isPole(b)
            if pa != pb { return !pa }
            return a.stopId < b.stopId
        }
        // Strongest platforms first: they anchor their station.
        let sorted = stops.sorted(by: better)
        var out: [MapStation] = []
        var anchors: [String: [(index: Int, at: LatLon, pole: Bool)]] = [:]
        var members: [[MapStop]] = []
        var usedIds = Set<String>()
        for s in sorted {
            let pole = separatePoles && isPole(s)
            let k = key(s.name)
            let radius = pole ? StopGrouping.duplicateRadius : StopGrouping.mergeRadius
            if let a = anchors[k]?.first(where: { $0.pole == pole && $0.at.distance(to: s.coordinate) < radius }) {
                members[a.index].append(s)
                continue
            }
            let base = pole ? s.stopId : s.stationId
            let id = usedIds.contains(base) ? s.stopId : base
            usedIds.insert(id)
            anchors[k, default: []].append((out.count, s.coordinate, pole))
            members.append([s])
            out.append(MapStation(id: id, name: s.name, coordinate: s.coordinate, modes: [], importance: s.importance ?? 0, isPole: pole))
        }
        for i in out.indices {
            let o = out[i]
            out[i] = MapStation(id: o.id, name: o.name, coordinate: o.coordinate,
                                modes: Array(Set(members[i].flatMap { $0.modes ?? [] })).sorted(),
                                importance: o.importance, isPole: o.isPole)
        }
        return Array(out.prefix(maxStations))
    }
}
