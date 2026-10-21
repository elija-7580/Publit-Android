import Foundation

/// Static station data can cover many pans/zooms. Bounded by records and rectangles, not URLs.
final class MapStopCache: @unchecked Sendable {
    struct Coverage: Sendable {
        let min, max: LatLon
        let zoom: Int
        func contains(min lo: LatLon, max hi: LatLon, zoom requested: Int) -> Bool {
            zoom >= requested && lo.lat >= min.lat && lo.lon >= min.lon && hi.lat <= max.lat && hi.lon <= max.lon
        }
    }
    private struct Entry { let coverage: Coverage; let stops: [MapStop]; let date: Date }
    private let lock = NSLock()
    private var entries: [Entry] = []
    static let maxRecords = 12_000
    static let maxEntries = 4

    func covering(min: LatLon, max: LatLon, zoom: Int, now: Date = .now) -> [MapStop]? {
        lock.lock(); defer { lock.unlock() }
        guard let entry = entries.last(where: { now.timeIntervalSince($0.date) < 900 && $0.coverage.contains(min: min, max: max, zoom: zoom) }) else { return nil }
        return entry.stops.filter { $0.lat >= min.lat && $0.lat <= max.lat && $0.lon >= min.lon && $0.lon <= max.lon }
    }

    func clear() { lock.lock(); entries.removeAll(); lock.unlock() }

    func store(_ stops: [MapStop], min: LatLon, max: LatLon, zoom: Int, now: Date = .now) {
        guard stops.count <= Self.maxRecords else { return }
        lock.lock(); defer { lock.unlock() }
        entries.append(Entry(coverage: Coverage(min: min, max: max, zoom: zoom), stops: stops, date: now))
        while entries.count > Self.maxEntries || entries.reduce(0, { $0 + $1.stops.count }) > Self.maxRecords { entries.removeFirst() }
    }
}
