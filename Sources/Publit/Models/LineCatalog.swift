import Foundation

/// A line seen around a place, with a stop where it can be looked up.
struct LineRef: Codable, Hashable, Identifiable, Sendable, RouteStyled {
    var id: String { key }
    let key: String
    let mode: String
    let routeShortName: String?
    let displayName: String?
    let routeColor: String?
    let routeTextColor: String?
    let agencyName: String?
    /// Most common destinations first.
    let headsigns: [String]
    let stopId: String
    let stopName: String
    let lat: Double
    let lon: Double

    var coordinate: LatLon { LatLon(lat: lat, lon: lon) }
    var group: ModeGroup { ModeGroup.of(mode) }

    init(key: String, mode: String, routeShortName: String?, displayName: String?, routeColor: String?,
         routeTextColor: String?, agencyName: String?, headsigns: [String], stopId: String, stopName: String,
         lat: Double, lon: Double) {
        self.key = key; self.mode = mode; self.routeShortName = routeShortName; self.displayName = displayName
        self.routeColor = routeColor; self.routeTextColor = routeTextColor; self.agencyName = agencyName
        self.headsigns = headsigns; self.stopId = stopId; self.stopName = stopName; self.lat = lat; self.lon = lon
    }

    init(saved: SavedLine) {
        self.init(key: saved.routeKey, mode: saved.mode, routeShortName: saved.routeShortName, displayName: saved.displayName,
                  routeColor: saved.routeColor, routeTextColor: saved.routeTextColor, agencyName: nil, headsigns: [],
                  stopId: saved.stopId, stopName: saved.stopName, lat: saved.lat, lon: saved.lon)
    }

    func matches(_ st: StopTime) -> Bool { SavedLine.key(st) == key }
}

enum LineCatalog {
    /// Lines in the departures around `center`, each with the nearest stop it serves.
    static func build(_ stopTimes: [StopTime], around center: LatLon) -> [LineRef] {
        var buckets: [String: [StopTime]] = [:]
        for st in stopTimes { buckets[SavedLine.key(st), default: []].append(st) }
        let lines = buckets.map { key, list -> LineRef in
            let first = list[0]
            var counts: [String: Int] = [:]
            for st in list { if let h = st.directionName { counts[h, default: 0] += 1 } }
            let headsigns = counts.sorted { $0.value != $1.value ? $0.value > $1.value : $0.key < $1.key }.prefix(3).map(\.key)
            let nearest = list.min { $0.place.coordinate.distance(to: center) < $1.place.coordinate.distance(to: center) }!.place
            return LineRef(key: key, mode: first.mode, routeShortName: first.routeShortName, displayName: first.displayName,
                           routeColor: first.routeColor, routeTextColor: first.routeTextColor, agencyName: first.agencyName,
                           headsigns: headsigns, stopId: nearest.stopId ?? nearest.stationId, stopName: nearest.name,
                           lat: nearest.lat, lon: nearest.lon)
        }
        return sorted(lines)
    }

    /// By mode (train first), then line name in natural order ("2" before "10").
    static func sorted(_ lines: [LineRef]) -> [LineRef] {
        let groups = ModeGroup.allCases
        return lines.sorted { a, b in
            let ga = groups.firstIndex(of: a.group)!, gb = groups.firstIndex(of: b.group)!
            if ga != gb { return ga < gb }
            let c = a.lineName.compare(b.lineName, options: [.numeric, .caseInsensitive])
            return c != .orderedSame ? c == .orderedAscending : a.key < b.key
        }
    }

    /// Lowercased, without accents and spaces: "U 6" and "u6" are the same.
    static func fold(_ s: String) -> String {
        s.folding(options: [.caseInsensitive, .diacriticInsensitive], locale: nil)
            .lowercased().filter { !$0.isWhitespace && $0 != "-" }
    }

    private static let modeWords: Set<String> = ["bus", "tram", "line", "linie", "ligne", "linea", "línea", "lijn", "linia",
                                                 "metro", "ubahn", "sbahn", "train", "zug"]

    /// Lines matching a search: exact line name, then names starting with it, then destinations.
    static func search(_ query: String, in lines: [LineRef]) -> [LineRef] {
        let words = query.split(whereSeparator: \.isWhitespace).map(String.init)
        let meaningful = words.filter { !modeWords.contains(fold($0)) }
        let q = fold((meaningful.isEmpty ? words : meaningful).joined())
        guard !q.isEmpty else { return lines }
        var scored: [(Int, Int, LineRef)] = []
        for (i, line) in lines.enumerated() {
            let name = fold(line.lineName)
            let score: Int?
            if name == q { score = 0 }
            else if name.hasPrefix(q) { score = 1 }
            else if line.headsigns.contains(where: { fold($0).contains(q) }) { score = 2 }
            else if fold(line.stopName).contains(q) || fold(line.agencyName ?? "").contains(q) { score = 3 }
            else { score = nil }
            if let score { scored.append((score, i, line)) }
        }
        return scored.sorted { $0.0 != $1.0 ? $0.0 < $1.0 : $0.1 < $1.1 }.map(\.2)
    }
}

/// One direction of a line at a stop: where it goes and a trip that shows its whole route.
struct LineDirection: Identifiable, Hashable, Sendable {
    let id: String
    let headsign: String
    let sampleTripId: String
    let count: Int

    /// Feed direction when given, else the destination.
    static func groupId(_ st: StopTime) -> String { st.directionId ?? st.directionName ?? st.headsign }

    /// Directions of `line` in the departures, busiest first. The sample is a trip to the most
    /// common destination (short workings show only part of the route).
    static func from(_ stopTimes: [StopTime], matching key: String) -> [LineDirection] {
        var groups: [String: [StopTime]] = [:]
        var order: [String] = []
        for st in stopTimes where SavedLine.key(st) == key {
            let g = groupId(st)
            if groups[g] == nil { order.append(g) }
            groups[g, default: []].append(st)
        }
        let list = order.compactMap { g -> LineDirection? in
            guard let sts = groups[g] else { return nil }
            var counts: [String: Int] = [:]
            for st in sts { counts[st.directionName ?? st.headsign, default: 0] += 1 }
            guard let head = counts.max(by: { $0.value != $1.value ? $0.value < $1.value : $0.key > $1.key })?.key,
                  let sample = sts.first(where: { ($0.directionName ?? $0.headsign) == head && !$0.isCancelled })
                    ?? sts.first(where: { ($0.directionName ?? $0.headsign) == head }) else { return nil }
            return LineDirection(id: g, headsign: head, sampleTripId: sample.tripId, count: sts.count)
        }
        // Two directions at most in the picker: the busiest, then the one going the other way.
        return list.sorted { $0.count != $1.count ? $0.count > $1.count : $0.id < $1.id }
    }
}

/// A day's departures of one line at one stop, by hour (scheduled times, in the stop's time zone).
struct LineTimetable: Equatable, Sendable {
    struct Row: Equatable, Sendable, Identifiable {
        let hour: Int
        let minutes: [Int]
        var id: Int { hour }
    }

    let rows: [Row]
    var isEmpty: Bool { rows.isEmpty }
    var count: Int { rows.reduce(0) { $0 + $1.minutes.count } }

    /// - Parameters:
    ///   - direction: only this direction (`LineDirection.id`; nil: every direction). Short workings
    ///     of the same direction are included.
    ///   - start, end: the day shown.
    static func build(_ stopTimes: [StopTime], key: String, direction: String?, from start: Date, to end: Date,
                      timeZone: TimeZone) -> LineTimetable {
        var cal = Calendar(identifier: .gregorian)
        cal.timeZone = timeZone
        var byHour: [Int: Set<Int>] = [:]
        for st in stopTimes where SavedLine.key(st) == key {
            if let direction, LineDirection.groupId(st) != direction { continue }
            let t = st.scheduledDepartureTime ?? st.departureTime
            guard t >= start, t < end else { continue }
            let c = cal.dateComponents([.hour, .minute], from: t)
            guard let h = c.hour, let m = c.minute else { continue }
            byHour[h, default: []].insert(m)
        }
        // A service day can run past midnight: keep the hours in the order they come in the day.
        let firstHour = cal.component(.hour, from: start)
        let rows = byHour.keys.sorted { ($0 - firstHour + 24) % 24 < ($1 - firstHour + 24) % 24 }
            .map { Row(hour: $0, minutes: byHour[$0]!.sorted()) }
        return LineTimetable(rows: rows)
    }
}

/// Vehicles of a line placed on its route, from the departures at one stop of the route.
/// A trip that left the stop 4 minutes ago is where the sample trip was 4 minutes after that stop.
enum LineVehicles {
    struct Pattern: Sendable {
        let stops: [Place]
        let ruler: PolylineRuler
        /// Metres along the route of each stop.
        let stopMeters: [Double]
        /// Seconds after the first departure at each stop (arrival, departure).
        let offsets: [(Double, Double)]

        init?(stops: [Place], shape: [LatLon]) {
            guard stops.count > 1, let t0 = stops[0].departure ?? stops[0].arrival else { return nil }
            self.stops = stops
            ruler = PolylineRuler(shape.count > 1 ? shape : stops.map(\.coordinate))
            var meters: [Double] = []
            var at = 0.0
            for s in stops { at = ruler.distance(ofNearest: s.coordinate, from: at); meters.append(at) }
            stopMeters = meters
            offsets = stops.map { s in
                let a = (s.arrival ?? s.departure).map { $0.timeIntervalSince(t0) } ?? 0
                let d = (s.departure ?? s.arrival).map { $0.timeIntervalSince(t0) } ?? a
                return (a, d)
            }
        }

        var duration: Double { offsets.last?.0 ?? 0 }

        /// Index of the stop nearest to `stopId`/`coordinate` on the pattern.
        func index(of stopId: String?, near coordinate: LatLon) -> Int {
            if let stopId, let i = stops.firstIndex(where: { $0.stopId == stopId || $0.parentId == stopId }) { return i }
            return stops.indices.min { stops[$0].coordinate.distance(to: coordinate) < stops[$1].coordinate.distance(to: coordinate) } ?? 0
        }

        /// Position `seconds` after leaving the first stop; nil outside the trip.
        func position(after seconds: Double) -> (LatLon, Int)? {
            guard seconds >= -30, seconds <= duration + 30 else { return nil }
            for i in offsets.indices {
                let (a, d) = offsets[i]
                if seconds <= d {
                    if seconds >= a || i == 0 { return ruler.point(at: stopMeters[i]).map { ($0.0, i) } }
                    let (_, prevDep) = offsets[i - 1]
                    let span = a - prevDep
                    let f = span > 0 ? (seconds - prevDep) / span : 1
                    let m = stopMeters[i - 1] + (stopMeters[i] - stopMeters[i - 1]) * f
                    return ruler.point(at: m).map { ($0.0, i) }
                }
            }
            return ruler.point(at: stopMeters.last ?? 0).map { ($0.0, stops.count - 1) }
        }
    }

    struct Vehicle: Identifiable, Hashable, Sendable {
        let id: String
        let coordinate: LatLon
        let nextStop: String
        let delayMinutes: Int
        let realTime: Bool
    }

    /// - Parameters:
    ///   - departures: this line's departures in one direction at stop `index` of the pattern.
    static func estimate(_ pattern: Pattern, at index: Int, departures: [StopTime], now: Date) -> [Vehicle] {
        guard pattern.offsets.indices.contains(index) else { return [] }
        let atStop = pattern.offsets[index].1
        var seen = Set<String>()
        return departures.compactMap { st in
            guard !st.isCancelled, seen.insert(st.tripId).inserted else { return nil }
            let sinceStop = now.timeIntervalSince(st.departureTime)
            guard let (pos, next) = pattern.position(after: atStop + sinceStop) else { return nil }
            return Vehicle(id: st.tripId, coordinate: pos, nextStop: pattern.stops[next].name,
                           delayMinutes: st.delayMinutes, realTime: st.realTime)
        }
    }

    /// The time span of departures at stop `index` whose vehicles are on the route now.
    static func window(_ pattern: Pattern, at index: Int, now: Date) -> (start: Date, seconds: Int) {
        let before = pattern.offsets[index].1, after = pattern.duration - before
        return (now.addingTimeInterval(-after - 60), Int(before + after + 120))
    }
}
