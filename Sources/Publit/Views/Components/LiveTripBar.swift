import SwiftUI

/// A bar above the tab bar while a saved trip is on: what to do next ("Leave in 6 min",
/// "Get off at …"), the line, delay and how far along the trip is. Tap for the trip with live status.
/// Shown 30 min before leaving until 5 min after arrival, or for the trip started by hand.
/// ▶ starts the trip; a started trip shows as a dark live bar with a ring and a countdown.
struct LiveTripBar: View {
    @Environment(TripStore.self) var trips
    @Environment(AppState.self) var app
    @Environment(PassStore.self) var passes
    @Environment(\.scenePhase) var scenePhase
    @Environment(\.accessibilityReduceMotion) var reduceMotion
    @GestureState(resetTransaction: Transaction(animation: .spring(response: 0.35, dampingFraction: 0.8))) var pull: CGFloat = 0
    /// Hidden by hand for this trip only; the next trip shows again.
    @AppStorage("liveBarHiddenTrip") var hiddenId = ""

    var body: some View {
        // Minute clock: countdowns are in whole minutes, so one redraw a minute is enough.
        let now = Ticker.minute.now
        let current = trips.current(now: now).flatMap { $0.id == hiddenId ? nil : $0 }
        Group {
            if let trip = current {
                bar(trip, now: now)
                    .transition(reduceMotion ? .opacity : .move(edge: .bottom).combined(with: .opacity))
                    .task(id: "\(trip.id)|\(scenePhase)") { await follow(trip.id) }
            }
        }
        .animation(reduceMotion ? nil : .spring(response: 0.42, dampingFraction: 0.86), value: current?.id)
    }

    private func bar(_ trip: SavedTrip, now: Date) -> some View {
        let it = trip.itinerary
        let started = trips.activeTrip?.id == trip.id
        // Started: the countdown stands on its own, so the headline says "Leave at 14:05".
        let status = TripStatus.at(now, it, countdown: !started)
        let leg = it.transitLegs.first { $0.endTime > now }
        let total = max(it.endTime.timeIntervalSince(it.startTime), 60)
        let progress = min(max(now.timeIntervalSince(it.startTime) / total, 0), 1)
        let alert = status.phase == .cancelled || status.delayMinutes > 0
        let color: Color = status.phase == .cancelled ? .danger : status.delayMinutes > 0 ? .delay : .ink
        return HStack(spacing: 10) {
            Button { app.deepLink = .trip(trip.id) } label: {
                if started {
                    liveContent(trip, status: status, leg: leg, progress: progress, now: now)
                } else {
                    HStack(spacing: 10) {
                        if let leg { LineBadge(leg) } else {
                            Image(systemName: status.phase == .arrived ? "flag.checkered" : "figure.walk")
                                .font(.body.weight(.semibold)).frame(width: 28)
                        }
                        VStack(alignment: .leading, spacing: 4) {
                            HStack(spacing: 6) {
                                Text(verbatim: status.headline).font(.subheadline.weight(.semibold)).lineLimit(1)
                                if status.delayMinutes > 0 {
                                    Text(verbatim: "+\(status.delayMinutes)").font(.caption.weight(.bold).monospacedDigit())
                                        .foregroundStyle(Color.delay)
                                }
                            }
                            Text(verbatim: status.detail.isEmpty ? trip.title : status.detail)
                                .font(.caption).foregroundStyle(.secondary).lineLimit(1)
                            InkProgress(value: progress, tint: color).padding(.top, 2)
                        }
                        .frame(maxWidth: .infinity, alignment: .leading)
                    }
                    .contentShape(Rectangle())
                }
            }
            .buttonStyle(.plain)
            .accessibilityElement(children: .combine)
            .accessibilityIdentifier("liveTripBar")
            if !started {
                if status.phase != .arrived && status.phase != .cancelled {
                    // Like starting a workout: one tap and the trip screen guides you.
                    Button { Task { await start(trip) } } label: {
                        Image(systemName: "play.fill").font(.subheadline.weight(.bold)).foregroundStyle(Color.black)
                            .frame(width: 36, height: 36)
                            .background(Color.signature, in: Circle())
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel(Text("Start trip"))
                    .accessibilityIdentifier("liveTripBarStart")
                }
                Button { hiddenId = trip.id } label: {
                    Image(systemName: "xmark").font(.caption.weight(.bold)).foregroundStyle(.secondary)
                        .frame(width: 28, height: 32).contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel(Text("Hide"))
            }
        }
        .padding(.horizontal, 12).padding(.vertical, started ? 10 : 8)
        .background {
            if started {
                RoundedRectangle(cornerRadius: 24, style: .continuous).fill(Color.ink)
            } else {
                RoundedRectangle(cornerRadius: 22, style: .continuous).fill(Color.surface)
            }
        }
        .overlay(RoundedRectangle(cornerRadius: started ? 24 : 22, style: .continuous)
            .strokeBorder(started ? Color.clear : alert ? color.opacity(0.35) : Color.primary.opacity(0.08)))
        .overlay(alignment: .top) {
            Capsule().fill(started ? Color.onInk.opacity(0.35) : Color.ink.opacity(0.2))
                .frame(width: 24, height: 3).padding(.top, 3).accessibilityHidden(true)
        }
        .offset(y: reduceMotion ? 0 : pull)
        .simultaneousGesture(DragGesture(minimumDistance: 12)
            .updating($pull) { value, state, transaction in
                transaction.animation = nil
                if abs(value.translation.height) > abs(value.translation.width) {
                    state = max(-36, min(0, value.translation.height * 0.25))
                }
            }
            .onEnded { value in
                if abs(value.translation.height) > abs(value.translation.width),
                   value.translation.height < -40 || (value.translation.height < -15 && value.predictedEndTranslation.height < -100) {
                    app.deepLink = .trip(trip.id)
                }
            })
        .accessibilityAction(named: Text("Open trip")) { app.deepLink = .trip(trip.id) }
        .padding(.horizontal, 10).padding(.bottom, 6)
    }

    /// A started trip, like a running workout: a ring for the trip so far, what to do now,
    /// and a big countdown to the next moment (leave, departure, get off).
    private func liveContent(_ trip: SavedTrip, status: TripStatus, leg: Leg?, progress: Double, now: Date) -> some View {
        let mins = Int(ceil(status.nextEvent.timeIntervalSince(now) / 60))
        let showCountdown = status.phase != .arrived && status.phase != .cancelled && mins >= 0
        let symbol: String = switch status.phase {
        case .beforeLeaving: "figure.walk.departure"
        case .walking: "figure.walk"
        case .transfer: leg.map { ModeStyle.symbol($0.mode) } ?? "arrow.triangle.swap"
        case .riding: leg.map { ModeStyle.symbol($0.mode) } ?? "tram.fill"
        case .arrived: "flag.checkered"
        case .cancelled: "exclamationmark.triangle.fill"
        }
        return HStack(spacing: 12) {
            ZStack {
                Circle().stroke(Color.onInk.opacity(0.18), lineWidth: 4)
                Circle().trim(from: 0, to: max(progress, 0.02))
                    .stroke(Color.signature, style: StrokeStyle(lineWidth: 4, lineCap: .round))
                    .rotationEffect(.degrees(-90))
                Image(systemName: symbol).font(.system(size: 16, weight: .bold)).foregroundStyle(Color.onInk)
            }
            .frame(width: 42, height: 42)
            .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 3) {
                HStack(spacing: 5) {
                    Circle().fill(status.phase == .cancelled ? Color.danger : Color.live).frame(width: 6, height: 6)
                    Text("Live trip").font(.caption2.weight(.bold)).textCase(.uppercase)
                        .foregroundStyle(Color.onInk.opacity(0.7))
                    if let leg, status.phase == .riding || status.phase == .transfer {
                        LineBadge(leg, compact: true)
                    }
                }
                HStack(spacing: 6) {
                    Text(verbatim: status.headline).font(.subheadline.weight(.bold)).lineLimit(1)
                    if status.delayMinutes > 0 {
                        Text(verbatim: "+\(status.delayMinutes)").font(.caption.weight(.bold).monospacedDigit())
                            .foregroundStyle(Color.delay)
                    }
                }
                .foregroundStyle(Color.onInk)
                Text(verbatim: status.detail.isEmpty ? trip.title : status.detail)
                    .font(.caption).foregroundStyle(Color.onInk.opacity(0.7)).lineLimit(1)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            if showCountdown {
                VStack(spacing: 0) {
                    if mins < 60 {
                        Text(verbatim: "\(mins)").font(.title2.weight(.bold).monospacedDigit())
                            .contentTransition(.numericText(value: Double(mins)))
                            .animation(reduceMotion ? nil : .easeInOut(duration: 0.25), value: mins)
                        Text("min").font(.caption2.weight(.semibold)).foregroundStyle(Color.onInk.opacity(0.7))
                    } else {
                        Text(Fmt.time(status.nextEvent, tz: trip.itinerary.legs.first?.from.tz))
                            .font(.headline.weight(.bold).monospacedDigit())
                    }
                }
                .foregroundStyle(Color.onInk)
                .fixedSize()
            }
            Image(systemName: "chevron.up").font(.caption.weight(.bold)).foregroundStyle(Color.onInk.opacity(0.5))
                .accessibilityHidden(true)
        }
        .contentShape(Rectangle())
    }

    private func start(_ trip: SavedTrip) async {
        app.deepLink = .trip(trip.id)
        await TripSession.start(trip, in: trips, hasTicket: !passes.passes(forTrip: trip.id, at: trip.startTime).isEmpty)
    }

    /// Live times every 90 s while the app is open and the trip is not over.
    private func follow(_ id: String) async {
        guard scenePhase == .active else { return }
        while !Task.isCancelled {
            guard let t = trips.trip(id), t.endTime > .now else { return }
            await SavedTripView.refresh(t, in: trips)
            try? await Task.sleep(for: Energy.duration(90))
        }
    }
}

/// NavigationStack's destinations do not reliably honour an inset applied outside the stack.
/// Measure the actual bar (Dynamic Type included), then reserve it on each content scroller.
private struct LiveBarHeightKey: PreferenceKey {
    static var defaultValue: CGFloat { 0 }
    static func reduce(value: inout CGFloat, nextValue: () -> CGFloat) { value = max(value, nextValue()) }
}

private struct LiveBarInsetKey: EnvironmentKey {
    static let defaultValue: CGFloat = 0
}

extension EnvironmentValues {
    var liveBarInset: CGFloat {
        get { self[LiveBarInsetKey.self] }
        set { self[LiveBarInsetKey.self] = newValue }
    }
}

struct LiveTripBarHost: ViewModifier {
    @State var height: CGFloat = 0

    func body(content: Content) -> some View {
        content
            .environment(\.liveBarInset, height)
            // Everything in the tab keeps clear of the bar, not only lists: buttons and notes
            // pinned to the bottom (Plan's search key, "Updated", map controls) were hidden behind it.
            // Scrolling content still runs under it.
            .safeAreaPadding(.bottom, height)
            .overlay(alignment: .bottom) {
                VStack(spacing: 0) { LiveTripBar() }
                    .fixedSize(horizontal: false, vertical: true)
                    .background {
                        GeometryReader { proxy in
                            Color.clear.preference(key: LiveBarHeightKey.self, value: proxy.size.height)
                        }
                    }
            }
            .onPreferenceChange(LiveBarHeightKey.self) { value in
                // Geometry must not inherit row/appearance animations and bounce the list.
                guard abs(value - height) > 0.5 else { return }
                var transaction = Transaction()
                transaction.disablesAnimations = true
                withTransaction(transaction) { height = value }
            }
    }
}

struct LiveBarPadding: ViewModifier {
    @Environment(\.liveBarInset) var height
    /// The host already pads the whole tab (see `LiveTripBarHost`); kept so call sites stay valid.
    func body(content: Content) -> some View { content }
}

extension View {
    /// Install once, on the tab's NavigationStack. Sheets with no bar reset liveBarInset to zero.
    func liveTripBar() -> some View { modifier(LiveTripBarHost()) }
    func liveBarPadding() -> some View { modifier(LiveBarPadding()) }
}
