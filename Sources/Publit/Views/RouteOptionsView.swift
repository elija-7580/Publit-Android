import SwiftUI

/// Filters for route search: modes, changes, walking, step-free, and the street comparison.
/// Shared by Plan and "Where to now?", kept between launches.
struct RouteOptionsView: View {
    @Environment(AppState.self) var app
    @Environment(\.dismiss) var dismiss
    @State var draft = RouteOptions()
    @State var loaded = false

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    ForEach(ModeGroup.allCases) { group in
                        Toggle(isOn: Binding(get: { draft.modes.allows(group) },
                                             set: { draft.modes.set(group, on: $0) })) {
                            Label(group.title, systemImage: group.symbol)
                        }
                        // The last mode cannot be switched off: no mode means no routes.
                        .disabled(draft.modes.isActive && draft.modes.included == [group])
                        .accessibilityIdentifier("routeMode-\(group.rawValue)")
                    }
                } header: {
                    Text("Transport modes")
                } footer: {
                    Text("Routes only use the modes that are switched on.")
                }

                Section {
                    Picker("To the first stop", selection: $draft.firstMile) {
                        ForEach(FirstMile.allCases) { m in
                            Label(m.summary, systemImage: m.symbol).tag(m)
                        }
                    }
                    .pickerStyle(.inline)
                    .labelsHidden()
                    .accessibilityIdentifier("routeFirstMile")
                } header: {
                    Text("To the first stop")
                } footer: {
                    Text("Bike & Ride: cycle to a station and park there. Park & Ride: drive to a car park at a station; this search can take up to half a minute. Walking routes stay in the list for comparison; buses, trams and trains are always included.")
                }

                // The same settings as Settings → Smart routing, right where routes are planned.
                SmartRoutingSettingsSection()

                Section("Changes") {
                    Picker("Changes", selection: $draft.maxTransfers) {
                        ForEach(RouteOptions.transferChoices, id: \.self) { n in
                            Text(verbatim: RouteOptions.transferTitle(n)).tag(n)
                        }
                    }
                    .pickerStyle(.segmented)
                    .labelsHidden()
                    .accessibilityIdentifier("routeTransfers")
                }

                Section {
                    Picker("Walk to and from stops", selection: $draft.maxWalkMinutes) {
                        ForEach(RouteOptions.walkChoices, id: \.self) { m in
                            Text(verbatim: L10n.f("%lld min", m)).tag(m)
                        }
                    }
                    Toggle(isOn: $draft.wheelchair) {
                        Label("Step-free (wheelchair)", systemImage: "figure.roll")
                    }
                    Toggle(isOn: $draft.sharing) {
                        Label("Shared bikes and e-scooters", systemImage: "scooter")
                    }
                    .accessibilityIdentifier("routeSharing")
                } footer: {
                    Text("Step-free routes avoid stairs where the data allows it. With sharing, routes can start or end on a rented bike or e-scooter; the search takes a little longer.")
                }

                Section {
                    Toggle(isOn: $draft.compareStreetModes) {
                        Label("Compare with walking, cycling and driving", systemImage: "car.side")
                    }
                    .accessibilityIdentifier("compareStreet")
                } footer: {
                    Text("Driving times are without traffic and parking.")
                }
            }
            .inkList()
            .navigationTitle("Route options")
            .screenTrail("Route options")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Reset") {
                        let compare = draft.compareStreetModes
                        draft = RouteOptions()
                        draft.compareStreetModes = compare
                    }
                    .disabled(draft.isDefault)
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") { dismiss() }.accessibilityIdentifier("routeOptionsDone")
                }
            }
            .onAppear {
                guard !loaded else { return }
                draft = app.routeOptions
                loaded = true
            }
            // Swiping the sheet down keeps the changes too.
            .onDisappear { if draft != app.routeOptions { app.setRouteOptions(draft) } }
        }
    }
}

/// "Route options" row with the active filters, e.g. "Train, S-Bahn · Direct only".
struct RouteOptionsButton: View {
    /// One small line (Plan): the options are there, but not in the way.
    var compact = false
    @Environment(AppState.self) var app
    @State var showing = false

    var body: some View {
        let options = app.routeOptions
        Button { showing = true } label: {
            if compact {
                // A soft chip row, like the time chips above it.
                HStack(spacing: 8) {
                    Image(systemName: options.isDefault ? "slider.horizontal.3" : "line.3.horizontal.decrease.circle.fill")
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(options.isDefault ? Color.secondary : Color.ink)
                        .accessibilityHidden(true)
                    Text("Route options").font(.subheadline.weight(.semibold)).foregroundStyle(.primary)
                    Spacer(minLength: 8)
                    Text(verbatim: options.summary ?? L10n.t("All modes"))
                        .font(.subheadline).foregroundStyle(options.isDefault ? Color.secondary : Color.ink)
                        .lineLimit(1)
                    Image(systemName: "chevron.right").font(.caption.weight(.semibold)).foregroundStyle(.tertiary)
                        .accessibilityHidden(true)
                }
                .padding(.horizontal, 14)
                .frame(minHeight: 40)
                .background(Color.inkFill, in: Capsule())
                .contentShape(Capsule())
            } else {
            HStack(spacing: 10) {
                Image(systemName: options.isDefault ? "slider.horizontal.3" : "line.3.horizontal.decrease.circle.fill")
                    .foregroundStyle(options.isDefault ? Color.secondary : Color.ink)
                    .accessibilityHidden(true)
                VStack(alignment: .leading, spacing: 2) {
                    Text("Route options").foregroundStyle(.primary)
                    Text(verbatim: options.summary ?? L10n.t("All modes"))
                        .font(.caption).foregroundStyle(options.isDefault ? Color.secondary : Color.ink)
                        .lineLimit(2)
                }
                Spacer()
                if options.activeCount > 0 {
                    Text(verbatim: "\(options.activeCount)")
                        .font(.caption.weight(.bold)).foregroundStyle(Color.onInk)
                        .padding(.horizontal, 7).padding(.vertical, 2)
                        .background(Color.ink, in: Capsule())
                        .accessibilityHidden(true)
                }
            }
            }
        }
        .accessibilityElement(children: .combine)
        .accessibilityIdentifier("routeOptions")
        .sheet(isPresented: $showing) { RouteOptionsView().environment(\.liveBarInset, 0) }
    }
}

/// Walking, cycling and driving the whole way, next to the best public transport route.
struct StreetComparisonSection: View {
    let comparisons: [StreetComparison]
    /// Fastest public transport route, for "12 min faster".
    let bestTransit: Int?
    let fromName: String
    let toName: String

    var body: some View {
        if !comparisons.isEmpty {
            Section {
                ForEach(comparisons) { c in
                    NavigationLink {
                        ItineraryDetailView(itinerary: c.itinerary, fromName: fromName, toName: toName)
                    } label: { row(c) }
                    .accessibilityIdentifier("compare-\(c.mode)")
                    .contextMenu { openButton(c) }
                    .swipeActions(edge: .trailing) { openButton(c).tint(Color.ink) }
                }
            } header: {
                Text("Other ways")
            } footer: {
                if comparisons.contains(where: { $0.mode == "CAR" }) {
                    Text("Driving times are without traffic and parking.")
                }
            }
        }
    }

    /// The same way in Apple Maps (or the app chosen in Settings), with traffic and turn-by-turn.
    @ViewBuilder
    private func openButton(_ c: StreetComparison) -> some View {
        if let start = c.itinerary.legs.first?.from, let end = c.itinerary.legs.last?.to {
            let app = DirectionsApp.current
            Button {
                app.open(end.coordinate, name: toName, mode: DirectionsApp.mode(street: c.mode), from: (start.coordinate, fromName))
            } label: { Label(app.openLabel, systemImage: "arrow.up.forward.app") }
        }
    }

    private func row(_ c: StreetComparison) -> some View {
        HStack(spacing: 12) {
            Image(systemName: ModeStyle.symbol(c.mode))
                .font(.body.weight(.semibold)).foregroundStyle(.secondary)
                .frame(width: 28)
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 2) {
                Text(verbatim: ModeStyle.label(c.mode)).font(.subheadline.weight(.semibold))
                if let d = c.distance {
                    Text(verbatim: Fmt.distance(meters: d)).font(.caption).foregroundStyle(.secondary)
                }
            }
            Spacer()
            VStack(alignment: .trailing, spacing: 2) {
                Text(verbatim: Fmt.duration(seconds: c.duration)).font(.subheadline.weight(.semibold).monospacedDigit())
                if let note = Self.difference(c.duration, best: bestTransit) {
                    Text(verbatim: note).font(.caption).foregroundStyle(.secondary)
                }
            }
        }
        .accessibilityElement(children: .combine)
    }

    /// Compared with the fastest public transport route; within 3 minutes counts as the same.
    static func difference(_ seconds: Int, best: Int?) -> String? {
        guard let best else { return nil }
        let diff = seconds - best
        if abs(diff) < 180 { return L10n.t("About as fast") }
        return diff < 0 ? L10n.f("%@ faster", Fmt.duration(seconds: -diff)) : L10n.f("%@ slower", Fmt.duration(seconds: diff))
    }
}

/// Quick choice in the planner: how to get to the first stop. The results then mix
/// variants (walk to the tram, cycle or drive to the train), like other trip planners.
struct FirstMilePicker: View {
    @Environment(AppState.self) var app

    var body: some View {
        let current = app.routeOptions.firstMile
        VStack(alignment: .leading, spacing: 8) {
            Text("To the first stop")
                .font(.subheadline.weight(.semibold))
            HStack(spacing: 8) {
                ForEach(FirstMile.allCases) { mode in
                    let selected = mode == current
                    Button {
                        guard !selected else { return }
                        var o = app.routeOptions
                        o.firstMile = mode
                        app.setRouteOptions(o)
                    } label: {
                        VStack(spacing: 4) {
                            Image(systemName: mode.symbol).font(.body.weight(.semibold))
                            Text(verbatim: mode.title)
                                .font(.caption.weight(.semibold))
                                .lineLimit(1)
                                .minimumScaleFactor(0.8)
                        }
                        .frame(maxWidth: .infinity, minHeight: 52)
                        .foregroundStyle(selected ? Color.onInk : Color.primary)
                        .background(selected ? Color.ink : Color.inkFill,
                                    in: RoundedRectangle(cornerRadius: 12, style: .continuous))
                        .contentShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel(Text(verbatim: mode.summary))
                    .accessibilityAddTraits(selected ? .isSelected : [])
                    .accessibilityIdentifier("firstMile-\(mode.rawValue)")
                }
            }
            if current == .car {
                Text("Park & Ride searches take longer.")
                    .font(.caption).foregroundStyle(.secondary)
            }
        }
        .padding(.vertical, 4)
    }
}
