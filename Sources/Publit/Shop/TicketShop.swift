// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2026 Elija Samuel Friedrich-Ulrich

import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

/// Buying tickets inside Publit through the Publit ticket shop (services/ticket-shop).
/// The shop holds the sellers' API keys and the payment key; the app only sends the journey and gets back
/// offers, a payment page and finally the tickets. Off until `PublitTicketShopURL` is set in Info.plist.
enum TicketShop {
    static var baseURL: URL? {
        #if os(Android)
        // AndroidManifest meta-data publit.ticketShopUrl (Gradle property publit.ticketShopUrl).
        return validURL(Droid.string("ticketShop")?.trimmingCharacters(in: .whitespaces))
        #else
        let raw = (Bundle.main.object(forInfoDictionaryKey: "PublitTicketShopURL") as? String)?
            .trimmingCharacters(in: .whitespaces)
        return validURL(raw)
        #endif
    }

    static var isAvailable: Bool { baseURL != nil }

    static func validURL(_ raw: String?) -> URL? {
        guard let raw, !raw.isEmpty, !raw.hasPrefix("$("), let url = URL(string: raw),
              url.scheme == "https", url.host?.isEmpty == false else { return nil }
        return url
    }

    /// A leg that can have a ticket, with its position in the itinerary.
    struct SellableLeg: Identifiable {
        let index: Int
        let leg: Leg
        var id: Int { index }
    }

    /// Legs that can have a ticket: everything except walking, cycling and driving.
    static func sellableLegs(_ itinerary: Itinerary) -> [SellableLeg] {
        itinerary.legs.enumerated().filter { $0.element.isTransit }.map { SellableLeg(index: $0.offset, leg: $0.element) }
    }

    static func request(for itinerary: Itinerary, adults: Int, currency: String = "EUR",
                        locale: String = Locale.current.language.languageCode?.identifier ?? "en") -> OfferRequest {
        OfferRequest(
            legs: sellableLegs(itinerary).map { item in
                let leg = item.leg
                return OfferRequest.Leg(
                    index: item.index, mode: leg.mode, line: leg.routeShortName ?? leg.displayName,
                    operator: leg.agencyName,
                    from: .init(name: leg.from.name, lat: leg.from.lat, lon: leg.from.lon, tz: leg.from.tz, stopId: leg.from.stopId),
                    to: .init(name: leg.to.name, lat: leg.to.lat, lon: leg.to.lon, tz: leg.to.tz, stopId: leg.to.stopId),
                    departure: leg.startTime, arrival: leg.endTime)
            },
            adults: adults, currency: currency, locale: String(locale.prefix(2)).lowercased())
    }

    static func price(_ amount: Int, currency: String) -> String {
        FareEstimate.format(Decimal(amount) / 100, currency: currency)
    }
}

// MARK: - Wire format (see services/ticket-shop/src/index.js)

struct OfferRequest: Encodable, Sendable {
    struct Place: Encodable, Sendable {
        let name: String
        let lat: Double
        let lon: Double
        let tz: String?
        let stopId: String?
    }
    struct Leg: Encodable, Sendable {
        let index: Int
        let mode: String
        let line: String?
        let `operator`: String?
        let from: Place
        let to: Place
        let departure: Date
        let arrival: Date
    }
    let legs: [Leg]
    let adults: Int
    let currency: String
    let locale: String
}

struct ShopOffer: Decodable, Hashable, Sendable {
    let leg: Int
    let provider: String
    let providerName: String
    let title: String
    let `operator`: String?
    /// Minor units (cents).
    let amount: Int
    let currency: String
    let refundable: Bool?
    let token: String
}

struct OfferResponse: Decodable, Sendable {
    let offers: [ShopOffer]
}

struct ShopCustomer: Codable, Hashable, Sendable {
    var firstName: String
    var lastName: String
    var email: String

    var isComplete: Bool {
        !firstName.trimmingCharacters(in: .whitespaces).isEmpty && !lastName.trimmingCharacters(in: .whitespaces).isEmpty
            && email.contains("@") && email.contains(".")
    }
}

struct CheckoutRequest: Encodable, Sendable {
    let offers: [String]
    let customer: ShopCustomer
    let termsAccepted: Bool
}

struct CheckoutResponse: Decodable, Sendable {
    let order: String
    let paymentURL: URL
    let amount: Int
    let currency: String
}

struct ShopTicket: Decodable, Hashable, Sendable {
    struct Code: Decodable, Hashable, Sendable {
        let format: String
        let message: String
    }
    let title: String
    let `operator`: String?
    let origin: String?
    let destination: String?
    let validFrom: Date?
    let validUntil: Date?
    let passenger: String?
    let reference: String?
    let barcode: Code?
    /// The seller's ticket as PDF, base64.
    let pdf: String?
}

struct OrderResult: Decodable, Sendable {
    struct Item: Decodable, Sendable {
        /// "booked", "pending" (paid, ticket not ready yet) or "failed" (not charged).
        let status: String
        let leg: Int
        let amount: Int
        let tickets: [ShopTicket]
    }
    /// "booked", "partial" or "failed".
    let status: String
    let amountCharged: Int
    let currency: String
    let items: [Item]
}

struct ShopFailure: LocalizedError, Decodable, Sendable {
    let error: String
    let message: String
    var errorDescription: String? {
        switch error {
        case "expired": return L10n.t("The prices have expired. Please check them again.")
        case "unavailable": return L10n.t("A ticket is no longer available. Please check the prices again.")
        case "unpaid": return L10n.t("The payment is not complete yet.")
        case "cancelled": return L10n.t("The order was cancelled. You were not charged.")
        default: return L10n.t("Tickets cannot be bought right now. Please try again later.")
        }
    }
}

// MARK: - Client

struct TicketShopClient: Sendable {
    let baseURL: URL
    var session: URLSession = .shared

    static func makeDecoder() -> JSONDecoder {
        let d = JSONDecoder()
        d.dateDecodingStrategy = .custom { decoder in
            let text = try decoder.singleValueContainer().decode(String.self)
            if let date = Self.isoFraction.date(from: text) ?? Self.iso.date(from: text) { return date }
            throw DecodingError.dataCorrupted(.init(codingPath: decoder.codingPath, debugDescription: "Date \(text)"))
        }
        return d
    }

    static func makeEncoder() -> JSONEncoder {
        let e = JSONEncoder()
        e.dateEncodingStrategy = .custom { date, encoder in
            var c = encoder.singleValueContainer()
            try c.encode(Self.iso.string(from: date))
        }
        return e
    }

    private static var iso: ISO8601DateFormatter { ISO8601DateFormatter() }
    private static var isoFraction: ISO8601DateFormatter {
        let f = ISO8601DateFormatter()
        f.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return f
    }

    func offers(_ request: OfferRequest) async throws -> [ShopOffer] {
        try await post("v1/offers", request, as: OfferResponse.self).offers
    }

    func checkout(_ request: CheckoutRequest) async throws -> CheckoutResponse {
        try await post("v1/checkout", request, as: CheckoutResponse.self)
    }

    func complete(order: String) async throws -> OrderResult {
        let path = "v1/orders/\(order.addingPercentEncoding(withAllowedCharacters: .alphanumerics) ?? "")/complete"
        return try await post(path, Optional<String>.none, as: OrderResult.self)
    }

    private func post<Body: Encodable, Result: Decodable>(_ path: String, _ body: Body, as: Result.Type) async throws -> Result {
        var request = URLRequest(url: baseURL.appendingPathComponent(path))
        request.httpMethod = "POST"
        request.timeoutInterval = 60
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue(AppInfo.userAgent, forHTTPHeaderField: "User-Agent")
        request.httpBody = try Self.makeEncoder().encode(body)
        let (data, response) = try await session.data(for: request)
        let status = (response as? HTTPURLResponse)?.statusCode ?? 0
        guard (200..<300).contains(status) else {
            throw (try? Self.makeDecoder().decode(ShopFailure.self, from: data))
                ?? ShopFailure(error: "http", message: "HTTP \(status)")
        }
        return try Self.makeDecoder().decode(Result.self, from: data)
    }
}

// MARK: - Into the wallet

extension ShopTicket {
    func pass(symbol: TicketPass.Symbol, price: String?, tripId: String?) -> TicketPass {
        TicketPass(
            title: title, operatorName: `operator`, category: .single, symbol: symbol,
            origin: origin, destination: destination, passenger: passenger, price: price, reference: reference,
            validFrom: validFrom, validUntil: validUntil,
            barcode: barcode.flatMap { code in
                TicketPass.Barcode.Format(shopName: code.format).map { TicketPass.Barcode(format: $0, message: code.message) }
            },
            notes: L10n.t("Bought in Publit."), tripId: tripId)
    }

    var pdfData: Data? { pdf.flatMap { Data(base64Encoded: $0) } }
}

extension TicketPass.Barcode.Format {
    init?(shopName: String) {
        switch shopName.lowercased() {
        case "qr": self = .qr
        case "aztec": self = .aztec
        case "pdf417": self = .pdf417
        case "code128": self = .code128
        case "datamatrix", "data_matrix": self = .dataMatrix
        default: return nil
        }
    }
}

extension TicketPass.Symbol {
    init(mode: String) {
        switch mode {
        case "BUS", "COACH", "FLEX": self = .bus
        case "TRAM": self = .tram
        case "SUBWAY", "METRO": self = .metro
        case "FERRY": self = .ferry
        case "RAIL", "REGIONAL_RAIL", "REGIONAL_FAST_RAIL", "LONG_DISTANCE", "HIGHSPEED_RAIL", "NIGHT_RAIL", "SUBURBAN": self = .train
        default: self = .ticket
        }
    }
}
