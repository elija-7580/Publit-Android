// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2026 Elija Samuel Friedrich-Ulrich

#if !os(Android)
import AuthenticationServices
#endif
import SwiftUI

/// "Buy tickets" for a route: prices per leg, travellers, contact, one payment, tickets into the Tickets tab.
struct CheckoutView: View {
    @State var model: CheckoutModel
    let tripId: () -> String?
    @Environment(PassStore.self) var passes
    @Environment(TicketStore.self) var tickets
    @Environment(\.dismiss) var dismiss
    #if os(Android)
    @Environment(\.scenePhase) var scenePhase
    /// The payment page is open in the browser; coming back to the app books the order.
    @State var awaitingPayment = false
    #else
    @Environment(\.webAuthenticationSession) var webAuthenticationSession
    #endif

    init(itinerary: Itinerary, client: TicketShopClient, tripId: @escaping () -> String?) {
        _model = State(initialValue: CheckoutModel(itinerary: itinerary, client: client))
        self.tripId = tripId
    }

    var body: some View {
        NavigationStack {
            Form {
                switch model.phase {
                case .done(let saved, let failed, let pending): doneSection(saved: saved, failed: failed, pending: pending)
                default: purchaseSections
                }
            }
            .navigationTitle(Text("Buy tickets"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(L10n.t("Close")) { dismiss() }
                }
            }
            .task { await model.loadOffers() }
            #if os(Android)
            .onChange(of: scenePhase) { _, phase in
                guard phase == .active, awaitingPayment else { return }
                awaitingPayment = false
                Task { await model.finish(saveTo: passes, tripId: tripId()) }
            }
            #endif
            .interactiveDismissDisabled(model.phase == .paying || model.phase == .booking)
        }
    }

    @ViewBuilder
    var purchaseSections: some View {
        Section {
            ForEach(model.legs) { item in
                legRow(item.leg, offer: model.offers[item.index])
            }
            if model.phase == .loading {
                HStack { ProgressView(); Text("Checking prices …").foregroundStyle(.secondary) }
            }
        } header: {
            Text("Your journey")
        } footer: {
            if model.phase == .ready && !model.coversWholeTrip {
                Text("Publit cannot sell every part of this route yet. Buy the rest from the operator.")
            }
        }

        Section {
            // No Stepper on Android: minus and plus buttons.
            HStack {
                Text(L10n.f("Adults: %@", String(model.adults)))
                Spacer()
                Button { changeAdults(-1) } label: { Image(systemName: "minus.circle") }
                    .buttonStyle(.borderless).disabled(model.adults <= 1)
                Button { changeAdults(1) } label: { Image(systemName: "plus.circle") }
                    .buttonStyle(.borderless).disabled(model.adults >= 9)
            }
        }

        if !model.selectedOffers.isEmpty {
            Section {
                TextField(L10n.t("First name"), text: $model.customer.firstName)
                TextField(L10n.t("Last name"), text: $model.customer.lastName)
                TextField(L10n.t("E-mail"), text: $model.customer.email)
                    .keyboardType(.emailAddress).textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
            } header: {
                Text("Ticket holder")
            } footer: {
                Text("The name is printed on the ticket. The seller sends the confirmation to this address. Publit keeps both only on this device.")
            }

            Section {
                Toggle(isOn: $model.termsAccepted) {
                    Text("I accept the conditions of carriage and the ticket conditions of the sellers.")
                }
                LabeledContent(L10n.t("Total")) {
                    Text(verbatim: TicketShop.price(model.total, currency: model.currency)).font(.body.weight(.semibold))
                }
                Button {
                    Task { await pay() }
                } label: {
                    HStack {
                        Spacer()
                        if model.phase == .paying || model.phase == .booking { ProgressView() }
                        Text(L10n.f("Pay %@", TicketShop.price(model.total, currency: model.currency))).font(.body.weight(.semibold))
                        Spacer()
                    }
                }
                .disabled(!model.canPay)
                .accessibilityIdentifier("payTickets")
                #if os(Android)
                if awaitingPayment {
                    Button(L10n.t("Check payment")) {
                        awaitingPayment = false
                        Task { await model.finish(saveTo: passes, tripId: tripId()) }
                    }
                }
                #endif
            } footer: {
                Text("You pay on a secure payment page (card, Apple Pay and more). You are only charged for tickets that are booked.")
            }
        }

        if case .failed(let message) = model.phase {
            Section {
                Text(message).foregroundStyle(.red)
                Button(L10n.t("Try again")) {
                    Task {
                        if model.order != nil { await model.finish(saveTo: passes, tripId: tripId()) } else { await model.loadOffers() }
                    }
                }
            }
        }
    }

    func legRow(_ leg: Leg, offer: ShopOffer?) -> some View {
        HStack(alignment: .firstTextBaseline) {
            Image(systemName: ModeStyle.symbol(leg.mode)).foregroundStyle(.secondary).frame(width: 22)
            VStack(alignment: .leading, spacing: 2) {
                Text(verbatim: "\(leg.from.name) → \(leg.to.name)").font(.subheadline.weight(.semibold))
                Text(verbatim: [leg.routeShortName ?? leg.displayName, offer?.operator ?? leg.agencyName,
                                leg.startTime.formatted(date: .omitted, time: .shortened)]
                    .compactMap { $0 }.joined(separator: " · "))
                    .font(.footnote).foregroundStyle(.secondary)
                if let offer {
                    Text(verbatim: offer.title).font(.footnote).foregroundStyle(.secondary)
                } else if model.phase == .ready, let link = TicketLinks.link(for: leg, userLinked: tickets.linkedApps) {
                    Link(destination: link.url) { Text(link.title).font(.footnote.weight(.semibold)) }
                }
            }
            Spacer()
            if let offer {
                Text(verbatim: TicketShop.price(offer.amount, currency: offer.currency)).font(.subheadline.weight(.semibold))
            } else if model.phase == .ready {
                Text("Not in Publit").font(.footnote).foregroundStyle(.secondary)
            }
        }
        .accessibilityElement(children: .combine)
    }

    @ViewBuilder
    func doneSection(saved: Int, failed: Int, pending: Int) -> some View {
        Section {
            Label {
                Text(saved > 0 ? L10n.t("Your tickets are in the Tickets tab.") : L10n.t("No ticket could be booked. You were not charged."))
            } icon: {
                Image(systemName: saved > 0 ? "checkmark.circle.fill" : "exclamationmark.triangle.fill")
                    .foregroundStyle(saved > 0 ? Color.green : Color.orange)
            }
            if failed > 0 && saved > 0 {
                Text("Some parts could not be booked. You were only charged for the booked tickets.").foregroundStyle(.secondary)
            }
            if pending > 0 {
                Text("A ticket is not ready yet. The seller also sends it by e-mail.").foregroundStyle(.secondary)
                Button(L10n.t("Try again")) { Task { await model.finish(saveTo: passes, tripId: tripId()) } }
            }
            Button(L10n.t("Done")) { dismiss() }.font(.body.weight(.semibold))
        }
    }

    func changeAdults(_ delta: Int) {
        model.adults = min(9, max(1, model.adults + delta))
        Task { await model.loadOffers() }
    }

    func pay() async {
        guard let url = await model.startPayment() else { return }
        #if os(Android)
        // The browser returns with publit://shop/return; the app becoming active again books the order.
        awaitingPayment = Droid.bool("openURL", url.absoluteString)
        if !awaitingPayment { _ = model.paymentReturned(nil) }
        #else
        let callback: URL?
        do {
            callback = try await webAuthenticationSession.authenticate(using: url, callbackURLScheme: "publit",
                                                                         preferredBrowserSession: .ephemeral)
        } catch {
            callback = nil
        }
        if model.paymentReturned(callback) {
            await model.finish(saveTo: passes, tripId: tripId())
        }
        #endif
    }
}

/// Buying tickets for a route inside Publit, and the shops' partner links. Shown when either exists.
struct TicketShopSection: View {
    let itinerary: Itinerary
    let tripId: () -> String?
    @State var buying = false
    @Environment(TicketStore.self) var tickets

    var body: some View {
        let partnerLinks = TicketLinks.links(for: itinerary, userLinked: tickets.linkedApps).filter(\.isPartner)
        if !itinerary.isDirect && (TicketShop.isAvailable || !partnerLinks.isEmpty) {
            Section {
                if let url = TicketShop.baseURL {
                    Button { buying = true } label: {
                        Label(L10n.t("Buy tickets in Publit"), systemImage: "cart.fill")
                    }
                    .accessibilityIdentifier("buyTickets")
                    .sheet(isPresented: $buying) {
                        CheckoutView(itinerary: itinerary, client: TicketShopClient(baseURL: url), tripId: tripId)
                    }
                }
                ForEach(partnerLinks) { link in
                    Link(destination: link.url) { Label(link.title, systemImage: "cart") }
                }
            } header: {
                Text("Tickets")
            } footer: {
                if !partnerLinks.isEmpty {
                    Text("Some ticket links are partner links: Publit may get a small commission. The price stays the same, and Publit sends nothing about you.")
                }
            }
        }
    }
}
