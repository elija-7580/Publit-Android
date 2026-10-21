import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

/// Place search with Google (Places API, New), when it is chosen in Settings and a key is set.
/// Suggestions come without coordinates; the place is looked up once it is picked, in the same
/// session (Google bills a typed search and its pick together).
enum GooglePlaces {
    static let keyDefaultsKey = "googleAPIKey"

    /// The key from Settings, else one built into the app (`GoogleAPIKey` in Info.plist).
    static var apiKey: String? {
        let own = UserDefaults.standard.string(forKey: keyDefaultsKey)?.trimmingCharacters(in: .whitespacesAndNewlines)
        if let own, !own.isEmpty { return own }
        let built = (Bundle.main.object(forInfoDictionaryKey: "GoogleAPIKey") as? String)?.trimmingCharacters(in: .whitespaces)
        guard let built, !built.isEmpty, !built.hasPrefix("$(") else { return nil }
        return built
    }

    /// Google search is on and can be used.
    static var isActive: Bool { SearchProvider.current == .google && apiKey != nil }

    struct Suggestion: Identifiable, Hashable, Sendable {
        let id: String
        let name: String
        let subtitle: String
        /// Google's place types, e.g. "restaurant", "transit_station", "street_address".
        let types: [String]
        var isStop: Bool { types.contains { $0.contains("station") || $0 == "bus_stop" } }
        var isAddress: Bool { types.contains { $0 == "street_address" || $0 == "premise" || $0 == "route" } }
    }

    enum Failure: LocalizedError {
        case noKey, http(Int, String?)
        var errorDescription: String? {
            switch self {
            case .noKey: return L10n.t("Google search needs an API key in Settings.")
            case .http(_, let message): return message ?? L10n.t("Google search is not available right now.")
            }
        }
    }

    static func autocomplete(_ text: String, near: LatLon?, session: String) async throws -> [Suggestion] {
        var body: [String: Any] = ["input": text, "sessionToken": session, "languageCode": language]
        if let near {
            body["locationBias"] = ["circle": ["center": ["latitude": near.lat, "longitude": near.lon], "radius": 50_000.0]]
            body["origin"] = ["latitude": near.lat, "longitude": near.lon]
        }
        let data = try await request("https://places.googleapis.com/v1/places:autocomplete", body: body, fields: nil)
        let res = try JSONDecoder().decode(AutocompleteResponse.self, from: data)
        return (res.suggestions ?? []).compactMap { s in
            guard let p = s.placePrediction else { return nil }
            let name = p.structuredFormat?.mainText?.text ?? p.text?.text ?? ""
            guard !name.isEmpty else { return nil }
            return Suggestion(id: p.placeId, name: name, subtitle: p.structuredFormat?.secondaryText?.text ?? "", types: p.types ?? [])
        }
    }

    /// The picked suggestion with its coordinates, as a search hit like any other.
    static func place(_ s: Suggestion, session: String) async throws -> GeocodeMatch {
        var c = URLComponents(string: "https://places.googleapis.com/v1/places/\(s.id)")!
        c.queryItems = [URLQueryItem(name: "sessionToken", value: session), URLQueryItem(name: "languageCode", value: language)]
        let data = try await request(c.url!.absoluteString, body: nil, fields: "id,displayName,formattedAddress,location")
        let p = try JSONDecoder().decode(PlaceResponse.self, from: data)
        return GeocodeMatch.picked(name: p.displayName?.text ?? s.name, at: LatLon(lat: p.location.latitude, lon: p.location.longitude),
                                   locality: s.subtitle.isEmpty ? p.formattedAddress : s.subtitle)
    }

    private static var language: String { Locale.preferredLanguages.first.map { String($0.prefix(2)) } ?? "en" }

    private static func request(_ url: String, body: [String: Any]?, fields: String?) async throws -> Data {
        guard let key = apiKey else { throw Failure.noKey }
        var r = URLRequest(url: URL(string: url)!, timeoutInterval: 12)
        r.setValue(key, forHTTPHeaderField: "X-Goog-Api-Key")
        // Keys restricted to this iOS app are checked against the bundle id.
        if let bundle = Bundle.main.bundleIdentifier { r.setValue(bundle, forHTTPHeaderField: "X-Ios-Bundle-Identifier") }
        if let fields { r.setValue(fields, forHTTPHeaderField: "X-Goog-FieldMask") }
        if let body {
            r.httpMethod = "POST"
            r.setValue("application/json", forHTTPHeaderField: "Content-Type")
            r.httpBody = try JSONSerialization.data(withJSONObject: body)
        }
        let (data, response) = try await URLSession.shared.data(for: r)
        let code = (response as? HTTPURLResponse)?.statusCode ?? 0
        guard (200..<300).contains(code) else {
            let message = (try? JSONDecoder().decode(ErrorResponse.self, from: data))?.error.message
            throw Failure.http(code, message)
        }
        return data
    }

    private struct Text: Decodable { let text: String? }
    private struct AutocompleteResponse: Decodable {
        struct S: Decodable { let placePrediction: P? }
        struct P: Decodable {
            let placeId: String
            let text: Text?
            let structuredFormat: F?
            let types: [String]?
        }
        struct F: Decodable { let mainText: Text?; let secondaryText: Text? }
        let suggestions: [S]?
    }
    private struct PlaceResponse: Decodable {
        struct L: Decodable { let latitude: Double; let longitude: Double }
        let displayName: Text?
        let formattedAddress: String?
        let location: L
    }
    private struct ErrorResponse: Decodable {
        struct E: Decodable { let message: String? }
        let error: E
    }
}

/// Who answers the place search.
enum SearchProvider: String, CaseIterable, Identifiable {
    /// Transitous (OpenStreetMap): stops, addresses and places, nothing leaves for anyone else.
    case transitous
    /// Google Places: shops, restaurants and sights as in Google Maps; stops still come from Transitous.
    case google

    static let defaultsKey = "searchProvider"
    static var current: SearchProvider {
        SearchProvider(rawValue: UserDefaults.standard.string(forKey: defaultsKey) ?? "") ?? .transitous
    }

    var id: String { rawValue }
    var title: String {
        switch self {
        case .transitous: return L10n.t("OpenStreetMap (Transitous)")
        case .google: return "Google"
        }
    }
}
