import Foundation

/// The look of a pass. Only the background is chosen; text and label colours follow from it
/// so they always reach WCAG AA contrast (4.5:1), on screen and in Apple Wallet.
struct PassStyle: Codable, Hashable, Sendable {
    /// `RRGGBB`.
    let background: String

    init(background: String) {
        let hex = LineColors.normalized(background)
        self.background = LineColors.RGB(hex: hex) != nil ? hex : "111111"
    }

    static let standard = PassStyle(background: "111111")

    struct Preset: Identifiable, Sendable {
        let style: PassStyle
        let name: String
        var id: String { style.background }
    }

    /// Calm, distinct colours. Names are for VoiceOver.
    static var presets: [Preset] {
        [("111111", "Black"), ("FFC531", "Yellow"), ("D70015", "Red"), ("0066D6", "Blue"), ("1B7A33", "Green"),
         ("5E3B8C", "Purple"), ("00727A", "Teal"), ("B25000", "Orange"), ("3A3A3C", "Graphite"), ("F2F2F7", "Light")]
            .map { Preset(style: PassStyle(background: $0.0), name: L10n.t($0.1)) }
    }

    private var rgb: LineColors.RGB { LineColors.RGB(hex: background) ?? LineColors.black }

    /// White or black, whichever contrasts more.
    var foreground: String {
        LineColors.contrast(LineColors.white, rgb) >= LineColors.contrast(LineColors.black, rgb) ? "FFFFFF" : "000000"
    }

    /// Field labels: the text colour blended a little towards the background, as long as it stays legible.
    var label: String {
        let fg = LineColors.RGB(hex: foreground)!
        for amount in [0.30, 0.22, 0.14] {
            let mixed = Self.mix(fg, rgb, amount)
            if LineColors.contrast(mixed, rgb) >= 4.5 { return Self.hex(mixed) }
        }
        return foreground
    }

    var isLight: Bool { foreground == "000000" }

    /// `rgb(r, g, b)`, the colour format of pass.json.
    static func walletColor(_ hex: String) -> String {
        let c = LineColors.RGB(hex: hex) ?? LineColors.black
        return "rgb(\(Int((c.r * 255).rounded())), \(Int((c.g * 255).rounded())), \(Int((c.b * 255).rounded())))"
    }

    /// Operator colour when the operator is known.
    static func suggested(forOperator name: String?) -> PassStyle? {
        guard let name, let known = KnownOperators.find(name) else { return nil }
        return PassStyle(background: known.color)
    }

    static func mix(_ a: LineColors.RGB, _ b: LineColors.RGB, _ amount: Double) -> LineColors.RGB {
        LineColors.RGB(hex: hex(LineColors.RGBValues(r: a.r + (b.r - a.r) * amount, g: a.g + (b.g - a.g) * amount, b: a.b + (b.b - a.b) * amount)))!
    }

    static func hex(_ c: LineColors.RGB) -> String { hex(LineColors.RGBValues(r: c.r, g: c.g, b: c.b)) }

    static func hex(_ c: LineColors.RGBValues) -> String {
        func byte(_ v: Double) -> Int { max(0, min(255, Int((v * 255).rounded()))) }
        return String(format: "%02X%02X%02X", byte(c.r), byte(c.g), byte(c.b))
    }
}

extension LineColors {
    /// Plain components for colour arithmetic (RGB itself is built from hex).
    struct RGBValues: Sendable { let r: Double, g: Double, b: Double }
}
