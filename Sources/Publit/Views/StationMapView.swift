import SwiftUI
// Android: @Observable needs SkipFuse in this file, or model changes never reach the Compose UI.
import Observation
import SkipFuse
#if canImport(MapKit)
import MapKit
#endif

/// State of the transit map: stations, line shapes and moving vehicles for what is on screen.
/// Heavy work (grouping, polyline decoding) runs off the main thread; the 2-second tick only
/// interpolates positions along already decoded shapes.
@MainActor
@Observable
final class TransitMapModel {
    var content = TransitMapContent()
    var stationsHidden = false
    var liveHidden = false
    var isLoadingLive = false
    var liveError: String?
    var stationsError: String?
    /// A failed station download is tried again by the 2 s tick (before, only moving the map did).
    @ObservationIgnored private var stationsRetryAt: Date?
    /// The first live layer arrived; later refreshes update silently.
    var hasLive = false
    @ObservationIgnored private var liveRetryAt: Date?

    var filter = ModeFilter() { didSet { if filter != oldValue { rebuildLive(); bumpStations() } } }
    var showStations = true { didSet { if showStations != oldValue { bumpStations() } } }
    var showLines = true { didSet { if showLines != oldValue { publishShapes() } } }
    var showVehicles = true { didSet { if showVehicles != oldValue { tick() } } }
    var showSharing = false { didSet { if showSharing != oldValue { Task { await loadRentals(force: true) } } } }

    @ObservationIgnored private var region: MKCoordinateRegion?
    @ObservationIgnored private var allStations: [MapStation] = []
    @ObservationIgnored private var segments: [TripSegment] = []
    @ObservationIgnored private var segmentPrecision = 5
    @ObservationIgnored private var tracks: [VehicleTrack] = []
    @ObservationIgnored private var shapes: [String: LineShape] = [:]
    @ObservationIgnored private var liveBox: (min: LatLon, max: LatLon, at: Date)?
    @ObservationIgnored private var rentalBox: (min: LatLon, max: LatLon, at: Date)?
    @ObservationIgnored private var regionTask: Task<Void, Never>?
    @ObservationIgnored private var stationsTask: Task<Void, Never>?
    @ObservationIgnored private var liveTask: Task<Void, Never>?
    @ObservationIgnored private var rentalsTask: Task<Void, Never>?
    @ObservationIgnored private var stationsBusy = false
    @ObservationIgnored private var rentalsBusy = false
    @ObservationIgnored private var stationsQueued = false
    @ObservationIgnored private var liveQueued = false
    @ObservationIgnored private var rentalsQueued = false
    @ObservationIgnored private var active = true
    @ObservationIgnored private var stationCoverage: MapStopCache.Coverage?
    @ObservationIgnored private var rawStops: [MapStop] = []
    @ObservationIgnored private var separatePoles = false
    @ObservationIgnored private var buildGeneration = UUID()

    /// Spans (degrees latitude) above which a layer is not loaded: too much to show or download.
    static let maxStationSpan = 0.08
    static let maxLiveSpan = 0.06
    static let maxRentalSpan = 0.03
    /// Vehicles are asked for this far ahead; the first request also draws the lines.
    static let firstWindow: TimeInterval = 5 * 60
    static let refreshWindow: TimeInterval = 4 * 60
    static var refreshEvery: TimeInterval { Energy.interval(30) }
    static var tickSeconds: TimeInterval { Energy.interval(2) }

    func regionChanged(_ r: MKCoordinateRegion) {
        region = r
        regionTask?.cancel()
        regionTask = Task { [weak self] in
            try? await Task.sleep(for: .milliseconds(300))
            guard !Task.isCancelled, let self else { return }
            guard self.active else { return }
            self.queueLoads()
            self.tick()
        }
    }

    /// A new camera rectangle only replaces the debounce, not the already running downloads.
    /// One flight per layer finishes; if the camera moved, fetch just the latest interest next.
    private func queueLoads() {
        guard active, let region else { return }
        if stationsBusy { stationsQueued = true }
        else { stationsTask = Task { await loadStations(region) } }
        if isLoadingLive { liveQueued = true }
        else { liveTask = Task { await loadLive(force: false) } }
        if rentalsBusy { rentalsQueued = true }
        else { rentalsTask = Task { await loadRentals(force: false) } }
    }

    func pause() {
        active = false
        regionTask?.cancel(); stationsTask?.cancel(); liveTask?.cancel(); rentalsTask?.cancel()
        buildGeneration = UUID()
        // A cancelled download or build leaves nothing new: load the live layer again on resume
        // instead of waiting out the 30 s refresh.
        liveBox = nil
    }

    func resume() {
        active = true
        if let region { regionChanged(region) }
    }

    /// Called every `tickSeconds` while the map is open.
    func tick(now: Date = .now) {
        guard active, let r = region else { return }
        let (lo, hi) = Self.box(r, grow: 0.1)
        let list = showVehicles && !liveHidden ? LiveMap.vehicles(tracks, at: now, min: lo, max: hi) : []
        if list.isEmpty && content.vehicles.isEmpty { return }
        content.vehicles = list
        content.vehiclesVersion += 1
    }

    func refreshIfDue() async {
        if let stationsRetryAt, Date() >= stationsRetryAt, !stationsBusy, let region {
            self.stationsRetryAt = nil
            stationsTask = Task { await loadStations(region) }
        }
        if let liveRetryAt, Date() >= liveRetryAt, !isLoadingLive {
            self.liveRetryAt = nil
            liveTask = Task { await loadLive(force: true) }
        }
        if let box = liveBox, !isLoadingLive, Date().timeIntervalSince(box.at) >= Self.refreshEvery {
            liveTask = Task { await loadLive(force: true) }
        }
        if showSharing, let box = rentalBox, Date().timeIntervalSince(box.at) >= 60 { await loadRentals(force: true) }
    }

    // MARK: Loading

    private func loadStations(_ r: MKCoordinateRegion) async {
        guard active, !Task.isCancelled, !stationsBusy else { return }
        stationsBusy = true
        defer {
            stationsBusy = false
            if stationsQueued, active {
                stationsQueued = false
                if let region { stationsTask = Task { await loadStations(region) } }
            }
        }
        guard r.span.latitudeDelta <= Self.maxStationSpan else {
            stationsHidden = true
            if !content.stations.isEmpty { allStations = []; bumpStations() }
            return
        }
        stationsHidden = false
        // A margin around the screen: stations at the edge have all their platforms, so their pins stay put.
        let (viewLo, viewHi) = Self.box(r, grow: 0)
        let zoom = max(14, min(16, Self.zoom(r)))
        let separate = r.span.latitudeDelta <= MapStation.poleSpan
        if (separate != separatePoles || allStations.isEmpty), !rawStops.isEmpty {
            let cached = rawStops
            let grouped = await Task.detached(priority: .userInitiated) { MapStation.group(cached, separatePoles: separate) }.value
            guard !Task.isCancelled else { return }
            separatePoles = separate; allStations = grouped; bumpStations()
        }
        if let stationCoverage, stationCoverage.contains(min: viewLo, max: viewHi, zoom: zoom) { return }
        let (lo, hi) = Self.box(r, grow: 0.3)
        let stops: [MapStop]
        do {
            stops = try await TransitousClient.shared.mapStops(min: lo, max: hi, zoom: zoom)
        } catch {
            if !Task.isCancelled && !(error is CancellationError) && (error as? URLError)?.code != .cancelled {
                stationsError = L10n.t("Stations could not be loaded.")
                stationsRetryAt = Date().addingTimeInterval(5)
            }
            return
        }
        guard !Task.isCancelled else { return }
        stationsError = nil
        rawStops = stops
        let currentSeparate = (region?.span.latitudeDelta ?? r.span.latitudeDelta) <= MapStation.poleSpan
        let grouped = await Task.detached(priority: .userInitiated) { MapStation.group(stops, separatePoles: currentSeparate) }.value
        guard !Task.isCancelled else { return }
        separatePoles = currentSeparate
        allStations = grouped
        // Marked as covered only once its pins are on the map: a download or grouping cut off
        // halfway (map closed, app in the background) used to leave the area "covered" but empty.
        stationCoverage = MapStopCache.Coverage(min: lo, max: hi, zoom: zoom)
        bumpStations()
    }

    /// Centre of what is on screen: the map search prefers places around it.
    var center: LatLon? { region.map { LatLon(lat: $0.center.latitude, lon: $0.center.longitude) } }

    private func bumpStations() {
        let f = filter
        content.stations = !showStations ? [] : f.isActive ? allStations.filter { $0.modes.contains { f.allows($0) } } : allStations
        content.stationsVersion += 1
    }

    private func loadLive(force: Bool) async {
        guard active, !Task.isCancelled, !isLoadingLive, let r = region else { return }
        guard r.span.latitudeDelta <= Self.maxLiveSpan else {
            liveHidden = true
            tracks = []
            if !content.shapes.isEmpty { content.shapes = []; content.shapesVersion += 1 }
            return
        }
        let wasHidden = liveHidden
        liveHidden = false
        if wasHidden, !segments.isEmpty { rebuildLive() }
        guard showLines || showVehicles else { return }
        let (vlo, vhi) = Self.box(r, grow: 0)
        // Small pans stay inside the last (larger) box: no new request.
        if !force, let b = liveBox, vlo.lat >= b.min.lat, vlo.lon >= b.min.lon, vhi.lat <= b.max.lat, vhi.lon <= b.max.lon,
           Date().timeIntervalSince(b.at) < Self.refreshEvery { return }
        let (lo, hi) = Self.box(r, grow: 0.25)
        let first = liveBox == nil || shapes.isEmpty
        // The server leaves buses, trams and ferries out below zoom 14 (only trains): at the widest
        // view that still shows lines, ask as if zoomed in, so every line that runs there is drawn.
        let zoom = max(14, Self.zoom(r))
        let now = Date()
        liveBox = (lo, hi, now)
        isLoadingLive = true
        defer {
            isLoadingLive = false
            if liveQueued, active { liveQueued = false; liveTask = Task { await loadLive(force: false) } }
        }
        guard let segs = try? await TransitousClient.shared.mapTrips(
            min: lo, max: hi, zoom: zoom, start: now.addingTimeInterval(-60),
            end: now.addingTimeInterval(first ? Self.firstWindow : Self.refreshWindow)) else {
                liveBox = nil
                if !Task.isCancelled { liveError = L10n.t("Live layer unavailable. Stations still work."); liveRetryAt = Date().addingTimeInterval(10) }
                return
            }
        guard !Task.isCancelled else { liveBox = nil; return }
        liveError = nil; liveRetryAt = nil
        hasLive = true
        segments = segs
        segmentPrecision = zoom >= 14 ? 5 : 4
        rebuildLive()
    }

    /// Shapes and tracks from the last answer (also when the filter changes).
    private func rebuildLive() {
        let generation = UUID()
        buildGeneration = generation
        let segs = segments, f = filter, precision = segmentPrecision
        let tolerance = LiveMap.tolerance(spanMeters: (region?.span.latitudeDelta ?? 0.02) * 111_000)
        Task {
            let snap = await Task.detached(priority: .userInitiated) {
                LiveMap.build(segs, filter: f, precision: precision, tolerance: tolerance)
            }.value
            guard active, buildGeneration == generation, !Task.isCancelled else { return }
            tracks = snap.tracks
            // Lines stay drawn after their vehicles have passed; a new filter starts over.
            if f != lastShapeFilter { shapes = [:]; lastShapeFilter = f }
            if shapes.count > LiveMap.maxShapes { shapes = [:] }
            var changed = false
            for s in snap.shapes where shapes[s.id] == nil { shapes[s.id] = s; changed = true }
            if changed || content.shapes.isEmpty != shapes.isEmpty { publishShapes() }
            tick()
        }
    }

    @ObservationIgnored private var lastShapeFilter = ModeFilter()

    private func publishShapes() {
        content.shapes = showLines && !liveHidden
            ? shapes.values.sorted { LiveMap.rank($0.mode) != LiveMap.rank($1.mode) ? LiveMap.rank($0.mode) < LiveMap.rank($1.mode) : $0.id < $1.id }
            : []
        content.shapesVersion += 1
    }

    private func loadRentals(force: Bool) async {
        guard active, !Task.isCancelled, !rentalsBusy else { return }
        rentalsBusy = true
        defer {
            rentalsBusy = false
            if rentalsQueued, active { rentalsQueued = false; rentalsTask = Task { await loadRentals(force: false) } }
        }
        guard showSharing, let r = region, r.span.latitudeDelta <= Self.maxRentalSpan else {
            if !content.rentals.stations.isEmpty || !content.rentals.vehicles.isEmpty {
                content.rentals = RentalsResponse(); content.rentalsVersion += 1
            }
            rentalBox = nil
            return
        }
        let (vlo, vhi) = Self.box(r, grow: 0)
        if !force, let b = rentalBox, vlo.lat >= b.min.lat, vlo.lon >= b.min.lon, vhi.lat <= b.max.lat, vhi.lon <= b.max.lon { return }
        let (lo, hi) = Self.box(r, grow: 0.25)
        rentalBox = (lo, hi, Date())
        guard let res = try? await TransitousClient.shared.rentals(min: lo, max: hi) else { rentalBox = nil; return }
        guard !Task.isCancelled, showSharing else { return }
        // The whole loaded box: panning inside it does not reload, so nothing may be cut off.
        content.rentals = res.mapSnapshot(min: lo, max: hi)
        content.rentalsVersion += 1
    }

    // MARK: Geometry

    static func box(_ r: MKCoordinateRegion, grow: Double) -> (LatLon, LatLon) {
        let dl = r.span.latitudeDelta * (0.5 + grow), dn = r.span.longitudeDelta * (0.5 + grow)
        return (LatLon(lat: r.center.latitude - dl, lon: r.center.longitude - dn),
                LatLon(lat: r.center.latitude + dl, lon: r.center.longitude + dn))
    }

    static func zoom(_ r: MKCoordinateRegion) -> Int {
        Int((log2(360 / max(r.span.longitudeDelta, 0.0001))).rounded())
    }
}

/// Interactive map of stations, the lines running around you and their vehicles moving live.
/// Tap a station for its departures, a vehicle for its trip, a bike or scooter for its provider.
struct StationMapView: View {
    @Environment(\.dismiss) var dismiss
    @Environment(\.scenePhase) var scenePhase
    @Environment(LocationManager.self) var location
    @Environment(AppState.self) var app
    @State var model = TransitMapModel()
    @State var selection: MapSelection?
    @State var center: LatLon?
    @State var showLayers = false
    @State var query = ""
    @State var searching = false
    @State var results: [GeocodeMatch] = []
    @State var searchedFor: String?
    @State var focus: MapFocus?
    @State var recenter = 0
    /// Stops, places or addresses only (the scope bar under the search field).
    @State var scope: MapSearchScope = .all
    /// "Find nearby": the category shown on the map, its hits and where it was searched.
    @State var category: PlaceCategory?
    @State var categoryHits: [GeocodeMatch] = []
    @State var categoryVersion = 0
    @State var categorySearchedIn: MKCoordinateRegion?
    @State var categoryLoading = false
    @State var categoryFailed = false
    @State var categoryRequest = 0
    @State var region: MKCoordinateRegion?
    @AppStorage("mapModes") var storedModes = ""
    @AppStorage("mapStations") var showStations = true
    @AppStorage("mapLines") var showLines = true
    @AppStorage("mapVehicles") var showVehicles = true
    @AppStorage("mapSharing") var showSharing = false
    @AppStorage("mapBase") var storedBase = MapBase.defaultValue
    @AppStorage("map3D") var realistic3D = false
    /// On by default: museums, shops and beaches can be tapped for "Route here".
    @AppStorage("mapPOI") var pointsOfInterest = true
    @AppStorage("mapTraffic") var traffic = false

    private var look: MapLook {
        MapLook(base: MapBase(storageValue: storedBase), realistic3D: realistic3D, pointsOfInterest: pointsOfInterest, traffic: traffic)
    }

    var body: some View {
        NavigationStack {
            TransitMapView(content: model.content, initialCenter: center ?? location.coordinate,
                           look: look, focus: focus,
                           glide: TransitMapModel.tickSeconds,
                           onRegion: { model.regionChanged($0); region = $0 },
                           onSelect: { selection = $0 },
                           onPick: { place, refined in pick(place, refined: refined) },
                           onTapEmpty: { if selection != nil { selection = nil; focus = nil } },
                           recenter: recenter,
                           places: categoryHits, placesVersion: categoryVersion)
                .ignoresSafeArea(edges: .bottom)
                // Floating over the map, no bar behind it. The map does not reach under the
                // navigation bar at the top, so the chips always stay visible below it.
                .overlay(alignment: .top) { header.padding(.top, 8) }
                // Map controls in one white column at the edge: layers, then my location.
                .overlay(alignment: .bottomTrailing) {
                    VStack(spacing: 10) {
                        layersButton
                        Button { recenter += 1 } label: {
                            Image(systemName: "location.fill")
                        }
                        .buttonStyle(.mapControl)
                        .accessibilityLabel(Text("My location"))
                        .accessibilityIdentifier("mapMyLocation")
                    }
                    .padding(.trailing, 12).padding(.bottom, 28)
                }
                // Loading: the Publit mark in the middle of the map, small and out of the way of taps.
                .overlay {
                    if model.isLoadingLive && !model.hasLive {
                        LogoLoader(label: nil, size: 34)
                            .frame(width: 76, height: 76)
                            .matteSurface(in: Circle())
                            .accessibilityLabel(Text(verbatim: L10n.t("Updating live layer…")))
                            .allowsHitTesting(false)
                            .transition(.opacity.combined(with: .scale(scale: 0.85)))
                    }
                }
                .animation(.easeInOut(duration: 0.25), value: model.isLoadingLive && !model.hasLive)
                .navigationTitle("Stations")
                .screenTrail("Station map")
                .navigationBarTitleDisplayMode(.inline)
                .searchable(text: $query, isPresented: $searching, placement: .navigationBarDrawer(displayMode: .always),
                            prompt: Text("Search places, sights and stops"))
                #if os(Android)
                // No search suggestions or scope bar in Compose: the same list over the map while searching.
                .overlay { if searching { androidSearchPanel } }
                #else
                .searchScopes($scope, activation: .onSearchPresentation) {
                    ForEach(MapSearchScope.allCases) { Text($0.title).tag($0) }
                }
                .searchSuggestions { suggestions }
                #endif
                .onSubmit(of: .search) {
                    if let c = PlaceCategory.matching(query) { browse(c) } else if let first = results.first { show(first) }
                }
                .task(id: "\(query)|\(scope.rawValue)") { await search() }
                .toolbar {
                    ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } }
                }
                .sheet(item: $selection, onDismiss: {
                    // Closed by a swipe, ✕ or a tap on the map: the marker of a picked place goes too.
                    if selection == nil { focus = nil }
                }) { sel in
                    NavigationStack {
                        MapSelectionView(selection: sel, onRoute: route, onRouteFrom: routeFrom)
                            .toolbar {
                                ToolbarItem(placement: .topBarTrailing) {
                                    Button { selection = nil } label: {
                                        Image(systemName: "xmark").font(.footnote.weight(.bold))
                                    }
                                    .accessibilityLabel(Text("Close"))
                                    .accessibilityIdentifier("closeMapSelection")
                                }
                            }
                    }
                        .presentationDetents([.medium, .large])
                        #if !os(Android)
                        .presentationBackgroundInteraction(.enabled(upThrough: .medium))
                        #endif
                }
                .sheet(isPresented: $showLayers) {
                    MapLayersSheet(base: $storedBase, realistic3D: $realistic3D, pointsOfInterest: $pointsOfInterest, traffic: $traffic,
                                   stations: $showStations, lines: $showLines, vehicles: $showVehicles, sharing: $showSharing)
                        .presentationDetents([.medium, .large])
                }
        }
        .environment(\.liveBarInset, 0)
        .onAppear {
            model.resume()
            if center == nil { center = location.coordinate }
            model.filter = ModeFilter(storageValue: storedModes)
            model.showStations = showStations
            model.showLines = showLines
            model.showVehicles = showVehicles
            model.showSharing = showSharing
        }
        .onChange(of: storedModes) { model.filter = ModeFilter(storageValue: storedModes) }
        .onChange(of: showStations) { model.showStations = showStations }
        .onChange(of: showLines) { model.showLines = showLines }
        .onChange(of: showVehicles) { model.showVehicles = showVehicles }
        .onChange(of: showSharing) { model.showSharing = showSharing }
        .onDisappear { model.pause() }
        .task(id: scenePhase) {
            guard scenePhase == .active else { model.pause(); return }
            model.resume()
            // Vehicles glide every 2 s and are fetched again every 30 s, only while the map is on screen.
            guard scenePhase == .active else { return }
            while !Task.isCancelled {
                model.tick()
                await model.refreshIfDue()
                try? await Task.sleep(for: .seconds(TransitMapModel.tickSeconds))
            }
        }
    }

    private var filterBinding: Binding<ModeFilter> {
        Binding(get: { ModeFilter(storageValue: storedModes) }, set: { storedModes = $0.storageValue })
    }

    private var header: some View {
        VStack(spacing: 6) {
            ModeFilterBar(available: ModeGroup.allCases.filter { $0 != .other }, filter: filterBinding, iconsOnly: true)
                .frame(maxWidth: .infinity)
            if let message = model.stationsError ?? model.liveError {
                Text(verbatim: message).font(.footnote).padding(10).matteSurface(in: RoundedRectangle(cornerRadius: 14, style: .continuous)).mapShadow()
            }
            if let category { categoryBar(category) }
            if let hint {
                Text(hint).font(.footnote.weight(.semibold))
                    .padding(.horizontal, 12).padding(.vertical, 6)
                    .matteSurface(in: Capsule())
                    .mapShadow()
            }
        }
    }

    /// The category on the map: how many, "Search this area" after moving, and ✕ to clear it.
    private func categoryBar(_ c: PlaceCategory) -> some View {
        HStack(spacing: 8) {
            HStack(spacing: 6) {
                CategoryIcon(category: c, size: 22)
                Text(c.title).font(.subheadline.weight(.semibold)).lineLimit(1)
                if categoryLoading {
                    ProgressView().controlSize(.mini)
                } else if categoryFailed {
                    Image(systemName: "exclamationmark.triangle").foregroundStyle(.secondary)
                } else {
                    Text(verbatim: "\(categoryHits.count)").font(.subheadline.monospacedDigit()).foregroundStyle(.secondary)
                }
                Button { clearCategory() } label: {
                    Image(systemName: "xmark").font(.caption.weight(.bold)).foregroundStyle(.secondary)
                        .frame(width: 24, height: 24)
                }
                .accessibilityLabel(Text("Close"))
                .accessibilityIdentifier("clearCategory")
            }
            .padding(.leading, 6).padding(.trailing, 4).padding(.vertical, 4)
            .matteSurface(in: Capsule())
            .mapShadow()
            if movedSinceCategorySearch || categoryFailed {
                Button { browse(c) } label: {
                    Label(categoryFailed ? L10n.t("Try again") : L10n.t("Search this area"), systemImage: "arrow.clockwise")
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(Color.ink)
                        .floatingCapsule()
                }
                .accessibilityIdentifier("searchThisArea")
                .transition(.opacity)
            }
        }
        .buttonStyle(.plain)
        .animation(.easeInOut(duration: 0.2), value: movedSinceCategorySearch)
    }

    /// The map moved a good way (or zoomed) since the category was searched.
    private var movedSinceCategorySearch: Bool {
        guard let a = categorySearchedIn, let b = region else { return false }
        let moved = abs(a.center.latitude - b.center.latitude) > a.span.latitudeDelta * 0.35
            || abs(a.center.longitude - b.center.longitude) > a.span.longitudeDelta * 0.35
        let zoomed = b.span.latitudeDelta > a.span.latitudeDelta * 1.6 || b.span.latitudeDelta < a.span.latitudeDelta * 0.5
        return moved || zoomed
    }

    private var hint: String? {
        if model.stationsHidden && showStations { return L10n.t("Zoom in to see stations") }
        if model.liveHidden && (showLines || showVehicles) { return L10n.t("Zoom in to see lines and vehicles") }
        return nil
    }

    private var layersButton: some View {
        Button {
            guard selection != nil else { showLayers = true; return }
            // One sheet at a time: the open one goes down first, else SwiftUI drops the new one.
            selection = nil
            Task { try? await Task.sleep(for: .milliseconds(400)); showLayers = true }
        } label: {
            Image(systemName: "square.3.layers.3d")
        }
        .buttonStyle(.mapControl)
        .accessibilityLabel(Text("Map layers"))
        .accessibilityIdentifier("mapLayers")
    }

    // MARK: Search

    #if os(Android)
    private var androidSearchPanel: some View {
        VStack(spacing: 0) {
            Picker(L10n.t("Search"), selection: $scope) {
                ForEach(MapSearchScope.allCases) { Text($0.title).tag($0) }
            }
            .pickerStyle(.segmented)
            .padding(.horizontal, 12).padding(.vertical, 8)
            List { suggestions }
                .listStyle(.plain)
        }
        .background(Color.surface)
    }
    #endif

    @ViewBuilder
    private var suggestions: some View {
        if MapSearch.query(query) == nil {
            // Nothing typed yet: find places by kind around what is on the map, like Apple Maps.
            Section(L10n.t("Find nearby")) {
                ForEach(PlaceCategory.allCases) { c in
                    Button { browse(c) } label: {
                        HStack(spacing: 12) {
                            CategoryIcon(category: c, size: 30)
                            Text(c.title).foregroundStyle(.primary)
                        }
                    }
                    .accessibilityIdentifier("category-\(c.rawValue)")
                }
            }
        } else {
            if let c = PlaceCategory.matching(query), scope != .stops, scope != .addresses {
                Button { browse(c) } label: {
                    HStack(spacing: 12) {
                        CategoryIcon(category: c, size: 30)
                        Text(L10n.f("All %@ in this area", c.title)).foregroundStyle(.primary)
                    }
                }
                .accessibilityIdentifier("categoryFromQuery")
            }
            ForEach(results) { m in
                Button { show(m) } label: {
                    PlaceLabel(name: m.name, subtitle: subtitle(m), isStop: m.isStop, category: m.category)
                }
                .accessibilityIdentifier("mapSearchResult")
            }
            if results.isEmpty, let searchedFor, searchedFor == MapSearch.query(query) {
                Text("No places found").foregroundStyle(.secondary)
            }
        }
    }

    /// Address or area, with how far it is from the middle of the map.
    private func subtitle(_ m: GeocodeMatch) -> String {
        guard let from = model.center ?? location.coordinate else { return m.subtitle }
        let d = from.distance(to: LatLon(lat: m.lat, lon: m.lon))
        let distance = Fmt.distance(meters: d)
        return m.subtitle.isEmpty ? distance : "\(distance) · \(m.subtitle)"
    }

    /// Every place of this kind in view, as markers on the map (Apple's points of interest).
    private func browse(_ c: PlaceCategory) {
        searching = false
        query = ""
        selection = nil
        focus = nil
        category = c
        let area = region ?? MKCoordinateRegion(center: (model.center ?? location.coordinate ?? LatLon(lat: 48.2, lon: 16.37)).clCoordinate,
                                                latitudinalMeters: 2500, longitudinalMeters: 2500)
        categorySearchedIn = area
        categoryLoading = true
        categoryFailed = false
        categoryHits = []
        categoryVersion += 1
        categoryRequest += 1
        let request = categoryRequest
        Task {
            do {
                let hits = try await Self.applePlaces(in: area, category: c)
                guard categoryRequest == request, category == c else { return }
                categoryHits = hits
                categoryVersion += 1
                categoryLoading = false
            } catch {
                guard categoryRequest == request, category == c else { return }
                categoryLoading = false
                categoryFailed = !(error is CancellationError)
            }
        }
    }

    private func clearCategory() {
        categoryRequest += 1
        category = nil
        categoryHits = []
        categoryVersion += 1
        categorySearchedIn = nil
        categoryLoading = false
        categoryFailed = false
    }

    #if canImport(MapKit)
    /// Apple's points of interest of one kind in an area (a little larger than the screen, at most ~25 km).
    private static func applePlaces(in area: MKCoordinateRegion, category: PlaceCategory) async throws -> [GeocodeMatch] {
        var r = area
        r.span = MKCoordinateSpan(latitudeDelta: min(area.span.latitudeDelta * 1.1, 0.25),
                                  longitudeDelta: min(area.span.longitudeDelta * 1.1, 0.35))
        let request = MKLocalPointsOfInterestRequest(coordinateRegion: r)
        request.pointOfInterestFilter = MKPointOfInterestFilter(including: [MKPointOfInterestCategory(rawValue: category.appleCategory)])
        var items = (try? await MKLocalSearch(request: request).start())?.mapItems ?? []
        if items.isEmpty {
            // Some kinds come back only for a text search: ask by name in the same area.
            let text = MKLocalSearch.Request()
            text.naturalLanguageQuery = category.title
            text.region = r
            text.resultTypes = .pointOfInterest
            text.pointOfInterestFilter = request.pointOfInterestFilter
            items = (try? await MKLocalSearch(request: text).start())?.mapItems ?? []
        }
        return items.prefix(60).compactMap { item in
            let c = item.placemark.coordinate
            let point = LatLon(lat: c.latitude, lon: c.longitude)
            guard point.isValidCoordinate else { return nil }
            var m = TransitMapView.Coordinator.match(item, name: item.name ?? category.title, at: point)
            if m.category == nil { m = .picked(name: m.name, at: point, street: m.street, houseNumber: m.houseNumber,
                                               locality: m.areas.first?.name, country: m.country, category: category.word) }
            return m
        }
    }
    #else
    /// Android: the places of one kind from OpenStreetMap in the same area.
    private static func applePlaces(in area: MKCoordinateRegion, category: PlaceCategory) async throws -> [GeocodeMatch] {
        try await OSMPlaces.places(in: area, category: category)
    }
    #endif

    private func search() async {
        guard let text = MapSearch.query(query) else { results = []; searchedFor = nil; return }
        // Debounce: the task is cancelled when the text changes again.
        try? await Task.sleep(for: .milliseconds(300))
        guard !Task.isCancelled else { return }
        let near = model.center ?? location.coordinate
        let scope = scope
        // Stops and towns from the transit geocoder, sights and shops from Apple Maps ("Museum", "Lidl"), side by side.
        // The scope bar narrows it down: stops only, places only or addresses only.
        async let apple = Self.applePlaces(scope.usesApple ? text : nil, near: near, addresses: scope != .places)
        do {
            var found: [GeocodeMatch] = []
            if scope.usesTransit { found = try await TransitousClient.shared.geocode(text, near: near) }
            let extra = await apple
            guard !Task.isCancelled else { return }
            results = scope.filter(GeocodeMatch.merged(found, extra, limit: 25))
            searchedFor = text
        } catch {
            // Typing on: the next text searches again. A failure of the transit geocoder still shows Apple's places.
            let extra = scope.filter(await apple)
            guard !Task.isCancelled, !extra.isEmpty else { return }
            results = extra
            searchedFor = text
        }
    }

    #if canImport(MapKit)
    /// Points of interest and addresses from Apple Maps around the visible map; none when Apple has no answer.
    private static func applePlaces(_ text: String?, near: LatLon?, addresses: Bool = true) async -> [GeocodeMatch] {
        guard let text else { return [] }
        let request = MKLocalSearch.Request()
        request.naturalLanguageQuery = text
        request.resultTypes = addresses ? [.pointOfInterest, .address] : [.pointOfInterest]
        if let near {
            request.region = MKCoordinateRegion(center: near.clCoordinate, latitudinalMeters: 20_000, longitudinalMeters: 20_000)
        }
        guard let response = try? await MKLocalSearch(request: request).start() else { return [] }
        return response.mapItems.prefix(12).compactMap { item in
            let c = item.placemark.coordinate
            let point = LatLon(lat: c.latitude, lon: c.longitude)
            guard point.isValidCoordinate else { return nil }
            return TransitMapView.Coordinator.match(item, name: item.name ?? text, at: point)
        }
    }
    #else
    /// Android: the Transitous geocoder already covers OSM places and addresses.
    private static func applePlaces(_ text: String?, near: LatLon?, addresses: Bool = true) async -> [GeocodeMatch] { [] }
    #endif

    /// A place picked on the map: marked where it is (the map does not move) and opened in the place sheet.
    /// The refined version (with address) only updates what is still open.
    private func pick(_ place: GeocodeMatch, refined: Bool) {
        if refined {
            guard focus?.match.id == place.id else { return }
            focus = MapFocus(version: (focus?.version ?? 0) + 1, match: place, moves: false)
            if case .place(let open)? = selection, open.id == place.id { selection = .place(place) }
            return
        }
        searching = false
        focus = MapFocus(version: (focus?.version ?? 0) + 1, match: place, moves: false)
        selection = .place(place)
    }

    /// Flies there and marks it. Stops, addresses and single places open their sheet;
    /// towns and districts are for looking around, so the map stays free.
    private func show(_ m: GeocodeMatch) {
        searching = false
        focus = MapFocus(version: (focus?.version ?? 0) + 1, match: m)
        selection = MapSearch.meters(m) < 2500 ? MapSelection.place(m) : nil
    }

    /// "Route here" from a sheet: plan from the current position, on the Nearby tab under this map.
    private func route(_ place: SavedPlace) {
        selection = nil
        app.goNow(to: place)
        dismiss()
    }

    /// "Route from here": this place as the start in Plan.
    private func routeFrom(_ place: SavedPlace) {
        selection = nil
        app.route(from: place)
        dismiss()
    }
}

/// Map type, 3D and what is drawn on the map. Changes apply right away, the map stays visible above.
struct MapLayersSheet: View {
    @Environment(\.dismiss) var dismiss
    @Binding var base: String
    @Binding var realistic3D: Bool
    @Binding var pointsOfInterest: Bool
    @Binding var traffic: Bool
    @Binding var stations: Bool
    @Binding var lines: Bool
    @Binding var vehicles: Bool
    @Binding var sharing: Bool

    private var current: MapBase { MapBase(storageValue: base) }

    var body: some View {
        NavigationStack {
            List {
                Section("Map") {
                    LazyVGrid(columns: [GridItem(.flexible(), spacing: 10), GridItem(.flexible(), spacing: 10)], spacing: 10) {
                        ForEach(MapBase.allCases) { b in tile(b) }
                    }
                    .listRowInsets(EdgeInsets(top: 10, leading: 12, bottom: 10, trailing: 12))
                    Toggle(isOn: $realistic3D) { Label("3D terrain and buildings", systemImage: "mountain.2") }
                    Toggle(isOn: $pointsOfInterest) { Label("Places of interest", systemImage: "fork.knife") }
                        .disabled(!current.hasLabels)
                    Toggle(isOn: $traffic) { Label("Traffic", systemImage: "car") }
                        .disabled(!current.hasLabels)
                }
                Section {
                    Toggle(isOn: $stations) { Label("Stations", systemImage: "tram.circle") }
                    Toggle(isOn: $lines) { Label("Lines", systemImage: "point.topleft.down.to.point.bottomright.curvepath") }
                    Toggle(isOn: $vehicles) { Label("Vehicles", systemImage: "bus.doubledecker") }
                    Toggle(isOn: $sharing) { Label("Bikes and scooters", systemImage: "bicycle") }
                } header: {
                    Text("On the map")
                } footer: {
                    Text("Satellite maps load more data. In 3D, tilt the map with two fingers.")
                }
            }
            .inkList()
            .navigationTitle("Map layers")
            .navigationBarTitleDisplayMode(.inline)
            .screenTrail("Map layers")
            .toolbar {
                ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } }
            }
        }
    }

    private func tile(_ b: MapBase) -> some View {
        let selected = b == current
        return Button { base = b.rawValue } label: {
            VStack(spacing: 6) {
                Image(systemName: b.symbol).font(.title2)
                    .frame(maxWidth: .infinity, minHeight: 34)
                Text(b.title).font(.caption.weight(selected ? .semibold : .regular))
                    .multilineTextAlignment(.center)
                    .lineLimit(2)
                    .fixedSize(horizontal: false, vertical: true)
            }
            .padding(.vertical, 10)
            .padding(.horizontal, 6)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .foregroundStyle(selected ? Color.onInk : Color.primary)
            .background(selected ? Color.ink : Color.inkFill, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(selected ? AccessibilityTraits.isSelected : [])
        .accessibilityIdentifier("mapBase-\(b.rawValue)")
    }
}

/// A search hit that is not a stop: where it is, how far, and a route there.
struct MapPlaceView: View {
    let match: GeocodeMatch
    let onRoute: (SavedPlace) -> Void
    var onRouteFrom: ((SavedPlace) -> Void)? = nil
    @Environment(LocationManager.self) var location

    var body: some View {
        List {
            Section {
                PlaceLabel(name: match.name, subtitle: match.subtitle, isStop: false, category: match.category)
                if let here = location.coordinate {
                    let meters = here.distance(to: LatLon(lat: match.lat, lon: match.lon))
                    LabeledContent("Distance", value: Fmt.distance(meters: meters))
                    if meters <= 5000 {
                        LabeledContent("On foot", value: L10n.f("%lld min", Reach.walkMinutes(meters: meters)))
                    }
                }
            }
            Section {
                Button { onRoute(SavedPlace(match: match)) } label: {
                    Label("Route here", systemImage: "arrow.triangle.turn.up.right.diamond")
                }
                .accessibilityIdentifier("mapPlaceRoute")
                if let onRouteFrom {
                    Button { onRouteFrom(SavedPlace(match: match)) } label: {
                        Label("Route from here", systemImage: "arrow.up.forward.circle")
                    }
                    .accessibilityIdentifier("mapPlaceRouteFrom")
                }
                Button { openInMaps() } label: {
                    Label(DirectionsApp.current.openLabel, systemImage: "map")
                }
            }
        }
        .inkList()
        .navigationTitle(match.name)
        .navigationBarTitleDisplayMode(.inline)
        .screenTrail("Map place")
    }

    private func openInMaps() {
        DirectionsApp.current.open(LatLon(lat: match.lat, lon: match.lon), name: match.name, mode: .show)
    }
}

/// Sheet for whatever was tapped on the map.
struct MapSelectionView: View {
    let selection: MapSelection
    var onRoute: (SavedPlace) -> Void = { _ in }
    var onRouteFrom: ((SavedPlace) -> Void)? = nil

    var body: some View {
        switch selection {
        case .station(let s):
            StopDeparturesView(stopId: s.id, name: s.name, coordinate: s.coordinate, exact: s.isPole)
        case .vehicle(let v):
            VehicleTripView(vehicle: v)
        case .rentalStation(let s, let provider):
            RentalDetailView(title: s.name, provider: provider, kinds: (s.formFactors ?? []).map(RentalKind.init),
                             available: s.numVehiclesAvailable, link: [s.rentalUriIOS, s.rentalUriWeb])
        case .rentalVehicle(let v, let provider):
            RentalDetailView(title: RentalKind(v.formFactor).title, provider: provider, kinds: [RentalKind(v.formFactor)],
                             available: nil, link: [v.rentalUriIOS, v.rentalUriWeb])
        case .place(let m):
            if m.isStop {
                StopDeparturesView(stopId: m.id, name: m.name, coordinate: LatLon(lat: m.lat, lon: m.lon))
            } else {
                MapPlaceView(match: m, onRoute: onRoute, onRouteFrom: onRouteFrom)
            }
        }
    }
}

/// A shared bike or scooter (or a station of them): who runs it and a link to rent it.
struct RentalDetailView: View {
    let title: String
    let provider: String
    let kinds: [RentalKind]
    let available: Int?
    let link: [String?]

    private var url: URL? { link.compactMap { $0 }.first { !$0.isEmpty }.flatMap(URL.init(string:)) }

    var body: some View {
        List {
            Section {
                LabeledContent("Provider", value: provider)
                if !kinds.isEmpty {
                    LabeledContent("Vehicles", value: Array(Set(kinds.map(\.title))).sorted().joined(separator: ", "))
                }
                if let available { LabeledContent("Available now", value: "\(available)") }
            }
            if let url {
                Section {
                    Link(destination: url) { Label("Open in the provider's app", systemImage: "arrow.up.forward.app") }
                }
            }
            Section {
                EmptyView()
            } footer: {
                Text("Renting needs the provider's app and an account there.")
            }
        }
        .inkList()
        .navigationTitle(title)
        .screenTrail("Map stop")
        .navigationBarTitleDisplayMode(.inline)
    }
}

/// The trip of a vehicle tapped on the map: next stops with live times, and the whole line.
struct VehicleTripView: View {
    let vehicle: LiveVehicle
    @State var leg: Leg?
    @State var errorMessage: String?
    @State var now = Date()

    var body: some View {
        List {
            Section {
                HStack(spacing: 10) {
                    LineBadge(vehicle)
                    VStack(alignment: .leading, spacing: 2) {
                        if let to = leg?.shownDirection { Text(L10n.f("Towards %@", to)).font(.subheadline.weight(.semibold)) }
                        Text(vehicle.realTime ? L10n.t("Live position") : L10n.t("Position from the timetable"))
                            .font(.caption).foregroundStyle(.secondary)
                    }
                }
                if vehicle.delayMinutes >= 1 {
                    Text(L10n.f("%lld min late", vehicle.delayMinutes)).font(.footnote.weight(.semibold)).foregroundStyle(Color.delay)
                }
            }
            if let leg {
                let stops = leg.allStops
                let progress = TripProgress.at(now, stops: stops)
                let next = TripProgress.nextStopIndex(progress, count: stops.count) ?? stops.count
                Section("Next stops") {
                    ForEach(Array(stops.enumerated()).filter { $0.offset >= next }, id: \.offset) { _, stop in
                        HStack {
                            Text(stop.name)
                            Spacer()
                            if let t = stop.arrival ?? stop.departure {
                                Text(Fmt.time(t, tz: stop.tz)).monospacedDigit().foregroundStyle(.secondary)
                            }
                        }
                    }
                }
                if let line = lineRef(leg, next: next) {
                    Section {
                        NavigationLink { LineDetailView(line: line) } label: {
                            Label(L10n.f("Line %@: route and timetable", leg.lineName), systemImage: "point.topleft.down.to.point.bottomright.curvepath")
                        }
                    }
                }
            } else if let errorMessage {
                Text(errorMessage).foregroundStyle(.secondary)
            } else {
                LogoLoader()
            }
        }
        .inkList()
        .ticking(every: 15, now: $now)
        .navigationTitle(vehicle.lineName)
        .screenTrail("Map vehicle")
        .navigationBarTitleDisplayMode(.inline)
        .task {
            do {
                leg = try await TransitousClient.shared.trip(id: vehicle.id).legs.first(where: \.isTransit)
            } catch {
                errorMessage = error.localizedDescription
            }
        }
    }

    private func lineRef(_ leg: Leg, next: Int) -> LineRef? {
        let stops = leg.allStops
        guard !stops.isEmpty else { return nil }
        let anchor = stops[min(next, stops.count - 1)]
        let key = leg.routeId ?? "\(leg.agencyName ?? "")|\(leg.lineName)"
        return LineRef(key: key, mode: leg.mode, routeShortName: leg.routeShortName, displayName: leg.displayName,
                       routeColor: leg.routeColor, routeTextColor: leg.routeTextColor, agencyName: leg.agencyName,
                       headsigns: leg.directionName.map { [$0] } ?? [], stopId: anchor.stopId ?? anchor.stationId,
                       stopName: anchor.name, lat: anchor.lat, lon: anchor.lon)
    }
}
