import Foundation

/// One instruction of a walk: "Turn left onto Ludwigstraße", "Take the stairs down (level −1)".
struct WalkManeuver: Equatable, Sendable {
    enum Kind: String, Sendable {
        case start, straight, slightLeft, left, sharpLeft, slightRight, right, sharpRight, uTurn
        case stairsUp, stairsDown, stairs, elevator, levelUp, levelDown, arrive
    }

    var kind: Kind
    /// Metres from the start of the walk.
    var along: Double
    /// Metres until the next instruction.
    var distance: Double = 0
    var street: String?
    /// Level after this instruction (0 = street level), when the router knows it.
    var level: Double?
    var coordinate: LatLon
    /// Coming up from underground: the first street after the exit, or the side of the station.
    var exitTowards: String? = nil
    var exitSide: Compass.Point? = nil

    /// "Exit towards Ludwigstraße" / "Exit on the north-east side".
    var exitNote: String? {
        if let exitTowards { return L10n.f("Exit towards %@", exitTowards) }
        return exitSide.map(Compass.exit)
    }

    var symbol: String {
        switch kind {
        case .start: return "figure.walk"
        case .straight: return "arrow.up"
        case .slightLeft: return "arrow.up.left"
        case .left, .sharpLeft: return "arrow.turn.up.left"
        case .slightRight: return "arrow.up.right"
        case .right, .sharpRight: return "arrow.turn.up.right"
        case .uTurn: return "arrow.uturn.down"
        case .stairsUp, .stairsDown, .stairs: return "figure.stairs"
        case .elevator: return "arrow.up.arrow.down"
        case .levelUp: return "arrow.up.forward"
        case .levelDown: return "arrow.down.forward"
        case .arrive: return "mappin.and.ellipse"
        }
    }

    static func levelName(_ level: Double) -> String {
        if level == 0 { return L10n.t("street level") }
        let n = level == level.rounded() ? String(Int(level)) : String(level)
        return L10n.f("level %@", n.replacingOccurrences(of: "-", with: "−"))
    }

    /// The instruction; `destination` names the end of the walk.
    func instruction(destination: String) -> String {
        let s = street
        let lvl = level.map(Self.levelName)
        switch kind {
        case .start: return s.map { L10n.f("Head along %@", $0) } ?? L10n.t("Start walking")
        case .straight: return s.map { L10n.f("Continue on %@", $0) } ?? L10n.t("Continue straight on")
        case .slightLeft: return s.map { L10n.f("Bear left onto %@", $0) } ?? L10n.t("Bear left")
        case .left: return s.map { L10n.f("Turn left onto %@", $0) } ?? L10n.t("Turn left")
        case .sharpLeft: return s.map { L10n.f("Turn sharp left onto %@", $0) } ?? L10n.t("Turn sharp left")
        case .slightRight: return s.map { L10n.f("Bear right onto %@", $0) } ?? L10n.t("Bear right")
        case .right: return s.map { L10n.f("Turn right onto %@", $0) } ?? L10n.t("Turn right")
        case .sharpRight: return s.map { L10n.f("Turn sharp right onto %@", $0) } ?? L10n.t("Turn sharp right")
        case .uTurn: return L10n.t("Turn around")
        case .stairsUp: return lvl.map { L10n.f("Take the stairs up (%@)", $0) } ?? L10n.t("Take the stairs up")
        case .stairsDown: return lvl.map { L10n.f("Take the stairs down (%@)", $0) } ?? L10n.t("Take the stairs down")
        case .stairs: return L10n.t("Take the stairs")
        case .elevator: return lvl.map { L10n.f("Take the lift (%@)", $0) } ?? L10n.t("Take the lift")
        case .levelUp: return lvl.map { L10n.f("Go up (%@)", $0) } ?? L10n.t("Go up")
        case .levelDown: return lvl.map { L10n.f("Go down (%@)", $0) } ?? L10n.t("Go down")
        case .arrive: return L10n.f("Arrive at %@", destination)
        }
    }
}

/// Turn-by-turn instructions for a walking leg, and where a GPS fix is along it.
/// The router marks stairs and levels but rarely turns, so turns come from the path's shape.
struct WalkRoute: Sendable {
    let path: [LatLon]
    let length: Double
    let maneuvers: [WalkManeuver]
    /// Router distance per metre of path (stairs are shorter on the map than on foot).
    let scale: Double

    struct Position: Equatable, Sendable {
        /// Metres walked along the path.
        let along: Double
        /// Metres between the fix and the path.
        let offset: Double
        let remaining: Double
        /// The instruction being followed.
        let index: Int
        /// Metres to the next instruction.
        let toNext: Double?
    }

    /// Turns sharper than this are instructions; gentler bends are "straight on".
    static let minTurn = 35.0
    /// A new instruction needs this much walking since the last one (no zigzag in passages).
    static let minSpacing = 10.0

    init(leg: Leg) {
        var path: [LatLon] = []
        var marks: [(along: Double, step: StepInstruction)] = []
        var walked = 0.0
        for step in leg.steps ?? [] {
            let coords = step.polyline?.coordinates ?? []
            marks.append((walked, step))
            for c in coords {
                if let last = path.last {
                    if last == c { continue }
                    walked += last.distance(to: c)
                }
                path.append(c)
            }
        }
        if path.count < 2 {
            path = leg.legGeometry.coordinates.count >= 2 ? leg.legGeometry.coordinates : [leg.from.coordinate, leg.to.coordinate]
            marks = []
            walked = PathGeometry.length(path)
        }
        self.path = path
        length = walked

        // Level ranges of stairs are not in walking order: the levels before and after them are.
        func levelBefore(_ k: Int) -> Double? { k > 0 ? (marks[k - 1].step.toLevel ?? marks[k - 1].step.fromLevel) : nil }
        func levelAfter(_ k: Int) -> Double? { k + 1 < marks.count ? (marks[k + 1].step.fromLevel ?? marks[k + 1].step.toLevel) : nil }

        var out = [WalkManeuver(kind: .start, along: 0, street: marks.first.flatMap { Self.street($0.step) },
                                level: nil, coordinate: path[0])]
        for (k, mark) in marks.enumerated() where k > 0 {
            let step = mark.step, at = mark.along
            let own = [step.fromLevel, step.toLevel].compactMap { $0 }
            let before = levelBefore(k) ?? own.first
            var after = levelAfter(k)
            if after == nil || after == before { after = own.first { $0 != before } ?? after }
            let climb = (after ?? 0) - (before ?? 0)
            var kind: WalkManeuver.Kind? = Self.explicitKind(step.relativeDirection)
            switch step.relativeDirection {
            case "STAIRS": kind = climb > 0 ? .stairsUp : climb < 0 ? .stairsDown : .stairs
            case "ELEVATOR": kind = .elevator
            default: if kind == nil, climb != 0, own.count == 2, own[0] != own[1] { kind = climb > 0 ? .levelUp : .levelDown }
            }
            let sinceLast = at - (out.last?.along ?? 0)
            if kind == nil, sinceLast >= Self.minSpacing,
               let b = PathGeometry.point(at: at - 15, on: path), let junction = PathGeometry.point(at: at, on: path),
               let a = PathGeometry.point(at: at + 15, on: path), b != junction, junction != a {
                kind = Self.turnKind(PathGeometry.turn(from: PathGeometry.bearing(b, junction),
                                                       to: PathGeometry.bearing(junction, a)))
            }
            let name = Self.street(step)
            let point = PathGeometry.point(at: at, on: path) ?? path[0]
            if let kind {
                let changesLevel = [.stairsUp, .stairsDown, .elevator, .levelUp, .levelDown].contains(kind)
                out.append(WalkManeuver(kind: kind, along: at, street: changesLevel ? nil : name,
                                        level: changesLevel ? after : nil, coordinate: point))
            } else if let name, var last = out.last, last.street != name {
                if last.street == nil, at - last.along < 30, !Self.changesLevel(last.kind) {
                    // The street just turned into gets its name.
                    last.street = name
                    out[out.count - 1] = last
                } else if sinceLast >= Self.minSpacing, last.street != nil, step.area != true {
                    out.append(WalkManeuver(kind: .straight, along: at, street: name, level: nil, coordinate: point))
                }
            }
        }
        // Passages zigzag: a slight bend without a street, or a turn right before the next one, is noise.
        out = out.enumerated().filter { i, m in
            guard i > 0, i < out.count, Self.isTurn(m.kind) else { return true }
            if (m.kind == .slightLeft || m.kind == .slightRight) && m.street == nil { return false }
            let gap = (i + 1 < out.count ? out[i + 1].along : walked) - m.along
            if i + 1 < out.count, Self.isTurn(out[i + 1].kind), gap < 8 { return false }
            if gap < 4 || (i + 1 == out.count && gap < 8) { return false }
            return true
        }.map(\.element)
        out.append(WalkManeuver(kind: .arrive, along: walked, street: nil, level: nil, coordinate: path[path.count - 1]))
        // Stairs are flat in the geometry; the router's distance is the one to show.
        let scale = leg.distance.map { walked > 0 ? $0 / walked : 1 }.flatMap { (0.7...1.6).contains($0) ? $0 : nil } ?? 1
        self.scale = scale
        for i in out.indices.dropLast() { out[i].distance = max(0, out[i + 1].along - out[i].along) * scale }
        Self.markExit(&out, startLevel: marks.first?.step.fromLevel, start: leg.from.coordinate)
        maneuvers = out
    }

    /// Signs in stations name the street an exit leads to; failing that, its side of the station.
    /// Only when the walk starts below street level (getting off an underground train).
    private static func markExit(_ out: inout [WalkManeuver], startLevel: Double?, start: LatLon) {
        guard var level = startLevel, level < 0 else { return }
        for i in out.indices {
            guard let next = out[i].level else { continue }
            defer { level = next }
            guard level < 0, next >= 0 else { continue }
            let at = out[i].along
            let street = out[(i + 1)...].first { $0.street != nil && $0.along - at <= 150 }?.street
            if let street {
                out[i].exitTowards = street
            } else if start.distance(to: out[i].coordinate) >= 40 {
                out[i].exitSide = Compass.point(PathGeometry.bearing(start, out[i].coordinate))
            }
            return
        }
    }

    /// Street name; stairs and lifts carry entrance codes ("MP09") instead, which are not on signs.
    private static func street(_ step: StepInstruction) -> String? {
        guard step.relativeDirection != "STAIRS", step.relativeDirection != "ELEVATOR",
              let s = step.streetName?.trimmingCharacters(in: .whitespaces), s.count > 2,
              !s.allSatisfy(\.isNumber) else { return nil }
        return s
    }

    private static func isTurn(_ k: WalkManeuver.Kind) -> Bool {
        [.slightLeft, .left, .sharpLeft, .slightRight, .right, .sharpRight, .uTurn].contains(k)
    }

    private static func changesLevel(_ k: WalkManeuver.Kind) -> Bool {
        [.stairsUp, .stairsDown, .stairs, .elevator, .levelUp, .levelDown].contains(k)
    }

    private static func explicitKind(_ direction: String?) -> WalkManeuver.Kind? {
        switch direction {
        case "LEFT": return .left
        case "SLIGHTLY_LEFT": return .slightLeft
        case "HARD_LEFT": return .sharpLeft
        case "RIGHT": return .right
        case "SLIGHTLY_RIGHT": return .slightRight
        case "HARD_RIGHT": return .sharpRight
        case "UTURN_LEFT", "UTURN_RIGHT": return .uTurn
        default: return nil
        }
    }

    static func turnKind(_ delta: Double) -> WalkManeuver.Kind? {
        let a = abs(delta)
        guard a >= minTurn else { return nil }
        if a >= 165 { return .uTurn }
        if delta < 0 { return a < 60 ? .slightLeft : a < 135 ? .left : .sharpLeft }
        return a < 60 ? .slightRight : a < 135 ? .right : .sharpRight
    }

    func position(of p: LatLon) -> Position? {
        guard let hit = PathGeometry.locate(p, on: path) else { return nil }
        let along = min(hit.along, length)
        // An instruction counts as reached a few metres early (GPS lags behind).
        let index = maneuvers.lastIndex { $0.along <= along + 5 && $0.kind != .arrive } ?? 0
        let next = maneuvers.indices.contains(index + 1) ? maneuvers[index + 1] : nil
        return Position(along: along, offset: hit.offset, remaining: max(0, length - along) * scale, index: index,
                        toNext: next.map { max(0, $0.along - along) * scale })
    }

    /// Previewed future/past walks retain their planned length. GPS only shortens the current
    /// walk when the fix is close enough to its path, not a projection from another city.
    func livePosition(of coordinate: LatLon?, isActive: Bool) -> Position? {
        guard isActive, let coordinate, let position = position(of: coordinate), position.offset <= 250 else { return nil }
        return position
    }

    /// Clearly away from the path, allowing for the fix's accuracy.
    static func isOffRoute(_ position: Position, accuracy: Double) -> Bool {
        position.offset > max(50, accuracy * 1.5)
    }
}
