import SwiftUI

/// "Ink & Dot" (docs/design/README.md): flat opaque cards and matte floating controls,
/// ink instead of a brand colour, the logo's yellow dot only for "you" and "live".
extension Color {
    static let ground = Color(Palette.ground)
    static let surface = Color(Palette.surface)
    static let inkFill = Color(Palette.fill)
    static let ink = Color(Palette.ink)
    static let onInk = Color(Palette.onInk)
    static let signature = Color(Palette.signature)
    static let inkAction = Color(Palette.inkAction)
    static let routeAhead = Color(Palette.routeAhead)
    static let routeTraveled = Color(Palette.routeTraveled)
}

/// The main action: an ink slab with the label in `onInk` (black on light, white on dark).
struct InkButtonStyle: ButtonStyle {
    @Environment(\.isEnabled) var isEnabled

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.headline)
            .foregroundStyle(Color.onInk)
            .padding(.horizontal, 18)
            .padding(.vertical, 11)
            .frame(minHeight: 44)
            .background(Color.ink, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
            .contentShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
            .opacity(isEnabled ? (configuration.isPressed ? 0.75 : 1) : 0.35)
    }
}

/// Switches turn the live green when on: an ink switch was black on light and nearly white on dark,
/// and its thumb disappeared against it.
struct InkSwitchStyle: ToggleStyle {
    func makeBody(configuration: Configuration) -> some View {
        #if os(Android)
        configuration.label
        #else
        Toggle(configuration).toggleStyle(.switch).tint(Color.live)
        #endif
    }
}

extension ButtonStyle where Self == InkButtonStyle {
    static var ink: InkButtonStyle { InkButtonStyle() }
}

/// The secondary action next to an ink one ("Other connections", "Now", "Open in Maps"):
/// a soft well with an ink label, the same corner as the ink button. Replaces system `.bordered`.
struct SoftButtonStyle: ButtonStyle {
    @Environment(\.isEnabled) var isEnabled

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.subheadline.weight(.semibold))
            .foregroundStyle(Color.ink)
            .padding(.horizontal, 14)
            .padding(.vertical, 8)
            .frame(minHeight: 40)
            .background(Color.inkFill, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
            .contentShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
            .opacity(isEnabled ? (configuration.isPressed ? 0.7 : 1) : 0.35)
    }
}

extension ButtonStyle where Self == SoftButtonStyle {
    static var soft: SoftButtonStyle { SoftButtonStyle() }
}

/// A round control floating over a map (layers, my location): white, a hairline, one soft shadow
/// so it stays apart from busy map tiles. 44 pt, ink symbol.
struct MapControlStyle: ButtonStyle {
    var selected = false

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.body.weight(.semibold))
            .foregroundStyle(selected ? Color.onInk : Color.ink)
            .frame(width: 44, height: 44)
            .background(selected ? Color.ink : Color.surface, in: Circle())
            .overlay(Circle().strokeBorder(Color.ink.opacity(selected ? 0 : 0.09), lineWidth: 1))
            .mapShadow()
            .contentShape(Circle())
            .opacity(configuration.isPressed ? 0.75 : 1)
    }
}

extension ButtonStyle where Self == MapControlStyle {
    static var mapControl: MapControlStyle { MapControlStyle() }
}

extension View {
    /// The one shadow in the design: things that float over a map. Lists and cards stay flat.
    func mapShadow() -> some View {
        shadow(color: .black.opacity(0.16), radius: 5, x: 0, y: 2)
    }

    /// A capsule floating over a map or list ("Back to now", "Recommended: …"): white, hairline, map shadow.
    func floatingCapsule() -> some View {
        padding(.horizontal, 14).padding(.vertical, 10)
            .matteSurface(in: Capsule())
            .mapShadow()
    }
}

/// The logo's dot: where you are, what is live.
struct SignatureDot: View {
    var size: CGFloat = 8
    var body: some View {
        Circle().fill(Color.signature).frame(width: size, height: size).accessibilityHidden(true)
    }
}

/// Progress as the logo's stroke: a round-capped track, filled in ink (or a status colour),
/// with the yellow dot where you are now.
struct InkProgress: View {
    let value: Double
    var tint: Color = .ink

    var body: some View {
        let v = min(max(value, 0), 1)
        GeometryReader { geo in
            let w = geo.size.width
            ZStack(alignment: .leading) {
                Capsule().fill(Color.inkFill).frame(height: 6)
                Capsule().fill(tint).frame(width: max(6, w * v), height: 6)
                Circle().fill(Color.signature)
                    .frame(width: 12, height: 12)
                    .overlay(Circle().strokeBorder(Color.surface, lineWidth: 2.5).padding(-2.5))
                    .offset(x: min(max(w * v - 6, 0), max(w - 12, 0)))
            }
            .frame(height: 12)
        }
        .frame(height: 12)
        .accessibilityElement()
        .accessibilityValue(Text(verbatim: "\(Int((v * 100).rounded())) %"))
    }
}

extension View {
    /// A list on the flat "Ink & Dot" ground with opaque cards (apply `inkCard()` to its sections).
    func inkList() -> some View {
        #if os(Android)
        // SkipUI draws the round section corners and the cover over each card's last divider in
        // the list background colour. A hidden (clear) background left square cards with a stray
        // line under each one. The Material scheme (PublitTheme.kt) makes that background the ground.
        listSectionSpacing(12)
            .liveBarPadding()
        #else
        scrollContentBackground(.hidden)
            .background(Color.ground)
            .listSectionSpacing(12)
            .liveBarPadding()
        #endif
    }

    /// Opaque floating controls with a quiet edge, shared across platforms (no blur).
    func matteSurface<S: InsettableShape>(in shape: S) -> some View {
        background(Color.surface, in: shape)
            .overlay(shape.strokeBorder(Color.ink.opacity(0.09), lineWidth: 1))
    }

    /// Rows of a list section as one flat card.
    func inkCard() -> some View { listRowBackground(Color.surface) }
}

/// A warning in one calm form for every screen: a coloured edge and symbol, the words in ink.
/// Only `critical` (cancelled, missed) gets a tinted card, so it stands out from everything else.
struct Notice: View {
    enum Level { case critical, change, info }
    let level: Level
    let title: String
    var detail: String? = nil

    var tint: Color {
        switch level {
        case .critical: return .danger
        case .change: return .delay
        case .info: return .secondary
        }
    }

    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: 10) {
            Image(systemName: level == .info ? "info.circle.fill" : "exclamationmark.circle.fill")
                .font(.subheadline.weight(.bold))
                .foregroundStyle(tint)
            VStack(alignment: .leading, spacing: 2) {
                Text(title).font(.subheadline.weight(.semibold)).foregroundStyle(Color.ink)
                if let detail { Text(detail).font(.footnote).foregroundStyle(.secondary) }
            }
            Spacer(minLength: 0)
        }
        .padding(.vertical, 2)
        .accessibilityElement(children: .combine)
        .listRowBackground(NoticeBackground(tint: tint, tinted: level == .critical))
    }
}

/// The card behind a notice row: a 4 pt edge in its colour.
struct NoticeBackground: View {
    let tint: Color
    var tinted = false

    var body: some View {
        Color.surface
            .overlay(tinted ? tint.opacity(0.07) : Color.clear)
            .overlay(alignment: .leading) { tint.frame(width: 4) }
    }
}

/// A short warning inside a row: the symbol in its colour, the words in ink (same calm form as `Notice`).
struct InlineNotice: View {
    let text: String
    var tint: Color = .delay
    var systemImage = "exclamationmark.triangle.fill"

    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: 6) {
            Image(systemName: systemImage).foregroundStyle(tint).frame(width: 16)
            Text(verbatim: text).foregroundStyle(Color.ink)
        }
        .font(.caption.weight(.semibold))
        .fixedSize(horizontal: false, vertical: true)
        .accessibilityElement(children: .combine)
    }
}

/// One empty state for every tab: symbol, title, a line of help and an optional ink action.
/// Wraps instead of truncating, also inside a list card.
struct EmptyState<Action: View>: View {
    let title: String
    let systemImage: String
    let message: String
    @ViewBuilder var action: () -> Action

    var body: some View {
        VStack(spacing: 10) {
            Image(systemName: systemImage)
                .font(.system(size: 34, weight: .regular))
                .foregroundStyle(.secondary)
                .padding(.bottom, 4)
                .accessibilityHidden(true)
            Text(verbatim: title)
                .font(.title3.weight(.bold))
                .multilineTextAlignment(.center)
                .fixedSize(horizontal: false, vertical: true)
            Text(verbatim: message)
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
                .fixedSize(horizontal: false, vertical: true)
            action().padding(.top, 6)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 24)
        .padding(.horizontal, 12)
        .accessibilityElement(children: .contain)
    }
}

extension EmptyState where Action == EmptyView {
    init(title: String, systemImage: String, message: String) {
        self.init(title: title, systemImage: systemImage, message: message) { EmptyView() }
    }
}

/// Small controls above a list (sort, map): the same capsule as the mode chips.
struct ChipButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.subheadline.weight(.semibold))
            .foregroundStyle(Color.ink)
            .padding(.horizontal, 12)
            .frame(minHeight: 34)
            .background(Color.inkFill, in: Capsule())
            .contentShape(Capsule())
            .opacity(configuration.isPressed ? 0.7 : 1)
    }
}

extension ButtonStyle where Self == ChipButtonStyle {
    static var chip: ChipButtonStyle { ChipButtonStyle() }
}

/// Publit's input look: a soft, flat well in the card (no inner shadow, no edge), with a generous
/// continuous corner, the same family as the Nearby notice cards.
struct BevelField: ViewModifier {
    var radius: CGFloat = 16

    func body(content: Content) -> some View {
        content.background(Color.inkFill, in: RoundedRectangle(cornerRadius: radius, style: .continuous))
    }
}

/// The raised counterpart: a round key on the card (surface, one soft shadow, no bevel).
struct RaisedKey: ViewModifier {
    @Environment(\.colorScheme) var scheme
    var radius: CGFloat = 16

    func body(content: Content) -> some View {
        content.background(
            RoundedRectangle(cornerRadius: radius, style: .continuous)
                .fill(Color.surface)
                .shadow(color: .black.opacity(scheme == .dark ? 0.35 : 0.08), radius: 6, x: 0, y: 2)
        )
    }
}

extension View {
    func bevelField(radius: CGFloat = 16) -> some View { modifier(BevelField(radius: radius)) }
    func raisedKey(radius: CGFloat = 16) -> some View { modifier(RaisedKey(radius: radius)) }
}

/// Two or three choices in a soft capsule track; the chosen one is a raised capsule (replaces the system segmented control).
struct BevelSegmented<Value: Hashable>: View {
    @Binding var selection: Value
    let options: [(Value, String)]
    /// Taller keys for a main control (Plan: Depart/Arrive).
    var large = false

    var body: some View {
        HStack(spacing: 4) {
            ForEach(options.indices, id: \.self) { i in
                let value = options[i].0
                let title = options[i].1
                let selected = value == selection
                Button {
                    withAnimation(.snappy(duration: 0.2)) { selection = value }
                } label: {
                    Text(verbatim: title)
                        .font((large ? Font.body : .subheadline).weight(selected ? .bold : .medium))
                        .foregroundStyle(selected ? Color.ink : Color.secondary)
                        .frame(maxWidth: .infinity, minHeight: large ? 40 : 32)
                        .background { if selected { Color.clear.raisedKey(radius: large ? 20 : 16) } }
                        .contentShape(Capsule())
                }
                .buttonStyle(.plain)
                .accessibilityAddTraits(selected ? .isSelected : [])
            }
        }
        .padding(3)
        .bevelField(radius: large ? 23 : 19)
    }
}

/// One row type for places and quick routes (Nearby, Saved, onboarding): a round soft key with the
/// symbol, the title in semibold like Plan's From/To, the detail below in secondary.
struct PlaceKeyRow: View {
    let symbol: String
    let title: String
    var detail: String? = nil
    /// A route starts on tap: the ink arrow on the right says so.
    var routes = false

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: symbol)
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(Color.ink)
                .frame(width: 34, height: 34)
                .background(Color.inkFill, in: Circle())
            VStack(alignment: .leading, spacing: 1) {
                Text(verbatim: title).font(.body.weight(.semibold)).foregroundStyle(.primary).lineLimit(1)
                if let detail, !detail.isEmpty {
                    Text(verbatim: detail).font(.subheadline).foregroundStyle(.secondary).lineLimit(1)
                }
            }
            Spacer(minLength: 0)
            if routes {
                Image(systemName: "arrow.right")
                    .font(.footnote.weight(.bold))
                    .foregroundStyle(Color.secondary)
            }
        }
        .frame(minHeight: 46)
        .contentShape(Rectangle())
        .accessibilityElement(children: .combine)
    }
}

/// The search entry in Plan's look (Nearby "Where to now?"): the From/To connector dot, a
/// secondary label, the prompt, and the round ink search key on the right.
struct WhereToField: View {
    let label: LocalizedStringKey
    let prompt: LocalizedStringKey

    var body: some View {
        HStack(spacing: 12) {
            SignatureDot(size: 11)
                .frame(width: 12)
            Text(label).font(.subheadline).foregroundStyle(.secondary).lineLimit(1).fixedSize()
                .frame(minWidth: 44, alignment: .leading)
            Text(prompt).font(.body.weight(.semibold)).foregroundStyle(.secondary).lineLimit(1)
            Spacer(minLength: 0)
            Image(systemName: "magnifyingglass")
                .font(.subheadline.weight(.bold))
                .foregroundStyle(Color.onInk)
                .frame(width: 34, height: 34)
                .background(Color.ink, in: Circle())
        }
        .frame(minHeight: 52)
        .contentShape(Rectangle())
    }
}
