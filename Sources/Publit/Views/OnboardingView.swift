import SwiftUI

/// First launch: what Publit is, why it asks for your location, Home and Work, and where the
/// live trip and widgets are. Three short steps, each can be skipped; no account.
struct OnboardingView: View {
    static let doneKey = "onboardingDone"

    /// Not for screenshot and demo runs (UI tests pass `-onboardingDone YES`).
    static var needed: Bool {
        let env = ProcessInfo.processInfo.environment
        return !UserDefaults.standard.bool(forKey: doneKey) && env["PUBLIT_UI_SAMPLE"] == nil && env["PUBLIT_DEMO"] == nil
    }

    @Environment(LocationManager.self) var location
    @Environment(FavoritesStore.self) var favorites
    @AppStorage(OnboardingView.doneKey) var done = false
    @State var step = 0
    @State var picking: PlaceRole?

    var body: some View {
        VStack(spacing: 0) {
            HStack {
                Spacer()
                if step < 3 {
                    Button("Skip") { finish() }
                        .font(.subheadline.weight(.semibold))
                        .accessibilityIdentifier("onboardingSkip")
                }
            }
            .frame(height: 44)
            .padding(.horizontal, 20)

            TabView(selection: $step) {
                page(symbol: nil, title: L10n.t("Public transport, without the noise."),
                     text: L10n.t("Departures near you, routes across Europe and a guide until you get off. No ads, no account, no tracking."))
                    .tag(0)
                page(symbol: "location.circle", title: L10n.t("Departures where you are"),
                     text: L10n.t("Publit uses your location to show what leaves nearby and to plan from here. It is only sent with a search, never stored or shared."))
                    .tag(1)
                homeWork.tag(2)
                page(symbol: "dot.radiowaves.left.and.right", title: L10n.t("On the way"),
                     text: L10n.t("Start a route and Publit guides you: live times, changes, where to get off, also on the Lock Screen. Add the Publit widget for your next departures."))
                    .tag(3)
            }
            .tabViewStyle(.page(indexDisplayMode: .always))
            .indexViewStyle(.page(backgroundDisplayMode: .always))

            VStack(spacing: 10) {
                Button { primary() } label: {
                    Text(primaryTitle).frame(maxWidth: .infinity)
                }
                .buttonStyle(.ink)
                .accessibilityIdentifier("onboardingNext")
                if step == 1 {
                    Button("Not now") { withAnimation { step = 2 } }
                        .buttonStyle(.soft)
                }
            }
            .padding(.horizontal, 24)
            .padding(.bottom, 24)
        }
        .background(Color.ground.ignoresSafeArea())
        .sheet(item: $picking) { role in
            PlaceSearchView(title: role == .home ? "Home" : "Work", allowCurrentLocation: false) { endpoint in
                if case .place(let p) = endpoint { favorites.set(p, as: role) }
            }
        }
    }

    private var primaryTitle: String {
        switch step {
        case 1: return L10n.t("Allow location")
        case 3: return L10n.t("Get started")
        default: return L10n.t("Continue")
        }
    }

    private func primary() {
        switch step {
        case 1:
            location.start()
            withAnimation { step = 2 }
        case 3: finish()
        default: withAnimation { step += 1 }
        }
    }

    private func finish() {
        done = true
        location.start()
    }

    private func page(symbol: String?, title: String, text: String) -> some View {
        VStack(spacing: 20) {
            Spacer()
            if let symbol {
                Image(systemName: symbol)
                    .font(.system(size: 56, weight: .semibold))
                    .foregroundStyle(Color.ink)
                    .accessibilityHidden(true)
            } else {
                PublitAppIcon(size: 96)
            }
            Text(verbatim: title).font(.title.bold()).multilineTextAlignment(.center)
            Text(verbatim: text).font(.body).foregroundStyle(.secondary).multilineTextAlignment(.center)
                .fixedSize(horizontal: false, vertical: true)
            Spacer()
            Spacer()
        }
        .padding(.horizontal, 32)
    }

    private var homeWork: some View {
        VStack(spacing: 20) {
            Spacer()
            Image(systemName: "house")
                .font(.system(size: 56, weight: .semibold))
                .foregroundStyle(Color.ink)
                .accessibilityHidden(true)
            Text("Home and Work").font(.title.bold())
            Text("One tap to get home or to work, from anywhere. You can change them any time in Saved.")
                .font(.body).foregroundStyle(.secondary).multilineTextAlignment(.center)
                .fixedSize(horizontal: false, vertical: true)
            VStack(spacing: 10) {
                ForEach(PlaceRole.allCases) { role in
                    Button { picking = role } label: {
                        HStack(spacing: 12) {
                            Image(systemName: role.symbol).frame(width: 24)
                            VStack(alignment: .leading, spacing: 2) {
                                Text(verbatim: role.title).font(.subheadline.weight(.semibold))
                                Text(verbatim: favorites.place(for: role)?.name ?? L10n.t("Not set"))
                                    .font(.caption).foregroundStyle(.secondary).lineLimit(1)
                            }
                            Spacer()
                            Image(systemName: favorites.place(for: role) == nil ? "plus.circle" : "checkmark.circle.fill")
                                .foregroundStyle(favorites.place(for: role) == nil ? Color.secondary : Color.live)
                        }
                        .foregroundStyle(Color.ink)
                        .padding(14)
                        .background(Color.surface, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
                    }
                    .buttonStyle(.plain)
                    .accessibilityIdentifier("onboarding-\(role.rawValue)")
                }
            }
            Spacer()
            Spacer()
        }
        .padding(.horizontal, 32)
    }
}
