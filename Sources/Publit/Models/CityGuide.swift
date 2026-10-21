import Foundation

// Helpers for finding the way in a city during a started trip: platform changes, compass
// directions, station exits, changes between vehicles, what to announce, and a new connection.

/// A live platform that differs from the timetable.
struct PlatformChange: Equatable, Sendable {
    let scheduled: String
    let now: String
}

extension Place {
    /// The live platform when it is not the planned one ("5" instead of "3").
    var platformChange: PlatformChange? {
        guard let now = TransitLabels.clean(track), let planned = TransitLabels.clean(scheduledTrack),
              TransitLabels.identity(now) != TransitLabels.identity(planned) else { return nil }
        return PlatformChange(scheduled: planned, now: now)
    }
}

/// Eight compass points for "Head north-east" and "Exit on the west side".
enum Compass {
    enum Point: Int, CaseIterable, Sendable { case north, northEast, east, southEast, south, southWest, west, northWest }

    static func normalized(_ degrees: Double) -> Double {
        let d = degrees.truncatingRemainder(dividingBy: 360)
        return d < 0 ? d + 360 : d
    }

    static func point(_ bearing: Double) -> Point {
        Point(rawValue: Int((normalized(bearing) + 22.5) / 45) % 8) ?? .north
    }

    /// Where a bearing lies from where the phone points: −180…180, negative to the left.
    static func relative(_ bearing: Double, heading: Double) -> Double {
        let d = normalized(bearing - heading)
        return d > 180 ? d - 360 : d
    }

    static func head(_ p: Point) -> String {
        switch p {
        case .north: return L10n.t("Head north")
        case .northEast: return L10n.t("Head north-east")
        case .east: return L10n.t("Head east")
        case .southEast: return L10n.t("Head south-east")
        case .south: return L10n.t("Head south")
        case .southWest: return L10n.t("Head south-west")
        case .west: return L10n.t("Head west")
        case .northWest: return L10n.t("Head north-west")
        }
    }

    static func exit(_ p: Point) -> String {
        switch p {
        case .north: return L10n.t("Exit on the north side")
        case .northEast: return L10n.t("Exit on the north-east side")
        case .east: return L10n.t("Exit on the east side")
        case .southEast: return L10n.t("Exit on the south-east side")
        case .south: return L10n.t("Exit on the south side")
        case .southWest: return L10n.t("Exit on the south-west side")
        case .west: return L10n.t("Exit on the west side")
        case .northWest: return L10n.t("Exit on the north-west side")
        }
    }
}

extension WalkRoute {
    /// The way to go at a point of the walk: along the path for the next 20 m.
    func bearing(at along: Double) -> Double? {
        let a = max(0, min(along, length))
        guard let p = PathGeometry.point(at: a, on: path),
              let q = PathGeometry.point(at: min(a + 20, length), on: path), p != q else { return nil }
        return PathGeometry.bearing(p, q)
    }

    /// The instruction that brings you up to the street, when the walk starts underground.
    var exitIndex: Int? { maneuvers.firstIndex { $0.exitTowards != nil || $0.exitSide != nil } }
}

/// The change from one vehicle to the next: platforms, levels, walk and time to spare.
struct TransferInfo: Equatable, Sendable {
    let fromLine: String
    let fromTrack: String?
    let toTrack: String?
    let fromLevel: Double?
    let toLevel: Double?
    let walkSeconds: Int
    let spareSeconds: Int

    /// The change before the transit leg at `legIndex`; nil when no vehicle comes before it.
    init?(before legIndex: Int, in it: Itinerary) {
        guard it.legs.indices.contains(legIndex), it.legs[legIndex].isTransit,
              let p = it.legs[..<legIndex].lastIndex(where: \.isTransit) else { return nil }
        let arriving = it.legs[p], departing = it.legs[legIndex]
        let walks = it.legs[(p + 1)..<legIndex].filter { !$0.isTransit }
        fromLine = arriving.lineName
        fromTrack = TransitLabels.clean(arriving.to.track)
        toTrack = TransitLabels.clean(departing.from.track)
        fromLevel = walks.first?.steps?.first?.fromLevel
        toLevel = walks.last?.steps?.last?.toLevel
        walkSeconds = walks.reduce(0) { $0 + $1.duration }
        spareSeconds = Int(departing.startTime.timeIntervalSince(arriving.endTime)) - walkSeconds
    }

    /// "From platform 1 to platform 7"; nil when neither is known.
    var platforms: String? {
        switch (fromTrack, toTrack) {
        case let (a?, b?) where TransitLabels.identity(a) == TransitLabels.identity(b): return L10n.f("Same platform %@", b)
        case let (a?, b?): return L10n.f("From platform %@ to platform %@", a, b)
        case let (nil, b?): return L10n.f("To platform %@", b)
        default: return nil
        }
    }

    /// "level −2 → level −1" when the change goes up or down.
    var levels: String? {
        guard let a = fromLevel, let b = toLevel, a != b else { return nil }
        return "\(WalkManeuver.levelName(a)) → \(WalkManeuver.levelName(b))"
    }

    var isTight: Bool { spareSeconds < 120 }
}

/// Something worth saying (and a short vibration) during a started trip. The key makes sure
/// each one comes once.
struct GuideCue: Equatable, Sendable {
    enum Kind: Sendable { case stage, turn, getOff, platform }
    let kind: Kind
    let key: String
    let text: String
}

enum GuideCues {
    /// A turn is announced this far ahead.
    static let turnAhead = 30.0

    /// The transit leg whose platform matters now: the one waited for, or the next one.
    static func platformLeg(_ step: GuideStep, _ it: Itinerary) -> Int? {
        if step.kind == .wait { return step.legIndex }
        guard step.kind != .arrive, step.legIndex + 1 < it.legs.count else { return nil }
        return it.legs[(step.legIndex + 1)...].firstIndex(where: \.isTransit)
    }

    /// The most important thing to say now. A platform change goes first, then the next turn
    /// or "get off at the next stop", then the stage itself.
    static func current(_ step: GuideStep, _ it: Itinerary, at now: Date,
                        walk: WalkRoute?, position: WalkRoute.Position?, progress: TripProgress?) -> GuideCue? {
        guard it.legs.indices.contains(step.legIndex) else { return nil }
        let leg = it.legs[step.legIndex]
        if let i = platformLeg(step, it), it.legs[i].startTime > now, let change = it.legs[i].from.platformChange {
            return GuideCue(kind: .platform, key: "pl|\(i)|\(change.now)",
                            text: L10n.f("Platform change: %@ now leaves from platform %@.", it.legs[i].lineName, change.now))
        }
        switch step.kind {
        case .walk:
            if let walk, let position, let toNext = position.toNext, toNext <= turnAhead,
               let next = walk.maneuvers[safe: position.index + 1], next.kind != .arrive {
                let target = it.legs[(step.legIndex + 1)...].first(where: \.isTransit)?.from.name ?? leg.to.name
                return GuideCue(kind: .turn, key: "turn|\(step.legIndex)|\(position.index + 1)",
                                text: [next.instruction(destination: target), next.exitNote].compactMap { $0 }.joined(separator: ". "))
            }
            let target = it.legs[(step.legIndex + 1)...].first(where: \.isTransit)?.from.name ?? leg.to.name
            return GuideCue(kind: .stage, key: "stage|\(step.id)", text: L10n.f("Walk to %@", target))
        case .wait:
            let line = leg.lineName
            let text: String
            switch (leg.shownDirection, TransitLabels.clean(leg.from.track)) {
            case let (d?, t?): text = L10n.f("Take %@ towards %@ from platform %@", line, d, t)
            case let (d?, nil): text = L10n.f("Take %@ towards %@", line, d)
            default: text = L10n.f("Take %@", line)
            }
            return GuideCue(kind: .stage, key: "stage|\(step.id)", text: text)
        case .ride:
            if let progress, TripGuide.stopsLeft(progress, count: leg.allStops.count) == 1 {
                return GuideCue(kind: .getOff, key: "getoff|\(step.legIndex)", text: L10n.f("Next stop: %@. Get off there.", leg.to.name))
            }
            return GuideCue(kind: .stage, key: "stage|\(step.id)", text: L10n.f("Get off at %@", leg.to.name))
        case .arrive:
            return now >= it.endTime ? GuideCue(kind: .stage, key: "stage|\(step.id)", text: L10n.t("You have arrived")) : nil
        }
    }
}

/// What changed in a live refresh that is worth a notification during a trip.
enum TripChanges {
    enum Change: Equatable, Sendable {
        case platform(legIndex: Int, line: String, change: PlatformChange)
        case cancelled(legIndex: Int, line: String, stop: String)
    }

    static func between(_ old: Itinerary, _ new: Itinerary, now: Date = .now) -> [Change] {
        guard old.legs.count == new.legs.count else { return [] }
        var out: [Change] = []
        for (i, (a, b)) in zip(old.legs, new.legs).enumerated() where b.isTransit && b.startTime > now {
            if b.cancelled == true, a.cancelled != true {
                out.append(.cancelled(legIndex: i, line: b.lineName, stop: b.from.name))
            } else if let change = b.from.platformChange,
                      TransitLabels.clean(a.from.track).map(TransitLabels.identity) != TransitLabels.identity(change.now) {
                out.append(.platform(legIndex: i, line: b.lineName, change: change))
            }
        }
        return out
    }
}

/// A new connection from where you are when the trip went wrong.
enum TripAlternative {
    /// Offer one: off the path, missed the vehicle, or a ride ahead does not run.
    static func needed(_ check: TripGuide.Check, _ it: Itinerary, at now: Date) -> Bool {
        check != .fine || it.legs.contains { $0.isTransit && $0.cancelled == true && $0.startTime > now.addingTimeInterval(-60) }
    }

    /// The one that arrives first among those you can still catch.
    static func pick(_ options: [Itinerary], at now: Date) -> Itinerary? {
        choices(options, at: now).first
    }

    /// Those you can still catch, the earliest arrival first, each route once.
    static func choices(_ options: [Itinerary], at now: Date, limit: Int = 5) -> [Itinerary] {
        Array(options.uniqueRoutes
            .filter { !$0.isCancelled && ($0.firstTransitLeg?.startTime ?? $0.startTime) > now.addingTimeInterval(30) }
            .sorted { $0.endTime < $1.endTime }
            .prefix(limit))
    }

    /// Minutes a new route arrives after (+) or before (−) the trip as it stands.
    static func minutesLater(_ option: Itinerary, than trip: Itinerary) -> Int {
        Int((option.endTime.timeIntervalSince(trip.endTime) / 60).rounded())
    }

    /// "Arrives 8 min later than planned", for comparing a new route with the old one.
    static func comparison(_ option: Itinerary, than trip: Itinerary) -> String {
        let d = minutesLater(option, than: trip)
        if d > 0 { return L10n.f("%lld min later than your trip", d) }
        if d < 0 { return L10n.f("%lld min earlier than your trip", -d) }
        return L10n.t("Arrives as your trip would")
    }
}

extension Array {
    subscript(safe i: Int) -> Element? { indices.contains(i) ? self[i] : nil }
}
