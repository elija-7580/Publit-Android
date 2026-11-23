// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2026 Elija Samuel Friedrich-Ulrich

import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif
import Observation

/// One purchase for a whole route: an offer per leg where Publit can sell it, one payment for all of them,
/// and the tickets saved to the Tickets tab. Legs without an offer keep their operator's own ticket link.
@MainActor
@Observable
final class CheckoutModel {
    enum Phase: Equatable {
        case loading
        case ready
        case paying
        case booking
        case done(saved: Int, failedLegs: Int, pendingLegs: Int)
        case failed(String)
    }

    let itinerary: Itinerary
    private let client: TicketShopClient
    private(set) var phase: Phase = .loading
    private(set) var offers: [Int: ShopOffer] = [:]
    /// The paid order, kept so the tickets can be fetched again if the app was interrupted.
    private(set) var order: String?
    var adults = 1
    var customer = ShopCustomer(firstName: CheckoutModel.stored("shopFirstName"), lastName: CheckoutModel.stored("shopLastName"),
                                email: CheckoutModel.stored("shopEmail"))
    var termsAccepted = false

    init(itinerary: Itinerary, client: TicketShopClient) {
        self.itinerary = itinerary
        self.client = client
    }

    var legs: [TicketShop.SellableLeg] { TicketShop.sellableLegs(itinerary) }
    var selectedOffers: [ShopOffer] { legs.compactMap { offers[$0.index] } }
    var total: Int { selectedOffers.reduce(0) { $0 + $1.amount } }
    var currency: String { selectedOffers.first?.currency ?? "EUR" }
    var coversWholeTrip: Bool { !legs.isEmpty && selectedOffers.count == legs.count }
    var canPay: Bool { phase == .ready && !selectedOffers.isEmpty && customer.isComplete && termsAccepted }

    func loadOffers() async {
        phase = .loading
        do {
            let found = try await client.offers(TicketShop.request(for: itinerary, adults: adults))
            offers = Dictionary(found.map { ($0.leg, $0) }, uniquingKeysWith: { first, _ in first })
            phase = .ready
        } catch {
            offers = [:]
            phase = .failed(Self.message(error))
        }
    }

    /// Reserves the tickets and returns the payment page to open. Remembers the contact details on this device only.
    func startPayment() async -> URL? {
        guard canPay else { return nil }
        Self.store(customer)
        phase = .paying
        do {
            let response = try await client.checkout(CheckoutRequest(offers: selectedOffers.map(\.token), customer: customer,
                                                                     termsAccepted: true))
            order = response.order
            return response.paymentURL
        } catch {
            phase = .failed(Self.message(error))
            return nil
        }
    }

    /// The payment page sent the user back (`publit://shop/return?order=…` or `?cancelled=1`).
    func paymentReturned(_ callback: URL?) -> Bool {
        let items = URLComponents(url: callback ?? URL(fileURLWithPath: "/"), resolvingAgainstBaseURL: false)?.queryItems ?? []
        if let returned = items.first(where: { $0.name == "order" })?.value, returned == order { return true }
        phase = .ready
        return false
    }

    /// Books the paid order and saves its tickets. Safe to repeat: nothing is charged twice.
    func finish(saveTo store: PassStore, tripId: String?) async {
        guard let order else { return }
        phase = .booking
        do {
            let result = try await client.complete(order: order)
            var saved = 0
            for item in result.items where item.status == "booked" {
                let leg = itinerary.legs.indices.contains(item.leg) ? itinerary.legs[item.leg] : nil
                let symbol = TicketPass.Symbol(mode: leg?.mode ?? "")
                let price = TicketShop.price(item.amount, currency: result.currency.isEmpty ? currency : result.currency)
                for ticket in item.tickets {
                    let pass = ticket.pass(symbol: symbol, price: price, tripId: tripId)
                    if let pdf = ticket.pdfData {
                        _ = try? await store.add(pass, document: pdf, kind: .pdf, deduplicate: true)
                    } else {
                        store.insert(pass)
                    }
                    saved += 1
                }
            }
            phase = .done(saved: saved, failedLegs: result.items.filter { $0.status == "failed" }.count,
                          pendingLegs: result.items.filter { $0.status == "pending" }.count)
        } catch {
            // Not paid (the payment page was closed): start again from the prices instead of retrying this order.
            if let failure = error as? ShopFailure, failure.error == "unpaid" || failure.error == "cancelled" { order = nil }
            phase = .failed(Self.message(error))
        }
    }

    static func message(_ error: Error) -> String {
        if let failure = error as? ShopFailure { return failure.errorDescription ?? failure.message }
        if (error as? URLError) != nil { return L10n.t("No connection to the ticket shop. Check your internet connection.") }
        return L10n.t("Tickets cannot be bought right now. Please try again later.")
    }

    private static func stored(_ key: String) -> String { UserDefaults.standard.string(forKey: key) ?? "" }

    private static func store(_ customer: ShopCustomer) {
        UserDefaults.standard.set(customer.firstName.trimmingCharacters(in: .whitespaces), forKey: "shopFirstName")
        UserDefaults.standard.set(customer.lastName.trimmingCharacters(in: .whitespaces), forKey: "shopLastName")
        UserDefaults.standard.set(customer.email.trimmingCharacters(in: .whitespaces), forKey: "shopEmail")
    }
}
