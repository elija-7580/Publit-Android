import Foundation

/// Departures of one station, or of one street stop (a bus or tram stop on one side of the road).
/// Platforms of the same station often come with different parent IDs (or none) in the feeds,
/// so stations are grouped by name and distance, not by ID alone.
struct StopGroup: Identifiable, Sendable {
    let id: String
    let name: String
    let coordinate: LatLon
    var stopIds: [String]
    var departures: [StopTime]
    var distance: Double?
    /// A single street stop: only its own departures, at its own position (not the station's middle).
    var isPole = false
    /// Main destinations from this street stop, to tell it from the one across the road.
    var directions: [String] = []
}

enum StopGrouping {
    /// Two stops with the same name closer than this are treated as one station.
    static let mergeRadius = 300.0
    /// Two street stops with the same name closer than this are the same stop in two feeds.
    static let duplicateRadius = 8.0

    static func normalizedName(_ name: String) -> String {
        // Turkish dotless/dotted i do not fold to "i" on their own.
        name.replacingOccurrences(of: "ı", with: "i").replacingOccurrences(of: "İ", with: "I")
            .folding(options: [.caseInsensitive, .diacriticInsensitive], locale: nil)
            .trimmingCharacters(in: .whitespacesAndNewlines)
    }

    /// Buses, coaches and trams stop at the roadside, usually one stop per direction.
    static func isStreetMode(_ mode: String) -> Bool {
        switch mode {
        case "BUS", "COACH", "TRAM", "TROLLEYBUS", "CABLE_CAR": return true
        default: return false
        }
    }

    /// - Parameter separatePoles: street stops of the same name stay apart, each with its own directions.
    static func group(_ stopTimes: [StopTime], around here: LatLon?, separatePoles: Bool = true) -> [StopGroup] {
        // A stop is a street stop when everything leaving from it is a bus or tram.
        var railStops = Set<String>()
        if separatePoles {
            for st in stopTimes where !isStreetMode(st.mode) { railStops.insert(st.place.stopId ?? st.place.stationId) }
        }
        var groups: [StopGroup] = []
        var candidates: [String: [Int]] = [:]
        var names: [String: String] = [:]
        for st in stopTimes.sorted(by: { $0.departureTime < $1.departureTime }) {
            let name = names[st.place.name] ?? normalizedName(st.place.name)
            names[st.place.name] = name
            let c = st.place.coordinate
            let sid = st.place.stopId ?? st.place.stationId
            let pole = separatePoles && !railStops.contains(sid)
            let key = pole ? "pole|" + name : name
            let radius = pole ? duplicateRadius : mergeRadius
            if let i = candidates[key]?.first(where: {
                (pole && groups[$0].stopIds.contains(sid)) || groups[$0].coordinate.distance(to: c) <= radius
            }) {
                groups[i].departures.append(st)
                if let sid = st.place.stopId, !groups[i].stopIds.contains(sid) { groups[i].stopIds.append(sid) }
            } else {
                candidates[key, default: []].append(groups.count)
                groups.append(StopGroup(id: pole ? sid : st.place.stationId, name: st.place.name, coordinate: c,
                                        stopIds: st.place.stopId.map { [$0] } ?? [],
                                        departures: [st], distance: here?.distance(to: c), isPole: pole))
            }
        }
        // IDs must be unique for the list (two stations can share a parent ID).
        var seen = Set<String>()
        for i in groups.indices {
            if groups[i].isPole { groups[i].directions = directions(groups[i].departures) }
            if !seen.insert(groups[i].id).inserted {
                let unique = "\(groups[i].id)#\(i)"
                groups[i] = StopGroup(id: unique, name: groups[i].name, coordinate: groups[i].coordinate,
                                      stopIds: groups[i].stopIds, departures: groups[i].departures,
                                      distance: groups[i].distance, isPole: groups[i].isPole, directions: groups[i].directions)
                seen.insert(unique)
            }
        }
        return groups.sorted { ($0.distance ?? .infinity) < ($1.distance ?? .infinity) }
    }

    /// The most frequent destinations, at most `limit`.
    static func directions(_ departures: [StopTime], limit: Int = 2) -> [String] {
        var counts: [String: Int] = [:]
        var first: [String: Int] = [:]
        for (i, st) in departures.enumerated() {
            let d = st.directionName ?? st.headsign
            guard !d.isEmpty else { continue }
            counts[d, default: 0] += 1
            if first[d] == nil { first[d] = i }
        }
        return counts.keys.sorted { counts[$0]! != counts[$1]! ? counts[$0]! > counts[$1]! : first[$0]! < first[$1]! }
            .prefix(limit).map { $0 }
    }
}
