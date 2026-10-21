#if os(Android)
import Foundation

/// Android counterpart of the Live Activity: an ongoing notification of a saved trip with
/// "Leave in 6 min", then the ride, and a countdown (chronometer) to the next event.
/// Same API as the iOS `LiveTripActivity`, so PublitApp, TripSession and the views run unchanged.
@MainActor
enum LiveTripActivity {
    static let maxLead: TimeInterval = 8 * 3600
    static var isAvailable: Bool { Droid.bool("notificationsAllowed") }
    static let afterArrival: TimeInterval = 3 * 60
    static let backgroundGrace: TimeInterval = 5 * 60

    /// Trips with a notification shown by this process, with the last state.
    private static var running: [String: TripActivityAttributes.ContentState] = [:]
    private static var attributes: [String: TripActivityAttributes] = [:]
    /// Handed over in the background (still on screen, ends by itself).
    private static var handedOver: Set<String> = []

    static func isOver(_ trip: SavedTrip, now: Date = .now) -> Bool {
        now >= trip.endTime.addingTimeInterval(afterArrival)
    }

    static func isRunning(_ tripId: String) -> Bool { running[tripId] != nil && !handedOver.contains(tripId) }

    static func state(for trip: SavedTrip, now: Date = .now) -> TripActivityAttributes.ContentState {
        let s = TripStatus.at(now, trip.itinerary, countdown: false)
        let legs = trip.itinerary.legs
        let leg = legs.first { $0.isTransit && $0.endTime > now } ?? trip.itinerary.firstTransitLeg
        let riding = leg.map { $0.startTime <= now } ?? false
        let nextIndex = legs.firstIndex { $0.isTransit && $0.startTime > now }
        let departing = nextIndex.map { legs[$0] }
        var state = TripActivityAttributes.ContentState(
            phase: s.phase.rawValue, headline: s.headline, detail: s.detail, nextEvent: s.nextEvent,
            delayMinutes: s.delayMinutes, arrival: trip.endTime, lineName: leg?.lineName,
            lineColor: leg?.lineColors.background, lineTextColor: leg?.lineColors.text, updatedAt: now)
        if let departing, s.phase != .cancelled, s.phase != .arrived {
            state.platform = TransitLabels.clean(departing.from.track).map { L10n.f("Pl. %@", $0) }
            state.platformChanged = departing.from.platformChange != nil ? true : nil
            if riding {
                state.then = L10n.f("Then: %@", L10n.f("%@ at %@", departing.lineName, Fmt.time(departing.startTime, tz: departing.from.tz)))
            }
        }
        return state
    }

    enum StartResult { case started, tooEarly, unavailable, failed(String) }

    @discardableResult
    static func start(_ trip: SavedTrip, hasTicket: Bool, now: Date = .now) -> StartResult {
        guard isAvailable else { return .unavailable }
        guard trip.startTime.timeIntervalSince(now) < maxLead else { return .tooEarly }
        attributes[trip.id] = TripActivityAttributes(tripId: trip.id, title: trip.title, hasTicket: hasTicket)
        handedOver.remove(trip.id)
        show(trip, state(for: trip, now: now), timeout: 0)
        return .started
    }

    static func update(_ trip: SavedTrip, now: Date = .now) async {
        guard let old = running[trip.id], !handedOver.contains(trip.id) else { return }
        if now >= trip.endTime {
            let gone = trip.endTime.addingTimeInterval(afterArrival)
            show(trip, state(for: trip, now: now), timeout: max(1, gone.timeIntervalSince(now)))
            running[trip.id] = nil
        } else {
            let new = state(for: trip, now: now)
            guard !same(old, new) else { return }
            show(trip, new, timeout: 0)
        }
    }

    static func end(_ tripId: String) async {
        running[tripId] = nil
        handedOver.remove(tripId)
        attributes[tripId] = nil
        Droid.call("liveEnd", tripId)
    }

    /// App goes to the background: the notification stays with its last state and a countdown,
    /// and goes by itself shortly after the arrival (nothing updates it any more).
    static func handOver(_ lookup: (String) -> SavedTrip?, now: Date = .now) async {
        for id in Array(running.keys) {
            guard let trip = lookup(id), !isOver(trip, now: now) else { await end(id); continue }
            let gone = trip.endTime.addingTimeInterval(now >= trip.endTime ? afterArrival : backgroundGrace)
            show(trip, state(for: trip, now: now), timeout: max(1, gone.timeIntervalSince(now)))
            handedOver.insert(id)
        }
    }

    /// App back in the foreground: handed-over trips run live again, finished ones go.
    static func resume(_ lookup: (String) -> SavedTrip?, now: Date = .now) async -> [SavedTrip] {
        var restart: [SavedTrip] = []
        for id in Array(handedOver) {
            handedOver.remove(id)
            if let trip = lookup(id), now < trip.endTime { restart.append(trip) } else { await end(id) }
        }
        for id in Array(running.keys) where !handedOver.contains(id) {
            if lookup(id).map({ isOver($0, now: now) }) ?? true { await end(id) }
        }
        return restart
    }

    static func same(_ a: TripActivityAttributes.ContentState, _ b: TripActivityAttributes.ContentState) -> Bool {
        var b = b
        b.updatedAt = a.updatedAt
        return a == b
    }

    private static func show(_ trip: SavedTrip, _ state: TripActivityAttributes.ContentState, timeout: TimeInterval) {
        running[trip.id] = state
        let attrs = attributes[trip.id] ?? TripActivityAttributes(tripId: trip.id, title: trip.title, hasTicket: false)
        var json: [String: Any] = [
            "id": trip.id, "title": attrs.title, "headline": state.headline, "detail": state.detail,
            "next": state.nextEvent.timeIntervalSince1970 * 1000, "arrival": state.arrival.timeIntervalSince1970 * 1000,
            "url": attrs.tripURL.absoluteString, "timeout": timeout * 1000,
            "phase": state.phase, "delay": state.delayMinutes,
        ]
        if attrs.hasTicket { json["ticketsURL"] = attrs.ticketsURL.absoluteString; json["ticketsLabel"] = L10n.t("Ticket") }
        if let v = state.lineName { json["line"] = v }
        if let v = state.lineColor { json["lineColor"] = v }
        if let v = state.platform { json["platform"] = v }
        if let v = state.then { json["then"] = v }
        if let data = try? JSONSerialization.data(withJSONObject: json), let text = String(data: data, encoding: .utf8) {
            Droid.call("liveShow", text)
        }
    }
}
#endif
