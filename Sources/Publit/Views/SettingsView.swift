import SwiftUI

/// Settings and credits, behind the gear in the top-left corner of every tab.
/// Credits for the data sources live here once instead of under every list.
struct SettingsView: View {
    @Environment(\.dismiss) var dismiss
    @Environment(TicketStore.self) var tickets
    @AppStorage("mapLines") var mapLines = true
    @AppStorage("mapVehicles") var mapVehicles = true
    @AppStorage("mapSharing") var mapSharing = false
    @AppStorage("routeTimelines") var routeTimelines = true
    @AppStorage(LineAlertMonitor.enabledKey) var lineAlerts = false
    @AppStorage(LiveOverlay.defaultsKey) var extraLive = true
    @AppStorage("mapBase") var mapBase = MapBase.defaultValue
    @AppStorage("mapPOI") var mapPOI = true
    @AppStorage(DirectionsApp.defaultsKey) var directionsApp = DirectionsApp.apple.rawValue
    @AppStorage(SearchProvider.defaultsKey) var searchProvider = SearchProvider.transitous.rawValue
    @AppStorage(GooglePlaces.keyDefaultsKey) var googleKey = ""
    @AppStorage(MapProvider.defaultsKey) var mapProvider = MapProvider.apple.rawValue
    @AppStorage(StartTab.defaultsKey) var startTab = StartTab.nearby.rawValue
    @State var exportURL: URL?
    @State var summary: HangLog.Summary?
    @State var reportURL: URL?

    /// A new GitHub issue with version, device and counts filled in; the diagnostics file is attached by hand.
    private var issueURL: URL {
        let s = summary ?? HangLog.Summary()
        let body = """
        **What happened?**


        **What did you expect?**


        ---
        Publit \(AppInfo.version) (\(AppInfo.build)) · \(HangLog.device) · \(UIDevice.current.systemName) \(UIDevice.current.systemVersion)
        Recorded: \(s.freezes) freezes, \(s.errors) failed requests, \(s.crashes) crashes, \(s.exits) unexpected exits
        If you can, attach the file from Settings → Diagnostics → Share diagnostics.
        """
        var c = URLComponents(url: AppInfo.repositoryURL.appendingPathComponent("issues/new"), resolvingAgainstBaseURL: false)
        c?.queryItems = [URLQueryItem(name: "body", value: body)]
        return c?.url ?? AppInfo.repositoryURL
    }

    var body: some View {
        NavigationStack {
            List {
                Section {
                    HStack(spacing: 14) {
                        PublitAppIcon(size: 56)
                        VStack(alignment: .leading, spacing: 2) {
                            Text(verbatim: "Publit").font(.title2.bold())
                            Text("Public transport, without the noise.")
                                .font(.subheadline)
                                .foregroundStyle(.secondary)
                        }
                    }
                    .padding(.vertical, 4)
                    .accessibilityElement(children: .combine)
                }
                Section {
                    Picker(L10n.t("Start screen"), selection: $startTab) {
                        Text("Nearby").tag(StartTab.nearby.rawValue)
                        Text("Plan").tag(StartTab.plan.rawValue)
                    }
                    .accessibilityIdentifier("startTab")
                }
                Section(L10n.t("Travel preferences")) {
                    RouteOptionsButton()
                    Toggle(L10n.t("Show connection timelines"), isOn: $routeTimelines)
                    NavigationLink { ConnectionGuideView() } label: { Label(L10n.t("How to read connections"), systemImage: "questionmark.circle") }
                }
                SmartRoutingSettingsSection()
                Section {
                    if MapProvider.googleAvailable {
                        Picker(L10n.t("Map provider"), selection: $mapProvider) {
                            ForEach(MapProvider.allCases) { Text($0.title).tag($0.rawValue) }
                        }
                        .accessibilityIdentifier("mapProvider")
                    }
                    Picker(L10n.t("Map style"), selection: $mapBase) {
                        ForEach(MapBase.allCases) { Text($0.title).tag($0.rawValue) }
                    }
                    Toggle(L10n.t("Points of interest"), isOn: $mapPOI)
                    Toggle(L10n.t("Lines"), isOn: $mapLines)
                    Toggle(L10n.t("Vehicles"), isOn: $mapVehicles)
                    Toggle(L10n.t("Bikes and scooters"), isOn: $mapSharing)
                    Picker(L10n.t("Open directions in"), selection: $directionsApp) {
                        ForEach(DirectionsApp.allCases) { Text($0.title).tag($0.rawValue) }
                    }
                    .accessibilityIdentifier("directionsApp")
                } header: {
                    Text(L10n.t("Map"))
                } footer: {
                    if mapProvider == MapProvider.google.rawValue {
                        Text(L10n.t("Google Maps uses the Google API key under Search, with “Maps SDK for iOS” enabled. Without a key, Apple Maps is shown."))
                    }
                }
                Section {
                    Picker(L10n.t("Place search"), selection: $searchProvider) {
                        ForEach(SearchProvider.allCases) { Text($0.title).tag($0.rawValue) }
                    }
                    .accessibilityIdentifier("searchProvider")
                    if searchProvider == SearchProvider.google.rawValue || mapProvider == MapProvider.google.rawValue {
                        SecureField(L10n.t("Google API key"), text: $googleKey)
                            .textInputAutocapitalization(.never)
                            .autocorrectionDisabled()
                    }
                } header: {
                    Text(L10n.t("Search"))
                } footer: {
                    Text(L10n.t(searchProvider == SearchProvider.google.rawValue
                                ? "Shops, restaurants and sights come from Google; stops still come from Transitous. What you type and your approximate location go to Google."
                                : "Stops, addresses and places from OpenStreetMap, through Transitous."))
                }
                if Features.tickets {
                    Section("Tickets") {
                        NavigationLink {
                            TicketsView()
                        } label: {
                            LabeledContent {
                                Text("\(tickets.tickets.count)")
                            } label: {
                                Label("My tickets", systemImage: "ticket")
                            }
                        }
                        NavigationLink {
                            LinkedTicketAppsView()
                        } label: {
                            LabeledContent {
                                Text("\(tickets.linkedApps.count)")
                            } label: {
                                Label("Ticket apps per operator", systemImage: "arrow.up.forward.app")
                            }
                        }
                    }
                }
                Section {
                    Toggle(isOn: Binding(get: { lineAlerts }, set: { on in
                        lineAlerts = on
                        if on { Task { if !(await LineAlertMonitor.requestPermission()) { lineAlerts = false } } }
                    })) {
                        Label("Disruptions on saved lines", systemImage: "exclamationmark.bubble")
                    }
                    .accessibilityIdentifier("lineAlertsToggle")
                } header: {
                    Text("Notifications")
                } footer: {
                    Text("Publit checks your saved lines when you open it and from time to time in the background (iOS decides when), and tells you about alerts and cancelled departures.")
                }
                Section {
                    Link("Routing and timetables: Transitous and its data sources", destination: AppInfo.sourcesURL)
                    Link("Map data © OpenStreetMap contributors", destination: AppInfo.osmCopyrightURL)
                    Toggle(isOn: $extraLive) {
                        Label("Live data from more operators", systemImage: "dot.radiowaves.left.and.right")
                    }
                    .accessibilityIdentifier("extraLiveToggle")
                    NavigationLink { LiveSourcesView() } label: {
                        Label("Live data sources", systemImage: "antenna.radiowaves.left.and.right")
                    }
                    .accessibilityIdentifier("liveSources")
                    if let exportURL {
                        ShareLink(item: exportURL) {
                            Label("Export my data", systemImage: "square.and.arrow.up.on.square")
                        }
                        .accessibilityIdentifier("exportData")
                    }
                } header: {
                    Text("Data")
                } footer: {
                    VStack(alignment: .leading, spacing: 6) {
                        Text("Times marked live come from the operator's real-time feed; all others are timetable times.")
                        Text("Where Transitous has no live times, Publit also asks ÖBB, Wiener Linien, search.ch (Switzerland), regional networks in Germany (VRR, VBB, RMV, VBN, NAH.SH, VRN), ACTV (Venice), SL (Stockholm) and TfL (London, Powered by TfL Open Data) about delays, cancellations and disruptions. They only get the stop, line and time of a ride.")
                        Text("All information without guarantee. Times, platforms and prices come from the operators' open data and can change at short notice.")
                            .accessibilityIdentifier("noGuaranteeSettings")
                    }
                }
                Section {
                    if let summary, summary.total > 0 {
                        if summary.freezes > 0 { LabeledContent(L10n.t("Freezes"), value: "\(summary.freezes)") }
                        if summary.errors > 0 { LabeledContent(L10n.t("Failed requests"), value: "\(summary.errors)") }
                        if summary.crashes + summary.exits > 0 {
                            LabeledContent(L10n.t("Crashes and unexpected exits"), value: "\(summary.crashes + summary.exits)")
                        }
                    } else if summary != nil {
                        Text("No problems recorded").foregroundStyle(.secondary)
                    }
                    if let reportURL {
                        ShareLink(item: reportURL) {
                            Label("Share diagnostics", systemImage: "square.and.arrow.up")
                        }
                        .accessibilityIdentifier("shareFreezeLog")
                    }
                    Link(destination: issueURL) {
                        Label("Report a problem", systemImage: "exclamationmark.bubble")
                    }
                    if summary?.total ?? 0 > 0 {
                        Button(role: .destructive) {
                            HangLog.shared.clear()
                            summary = HangLog.Summary()
                        } label: {
                            Label("Delete diagnostics", systemImage: "trash")
                        }
                    }
                } header: {
                    Text("Diagnostics")
                } footer: {
                    Text("Publit notes freezes of 2 seconds or more, crashes, unexpected exits and failed requests (only their kind), each with the screens opened just before. Places, stops and tickets are not recorded, and nothing leaves this device unless you share it.")
                }
                Section {
                    Link("Source code and feedback", destination: AppInfo.repositoryURL)
                    Link("Privacy policy", destination: AppInfo.privacyURL)
                    LabeledContent("Version", value: "\(AppInfo.version) (\(AppInfo.build))").accessibilityIdentifier("installedBuild")
                } header: {
                    Text("About")
                } footer: {
                    if Features.tickets {
                        Text("Publit is open source (MIT). No account, no ads, no tracking. Saved places, trips and tickets stay on this device.")
                    } else {
                        Text("Publit is open source (MIT). No account, no ads, no tracking. Saved places and trips stay on this device.")
                    }
                }
            }
            .readableWidth()
            .inkList()
            // Once per opening, on the list: on the section it ran again each time the section scrolled
            // back in, and rows appearing and disappearing made the screen jump back and forth.
            .task {
                guard summary == nil else { return }
                let loaded = await Task.detached(priority: .utility) {
                    (HangLog.shared.summary, HangLog.shared.writeReport(), DataExport.write())
                }.value
                var t = Transaction()
                t.disablesAnimations = true
                withTransaction(t) {
                    summary = loaded.0
                    reportURL = loaded.1
                    exportURL = loaded.2
                }
            }
            .navigationTitle("Settings")
            .screenTrail("Settings")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } }
            }
        }
    }
}

/// Gear button for the navigation bar of each tab.
struct SettingsButton: View {
    @Environment(AppState.self) var app
    @State var showing = false

    var body: some View {
        Button { showing = true } label: { Label("Settings", systemImage: "gearshape") }
            .sheet(isPresented: $showing) { SettingsView().environment(\.liveBarInset, 0) }
            .onChange(of: app.dismissToken) { showing = false }
    }
}

extension View {
    func withSettingsButton() -> some View {
        toolbar {
            ToolbarItem(placement: .topBarLeading) { SettingsButton() }
        }
    }
}
