import Foundation

/// One line at a station with its next departures, e.g. "U3 → Moosach: 3, 13, 23 min".
struct LineAtStop: Identifiable, Sendable {
    /// Route key as used for saved lines.
    let key: String
    /// Next departures of this line, soonest first (all directions).
    let departures: [StopTime]

    var id: String { key }
    var first: StopTime { departures[0] }
    var mode: String { first.mode }
    var group: ModeGroup { ModeGroup.of(first.mode) }

    /// Directions in order of the next departure, without repeats.
    var directions: [String] {
        var seen = Set<String>()
        return departures.compactMap(\.directionName).filter { seen.insert(TransitLabels.identity($0)).inserted }
    }

    /// Next departures per direction (up to `perDirection`), directions ordered by their next departure.
    func byDirection(perDirection: Int = 3) -> [(direction: String?, departures: [StopTime])] {
        var order: [String] = []
        var buckets: [String: (String?, [StopTime])] = [:]
        for st in departures {
            let k = st.directionName.map(TransitLabels.identity) ?? ""
            if buckets[k] == nil { order.append(k); buckets[k] = (st.directionName, []) }
            if buckets[k]!.1.count < perDirection { buckets[k]!.1.append(st) }
        }
        return order.map { (buckets[$0]!.0, buckets[$0]!.1) }
    }
}

enum LineGrouping {
    /// Lines in the departures, ordered by mode (train first) and then by line name in natural order
    /// ("2" before "10"). Cancelled departures stay in the list; they are shown struck through.
    static func lines(_ stopTimes: [StopTime], filter: ModeFilter = .all) -> [LineAtStop] {
        var order: [String] = []
        var buckets: [String: [StopTime]] = [:]
        for st in stopTimes where filter.allows(st.mode) {
            let k = SavedLine.key(st)
            if buckets[k] == nil { order.append(k) }
            buckets[k, default: []].append(st)
        }
        let groups = ModeGroup.allCases
        return order.map { LineAtStop(key: $0, departures: buckets[$0]!.sorted { $0.departureTime < $1.departureTime }) }
            .sorted { a, b in
                let ga = groups.firstIndex(of: a.group)!, gb = groups.firstIndex(of: b.group)!
                if ga != gb { return ga < gb }
                let c = a.first.lineName.compare(b.first.lineName, options: [.numeric, .caseInsensitive])
                if c != .orderedSame { return c == .orderedAscending }
                return a.first.departureTime < b.first.departureTime
            }
    }
}
