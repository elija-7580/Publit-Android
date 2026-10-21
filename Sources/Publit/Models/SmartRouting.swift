import Foundation

/// Smart routing (Settings → Smart routing): routes ranked by how long they feel, changes rated
/// by how likely they work, a backup for each change, and the user's own walking speed.
///
/// The weights come from transport research: waiting feels about 1.7 times and walking about
/// 1.65 times as long as riding (UK meta-analysis, Abrantes & Wardman 2011), and a change on its
/// own costs about 15 minutes of riding (Garcia-Martinez et al. 2018). Everything is computed on
/// the iPhone; what you pick only tunes these weights on the device.
enum SmartRouting {
    static let enabledKey = "smartRouting"
    /// Minutes a change is worth (the "Fewer changes ↔ Faster" slider; learning moves it).
    static let transferMinutesKey = "smartTransferMinutes"
    /// Learned weight of walking.
    static let walkWeightKey = "smartWalkWeight"
    /// Minutes added to every change (luggage, children, slower walking).
    static let extraTransferKey = "smartExtraTransfer"
    static let ownSpeedKey = "smartOwnWalkingSpeed"
    /// Measured walking speed in m/s (`WalkingPace`).
    static let measuredSpeedKey = "smartMeasuredSpeed"
    static let flatWalksKey = "smartFlatWalks"
    static let learnKey = "smartLearn"

    static let defaultTransferMinutes = 15.0
    static let transferMinutesRange = 3.0...30.0
    static let defaultWalkWeight = 1.65
    static let walkWeightRange = 1.1...2.5
    static let extraTransferChoices = [0, 2, 5, 10]

    private static var defaults: UserDefaults { .standard }

    static var isEnabled: Bool { defaults.object(forKey: enabledKey) as? Bool ?? true }
    static var learns: Bool { defaults.object(forKey: learnKey) as? Bool ?? true }

    // MARK: Weights

    struct Weights: Equatable, Sendable {
        var transferMinutes = SmartRouting.defaultTransferMinutes
        var wait = 1.7
        var walk = SmartRouting.defaultWalkWeight
        /// Arriving later than the earliest route (or leaving earlier, when arriving by a time).
        var lateness = 0.5
        /// What a broken change costs on average: the wait for the next vehicle and the uncertainty.
        var missedChangeMinutes = 20.0
    }

    static var weights: Weights {
        var w = Weights()
        if let t = defaults.object(forKey: transferMinutesKey) as? Double { w.transferMinutes = t.clamped(to: transferMinutesRange) }
        if let k = defaults.object(forKey: walkWeightKey) as? Double { w.walk = k.clamped(to: walkWeightRange) }
        return w
    }

    /// How long a route feels, in minutes, without its place in the time window.
    static func feltMinutes(_ it: Itinerary, weights w: Weights = weights) -> Double {
        var ride = 0.0, walk = 0.0, other = 0.0, wait = 0.0
        for (i, leg) in it.legs.enumerated() {
            let minutes = Double(leg.duration) / 60
            if leg.isTransit { ride += minutes } else if leg.mode == "WALK" { walk += minutes } else { other += minutes }
            if i > 0 {
                let gap = leg.startTime.timeIntervalSince(it.legs[i - 1].endTime) / 60
                if gap > 0 { wait += gap }
            }
        }
        let changes = Double(transitChanges(it))
        let risk = (1 - reliability(it)) * w.missedChangeMinutes
        return ride + other + w.walk * walk + w.wait * wait + w.transferMinutes * changes + risk
    }

    /// Changes between vehicles (the planner's `transfers` can count a stay-seated through-run).
    static func transitChanges(_ it: Itinerary) -> Int { max(0, it.transitLegs.count - 1) }

    /// Cost within a list: felt time plus arriving later than the best (leaving earlier, for arrive-by).
    static func costs(_ list: [Itinerary], arriveBy: Bool, weights w: Weights = weights) -> [Double] {
        guard !list.isEmpty else { return [] }
        let earliestEnd = list.map(\.endTime).min()!
        let latestStart = list.map(\.startTime).max()!
        return list.map { it in
            let shift = arriveBy ? latestStart.timeIntervalSince(it.startTime) : it.endTime.timeIntervalSince(earliestEnd)
            return feltMinutes(it, weights: w) + w.lateness * max(0, shift) / 60 + (it.isCancelled ? 10_000 : 0)
        }
    }

    /// Best first; ties keep the planner's order.
    static func ranked(_ list: [Itinerary], arriveBy: Bool = false, weights w: Weights = weights) -> [Itinerary] {
        let c = costs(list, arriveBy: arriveBy, weights: w)
        return list.indices.sorted { c[$0] != c[$1] ? c[$0] < c[$1] : $0 < $1 }.map { list[$0] }
    }

    /// The route to suggest: lowest cost among more than one that still run.
    static func recommended(_ list: [Itinerary], arriveBy: Bool = false, weights w: Weights = weights) -> Itinerary? {
        guard list.filter({ !$0.isCancelled }).count > 1 else { return nil }
        return ranked(list, arriveBy: arriveBy, weights: w).first { !$0.isCancelled }
    }

    // MARK: Fewer, more different routes

    /// Drops routes that another one beats on everything: leaves no earlier, arrives no later,
    /// with no more changes and not more walking. Walking the whole way is never dropped.
    static func pruned(_ list: [Itinerary]) -> [Itinerary] {
        let walks = list.map { Double(RouteSort.walkSeconds($0)) }
        return list.enumerated().filter { i, a in
            guard !a.isDirect else { return true }
            return !list.enumerated().contains { j, b in
                guard j != i, !b.isDirect, !b.isCancelled else { return false }
                let noWorse = b.startTime >= a.startTime && b.endTime <= a.endTime
                    && transitChanges(b) <= transitChanges(a) && walks[j] <= walks[i] + 60
                let better = b.startTime > a.startTime || b.endTime < a.endTime || transitChanges(b) < transitChanges(a)
                return noWorse && better
            }
        }.map(\.element)
    }

    /// Labels on the routes of a list: the suggested one and the best in each respect.
    enum Badge: String, CaseIterable, Sendable {
        case recommended, fastest, fewestChanges, leastWalking

        var title: String {
            switch self {
            case .recommended: return L10n.t("Recommended")
            case .fastest: return L10n.t("Fastest")
            case .fewestChanges: return L10n.t("Fewest changes")
            case .leastWalking: return L10n.t("Least walking")
            }
        }

        var symbol: String {
            switch self {
            case .recommended: return "sparkles"
            case .fastest: return "hare"
            case .fewestChanges: return "arrow.triangle.swap"
            case .leastWalking: return "figure.walk"
            }
        }
    }

    /// Badges by `routeKey`. A badge goes to one route only, and only where the routes differ.
    static func badges(_ list: [Itinerary], arriveBy: Bool = false, weights w: Weights = weights) -> [String: [Badge]] {
        let live = list.filter { !$0.isCancelled && !$0.isDirect }
        guard live.count > 1 else { return [:] }
        var out: [String: [Badge]] = [:]
        func give(_ b: Badge, _ it: Itinerary?) { if let it { out[it.routeKey, default: []].append(b) } }
        give(.recommended, recommended(live, arriveBy: arriveBy, weights: w))
        func best(_ value: (Itinerary) -> Double, minGap: Double) -> Itinerary? {
            let values = live.map(value)
            guard let lo = values.min(), let hi = values.max(), hi - lo >= minGap,
                  values.filter({ $0 == lo }).count == 1 else { return nil }
            return live[values.firstIndex(of: lo)!]
        }
        give(.fastest, best({ Double($0.duration) }, minGap: 120))
        give(.fewestChanges, best({ Double(transitChanges($0)) }, minGap: 1))
        give(.leastWalking, best({ Double(RouteSort.walkSeconds($0)) }, minGap: 180))
        return out
    }

    /// Why the suggested route beats the fastest one, in a few words.
    static func reason(for rec: Itinerary, in list: [Itinerary]) -> String? {
        guard let fastest = list.filter({ !$0.isCancelled && !$0.isDirect }).min(by: { $0.duration < $1.duration }),
              fastest.routeKey != rec.routeKey else { return nil }
        let fewer = transitChanges(fastest) - transitChanges(rec)
        if fewer > 0 { return fewer == 1 ? L10n.t("One change fewer") : L10n.f("%lld changes fewer", fewer) }
        if reliability(rec) - reliability(fastest) >= 0.1 { return L10n.t("Safer change") }
        let walkSaved = (RouteSort.walkSeconds(fastest) - RouteSort.walkSeconds(rec)) / 60
        if walkSaved >= 3 { return L10n.f("%lld min less walking", walkSaved) }
        return nil
    }

    // MARK: Changes: chance and backup

    struct Change: Sendable {
        let stopName: String
        let incoming: Leg
        let outgoing: Leg
        /// Index of the outgoing leg in the itinerary.
        let outgoingIndex: Int
        let walkSeconds: Int
        /// Minutes left after the walk (negative: already broken).
        let spareMinutes: Double
        let chance: Double

        enum Level: Sendable { case safe, likely, risky }
        var level: Level { chance >= 0.9 ? .safe : chance >= 0.7 ? .likely : .risky }
        /// Whole percent, never 100 (nothing is certain) and at least 1.
        var percent: Int { min(99, max(1, Int((chance * 100).rounded()))) }
    }

    static func changes(_ it: Itinerary) -> [Change] {
        var out: [Change] = []
        let legs = it.legs
        var i = 0
        while i < legs.count {
            guard legs[i].isTransit else { i += 1; continue }
            var j = i + 1
            var walk = 0
            while j < legs.count, !legs[j].isTransit { walk += legs[j].duration; j += 1 }
            guard j < legs.count else { break }
            let spare = (legs[j].startTime.timeIntervalSince(legs[i].endTime) - Double(walk)) / 60
            out.append(Change(stopName: legs[i].to.name, incoming: legs[i], outgoing: legs[j], outgoingIndex: j, walkSeconds: walk,
                              spareMinutes: spare, chance: chance(spareMinutes: spare, walkSeconds: walk, incoming: legs[i])))
            i = j
        }
        return out
    }

    /// How spread out arrivals of this kind of vehicle are, in minutes.
    static func spread(_ mode: String) -> Double {
        switch mode {
        case "HIGHSPEED_RAIL", "LONG_DISTANCE", "NIGHT_RAIL", "AIRPLANE": return 4
        case "RAIL", "REGIONAL_RAIL", "REGIONAL_FAST_RAIL", "SUBURBAN", "COACH": return 2.5
        case "BUS", "FERRY", "FLEX", "ODM": return 2
        case "TRAM", "CABLE_CAR", "FUNICULAR", "AERIAL_LIFT", "AREAL_LIFT": return 1.5
        case "SUBWAY", "METRO": return 1
        default: return 2
        }
    }

    /// Chance the change works: a logistic curve over the minutes left, wider for vehicles that
    /// run late more often, narrower when the incoming one reports live times.
    static func chance(spareMinutes: Double, walkSeconds: Int, incoming: Leg) -> Double {
        var s = spread(incoming.mode)
        if incoming.realTime { s *= 0.6 }
        // Walks take a little longer than planned now and then.
        let margin = spareMinutes - Double(walkSeconds) / 60 * 0.1
        let x = (margin + 0.5 - s / 2) / (s * 0.6)
        return (1 / (1 + exp(-x))).clamped(to: 0.01...0.995)
    }

    /// Chance the whole route works as planned.
    static func reliability(_ it: Itinerary) -> Double { changes(it).reduce(1) { $0 * $1.chance } }

    /// The next vehicle that can replace a leg if its change is missed (from `numLegAlternatives`).
    struct Backup: Sendable {
        let leg: Leg
        /// Arrival at the end of the replaced leg (with its walk).
        let arrival: Date
    }

    static func backup(for leg: Leg) -> Backup? {
        let options = (leg.alternatives ?? []).compactMap { alt -> Backup? in
            guard let ride = alt.first(where: \.isTransit), ride.startTime > leg.startTime.addingTimeInterval(59),
                  ride.cancelled != true else { return nil }
            return Backup(leg: ride, arrival: alt.last?.endTime ?? ride.endTime)
        }
        return options.min { $0.leg.startTime < $1.leg.startTime }
    }

    // MARK: Live trip

    /// A change still ahead that has become unlikely (or broken): time to look for another way.
    static func changeAtRisk(_ it: Itinerary, at now: Date) -> Change? {
        changes(it).first { $0.outgoing.startTime > now && $0.incoming.endTime > now.addingTimeInterval(-120) && $0.chance < 0.5 }
    }

    // MARK: Request

    /// What smart routing adds to the search.
    struct Request: Equatable, Sendable {
        var enabled = false
        var pedestrianSpeed: Double? = nil
        var extraTransferMinutes = 0
        var flatWalks = false

        static var current: Request {
            guard SmartRouting.isEnabled else { return Request() }
            let d = UserDefaults.standard
            var r = Request(enabled: true)
            if d.bool(forKey: ownSpeedKey), let v = d.object(forKey: measuredSpeedKey) as? Double, (0.6...2.2).contains(v) {
                r.pedestrianSpeed = (v * 100).rounded() / 100
            }
            r.extraTransferMinutes = max(0, d.integer(forKey: extraTransferKey))
            r.flatWalks = d.bool(forKey: flatWalksKey)
            return r
        }

        var queryItems: [URLQueryItem] {
            guard enabled else { return [] }
            var q = [URLQueryItem(name: "numLegAlternatives", value: "2"),
                     // Direct rides that a faster route with changes overtakes; `pruned` drops the useless ones.
                     URLQueryItem(name: "slowDirect", value: "true"),
                     URLQueryItem(name: "fastestSlowDirectFactor", value: "1.5")]
            if let pedestrianSpeed { q.append(URLQueryItem(name: "pedestrianSpeed", value: String(pedestrianSpeed))) }
            if extraTransferMinutes > 0 { q.append(URLQueryItem(name: "additionalTransferTime", value: String(extraTransferMinutes))) }
            if flatWalks { q.append(URLQueryItem(name: "elevationCosts", value: "LOW")) }
            return q
        }

        var key: String { enabled ? "s\(pedestrianSpeed.map { String($0) } ?? "-")|\(extraTransferMinutes)|\(flatWalks)" : "off" }
    }

    // MARK: Learning

    /// Routes last offered in the planner, to learn from the one that is saved.
    @MainActor static var offered: [Itinerary] = []
    @MainActor static var offeredArriveBy = false

    /// Picking a route other than the suggested one moves the weights a little towards it.
    @MainActor static func learn(chosen: Itinerary) {
        guard isEnabled, learns, offered.contains(where: { $0.routeKey == chosen.routeKey }) else { return }
        let w = learned(chosen: chosen, offered: offered, arriveBy: offeredArriveBy, weights: weights)
        defaults.set(w.transferMinutes, forKey: transferMinutesKey)
        defaults.set(w.walk, forKey: walkWeightKey)
    }

    static func learned(chosen: Itinerary, offered: [Itinerary], arriveBy: Bool, weights: Weights) -> Weights {
        var w = weights
        guard let rec = recommended(offered, arriveBy: arriveBy, weights: weights), rec.routeKey != chosen.routeKey else { return w }
        let changeDiff = transitChanges(chosen) - transitChanges(rec)
        if changeDiff < 0 { w.transferMinutes += 2 } else if changeDiff > 0 { w.transferMinutes -= 2 }
        let walkDiff = Double(RouteSort.walkSeconds(chosen) - RouteSort.walkSeconds(rec)) / 60
        if walkDiff <= -2 { w.walk += 0.1 } else if walkDiff >= 2 { w.walk -= 0.1 }
        w.transferMinutes = w.transferMinutes.clamped(to: transferMinutesRange)
        w.walk = (w.walk * 100).rounded() / 100
        w.walk = w.walk.clamped(to: walkWeightRange)
        return w
    }

    static func resetLearning() {
        defaults.removeObject(forKey: transferMinutesKey)
        defaults.removeObject(forKey: walkWeightKey)
    }
}

extension Comparable {
    func clamped(to range: ClosedRange<Self>) -> Self { min(max(self, range.lowerBound), range.upperBound) }
}
