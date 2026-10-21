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
