import SwiftUI
#if canImport(EventKit)
import EventKit
#endif
#if canImport(EventKitUI)
import EventKitUI
#endif

/// Save, calendar, leave reminder and tickets for one route. Used in route details and saved trips.
struct TripActionsSection: View {
    let itinerary: Itinerary
    let fromName: String
    let toName: String

    @Environment(TripStore.self) var trips
    @Environment(TicketStore.self) var tickets
    @Environment(PassStore.self) var passes
    @Environment(AppState.self) var app
    @State var calendarTrip: SavedTrip?
    @State var reminderOn = false
    @State var note: String?
    @State var addingTicket = false
    @State var showingTickets = false

    private var saved: SavedTrip? { trips.saved(itinerary) }
    /// Unsaved here, but kept until this screen closes.
    private var pending: Bool { saved.map { trips.isPendingRemoval($0.id) } ?? false }

    #if os(Android)

    var typeSize: DynamicTypeSize { AndroidEnvironment.dynamicTypeSize }

    #else

    @Environment(\.dynamicTypeSize) var typeSize

    #endif
    var body: some View {
        Section {
            VStack(spacing: 16) {
            if let saved, trips.activeTripId == saved.id {
                Button { Task { await stopTrip(saved) } } label: {
                    Label("End trip", systemImage: "stop.circle")
                }
                .accessibilityIdentifier("endTrip")
            } else {
                Button { Task { await startTrip() } } label: {
                    Text("Start trip")
                        .font(.headline)
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.ink)
                .controlSize(.large)
                .disabled(TripStatus.startBlocked(itinerary, now: .now) != nil)
                .accessibilityIdentifier("startTrip")
                // A grey button with no reason looked broken.
                if let reason = TripStatus.startBlocked(itinerary, now: .now) {
                    Text(reason).font(.footnote).foregroundStyle(.secondary)
                        .frame(maxWidth: .infinity, alignment: .center).multilineTextAlignment(.center)
                        .accessibilityIdentifier("startTripReason")
                }
            }
            Divider().frame(maxWidth: .infinity)
            // Not a LazyVGrid: a lazy grid inside a self-sizing list row is measured again on every pass.
            EqualColumns(columns: typeSize.isAccessibilitySize ? 1 : 2, spacing: 12) {
                secondaryButtons
            }
            .labelStyle(ActionLabelStyle())
            .buttonStyle(ActionButtonStyle())
            if let note {
                Text(note).font(.footnote).foregroundStyle(.secondary).frame(maxWidth: .infinity, alignment: .leading)
            }
            }
            .padding(.vertical, 4)
            .listRowSeparator(.hidden)
            .sheet(item: $calendarTrip) { trip in CalendarEventSheet(trip: trip).ignoresSafeArea() }
            // Here and not on TicketSection: on a Section it is copied onto every ticket row.
            .sheet(isPresented: $addingTicket) { AddTicketView(tripId: saved?.id) }
        } footer: {
            Text("The calendar entry links back to this trip with its live status.")
        }
        .task(id: saved?.id) {
            if let id = saved?.id { reminderOn = await LeaveReminder.isScheduled(id) }
        }

        if Features.tickets {
            TicketSection(itinerary: itinerary, tripId: saved?.id, onAddTicket: {
                _ = ensureSaved()
                addingTicket = true
            })
        }
        // Looking at a route shows no tickets; a saved or started trip does.
        if Features.passes, !itinerary.isDirect, saved != nil {
            TripPassSection(tripId: saved?.id, at: itinerary.startTime, ensureTrip: { ensureSaved().id })
        }
    }

    @ViewBuilder
    private var secondaryButtons: some View {
        Button {
            if let saved {
                if pending { trips.keep(saved.id) } else { trips.markForRemoval(saved.id) }
            } else {
                trips.save(itinerary, from: fromName, to: toName)
                SmartRouting.learn(chosen: itinerary)
            }
        } label: {
            let isSaved = saved != nil && !pending
            Label(isSaved ? L10n.t("Saved") : L10n.t("Save trip"), systemImage: isSaved ? "bookmark.fill" : "bookmark")
        }
        .buttonStyle(ActionButtonStyle())
        .accessibilityIdentifier("saveTrip")
        Button {
            calendarTrip = ensureSaved()
        } label: {
            Label("Add to Calendar", systemImage: "calendar.badge.plus")
        }
        .buttonStyle(ActionButtonStyle())
        // A link anyone can open (web page → Publit, or maps), with the route fetched live on their phone.
        // Before arrival with "I'm arriving at … at 18:42"; a notification follows if that moves later.
        let upcoming = itinerary.endTime > .now
        ShareLink(item: ShareLinks.web(.route(SharedRoute(itinerary, fromName: fromName, toName: toName))),
                  subject: Text(verbatim: "\(fromName) → \(toName)"),
                  message: Text(verbatim: upcoming ? ArrivalShare.message(for: itinerary, to: toName) : "\(fromName) → \(toName)")) {
            Label(upcoming ? L10n.t("Share arrival") : L10n.t("Share trip"), systemImage: "square.and.arrow.up")
        }
        .buttonStyle(ActionButtonStyle())
        .simultaneousGesture(TapGesture().onEnded {
            guard upcoming else { return }
            let trip = ensureSaved()
            ArrivalUpdates.recordShare(tripKey: trip.id, arrival: itinerary.endTime)
        })
        .accessibilityIdentifier("shareArrival")
        if itinerary.startTime > .now {
            Button {
                Task { await toggleReminder() }
            } label: {
                Label(reminderOn ? L10n.t("Reminder on") : L10n.t("Leave reminder"),
                      systemImage: reminderOn ? "bell.fill" : "bell")
            }
            .buttonStyle(ActionButtonStyle())
        }
    }

    private func startTrip() async {
        let trip = ensureSaved()
        app.open(trip.deepLink)
        note = await TripSession.start(trip, in: trips,
                                       hasTicket: !passes.passes(forTrip: trip.id, at: trip.startTime).isEmpty)
    }

    private func stopTrip(_ trip: SavedTrip) async {
        await TripSession.stop(trip, in: trips)
        reminderOn = false
        note = L10n.t("Trip ended")
    }

    private func ensureSaved() -> SavedTrip {
        if let saved {
            trips.keep(saved.id)
            return saved
        }
        return trips.save(itinerary, from: fromName, to: toName)
    }

    private func toggleReminder() async {
        let trip = ensureSaved()
        if reminderOn {
            LeaveReminder.cancel(trip.id)
            if trips.activeTripId != trip.id {
                trips.setFollowLive(trip.id, false)
                await LiveTripActivity.end(trip.id)
            }
            reminderOn = false
            note = nil
            return
        }
        switch await LeaveReminder.schedule(trip) {
        case .denied:
            note = L10n.t("Allow notifications for Publit in Settings.")
            return
        case .tooLate:
            note = L10n.t("This trip starts now.")
        case .scheduled(let date):
            note = L10n.f("We'll remind you at %@.", Fmt.time(date, tz: trip.itinerary.legs.first?.from.tz))
        }
        trips.setFollowLive(trip.id, true)
        reminderOn = true
        switch LiveTripActivity.start(trip, hasTicket: !passes.passes(forTrip: trip.id, at: trip.startTime).isEmpty) {
        case .started: note = [note, L10n.t("Follow it on the lock screen.")].compactMap { $0 }.joined(separator: " ")
        case .tooEarly: note = [note, L10n.t("Live updates on the lock screen start 8 hours before.")].compactMap { $0 }.joined(separator: " ")
        case .unavailable: note = [note, L10n.t("Turn on Live Activities for Publit in Settings to follow it on the lock screen.")].compactMap { $0 }.joined(separator: " ")
        case .failed: break
        }
    }
}

/// "Buy ticket" per operator and the user's own tickets.
struct TicketSection: View {
    let itinerary: Itinerary
    let tripId: String?
    let onAddTicket: () -> Void

    @Environment(TicketStore.self) var tickets

    var body: some View {
        let links = TicketLinks.links(for: itinerary, userLinked: tickets.linkedApps)
        let mine = tickets.tickets(forTrip: tripId)
        if !itinerary.isDirect {
            Section {
                ForEach(mine.prefix(2)) { ticket in
                    TicketCardButton(ticket: ticket)
                }
                ForEach(links) { l in
                    Link(destination: l.url) {
                        Label(l.title, systemImage: l.source == .userLinked ? "arrow.up.forward.app" : "cart")
                    }
                }
                Button(action: onAddTicket) {
                    Label("Add ticket for this trip", systemImage: "plus.rectangle.on.rectangle")
                }
            } header: {
                Text("Tickets")
            } footer: {
                if links.isEmpty {
                    Text("The operator publishes no ticket link. Link your ticket app in Settings → Ticket apps.")
                }
            }
        }
    }
}

/// One ticket row that owns its own full-screen presentation.
/// A presentation modifier on a `Group`/`Section` inside a `List` is copied onto every row,
/// so several covers competed for one binding and the ticket never opened reliably.
struct TicketCardButton: View {
    let ticket: Ticket
    @State var showing = false
    /// fullScreenCover does not inherit the presenter's Dynamic Type; re-apply so UI samples (and a11y sizes) reach the ticket.
    #if os(Android)
    var dynamicTypeSize: DynamicTypeSize { AndroidEnvironment.dynamicTypeSize }
    #else
    @Environment(\.dynamicTypeSize) var dynamicTypeSize
    #endif
    var body: some View {
        Button { showing = true } label: { TicketSummaryCard(ticket: ticket) }
            .buttonStyle(.plain)
            .fullScreenCover(isPresented: $showing) {
                TicketDisplayView(tickets: [ticket])
                    .dynamicTypeSize(dynamicTypeSize)
            }
    }
}

/// System "New Event" sheet, prefilled. Needs no calendar permission (iOS 17 write-only via EventKitUI).
struct CalendarEventSheet: UIViewControllerRepresentable {
    let trip: SavedTrip
    @Environment(\.dismiss) var dismiss

    func makeCoordinator() -> Coordinator { Coordinator(dismiss: { dismiss() }) }

    func makeUIViewController(context: Context) -> EKEventEditViewController {
        let store = EKEventStore()
        let event = EKEvent(eventStore: store)
        event.title = CalendarText.title(trip)
        event.startDate = trip.startTime
        event.endDate = trip.endTime
        event.location = CalendarText.location(trip)
        event.notes = CalendarText.notes(trip)
        event.url = CalendarText.url(trip)
        event.addAlarm(EKAlarm(absoluteDate: TripStatus.reminderTime(trip.itinerary)))
        let vc = EKEventEditViewController()
        vc.eventStore = store
        vc.event = event
        vc.editViewDelegate = context.coordinator
        return vc
    }

    func updateUIViewController(_ vc: EKEventEditViewController, context: Context) {}

    final class Coordinator: NSObject, EKEventEditViewDelegate {
        let dismiss: () -> Void
        init(dismiss: @escaping () -> Void) { self.dismiss = dismiss }
        func eventEditViewController(_ controller: EKEventEditViewController, didCompleteWith action: EKEventEditViewAction) {
            dismiss()
        }
    }
}

/// Equal-width columns; each row as tall as its tallest cell. Measures every cell once per pass.
struct EqualColumns: Layout {
    var columns: Int
    var spacing: CGFloat

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let cols = max(1, columns)
        let width: CGFloat
        if let w = proposal.width, w.isFinite {
            width = w
        } else {
            let widest = subviews.map { $0.sizeThatFits(.unspecified).width }.max() ?? 0
            width = widest * CGFloat(cols) + spacing * CGFloat(cols - 1)
        }
        let heights = rowHeights(subviews, cellWidth: cellWidth(width, cols))
        return CGSize(width: width, height: heights.reduce(0, +) + spacing * CGFloat(max(0, heights.count - 1)))
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        let cols = max(1, columns)
        let cell = cellWidth(bounds.width, cols)
        let heights = rowHeights(subviews, cellWidth: cell)
        var y = bounds.minY
        for (row, height) in heights.enumerated() {
            for col in 0..<cols {
                let i = row * cols + col
                guard i < subviews.count else { break }
                let x = bounds.minX + CGFloat(col) * (cell + spacing)
                subviews[i].place(at: CGPoint(x: x, y: y), proposal: ProposedViewSize(width: cell, height: height))
            }
            y += height + spacing
        }
    }

    private func cellWidth(_ width: CGFloat, _ cols: Int) -> CGFloat {
        max(0, (width - spacing * CGFloat(cols - 1)) / CGFloat(cols))
    }

    private func rowHeights(_ subviews: Subviews, cellWidth: CGFloat) -> [CGFloat] {
        let cols = max(1, columns)
        return stride(from: 0, to: subviews.count, by: cols).map { start in
            subviews[start..<min(start + cols, subviews.count)]
                .map { $0.sizeThatFits(ProposedViewSize(width: cellWidth, height: nil)).height }
                .max() ?? 0
        }
    }
}

/// Icon above a short title, filling an equal share of the row.
struct TileLabelStyle: LabelStyle {
    func makeBody(configuration: Configuration) -> some View {
        VStack(spacing: 6) {
            configuration.icon.font(.title3)
            configuration.title.font(.caption.weight(.semibold))
                .multilineTextAlignment(.center)
                .lineLimit(2).minimumScaleFactor(0.85)
                .fixedSize(horizontal: false, vertical: true)
        }
        .frame(maxWidth: .infinity, minHeight: 58)
    }
}

/// Fixed padding and unlimited wrapping, independent of iOS's automatic capsule button shapes.
struct ActionLabelStyle: LabelStyle {
    func makeBody(configuration: Configuration) -> some View {
        VStack(spacing: 8) {
            configuration.icon.font(.title3.weight(.semibold))
            configuration.title.font(.subheadline.weight(.semibold))
                .multilineTextAlignment(.center).fixedSize(horizontal: false, vertical: true)
        }
        .frame(maxWidth: .infinity, minHeight: 64, alignment: .center)
    }
}

struct ActionButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label.padding(12).frame(maxWidth: .infinity)
            .foregroundStyle(Color.ink)
            .background(Color.ink.opacity(configuration.isPressed ? 0.2 : 0.1), in: RoundedRectangle(cornerRadius: 14))
            .contentShape(RoundedRectangle(cornerRadius: 14))
    }
}

extension TripStore {
    /// Deletes trips unsaved on a screen that has closed, with their reminder and Live Activity.
    func discardPendingRemovals() {
        for id in removePending() {
            LeaveReminder.cancel(id)
            Task { await LiveTripActivity.end(id) }
        }
    }

    /// The same once the back animation has finished: removing a trip mid-transition changes
    /// the list being revealed (Saved, Plan) while UIKit is laying it out.
    func discardPendingRemovalsLater() {
        guard !pendingRemoval.isEmpty else { return }
        Task { @MainActor in
            try? await Task.sleep(for: .milliseconds(600))
            discardPendingRemovals()
        }
    }
}
