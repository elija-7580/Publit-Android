import Foundation

/// Transport modes as people choose them: "no buses", "only trains". Each group covers the
/// MOTIS modes behind it (an ICE and a regional train are both "Train").
enum ModeGroup: String, CaseIterable, Codable, Identifiable, Hashable, Sendable {
    case train, suburban, subway, tram, bus, ferry, other

    var id: String { rawValue }

    /// MOTIS modes for `transitModes` (routes) and `mode` (departures).
    var apiModes: [String] {
        switch self {
        case .train: return ["HIGHSPEED_RAIL", "LONG_DISTANCE", "NIGHT_RAIL", "REGIONAL_FAST_RAIL", "REGIONAL_RAIL", "RAIL"]
        case .suburban: return ["SUBURBAN"]
        case .subway: return ["SUBWAY", "METRO"]
        case .tram: return ["TRAM"]
        case .bus: return ["BUS", "COACH"]
        case .ferry: return ["FERRY"]
        case .other: return ["AIRPLANE", "FUNICULAR", "AERIAL_LIFT", "CABLE_CAR", "ODM", "OTHER"]
        }
    }

    static func of(_ mode: String) -> ModeGroup {
        switch mode {
        case "SUBURBAN": return .suburban
        case "SUBWAY", "METRO": return .subway
        case "TRAM": return .tram
        case "BUS", "COACH", "FLEX": return .bus
        case "FERRY": return .ferry
        case "AIRPLANE", "FUNICULAR", "AERIAL_LIFT", "AREAL_LIFT", "CABLE_CAR", "ODM", "OTHER", "RIDE_SHARING": return .other
        default: return .train
        }
    }

    var title: String {
        switch self {
        case .train: return L10n.t("Train")
        case .suburban: return L10n.t("S-Bahn")
        case .subway: return L10n.t("Metro")
        case .tram: return L10n.t("Tram")
        case .bus: return L10n.t("Bus")
        case .ferry: return L10n.t("Ferry")
        case .other: return L10n.t("Other")
        }
    }

    var symbol: String {
        switch self {
        case .train: return "train.side.front.car"
        case .suburban: return "lightrail.fill"
        case .subway: return "tram.fill.tunnel"
        case .tram: return "tram.fill"
        case .bus: return "bus.fill"
        case .ferry: return "ferry.fill"
        case .other: return "cablecar.fill"
        }
    }

    /// Representative mode for colours.
    var mode: String { self == .train ? "RAIL" : apiModes[0] }

    /// Groups present in some departures or legs, in the fixed order of `allCases`.
    static func present(in modes: some Sequence<String>) -> [ModeGroup] {
        let found = Set(modes.filter(ModeStyle.isTransit).map(ModeGroup.of))
        return allCases.filter(found.contains)
    }
}

/// Which modes to show. Empty means all: a new mode in a feed is never hidden by accident.
struct ModeFilter: Codable, Hashable, Sendable {
    var included: Set<ModeGroup> = []

    static let all = ModeFilter()

    var isActive: Bool { !included.isEmpty && included != Set(ModeGroup.allCases) }

    func allows(_ mode: String) -> Bool {
        !isActive || !ModeStyle.isTransit(mode) || included.contains(ModeGroup.of(mode))
    }

    func allows(_ group: ModeGroup) -> Bool { !isActive || included.contains(group) }

    /// Comma list for the API, nil when nothing is filtered.
    var apiValue: String? {
        guard isActive else { return nil }
        return ModeGroup.allCases.filter(included.contains).flatMap(\.apiModes).joined(separator: ",")
    }

    /// Chip behaviour: from "all", a tap shows only that group; further taps add or remove groups;
    /// removing the last one goes back to "all".
    mutating func toggleOnly(_ group: ModeGroup) {
        if !isActive { included = [group]; return }
        if included.contains(group) { included.remove(group) } else { included.insert(group) }
        if !isActive { included = [] }
    }

    /// Switch behaviour (route options): every group starts on; at least one stays on.
    mutating func set(_ group: ModeGroup, on: Bool) {
        var set = isActive ? included : Set(ModeGroup.allCases)
        if on { set.insert(group) } else if set.count > 1 { set.remove(group) }
        included = set == Set(ModeGroup.allCases) ? [] : set
    }

    /// Short text for the active filter: "Train, S-Bahn", or "Without Bus" when most modes are on.
    var summary: String? {
        guard isActive else { return nil }
        let on = ModeGroup.allCases.filter(included.contains)
        let off = ModeGroup.allCases.filter { !included.contains($0) }
        if off.count < on.count { return L10n.f("Without %@", off.map(\.title).joined(separator: ", ")) }
        return on.map(\.title).joined(separator: ", ")
    }

    /// Stored as "train,bus" (for @AppStorage).
    var storageValue: String { ModeGroup.allCases.filter(included.contains).map(\.rawValue).joined(separator: ",") }
    init(storageValue: String) {
        included = Set(storageValue.split(separator: ",").compactMap { ModeGroup(rawValue: String($0)) })
    }
    init(included: Set<ModeGroup> = []) { self.included = included }
}

/// Route search options, kept between launches.
struct RouteOptions: Codable, Hashable, Sendable {
    var modes = ModeFilter()
    /// nil = any number of changes.
    var maxTransfers: Int? = nil
    /// Longest walk to the first and from the last stop (minutes).
    var maxWalkMinutes = RouteOptions.defaultWalkMinutes
    /// Step-free routes and transfers (MOTIS wheelchair profile).
    var wheelchair = false
    /// Also ask for walking, cycling and driving the whole way, to compare.
    var compareStreetModes = true
    /// Shared bikes and e-scooters to and from the stations.
    var sharing = false
    /// How to get to the first stop: walking, own bike (Bike & Ride) or car (Park & Ride).
    var firstMile = FirstMile.walk
    /// Order of the listed routes (sorting only, the same routes).
    var sort = RouteSort.recommended

    init() {}

    private enum CodingKeys: String, CodingKey {
        case modes, maxTransfers, maxWalkMinutes, wheelchair, compareStreetModes, sharing, firstMile
        // Stored as "order" since smart routing: the old "sort" (departure by default) gives way to "Recommended".
        case sort = "order"
    }

    /// Options saved by older versions lack the newer keys; they keep their defaults.
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        modes = try c.decodeIfPresent(ModeFilter.self, forKey: .modes) ?? ModeFilter()
        maxTransfers = try c.decodeIfPresent(Int.self, forKey: .maxTransfers)
        maxWalkMinutes = try c.decodeIfPresent(Int.self, forKey: .maxWalkMinutes) ?? Self.defaultWalkMinutes
        wheelchair = try c.decodeIfPresent(Bool.self, forKey: .wheelchair) ?? false
        compareStreetModes = try c.decodeIfPresent(Bool.self, forKey: .compareStreetModes) ?? true
        sharing = try c.decodeIfPresent(Bool.self, forKey: .sharing) ?? false
        firstMile = (try? c.decodeIfPresent(FirstMile.self, forKey: .firstMile)) ?? .walk
        sort = (try? c.decodeIfPresent(RouteSort.self, forKey: .sort)) ?? .recommended
    }

    static let defaultWalkMinutes = 30
    static let walkChoices = [5, 10, 15, 20, 30]
    static let transferChoices: [Int?] = [nil, 0, 1, 2]

    var isDefault: Bool {
        !modes.isActive && maxTransfers == nil && maxWalkMinutes == Self.defaultWalkMinutes && !wheelchair && !sharing
            && firstMile == .walk
    }

    /// How many options differ from the default (badge on the filter button).
    var activeCount: Int {
        [modes.isActive, maxTransfers != nil, maxWalkMinutes != Self.defaultWalkMinutes, wheelchair, sharing,
         firstMile != .walk].filter { $0 }.count
    }

    /// Walking the whole way comes with the routes (it joins the list when it is about as fast).
    var directModes: [String] { ["WALK"] }

    /// Cycling and driving the whole way, asked apart from the routes: a car route through a big
    /// city takes the server up to ten times as long as the transit search (Paris: 12 s instead of 1 s).
    var comparisonModes: [String] { compareStreetModes ? ["BIKE", "CAR"] : [] }

    /// Ways to the first stop. Walking always stays in, so the results mix variants
    /// (walk to the tram, or cycle to the train station) for comparison.
    var preTransitModes: [String] {
        var m = ["WALK"]
        if let extra = firstMile.apiMode { m.append(extra) }
        if sharing { m.append("RENTAL") }
        return m
    }

    /// Ways from the last stop: walking, or a shared bike or scooter.
    var postTransitModes: [String] { sharing ? ["WALK", "RENTAL"] : ["WALK"] }

    /// Park & Ride and sharing take the server much longer (up to about half a minute).
    var isSlowSearch: Bool { firstMile == .car || sharing }

    /// Part of the query key: other options mean other routes.
    var key: String {
        "\(modes.storageValue)|\(maxTransfers.map(String.init) ?? "-")|\(maxWalkMinutes)|\(wheelchair)|\(compareStreetModes)|\(sharing)|\(firstMile.rawValue)|\(SmartRouting.Request.current.key)"
    }

    static func transferTitle(_ n: Int?) -> String {
        switch n {
        case nil: return L10n.t("Any")
        case 0?: return L10n.t("Direct only")
        case let n?: return L10n.f("Up to %lld", n)
        }
    }

    /// One line for the planner, e.g. "Train, S-Bahn · Up to 1 change · Walk up to 10 min".
    var summary: String? {
        var parts: [String] = []
        if let m = modes.summary { parts.append(m) }
        if let t = maxTransfers { parts.append(t == 0 ? L10n.t("Direct only") : L10n.f("Changes: up to %lld", t)) }
        if maxWalkMinutes != Self.defaultWalkMinutes { parts.append(L10n.f("Walk up to %lld min", maxWalkMinutes)) }
        if wheelchair { parts.append(L10n.t("Step-free")) }
        if firstMile != .walk { parts.append(firstMile.summary) }
        if sharing { parts.append(L10n.t("With sharing")) }
        return parts.isEmpty ? nil : parts.joined(separator: " · ")
    }

    // MARK: Storage

    static let storageKey = "routeOptions"

    static func load(_ defaults: UserDefaults = .standard) -> RouteOptions {
        guard let data = defaults.data(forKey: storageKey),
              let value = try? JSONDecoder().decode(RouteOptions.self, from: data) else { return RouteOptions() }
        return value
    }

    func save(_ defaults: UserDefaults = .standard) {
        if let data = try? JSONEncoder().encode(self) { defaults.set(data, forKey: Self.storageKey) }
    }
}

/// Walking, cycling or driving the whole way, for comparison with public transport.
struct StreetComparison: Identifiable, Sendable {
    let mode: String
    let itinerary: Itinerary
    var id: String { mode }
    var duration: Int { itinerary.duration }
    var distance: Double? {
        let total = itinerary.legs.compactMap(\.distance).reduce(0, +)
        return total > 0 ? total : nil
    }

    static let order = ["WALK", "BIKE", "CAR"]

    /// Worth showing next to public transport: nobody walks three hours to save ten minutes.
    /// Walks up to 45 min and rides up to 90 min always; longer ones only when they are not
    /// much slower than public transport, and never beyond 2 h on foot or 4 h by bike.
    func isRealistic(bestTransit: Int?) -> Bool {
        let minutes = duration / 60
        let close = bestTransit.map { duration <= $0 * 5 / 4 } ?? true
        switch mode {
        case "WALK": return minutes <= 45 || (close && minutes <= 120)
        case "BIKE": return minutes <= 90 || (close && minutes <= 240)
        default: return true
        }
    }

    /// One per mode (the fastest), in the order walk, bike, car.
    static func from(_ direct: [Itinerary]) -> [StreetComparison] {
        var best: [String: Itinerary] = [:]
        for it in direct {
            guard let mode = streetMode(it) else { continue }
            if let b = best[mode], b.duration <= it.duration { continue }
            best[mode] = it
        }
        return order.compactMap { m in best[m].map { StreetComparison(mode: m, itinerary: $0) } }
    }

    /// The single street mode of a direct route, nil for anything mixed or transit.
    static func streetMode(_ it: Itinerary) -> String? {
        let modes = Set(it.legs.map(\.mode))
        guard modes.count == 1, let m = modes.first, order.contains(m) else { return nil }
        return m
    }
}

/// How the planner orders its routes.
enum RouteSort: String, Codable, CaseIterable, Identifiable, Sendable {
    case recommended, departure, fastest, fewestChanges, leastWalking

    var id: String { rawValue }

    /// The order in use: "Recommended" falls back to departure while smart routing is off.
    var effective: RouteSort { self == .recommended && !SmartRouting.isEnabled ? .departure : self }

    /// The orders to offer in the menu.
    static var offered: [RouteSort] { SmartRouting.isEnabled ? allCases : allCases.filter { $0 != .recommended } }

    var title: String {
        switch self {
        case .recommended: return L10n.t("Recommended")
        case .departure: return L10n.t("Departure")
        case .fastest: return L10n.t("Fastest")
        case .fewestChanges: return L10n.t("Fewest changes")
        case .leastWalking: return L10n.t("Least walking")
        }
    }

    var symbol: String {
        switch self {
        case .recommended: return "sparkles"
        case .departure: return "clock"
        case .fastest: return "hare"
        case .fewestChanges: return "arrow.triangle.swap"
        case .leastWalking: return "figure.walk"
        }
    }

    /// Seconds on foot (walks to, between and from the vehicles).
    static func walkSeconds(_ it: Itinerary) -> Int {
        it.legs.filter { $0.mode == "WALK" }.reduce(0) { $0 + $1.duration }
    }

    /// Stable: ties keep the planner's order (by departure). `.departure` leaves the list as it is.
    func apply(_ list: [Itinerary], arriveBy: Bool = false) -> [Itinerary] {
        let order = effective
        guard order != .departure else { return list }
        if order == .recommended { return SmartRouting.ranked(list, arriveBy: arriveBy) }
        func key(_ it: Itinerary) -> (Int, Int) {
            switch order {
            case .departure, .recommended: return (0, 0)
            case .fastest: return (it.duration, it.transfers)
            case .fewestChanges: return (it.transfers, it.duration)
            case .leastWalking: return (Self.walkSeconds(it), it.duration)
            }
        }
        return list.enumerated().sorted { a, b in
            let ka = key(a.element), kb = key(b.element)
            return ka != kb ? ka < kb : a.offset < b.offset
        }.map(\.element)
    }
}

/// How to get from the start to the first stop (MOTIS `preTransitModes`).
/// Bus, tram and train are always part of the route itself; this is only the first mile.
enum FirstMile: String, Codable, CaseIterable, Identifiable, Sendable {
    case walk, bike, car

    var id: String { rawValue }

    /// Added to `WALK`; nil = walking only.
    var apiMode: String? {
        switch self {
        case .walk: return nil
        case .bike: return "BIKE"
        // Drive to a car park near a station, then walk to the platform.
        case .car: return "CAR_PARKING"
        }
    }

    var title: String {
        switch self {
        case .walk: return L10n.t("Walk")
        case .bike: return L10n.t("Bike")
        case .car: return L10n.t("Car")
        }
    }

    var summary: String {
        switch self {
        case .walk: return L10n.t("Walk")
        case .bike: return L10n.t("Bike & Ride")
        case .car: return L10n.t("Park & Ride")
        }
    }

    var symbol: String {
        switch self {
        case .walk: return "figure.walk"
        case .bike: return "bicycle"
        case .car: return "car.fill"
        }
    }
}
