import SwiftUI

// One app state, shared with the Kotlin callbacks (DroidEvents). Defined on every platform so the
// iOS pre-build that Skip runs before the Android build also compiles.
extension AppState {
    static let shared = AppState()
}

#if os(Android)

/// Environment values iOS provides that SkipUI does not (yet): read from Android.
enum AndroidEnvironment {
    /// Android's font scale as the nearest Dynamic Type size (Settings → Display → Font size).
    static var dynamicTypeSize: DynamicTypeSize {
        let scale = Droid.double("fontScale") ?? 1
        switch scale {
        case ..<0.9: return .small
        case ..<1.05: return .large
        case ..<1.2: return .xLarge
        case ..<1.4: return .xxLarge
        case ..<1.6: return .xxxLarge
        case ..<1.8: return .accessibility1
        default: return .accessibility2
        }
    }

    /// The system is in dark mode (for colours that iOS resolves per trait).
    static var isDark: Bool { Droid.bool("isDark") }

    /// Screen pixels per point (dp), like iOS's displayScale.
    static var displayScale: CGFloat { CGFloat(Droid.double("density") ?? 2) }
}
#endif
