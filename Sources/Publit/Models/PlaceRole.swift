import Foundation

/// Home and Work get a fixed slot so they are one tap away in the planner.
enum PlaceRole: String, Codable, CaseIterable, Identifiable, Sendable {
    case home, work
    var id: String { rawValue }
    var title: String { self == .home ? L10n.t("Home") : L10n.t("Work") }
    var symbol: String { self == .home ? "house.fill" : "briefcase.fill" }
}
