import SwiftUI

struct PlanView: View {
    @AppStorage(SmartRouting.enabledKey) var smartRouting = true
    @Environment(AppState.self) var app
    @Environment(\.scenePhase) var scenePhase
    @State var isVisible = false
    @Environment(LocationManager.self) var location
    @State var picking: PickTarget?
    @State var opened: OpenedRoute?
    /// Route rows on screen, to offer a jump to the recommended one while it is out of sight.
    @State var visibleRows: Set<String> = []

    /// Live refresh interval while planning for "now".
    static var refreshInterval: Duration { Energy.duration(60) }

    enum PickTarget: String, Identifiable { case from, to; var id: String { rawValue } }

    var body: some View {
        @Bindable var plan = app.plan
        let pick = smartPick(plan)
        ScrollViewReader { proxy in
        List {
            Section {
                // From and To in one soft card, joined like the logo's stroke; the round swap key on the right.
                HStack(spacing: 12) {
                    EndpointConnector().frame(width: 12)
                    VStack(spacing: 0) {
                        endpointButton(label: "From", endpoint: plan.from, target: .from)
                            .accessibilityIdentifier("endpoint-from")
                        Rectangle().fill(Color.inkFill).frame(height: 1)
                        HStack(spacing: 8) {
                            endpointButton(label: "To", endpoint: plan.to, target: .to)
                                .accessibilityIdentifier("endpoint-to")
                            // Search right next to the destination (it replaced the old "Clear destination" ✕).
                            if plan.to != nil {
                                Button { startSearch() } label: {
                                    Image(systemName: "magnifyingglass")
                                        .font(.subheadline.weight(.bold))
                                        .foregroundStyle(Color.onInk)
                                        .frame(width: 34, height: 34)
                                        .background(Color.ink, in: Circle())
                                }
                                .accessibilityLabel(Text("Search routes"))
                                .accessibilityIdentifier("searchRoutes")
                            }
                        }
                    }
                    Button { app.plan.swap() } label: {
                        Image(systemName: "arrow.up.arrow.down")
                            .font(.subheadline.weight(.semibold))
                            .foregroundStyle(Color.ink)
                            .frame(width: 40, height: 40)
                            .background(Color.inkFill, in: Circle())
                    }
                    .disabled(plan.to == nil)
                    .accessibilityLabel(Text("Swap"))
                    .accessibilityIdentifier("swapEndpoints")
                }
                .buttonStyle(.borderless)
                // The card is held together by spacing: list separators drew stray lines under "To" and "Now".
                .listRowSeparator(.hidden)
                // Plan is for later (tonight, next week). "From here, now" lives in Nearby.
                VStack(alignment: .leading, spacing: 12) {
                    BevelSegmented(selection: $plan.timeMode,
                                   options: [(TimeMode.departAt, L10n.t("Depart")), (TimeMode.arriveBy, L10n.t("Arrive"))],
                                   large: true)
                        .accessibilityLabel(Text("When"))
                    HStack(spacing: 10) {
                        DatePicker(plan.timeMode == .arriveBy ? L10n.t("Arrive by") : L10n.t("Depart at"), selection: $plan.date)
                            .labelsHidden()
                            .datePickerStyle(.compact)
                            .controlSize(.large)
                        Spacer(minLength: 0)
                        // Back to the current time in one tap, without scrolling the wheels.
                        Button {
                            plan.date = .now
                            if plan.timeMode == .arriveBy { plan.timeMode = .departAt }
                        } label: {
                            Label("Now", systemImage: "clock.arrow.circlepath").font(.subheadline.weight(.semibold))
                        }
                        .buttonStyle(.soft)
                        .disabled(plan.timeMode == .departAt && abs(plan.date.timeIntervalSinceNow) < 90)
                        .accessibilityIdentifier("planTimeNow")
                    }
                }
                .padding(.vertical, 6)
                .listRowSeparator(.hidden)
                RouteOptionsButton(compact: true)
                    .listRowSeparator(.hidden)
            }

            if plan.to == nil || !plan.searchStarted {
                RecentSearchesSection()
            } else {
                resultsSection(plan, pick: pick)
                StreetComparisonSection(comparisons: plan.comparisons,
                                        bestTransit: plan.itineraries.filter { !$0.isDirect }.map(\.duration).min(),
                                        fromName: plan.from.title, toName: plan.to?.title ?? "")
            }
        }
        .readableWidth()
        .inkList()
        // The suggested route out of sight (later in the list): a capsule jumps to it, the list stays put otherwise.
        .overlay(alignment: .bottom) {
            if plan.searchStarted, plan.to != nil, let key = pick.key, !visibleRows.contains(key),
               let it = pick.list.first(where: { $0.routeKey == key }) {
                Button {
                    withAnimation(.snappy) { proxy.scrollTo(key, anchor: .center) }
                } label: {
                    Label(L10n.f("Recommended: %@", "\(Fmt.time(it.startTime, tz: it.legs.first?.from.tz)) – \(Fmt.time(it.endTime, tz: it.legs.last?.to.tz))"),
                          systemImage: "sparkles")
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(Color.ink)
                        .floatingCapsule()
                }
                .buttonStyle(.plain)
                .padding(.bottom, 12)
                .transition(.move(edge: .bottom).combined(with: .opacity))
                .accessibilityIdentifier("jumpToRecommended")
            }
        }
        }
        // The last footer ("Updated …") stays clear of the tab bar and the live bar.
        .contentMargins(.bottom, 32, for: .scrollContent)
        // Pull down: fetch the routes again (delays, a train just gone).
        .refreshable {
            guard app.plan.searchStarted, app.plan.to != nil else { return }
            await app.plan.search(current: location.coordinate, locationDenied: location.isDenied, silent: true)
        }
        .navigationDestination(item: $opened) { route in detail(route.itinerary) }
        .navigationTitle("Plan")
        .screenTrail("Plan")
        .withSettingsButton()
        .onAppear {
            isVisible = true
            if plan.timeMode == .now { plan.timeMode = .departAt }
            if plan.date < .now.addingTimeInterval(-60) { plan.date = .now }
        }
        .sheet(item: $picking) { target in
            PlaceSearchView(title: target == .from ? "From" : "To", allowCurrentLocation: true) { endpoint in
                if target == .from { app.plan.from = endpoint } else { app.plan.to = endpoint; app.plan.source = .search }
            }
        }
        .onChange(of: app.dismissToken) { picking = nil }
        .onDisappear { isVisible = false }
        .task(id: "\(app.plan.queryKey)|\(scenePhase)|\(app.tab)|\(isVisible)|\(app.plan.searchStarted)") {
            guard isVisible, scenePhase == .active, app.tab == .plan, app.plan.searchStarted else { return }
            // Back after a while: a time that has passed becomes now (the routes from then are gone).
            if app.plan.date < .now.addingTimeInterval(-60) { app.plan.date = .now }
            // From here: a fresh fix, not the one from when the app was last open (another town, maybe).
            var here = location.coordinate
            if app.plan.from == .currentLocation { here = await location.freshCoordinate() }
            guard !Task.isCancelled else { return }
            await app.plan.search(current: here, locationDenied: location.isDenied)
            // Keep "now" results live: delays, cancellations and departed trips change by the minute.
            while app.plan.isLive, app.plan.to != nil, !Task.isCancelled {
                try? await Task.sleep(for: Self.refreshInterval)
                guard !Task.isCancelled else { break }
                await app.plan.search(current: location.coordinate, locationDenied: location.isDenied, silent: true)
            }
        }
        .onChange(of: location.hasFix) { _, hasFix in
            // Retry once the first location fix arrives.
            if hasFix, app.plan.searchStarted, app.plan.itineraries.isEmpty, app.plan.to != nil {
                Task { await app.plan.search(current: location.coordinate) }
            }
        }
    }

    /// Starts the search, or runs it again when the routes are already shown.
    private func startSearch() {
        if !app.plan.searchStarted { app.plan.searchStarted = true; return }
        Task { await app.plan.search(current: location.coordinate, locationDenied: location.isDenied, silent: true) }
    }

    struct Pick {
        var list: [Itinerary] = []
        var badges: [String: [SmartRouting.Badge]] = [:]
        var key: String?
        var reason: String?
    }

    /// Smart routing: the suggested route and the best in each respect, with the reason in a few words.
    private func smartPick(_ plan: PlanModel) -> Pick {
        guard plan.searchStarted, plan.to != nil else { return Pick() }
        let its = plan.sortedItineraries.uniqueRoutes
        let badges = smartRouting ? SmartRouting.badges(its, arriveBy: plan.timeMode == .arriveBy) : [:]
        let key = badges.first { $0.value.contains(.recommended) }?.key
        let reason = its.first { $0.routeKey == key }.flatMap { SmartRouting.reason(for: $0, in: its) }
        return Pick(list: its, badges: badges, key: key, reason: reason)
    }

    /// Same weekday and time, next time it comes round.
    static func nextOccurrence(of date: Date, after now: Date = .now) -> Date {
        var d = date
        while d < now { d = Calendar.current.date(byAdding: .day, value: 7, to: d) ?? now }
        return d
    }

    private func endpointButton(label: LocalizedStringKey, endpoint: Endpoint?, target: PickTarget) -> some View {
        Button { picking = target } label: {
            HStack(spacing: 8) {
                Text(label).font(.subheadline).foregroundStyle(.secondary).lineLimit(1).fixedSize()
                    .frame(minWidth: 44, alignment: .leading)
                if let endpoint {
                    if case .currentLocation = endpoint {
                        SignatureDot(size: 9)
                    }
                    Text(endpoint.title).font(.body.weight(.semibold)).foregroundStyle(.primary).lineLimit(1)
                } else {
                    Text("Where to?").foregroundStyle(.secondary)
                }
                Spacer(minLength: 0)
            }
            .frame(minHeight: 46)
            .contentShape(Rectangle())
        }
        .accessibilityElement(children: .combine)
        .accessibilityValue(endpoint?.title ?? L10n.t("not set"))
    }

    private func detail(_ it: Itinerary) -> some View {
        ItineraryDetailView(itinerary: it, fromName: app.plan.from.title, toName: app.plan.to?.title ?? "")
    }

    @ViewBuilder
    private func resultsSection(_ plan: PlanModel, pick: Pick) -> some View {
        let its = pick.list
        // Every bar starts at the left; the longest route fills the width, so lengths still compare.
        let span = TimeInterval(its.map(\.duration).max() ?? 0)
        let zoneNote = its.first.flatMap { Fmt.foreignZoneNote(tz: $0.legs.first?.from.tz, at: $0.startTime) }
        let badges = pick.badges
        let recommendedKey = pick.key
        let reason = pick.reason

        Section {
            OfflineNote()
            if plan.isLoading && its.isEmpty {
                LogoLoader()
            } else if let msg = plan.errorMessage, its.isEmpty {
                Text(msg).foregroundStyle(.secondary)
            }
            if let note = plan.walkNote, !its.isEmpty {
                // Found only with a longer walk: say so, the walk is part of the decision.
                InlineNotice(text: note, systemImage: "figure.walk")
                    .accessibilityIdentifier("longWalkNote")
            }

            ForEach(its, id: \.routeKey) { it in
                Button { opened = OpenedRoute(itinerary: it) } label: {
                    HStack(spacing: 8) {
                        ItineraryRow(itinerary: it, windowStart: it.startTime, windowEnd: it.startTime.addingTimeInterval(span),
                                     badges: badges[it.routeKey] ?? [], reason: it.routeKey == recommendedKey ? reason : nil)
                        Image(systemName: "chevron.right")
                            .font(.footnote.weight(.semibold))
                            .foregroundStyle(.tertiary)
                            .accessibilityHidden(true)
                    }
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityAddTraits(.isLink)
                .accessibilityIdentifier("routeRow")
                .id(it.routeKey)
                .onAppear { visibleRows.insert(it.routeKey) }
                .onDisappear { visibleRows.remove(it.routeKey) }
            }

            if plan.canLoadMore {
                Button {
                    Task { await plan.loadMore() }
                } label: {
                    HStack {
                        Text(plan.timeMode == .arriveBy ? L10n.t("Earlier connections") : L10n.t("Later connections"))
                        if plan.isLoadingMore { Spacer(); LogoLoader(size: 24).frame(width: 48) }
                    }
                }
            }
        } header: {
            // "Venice · ACTV" above the routes when they all start in one network.
            if let region = its.first.flatMap({ TransitRegion.along($0).first }),
               its.allSatisfy({ TransitRegion.along($0).first == region }) {
                Text(verbatim: region.title).accessibilityIdentifier("resultsRegion")
            }
        } footer: {
            if !its.isEmpty {
                VStack(alignment: .leading, spacing: 6) {
                    if let zoneNote { Label(zoneNote, systemImage: "clock") }
                    if let updated = plan.updatedAt {
                        Label(L10n.f("Updated %@", Fmt.time(updated)), systemImage: plan.isLive ? "dot.radiowaves.left.and.right" : "arrow.clockwise")
                            .accessibilityIdentifier("routesUpdated")
                    }
                }
                .padding(.top, 8)
            }
        }
    }
}

/// The line between From and To: an open ring at the start, a full dot at the end, joined by a
/// round-capped stroke (the logo's line).
struct EndpointConnector: View {
    var body: some View {
        VStack(spacing: 4) {
            Circle().strokeBorder(Color.ink, lineWidth: 2.5).frame(width: 11, height: 11)
            Capsule().fill(Color.ink.opacity(0.25)).frame(width: 2.5).frame(maxHeight: .infinity)
            Circle().fill(Color.ink).frame(width: 11, height: 11)
        }
        .padding(.vertical, 18)
        .accessibilityHidden(true)
    }
}

/// One route in the comparison list. All rows share one time axis so routes can be compared at a glance.
struct ItineraryRow: View {
    @AppStorage("routeTimelines") var showTimeline = true
    @AppStorage(SmartRouting.enabledKey) var smartRouting = true
    let itinerary: Itinerary
    let windowStart: Date
    let windowEnd: Date
    /// Smart routing labels ("Recommended", "Fastest", …) and why the route is suggested.
    var badges: [SmartRouting.Badge] = []
    var reason: String? = nil

    private var tz: String? { itinerary.legs.first?.from.tz }

    var body: some View {
        let warnings = TransferRisk.warnings(for: itinerary)
        // The weakest change, when it is not sure to work (the warnings above already cover broken ones).
        let shaky = smartRouting && warnings.isEmpty
            ? SmartRouting.changes(itinerary).filter { $0.chance < 0.8 }.min { $0.chance < $1.chance } : nil
        VStack(alignment: .leading, spacing: 6) {
            if !badges.isEmpty { SmartBadgesRow(badges: badges, reason: reason) }

            HStack(alignment: .firstTextBaseline) {
                Text("\(Fmt.time(itinerary.startTime, tz: tz)) – \(Fmt.time(itinerary.endTime, tz: itinerary.legs.last?.to.tz))")
                    .font(.headline.monospacedDigit())
                if itinerary.hasRealtime {
                    Image(systemName: "dot.radiowaves.left.and.right")
                        .font(.caption).foregroundStyle(Color.live)
                        .accessibilityLabel("Live data")
                }
                Spacer()
                DurationFigure(seconds: itinerary.duration, size: 22)
            }
            if showTimeline {
                TimelineBar(itinerary: itinerary, windowStart: windowStart, windowEnd: windowEnd).frame(height: 42)
            }
            // Where to go, at a glance: the stop and platform to get on at, without opening the route.
            BoardingLine(itinerary: itinerary)
            HStack(spacing: 8) {
                Text(Fmt.changes(itinerary.transfers))
                if let first = itinerary.firstTransitLeg, let dir = first.shownDirection {
                    Text(verbatim: "· \(first.lineName) → \(dir)").lineLimit(1)
                }
                if itinerary.isCancelled {
                    Text("Cancelled").foregroundStyle(Color.danger)
                }
            }
            .font(.caption)
            .foregroundStyle(.secondary)
            if itinerary.transitLegs.contains(where: { !$0.serviceAlerts.isEmpty || $0.isReplacementBus }) {
                InlineNotice(text: L10n.t("Service changes on this route"))
            }
            ForEach(warnings, id: \.self) { w in
                InlineNotice(text: w.message, tint: w.level == .missed ? Color.danger : Color.delay)
            }
            if let shaky {
                InlineNotice(text: L10n.f("Change at %@: %lld %% chance", shaky.stopName, shaky.percent),
                             tint: shaky.level == .risky ? Color.danger : Color.delay)
            }
        }
        .padding(.vertical, 4)
        // The suggested route stands out by a mark at its edge, not by moving it to the top.
        .overlay(alignment: .leading) {
            if badges.contains(.recommended) {
                Capsule().fill(Color.signature).frame(width: 3).padding(.vertical, 6).offset(x: -10)
                    .accessibilityHidden(true)
            }
        }
    }
}

struct TimelineBar: View {
    let itinerary: Itinerary
    let windowStart: Date
    let windowEnd: Date

    var body: some View {
        GeometryReader { geo in
            let span = max(windowEnd.timeIntervalSince(windowStart), 60)
            let width = geo.size.width
            let height: CGFloat = 22
            ZStack(alignment: .topLeading) {
                ForEach(Array(itinerary.legs.enumerated()), id: \.offset) { index, leg in
                    if index > 0, itinerary.legs[index - 1].endTime < leg.startTime {
                        let previous = itinerary.legs[index - 1].endTime
                        let gapStart = max(0, min(width, previous.timeIntervalSince(windowStart) / span * width))
                        let gapEnd = max(gapStart, min(width, leg.startTime.timeIntervalSince(windowStart) / span * width))
                        Path { path in path.move(to: CGPoint(x: gapStart, y: height / 2)); path.addLine(to: CGPoint(x: gapEnd, y: height / 2)) }
                            .stroke(Color.secondary, style: StrokeStyle(lineWidth: 2, lineCap: .butt, dash: [4, 4]))
                    }
                    let x = max(0, min(width, leg.startTime.timeIntervalSince(windowStart) / span * width))
                    let end = max(x, min(width, leg.endTime.timeIntervalSince(windowStart) / span * width))
                    let w = max(0, min(width - x, max(end - x, 2)))
                    if leg.isTransit {
                        RoundedRectangle(cornerRadius: 4, style: .continuous)
                            .fill(leg.lineStroke)
                            .overlay {
                                if w > 36 {
                                    Text(leg.lineName)
                                        .font(.caption2.weight(.bold))
                                        .foregroundStyle(leg.lineTextColor)
                                        .lineLimit(1)
                                        .padding(.horizontal, 2)
                                }
                            }
                            .frame(width: w, height: height)
                            .offset(x: x)
                    } else {
                        // A walk: a row of dots, like the walk in the route detail; waiting stays dashed.
                        Path { p in p.move(to: CGPoint(x: x + 2, y: height / 2)); p.addLine(to: CGPoint(x: x + max(w - 2, 2), y: height / 2)) }
                            .stroke(Color.secondary.opacity(0.7), style: StrokeStyle(lineWidth: 3.5, lineCap: .round, dash: [0.1, 6]))
                    }
                    // Minutes under a piece only where they fit and say something: rides, and walks of 3 min or more.
                    let minutes = max(1, Int((Double(leg.duration) / 60).rounded()))
                    if leg.isTransit ? w >= 28 : (w >= 40 && minutes >= 3) {
                        HStack(spacing: 2) {
                            if !leg.isTransit { Image(systemName: ModeStyle.symbol(leg.mode)).font(.system(size: 9, weight: .semibold)) }
                            Text(verbatim: "\(minutes)′")
                        }
                        .font(.caption2.weight(.semibold).monospacedDigit())
                        .foregroundStyle(.secondary).lineLimit(1)
                        .frame(width: w, height: 16).offset(x: x, y: height + 3)
                    }
                }
            }
            .frame(width: width, height: geo.size.height, alignment: .topLeading).clipped()
        }
        .accessibilityHidden(true)
    }
}
