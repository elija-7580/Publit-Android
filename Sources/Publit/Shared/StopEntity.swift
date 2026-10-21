#if canImport(AppIntents)
import AppIntents
#endif
import Foundation

/// A stop for Siri, Shortcuts and the departures widget. All data lives in the identifier,
/// so the widget can restore it without access to the app's saved places.
/// Android: no App Intents; the same value type for links and launcher shortcuts.
struct StopEntity: Identifiable, Hashable, Sendable {

    let id: String
    let stopId: String
    let name: String
    let lat: Double
    let lon: Double


    init(stopId: String, name: String, lat: Double, lon: Double) {
        self.stopId = stopId; self.name = name; self.lat = lat; self.lon = lon
        id = "\(lat),\(lon)|\(stopId)|\(name)"
    }

    init?(id: String) {
        let parts = id.split(separator: "|", maxSplits: 2, omittingEmptySubsequences: false).map(String.init)
        let coords = parts.first?.split(separator: ",").compactMap { Double($0) } ?? []
        guard parts.count == 3, coords.count == 2, LatLon(lat: coords[0], lon: coords[1]).isValidCoordinate, !parts[1].isEmpty else { return nil }
        self.init(stopId: parts[1], name: parts[2], lat: coords[0], lon: coords[1])
    }

    var deepLink: URL { DeepLink.stop(id: stopId, name: name, lat: lat, lon: lon).url }
}

struct StopQuery {
    func entities(for identifiers: [StopEntity.ID]) async throws -> [StopEntity] {
        identifiers.compactMap(StopEntity.init(id:))
    }

    func entities(matching string: String) async throws -> [StopEntity] {
        let text = string.trimmingCharacters(in: .whitespaces)
        guard text.count >= 2 else { return [] }
        return try await TransitousClient.shared.geocode(text, near: nil, type: "STOP")
            .filter(\.isStop)
            .map { StopEntity(stopId: $0.id, name: $0.name, lat: $0.lat, lon: $0.lon) }
    }

    /// The user's saved stops (in the app; the widget cannot read them and offers search instead).
    func suggestedEntities() async throws -> [StopEntity] {
        StopQuery.savedStops()
    }

    private struct Saved: Decodable { let name: String; let lat: Double; let lon: Double; let stopId: String? }

    static func savedStops(defaults: UserDefaults = .standard) -> [StopEntity] {
        guard let data = defaults.data(forKey: "publit.favorites.v1"),
              let items = try? JSONDecoder().decode([Saved].self, from: data) else { return [] }
        return items.compactMap { s in
            s.stopId.map { StopEntity(stopId: $0, name: s.name, lat: s.lat, lon: s.lon) }
        }
    }
}

#if canImport(AppIntents) && !os(Android)
extension StopEntity: AppEntity {
    static var typeDisplayRepresentation: TypeDisplayRepresentation = "Stop"
    static var defaultQuery = StopQuery()
    var displayRepresentation: DisplayRepresentation { DisplayRepresentation(title: "\(name)") }
}

extension StopQuery: EntityStringQuery {}
#endif
