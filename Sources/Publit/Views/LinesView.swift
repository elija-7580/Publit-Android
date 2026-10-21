import SwiftUI
#if canImport(MapKit)
import MapKit
#endif

/// Lines tab: the lines around you (and saved ones), searchable by line or destination,
/// plus stations by name. Each line opens its route on a map, live vehicles and timetables.
struct LinesView: View {
    @Environment(LocationManager.self) var location
    @Environment(FavoritesStore.self) var favorites
    @State var lines: [LineRef] = []
    @State var stations: [GeocodeMatch] = []
    @State var query = ""
    @State var isLoading = false
    @State var errorMessage: String?
    @AppStorage("linesModes") var storedModes = ""

    /// Lines with a stop within this distance count as "near you".
    static let radius = 1000

    private var filter: ModeFilter { ModeFilter(storageValue: storedModes) }
    private var filterBinding: Binding<ModeFilter> {
        Binding(get: { ModeFilter(storageValue: storedModes) }, set: { storedModes = $0.storageValue })
    }
    private var available: [ModeGroup] {
        let present = Set(lines.map(\.group))
        return ModeGroup.allCases.filter { present.contains($0) || filter.included.contains($0) }
    }
    private var shown: [LineRef] {
        LineCatalog.search(query, in: lines.filter { filter.allows($0.mode) })
    }
    private var savedShown: [SavedLine] {
        let q = LineCatalog.fold(query)
        return favorites.lines.filter { q.isEmpty || LineCatalog.fold($0.lineName).hasPrefix(q) || LineCatalog.fold($0.stopName).contains(q) }
    }

    var body: some View {
        List {
            if !savedShown.isEmpty {
                Section("Saved lines") {
                    ForEach(savedShown) { line in
                        NavigationLink { LineDetailView(line: LineRef(saved: line)) } label: {
                            LineRefRow(line: LineRef(saved: line), subtitle: L10n.f("At %@", line.stopName))
                        }
                    }
                }
            }
            if available.count > 1 || filter.isActive {
                ModeFilterBar(available: available, filter: filterBinding).chipRow()
            }
            Section {
                if location.isDenied && lines.isEmpty {
                    Text("Allow location access to see the lines around you, or search for a station.")
                        .foregroundStyle(.secondary)
                } else if lines.isEmpty {
                    if let errorMessage {
                        Text(errorMessage).foregroundStyle(.secondary)
                    } else if isLoading || !location.hasFix {
                        LogoLoader()
                    } else {
                        Text("No lines found nearby.").foregroundStyle(.secondary)
                    }
                } else if shown.isEmpty {
                    Text(query.isEmpty ? L10n.t("No lines for these modes.") : L10n.t("No line nearby matches. Try a station below."))
                        .foregroundStyle(.secondary)
                }
                ForEach(shown) { line in
                    NavigationLink { LineDetailView(line: line) } label: {
                        LineRefRow(line: line, subtitle: L10n.f("At %@", line.stopName))
                    }
                    .accessibilityIdentifier("lineRow")
                }
            } header: {
                Text(query.isEmpty ? LocalizedStringKey("Lines near you") : LocalizedStringKey("Lines"))
            }
            if !stations.isEmpty {
                Section("Stations") {
                    ForEach(stations) { m in
                        NavigationLink {
                            StopDeparturesView(stopId: m.id, name: m.name, coordinate: LatLon(lat: m.lat, lon: m.lon))
                        } label: {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(m.name)
                                if !m.subtitle.isEmpty { Text(m.subtitle).font(.caption).foregroundStyle(.secondary) }
                            }
                        }
                    }
                }
            }
        }
        .readableWidth()
        .inkList()
        .navigationTitle("Lines")
        .screenTrail("Lines")
        .withSettingsButton()
        .searchable(text: $query, prompt: Text("Line, destination or station"))
        .refreshable { await load(force: true) }
        .task(id: location.hasFix) { await load(force: false) }
        .task(id: query) {
            // Stations by name, after a short pause in typing.
            let q = query.trimmingCharacters(in: .whitespaces)
            guard q.count >= 2 else { stations = []; return }
            try? await Task.sleep(for: .milliseconds(350))
            guard !Task.isCancelled else { return }
            stations = (try? await TransitousClient.shared.geocode(q, near: location.coordinate, type: "STOP")) ?? []
        }
    }

    private func load(force: Bool) async {
        guard let here = location.coordinate else { return }
        // Rounded to ~100 m: walking around does not ask again (the answer is cached for 30 min).
        let center = LatLon(lat: (here.lat * 1000).rounded() / 1000, lon: (here.lon * 1000).rounded() / 1000)
        isLoading = true
        defer { isLoading = false }
        do {
            let res = try await TransitousClient.shared.departures(
                center: center, radius: Self.radius, time: .now, window: 3600, count: 100, maxAge: force ? 0 : 1800)
            let stopTimes = res.stopTimes
            lines = await Task.detached(priority: .userInitiated) { LineCatalog.build(stopTimes, around: center) }.value
            errorMessage = nil
        } catch {
            if !(error is CancellationError) && (error as? URLError)?.code != .cancelled { errorMessage = error.localizedDescription }
        }
    }
}

/// Badge, destinations and where the line stops.
struct LineRefRow: View {
    let line: LineRef
    let subtitle: String?

    var body: some View {
        HStack(spacing: 12) {
            LineBadge(line).frame(minWidth: 56, alignment: .leading)
            VStack(alignment: .leading, spacing: 2) {
                if !line.headsigns.isEmpty {
                    Text(line.headsigns.prefix(2).map { "→ \($0)" }.joined(separator: "\n"))
                        .font(.subheadline).lineLimit(2)
                }
                if let subtitle { Text(subtitle).font(.caption).foregroundStyle(.secondary).lineLimit(1) }
            }
        }
        .accessibilityElement(children: .combine)
    }
}

/// One line: its route on a map with the vehicles on it now, the stops in order, alerts,
/// and a timetable for every stop.
struct LineDetailView: View {
    @State var isVisible = false
    @Environment(\.scenePhase) var scenePhase
    @Environment(AppState.self) var app
    let line: LineRef
    var initialDepartures: [StopTime] = []
    @Environment(FavoritesStore.self) var favorites
    @State var directions: [LineDirection] = []
    @State var directionId: String?
    @State var legs: [String: Leg] = [:]
    /// Route shape with the stops placed on it, worked out once per direction (off the main thread).
    @State var patterns: [String: LineVehicles.Pattern] = [:]
    @State var alerts: [Alert] = []
    @State var vehicleDepartures: [StopTime] = []
    @State var errorMessage: String?
    @State var loaded = false

    private var direction: LineDirection? { directions.first { $0.id == directionId } ?? directions.first }
    private var leg: Leg? { direction.flatMap { legs[$0.id] } }
    private var savedLine: SavedLine { SavedLine(line: line) }

    var body: some View {
        List {
            Section {
                HStack(spacing: 10) {
                    LineBadge(line)
                    if let agency = line.agencyName { Text(agency).font(.subheadline).foregroundStyle(.secondary) }
                }
                if directions.count > 1 {
                    Picker("Direction", selection: Binding(get: { direction?.id ?? "" }, set: { directionId = $0 })) {
                        ForEach(directions.prefix(4)) { d in Text(verbatim: "→ \(d.headsign)").tag(d.id) }
                    }
                    .pickerStyle(.inline)
                    .labelsHidden()
                    .accessibilityIdentifier("lineDirection")
                } else if let d = direction {
                    Text(L10n.f("Towards %@", d.headsign)).font(.subheadline.weight(.semibold))
                }
            }
            if !alerts.isEmpty { Section { ServiceAlertsView(alerts: alerts) } }
            if let leg {
                let stops = leg.allStops
                let pattern = direction.flatMap { patterns[$0.id] }
                Section {
                    Clocked(every: Energy.interval(5)) { now in
                        LineMap(line: line, shape: leg.legGeometry.mapPoints, stops: stops,
                                vehicles: pattern.map { p in
                                    LineVehicles.estimate(p, at: p.index(of: line.stopId, near: line.coordinate),
                                                          departures: vehicleDepartures, now: now)
                                } ?? [])
                    }
                    .frame(height: 280)
                    .listRowInsets(EdgeInsets())
                } footer: {
                    Text("Vehicle positions are estimated from live departure times.")
                }
                Section {
                    ForEach(Array(stops.enumerated()), id: \.offset) { i, stop in
                        NavigationLink {
                            LineTimetableView(line: line, stopId: stop.stopId ?? stop.stationId, stopName: stop.name,
                                              direction: direction, tz: stop.tz)
                        } label: {
                            LineStopRow(stop: stop, index: i, count: stops.count, color: line.lineStroke,
                                        minutes: minutesFromStart(stop, first: stops[0]),
                                        isHere: stop.stopId == line.stopId || stop.parentId == line.stopId || stop.name == line.stopName)
                        }
                        .accessibilityIdentifier("lineStop")
                    }
                } header: {
                    Text(L10n.f("%lld stops", stops.count))
                } footer: {
                    Text("Tap a stop for its timetable.")
                }
            } else if let errorMessage {
                Text(errorMessage).foregroundStyle(.secondary)
            } else if loaded && directions.isEmpty {
                Text("No departures of \(line.lineName) in the next hours.").foregroundStyle(.secondary)
            } else {
                LogoLoader()
            }
        }
        .readableWidth()
        .inkList()
        .navigationTitle(line.lineName)
        .screenTrail("Line")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button { favorites.toggle(savedLine) } label: {
                    Label(favorites.containsLine(savedLine.id) ? L10n.t("Saved line") : L10n.t("Save line"),
                          systemImage: favorites.containsLine(savedLine.id) ? "star.fill" : "star")
                }
            }
        }
        .task {
            if !initialDepartures.isEmpty {
                directions = LineDirection.from(initialDepartures, matching: line.key)
                loaded = true
            }
            await loadDirections()
        }
        .onAppear { isVisible = true }
        .onDisappear { isVisible = false }
        .task(id: "\(direction?.id ?? "-")|\(scenePhase)|\(app.tab)|\(isVisible)") {
            guard isVisible, scenePhase == .active, let d = direction else { return }
            await loadRoute(d)
            while !Task.isCancelled {
                await loadVehicles(d)
                try? await Task.sleep(for: Energy.duration(30))
            }
        }
    }

    private func minutesFromStart(_ stop: Place, first: Place) -> Int? {
        guard let t0 = first.departure ?? first.arrival, let t = stop.arrival ?? stop.departure else { return nil }
        return Int((t.timeIntervalSince(t0) / 60).rounded())
    }

    private func loadDirections() async {
        do {
            let res = try await TransitousClient.shared.departures(stopId: line.stopId, count: 100,
                                                                  radius: StopDeparturesView.stationRadius)
            try Task.checkCancellation()
            let mine = StopTime.upcoming(res.stopTimes).filter(line.matches)
            directions = LineDirection.from(mine, matching: line.key)
            // Prefer the direction the line was opened with (e.g. from a vehicle on the map).
            if directionId == nil, let h = line.headsigns.first, let d = directions.first(where: { $0.headsign == h }) {
                directionId = d.id
            }
            alerts = Alert.relevant(mine.flatMap(\.serviceAlerts).reduce(into: [Alert]()) { acc, a in
                if !acc.contains(where: { $0.headerText == a.headerText }) { acc.append(a) }
            })
            errorMessage = nil
        } catch {
            if !(error is CancellationError) { errorMessage = error.localizedDescription }
        }
        loaded = true
    }

    private func loadRoute(_ d: LineDirection) async {
        guard legs[d.id] == nil else { return }
        do {
            if let leg = try await TransitousClient.shared.trip(id: d.sampleTripId).legs.first(where: \.isTransit) {
                let stops = leg.allStops, shape = leg.legGeometry.mapPoints
                let pattern = await Task.detached(priority: .userInitiated) { LineVehicles.Pattern(stops: stops, shape: shape) }.value
                try Task.checkCancellation()
                legs[d.id] = leg
                patterns[d.id] = pattern
            }
        } catch {
            if !(error is CancellationError) { errorMessage = error.localizedDescription }
        }
    }

    /// Departures at this line's stop over the time the line takes end to end: each is a vehicle on the route now.
    private func loadVehicles(_ d: LineDirection) async {
        guard let p = patterns[d.id] else { return }
        let index = p.index(of: line.stopId, near: line.coordinate)
        let w = LineVehicles.window(p, at: index, now: .now)
        let modes = ModeGroup.of(line.mode).apiModes.joined(separator: ",")
        guard let res = try? await TransitousClient.shared.departures(
            stopId: line.stopId, radius: StopDeparturesView.stationRadius, time: w.start, window: w.seconds,
            modes: modes, count: 30, maxAge: 25) else { return }
        vehicleDepartures = res.stopTimes.filter { line.matches($0) && LineDirection.groupId($0) == d.id }
    }
}

/// The route as a coloured line with its stops and the vehicles on it.
struct LineMap: View {
    let line: LineRef
    let shape: [LatLon]
    let stops: [Place]
    let vehicles: [LineVehicles.Vehicle]

    #if os(Android)
    var body: some View {
        var content = GoogleMapContent(lines: [.init(points: shape, color: line.lineStroke, width: 5)])
        content.pins = stops.map { .init(coordinate: $0.coordinate, kind: .dot(color: .white, border: line.lineStroke, size: 8, borderWidth: 2)) }
        content.pins += vehicles.map { .init(coordinate: $0.coordinate, kind: .marker(title: "\(line.lineName) · \($0.nextStop)", color: line.lineStroke)) }
        return GoogleMapView(content: content, camera: .fit(shape.isEmpty ? stops.map(\.coordinate) : shape))
    }
    #else
    var body: some View {
        Map(initialPosition: .automatic) {
            UserAnnotation()
            MapPolyline(coordinates: shape.map(\.clCoordinate))
                .stroke(line.lineStroke, style: StrokeStyle(lineWidth: 5, lineCap: .round, lineJoin: .round))
            ForEach(Array(stops.enumerated()), id: \.offset) { _, s in
                Annotation("", coordinate: s.coordinate.clCoordinate, anchor: .center) {
                    Circle().fill(.white).frame(width: 8, height: 8)
                        .overlay(Circle().stroke(line.lineStroke, lineWidth: 2))
                }
            }
            ForEach(vehicles) { v in
                Annotation(v.nextStop, coordinate: v.coordinate.clCoordinate, anchor: .center) {
                    LineBadge(line, compact: true)
                        .shadow(radius: 2)
                        .accessibilityLabel(L10n.f("%@ to %@, next stop %@", line.lineName, line.headsigns.first ?? "", v.nextStop))
                }
                .annotationTitles(.hidden)
            }
        }
        .publitMapStyle()
        .mapControls { MapCompass() }
    }
    #endif
}

/// A stop on the line's route: a dot on a vertical line in the line colour, the name and minutes from the start.
struct LineStopRow: View {
    let stop: Place
    let index: Int
    let count: Int
    let color: Color
    let minutes: Int?
    let isHere: Bool

    var body: some View {
        HStack(spacing: 12) {
            ZStack {
                Rectangle().fill(color)
                    .frame(width: 4)
                    .padding(.top, index == 0 ? 22 : 0)
                    .padding(.bottom, index == count - 1 ? 22 : 0)
                Circle().fill(isHere ? color : .white).frame(width: 12, height: 12)
                    .overlay(Circle().stroke(color, lineWidth: 3))
            }
            .frame(width: 16)
            .frame(maxHeight: .infinity)
            Text(stop.name).font(isHere ? .body.weight(.semibold) : .body)
            Spacer()
            if let minutes, index > 0 {
                Text(L10n.f("+%lld min", minutes)).font(.caption).monospacedDigit().foregroundStyle(.secondary)
            }
        }
        .padding(.vertical, -4)
    }
}

/// All departures of one line at one stop on a day, by hour (scheduled times).
struct LineTimetableView: View {
    let line: LineRef
    let stopId: String
    let stopName: String
    let direction: LineDirection?
    let tz: String?

    @State var day = Calendar.current.startOfDay(for: .now)
    @State var table: LineTimetable?
    @State var errorMessage: String?

    private var zone: TimeZone { Fmt.timeZone(tz) }

    var body: some View {
        List {
            Section {
                DatePicker("Day", selection: $day, displayedComponents: .date)
                if let d = direction { LabeledContent("Direction", value: d.headsign) }
            }
            if let table {
                if table.isEmpty {
                    Text("No departures on this day.").foregroundStyle(.secondary)
                        .accessibilityIdentifier("lineTimetable")
                } else {
                    Section {
                        ForEach(table.rows) { row in
                            HStack(alignment: .firstTextBaseline, spacing: 14) {
                                Text(String(format: "%02d", row.hour)).font(.body.weight(.bold)).monospacedDigit()
                                    .frame(width: 30, alignment: .leading)
                                FlowRow(spacing: 10) {
                                    ForEach(row.minutes, id: \.self) { m in
                                        Text(String(format: "%02d", m)).monospacedDigit()
                                            .foregroundStyle(isPast(row.hour, m) ? .secondary : .primary)
                                    }
                                }
                            }
                            .accessibilityElement(children: .combine)
                            .accessibilityIdentifier("lineTimetable")
                        }
                    } header: {
                        Text(L10n.f("%lld departures", table.count))
                    } footer: {
                        Text("Scheduled times. Live delays are on the departure board.")
                    }
                }
            } else if let errorMessage {
                Text(errorMessage).foregroundStyle(.secondary)
            } else {
                LogoLoader()
            }
        }
        .readableWidth()
        .inkList()
        .navigationTitle(stopName)
        .screenTrail("Line stop")
        .navigationBarTitleDisplayMode(.inline)
        .task(id: day) { await load() }
    }

    private func isPast(_ hour: Int, _ minute: Int) -> Bool {
        var cal = Calendar(identifier: .gregorian)
        cal.timeZone = zone
        guard cal.isDate(day, inSameDayAs: .now) else { return day < .now }
        let now = cal.dateComponents([.hour, .minute], from: .now)
        return (hour, minute) < (now.hour ?? 0, now.minute ?? 0)
    }

    private func load() async {
        table = nil
        var cal = Calendar(identifier: .gregorian)
        cal.timeZone = zone
        let local = Calendar.current.dateComponents([.year, .month, .day], from: day)
        guard let start = cal.date(from: local), let end = cal.date(byAdding: .day, value: 1, to: start) else { return }
        do {
            // A whole day at a busy station is large; only this mode, cached for hours (timetables rarely change).
            let res = try await TransitousClient.shared.departures(
                stopId: stopId, time: start, window: 24 * 3600, modes: ModeGroup.of(line.mode).apiModes.joined(separator: ","),
                count: 20, maxAge: 6 * 3600)
            let key = line.key, dir = direction?.id, stopTimes = res.stopTimes, z = zone
            table = await Task.detached(priority: .userInitiated) {
                LineTimetable.build(stopTimes, key: key, direction: dir, from: start, to: end, timeZone: z)
            }.value
            errorMessage = nil
        } catch {
            if !(error is CancellationError) { errorMessage = error.localizedDescription }
        }
    }
}
