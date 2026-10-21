import Foundation

/// GTFS line colours with a legibility check (WCAG 2 contrast).
enum LineColors {
    struct Pair: Equatable, Sendable {
        let background: String
        let text: String
    }

    struct RGB: Equatable, Sendable {
        let r: Double, g: Double, b: Double

        /// Parses `e3000f` or `#E3000F`.
        init?(hex: String?) {
            guard var s = hex?.trimmingCharacters(in: .whitespaces), !s.isEmpty else { return nil }
            if s.hasPrefix("#") { s.removeFirst() }
            guard s.count == 6, let v = UInt32(s, radix: 16) else { return nil }
            r = Double((v >> 16) & 0xFF) / 255
            g = Double((v >> 8) & 0xFF) / 255
            b = Double(v & 0xFF) / 255
        }

        var luminance: Double {
            func lin(_ c: Double) -> Double { c <= 0.03928 ? c / 12.92 : pow((c + 0.055) / 1.055, 2.4) }
            return 0.2126 * lin(r) + 0.7152 * lin(g) + 0.0722 * lin(b)
        }
    }

    static func contrast(_ a: RGB, _ b: RGB) -> Double {
        let (l1, l2) = (max(a.luminance, b.luminance), min(a.luminance, b.luminance))
        return (l1 + 0.05) / (l2 + 0.05)
    }

    static let white = RGB(hex: "FFFFFF")!
    static let black = RGB(hex: "000000")!

    /// Minimum ratio we accept for an operator's own text colour on its line colour
    /// (badge text is small, so WCAG AA requires 4.5:1, even when bold).
    static let minimumOperatorContrast = 4.5

    static func normalized(_ hex: String) -> String {
        hex.trimmingCharacters(in: .whitespaces).replacingOccurrences(of: "#", with: "").uppercased()
    }

    /// Keeps the operator's text colour when it is legible, otherwise picks white or black,
    /// whichever contrasts more with the background.
    static func resolve(routeColor: String?, routeTextColor: String?, fallback: String) -> Pair {
        let bgHex = RGB(hex: routeColor) != nil ? normalized(routeColor!) : normalized(fallback)
        guard let bg = RGB(hex: bgHex) else { return Pair(background: "4D4D59", text: "FFFFFF") }
        if routeColor != nil, let given = RGB(hex: routeTextColor),
           contrast(given, bg) >= minimumOperatorContrast {
            return Pair(background: bgHex, text: normalized(routeTextColor!))
        }
        return Pair(background: bgHex, text: contrast(white, bg) >= contrast(black, bg) ? "FFFFFF" : "000000")
    }

    // MARK: Lines on dark and light backgrounds

    /// Card and list background in dark mode (secondarySystemGroupedBackground) and in light mode.
    static let darkSurface = RGB(hex: "1C1C1E")!
    static let lightSurface = RGB(hex: "FFFFFF")!

    /// Below this a line colour melts into the background: near-black lines (Munich S8 #2D2B29,
    /// 1.2:1) in dark mode, white or pale yellow ones in light mode. Ordinary dark blues and greens
    /// (U-Bahn blue #0065AE: 2.8:1) stay above and keep their colour exactly.
    static let minimumSurfaceContrast = 1.8

    static func blendsIn(_ hex: String?, dark: Bool) -> Bool {
        guard let c = RGB(hex: hex) else { return false }
        return contrast(c, dark ? darkSurface : lightSurface) < minimumSurfaceContrast
    }

    /// For lines, bars and dots drawn without a badge: the operator's colour, lifted towards
    /// white (dark mode) or black (light mode) only as far as needed to stand out. Badges keep the
    /// exact colour and get an edge instead (`blendsIn`).
    static func strokeHex(_ hex: String, dark: Bool) -> String {
        guard let c = RGB(hex: hex) else { return normalized(hex) }
        let surface = dark ? darkSurface : lightSurface
        guard contrast(c, surface) < minimumSurfaceContrast else { return normalized(hex) }
        let target = dark ? white : black
        for step in 1...20 {
            let amount = Double(step) / 20
            let m = RGB(r: c.r + (target.r - c.r) * amount, g: c.g + (target.g - c.g) * amount, b: c.b + (target.b - c.b) * amount)
            if contrast(m, surface) >= minimumSurfaceContrast { return m.hex }
        }
        return target.hex
    }
}

extension LineColors.RGB {
    init(r: Double, g: Double, b: Double) {
        self.r = r; self.g = g; self.b = b
    }

    /// `2D2B29`
    var hex: String {
        func byte(_ v: Double) -> Int { max(0, min(255, Int((v * 255).rounded()))) }
        return String(format: "%02X%02X%02X", byte(r), byte(g), byte(b))
    }
}
