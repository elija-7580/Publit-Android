import SwiftUI

extension Color {
    /// Parses GTFS-style hex colours (`e3000f` or `#e3000f`).
    init?(hex: String?) {
        guard let rgb = LineColors.RGB(hex: hex) else { return nil }
        self.init(red: rgb.r, green: rgb.g, blue: rgb.b)
    }
}

extension RouteStyled {
    var lineColor: Color { Color(hex: lineColors.background) ?? .gray }
    var lineTextColor: Color { Color(hex: lineColors.text) ?? .white }
}

extension ModeStyle {
    static func color(_ mode: String) -> Color { Color(hex: defaultHex(mode)) ?? .gray }
}

#if canImport(UIKit) && !os(watchOS)
import UIKit

extension Color {
    /// Light/dark adaptive colour from a `Palette.Tone`.
    init(_ tone: Palette.Tone) {
        let l = LineColors.RGB(hex: tone.light)!
        let d = LineColors.RGB(hex: tone.dark) ?? l
        self.init(uiColor: UIColor { trait in
            let c = trait.userInterfaceStyle == .dark ? d : l
            return UIColor(red: c.r, green: c.g, blue: c.b, alpha: 1)
        })
    }
    static let live = Color(Palette.live)
    static let delay = Color(Palette.delay)
    static let danger = Color(Palette.danger)
    static let appAccent = Color(Palette.accent)

    /// A line colour for strokes, bars and dots: near-black lines are lifted in dark mode,
    /// white and pale ones darkened in light mode (`LineColors.strokeHex`).
    init(lineStroke hex: String) {
        let l = LineColors.RGB(hex: LineColors.strokeHex(hex, dark: false)) ?? LineColors.black
        let d = LineColors.RGB(hex: LineColors.strokeHex(hex, dark: true)) ?? LineColors.white
        self.init(uiColor: UIColor { trait in
            let c = trait.userInterfaceStyle == .dark ? d : l
            return UIColor(red: c.r, green: c.g, blue: c.b, alpha: 1)
        })
    }
}

extension RouteStyled {
    /// For route lines on maps, timeline bars, progress and stop lines (not for badge fills).
    var lineStroke: Color { Color(lineStroke: lineColors.background) }
}
#elseif os(Android)
extension Color {
    /// Light/dark adaptive colour from a `Palette.Tone`: the tones ship as colour sets with a dark
    /// appearance (Resources/Module.xcassets/Palette-*), which Compose resolves for the current mode.
    init(_ tone: Palette.Tone) {
        if let name = Self.toneAssets["\(tone.light)|\(tone.dark)"] {
            self.init("Palette-" + name, bundle: .module)
        } else {
            let c = LineColors.RGB(hex: AndroidEnvironment.isDark ? tone.dark : tone.light) ?? LineColors.black
            self.init(red: c.r, green: c.g, blue: c.b)
        }
    }
    private static let toneAssets: [String: String] = {
        let all: [(String, Palette.Tone)] = [("live", Palette.live), ("delay", Palette.delay), ("danger", Palette.danger),
            ("accent", Palette.accent), ("ground", Palette.ground), ("surface", Palette.surface), ("fill", Palette.fill),
            ("ink", Palette.ink), ("onInk", Palette.onInk), ("inkAction", Palette.inkAction),
            ("routeAhead", Palette.routeAhead), ("routeTraveled", Palette.routeTraveled), ("signature", Palette.signature)]
        var map: [String: String] = [:]
        for (name, t) in all { map["\(t.light)|\(t.dark)"] = name }
        return map
    }()
    static let live = Color(Palette.live)
    static let delay = Color(Palette.delay)
    static let danger = Color(Palette.danger)
    static let appAccent = Color(Palette.accent)

    /// A line colour for strokes, bars and dots, for the mode the phone is in now.
    init(lineStroke hex: String) {
        let dark = AndroidEnvironment.isDark
        let c = LineColors.RGB(hex: LineColors.strokeHex(hex, dark: dark)) ?? (dark ? LineColors.white : LineColors.black)
        self.init(red: c.r, green: c.g, blue: c.b)
    }
}

extension RouteStyled {
    var lineStroke: Color { Color(lineStroke: lineColors.background) }
}
#elseif os(watchOS)
// The watch is always dark and has no trait-based colours: the dark tones only.
extension Color {
    init(_ tone: Palette.Tone) {
        let c = LineColors.RGB(hex: tone.dark) ?? LineColors.RGB(hex: tone.light)!
        self.init(red: c.r, green: c.g, blue: c.b)
    }
    static let live = Color(Palette.live)
    static let delay = Color(Palette.delay)
    static let danger = Color(Palette.danger)
    static let appAccent = Color(Palette.accent)

    init(lineStroke hex: String) {
        let c = LineColors.RGB(hex: LineColors.strokeHex(hex, dark: true)) ?? LineColors.white
        self.init(red: c.r, green: c.g, blue: c.b)
    }
}

extension RouteStyled {
    var lineStroke: Color { Color(lineStroke: lineColors.background) }
}
#endif
