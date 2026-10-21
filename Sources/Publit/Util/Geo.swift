import Foundation

extension LatLon {
    /// Great-circle distance in metres (haversine). Accurate to well under 1 % at city scale.
    func distance(to other: LatLon) -> Double {
        let r = 6_371_000.0
        let p1 = lat * .pi / 180, p2 = other.lat * .pi / 180
        let dp = (other.lat - lat) * .pi / 180, dl = (other.lon - lon) * .pi / 180
        let a = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
        let bounded = max(0, min(1, a))
        return 2 * r * atan2(sqrt(bounded), sqrt(1 - bounded))
    }
}

/// Small-scale path maths for walking directions and positions along a line (metres, flat earth
/// around each point: accurate enough within a city).
enum PathGeometry {
    static func length(_ path: [LatLon]) -> Double {
        zip(path, path.dropFirst()).reduce(0) { $0 + $1.0.distance(to: $1.1) }
    }

    /// The closest point of `path` to `p`: how far along the path it is and how far `p` is from it.
    static func locate(_ p: LatLon, on path: [LatLon]) -> (along: Double, offset: Double)? {
        guard let first = path.first else { return nil }
        guard path.count > 1 else { return (0, p.distance(to: first)) }
        let kx = 111_320 * cos(p.lat * .pi / 180), ky = 110_540.0
        var best: (along: Double, offset: Double) = (0, .infinity)
        var walked = 0.0
        for (a, b) in zip(path, path.dropFirst()) {
            let ax = (a.lon - p.lon) * kx, ay = (a.lat - p.lat) * ky
            let dx = (b.lon - a.lon) * kx, dy = (b.lat - a.lat) * ky
            let seg2 = dx * dx + dy * dy
            let t = seg2 > 0 ? min(max(-(ax * dx + ay * dy) / seg2, 0), 1) : 0
            let cx = ax + t * dx, cy = ay + t * dy
            let offset = (cx * cx + cy * cy).squareRoot()
            let seg = seg2.squareRoot()
            if offset < best.offset { best = (walked + t * seg, offset) }
            walked += seg
        }
        return best
    }

    /// The point `along` metres into `path` (clamped to its ends).
    static func point(at along: Double, on path: [LatLon]) -> LatLon? {
        guard var prev = path.first else { return nil }
        guard along > 0 else { return prev }
        var walked = 0.0
        for next in path.dropFirst() {
            let seg = prev.distance(to: next)
            if seg > 0, walked + seg >= along {
                let t = (along - walked) / seg
                return LatLon(lat: prev.lat + (next.lat - prev.lat) * t, lon: prev.lon + (next.lon - prev.lon) * t)
            }
            walked += seg
            prev = next
        }
        return prev
    }

    /// Compass bearing from `a` to `b` in degrees (0 = north, 90 = east).
    static func bearing(_ a: LatLon, _ b: LatLon) -> Double {
        let p1 = a.lat * .pi / 180, p2 = b.lat * .pi / 180, dl = (b.lon - a.lon) * .pi / 180
        let y = sin(dl) * cos(p2), x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
        let deg = atan2(y, x) * 180 / .pi
        return deg < 0 ? deg + 360 : deg
    }

    /// -180…180: positive is a turn to the right.
    static func turn(from a: Double, to b: Double) -> Double {
        var d = (b - a).truncatingRemainder(dividingBy: 360)
        if d > 180 { d -= 360 }
        if d < -180 { d += 360 }
        return d
    }
}
