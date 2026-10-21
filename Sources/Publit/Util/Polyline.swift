import Foundation

enum Polyline {
    /// Decodes a Google encoded polyline with the given decimal precision.
    static func decode(_ encoded: String, precision: Int) -> [LatLon] {
        guard (0...7).contains(precision), encoded.utf8.count <= 1_000_000 else { return [] }
        let bytes = Array(encoded.utf8)
        let factor = pow(10.0, Double(precision))
        var coords: [LatLon] = []
        var index = 0
        var lat: Int64 = 0, lon: Int64 = 0

        func next() -> Int64? {
            var bits: UInt64 = 0
            var shift = 0
            while index < bytes.count, shift < 40 {
                let byte = bytes[index]
                index += 1
                guard byte >= 63, byte <= 126 else { return nil }
                let value = UInt64(byte - 63)
                bits |= (value & 31) << shift
                if value < 32 {
                    let magnitude = Int64(bits >> 1)
                    return bits & 1 == 1 ? -magnitude - 1 : magnitude
                }
                shift += 5
            }
            return nil
        }

        while index < bytes.count {
            guard coords.count < 50_000, let dLat = next(), let dLon = next() else { return [] }
            let (a, overLat) = lat.addingReportingOverflow(dLat)
            let (b, overLon) = lon.addingReportingOverflow(dLon)
            guard !overLat, !overLon else { return [] }
            let point = LatLon(lat: Double(a) / factor, lon: Double(b) / factor)
            guard point.isValidCoordinate else { return [] }
            lat = a; lon = b
            coords.append(point)
        }
        return coords
    }

    /// Fewer points for drawing on a map (Douglas-Peucker). A route is drawn with a few hundred
    /// points instead of thousands; nothing visible changes at street zoom.
    static func simplify(_ points: [LatLon], toleranceMeters: Double = 4) -> [LatLon] {
        guard points.count > 2 else { return points }
        let lat0 = points[0].lat * .pi / 180
        let mx = 111_320 * cos(lat0), my = 110_540.0
        let xs = points.map { $0.lon * mx }, ys = points.map { $0.lat * my }
        let tol2 = toleranceMeters * toleranceMeters
        var keep = [Bool](repeating: false, count: points.count)
        keep[0] = true; keep[points.count - 1] = true
        var stack = [(0, points.count - 1)]
        while let (a, b) = stack.popLast() {
            guard b > a + 1 else { continue }
            let dx = xs[b] - xs[a], dy = ys[b] - ys[a]
            let len2 = dx * dx + dy * dy
            var worst = -1.0, index = a
            for i in (a + 1)..<b {
                let px = xs[i] - xs[a], py = ys[i] - ys[a]
                let d2: Double
                if len2 == 0 {
                    d2 = px * px + py * py
                } else {
                    let t = max(0, min(1, (px * dx + py * dy) / len2))
                    let ex = px - t * dx, ey = py - t * dy
                    d2 = ex * ex + ey * ey
                }
                if d2 > worst { worst = d2; index = i }
            }
            if worst > tol2 {
                keep[index] = true
                stack.append((a, index)); stack.append((index, b))
            }
        }
        return points.indices.compactMap { keep[$0] ? points[$0] : nil }
    }
}

/// Fewer points for small overview maps: a 600 km train ride keeps thousands of points at 4 m,
/// and drawing them made long trips slow to open. Tolerance grows with the size of the leg.
enum OverviewPaths {
    private static var cache: [String: [LatLon]] = [:]
    private static let lock = NSLock()

    static func path(_ points: [LatLon], key: String) -> [LatLon] {
        guard points.count > 300 else { return points }
        lock.lock()
        if let hit = cache[key] { lock.unlock(); return hit }
        lock.unlock()
        var minLat = 90.0, maxLat = -90.0, minLon = 180.0, maxLon = -180.0
        for p in points {
            minLat = min(minLat, p.lat); maxLat = max(maxLat, p.lat)
            minLon = min(minLon, p.lon); maxLon = max(maxLon, p.lon)
        }
        let span = LatLon(lat: minLat, lon: minLon).distance(to: LatLon(lat: maxLat, lon: maxLon))
        let out = Polyline.simplify(points, toleranceMeters: max(4, span / 1500))
        lock.lock()
        if cache.count > 200 { cache.removeAll() }
        cache[key] = out
        lock.unlock()
        return out
    }
}
