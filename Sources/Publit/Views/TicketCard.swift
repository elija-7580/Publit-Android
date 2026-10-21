import SwiftUI
#if canImport(UIKit)
import UIKit
#endif

/// A stored ticket is an attachment, not a claim that an operator-issued ticket is valid.
struct TicketSummaryCard: View {
    let ticket: Ticket
    @Environment(TripStore.self) var trips

    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            Image(systemName: "ticket.fill")
                .font(.title2).foregroundStyle(Color.ink)
                .frame(width: 44, height: 44)
                .background(Color.ink.opacity(0.10), in: RoundedRectangle(cornerRadius: 10))
            VStack(alignment: .leading, spacing: 6) {
                Text(verbatim: ticket.name).font(.headline).foregroundStyle(.primary)
                    .fixedSize(horizontal: false, vertical: true)
                Text(ticket.kind == .pass ? L10n.t("Pass") : L10n.t("Single trip"))
                    .font(.caption).foregroundStyle(.secondary)
                if let tripId = ticket.tripId, let trip = trips.trip(tripId) {
                    Text(verbatim: trip.title).font(.caption).foregroundStyle(.secondary)
                        .fixedSize(horizontal: false, vertical: true)
                }
                if let until = ticket.validUntil {
                    Text(ticket.isValid() ? L10n.f("Valid until %@", until.formatted(date: .abbreviated, time: .omitted)) : L10n.t("Expired"))
                        .font(.caption).foregroundStyle(ticket.isValid() ? Color.secondary : Color.danger)
                }
                Label(ticket.imageFile != nil ? L10n.t("Stored copy") : L10n.t("Open ticket app"),
                      systemImage: ticket.imageFile != nil ? "doc" : "arrow.up.forward.app")
                    .font(.caption).foregroundStyle(.secondary)
            }
            Spacer(minLength: 0)
        }
        .padding(14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color.ink.opacity(0.05), in: RoundedRectangle(cornerRadius: 14))
        .overlay(RoundedRectangle(cornerRadius: 14).stroke(Color.ink.opacity(0.16), lineWidth: 1))
        .contentShape(Rectangle())
        .accessibilityElement(children: .combine)
        .accessibilityIdentifier("ticketCard")
    }
}
