import SwiftUI
#if canImport(MapKit)
import MapKit
#endif

struct CompareMapView: View, Equatable {
    let itineraries: [Itinerary]
    let selectedId: String?

    static func == (a: CompareMapView, b: CompareMapView) -> Bool {
        a.selectedId == b.selectedId && a.itineraries.map(\.routeKey) == b.itineraries.map(\.routeKey)
    }

    /// Selected route is drawn last so it sits on top.
    private var ordered: [Itinerary] {
        itineraries.filter { $0.routeKey != selectedId } + itineraries.filter { $0.routeKey == selectedId }
    }

    @AppStorage(MapProvider.defaultsKey) var mapProvider = MapProvider.apple.rawValue

    var body: some View {
        #if os(Android)
            GoogleMapView(content: googleContent, camera: .fit(ordered.flatMap { $0.legs.flatMap(\.legGeometry.mapPoints) }))
        #elseif canImport(GoogleMaps)
        if MapProvider.usesGoogle(mapProvider) {
            GoogleMapView(content: googleContent, camera: .fit(ordered.flatMap { $0.legs.flatMap(\.legGeometry.mapPoints) }))
        } else {
            appleMap
        }
        #else
        appleMap
        #endif
    }

    private var googleContent: GoogleMapContent {
        GoogleMapContent(lines: ordered.flatMap { it in
            let isSelected = it.routeKey == selectedId
            return it.legs.map { leg in
                GoogleMapContent.Line(points: leg.overviewPath,
                                      color: isSelected ? (leg.isTransit ? leg.lineStroke : Color.routeAhead) : Color.gray.opacity(0.45),
                                      width: isSelected ? (leg.isTransit ? 5 : 4) : 3)
            }
        })
    }

#if !os(Android)
    private var appleMap: some View {
        Map(initialPosition: .automatic) {
            UserAnnotation()
            ForEach(ordered, id: \.routeKey) { it in
                let isSelected = it.routeKey == selectedId
                ForEach(Array(it.legs.enumerated()), id: \.offset) { _, leg in
                    MapPolyline(coordinates: leg.overviewPath.map(\.clCoordinate))
                        .stroke(isSelected ? (leg.isTransit ? leg.lineStroke : Color.routeAhead) : Color.gray.opacity(0.45),
                                style: .leg(leg.isTransit, width: isSelected ? 5 : 3))
                }
            }
        }
        .publitMapStyle()
    }
#endif
}

struct ItineraryDetailView: View {
    @Environment(TripStore.self) var trips
    /// False once this screen is popped or dismissed (still true while a child screen is pushed).
    @Environment(\.isPresented) var isPresented
    let itinerary: Itinerary
    var fromName: String = ""
    var toName: String = ""
    /// Live state of a saved trip ("On time", "Leave in 6 min", …), evaluated every 30 s.
    /// Only the banner redraws on that timer, not the map and the steps.
    var statusAt: ((Date) -> TripStatus)? = nil
    var refreshedAt: Date? = nil
    @State var showsMap = false
    @State var mapReady = false
    /// The screen opens with the top (time, lines) at once; changes, actions and steps follow a
    /// moment later, so a long trip never leaves the tap without an answer.
    @State var showsAll = false

    private var isBikeOrCar: Bool {
        let m = StreetComparison.streetMode(itinerary)
        return m == "BIKE" || m == "CAR"
    }

    private var resolvedFrom: String { fromName.isEmpty ? Self.placeName(itinerary.legs.first?.from) : fromName }
    private var resolvedTo: String { toName.isEmpty ? Self.placeName(itinerary.legs.last?.to) : toName }

    /// MOTIS names coordinate endpoints "START"/"END".
    static func placeName(_ p: Place?) -> String {
        guard let p, p.name != "START", p.name != "END" else { return L10n.t("Destination") }
        return p.name
    }

    var body: some View {
        let warnings = TransferRisk.warnings(for: itinerary)
        List {
            // 1. What matters first: the way at a glance, when, how long, which lines, live state and warnings.
            Section {
                Button { showsMap = true } label: {
                    ZStack {
                        Color.surface
                        // Drawn a moment after the screen opens: building a map with a long route
                        // made the push wait.
                        if mapReady { RouteMap(itinerary: itinerary, isPreview: true).transition(.opacity) }
                    }
                    .frame(height: 200)
                    .allowsHitTesting(false)
                    .overlay(alignment: .bottomTrailing) {
                        Image(systemName: "arrow.up.left.and.arrow.down.right")
                            .font(.body.weight(.semibold))
                            .frame(width: 40, height: 40)
                            .matteSurface(in: Circle())
                            .mapShadow()
                            .padding(10)
                    }
                }
                .buttonStyle(.plain)
                .listRowInsets(EdgeInsets())
                .listRowSeparator(.hidden)
                .accessibilityLabel(Text("Trip overview"))
                .accessibilityIdentifier("routePreviewMap")
            }
            .task {
                guard !mapReady || !showsAll else { return }
                try? await Task.sleep(for: .milliseconds(60))
                showsAll = true
                try? await Task.sleep(for: .milliseconds(300))
                withAnimation(.easeOut(duration: 0.2)) { mapReady = true }
            }

            Section {
                if let statusAt {
                    Clocked(every: 30) { now in
                        StatusBanner(status: statusAt(now), refreshedAt: refreshedAt)
                    }
                }
                Clocked(every: 30) { now in LeaveHero(itinerary: itinerary, now: now, framed: false) }
                    .listRowSeparator(.hidden)
                // The chain of lines once; where to get on and off is in the steps below (each opens for its stops).
                RouteSummary(itinerary: itinerary, showsTimes: false)
                    .listRowSeparator(.hidden, edges: .top)
                ForEach(TransitRegion.along(itinerary), id: \.network) { RegionTicketRow(region: $0) }
                if let note = Fmt.foreignZoneNote(tz: itinerary.legs.first?.from.tz, at: itinerary.startTime) {
                    Label(note, systemImage: "clock").font(.footnote).foregroundStyle(.secondary)
                }
                if StreetComparison.streetMode(itinerary) == "CAR" {
                    Label("Driving times are without traffic and parking.", systemImage: "info.circle")
                        .font(.footnote).foregroundStyle(.secondary)
                }
                if let mode = StreetComparison.streetMode(itinerary), let start = itinerary.legs.first?.from,
                   let end = itinerary.legs.last?.to {
                    let app = DirectionsApp.current
                    Button {
                        app.open(end.coordinate, name: resolvedTo, mode: DirectionsApp.mode(street: mode),
                                 from: (start.coordinate, resolvedFrom))
                    } label: {
                        Label(app.openLabel, systemImage: "arrow.up.forward.app").frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.soft)
                    .accessibilityIdentifier("openStreetRouteInMaps")
                }
            }

            if !showsAll {
                Section {
                    LogoLoader().frame(maxWidth: .infinity)
                }
            } else {
            // Warnings in one calm form: a coloured edge, the words in ink.
            if !warnings.isEmpty {
                Section {
                    ForEach(warnings, id: \.self) { w in
                        Notice(level: w.level == .missed ? .critical : .change, title: w.message)
                    }
                }
            }

            // How sure each change is and what takes over if it breaks (smart routing).
            SmartChangesSection(itinerary: itinerary)

            // 2. What you can do with it, right below: start, save, calendar, reminder.
            // Cycling and driving are shown for comparison only: no trip, reminder or fares.
            if !isBikeOrCar {
                TripActionsSection(itinerary: itinerary, fromName: resolvedFrom, toName: resolvedTo)
            }

            // 3. The way, step by step, as a timeline: times on the left, the line in its colour.
            Section("Steps") {
                ForEach(Array(itinerary.legs.enumerated()), id: \.offset) { _, leg in
                    TimelineLegRow(leg: leg)
                }
                TimelineEndRow(time: itinerary.endTime, tz: itinerary.legs.last?.to.tz, name: resolvedTo)
            }

            if !isBikeOrCar { FareSection(itinerary: itinerary) }

            Section {
            } footer: {
                Text("All information without guarantee. Times, platforms and prices come from the operators' open data and can change at short notice.")
                    .accessibilityIdentifier("noGuaranteeNote")
            }
            }
        }
        .readableWidth()
        .inkList()
        .sheet(isPresented: $showsMap) {
            NavigationStack {
                RouteMap(itinerary: itinerary)
                    .ignoresSafeArea(edges: .bottom)
                    .navigationTitle(Text("Trip overview"))
                    .navigationBarTitleDisplayMode(.inline)
                    .toolbar {
                        ToolbarItem(placement: .confirmationAction) { Button("Done") { showsMap = false } }
                    }
            }
        }
        .navigationTitle("Route")
        .screenTrail("Route")
        .navigationBarTitleDisplayMode(.inline)
        // Tickets only for a saved trip: looking at a route shows none.
        .showPassToolbar(tripId: trips.saved(itinerary)?.id, at: itinerary.startTime,
                         enabled: Features.passes && trips.saved(itinerary) != nil)
        // A trip unsaved here is only deleted when the screen closes, not when a detail is pushed.
        .onDisappear { if !isPresented { trips.discardPendingRemovalsLater() } }
        .onChange(of: isPresented) { _, presented in if !presented { trips.discardPendingRemovalsLater() } }
    }
}

/// Time, duration and the chain of lines, e.g. 🚶 › S8 › U6 › 🚶.
struct RouteSummary: View {
    let itinerary: Itinerary
    /// Off under the leave card, which already shows them.
    var showsTimes = true
    #if os(Android)
    var typeSize: DynamicTypeSize { AndroidEnvironment.dynamicTypeSize }
    #else
    @Environment(\.dynamicTypeSize) var typeSize
    #endif
    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            // Chosen by text size, not ViewThatFits: measuring both layouts in a self-sizing row
            // can make the row's height flip between passes.
            if showsTimes {
                if typeSize.isAccessibilitySize {
                    stackedTimes
                } else {
                    HStack(alignment: .firstTextBaseline) {
                        Text("\(Fmt.time(itinerary.startTime, tz: itinerary.legs.first?.from.tz)) – \(Fmt.time(itinerary.endTime, tz: itinerary.legs.last?.to.tz))")
                            .font(.title2.weight(.bold).monospacedDigit())
                            .lineLimit(1).minimumScaleFactor(0.7)
                        Spacer(minLength: 8)
                        DurationFigure(seconds: itinerary.duration)
                            .lineLimit(1).fixedSize()
                    }
                }
            }
            legChain
            HStack(spacing: 6) {
                Text(Fmt.changes(itinerary.transfers))
                if itinerary.hasRealtime && !Connectivity.shared.isOffline {
                    Text(verbatim: "·")
                    Label("Live", systemImage: "dot.radiowaves.left.and.right")
                        .foregroundStyle(Color.live)
                }
                if itinerary.isCancelled {
                    Text(verbatim: "·")
                    Text("Cancelled").foregroundStyle(Color.danger)
                }
            }
            .font(.subheadline).foregroundStyle(.secondary)
        }
        .padding(.vertical, 4)
        .accessibilityElement(children: .combine)
    }

    private var stackedTimes: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text("\(Fmt.time(itinerary.startTime, tz: itinerary.legs.first?.from.tz)) – \(Fmt.time(itinerary.endTime, tz: itinerary.legs.last?.to.tz))")
                .font(.title2.bold().monospacedDigit())
            DurationFigure(seconds: itinerary.duration)
        }
    }

    private var legChain: some View {
        // Short walks between platforms are noise in the overview; the steps list keeps them.
        let legs = itinerary.legs.filter { $0.isTransit || $0.duration >= 120 }
        return FlowRow(spacing: 6) {
            ForEach(Array(legs.enumerated()), id: \.offset) { _, leg in
                // No arrows between them: once the chain wraps, a line starting with "›" read as noise.
                if leg.isTransit {
                    LineBadge(leg)
                } else {
                    HStack(spacing: 2) {
                        Image(systemName: ModeStyle.symbol(leg.mode))
                        Text(verbatim: "\(max(1, leg.duration / 60))")
                    }
                    .font(.caption.weight(.semibold).monospacedDigit()).foregroundStyle(.secondary)
                    .padding(.horizontal, 6).frame(minHeight: 24)
                    .background(Color.inkFill, in: Capsule())
                }
            }
        }
    }
}

/// Wraps its children onto new lines when they do not fit (long routes with many changes).
struct FlowRow: Layout {
    var spacing: CGFloat = 4

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let maxWidth = proposal.width ?? .infinity
        let frames = arrange(subviews, maxWidth: maxWidth)
        let width = frames.map(\.maxX).max() ?? 0
        let height = frames.map(\.maxY).max() ?? 0
        return CGSize(width: min(width, maxWidth), height: height)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        // The same arrangement as when measuring, so rows never differ between the two.
        let frames = arrange(subviews, maxWidth: proposal.width ?? bounds.width)
        for (v, f) in zip(subviews, frames) {
            v.place(at: CGPoint(x: bounds.minX + f.minX, y: bounds.minY + f.minY), proposal: ProposedViewSize(f.size))
        }
    }

    private func arrange(_ subviews: Subviews, maxWidth: CGFloat) -> [CGRect] {
        var frames: [CGRect] = []
        var rows: [Range<Int>] = []
        var rowStart = 0
        var x: CGFloat = 0, y: CGFloat = 0, rowHeight: CGFloat = 0
        for v in subviews {
            let s = v.sizeThatFits(.unspecified)
            if x > 0, x + s.width > maxWidth {
                rows.append(rowStart..<frames.count); rowStart = frames.count
                x = 0; y += rowHeight + spacing; rowHeight = 0
            }
            frames.append(CGRect(origin: CGPoint(x: x, y: y), size: s))
            x += s.width + spacing
            rowHeight = max(rowHeight, s.height)
        }
        rows.append(rowStart..<frames.count)
        // Small pieces (a walk's minutes) sit centred next to taller line badges, not at the top.
        for r in rows where !r.isEmpty {
            let h = frames[r].map(\.height).max() ?? 0
            for i in r { frames[i].origin.y += (h - frames[i].height) / 2 }
        }
        return frames
    }
}

extension StrokeStyle {
    /// Route lines on maps: always solid. Walks are told apart by colour (`Color.routeAhead`) and a
    /// slightly thinner line; the dotted walks of earlier builds broke up or vanished on the map.
    static func leg(_ isTransit: Bool, width: CGFloat) -> StrokeStyle {
        StrokeStyle(lineWidth: isTransit ? width : max(3, width - 1), lineCap: .round, lineJoin: .round)
    }
}

/// The whole route on a map, with start and destination.
struct RouteMap: View {
    let itinerary: Itinerary
    /// The small preview at the top of a route: no location dot, nothing to steer.
    var isPreview = false
    @AppStorage(MapProvider.defaultsKey) var mapProvider = MapProvider.apple.rawValue

    /// Where the route changes vehicle: a small mark, so the stops read at a glance.
    private var changePoints: [LatLon] {
        let rides = itinerary.legs.filter(\.isTransit)
        return rides.dropFirst().map(\.from.coordinate)
    }

    var body: some View {
        #if os(Android)
            GoogleMapView(content: googleContent, camera: .fit(Self.framePoints(itinerary)))
        #elseif canImport(GoogleMaps)
        if MapProvider.usesGoogle(mapProvider) {
            GoogleMapView(content: googleContent, camera: .fit(Self.framePoints(itinerary)))
        } else {
            appleMap
        }
        #else
        appleMap
        #endif
    }

    /// The route alone frames the map: not where you are now (which zoomed out to the whole country).
    static func framePoints(_ it: Itinerary) -> [LatLon] {
        it.legs.flatMap { $0.overviewPath + [$0.from.coordinate, $0.to.coordinate] }
    }

    /// The route's bounding box with a margin, so the ends never sit under the map's edge or buttons.
    static func frame(_ it: Itinerary, margin: Double = 0.18) -> MKMapRect? {
        let points = framePoints(it).map { MKMapPoint($0.clCoordinate) }
        guard let first = points.first else { return nil }
        var minX = first.x, maxX = first.x, minY = first.y, maxY = first.y
        for p in points { minX = min(minX, p.x); maxX = max(maxX, p.x); minY = min(minY, p.y); maxY = max(maxY, p.y) }
        // At least ~400 m across, so a short walk is not zoomed in to the pavement.
        let minSide = 400 * MKMapPointsPerMeterAtLatitude(first.coordinate.latitude)
        let w = max(maxX - minX, minSide), h = max(maxY - minY, minSide)
        let cx = (minX + maxX) / 2, cy = (minY + maxY) / 2
        let pw = w * (1 + 2 * margin), ph = h * (1 + 2 * margin)
        return MKMapRect(x: cx - pw / 2, y: cy - ph / 2, width: pw, height: ph)
    }

    private var googleContent: GoogleMapContent {
        var content = GoogleMapContent(lines: itinerary.legs.map {
            GoogleMapContent.Line(points: $0.overviewPath, color: $0.isTransit ? $0.lineStroke : Color.routeAhead,
                                  width: $0.isTransit ? 5 : 4)
        })
        for p in changePoints {
            content.pins.append(.init(coordinate: p, kind: .dot(color: .white, border: Color.inkAction, size: 10, borderWidth: 2)))
        }
        if let first = itinerary.legs.first {
            content.pins.append(.init(coordinate: first.from.coordinate,
                                      kind: .dot(color: .white, border: Color.inkAction, size: 16, borderWidth: 4)))
        }
        if let last = itinerary.legs.last {
            content.pins.append(.init(coordinate: last.to.coordinate,
                                      kind: .dot(color: Color.inkAction, border: .white, size: 18, borderWidth: 3)))
        }
        return content
    }

#if !os(Android)
    private var appleMap: some View {
        let start: MapCameraPosition = Self.frame(itinerary).map { MapCameraPosition.rect($0) } ?? .automatic
        return Map(initialPosition: start, interactionModes: isPreview ? [] : .all) {
            if !isPreview { UserAnnotation() }
            ForEach(Array(itinerary.legs.enumerated()), id: \.offset) { _, leg in
                MapPolyline(coordinates: leg.overviewPath.map(\.clCoordinate))
                    .stroke(leg.isTransit ? leg.lineStroke : Color.routeAhead, style: .leg(leg.isTransit, width: isPreview ? 4 : 5))
            }
            ForEach(Array(changePoints.enumerated()), id: \.offset) { _, p in
                Annotation("", coordinate: p.clCoordinate, anchor: .center) { RouteMark(kind: .change) }
            }
            if let first = itinerary.legs.first {
                Annotation("", coordinate: first.from.coordinate.clCoordinate, anchor: .center) { RouteMark(kind: .start) }
            }
            if let last = itinerary.legs.last {
                Annotation(isPreview ? "" : ItineraryDetailView.placeName(last.to),
                           coordinate: last.to.coordinate.clCoordinate, anchor: .center) { RouteMark(kind: .destination) }
            }
        }
        .publitMapStyle()
        .mapControlVisibility(isPreview ? .hidden : .automatic)
        .mapControls { MapUserLocationButton(); MapCompass(); MapScaleView() }
    }
#endif
}

/// The logo's marks on a map: a ring where the trip starts, the ink dot where it ends,
/// small rings where you change.
struct RouteMark: View {
    enum Kind { case start, change, destination }
    let kind: Kind

    var body: some View {
        switch kind {
        case .start:
            Circle().fill(.white).frame(width: 16, height: 16)
                .overlay(Circle().strokeBorder(Color.black.opacity(0.85), lineWidth: 4))
                .shadow(color: .black.opacity(0.35), radius: 2, y: 1)
        case .change:
            Circle().fill(.white).frame(width: 10, height: 10)
                .overlay(Circle().strokeBorder(Color.black.opacity(0.75), lineWidth: 2))
        case .destination:
            Circle().fill(Color.black.opacity(0.9)).frame(width: 18, height: 18)
                .overlay(Circle().strokeBorder(.white, lineWidth: 3))
                .shadow(color: .black.opacity(0.35), radius: 2, y: 1)
        }
    }
}

struct LegRow: View {
    let leg: Leg
    /// Its own coloured bar on the left; off inside the timeline, which draws the line itself.
    var bar = true

    var body: some View {
        if leg.isTransit {
            VStack(alignment: .leading, spacing: 10) {
                HStack(alignment: .firstTextBaseline, spacing: 8) {
                    LineBadge(leg)
                    if let detail = leg.serviceDetail {
                        Text(verbatim: detail).font(.caption.weight(.semibold))
                    }
                    Spacer(minLength: 4)
                    DurationFigure(seconds: leg.duration, size: 22)
                }
                DirectionLabel(direction: leg.shownDirection, circular: leg.isCircular)
                if leg.isReplacementBus { Label("Rail replacement bus", systemImage: "bus.fill").font(.subheadline.weight(.semibold)).foregroundStyle(Color.delay) }
                stopLine(label: "Board here", time: leg.startTime, delay: leg.delayMinutes,
                         name: leg.from.name, track: leg.from.track, tz: leg.from.tz)
                HStack(spacing: 6) {
                    if let stops = leg.intermediateStops { Text(Fmt.stops(stops.count)); Text(verbatim: "·") }
                    Text(leg.realTime && !Connectivity.shared.isOffline ? [L10n.t("Live"), leg.liveSource].compactMap { $0 }.joined(separator: " · ") : L10n.t("Timetable"))
                }
                .font(.caption).foregroundStyle(.secondary)
                stopLine(label: "Get off here", time: leg.endTime,
                         delay: Int((leg.endTime.timeIntervalSince(leg.scheduledEndTime) / 60).rounded()),
                         name: leg.to.name, track: leg.to.track, tz: leg.to.tz)
                if leg.cancelled == true {
                    Text("This trip is cancelled.").font(.caption.weight(.semibold)).foregroundStyle(Color.danger)
                }
                ServiceAlertsView(alerts: leg.serviceAlerts)
                GetOffButton(leg: leg)
            }
            .padding(.vertical, 6)
            .padding(.leading, bar ? 12 : 0)
            .overlay(alignment: .leading) {
                if bar {
                    RoundedRectangle(cornerRadius: 2).fill(leg.lineStroke).frame(width: 4).padding(.vertical, 6)
                        .accessibilityHidden(true)
                }
            }
            .accessibilityIdentifier("transitLeg")
        } else {
            HStack(alignment: .top, spacing: 8) {
                Image(systemName: leg.rental.map { $0.kind.symbol } ?? ModeStyle.symbol(leg.mode))
                    .foregroundStyle(leg.rental == nil ? Color.secondary : Color.ink).frame(width: 24)
                VStack(alignment: .leading, spacing: 4) {
                    if let r = leg.rental {
                        // Shared bike or scooter: whose, from where, and a link to rent it.
                        Text(verbatim: "\(r.kind.title) · \(r.systemName ?? ModeStyle.label(leg.mode))")
                        if let from = r.fromStationName, !from.isEmpty {
                            Text(L10n.f("Pick up at %@", from)).foregroundStyle(.secondary)
                        }
                        if let to = r.toStationName, !to.isEmpty {
                            Text(L10n.f("Return at %@", to)).foregroundStyle(.secondary)
                        }
                        if let d = leg.distance, d > 0 { Text(Fmt.distance(meters: d)).foregroundStyle(.secondary) }
                        if let url = r.bookingURL {
                            Link(destination: url) { Label("Rent in the provider's app", systemImage: "arrow.up.forward.app") }
                                .font(.subheadline.weight(.semibold))
                        }
                    } else {
                        Text(verbatim: ModeStyle.label(leg.mode))
                        if let d = leg.distance, d > 0 { Text(Fmt.distance(meters: d)).foregroundStyle(.secondary) }
                    }
                }
                Spacer(minLength: 4)
                DurationFigure(seconds: leg.duration, size: 22)
            }
            .font(.subheadline)
        }
    }

    private func stopLine(label: LocalizedStringKey, time: Date, delay: Int, name: String,
                          track: String?, tz: String?) -> some View {
        HStack(alignment: .top, spacing: 12) {
            VStack(alignment: .leading, spacing: 4) {
                Text(Fmt.time(time, tz: tz)).font(.subheadline.weight(.semibold).monospacedDigit())
                    .lineLimit(1).fixedSize()
                if delay > 0 { Text(L10n.f("+%lld min", delay)).font(.caption).foregroundStyle(Color.delay) }
            }
            VStack(alignment: .leading, spacing: 4) {
                Text(label).font(.caption).foregroundStyle(.secondary)
                Text(verbatim: name).font(.subheadline.weight(.semibold)).fixedSize(horizontal: false, vertical: true)
                if let track = TransitLabels.clean(track) {
                    Text(L10n.f("Platform %@", track)).font(.caption).foregroundStyle(.secondary)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
    }
}

/// "Remind me to get off" for one transit leg.
struct GetOffButton: View {
    let leg: Leg
    @State var scheduled = false
    @State var note: String?

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Button {
                Task { await toggle() }
            } label: {
                Label(scheduled ? L10n.t("Reminder set") : L10n.t("Remind me to get off"),
                      systemImage: scheduled ? "bell.fill" : "bell")
                    .font(.caption.weight(.semibold))
            }
            .buttonStyle(.borderless)
            if let note {
                Text(note).font(.caption2).foregroundStyle(.secondary)
            }
        }
        .task { scheduled = await GetOffReminder.isScheduled(leg) }
    }

    private func toggle() async {
        if scheduled {
            GetOffReminder.cancel(for: leg)
            scheduled = false
            note = nil
            return
        }
        switch await GetOffReminder.schedule(for: leg) {
        case .scheduled(let date):
            scheduled = true
            note = L10n.f("We'll notify you at %@.", Fmt.time(date, tz: leg.to.tz))
        case .tooLate:
            note = L10n.t("This stop is less than two minutes away.")
        case .denied:
            note = L10n.t("Allow notifications for Publit in Settings.")
        }
    }
}

/// Live state of a saved trip.
struct StatusBanner: View {
    let status: TripStatus
    let refreshedAt: Date?

    private var color: Color {
        switch status.phase {
        case .cancelled: return .danger
        default: return status.delayMinutes > 0 ? .delay : .live
        }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack {
                Text(status.headline).font(.headline)
                Spacer()
                if status.phase != .arrived && status.phase != .cancelled {
                    Text(status.delayMinutes > 0 ? L10n.f("+%lld min", status.delayMinutes) : L10n.t("On time"))
                        .font(.subheadline.weight(.semibold)).foregroundStyle(color)
                }
            }
            Text(status.detail).font(.subheadline).foregroundStyle(status.phase == .cancelled ? Color.danger : .secondary)
            if let refreshedAt {
                Text("Updated \(refreshedAt.formatted(date: .omitted, time: .shortened))")
                    .font(.caption2).foregroundStyle(.secondary)
            }
        }
        .accessibilityElement(children: .combine)
    }
}

/// One leg in the route timeline: when it starts, a stop on the line (solid in the line's colour,
/// dotted blue on foot) and the leg itself.
struct TimelineLegRow: View {
    let leg: Leg
    /// Rides open to their stops, notes and the get-off alarm; closed they say where to get on and off.
    @State var expanded = false

    var body: some View {
        HStack(alignment: .top, spacing: 10) {
            Text(Fmt.time(leg.startTime, tz: leg.from.tz))
                .font(.subheadline.weight(.semibold).monospacedDigit())
                .lineLimit(1)
                .minimumScaleFactor(0.75)
                .frame(width: TimelineTime.width, alignment: .trailing)
                .padding(.top, 10)
            TimelineRail(color: leg.isTransit ? leg.lineStroke : Color.secondary, dotted: !leg.isTransit)
            Group {
                if leg.isTransit { ride } else { LegRow(leg: leg, bar: false) }
            }
            .padding(.vertical, 4)
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .listRowInsets(EdgeInsets(top: 0, leading: 8, bottom: 0, trailing: 16))
        .listRowSeparator(.hidden)
    }

    private var ride: some View {
        VStack(alignment: .leading, spacing: 10) {
            Button {
                withAnimation(.snappy(duration: 0.25)) { expanded.toggle() }
            } label: {
                VStack(alignment: .leading, spacing: 8) {
                    HStack(alignment: .firstTextBaseline, spacing: 8) {
                        LineBadge(leg)
                        if let detail = leg.serviceDetail {
                            Text(verbatim: detail).font(.caption.weight(.semibold)).lineLimit(1)
                        }
                        Spacer(minLength: 4)
                        DurationFigure(seconds: leg.duration, size: 22)
                        Image(systemName: "chevron.down")
                            .font(.footnote.weight(.semibold)).foregroundStyle(.tertiary)
                            .rotationEffect(.degrees(expanded ? 180 : 0))
                            .accessibilityHidden(true)
                    }
                    DirectionLabel(direction: leg.shownDirection, circular: leg.isCircular)
                    stop(L10n.f("Board at %@", leg.from.name), track: leg.from.track, time: nil, delay: leg.delayMinutes)
                    stop(L10n.f("Get off at %@", leg.to.name), track: leg.to.track, time: leg.endTime,
                         delay: Int((leg.endTime.timeIntervalSince(leg.scheduledEndTime) / 60).rounded()))
                    if !expanded, !leg.serviceAlerts.isEmpty || leg.isReplacementBus {
                        Label("Service changes on this route", systemImage: "exclamationmark.triangle")
                            .font(.caption.weight(.semibold)).foregroundStyle(Color.delay)
                    }
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityHint(Text(expanded ? "Hide stops" : "Show stops"))
            .accessibilityIdentifier("transitLeg")
            if leg.cancelled == true {
                Text("This trip is cancelled.").font(.caption.weight(.semibold)).foregroundStyle(Color.danger)
            }
            if expanded {
                if leg.isReplacementBus {
                    Label("Rail replacement bus", systemImage: "bus.fill").font(.subheadline.weight(.semibold)).foregroundStyle(Color.delay)
                }
                if let stops = leg.intermediateStops, !stops.isEmpty {
                    VStack(alignment: .leading, spacing: 4) {
                        ForEach(Array(stops.enumerated()), id: \.offset) { _, p in
                            HStack(spacing: 8) {
                                Text(verbatim: (p.departure ?? p.arrival).map { Fmt.time($0, tz: p.tz ?? leg.from.tz) } ?? "")
                                    .font(.caption.monospacedDigit()).foregroundStyle(.secondary)
                                    .frame(minWidth: 40, alignment: .leading)
                                Text(verbatim: p.name).font(.caption).lineLimit(1)
                                    .strikethrough(p.cancelled == true)
                            }
                        }
                    }
                    .padding(.leading, 2)
                }
                Text(leg.realTime && !Connectivity.shared.isOffline ? [L10n.t("Live"), leg.liveSource].compactMap { $0 }.joined(separator: " · ") : L10n.t("Timetable"))
                    .font(.caption).foregroundStyle(.secondary)
                ServiceAlertsView(alerts: leg.serviceAlerts)
                GetOffButton(leg: leg)
            }
        }
        .padding(.vertical, 6)
    }

    private func stop(_ title: String, track: String?, time: Date?, delay: Int) -> some View {
        HStack(alignment: .firstTextBaseline, spacing: 6) {
            Text(verbatim: title).font(.subheadline.weight(.semibold)).fixedSize(horizontal: false, vertical: true)
            if let t = TransitLabels.clean(track) {
                Text(L10n.f("Platform %@", t)).font(.caption).foregroundStyle(.secondary)
            }
            Spacer(minLength: 4)
            if let time {
                Text(Fmt.time(time, tz: leg.to.tz)).font(.caption.weight(.semibold).monospacedDigit()).foregroundStyle(.secondary)
            }
            if delay > 0 { Text(L10n.f("+%lld min", delay)).font(.caption).foregroundStyle(Color.delay) }
        }
    }
}

/// The end of the timeline: arrival time and the destination.
struct TimelineEndRow: View {
    let time: Date
    let tz: String?
    let name: String

    var body: some View {
        HStack(alignment: .center, spacing: 10) {
            Text(Fmt.time(time, tz: tz))
                .font(.subheadline.weight(.semibold).monospacedDigit())
                .lineLimit(1)
                .minimumScaleFactor(0.75)
                .frame(width: TimelineTime.width, alignment: .trailing)
            Image(systemName: "flag.checkered.circle.fill")
                .font(.title3).foregroundStyle(Color.ink)
                .frame(width: 18)
            Text(name).font(.headline)
            Spacer(minLength: 0)
        }
        .padding(.vertical, 8)
        .listRowInsets(EdgeInsets(top: 0, leading: 8, bottom: 0, trailing: 16))
        .listRowSeparator(.hidden)
        .accessibilityElement(children: .combine)
    }
}

struct TimelineRail: View {
    let color: Color
    let dotted: Bool

    var body: some View {
        VStack(spacing: 0) {
            Circle()
                .strokeBorder(color, lineWidth: 3)
                .background(Circle().fill(Color.surface))
                .frame(width: 14, height: 14)
                .padding(.top, 12)
            RailLine()
                .stroke(color, style: StrokeStyle(lineWidth: dotted ? 3 : 5, lineCap: .round, dash: dotted ? [0.1, 7] : []))
                .frame(width: 18)
        }
        .frame(width: 18)
        .frame(maxHeight: .infinity)
        .accessibilityHidden(true)
    }
}

private struct RailLine: Shape {
    func path(in rect: CGRect) -> Path {
        var p = Path()
        p.move(to: CGPoint(x: rect.midX, y: rect.minY))
        p.addLine(to: CGPoint(x: rect.midX, y: rect.maxY))
        return p
    }
}

/// The one number that decides: when to leave, and when you arrive. A soft card in the list's own
/// surface (not a bright slab in dark mode); each time is shown once.
struct LeaveHero: View {
    let itinerary: Itinerary
    let now: Date
    /// Its own card; off inside a list section, which already is one.
    var framed = true

    var body: some View {
        let tz = itinerary.legs.first?.from.tz
        let endTz = itinerary.legs.last?.to.tz
        let minutes = Int((itinerary.startTime.timeIntervalSince(now) / 60).rounded(.up))
        let soon = minutes <= 90 && minutes >= -1
        let big: String = soon
            ? (minutes >= 1 ? L10n.f("Leave in %lld min", minutes) : L10n.t("Leave now"))
            : "\(Fmt.time(itinerary.startTime, tz: tz)) – \(Fmt.time(itinerary.endTime, tz: endTz))"
        HStack(alignment: .center, spacing: 12) {
            VStack(alignment: .leading, spacing: 2) {
                HStack(spacing: 6) {
                    if soon { SignatureDot(size: 8) }
                    Group {
                        if soon { Text(Fmt.time(itinerary.startTime, tz: tz)) }
                        else { Text(itinerary.startTime, format: .dateTime.weekday(.abbreviated).day().month(.abbreviated)) }
                    }
                    .font(.caption.weight(.semibold).monospacedDigit()).foregroundStyle(.secondary)
                }
                Text(verbatim: big)
                    .font(.title2.weight(.bold).monospacedDigit())
                    .foregroundStyle(Color.ink)
                    .lineLimit(1).minimumScaleFactor(0.6)
            }
            Spacer(minLength: 8)
            VStack(alignment: .trailing, spacing: 2) {
                if soon {
                    Text("Arrive").font(.caption.weight(.semibold)).foregroundStyle(.secondary)
                    Text(Fmt.time(itinerary.endTime, tz: endTz))
                        .font(.title2.weight(.bold).monospacedDigit()).foregroundStyle(Color.ink)
                } else {
                    DurationFigure(seconds: itinerary.duration, size: 24).fixedSize()
                }
            }
        }
        .padding(.horizontal, framed ? 16 : 0).padding(.vertical, framed ? 14 : 4)
        .background(framed ? Color.surface : .clear, in: RoundedRectangle(cornerRadius: 22, style: .continuous))
        .accessibilityElement(children: .combine)
    }
}

/// The time column of the route timeline: wide enough for "12:59 PM" on one line.
enum TimelineTime {
    static let width: CGFloat = {
        let pattern = DateFormatter.dateFormat(fromTemplate: "j", options: 0, locale: .current) ?? ""
        return pattern.contains("a") ? 70 : 52
    }()
}
