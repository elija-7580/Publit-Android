import Foundation

/// Distances along a polyline, to place a vehicle at a share of the way between two stops.
struct PolylineRuler: Sendable {
    let points: [LatLon]
    /// Metres from the first point to each point.
    let cumulative: [Double]

    init(_ points: [LatLon]) {
        let points = points.filter(\.isValidCoordinate)
        self.points = points
        var acc = [Double](repeating: 0, count: points.count)
        if points.count > 1 {
            for i in 1..<points.count { acc[i] = acc[i - 1] + points[i - 1].distance(to: points[i]) }
        }
        cumulative = acc
    }

    var length: Double { cumulative.last ?? 0 }

    /// Point `meters` along the line, and the direction of travel there (degrees from north).
    func point(at meters: Double) -> (LatLon, Double?)? {
        guard meters.isFinite, let first = points.first else { return nil }
        guard points.count > 1 else { return (first, nil) }
        let d = min(max(meters, 0), length)
        // Binary search for the piece that contains d.
        var lo = 0, hi = cumulative.count - 1
        while hi - lo > 1 {
            let mid = (lo + hi) / 2
            if cumulative[mid] <= d { lo = mid } else { hi = mid }
        }
        let a = points[lo], b = points[hi]
        let span = cumulative[hi] - cumulative[lo]
        let f = span > 0 ? (d - cumulative[lo]) / span : 0
        let p = LatLon(lat: a.lat + (b.lat - a.lat) * f, lon: a.lon + (b.lon - a.lon) * f)
        return (p, Self.bearing(a, b))
    }

    /// Metres along the line to the vertex nearest to `p`, searching from `start` metres on
    /// (stops come in order, so each search continues where the last one ended).
    func distance(ofNearest p: LatLon, from start: Double = 0) -> Double {
        var best = Double.infinity, at = start
        for i in points.indices where cumulative[i] >= start {
            let d = points[i].distance(to: p)
            if d < best { best = d; at = cumulative[i] }
        }
        return best.isFinite ? at : start
    }

    static func bearing(_ a: LatLon, _ b: LatLon) -> Double? {
        guard a != b else { return nil }
        let p1 = a.lat * .pi / 180, p2 = b.lat * .pi / 180, dl = (b.lon - a.lon) * .pi / 180
        let y = sin(dl) * cos(p2)
        let x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
        let deg = atan2(y, x) * 180 / .pi
        return deg < 0 ? deg + 360 : deg
    }
}

/// A vehicle drawn on the map at its estimated position.
struct LiveVehicle: Identifiable, Hashable, Sendable {
    let id: String
    let name: String
    let mode: String
    let colorHex: String
    let coordinate: LatLon
    /// Direction of travel, degrees from north; nil while standing at a stop.
    let heading: Double?
    let nextStop: String
    let delayMinutes: Int
    let realTime: Bool
}

/// Part of a line's route, drawn once however many vehicles use it.
struct LineShape: Identifiable, Hashable, Sendable {
    let id: String
    let mode: String
    let colorHex: String
    let points: [LatLon]
}

/// The way one vehicle takes through the visible area, stop to stop.
struct VehicleTrack: Sendable {
    struct Piece: Sendable {
        let departure: Date
        let arrival: Date
        let ruler: PolylineRuler
        let toName: String
        let delayMinutes: Int
    }

    let id: String
    let name: String
    let mode: String
    let colorHex: String
    let realTime: Bool
    let pieces: [Piece]

    /// Where the vehicle is at `t`; nil before it comes or after it has left the area.
    func vehicle(at t: Date) -> LiveVehicle? {
        for (i, p) in pieces.enumerated() {
            if t < p.departure {
                // Waiting at the stop it arrived at, or about to leave its first stop.
                let waiting = i > 0 ? t >= pieces[i - 1].arrival : p.departure.timeIntervalSince(t) < 60
                guard waiting, let start = p.ruler.points.first else { return nil }
                return make(start, heading: nil, next: p.toName, delay: p.delayMinutes)
            }
            if t <= p.arrival {
                let span = p.arrival.timeIntervalSince(p.departure)
                let f = span > 0 ? t.timeIntervalSince(p.departure) / span : 1
                guard let (pos, heading) = p.ruler.point(at: f * p.ruler.length) else { return nil }
                return make(pos, heading: heading, next: p.toName, delay: p.delayMinutes)
            }
        }
        return nil
    }

    private func make(_ pos: LatLon, heading: Double?, next: String, delay: Int) -> LiveVehicle {
        LiveVehicle(id: id, name: name, mode: mode, colorHex: colorHex, coordinate: pos, heading: heading,
                    nextStop: next, delayMinutes: delay, realTime: realTime)
    }
}

/// Turns `/map/trips` segments into line shapes and vehicle tracks. Runs off the main thread;
/// the map then only asks the tracks for positions, which is cheap.
enum LiveMap {
    /// Flights cross the whole map; very long segments are long-distance trains passing through.
    static let excludedModes: Set<String> = ["AIRPLANE"]
    static let maxSegmentMeters = 200_000.0
    /// Upper bounds that keep MapKit smooth on older phones.
    static let maxShapes = 1500
    static let maxVehicles = 300

    struct Snapshot: Sendable {
        var shapes: [LineShape] = []
        var tracks: [VehicleTrack] = []
    }

    /// Colour used for a line: the feed's, else the mode's.
    static func colorHex(_ routeColor: String?, mode: String) -> String {
        let c = (routeColor ?? "").trimmingCharacters(in: CharacterSet(charactersIn: "# ")).uppercased()
        return c.count == 6 && c.allSatisfy(\.isHexDigit) ? c : ModeStyle.defaultHex(mode)
    }

    /// Drawing order: buses at the bottom, rail on top.
    static func rank(_ mode: String) -> Int {
        switch ModeGroup.of(mode) {
        case .bus: return 0
        case .other, .ferry: return 1
        case .tram: return 2
        case .subway: return 3
        case .suburban: return 4
        case .train: return 5
        }
    }

    /// - Parameters:
    ///   - precision: polyline precision that was requested.
    ///   - tolerance: simplification in metres (larger when zoomed out).
    static func build(_ segments: [TripSegment], filter: ModeFilter, precision: Int, tolerance: Double) -> Snapshot {
        var shapes: [String: LineShape] = [:]
        var pieces: [String: [VehicleTrack.Piece]] = [:]
        var info: [String: (name: String, mode: String, color: String, realTime: Bool)] = [:]
        for s in segments where !excludedModes.contains(s.mode) && s.distance <= maxSegmentMeters && filter.allows(s.mode) {
            guard s.distance.isFinite, s.distance >= 0, s.from.coordinate.isValidCoordinate,
                  s.to.coordinate.isValidCoordinate, s.arrival >= s.departure,
                  let trip = s.trips.first else { continue }
            let color = colorHex(s.routeColor, mode: s.mode)
            let points = Polyline.simplify(Polyline.decode(s.polyline, precision: precision), toleranceMeters: tolerance)
            guard points.count > 1 else { continue }
            let shapeKey = "\(s.from.stopId ?? s.from.name)>\(s.to.stopId ?? s.to.name)|\(color)"
            if shapes[shapeKey] == nil { shapes[shapeKey] = LineShape(id: shapeKey, mode: s.mode, colorHex: color, points: points) }
            let delayValue = (s.departure.timeIntervalSince(s.scheduledDeparture) / 60).rounded()
            guard delayValue.isFinite else { continue }
            let delay = Int(max(-1440, min(1440, delayValue)))
            pieces[trip.tripId, default: []].append(VehicleTrack.Piece(
                departure: s.departure, arrival: s.arrival, ruler: PolylineRuler(points), toName: s.to.name, delayMinutes: delay))
            if info[trip.tripId] == nil {
                let name = TransitLabels.clean(trip.routeShortName) ?? TransitLabels.clean(trip.displayName) ?? ModeStyle.label(s.mode)
                info[trip.tripId] = (name, s.mode, color, s.realTime)
            }
        }
        let tracks = pieces.compactMap { id, list -> VehicleTrack? in
            guard let i = info[id] else { return nil }
            return VehicleTrack(id: id, name: i.name, mode: i.mode, colorHex: i.color, realTime: i.realTime,
                                pieces: list.sorted { $0.departure < $1.departure })
        }
        let orderedShapes = shapes.values.sorted { rank($0.mode) != rank($1.mode) ? rank($0.mode) < rank($1.mode) : $0.id < $1.id }
        return Snapshot(shapes: Array(orderedShapes.prefix(maxShapes)), tracks: tracks)
    }

    /// Vehicles inside `min`…`max` at `t`, rail first when there are too many to draw.
    static func vehicles(_ tracks: [VehicleTrack], at t: Date, min: LatLon, max: LatLon) -> [LiveVehicle] {
        let list = tracks.compactMap { $0.vehicle(at: t) }.filter {
            $0.coordinate.lat >= min.lat && $0.coordinate.lat <= max.lat && $0.coordinate.lon >= min.lon && $0.coordinate.lon <= max.lon
        }
        guard list.count > maxVehicles else { return list }
        return Array(list.sorted { rank($0.mode) > rank($1.mode) }.prefix(maxVehicles))
    }

    /// Simplification for a map showing `spanMeters` across: about a pixel or two.
    static func tolerance(spanMeters: Double) -> Double { Swift.max(2, Swift.min(40, spanMeters / 800)) }
}
