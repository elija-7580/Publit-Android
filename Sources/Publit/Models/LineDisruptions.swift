import Foundation

/// Service changes on a saved line worth a notification: alerts and cancelled departures.
struct LineDisruption: Hashable, Sendable {
    /// Same disruption, same fingerprint: each is announced once.
    let fingerprint: String
    let title: String
    let body: String

    /// - Parameter horizon: only departures in the next `horizon` seconds count.
    static func detect(_ line: SavedLine, in stopTimes: [StopTime], now: Date = .now,
                       horizon: TimeInterval = 2 * 3600) -> [LineDisruption] {
        let mine = stopTimes.filter { line.matches($0) && $0.departureTime >= now.addingTimeInterval(-60)
            && $0.departureTime <= now.addingTimeInterval(horizon) }
        var out: [LineDisruption] = []
        var seenAlerts = Set<String>()
        for st in mine {
            for a in st.serviceAlerts where seenAlerts.insert(a.headerText).inserted {
                let body = a.descriptionText.isEmpty ? L10n.f("At %@", line.stopName) : String(a.descriptionText.prefix(240))
                out.append(LineDisruption(fingerprint: "alert|\(line.id)|\(stableHash(a.headerText))",
                                          title: "\(line.lineName): \(a.headerText)", body: body))
            }
        }
        let cancelled = mine.filter(\.isCancelled)
        if let first = cancelled.first {
            let tz = first.place.tz
            let times = cancelled.prefix(3).map { Fmt.time($0.scheduledDepartureTime ?? $0.departureTime, tz: tz) }
            let title = cancelled.count == 1
                ? L10n.f("%@: departure cancelled", line.lineName)
                : L10n.f("%@: %lld departures cancelled", line.lineName, cancelled.count)
            let body = L10n.f("At %@: %@", line.stopName, times.joined(separator: ", "))
            out.append(LineDisruption(fingerprint: "cancel|\(line.id)|" + cancelled.map(\.tripId).sorted().joined(separator: ","),
                                      title: title, body: body))
        }
        return out
    }

    /// FNV-1a: the same text gives the same value in every launch (unlike `hashValue`).
    static func stableHash(_ s: String) -> String {
        var h: UInt64 = 0xcbf29ce484222325
        for b in s.utf8 { h ^= UInt64(b); h = h &* 0x100000001b3 }
        return String(h, radix: 16)
    }
}

/// Disruptions already announced, so the same alert does not come again on every check.
struct DisruptionLedger: Codable, Sendable {
    var announced: [String: Date] = [:]

    static let keepFor: TimeInterval = 2 * 24 * 3600

    /// The new ones among `list`, and remembers them.
    mutating func takeNew(_ list: [LineDisruption], now: Date = .now) -> [LineDisruption] {
        announced = announced.filter { now.timeIntervalSince($0.value) < Self.keepFor }
        let fresh = list.filter { announced[$0.fingerprint] == nil }
        for d in fresh { announced[d.fingerprint] = now }
        return fresh
    }

    static let storageKey = "publit.disruptions.v1"

    static func load(_ defaults: UserDefaults = .standard) -> DisruptionLedger {
        defaults.data(forKey: storageKey).flatMap { try? JSONDecoder().decode(DisruptionLedger.self, from: $0) } ?? DisruptionLedger()
    }

    func save(_ defaults: UserDefaults = .standard) {
        if let data = try? JSONEncoder().encode(self) { defaults.set(data, forKey: Self.storageKey) }
    }
}

/// "I'm arriving at …" text to send to someone, with the live time.
enum ArrivalShare {
    static func message(to destination: String, arrival: Date, tz: String?, delayMinutes: Int, now: Date = .now) -> String {
        let time = Fmt.time(arrival, tz: tz)
        if arrival <= now { return L10n.f("I've arrived at %@.", destination) }
        var text = L10n.f("I'm arriving at %@ at %@.", destination, time)
        if delayMinutes >= 2 { text += " " + L10n.f("(%lld min late)", delayMinutes) }
        return text
    }

    static func message(for it: Itinerary, to destination: String, now: Date = .now) -> String {
        let last = it.legs.last
        let delay = last.map { Int(($0.endTime.timeIntervalSince($0.scheduledEndTime) / 60).rounded()) } ?? 0
        return message(to: destination, arrival: it.endTime, tz: last?.to.tz, delayMinutes: delay, now: now)
    }

    /// Worth telling again when the arrival moved by this much since it was shared.
    static let notableShift: TimeInterval = 3 * 60

    static func shifted(sharedArrival: Date, current: Date) -> Bool {
        abs(current.timeIntervalSince(sharedArrival)) >= notableShift
    }
}
