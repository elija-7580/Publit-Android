import Foundation

/// The map under stations and lines. Stored by raw value (`mapBase`), so keep the names.
enum MapBase: String, CaseIterable, Identifiable, Sendable {
    /// Apple's normal map.
    case standard
    /// Paler streets and buildings: stations and lines stand out, like Apple Maps' transit view.
    /// The default of every map (`defaultValue`).
    case muted
    /// Aerial imagery with street and place names.
    case satellite
    /// Aerial imagery only.
    case imagery

    var id: String { rawValue }

    var title: String {
        switch self {
        case .standard: return L10n.t("Standard")
        case .muted: return L10n.t("Transit")
        case .satellite: return L10n.t("Satellite")
        case .imagery: return L10n.t("Satellite without names")
        }
    }

    var symbol: String {
        switch self {
        case .standard: return "map"
        case .muted: return "tram.fill"
        case .satellite: return "globe.europe.africa.fill"
        case .imagery: return "photo"
        }
    }

    var isSatellite: Bool { self == .satellite || self == .imagery }
    /// Traffic and points of interest only exist on maps with names.
    var hasLabels: Bool { self != .imagery }

    /// What `mapBase` holds until it is changed: the transit look, lines first.
    static let defaultValue = MapBase.muted.rawValue

    init(storageValue: String) { self = MapBase(rawValue: storageValue) ?? .standard }
}

/// Everything about the map's look that the layers sheet changes. Equatable, so the map view
/// only swaps its configuration when something really changed.
struct MapLook: Equatable, Sendable {
    var base: MapBase = .standard
    /// Terrain and buildings in 3D (Apple's realistic elevation).
    var realistic3D = false
    /// Shops, sights and the like from Apple Maps (public transport stays off: those are our pins).
    var pointsOfInterest = false
    var traffic = false

    var showsPointsOfInterest: Bool { pointsOfInterest && base.hasLabels }
    var showsTraffic: Bool { traffic && base.hasLabels }
}

/// Searching places and towns on the map.
enum MapSearch {
    /// Shortest text that is sent to the geocoder.
    static let minLength = 2

    /// How much map (metres from edge to edge) to show for a hit: a city as a whole, a district,
    /// a village, a stop or an address up close. Stays below the span at which stations are hidden.
    static func meters(type: String, category: String?) -> Double {
        switch type {
        case "STOP": return 900
        case "ADDRESS": return 450
        default: break
        }
        let c = (category ?? "").lowercased()
        if c.hasPrefix("place") || c == "city" || c == "town" || c == "municipality" { return 6000 }
        switch c {
        case "suburb", "borough", "quarter", "district", "county": return 3500
        case "village", "neighbourhood", "island": return 2500
        case "hamlet", "locality", "isolated_dwelling", "farm": return 1200
        default: return 600
        }
    }

    static func meters(_ m: GeocodeMatch) -> Double { meters(type: m.type, category: m.category) }

    /// The text worth searching for, or nil while it is too short.
    static func query(_ text: String) -> String? {
        let t = text.trimmingCharacters(in: .whitespacesAndNewlines)
        return t.count >= minLength ? t : nil
    }

    /// SF Symbol for a hit in the list.
    static func symbol(_ m: GeocodeMatch) -> String {
        switch m.type {
        case "STOP": return "tram.circle.fill"
        case "ADDRESS": return "house.circle.fill"
        default: return meters(m) >= 2500 ? "building.2.crop.circle.fill" : "mappin.circle.fill"
        }
    }
}
