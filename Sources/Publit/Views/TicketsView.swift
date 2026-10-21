import SwiftUI
#if canImport(PhotosUI)
import PhotosUI
#endif
#if canImport(UIKit)
import UIKit
#endif

/// All tickets the user keeps in Publit.
struct TicketsView: View {
    @Environment(TicketStore.self) var store
    #if os(Android)
    var dynamicTypeSize: DynamicTypeSize { AndroidEnvironment.dynamicTypeSize }
    #else
    @Environment(\.dynamicTypeSize) var dynamicTypeSize
    #endif
    @State var adding = false
    @State var showing: Ticket?

    var body: some View {
        List {
            if store.tickets.isEmpty {
                ContentUnavailableView("No tickets yet", systemImage: "ticket",
                                       description: Text("Add a screenshot of a pass such as KlimaTicket or Deutschlandticket, a one-way ticket, or a link to the app your ticket lives in."))
            }
            if !store.passes.isEmpty {
                Section("Passes") {
                    ForEach(store.passes) { t in row(t) }
                        .onDelete { idx in idx.map { store.passes[$0].id }.forEach(store.remove) }
                }
            }
            let singles = store.tickets.filter { $0.kind == .single }
            if !singles.isEmpty {
                Section("Single trips") {
                    ForEach(singles) { t in row(t) }
                        .onDelete { idx in idx.map { singles[$0].id }.forEach(store.remove) }
                }
            }
        }
        .readableWidth()
        .inkList()
        .navigationTitle("My tickets")
        .screenTrail("My tickets")
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button { adding = true } label: { Label("Add ticket", systemImage: "plus") }
            }
        }
        .sheet(isPresented: $adding) { AddTicketView(tripId: nil) }
        .fullScreenCover(item: $showing) { t in
            TicketDisplayView(tickets: [t])
                .dynamicTypeSize(dynamicTypeSize)
        }
    }

    private func row(_ t: Ticket) -> some View {
        Button { showing = t } label: { TicketSummaryCard(ticket: t) }
            .buttonStyle(.plain)
            .padding(.vertical, 4)
    }

}

/// Add a ticket: screenshot and/or app link.
struct AddTicketView: View {
    /// Single tickets added from a saved trip belong to that trip.
    let tripId: String?

    @Environment(\.dismiss) var dismiss
    @Environment(TicketStore.self) var store
    @State var name = ""
    @State var kind: Ticket.Kind
    @State var photo: PhotosPickerItem?
    @State var imageData: Data?
    @State var imagePreview: UIImage?
    @State var photoLoadTask: Task<Void, Never>?
    @State var appLink = ""
    @State var hasExpiry = false
    @State var isSaving = false
    @State var saveError: String?
    @State var validUntil = Calendar.current.date(byAdding: .year, value: 1, to: .now) ?? .now

    init(tripId: String?, kind: Ticket.Kind? = nil) {
        self.tripId = tripId
        _kind = State(initialValue: kind ?? (tripId == nil ? .pass : .single))
    }

    private var linkIsValid: Bool { appLink.isEmpty || TicketLinks.url(appLink) != nil }
    private var canSave: Bool { (imageData != nil || !appLink.isEmpty) && linkIsValid }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField("Name, e.g. KlimaTicket Österreich", text: $name)
                    Picker("Type", selection: $kind) {
                        ForEach(Ticket.Kind.allCases, id: \.self) { Text($0.title).tag($0) }
                    }
                }
                Section {
                    PhotosPicker(selection: $photo, matching: .images) {
                        Label(imageData == nil ? L10n.t("Choose screenshot") : L10n.t("Replace screenshot"), systemImage: "photo.on.rectangle")
                    }
                    if let imagePreview {
                        Image(uiImage: imagePreview).resizable().scaledToFit().frame(maxHeight: 220)
                            .frame(maxWidth: .infinity)
                    }
                } header: {
                    Text("Screenshot")
                } footer: {
                    Text("A screenshot of the ticket or its QR code. Inspectors usually need the live ticket in the operator's app; use the link below for that.")
                }
                Section {
                    TextField("Link to ticket app or shop", text: $appLink)
                        .keyboardType(.URL).textInputAutocapitalization(.never).autocorrectionDisabled()
                    if !linkIsValid {
                        Text("This is not a valid link.").font(.caption).foregroundStyle(Color.danger)
                    }
                } header: {
                    Text("Open app")
                } footer: {
                    Text("A web address (many ticket apps open from their website link) or the app's own link, e.g. appname://.")
                }
                if kind == .pass {
                    Section {
                        Toggle("Expires", isOn: $hasExpiry)
                        if hasExpiry {
                            DatePicker("Valid until", selection: $validUntil, displayedComponents: .date)
                        }
                    }
                }
            }
            .inkList()
            .overlay { if isSaving { LogoLoader(size: 40).frame(width: 100).matteSurface(in: RoundedRectangle(cornerRadius: 12)) } }
            .alert("Ticket could not be saved", isPresented: Binding(get: { saveError != nil }, set: { if !$0 { saveError = nil } })) {
                Button("Done") { saveError = nil }
            } message: { Text(saveError ?? "") }
            .navigationTitle("Add ticket")
            .screenTrail("Add ticket")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() }.disabled(isSaving) }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") {
                        isSaving = true
                        Task {
                            do {
                                try await store.addAsync(name: name, kind: kind, image: imageData, appLink: appLink,
                                                         validUntil: kind == .pass && hasExpiry ? validUntil : nil, tripId: tripId)
                                dismiss()
                            } catch { saveError = L10n.t("Couldn't save ticket. Please try again.") }
                            isSaving = false
                        }
                    }
                    .disabled(!canSave || isSaving)
                }
            }
            .interactiveDismissDisabled(isSaving)
            .onDisappear { photoLoadTask?.cancel() }
            .onChange(of: photo) { _, item in
                photoLoadTask?.cancel()
                imageData = nil
                imagePreview = nil
                photoLoadTask = Task {
                    guard let item, let data = try? await item.loadTransferable(type: Data.self),
                          !Task.isCancelled,
                          let preview = await TicketImageLoader.preview(data), !Task.isCancelled else { return }
                    // Preserve source bytes; the small display preview is decoded off the main actor.
                    imageData = data
                    imagePreview = preview
                }
            }
        }
    }
}

/// Full-screen ticket view with maximum brightness so scanners can read the code.
struct TicketDisplayView: View {
    let tickets: [Ticket]
    @Environment(\.dismiss) var dismiss
    @Environment(\.openURL) var openURL
    @Environment(TicketStore.self) var store
    @State var previousBrightness: CGFloat?
    @State var original: Ticket?

    var body: some View {
        NavigationStack {
            Group {
                if tickets.isEmpty {
                    ContentUnavailableView("No ticket", systemImage: "ticket",
                                           description: Text("Add tickets in Settings → My tickets."))
                } else {
                    TabView {
                        ForEach(tickets) { t in page(t) }
                    }
                    .tabViewStyle(.page(indexDisplayMode: tickets.count > 1 ? .always : .never))
                    .indexViewStyle(.page(backgroundDisplayMode: .always))
                }
            }
            .navigationTitle(tickets.count == 1 ? tickets[0].name : L10n.t("Tickets"))
            .screenTrail("Ticket display")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } }
            }
        }
        .onAppear {
            previousBrightness = UIScreen.main.brightness
            UIScreen.main.brightness = 1
        }
        .onDisappear {
            if let previousBrightness { UIScreen.main.brightness = previousBrightness }
        }
    }

    @ViewBuilder
    private func page(_ t: Ticket) -> some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                TicketSummaryCard(ticket: t)
                if let url = store.imageURL(t) {
                    VStack(alignment: .leading, spacing: 12) {
                        Label("Stored copy", systemImage: "doc").font(.subheadline.weight(.semibold))
                        TicketAttachmentView(url: url, label: L10n.f("Ticket %@", t.name))
                            .frame(maxWidth: .infinity)
                            .padding(12)
                            .background(Color.white)
                            .accessibilityLabel(L10n.f("Ticket %@", t.name))
                        Button { original = t } label: {
                            Label("Show original", systemImage: "arrow.up.left.and.arrow.down.right")
                                .frame(maxWidth: .infinity)
                        }
                        .buttonStyle(.soft)
                        .accessibilityIdentifier("showTicketOriginal")
                    }
                    .padding(16)
                    .background(Color(uiColor: .secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 14))
                    .overlay(RoundedRectangle(cornerRadius: 14).stroke(Color.primary.opacity(0.10), lineWidth: 1))
                }
                if let app = t.appURL {
                    Button { openURL(app) } label: {
                        Label("Open ticket app", systemImage: "arrow.up.forward.app").frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.ink).controlSize(.large)
                }
                if t.imageFile == nil && t.appURL == nil {
                    Text("This ticket has no screenshot or link.").foregroundStyle(.secondary)
                }
                Text("A stored screenshot may not be accepted. Use the operator's live ticket when required.")
                    .font(.footnote).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true)
            }
            .padding(16).padding(.bottom, 32)
        }
        .background(Color.ground)
        .sheet(item: $original) { ticket in
            NavigationStack {
                if let url = store.imageURL(ticket) {
                    TicketAttachmentView(url: url, label: L10n.f("Ticket %@", ticket.name), original: true)
                        .frame(maxWidth: .infinity, maxHeight: .infinity)
                        .padding(8).background(Color.white)
                        .accessibilityLabel(L10n.f("Ticket %@", ticket.name))
                        .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { original = nil } } }
                }
            }
        }
    }

}

/// Operator → the user's own ticket app or shop. Used for "Buy ticket" in every route.
struct LinkedTicketAppsView: View {
    @Environment(TicketStore.self) var store
    @State var operatorName = ""
    @State var link = ""

    /// Shops whose links usually open the operator's app when it is installed.
    struct Suggestion: Hashable { let name: String; let url: String }
    static let suggestions: [Suggestion] = [
        Suggestion(name: "ÖBB", url: "https://tickets.oebb.at"),
        Suggestion(name: "Wiener Linien", url: "https://shop.wienerlinien.at"),
        Suggestion(name: "DB", url: "https://int.bahn.de"),
        Suggestion(name: "MVV", url: "https://www.mvv-muenchen.de"),
        Suggestion(name: "Trenitalia", url: "https://www.trenitalia.com"),
        Suggestion(name: "Italo", url: "https://www.italotreno.com"),
        Suggestion(name: "SBB", url: "https://www.sbb.ch"),
    ]

    var body: some View {
        List {
            Section {
                ForEach(store.linkedApps.keys.sorted(), id: \.self) { key in
                    VStack(alignment: .leading, spacing: 2) {
                        Text(key.capitalized)
                        Text(store.linkedApps[key] ?? "").font(.caption).foregroundStyle(.secondary).lineLimit(1)
                    }
                }
                .onDelete { idx in
                    let keys = store.linkedApps.keys.sorted()
                    idx.forEach { store.linkApp(nil, forOperator: keys[$0]) }
                }
            } footer: {
                Text("Routes with this operator show \"Open your app\" instead of the operator's website.")
            }
            Section("Add") {
                TextField("Operator as shown in routes, e.g. Atac", text: $operatorName)
                TextField("Link, e.g. https://… or app://", text: $link)
                    .keyboardType(.URL).textInputAutocapitalization(.never).autocorrectionDisabled()
                Button("Link") {
                    store.linkApp(link, forOperator: operatorName)
                    operatorName = ""; link = ""
                }
                .disabled(operatorName.isEmpty || TicketLinks.url(link) == nil)
            }
            Section("Suggestions") {
                ForEach(Self.suggestions, id: \.self) { s in
                    Button {
                        operatorName = s.name; link = s.url
                    } label: {
                        LabeledContent(s.name, value: URL(string: s.url)?.host ?? s.url)
                    }
                }
            }
        }
        .readableWidth()
        .inkList()
        .liveBarPadding()
        .navigationTitle("Ticket apps")
        .screenTrail("Ticket apps")
    }
}

/// Ticket button for navigation bars: shows the trip's tickets and valid passes.
struct ShowTicketButton: View {
    var tripId: String? = nil
    @Environment(TicketStore.self) var store
    #if os(Android)
    var dynamicTypeSize: DynamicTypeSize { AndroidEnvironment.dynamicTypeSize }
    #else
    @Environment(\.dynamicTypeSize) var dynamicTypeSize
    #endif
    @State var showing = false

    var body: some View {
        let list = store.tickets(forTrip: tripId)
        if !list.isEmpty {
            Button { showing = true } label: { Label("Show ticket", systemImage: "ticket") }
                .fullScreenCover(isPresented: $showing) {
                    TicketDisplayView(tickets: list)
                        .dynamicTypeSize(dynamicTypeSize)
                }
        }
    }
}
