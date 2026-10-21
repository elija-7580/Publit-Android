import Foundation

/// Where the traveller is in a trip at a given moment: drives the leave reminder,
/// the Live Activity and the status line of a saved trip.
struct TripStatus: Equatable, Sendable {
    enum Phase: String, Codable, Sendable { case beforeLeaving, walking, riding, transfer, arrived, cancelled }

    let phase: Phase
    /// The next moment that matters (leave, departure, arrival).
    let nextEvent: Date
    /// Short line for lock screen and notifications, e.g. "Leave in 6 min".
    let headline: String
    /// e.g. "U6 to Garching from Marienplatz, Pl. 2 at 16:12".
    let detail: String
    let delayMinutes: Int

    /// Minutes before leaving at which the leave reminder fires.
    static let leaveLead: TimeInterval = 5 * 60

    static func leaveTime(_ it: Itinerary) -> Date { it.startTime }
    static func reminderTime(_ it: Itinerary) -> Date { leaveTime(it).addingTimeInterval(-leaveLead) }

    /// A live refresh moved the departure by a minute or more, while the trip still lies ahead:
    /// a pending "time to leave" reminder has to move with it.
    static func reminderMoved(from old: Itinerary, to new: Itinerary, now: Date = .now) -> Bool {
        abs(reminderTime(new).timeIntervalSince(reminderTime(old))) >= 60 && new.startTime > now.addingTimeInterval(30)
    }

    /// - Parameter countdown: "Leave in 6 min". Without it "Leave at 14:05", which stays true between
    ///   updates (the Live Activity shows a running countdown next to it and is updated rarely).
    static func at(_ now: Date, _ it: Itinerary, countdown: Bool = true) -> TripStatus {
        let tz = it.legs.first?.from.tz
        let transit = it.transitLegs
        func describe(_ leg: Leg) -> String {
            let time = Fmt.time(leg.startTime, tz: leg.from.tz)
            let line: String
            if let h = leg.headsign, !h.isEmpty {
                line = L10n.f("%@ to %@ from %@", leg.lineName, h, leg.from.name)
            } else {
                line = L10n.f("%@ from %@", leg.lineName, leg.from.name)
            }
            if let change = leg.from.platformChange {
                return L10n.f("%@, Pl. %@ at %@", line, L10n.f("%@ (changed)", change.now), time)
            }
            if let t = leg.from.track, !t.isEmpty { return L10n.f("%@, Pl. %@ at %@", line, t, time) }
            return L10n.f("%@ at %@", line, time)
        }
        if it.isCancelled {
            let leg = it.legs.first { $0.isTransit && $0.cancelled == true }
            return TripStatus(phase: .cancelled, nextEvent: it.startTime, headline: L10n.t("Connection cancelled"),
                              detail: leg.map { L10n.f("%@ from %@ does not run.", $0.lineName, $0.from.name) } ?? L10n.t("Plan again."),
                              delayMinutes: 0)
        }
        let delay = transit.first?.delayMinutes ?? 0
        if now >= it.endTime {
            return TripStatus(phase: .arrived, nextEvent: it.endTime, headline: L10n.t("Arrived"),
                              detail: L10n.f("at %@ %@", it.legs.last?.to.name ?? L10n.t("destination"), Fmt.time(it.endTime, tz: it.legs.last?.to.tz)),
                              delayMinutes: 0)
        }
        if now < it.startTime {
            let mins = Int(ceil(it.startTime.timeIntervalSince(now) / 60))
            let head = mins <= 0 ? L10n.t("Leave now") : (mins < 60 && countdown) ? L10n.f("Leave in %lld min", mins)
                : L10n.f("Leave at %@", Fmt.time(it.startTime, tz: tz))
            let det = transit.first.map(describe) ?? L10n.f("Walk %@", Fmt.duration(seconds: it.duration))
            return TripStatus(phase: .beforeLeaving, nextEvent: it.startTime, headline: head, detail: det, delayMinutes: delay)
        }
        // Underway: find the leg we are in.
        guard let i = it.legs.firstIndex(where: { now < $0.endTime }) else {
            return TripStatus(phase: .arrived, nextEvent: it.endTime, headline: L10n.t("Arrived"), detail: "", delayMinutes: 0)
        }
        let leg = it.legs[i]
        if leg.isTransit {
            if now < leg.startTime {
                return TripStatus(phase: .transfer, nextEvent: leg.startTime,
                                  headline: L10n.f("%@ departs %@", leg.lineName, Fmt.time(leg.startTime, tz: leg.from.tz)),
                                  detail: describe(leg), delayMinutes: leg.delayMinutes)
            }
            return TripStatus(phase: .riding, nextEvent: leg.endTime,
                              headline: L10n.f("Get off at %@", leg.to.name),
                              detail: L10n.f("%@ arrives %@", leg.lineName, Fmt.time(leg.endTime, tz: leg.to.tz)),
                              delayMinutes: Int((leg.endTime.timeIntervalSince(leg.scheduledEndTime) / 60).rounded()))
        }
        if let next = it.legs[(i + 1)...].first(where: \.isTransit) {
            return TripStatus(phase: i == 0 ? .walking : .transfer, nextEvent: next.startTime,
                              headline: L10n.f("Walk to %@", next.from.name), detail: describe(next), delayMinutes: next.delayMinutes)
        }
        return TripStatus(phase: .walking, nextEvent: it.endTime,
                          headline: L10n.f("Walk to %@", it.legs.last?.to.name ?? L10n.t("destination")),
                          detail: L10n.f("Arrive %@", Fmt.time(it.endTime, tz: it.legs.last?.to.tz)), delayMinutes: 0)
    }
}

/// Text for the calendar event of a saved trip. The URL opens the trip with its live status.
enum CalendarText {
    static func title(_ trip: SavedTrip) -> String {
        let lines = trip.itinerary.transitLegs.map(\.lineName)
        return lines.isEmpty ? L10n.f("Walk to %@", trip.toName) : "\(trip.toName) (\(lines.joined(separator: ", ")))"
    }

    static func notes(_ trip: SavedTrip) -> String {
        var out: [String] = []
        for leg in trip.itinerary.legs where leg.isTransit {
            var s = "\(Fmt.time(leg.startTime, tz: leg.from.tz)) \(leg.lineName)"
            if let h = leg.headsign, !h.isEmpty { s += " → \(h)" }
            s += ", \(leg.from.name)"
            if let t = leg.from.track, !t.isEmpty { s += " (" + L10n.f("Pl. %@", t) + ")" }
            s += " – \(leg.to.name) \(Fmt.time(leg.endTime, tz: leg.to.tz))"
            out.append(s)
        }
        return out.joined(separator: "\n")
    }

    /// One short link in the event's URL field: opens the saved trip with live status and tickets.
    /// (The whole route as a link was 1–2 KB, and the web page behind share links needs GitHub Pages.)
    static func url(_ trip: SavedTrip) -> URL { DeepLink.trip(trip.id).url }

    static func location(_ trip: SavedTrip) -> String {
        trip.itinerary.firstTransitLeg?.from.name ?? trip.fromName
    }
}

/// How often the open app refreshes a running Live Activity. Each refresh is a network request
/// and a lock-screen redraw, so they are spread out when nothing is about to happen: the
/// countdown runs on the lock screen by itself.
enum LiveRefresh {
    /// Nothing running: look again in five minutes (a trip may need its Live Activity started).
    static let idle: TimeInterval = 300

    static func interval(start: Date, end: Date, nextEvent: Date, now: Date = .now) -> TimeInterval {
        let toStart = start.timeIntervalSince(now)
        if now >= end { return idle }
        if toStart > 3600 { return 600 }
        if toStart > 20 * 60 { return 180 }
        if toStart > 0 { return 60 }
        // Underway: a long ride with nothing to do for a while needs fewer checks.
        return nextEvent.timeIntervalSince(now) > 10 * 60 ? 120 : 60
    }

    /// The soonest refresh any of the trips needs.
    static func interval(_ trips: [Itinerary], now: Date = .now) -> TimeInterval {
        trips.map { interval(start: $0.startTime, end: $0.endTime, nextEvent: TripStatus.at(now, $0).nextEvent, now: now) }
            .min() ?? idle
    }
}

extension TripStatus {
    /// Why a route cannot be started, or nil when it can.
    static func startBlocked(_ it: Itinerary, now: Date) -> String? {
        if it.endTime < now { return L10n.t("This connection is already over. Plan again for a new one.") }
        if it.isCancelled {
            let leg = it.legs.first { $0.isTransit && $0.cancelled == true }
            return leg.map { L10n.f("%@ from %@ does not run. Choose another connection.", $0.lineName, $0.from.name) }
                ?? L10n.t("A ride on this connection does not run. Choose another connection.")
        }
        return nil
    }
}
