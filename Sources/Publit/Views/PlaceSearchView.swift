import SwiftUI

struct PlaceSearchView: View {
    let title: LocalizedStringKey
    let allowCurrentLocation: Bool
    let onSelect: (Endpoint) -> Void

    @Environment(\.dismiss) var dismiss
    @Environment(FavoritesStore.self) var favorites
    @Environment(LocationManager.self) var location
    @Environment(SearchHistoryStore.self) var history
    @State var confirmClear = false
    @State var query = ""
    @State var results: [GeocodeMatch] = []
    @State var errorMessage: String?
    @State var isSearching = false
    /// Query the current results belong to; "No matches." only after a real search.
    @State var searchToken = UUID()
    @State var searched: String?
    @State var scope: SearchScope = .all
    /// Google's suggestions (Settings → Search), listed like the other hits; looked up when picked.
    @State var googleResults: [GooglePlaces.Suggestion] = []
    @State var googleSession = UUID().uuidString
    @State var resolving: String?

    /// Search everything, or only stops, addresses or places (shops, sights, airports…).
    enum SearchScope: String, CaseIterable, Identifiable {
        case all, stops, addresses, places
        var id: String { rawValue }
        var apiType: String? {
            switch self {
            case .all: return nil
            case .stops: return "STOP"
            case .addresses: return "ADDRESS"
            case .places: return "PLACE"
            }
        }
        var title: LocalizedStringKey {
            switch self {
            case .all: return "All"
            case .stops: return "Stops"
            case .addresses: return "Addresses"
            case .places: return "Places"
            }
        }
    }

    var body: some View {
        NavigationStack {
            List {
                if query.trimmingCharacters(in: .whitespaces).isEmpty {
                    // Home and Work first, then the current location, other saved places, recent ones.
                    Section {
                        ForEach(PlaceRole.allCases, id: \.self) { role in
                            if let p = favorites.place(for: role) {
                                Button { select(.place(p)) } label: {
                                    Label {
                                        VStack(alignment: .leading, spacing: 1) {
                                            Text(role.title).foregroundStyle(.primary)
                                            Text(verbatim: p.name).font(.caption).foregroundStyle(.secondary).lineLimit(1)
                                        }
                                    } icon: { Image(systemName: role.symbol) }
                                }
                                .accessibilityIdentifier("pick-\(role.rawValue)")
                            }
                        }
                        if allowCurrentLocation && !location.isDenied {
                            Button { select(.currentLocation) } label: {
                                Label("Current location", systemImage: "location.fill")
                            }
                        }
                    }
                    let others = favorites.items.filter { $0.role == nil }
                    if !others.isEmpty {
                        Section("Saved") {
                            ForEach(others) { f in
                                Button { select(.place(f)) } label: {
                                    PlaceLabel(name: f.name, subtitle: f.subtitle, isStop: f.isStop)
                                }
                            }
                        }
                    }
                    recentSection
                } else {
                    if let errorMessage {
                        Text(errorMessage).foregroundStyle(.secondary)
                    } else if results.isEmpty && googleResults.isEmpty && !isSearching && searched == query.trimmingCharacters(in: .whitespaces) + "|" + scope.rawValue {
                        Text("No matches.").foregroundStyle(.secondary)
                    }
                    ForEach(results) { m in
                        let saved = SavedPlace(match: m)
                        Button { select(.place(saved)) } label: {
                            PlaceLabel(name: m.name, subtitle: m.subtitle, isStop: m.isStop)
                        }
                        .swipeActions {
                            Button { favorites.toggle(saved) } label: {
                                Label("Save", systemImage: favorites.contains(saved) ? "star.slash" : "star")
                            }
                            .tint(Color.inkAction)
                        }
                    }
                    ForEach(googleResults) { g in
                        Button { pick(g, save: false) } label: {
                            HStack {
                                PlaceLabel(name: g.name, subtitle: g.subtitle, isStop: false)
                                if resolving == g.id { Spacer(); ProgressView() }
                            }
                        }
                        .disabled(resolving != nil)
                        .swipeActions {
                            Button { pick(g, save: true) } label: { Label("Save", systemImage: "star") }
                                .tint(Color.inkAction)
                        }
                    }
                }
            }
            .inkList()
            .environment(\.liveBarInset, 0)
            .searchable(text: $query, placement: .navigationBarDrawer(displayMode: .always),
                        prompt: "Address, stop or place")
            .searchScopes($scope, activation: .onTextEntry) {
                ForEach(SearchScope.allCases) { Text($0.title).tag($0) }
            }
            .autocorrectionDisabled()
            .navigationTitle(title)
            .screenTrail("Place search")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
            }
            .task(id: "\(scope.rawValue)|\(query)") { await search() }
        }
    }

    /// Places searched before, newest first: the history lives where you search.
    private var recentPlaces: [RecentSearch] {
        var seen = Set<String>()
        return history.entries.filter { entry in
            if case .currentLocation = entry.to { return false }
            return seen.insert(entry.to.key).inserted
        }
    }

    @ViewBuilder
    private var recentSection: some View {
        let recent = Array(recentPlaces.prefix(6))
        if !recent.isEmpty {
            Section {
                ForEach(recent) { search in
                    Button { select(search.to) } label: {
                        HStack(spacing: 12) {
                            Image(systemName: "clock.arrow.circlepath")
                                .font(.title3).foregroundStyle(.secondary)
                            VStack(alignment: .leading, spacing: 1) {
                                Text(search.to.title).foregroundStyle(.primary).lineLimit(1)
                                Text(L10n.f("from %@", search.from.title)).font(.caption).foregroundStyle(.secondary).lineLimit(1)
                            }
                        }
                    }
                    .accessibilityIdentifier("recentSearch")
                    .swipeActions { Button("Remove", role: .destructive) { history.remove(search.id) } }
                }
                Button("Clear history", role: .destructive) { confirmClear = true }
                    .font(.subheadline)
            } header: { Text("Recently searched") }
            .confirmationDialog("Clear search history?", isPresented: $confirmClear, titleVisibility: .visible) {
                Button("Clear history", role: .destructive) { history.clear() }
                Button("Cancel", role: .cancel) { }
            }
        }
    }

    private func select(_ endpoint: Endpoint) {
        onSelect(endpoint)
        dismiss()
    }

    private func search() async {
        let token = UUID()
        searchToken = token
        let text = query.trimmingCharacters(in: .whitespaces)
        guard text.count >= 2 else { results = []; googleResults = []; errorMessage = nil; searched = nil; isSearching = false; return }
        // Debounce: the task is cancelled when the query changes again.
        try? await Task.sleep(for: .milliseconds(300))
        guard !Task.isCancelled else { return }
        isSearching = true
        defer { if searchToken == token { isSearching = false } }
        // A pasted link from Google Maps, Apple Maps or OpenStreetMap: the place it points to.
        if let link = MapLink.parse(text: text) {
            let place = await MapLink.resolve(link)
            guard !Task.isCancelled, searchToken == token else { return }
            if let place, let lat = place.lat, let lon = place.lon {
                results = [GeocodeMatch.picked(name: place.name ?? L10n.t("Place from the link"), at: LatLon(lat: lat, lon: lon))]
                googleResults = []
                errorMessage = nil
                searched = text + "|" + scope.rawValue
                return
            }
            if let name = place?.name ?? link.name, name != text {
                await searchNames(name, token: token)
                return
            }
        }
        await searchNames(text, token: token)
    }

    private func searchNames(_ text: String, token: UUID) async {
        let scope = scope
        let useGoogle = GooglePlaces.isActive && scope != .stops
        let near = location.coordinate
        async let transit = TransitousClient.shared.geocode(text, near: near, type: useGoogle && scope == .all ? nil : scope.apiType)
        var google: [GooglePlaces.Suggestion]?
        if useGoogle {
            google = try? await GooglePlaces.autocomplete(text, near: location.coordinate, session: googleSession)
        }
        do {
            var r = try await transit
            guard !Task.isCancelled, searchToken == token else { return }
            if let google {
                // Google for places and addresses, Transitous for the stops (they route best).
                r = scope == .all ? Array(r.filter(\.isStop).prefix(4)) : []
                googleResults = google.filter { g in
                    !g.isStop && (scope == .addresses ? g.isAddress : scope == .places ? !g.isAddress : true)
                }
            } else {
                googleResults = []
            }
            results = r
            errorMessage = nil
            searched = text + "|" + scope.rawValue
        } catch {
            guard !Task.isCancelled, searchToken == token else { return }
            if let google, !google.isEmpty {
                results = []
                googleResults = google.filter { !$0.isStop }
                errorMessage = nil
                searched = text + "|" + scope.rawValue
            } else if !(error is CancellationError) && (error as? URLError)?.code != .cancelled {
                errorMessage = error.localizedDescription
            }
        }
    }

    /// A Google suggestion has no coordinates yet: look the place up, then use or save it.
    private func pick(_ g: GooglePlaces.Suggestion, save: Bool) {
        resolving = g.id
        Task {
            defer { resolving = nil }
            do {
                let m = try await GooglePlaces.place(g, session: googleSession)
                // The session ends with the pick; the next typing starts a new one.
                googleSession = UUID().uuidString
                let saved = SavedPlace(match: m)
                if save { favorites.toggle(saved) } else { select(.place(saved)) }
            } catch {
                errorMessage = error.localizedDescription
            }
        }
    }
}

struct PlaceLabel: View {
    let name: String
    let subtitle: String?
    let isStop: Bool
    /// Apple's kind of place ("restaurant"): its coloured symbol, as in Apple Maps.
    var category: String? = nil

    var body: some View {
        HStack(spacing: 12) {
            if !isStop, let c = PlaceCategory.of(category) {
                CategoryIcon(category: c, size: 28)
            } else {
                Image(systemName: isStop ? "tram.circle.fill" : "mappin.circle.fill")
                    .font(.title3)
                    .foregroundStyle(isStop ? Color.ink : Color.secondary)
            }
            VStack(alignment: .leading, spacing: 1) {
                Text(name).foregroundStyle(.primary).lineLimit(1)
                if let subtitle, !subtitle.isEmpty {
                    Text(subtitle).font(.caption).foregroundStyle(.secondary).lineLimit(1)
                }
            }
        }
    }
}

/// A kind of place as Apple Maps draws it: white symbol on a circle in the category's colour.
struct CategoryIcon: View {
    let category: PlaceCategory
    var size: CGFloat = 28

    var body: some View {
        Image(systemName: category.symbol)
            .font(.system(size: size * 0.46, weight: .semibold))
            .foregroundStyle(.white)
            .frame(width: size, height: size)
            .background(Color(uiColor: UIColor(hex: category.colorHex)), in: Circle())
            .accessibilityHidden(true)
    }
}
