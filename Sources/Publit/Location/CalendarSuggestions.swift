// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2026 Elija Samuel Friedrich-Ulrich
import Foundation
import Observation

/// Upcoming calendar events with a place, read on the phone (Android: CalendarContract through
/// `Droid.calendarEvents`, see Skip/Droid.kt). Off until the person turns it on; calendar access is
/// asked for only then. The events stay on the device: a place is looked up (and so sent to a search)
/// only when its route is opened.
@MainActor
@Observable
final class CalendarSuggestions {
    static let shared = CalendarSuggestions()

    enum Access: String { case notAsked = "0", denied = "1", granted = "2" }

    private(set) var appointments: [CalendarAppointment] = []
    private(set) var access: Access = CalendarSuggestions.currentAccess()
    private(set) var enabled = UserDefaults.standard.bool(forKey: CalendarTrips.enabledKey)
    /// A place of an event is being looked up (one at a time).
    private(set) var resolving: String?

    @ObservationIgnored private var lastHere: LatLon?
    @ObservationIgnored private var lastRead: Date?

    /// Allowed and on: events are read.
    var isActive: Bool { enabled && access == .granted }
    /// Asked and refused: only the system settings can allow it again.
    var isBlocked: Bool { access == .denied }

    private static func currentAccess() -> Access {
        #if os(Android)
        return Access(rawValue: Droid.string("calendarPermission") ?? "0") ?? .notAsked
        #else
        return .notAsked
        #endif
    }

    /// Turns the suggestions on and asks for calendar access once (the system shows its own question).
    @discardableResult
    func enable() async -> Bool {
        #if os(Android)
        if access != .granted {
            let answer = await DroidRequests.shared.run { id in Droid.call("requestCalendarPermission", id) }
            access = Access(rawValue: answer) ?? Self.currentAccess()
        }
        #endif
        setEnabled(access == .granted)
        refresh(here: lastHere, force: true)
        return enabled
    }

    func disable() {
        setEnabled(false)
        lastRead = nil
        appointments = []
    }

    private func setEnabled(_ on: Bool) {
        enabled = on
        UserDefaults.standard.set(on, forKey: CalendarTrips.enabledKey)
    }

    /// Opens the app's page in the system settings (access was refused).
    func openSystemSettings() {
        #if os(Android)
        Droid.call("openAppSettings")
        #endif
    }

    /// Reads the events of the next hours again (on appearing). Within a minute the last reading is
    /// kept, unless `force`.
    func refresh(here: LatLon?, now: Date = .now, force: Bool = false) {
        lastHere = here ?? lastHere
        let current = Self.currentAccess()
        if current != access { access = current }
        guard isActive else { if !appointments.isEmpty { appointments = [] }; return }
        if !force, let lastRead, now.timeIntervalSince(lastRead) < 60, now >= lastRead { return }
        lastRead = now
        let next = CalendarTrips.suggestions(readEvents(now: now), now: now, here: lastHere)
        if next != appointments { appointments = next }
    }

    private struct RawEvent: Decodable {
        var id: String
        var title: String
        var start: Double
        var end: Double
        var allDay: Bool
        var location: String
        var cancelled: Bool
        var declined: Bool
    }

    private func readEvents(now: Date) -> [CalendarAppointment] {
        #if os(Android)
        let from = now.addingTimeInterval(-3600).timeIntervalSince1970 * 1000
        let to = now.addingTimeInterval(CalendarTrips.lookahead).timeIntervalSince1970 * 1000
        guard let json = Droid.string("calendarEvents", from, to), let data = json.data(using: .utf8),
              let raw = try? JSONDecoder().decode([RawEvent].self, from: data) else { return [] }
        return raw.filter { !$0.cancelled && !$0.declined }.map { e in
            CalendarAppointment(id: "\(e.id)|\(Int(e.start / 1000))", title: e.title,
                                start: Date(timeIntervalSince1970: e.start / 1000),
                                end: Date(timeIntervalSince1970: e.end / 1000),
                                location: e.location, lat: nil, lon: nil, isAllDay: e.allDay)
        }
        #else
        return []
        #endif
    }

    /// Coordinates for the event's place, looked up with the open geocoder. Only called when its route is opened.
    func resolve(_ a: CalendarAppointment, near: LatLon?) async -> SavedPlace? {
        if let c = a.coordinate { return a.place(at: c) }
        guard resolving == nil else { return nil }
        resolving = a.id
        defer { resolving = nil }
        if let m = try? await TransitousClient.shared.geocode(a.searchText, near: near).first {
            return a.place(at: LatLon(lat: m.lat, lon: m.lon))
        }
        // A full address often fails as one string: try its first line (the place's name) alone.
        if a.placeName != a.searchText, let m = try? await TransitousClient.shared.geocode(a.placeName, near: near).first {
            return a.place(at: LatLon(lat: m.lat, lon: m.lon))
        }
        return nil
    }
}
