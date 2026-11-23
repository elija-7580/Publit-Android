// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2026 Elija Samuel Friedrich-Ulrich
import SwiftUI

/// Opens the route to a calendar event: looks up its place (only now, on the tap) and fills Plan.
@MainActor
enum CalendarRouting {
    static func open(_ a: CalendarAppointment, app: AppState, near: LatLon?) {
        // One lookup at a time: a second tap while a place is looked up does nothing.
        guard CalendarSuggestions.shared.resolving == nil else { return }
        Task {
            if let place = await CalendarSuggestions.shared.resolve(a, near: near) {
                app.route(to: a, place: place)
            } else {
                app.routeShared(to: nil)
                app.plan.showError(L10n.f("No place found for “%@”.", a.placeName))
            }
        }
    }

    /// "Today", or the short weekday for a later day.
    static func day(_ date: Date, now: Date = .now) -> String {
        if Calendar.current.isDate(date, inSameDayAs: now) { return L10n.t("Today") }
        let f = DateFormatter()
        f.locale = Locale.current
        f.dateFormat = "EEE"
        return f.string(from: date)
    }
}

/// One event as a destination: a small calendar leaf with the time, then title and place.
struct CalendarAppointmentRow: View {
    let appointment: CalendarAppointment
    var resolving = false

    var body: some View {
        HStack(spacing: 12) {
            VStack(spacing: 0) {
                Text(verbatim: CalendarRouting.day(appointment.start))
                    .font(.caption2.weight(.bold))
                    .textCase(.uppercase)
                    .foregroundStyle(.secondary)
                    .lineLimit(1).minimumScaleFactor(0.6)
                Text(verbatim: Fmt.time(appointment.start))
                    .font(.subheadline.weight(.bold).monospacedDigit())
                    .foregroundStyle(Color.ink)
                    .lineLimit(1).minimumScaleFactor(0.7)
            }
            .padding(.horizontal, 4)
            .frame(width: 58, height: 44)
            .background(Color.inkFill, in: RoundedRectangle(cornerRadius: 10, style: .continuous))
            VStack(alignment: .leading, spacing: 1) {
                Text(verbatim: appointment.displayTitle)
                    .font(.body.weight(.semibold)).foregroundStyle(.primary).lineLimit(1)
                Label {
                    Text(verbatim: appointment.placeName).lineLimit(1)
                } icon: {
                    Image(systemName: appointment.coordinate == nil ? "mappin" : "mappin.circle.fill")
                }
                .labelStyle(CompactLabelStyle())
                .font(.subheadline).foregroundStyle(.secondary)
            }
            Spacer(minLength: 0)
            if resolving {
                ProgressView()
            } else {
                Image(systemName: "arrow.right")
                    .font(.footnote.weight(.bold))
                    .foregroundStyle(Color.secondary)
            }
        }
        .frame(minHeight: 50)
        .contentShape(Rectangle())
        .accessibilityElement(children: .combine)
        .accessibilityHint(Text("Shows routes that get you there on time"))
    }
}

/// Plan, before a search: the next events with a place, or once the offer to suggest them.
struct CalendarPlanSection: View {
    @Environment(AppState.self) private var app
    @Environment(LocationManager.self) private var location
    @AppStorage(CalendarTrips.invitationDismissedKey) private var dismissed = false
    private var calendar: CalendarSuggestions { .shared }

    var body: some View {
        Group {
            if calendar.isActive {
                if !calendar.appointments.isEmpty {
                    Section {
                        ForEach(calendar.appointments) { a in
                            Button { CalendarRouting.open(a, app: app, near: location.coordinate) } label: {
                                CalendarAppointmentRow(appointment: a, resolving: calendar.resolving == a.id)
                            }
                            .buttonStyle(.plain)
                            .accessibilityIdentifier("calendarAppointment")
                        }
                    } header: {
                        Label("From your calendar", systemImage: "calendar")
                    }
                    .inkCard()
                }
            } else if !dismissed && !calendar.isBlocked && !calendar.enabled {
                Section {
                    VStack(alignment: .leading, spacing: 10) {
                        HStack(alignment: .top, spacing: 12) {
                            Image(systemName: "calendar.badge.clock")
                                .font(.title3.weight(.semibold))
                                .foregroundStyle(Color.ink)
                                .frame(width: 34, height: 34)
                                .background(Color.inkFill, in: Circle())
                            VStack(alignment: .leading, spacing: 3) {
                                Text("Your appointments as destinations").font(.body.weight(.semibold))
                                Text("Publit suggests routes to upcoming events that have a place, so you arrive on time. Your calendar is read on this device only.")
                                    .font(.subheadline).foregroundStyle(.secondary)
                                    .fixedSize(horizontal: false, vertical: true)
                            }
                        }
                        HStack(spacing: 10) {
                            Button { Task { await calendar.enable(); calendar.refresh(here: location.coordinate, force: true) } } label: {
                                Text("Turn on").frame(maxWidth: .infinity)
                            }
                            .buttonStyle(.ink)
                            .accessibilityIdentifier("calendarTurnOn")
                            Button { withAnimation { dismissed = true } } label: {
                                Text("Not now").frame(maxWidth: .infinity)
                            }
                            .buttonStyle(.soft)
                        }
                    }
                    .padding(.vertical, 4)
                    .buttonStyle(.borderless)
                }
                .inkCard()
            }
        }
    }
}

/// Above the routes to a calendar event: what it is and when to leave to be there a little early.
struct AppointmentBanner: View {
    let appointment: CalendarAppointment
    let itineraries: [Itinerary]

    var body: some View {
        let target = appointment.arriveBy
        let best = CalendarTrips.latestOnTime(itineraries, arriveBy: target)
        HStack(alignment: .top, spacing: 12) {
            Image(systemName: "calendar")
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(Color.ink)
                .frame(width: 34, height: 34)
                .background(Color.inkFill, in: Circle())
            VStack(alignment: .leading, spacing: 2) {
                Text(verbatim: "\(appointment.displayTitle) · \(Fmt.time(appointment.start))")
                    .font(.body.weight(.semibold)).lineLimit(2)
                if let best {
                    Text(L10n.f("Leave by %@ to arrive %lld min early", Fmt.time(best.startTime, tz: best.legs.first?.from.tz),
                                Int(CalendarTrips.arriveEarly / 60)))
                        .font(.subheadline.weight(.semibold)).foregroundStyle(Color.ink)
                        .accessibilityIdentifier("appointmentLeaveBy")
                } else if !itineraries.isEmpty {
                    Text(L10n.f("No connection arrives before %@", Fmt.time(appointment.start)))
                        .font(.subheadline).foregroundStyle(Color.delay)
                }
            }
            Spacer(minLength: 0)
        }
        .padding(.vertical, 4)
        .accessibilityElement(children: .combine)
    }
}

/// Settings: the switch for calendar suggestions, with what it reads and where it stays.
struct CalendarSettingsSection: View {
    private var calendar: CalendarSuggestions { .shared }

    var body: some View {
        Section {
            Toggle(isOn: Binding(get: { calendar.isActive }, set: { on in
                if on { Task { await calendar.enable() } } else { calendar.disable() }
            })) {
                Label("Suggest appointments as destinations", systemImage: "calendar.badge.clock")
            }
            .disabled(calendar.isBlocked)
            .accessibilityIdentifier("calendarSuggestionsToggle")
            if calendar.isBlocked {
                Button("Open Settings") { calendar.openSystemSettings() }
            }
        } header: {
            Text("Calendar")
        } footer: {
            if calendar.isBlocked {
                Text("Calendar access is off for Publit. Turn it on in the Settings app.")
            } else {
                Text("Upcoming events with a place appear in Plan, in the destination search and in Nearby, with routes that arrive a few minutes early. Your calendar is read on this device only; a place is looked up only when you open its route.")
            }
        }
    }
}
