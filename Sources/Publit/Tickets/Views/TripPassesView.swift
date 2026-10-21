import SwiftUI

/// The tickets for one trip: its own single tickets, then passes valid at the trip's time.
/// Without a trip: the passes valid now.
struct TripPassesView: View {
    var tripId: String? = nil
    var at: Date = .now
    @Environment(PassStore.self) var store
    @State var adding = false

    var body: some View {
        let list = store.passes(forTrip: tripId, at: at)
        Group {
            if list.isEmpty {
                ContentUnavailableView {
                    Label(L10n.t("No ticket for this trip"), systemImage: "ticket")
                } description: {
                    Text("Add the ticket from a PDF, a photo or a screenshot. Day, weekly and annual passes show up for every trip while they are valid.")
                } actions: {
                    Button(L10n.t("Add ticket")) { adding = true }.buttonStyle(.ink)
                }
            } else {
                ScrollView {
                    LazyVStack(spacing: 14) {
                        ForEach(list) { pass in
                            NavigationLink { PassDetailView(passId: pass.id) } label: {
                                PassCardView(pass: pass, compact: true, now: at, logo: PassLogoCache.image(pass, store: store))
                            }
                            .buttonStyle(.plain)
                            .accessibilityElement(children: .ignore)
                            .accessibilityLabel(pass.accessibilitySummary)
                            .accessibilityAddTraits(.isButton)
                        }
                        Button { adding = true } label: {
                            Label(L10n.t("Add ticket"), systemImage: "plus").frame(maxWidth: .infinity)
                        }
                        .buttonStyle(.soft)
                    }
                    .padding()
                }
                .background(Color.ground)
            }
        }
        .liveBarPadding()
        .navigationTitle(L10n.t("Tickets"))
        .screenTrail("Trip tickets")
        .navigationBarTitleDisplayMode(.inline)
        .sheet(isPresented: $adding) { PassImportView(tripId: tripId) }
    }
}

/// "Show ticket" for a trip: straight to the code when there is exactly one ticket with a code
/// (fast at the gate), otherwise the trip's tickets.
/// The bar item exists only when there is a ticket, and its sheets hang on the screen, not inside
/// the navigation bar: an empty item, or one that presents, can keep the bar laying itself out.
struct ShowPassToolbar: ViewModifier {
    var tripId: String?
    var at: Date
    var enabled = true
    @Environment(PassStore.self) var store
    #if os(Android)
    var dynamicTypeSize: DynamicTypeSize { AndroidEnvironment.dynamicTypeSize }
    #else
    @Environment(\.dynamicTypeSize) var dynamicTypeSize
    #endif
    @State var code: TicketPass?
    @State var listing = false

    func body(content: Content) -> some View {
        let list = enabled ? store.passes(forTrip: tripId, at: at) : []
        content
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    if !list.isEmpty {
                        Button {
                            if list.count == 1, list[0].barcode != nil { code = list[0] } else { listing = true }
                        } label: {
                            Label(L10n.t("Show ticket"), systemImage: "ticket")
                        }
                        .accessibilityIdentifier("showTicket")
                    }
                }
            }
            .fullScreenCover(item: $code) { pass in
                FullScreenCodeView(pass: pass, source: source(pass)).dynamicTypeSize(dynamicTypeSize)
            }
            .sheet(isPresented: $listing) {
                NavigationStack {
                    TripPassesView(tripId: tripId, at: at)
                        .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { listing = false } } }
                }
                .environment(\.liveBarInset, 0)
            }
    }

    private func source(_ pass: TicketPass) -> BarcodeSource? {
        guard let url = store.documentURL(pass), let d = pass.document else { return nil }
        return .file(url, d.kind, page: d.page)
    }
}

/// The ticket button of a running trip. Tap: the default ticket at once (its code full screen).
/// Hold: every ticket valid now, to show another one or make it the default; or add one.
struct QuickTicketToolbar: ViewModifier {
    static let defaultKey = "defaultPassId"
    var tripId: String?
    var at: Date
    var enabled = true
    @Environment(PassStore.self) var store
    #if os(Android)
    var dynamicTypeSize: DynamicTypeSize { AndroidEnvironment.dynamicTypeSize }
    #else
    @Environment(\.dynamicTypeSize) var dynamicTypeSize
    #endif
    @AppStorage(Self.defaultKey) var defaultId = ""
    @State var code: TicketPass?
    @State var detail: TicketPass?
    @State var listing = false
    @State var adding = false

    /// The default ticket when it is valid for this trip, else the trip's own one, else one with a code.
    static func choice(_ list: [TicketPass], preferred: String) -> TicketPass? {
        list.first { $0.id == preferred } ?? list.first { $0.barcode != nil } ?? list.first
    }

    func body(content: Content) -> some View {
        let list = enabled ? store.passes(forTrip: tripId, at: at) : []
        let chosen = Self.choice(list, preferred: defaultId)
        content
            .toolbar {
                if enabled {
                    ToolbarItem(placement: .topBarTrailing) {
                        Menu {
                            if !list.isEmpty {
                                Section(L10n.t("Default ticket")) {
                                    ForEach(list) { pass in
                                        Button {
                                            defaultId = pass.id
                                            show(pass)
                                        } label: {
                                            if pass.id == chosen?.id {
                                                Label(pass.displayTitle, systemImage: "checkmark")
                                            } else {
                                                Text(verbatim: pass.displayTitle)
                                            }
                                        }
                                    }
                                }
                                Button { listing = true } label: { Label(L10n.t("All tickets"), systemImage: "rectangle.stack") }
                            }
                            Button { adding = true } label: { Label(L10n.t("Add ticket"), systemImage: "plus") }
                        } label: {
                            Label(L10n.t("Show ticket"), systemImage: chosen == nil ? "ticket" : "ticket.fill")
                        } primaryAction: {
                            if let chosen { show(chosen) } else { adding = true }
                        }
                        .accessibilityIdentifier("quickTicket")
                    }
                }
            }
            .fullScreenCover(item: $code) { pass in
                FullScreenCodeView(pass: pass, source: source(pass)).dynamicTypeSize(dynamicTypeSize)
            }
            .sheet(item: $detail) { pass in
                NavigationStack {
                    PassDetailView(passId: pass.id)
                        .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { detail = nil } } }
                }
                .environment(\.liveBarInset, 0)
            }
            .sheet(isPresented: $listing) {
                NavigationStack {
                    TripPassesView(tripId: tripId, at: at)
                        .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { listing = false } } }
                }
                .environment(\.liveBarInset, 0)
            }
            .sheet(isPresented: $adding) { PassImportView(tripId: tripId) }
    }

    private func show(_ pass: TicketPass) {
        if pass.barcode != nil { code = pass } else { detail = pass }
    }

    private func source(_ pass: TicketPass) -> BarcodeSource? {
        guard let url = store.documentURL(pass), let d = pass.document else { return nil }
        return .file(url, d.kind, page: d.page)
    }
}

extension View {
    func quickTicketToolbar(tripId: String?, at date: Date, enabled: Bool = true) -> some View {
        modifier(QuickTicketToolbar(tripId: tripId, at: date, enabled: enabled))
    }

    func showPassToolbar(tripId: String? = nil, at date: Date, enabled: Bool = true) -> some View {
        modifier(ShowPassToolbar(tripId: tripId, at: date, enabled: enabled))
    }
}

/// The trip section on route and saved-trip screens: the trip's tickets and "Add ticket".
struct TripPassSection: View {
    let tripId: String?
    let at: Date
    /// Saves the trip first, so a single ticket can belong to it; returns its ID.
    let ensureTrip: () -> String
    @Environment(PassStore.self) var store
    @State var adding: String?
    @State var listing = false

    var body: some View {
        let list = store.passes(forTrip: tripId, at: at)
        Section {
            ForEach(list) { pass in
                Button { listing = true } label: {
                    HStack(spacing: 12) {
                        Image(systemName: pass.symbol.systemImage)
                            .frame(width: 30, height: 30)
                            .foregroundStyle(Color(hex: pass.style.foreground) ?? .white)
                            .background(Color(hex: pass.style.background) ?? .black, in: RoundedRectangle(cornerRadius: 7))
                            .accessibilityHidden(true)
                        VStack(alignment: .leading, spacing: 2) {
                            Text(verbatim: pass.displayTitle).font(.subheadline.weight(.semibold)).foregroundStyle(Color.primary)
                            if let op = pass.operatorName { Text(verbatim: op).font(.caption).foregroundStyle(.secondary) }
                        }
                        Spacer()
                        Image(systemName: "chevron.right").font(.footnote.weight(.semibold)).foregroundStyle(.tertiary)
                    }
                }
                .accessibilityLabel(pass.accessibilitySummary)
            }
            Button { adding = ensureTrip() } label: {
                Label(L10n.t("Add ticket for this trip"), systemImage: "plus.rectangle.on.rectangle")
            }
            .id("trip-ticket-import-row")
            .accessibilityIdentifier("addTripTicket")
            // On one row only: presentation modifiers on a Section are copied onto every row.
            .sheet(item: Binding(get: { adding.map(TripRef.init) }, set: { adding = $0?.id })) { ref in
                PassImportView(tripId: ref.id)
            }
            .sheet(isPresented: $listing) {
                NavigationStack {
                    TripPassesView(tripId: tripId, at: at)
                        .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { listing = false } } }
                }
                .environment(\.liveBarInset, 0)
            }
        } header: {
            Text("Tickets")
        }
    }

    private struct TripRef: Identifiable { let id: String }
}
