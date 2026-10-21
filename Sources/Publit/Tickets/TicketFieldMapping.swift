import Foundation

/// Only explicit user assignments, never inference. Kept separate from Vision for unit tests.
enum TicketField: String, CaseIterable, Identifiable, Sendable {
    case title, operatorName, origin, destination, via, passenger, price, reference, validFrom, validUntil, notes

    var id: String { rawValue }

    var title: String {
        switch self {
        case .title: return L10n.t("Title")
        case .operatorName: return L10n.t("Operator")
        case .origin: return L10n.t("From")
        case .destination: return L10n.t("To")
        case .via: return L10n.t("Via")
        case .passenger: return L10n.t("Passenger")
        case .price: return L10n.t("Price")
        case .reference: return L10n.t("Ticket number")
        case .validFrom: return L10n.t("Valid from")
        case .validUntil: return L10n.t("Valid until")
        case .notes: return L10n.t("Notes")
        }
    }

    /// False leaves the pass unchanged, e.g. when an assigned date cannot be read.
    @discardableResult
    func apply(_ text: String, to pass: inout TicketPass, calendar: Calendar = .current) -> Bool {
        let value = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !value.isEmpty else { return false }
        switch self {
        case .title: pass.title = value
        case .operatorName: pass.operatorName = value
        case .origin: pass.origin = value
        case .destination: pass.destination = value
        case .via: pass.via = value
        case .passenger: pass.passenger = value
        case .price: pass.price = value
        case .reference: pass.reference = value
        case .notes: pass.notes = value
        case .validFrom, .validUntil:
            guard let date = TicketTextParser.date(in: value, end: self == .validUntil, calendar: calendar) else { return false }
            if self == .validFrom { pass.validFrom = date } else { pass.validUntil = date }
        }
        return true
    }
}
