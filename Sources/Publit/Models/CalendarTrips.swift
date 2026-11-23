// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2026 Elija Samuel Friedrich-Ulrich
import Foundation

/// An upcoming calendar event with a place, offered as a destination (Plan, place search, Nearby).
/// Read on the device only: nothing of it leaves the phone until a route to it is asked for.
struct CalendarAppointment: Identifiable, Hashable, Codable, Sendable {
    /// Event identifier and start: the occurrences of a repeating event share one identifier.
    var id: String
    var title: String
    var start: Date
    var end: Date
    /// The place as written in the event ("Café Central\nHerrengasse 14, 1010 Wien").
    var location: String
    /// Set when the calendar stored the place with a map pin (picked from Apple Maps).
    var lat: Double?
    var lon: Double?
    var isAllDay = false

    var coordinate: LatLon? {
        guard let lat, let lon, lat != 0 || lon != 0 else { return nil }
        return LatLon(lat: lat, lon: lon)
    }

    /// The first line of the place, for a row ("Café Central").
    var placeName: String {
        location.split(whereSeparator: \.isNewline)
            .map { $0.trimmingCharacters(in: .whitespaces) }
            .first { !$0.isEmpty } ?? location
    }

    /// The place on one line, for the address lookup ("Café Central, Herrengasse 14, 1010 Wien").
    var searchText: String {
        location.split(whereSeparator: \.isNewline)
            .map { $0.trimmingCharacters(in: .whitespaces) }
            .filter { !$0.isEmpty }
            .joined(separator: ", ")
    }

    /// The title, or the place when the event has none.
    var displayTitle: String {
        let t = title.trimmingCharacters(in: .whitespacesAndNewlines)
        return t.isEmpty ? placeName : t
    }

    /// When to be there: a few minutes before the start.
    var arriveBy: Date { start.addingTimeInterval(-CalendarTrips.arriveEarly) }

    /// The destination for a route, once the place has coordinates.
    func place(at c: LatLon) -> SavedPlace {
        SavedPlace(id: CalendarTrips.placePrefix + id, name: placeName, subtitle: displayTitle,
                   lat: c.lat, lon: c.lon, stopId: nil)
    }
}

enum CalendarTrips {
    /// On only after the person turned it on (and allowed calendar access).
    static let enabledKey = "calendarSuggestions"
    /// "Not now" on the invitation in Plan: it does not come back (Settings still has the switch).
    static let invitationDismissedKey = "calendarSuggestionsInviteDismissed"
    /// Events up to this far ahead are offered.
    static let lookahead: TimeInterval = 36 * 3600
    /// Nearby offers the next event only when it starts within this time.
    static let nearbyWindow: TimeInterval = 4 * 3600
    /// Arrive this long before the event starts.
    static let arriveEarly: TimeInterval = 5 * 60
    /// An event starting sooner than this is not offered any more: too late to set off.
    static let minimumLead: TimeInterval = 5 * 60
    /// Closer than this (metres) to where you are: you are there already.
    static let alreadyThereRadius: Double = 200
    /// IDs of places made from an event start with this.
    static let placePrefix = "calendar|"

    /// Upcoming events worth a route, soonest first: not all-day, with a real place (no video call),
    /// starting within `lookahead`, and not where you are now.
    static func suggestions(_ events: [CalendarAppointment], now: Date, here: LatLon? = nil,
                            limit: Int = 3) -> [CalendarAppointment] {
        var seen = Set<String>()
        return events
            .filter { e in
                guard !e.isAllDay,
                      e.start.timeIntervalSince(now) >= minimumLead,
                      e.start.timeIntervalSince(now) <= lookahead,
                      isPhysicalPlace(e.location) else { return false }
                if let here, let c = e.coordinate, c.distance(to: here) < alreadyThereRadius { return false }
                return true
            }
            .sorted { $0.start < $1.start }
            .filter { seen.insert($0.id).inserted }
            .prefix(limit)
            .map { $0 }
    }

    /// The next event for Nearby, when it starts soon enough to set off for it from here.
    static func next(_ suggestions: [CalendarAppointment], now: Date) -> CalendarAppointment? {
        suggestions.first { $0.start.timeIntervalSince(now) <= nearbyWindow }
    }

    /// Words that mean the meeting happens on a screen, not at a place.
    private static let onlineMarkers = [
        "http://", "https://", "www.", "zoom.us", "zoom meeting", "teams.microsoft", "microsoft teams",
        "meet.google", "google meet", "webex", "skype", "facetime", "jitsi", "whereby",
        "online", "virtual", "virtuell", "remote", "telefon", "phone call", "conference call",
    ]

    /// A place someone can travel to: not empty and not a video call link.
    static func isPhysicalPlace(_ text: String) -> Bool {
        let t = text.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        guard t.count >= 2 else { return false }
        return !onlineMarkers.contains { t.contains($0) }
    }

    /// The route to take to be there on time: the one leaving last that still arrives by `arriveBy`.
    static func latestOnTime(_ itineraries: [Itinerary], arriveBy: Date) -> Itinerary? {
        itineraries
            .filter { !$0.isCancelled && $0.endTime <= arriveBy.addingTimeInterval(30) }
            .max { $0.startTime < $1.startTime }
    }
}
