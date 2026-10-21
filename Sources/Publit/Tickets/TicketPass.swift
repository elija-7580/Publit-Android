import Foundation

/// A ticket kept in Publit as a pass, modelled on Wallet passes: the details the user checked,
/// an optional static code and the original document (a PDF page or an image).
/// Stored on the device only. It is a copy for quick access, not an operator-issued ticket.
struct TicketPass: Codable, Identifiable, Hashable, Sendable {
    enum Category: String, Codable, CaseIterable, Sendable {
        case single, returnTrip, dayPass, period, subscription, reservation, other

        var title: String {
            switch self {
            case .single: return L10n.t("Single ticket")
            case .returnTrip: return L10n.t("Return ticket")
            case .dayPass: return L10n.t("Day ticket")
            case .period: return L10n.t("Weekly or monthly pass")
            case .subscription: return L10n.t("Annual pass or subscription")
            case .reservation: return L10n.t("Seat reservation")
            case .other: return L10n.t("Other ticket")
            }
        }

        /// Valid for many trips, so it is offered for every trip while it is valid.
        var isPass: Bool { self == .dayPass || self == .period || self == .subscription }
    }

    enum Symbol: String, Codable, CaseIterable, Sendable {
        case train, tram, metro, bus, ferry, ticket

        var systemImage: String {
            switch self {
            case .train: return "train.side.front.car"
            case .tram: return "tram.fill"
            case .metro: return "tram.fill.tunnel"
            case .bus: return "bus.fill"
            case .ferry: return "ferry.fill"
            case .ticket: return "ticket.fill"
            }
        }

        var title: String {
            switch self {
            case .train: return L10n.t("Train")
            case .tram: return L10n.t("Tram")
            case .metro: return L10n.t("Metro")
            case .bus: return L10n.t("Bus")
            case .ferry: return L10n.t("Ferry")
            case .ticket: return L10n.t("Ticket")
            }
        }
    }

    /// A code that does not change. Codes that rotate every few seconds only work in the operator's app.
    struct Barcode: Codable, Hashable, Sendable {
        enum Format: String, Codable, CaseIterable, Sendable {
            case qr, aztec, pdf417, code128, dataMatrix
            /// Linear retail and logistics codes, found on some paper and bus tickets.
            case ean13, ean8, upce, code39, code93, itf, codabar

            /// Wallet shows only these four; other codes stay in Publit.
            var walletFormat: String? {
                switch self {
                case .qr: return "PKBarcodeFormatQR"
                case .aztec: return "PKBarcodeFormatAztec"
                case .pdf417: return "PKBarcodeFormatPDF417"
                case .code128: return "PKBarcodeFormatCode128"
                default: return nil
                }
            }

            /// Product names, the same in every language.
            var title: String {
                switch self {
                case .qr: return "QR"
                case .aztec: return "Aztec"
                case .pdf417: return "PDF417"
                case .code128: return "Code 128"
                case .dataMatrix: return "Data Matrix"
                case .ean13: return "EAN-13"
                case .ean8: return "EAN-8"
                case .upce: return "UPC-E"
                case .code39: return "Code 39"
                case .code93: return "Code 93"
                case .itf: return "ITF"
                case .codabar: return "Codabar"
                }
            }

            var isSquare: Bool { self == .qr || self == .aztec || self == .dataMatrix }
            /// Bars in one row (all but the 2D codes).
            var isLinear: Bool { !isSquare && self != .pdf417 }
            /// Publit can draw these again from their content; the others are shown as cut out of the original.
            var canRedraw: Bool { self == .qr || self == .aztec || self == .pdf417 || self == .code128 }
        }

        /// Where the code sits on the original page, as fractions of its width and height (origin top left).
        /// Codes Publit cannot draw again are shown cut out of the original, exactly as issued.
        struct Region: Codable, Hashable, Sendable {
            var x, y, width, height: Double

            /// With a margin for the quiet zone scanners need around the code, kept inside the page.
            func padded(_ fraction: Double = 0.12) -> Region {
                let dx = max(width * fraction, 0.01), dy = max(height * fraction, 0.01)
                let x0 = max(0, x - dx), y0 = max(0, y - dy)
                let x1 = min(1, x + width + dx), y1 = min(1, y + height + dy)
                return Region(x: x0, y: y0, width: x1 - x0, height: y1 - y0)
            }
        }

        var format: Format
        /// Text content, when the code holds text.
        var message: String?
        /// Raw bytes when the text does not represent them exactly (binary codes such as UIC 918.3 rail tickets).
        var payload: Data?
        /// Archived `CIBarcodeDescriptor`: redraws exactly the scanned symbol, binary content included.
        var descriptor: Data?
        var altText: String?
        var region: Region?

        init(format: Format, message: String? = nil, payload: Data? = nil, descriptor: Data? = nil, altText: String? = nil,
             region: Region? = nil) {
            self.format = format
            self.message = message
            self.payload = payload
            self.descriptor = descriptor
            self.altText = altText
            self.region = region
        }

        /// Can go into Apple Wallet: a format Wallet draws, with content it can carry.
        var walletReady: Bool { format.walletFormat != nil && walletMessage != nil }

        /// The bytes to encode when the code is drawn again.
        var bytes: Data? { payload ?? message.map { Data($0.utf8) } }

        /// Wallet takes the message as text. ISO-8859-1 maps each byte to one character, so binary content survives.
        var walletMessage: String? {
            if let payload {
                var scalars = String.UnicodeScalarView()
                scalars.append(contentsOf: payload.map { Unicode.Scalar($0) })
                return String(scalars)
            }
            return message
        }

        var walletEncoding: String { payload != nil ? "iso-8859-1" : "utf-8" }

        /// Static rail ticket code after UIC 918.3 (header `#UT`), issued by many European railways.
        var isUIC: Bool {
            guard let bytes else { return false }
            return Array(bytes.prefix(3)) == Array("#UT".utf8)
        }

        /// Four-digit UIC company code of the issuer in a UIC 918.3 header, e.g. 1181 for ÖBB.
        var uicIssuerCode: String? {
            guard isUIC, let bytes, bytes.count >= 9 else { return nil }
            let digits = Array(bytes)[5..<9]
            guard digits.allSatisfy({ $0 >= 0x30 && $0 <= 0x39 }) else { return nil }
            return String(decoding: digits, as: UTF8.self)
        }

        /// Identity for removing duplicates found on the same page.
        var dedupeKey: String {
            "\(format.rawValue)|" + (message ?? payload?.base64EncodedString() ?? descriptor?.base64EncodedString() ?? "")
        }
    }

    struct Logo: Codable, Hashable, Sendable {
        var file: String
        /// Where it came from (Wikimedia Commons file page or website), shown as the credit.
        var source: String?
    }

    struct Document: Codable, Hashable, Sendable {
        enum Kind: String, Codable, Sendable { case pdf, image }
        var file: String
        var kind: Kind
        /// PDF page with the ticket (0-based).
        var page: Int
        /// Exact original + page, for repeated share imports. Optional for older saved tickets.
        var fingerprint: String? = nil
    }

    enum Validity: Equatable, Sendable {
        case unknown
        case upcoming(Date)
        case valid(until: Date?)
        case expired(Date)
    }

    let id: String
    var title: String
    var operatorName: String?
    var category: Category
    var symbol: Symbol
    var origin: String?
    var destination: String?
    var via: String?
    var passenger: String?
    /// "1" or "2".
    var travelClass: String?
    /// As printed on the ticket, e.g. "€ 19,90".
    var price: String?
    /// Ticket or booking number.
    var reference: String?
    var validFrom: Date?
    var validUntil: Date?
    var barcode: Barcode?
    var document: Document?
    var style: PassStyle
    var notes: String?
    /// The saved trip it was added for, if any.
    var tripId: String?
    /// The old `Ticket` this pass was imported from.
    var legacyId: String?
    /// Operator logo (PNG file next to the documents), chosen by the user or suggested from Wikidata.
    var logo: Logo? = nil
    /// Set when the user moved the ticket to the archive by hand.
    var archivedAt: Date? = nil
    /// The user took an expired ticket back out of the archive: it stays in the list.
    var keptOut: Bool? = nil
    let createdAt: Date
    var updatedAt: Date

    init(id: String = UUID().uuidString, title: String = "", operatorName: String? = nil, category: Category = .single,
         symbol: Symbol = .ticket, origin: String? = nil, destination: String? = nil, via: String? = nil,
         passenger: String? = nil, travelClass: String? = nil, price: String? = nil, reference: String? = nil,
         validFrom: Date? = nil, validUntil: Date? = nil, barcode: Barcode? = nil, document: Document? = nil,
         style: PassStyle = .standard, notes: String? = nil, tripId: String? = nil, legacyId: String? = nil,
         createdAt: Date = .now, updatedAt: Date? = nil) {
        self.id = id
        self.title = title
        self.operatorName = operatorName
        self.category = category
        self.symbol = symbol
        self.origin = origin
        self.destination = destination
        self.via = via
        self.passenger = passenger
        self.travelClass = travelClass
        self.price = price
        self.reference = reference
        self.validFrom = validFrom
        self.validUntil = validUntil
        self.barcode = barcode
        self.document = document
        self.style = style
        self.notes = notes
        self.tripId = tripId
        self.legacyId = legacyId
        self.createdAt = createdAt
        self.updatedAt = updatedAt ?? createdAt
    }

    func validity(at date: Date = .now) -> Validity {
        if let validUntil, validUntil < date { return .expired(validUntil) }
        if let validFrom, validFrom > date { return .upcoming(validFrom) }
        if validFrom == nil && validUntil == nil { return .unknown }
        return .valid(until: validUntil)
    }

    /// Expired tickets stay in the list for a day (a delayed trip, a late check), then move to the archive.
    static let archiveDelay: TimeInterval = 24 * 3600

    func isArchived(at date: Date = .now) -> Bool {
        if archivedAt != nil { return true }
        if keptOut == true { return false }
        if case .expired(let end) = validity(at: date) { return date.timeIntervalSince(end) >= Self.archiveDelay }
        return false
    }

    /// For ordering and grouping the archive: when the ticket ended, else when it was archived or added.
    var archiveDate: Date { validUntil ?? validFrom ?? archivedAt ?? createdAt }

    var hasRoute: Bool { origin?.isEmpty == false && destination?.isEmpty == false }

    var displayTitle: String {
        let t = title.trimmingCharacters(in: .whitespacesAndNewlines)
        if !t.isEmpty { return t }
        return operatorName?.nilIfEmpty ?? category.title
    }

    var travelClassText: String? { travelClass.map { L10n.f("Class %@", $0) } }

    /// One sentence for VoiceOver.
    var accessibilitySummary: String {
        var parts = [displayTitle]
        if let operatorName { parts.append(operatorName) }
        if hasRoute, let origin, let destination { parts.append(L10n.f("from %1$@ to %2$@", origin, destination)) }
        switch validity() {
        case .valid(let until): parts.append(until.map { L10n.f("Valid until %@", PassFormat.date($0, end: true)) } ?? L10n.t("Valid now"))
        case .upcoming(let from): parts.append(L10n.f("Valid from %@", PassFormat.date(from, end: false)))
        case .expired: parts.append(L10n.t("Expired"))
        case .unknown: break
        }
        return parts.joined(separator: ", ")
    }
}

/// The list order: valid now (ending soonest first), upcoming, without dates, expired (latest first).
/// Archived tickets are not in these groups.
enum PassGroup: String, CaseIterable, Identifiable, Sendable {
    case valid, upcoming, undated, expired
    var id: String { rawValue }

    var title: String {
        switch self {
        case .valid: return L10n.t("Valid now")
        case .upcoming: return L10n.t("Upcoming")
        case .undated: return L10n.t("No dates")
        case .expired: return L10n.t("Expired")
        }
    }

    static func of(_ pass: TicketPass, at date: Date) -> PassGroup {
        switch pass.validity(at: date) {
        case .valid: return .valid
        case .upcoming: return .upcoming
        case .unknown: return .undated
        case .expired: return .expired
        }
    }

    static func grouped(_ passes: [TicketPass], at date: Date) -> [(group: PassGroup, passes: [TicketPass])] {
        let far = Date.distantFuture
        return allCases.compactMap { group in
            let members = passes.filter { !$0.isArchived(at: date) && of($0, at: date) == group }.sorted { a, b in
                switch group {
                case .valid: return (a.validUntil ?? far, a.createdAt) < (b.validUntil ?? far, b.createdAt)
                case .upcoming: return (a.validFrom ?? far) < (b.validFrom ?? far)
                case .undated: return a.createdAt > b.createdAt
                case .expired: return (a.validUntil ?? .distantPast) > (b.validUntil ?? .distantPast)
                }
            }
            return members.isEmpty ? nil : (group, members)
        }
    }
}

enum PassArchive {
    /// Newest first, in months ("October 2025").
    static func months(_ passes: [TicketPass], calendar: Calendar = .current) -> [(month: Date, passes: [TicketPass])] {
        let sorted = passes.sorted { ($0.archiveDate, $0.createdAt) > ($1.archiveDate, $1.createdAt) }
        var out: [(month: Date, passes: [TicketPass])] = []
        for pass in sorted {
            let start = calendar.dateInterval(of: .month, for: pass.archiveDate)?.start ?? pass.archiveDate
            if out.last?.month == start { out[out.count - 1].passes.append(pass) } else { out.append((start, [pass])) }
        }
        return out
    }

    /// Search across what a ticket shows: title, operator, route, passenger, number, notes.
    static func matches(_ pass: TicketPass, _ query: String) -> Bool {
        let words = query.split(whereSeparator: \.isWhitespace).map(String.init)
        guard !words.isEmpty else { return true }
        let haystack = [pass.displayTitle, pass.operatorName, pass.origin, pass.destination, pass.via, pass.passenger,
                        pass.reference, pass.notes, pass.category.title].compactMap { $0 }.joined(separator: " ")
        return words.allSatisfy { haystack.range(of: $0, options: [.caseInsensitive, .diacriticInsensitive]) != nil }
    }
}

enum PassFormat {
    /// Dates without a time of day (midnight or 23:59:59 at the end) are shown as dates only.
    static func date(_ date: Date, end: Bool, calendar: Calendar = .current) -> String {
        let c = calendar.dateComponents([.hour, .minute, .second], from: date)
        let midnight = c.hour == 0 && c.minute == 0
        let endOfDay = c.hour == 23 && c.minute == 59
        if (end && endOfDay) || (!end && midnight) {
            return date.formatted(date: .abbreviated, time: .omitted)
        }
        return date.formatted(date: .abbreviated, time: .shortened)
    }
}
