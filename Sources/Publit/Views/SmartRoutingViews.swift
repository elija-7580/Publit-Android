import SwiftUI

/// "✦ Recommended · One change fewer", then the other labels of a route in the list.
struct SmartBadgesRow: View {
    let badges: [SmartRouting.Badge]
    var reason: String? = nil

    var body: some View {
        HStack(spacing: 6) {
            ForEach(badges, id: \.self) { badge in
                Label(badge.title, systemImage: badge.symbol)
                    .labelStyle(.titleAndIcon)
                    .font(.caption2.weight(.bold))
                    // The suggestion is a solid ink chip (readable in light and dark), the rest stay quiet.
                    .foregroundStyle(badge == .recommended ? Color.onInk : Color.secondary)
                    .padding(.horizontal, 8).padding(.vertical, 3)
                    .background(badge == .recommended ? Color.ink : Color.inkFill, in: Capsule())
            }
            if let reason {
                Text(verbatim: reason).font(.caption2).foregroundStyle(.secondary).lineLimit(1)
            }
        }
        .accessibilityElement(children: .combine)
        .accessibilityIdentifier("smartBadges")
    }
}

/// On a route: each change with how likely it works and the vehicle that takes over if it does not.
struct SmartChangesSection: View {
    let itinerary: Itinerary
    @AppStorage(SmartRouting.enabledKey) var enabled = true

    var body: some View {
        if enabled, !itinerary.isDirect {
            let changes = SmartRouting.changes(itinerary)
            let lastRide = itinerary.legs.lastIndex(where: \.isTransit)
            Section {
                if changes.isEmpty {
                    Label(L10n.t("No change: nothing to miss"), systemImage: "checkmark.circle")
                        .font(.subheadline)
                        .foregroundStyle(Color.live)
                }
                ForEach(Array(changes.enumerated()), id: \.offset) { _, change in
                    ChangeChanceRow(change: change, isLastRide: change.outgoingIndex == lastRide)
                }
            } header: {
                Text(L10n.t("Smart routing"))
            } footer: {
                Text(L10n.f("Feels like %@: waiting, walking and changes count more than riding.",
                            Fmt.duration(seconds: Int(SmartRouting.feltMinutes(itinerary) * 60))))
            }
        }
    }
}

struct ChangeChanceRow: View {
    let change: SmartRouting.Change
    let isLastRide: Bool

    private var tint: Color {
        switch change.level {
        case .safe: return .live
        case .likely: return .delay
        case .risky: return .danger
        }
    }

    private var verdict: String {
        switch change.level {
        case .safe: return L10n.t("Safe change")
        case .likely: return L10n.t("Change should work")
        case .risky: return L10n.t("Risky change")
        }
    }

    var body: some View {
        let spare = change.spareMinutes >= 1 ? L10n.f("%@ to spare", Fmt.duration(seconds: Int(change.spareMinutes * 60)))
            : L10n.t("Very tight change")
        VStack(alignment: .leading, spacing: 5) {
            HStack(alignment: .firstTextBaseline) {
                Image(systemName: change.level == .risky ? "exclamationmark.triangle.fill" : "arrow.triangle.swap")
                    .foregroundStyle(tint)
                Text(L10n.f("Change at %@", change.stopName)).font(.subheadline.weight(.semibold))
                Spacer(minLength: 8)
                Text(verbatim: "\(change.percent) %")
                    .font(.subheadline.weight(.bold).monospacedDigit())
                    .foregroundStyle(tint)
            }
            Text(verbatim: "\(verdict) · \(spare)")
                .font(.caption).foregroundStyle(.secondary)
            if let backup = SmartRouting.backup(for: change.outgoing) {
                let then = isLastRide ? L10n.f("Arrive %@", Fmt.time(backup.arrival, tz: change.outgoing.to.tz))
                    : L10n.t("still makes the next change")
                Label(L10n.f("If missed: %@ at %@", backup.leg.lineName, Fmt.time(backup.leg.startTime, tz: backup.leg.from.tz)) + " · " + then,
                      systemImage: "arrow.uturn.forward")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(Color.ink)
            } else if change.level != .safe {
                Text(L10n.t("No backup found if you miss it.")).font(.caption).foregroundStyle(.secondary)
            }
        }
        .padding(.vertical, 2)
        .accessibilityElement(children: .combine)
    }
}

/// Settings → Smart routing.
struct SmartRoutingSettingsSection: View {
    @AppStorage(SmartRouting.enabledKey) var enabled = true
    @AppStorage(SmartRouting.transferMinutesKey) var transferMinutes = SmartRouting.defaultTransferMinutes
    @AppStorage(SmartRouting.walkWeightKey) var walkWeight = SmartRouting.defaultWalkWeight
    @AppStorage(SmartRouting.extraTransferKey) var extraTransfer = 0
    @AppStorage(SmartRouting.ownSpeedKey) var ownSpeed = false
    @AppStorage(SmartRouting.measuredSpeedKey) var measuredSpeed = 0.0
    @AppStorage(SmartRouting.flatWalksKey) var flatWalks = false
    @AppStorage(SmartRouting.learnKey) var learn = true
    @State var measuring = false

    private var learnedSomething: Bool {
        transferMinutes != SmartRouting.defaultTransferMinutes || walkWeight != SmartRouting.defaultWalkWeight
    }

    var body: some View {
        Section {
            Toggle(isOn: $enabled) {
                Label(L10n.t("Smart routing"), systemImage: "sparkles")
            }
            .accessibilityIdentifier("smartRouting")
            if enabled {
                VStack(alignment: .leading, spacing: 6) {
                    Text(L10n.t("Changes")).font(.subheadline)
                    Slider(value: $transferMinutes, in: SmartRouting.transferMinutesRange, step: 1) {
                        Text(L10n.t("Changes"))
                    } minimumValueLabel: {
                        Text(L10n.t("Faster")).font(.caption)
                    } maximumValueLabel: {
                        Text(L10n.t("Fewer changes")).font(.caption)
                    }
                    .accessibilityIdentifier("smartTransferSlider")
                    Text(L10n.f("A change counts like %lld extra minutes.", Int(transferMinutes)))
                        .font(.caption).foregroundStyle(.secondary)
                }
                Picker(L10n.t("Extra time for changes"), selection: $extraTransfer) {
                    ForEach(SmartRouting.extraTransferChoices, id: \.self) { m in
                        Text(m == 0 ? L10n.t("None") : L10n.f("+%lld min", m)).tag(m)
                    }
                }
                if WalkingPace.isAvailable {
                    Toggle(L10n.t("Use my walking speed"), isOn: $ownSpeed)
                        .onChange(of: ownSpeed) { _, on in if on { Task { await measure() } } }
                    if ownSpeed {
                        if measuring {
                            ProgressView()
                        } else if measuredSpeed > 0 {
                            LabeledContent(L10n.t("Your walking speed"), value: Self.speedText(measuredSpeed))
                            if let source = WalkingPace.source {
                                Label(source == .health ? L10n.t("From Apple Health") : L10n.t("From the step counter"),
                                      systemImage: source == .health ? "heart.fill" : "figure.walk")
                                    .font(.caption).foregroundStyle(.secondary)
                            }
                        } else {
                            Text(L10n.t("Not enough walking measured yet.")).font(.caption).foregroundStyle(.secondary)
                        }
                    }
                }
                Toggle(L10n.t("Avoid steep walks"), isOn: $flatWalks)
                Toggle(L10n.t("Learn from my choices"), isOn: $learn)
                if learnedSomething {
                    Button(L10n.t("Reset learned preferences")) {
                        SmartRouting.resetLearning()
                        transferMinutes = SmartRouting.defaultTransferMinutes
                        walkWeight = SmartRouting.defaultWalkWeight
                    }
                }
            }
        } header: {
            Text(L10n.t("Smart routing"))
        } footer: {
            Text(L10n.t(enabled
                ? "Routes stay in time order. The one that feels shortest is marked “Recommended”: waiting, walking and changes count more than riding. Each change shows how likely it works and what to take if you miss it. Your choices tune this on your iPhone only."
                : "Routes are listed by departure, as the planner returns them."))
        }
        .task { if enabled && ownSpeed { await measure() } }
    }

    private func measure() async {
        measuring = true
        if let v = await WalkingPace.measure() { measuredSpeed = v }
        measuring = false
    }

    static func speedText(_ metersPerSecond: Double) -> String {
        Measurement(value: metersPerSecond * 3.6, unit: UnitSpeed.kilometersPerHour)
            .formatted(.measurement(width: .abbreviated, usage: .asProvided, numberFormatStyle: .number.precision(.fractionLength(1))))
    }
}
