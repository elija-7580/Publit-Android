import Foundation

/// Circle lines (ACTV 5.1/5.2 from Lido, town buses that loop back) carry the stop they start
/// from as headsign. "Towards Lido" while you board at Lido and ride away from it reads as the
/// wrong direction, so such a leg shows where you ride to, marked as a circle line.
enum CircularLine {
    /// Without the quoted pier or platform letter: `Lido S.M.E. "A"` and `Lido S.M.E. "B"` are one place.
    static func baseName(_ name: String) -> String {
        var s = name.replacingOccurrences(of: #"\s*["“”„][^"“”„]{1,6}["“”„]"#, with: "", options: .regularExpression)
        s = s.replacingOccurrences(of: #"\s*\([^)]{1,12}\)\s*$"#, with: "", options: .regularExpression)
        s = s.replacingOccurrences(of: #"\s+[A-Z]$"#, with: "", options: .regularExpression)
        return StopGrouping.normalizedName(s)
    }

    /// The vehicle's headsign is the stop it is leaving from (or the same place on another pier).
    static func isLoop(headsign: String?, origin: String) -> Bool {
        guard let h = TransitLabels.clean(headsign) else { return false }
        let a = baseName(h)
        return !a.isEmpty && a == baseName(origin)
    }
}

extension Leg {
    var isCircular: Bool { isTransit && CircularLine.isLoop(headsign: headsign, origin: from.name) }
    /// Shown after "Towards": on a circle line the stop you get off at, otherwise the headsign.
    var shownDirection: String? { isCircular ? TransitLabels.clean(to.name) : directionName }
}

extension StopTime {
    var isCircular: Bool {
        CircularLine.isLoop(headsign: headsign, origin: place.name)
            || tripTo.map { CircularLine.isLoop(headsign: $0.name, origin: place.name) } == true
    }
    /// A departure of a circle line has no useful destination: it says "Circle line" instead.
    var shownDirection: String? { isCircular ? nil : directionName }
}
