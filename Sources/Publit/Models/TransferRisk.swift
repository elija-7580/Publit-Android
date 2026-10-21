import Foundation

/// A change between two transit legs that is tight or already broken.
struct TransferWarning: Hashable, Sendable {
    enum Level: Sendable { case tight, missed }
    let stopName: String
    /// Minutes between arrival of the incoming and departure of the outgoing vehicle (can be negative).
    let bufferMinutes: Int
    let level: Level

    var message: String {
        switch level {
        case .missed: return L10n.f("Change at %@ no longer works", stopName)
        case .tight: return bufferMinutes <= 0 ? L10n.f("Very tight change at %@", stopName)
                                               : L10n.f("Tight change at %@ (%lld min)", stopName, bufferMinutes)
        }
    }
}

enum TransferRisk {
    /// Below this many minutes a change counts as tight, if it involves a walk or a delayed leg.
    static let tightMinutes = 3

    /// Uses real-time times where available. A change is flagged when the time left is
    /// shorter than the walk between the vehicles, or under `tightMinutes` while the
    /// incoming leg is running late.
    static func warnings(for itinerary: Itinerary) -> [TransferWarning] {
        var out: [TransferWarning] = []
        let legs = itinerary.legs
        var i = 0
        while i < legs.count {
            guard legs[i].isTransit else { i += 1; continue }
            var j = i + 1
            var walkSeconds = 0
            while j < legs.count, !legs[j].isTransit { walkSeconds += legs[j].duration; j += 1 }
            guard j < legs.count else { break }
            let incoming = legs[i], outgoing = legs[j]
            let gap = outgoing.startTime.timeIntervalSince(incoming.endTime)
            let buffer = Int(((gap - Double(walkSeconds)) / 60).rounded(.down))
            let late = incoming.realTime && incoming.endTime > incoming.scheduledEndTime.addingTimeInterval(59)
            if gap < Double(walkSeconds) {
                out.append(TransferWarning(stopName: incoming.to.name, bufferMinutes: buffer, level: .missed))
            } else if late && buffer < tightMinutes {
                out.append(TransferWarning(stopName: incoming.to.name, bufferMinutes: buffer, level: .tight))
            }
            i = j
        }
        return out
    }
}
