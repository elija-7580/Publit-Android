#if os(Android)
import SwiftUI
import SkipFuse

// Android: SwiftUI modifiers and types that Skip does not bridge (yet). Declared in this module,
// they take precedence over Skip's unavailable stubs, so the shared views compile unchanged.
// Each one either maps to the closest Compose behaviour or is a no-op where Android has no equivalent
// (accessibility grouping, tap shapes: a Compose clickable already covers the whole frame).

// MARK: Text and fonts

extension Font {
    /// Roboto's digits are tabular already.
    func monospacedDigit(_ isActive: Bool = true) -> Font { self }
}

extension Text {
    func monospacedDigit() -> Text { self }
}

extension View {
    @_disfavoredOverload func monospacedDigit() -> some View { self }
    func dynamicTypeSize(_ size: DynamicTypeSize) -> some View { self }
    func dynamicTypeSize(_ range: Range<DynamicTypeSize>) -> some View { self }
    func dynamicTypeSize(_ range: ClosedRange<DynamicTypeSize>) -> some View { self }
    func dynamicTypeSize(_ range: PartialRangeThrough<DynamicTypeSize>) -> some View { self }
}

extension ShapeStyle where Self == HierarchicalShapeStyle {
    /// Compose has two text emphasis levels; the third falls back to the second.
    static var tertiary: HierarchicalShapeStyle { .secondary }
}

/// One run of `StyledText`.
struct TextRun {
    var text: String
    var color: Color?
    var size: CGFloat?
    /// Compose weight, 100…900.
    var weight: Int?

    init(_ text: String, color: Color? = nil, size: CGFloat? = nil, weight: Font.Weight? = nil) {
        self.text = text
        self.color = color
        self.size = size
        self.weight = weight.map(TextRun.composeWeight)
    }

    static func composeWeight(_ w: Font.Weight) -> Int {
        switch w {
        case .ultraLight: return 100
        case .thin: return 200
        case .light: return 300
        case .medium: return 500
        case .semibold: return 600
        case .bold: return 700
        case .heavy: return 800
        case .black: return 900
        default: return 400
        }
    }
}

/// Text with differently styled parts in one paragraph (SwiftUI's `Text + Text`), drawn by SkipUI
/// as one Compose AnnotatedString, so it wraps like a single text.
struct StyledText: View, SkipUIBridging {
    typealias Body = Never
    let runs: [TextRun]

    init(_ runs: [TextRun]) { self.runs = runs }

    var Java_view: any SkipUI.View {
        var flags: [Int] = []
        for r in runs {
            var f = 0
            if r.color != nil { f |= 1 }
            if r.size != nil { f |= 2 }
            if r.weight != nil { f |= 4 }
            flags.append(f)
        }
        return SkipUI.Text(bridgedRuns: runs.map { SkipUI.Text(verbatim: $0.text) },
                           colors: runs.map { ($0.color ?? Color.primary).Java_view },
                           fontSizes: runs.map { Double($0.size ?? 0) },
                           fontWeights: runs.map { $0.weight ?? 400 },
                           flags: flags)
    }
}

// MARK: Shapes

extension InsettableShape {
    /// Skip declares this twice (inlinable and bridged); this picks the bridged one.
    func strokeBorder<S: ShapeStyle>(_ content: S, lineWidth: CGFloat = 1, antialiased: Bool = true) -> StrokeBorderShapeView<Self, S, EmptyView> {
        let view: StrokeBorderShapeView<Self, S, EmptyView> = strokeBorder(content, style: StrokeStyle(lineWidth: lineWidth), antialiased: antialiased)
        return view
    }
}

// MARK: Accessibility

extension View {
    func accessibilityElement(children: AccessibilityChildBehavior = .ignore) -> some View { self }

    @_disfavoredOverload func accessibilityLabel<S: StringProtocol>(_ label: S) -> some View {
        accessibilityLabel(Text(verbatim: String(label)))
    }

    func accessibilityHint(_ hint: Text, isEnabled: Bool = true) -> some View { self }
    func accessibilityHint(_ hintKey: LocalizedStringKey, isEnabled: Bool = true) -> some View { self }
    @_disfavoredOverload func accessibilityHint<S: StringProtocol>(_ hint: S, isEnabled: Bool = true) -> some View { self }
}

// MARK: Layout

extension View {
    func contentShape<S: Shape>(_ shape: S, eoFill: Bool = false) -> some View { self }
    func listRowInsets(_ insets: EdgeInsets?) -> some View { self }
    func listSectionSpacing(_ spacing: ListSectionSpacing) -> some View { self }
    func listSectionSpacing(_ spacing: CGFloat) -> some View { self }

    func safeAreaPadding(_ insets: EdgeInsets) -> some View { padding(insets) }
    func safeAreaPadding(_ edges: Edge.Set = .all, _ length: CGFloat? = nil) -> some View { padding(edges, length) }
    func safeAreaPadding(_ length: CGFloat) -> some View { padding(length) }

    func controlSize(_ controlSize: ControlSize) -> some View {
        scaleEffect(controlSize == .mini ? 0.6 : controlSize == .small ? 0.8 : 1)
    }

    func alignmentGuide(_ g: HorizontalAlignment, computeValue: @escaping (ViewDimensions) -> CGFloat) -> some View { self }
    func alignmentGuide(_ g: VerticalAlignment, computeValue: @escaping (ViewDimensions) -> CGFloat) -> some View { self }

    /// The pager cards of a trip span the screen: the screen width stands in for the scroll view's.
    func containerRelativeFrame(_ axes: Axis.Set, alignment: Alignment = .center,
                                _ length: @escaping (CGFloat, Axis) -> CGFloat) -> some View {
        frame(width: axes.contains(.horizontal) ? length(DroidScreen.widthPoints, .horizontal) : nil,
              height: axes.contains(.vertical) ? length(DroidScreen.heightPoints, .vertical) : nil,
              alignment: alignment)
    }

    func scrollPosition<ID: Hashable>(id: Binding<ID?>, anchor: UnitPoint? = nil) -> some View {
        scrollPosition(id: Binding<AnyHashable?>(get: { id.wrappedValue.map { AnyHashable($0) } },
                                                 set: { id.wrappedValue = $0?.base as? ID }), anchor: anchor)
    }

    func imageScale(_ scale: Image.Scale) -> some View {
        scaleEffect(scale == .small ? 0.8 : scale == .large ? 1.2 : 1)
    }

    func indexViewStyle(_ style: IndexViewStyleShim) -> some View { self }

    func searchScopes<V: Hashable, S: View>(_ scope: Binding<V>, activation: SearchScopeActivation = .automatic,
                                            @ViewBuilder _ scopes: () -> S) -> some View { self }
}

struct ViewDimensions {
    var width: CGFloat = 0
    var height: CGFloat = 0
    subscript(_ guide: HorizontalAlignment) -> CGFloat { 0 }
    subscript(_ guide: VerticalAlignment) -> CGFloat { 0 }
}

struct IndexViewStyleShim {
    enum BackgroundDisplayMode { case automatic, always, interactive, never }
    static func page(backgroundDisplayMode: BackgroundDisplayMode = .automatic) -> IndexViewStyleShim { IndexViewStyleShim() }
    static var page: IndexViewStyleShim { IndexViewStyleShim() }
}

extension SearchFieldPlacement {
    enum NavigationBarDrawerDisplayMode { case automatic, always }
    static func navigationBarDrawer(displayMode: NavigationBarDrawerDisplayMode = .automatic) -> SearchFieldPlacement { .automatic }
    static var navigationBarDrawer: SearchFieldPlacement { .automatic }
}

extension SearchScopeActivation {
    static var onTextEntry: SearchScopeActivation { .automatic }
    static var onSearchPresentation: SearchScopeActivation { .automatic }
}

extension PickerStyle where Self == MenuPickerStyle {
    /// An inline list of choices has no Compose counterpart in Skip: a menu in the row instead.
    static var inline: MenuPickerStyle { .menu }
}

/// `AnyLayout(VStackLayout(...))` / `AnyLayout(HStackLayout(...))` switching (large text sizes).
struct VStackLayout {
    var alignment: HorizontalAlignment = .center
    var spacing: CGFloat? = nil
}

struct HStackLayout {
    var alignment: VerticalAlignment = .center
    var spacing: CGFloat? = nil
}

struct AnyLayout {
    private enum Kind { case v(VStackLayout), h(HStackLayout) }
    private let kind: Kind
    init(_ layout: VStackLayout) { kind = .v(layout) }
    init(_ layout: HStackLayout) { kind = .h(layout) }

    @ViewBuilder
    func callAsFunction<C: View>(@ViewBuilder _ content: () -> C) -> some View {
        switch kind {
        case .v(let l): VStack(alignment: l.alignment, spacing: l.spacing) { content() }
        case .h(let l): HStack(alignment: l.alignment, spacing: l.spacing) { content() }
        }
    }
}

/// `Grid` of label/value cells: rows of equal-width cells.
struct Grid<Content: View>: View {
    var alignment: Alignment = .center
    var horizontalSpacing: CGFloat?
    var verticalSpacing: CGFloat?
    var content: Content

    init(alignment: Alignment = .center, horizontalSpacing: CGFloat? = nil, verticalSpacing: CGFloat? = nil,
         @ViewBuilder content: () -> Content) {
        self.alignment = alignment
        self.horizontalSpacing = horizontalSpacing
        self.verticalSpacing = verticalSpacing
        self.content = content()
    }

    var body: some View {
        VStack(alignment: alignment.horizontal, spacing: verticalSpacing) { content }
            .environment(\.gridSpacing, horizontalSpacing)
    }
}

struct GridRow<Content: View>: View {
    var alignment: VerticalAlignment = .top
    var content: Content
    @Environment(\.gridSpacing) var spacing

    init(alignment: VerticalAlignment = .top, @ViewBuilder content: () -> Content) {
        self.alignment = alignment
        self.content = content()
    }

    var body: some View {
        HStack(alignment: alignment, spacing: spacing) { content }
    }
}

private struct GridSpacingKey: EnvironmentKey {
    static let defaultValue: CGFloat? = nil
}

extension EnvironmentValues {
    var gridSpacing: CGFloat? {
        get { self[GridSpacingKey.self] }
        set { self[GridSpacingKey.self] = newValue }
    }
}

// MARK: Animation

extension Transaction {
    /// Compose has no per-change animation switch: changes in such a transaction are applied without one.
    var disablesAnimations: Bool {
        get { animation == nil }
        set { if newValue { animation = nil } }
    }
}

func withTransaction<Result>(_ transaction: Transaction, _ body: () throws -> Result) rethrows -> Result {
    if let animation = transaction.animation {
        return try withAnimation(animation, body)
    }
    return try body()
}

// MARK: Empty states

/// SwiftUI's empty-state view, drawn like Publit's own `EmptyState`.
struct ContentUnavailableView<Label: View, Description: View, Actions: View>: View {
    var label: Label
    var description: Description
    var actions: Actions

    init(@ViewBuilder label: () -> Label, @ViewBuilder description: () -> Description = { EmptyView() },
         @ViewBuilder actions: () -> Actions = { EmptyView() }) {
        self.label = label()
        self.description = description()
        self.actions = actions()
    }

    var body: some View {
        VStack(spacing: 10) {
            label
                .font(.title3.weight(.bold))
                .labelStyle(.titleAndIcon)
                .multilineTextAlignment(.center)
            description
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
            actions.padding(.top, 6)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .padding(.vertical, 24)
        .padding(.horizontal, 24)
    }
}

/// Symbol above the title, as in SwiftUI's empty states.
struct UnavailableLabel: View {
    let title: Text
    let systemImage: String

    var body: some View {
        VStack(spacing: 12) {
            Image(systemName: systemImage)
                .font(.system(size: 44, weight: .regular))
                .foregroundStyle(.secondary)
            title.font(.title3.weight(.bold))
        }
    }
}

extension ContentUnavailableView where Label == UnavailableLabel, Description == Text?, Actions == EmptyView {
    init(_ title: LocalizedStringKey, systemImage: String, description: Text? = nil) {
        self.label = UnavailableLabel(title: Text(title), systemImage: systemImage)
        self.description = description
        self.actions = EmptyView()
    }

    @_disfavoredOverload
    init<S: StringProtocol>(_ title: S, systemImage: String, description: Text? = nil) {
        self.label = UnavailableLabel(title: Text(verbatim: String(title)), systemImage: systemImage)
        self.description = description
        self.actions = EmptyView()
    }

    /// "No results for …".
    static func search(text: String) -> ContentUnavailableView<UnavailableLabel, Text?, EmptyView> {
        ContentUnavailableView(L10n.t("No Results"), systemImage: "magnifyingglass",
                               description: text.isEmpty ? nil : Text(verbatim: L10n.t("Check the spelling or try a new search.")))
    }

    static var search: ContentUnavailableView<UnavailableLabel, Text?, EmptyView> { search(text: "") }
}

// MARK: Colours

extension Color {
    init(uiColor: UIColor) {
        if uiColor === AdaptiveUIColor.surface { self = .surface } else { self.init(uiColor) }
    }
}

extension UIColor {
    /// Cards in grouped lists: Publit's surface tone (adaptive).
    static var secondarySystemGroupedBackground: UIColor { AdaptiveUIColor.surface }

    convenience init(hex: String) {
        let c = LineColors.RGB(hex: hex) ?? LineColors.RGB(hex: "8e8e93")!
        self.init(red: c.r, green: c.g, blue: c.b, alpha: 1)
    }
}

/// A placeholder object that `Color(uiColor:)` turns into the adaptive palette colour.
enum AdaptiveUIColor {
    static let surface = UIColor(red: 1, green: 1, blue: 1, alpha: 0.999)
}

// MARK: Button styles

// Skip draws only its built-in button styles. Publit's own ones become the same look around a plain
// button (Compose puts the click on the whole styled frame); only the pressed dimming is missing.

private struct EnabledOpacity: ViewModifier {
    @Environment(\.isEnabled) var isEnabled
    func body(content: Content) -> some View { content.opacity(isEnabled ? 1 : 0.35) }
}

extension View {
    func buttonStyle(_ style: InkButtonStyle) -> some View {
        buttonStyle(.plain)
            .font(.headline)
            .foregroundStyle(Color.onInk)
            .padding(.horizontal, 18)
            .padding(.vertical, 11)
            .frame(minHeight: 44)
            .background(Color.ink, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
            .modifier(EnabledOpacity())
    }

    func buttonStyle(_ style: SoftButtonStyle) -> some View {
        buttonStyle(.plain)
            .font(.subheadline.weight(.semibold))
            .foregroundStyle(Color.ink)
            .padding(.horizontal, 14)
            .padding(.vertical, 8)
            .frame(minHeight: 40)
            .background(Color.inkFill, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
            .modifier(EnabledOpacity())
    }

    func buttonStyle(_ style: MapControlStyle) -> some View {
        buttonStyle(.plain)
            .font(.body.weight(.semibold))
            .foregroundStyle(style.selected ? Color.onInk : Color.ink)
            .frame(width: 44, height: 44)
            .background(style.selected ? Color.ink : Color.surface, in: Circle())
            .overlay(Circle().stroke(Color.ink.opacity(style.selected ? 0 : 0.09), lineWidth: 1))
            .mapShadow()
    }

    func buttonStyle(_ style: ChipButtonStyle) -> some View {
        buttonStyle(.plain)
            .font(.subheadline.weight(.semibold))
            .foregroundStyle(Color.ink)
            .padding(.horizontal, 12)
            .frame(minHeight: 34)
            .background(Color.inkFill, in: Capsule())
    }

    func buttonStyle(_ style: ActionButtonStyle) -> some View {
        buttonStyle(.plain)
            .padding(12).frame(maxWidth: .infinity)
            .foregroundStyle(Color.ink)
            .background(Color.ink.opacity(0.1), in: RoundedRectangle(cornerRadius: 14))
    }

    /// Custom switch style: set on the whole app; Android keeps its Material switch.
    func toggleStyle(_ style: InkSwitchStyle) -> some View { self }
}

// MARK: Device

/// Screen size in points (Compose dp).
enum DroidScreen {
    static var widthPoints: CGFloat { CGFloat(Droid.double("screenWidthDp") ?? 360) }
    static var heightPoints: CGFloat { CGFloat(Droid.double("screenHeightDp") ?? 640) }
}

/// The window's brightness override: 0…1, or -1 for the system setting.
@MainActor final class UIScreen {
    static let main = UIScreen()
    var brightness: CGFloat {
        get { CGFloat(Droid.double("screenBrightness") ?? -1) }
        set { Droid.call("setScreenBrightness", Double(newValue)) }
    }
}

@MainActor final class UIDevice {
    static let current = UIDevice()
    var systemName: String { "Android" }
    var systemVersion: String { Droid.string("androidVersion") ?? "" }
    var model: String { Droid.string("deviceModel") ?? "Android" }
}

/// Full brightness while a code is on screen, as scanners at gates need; the system setting comes back
/// when it leaves the screen or the app goes to the background.
struct BrightnessBoost: ViewModifier {
    var active = true
    @Environment(\.scenePhase) var phase
    @State var boosted = false

    func body(content: Content) -> some View {
        content
            .onAppear(perform: boost)
            .onDisappear(perform: restore)
            .onChange(of: phase) { _, phase in phase == .active ? boost() : restore() }
    }

    private func boost() {
        guard active, !boosted else { return }
        boosted = true
        UIScreen.main.brightness = 1
    }

    private func restore() {
        guard boosted else { return }
        boosted = false
        UIScreen.main.brightness = -1
    }
}

extension View {
    func brightnessBoost(_ active: Bool = true) -> some View { modifier(BrightnessBoost(active: active)) }
}
#endif
