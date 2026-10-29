#if os(Android)
import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

/// Android: "Find nearby" without Apple Maps. The places of one kind come from OpenStreetMap
/// (Overpass API) for the visible area. Free-text search needs no second source here:
/// the Transitous geocoder already finds OSM places and addresses.
enum OSMPlaces {
    // Public instances can be overloaded. Try a bounded fallback rather than silently
    // turning one provider outage into an empty "Find nearby" result.
    static let endpoints = [
        URL(string: "https://overpass-api.de/api/interpreter")!,
        URL(string: "https://maps.mail.ru/osm/tools/overpass/api/interpreter")!,
        URL(string: "https://overpass.private.coffee/api/interpreter")!,
    ]

    /// Overpass filters for each category, matching Apple's categories as closely as OSM allows.
    static func filters(_ c: PlaceCategory) -> [String] {
        switch c {
        case .restaurant: return [#"["amenity"="restaurant"]"#]
        case .cafe: return [#"["amenity"="cafe"]"#]
        case .bakery: return [#"["shop"="bakery"]"#]
        case .supermarket: return [#"["shop"="supermarket"]"#]
        case .store: return [#"["shop"]["shop"!~"^(supermarket|bakery|vacant)$"]["name"]"#]
        case .pharmacy: return [#"["amenity"="pharmacy"]"#]
        case .hospital: return [#"["amenity"="hospital"]"#]
        case .hotel: return [#"["tourism"="hotel"]"#]
        case .atm: return [#"["amenity"="atm"]"#, #"["amenity"="bank"]["atm"="yes"]"#]
        case .parking: return [#"["amenity"="parking"]["access"!~"^(private|no)$"]"#]
        case .evCharger: return [#"["amenity"="charging_station"]"#]
        case .gasStation: return [#"["amenity"="fuel"]"#]
        case .restroom: return [#"["amenity"="toilets"]"#]
        case .museum: return [#"["tourism"="museum"]"#]
        case .park: return [#"["leisure"="park"]["name"]"#]
        case .nightlife: return [#"["amenity"~"^(bar|pub|nightclub)$"]"#]
        }
    }

    static func query(_ c: PlaceCategory, south: Double, west: Double, north: Double, east: Double) -> String {
        let box = String(format: "(%.5f,%.5f,%.5f,%.5f)", south, west, north, east)
        let parts = filters(c).map { "nwr\($0)\(box);" }.joined()
        return "[out:json][timeout:20];(\(parts));out center 60;"
    }

    static func places(in area: MKCoordinateRegion, category: PlaceCategory) async throws -> [GeocodeMatch] {
        let dLat = min(area.span.latitudeDelta * 1.1, 0.25) / 2
        let dLon = min(area.span.longitudeDelta * 1.1, 0.35) / 2
        let c = area.center
        let q = query(category, south: c.latitude - dLat, west: c.longitude - dLon, north: c.latitude + dLat, east: c.longitude + dLon)
        var allowed = CharacterSet.alphanumerics
        allowed.insert(charactersIn: "-._*")
        let body = ("data=" + (q.addingPercentEncoding(withAllowedCharacters: allowed) ?? q)).data(using: .utf8)
        for endpoint in endpoints {
            try Task.checkCancellation()
            var request = URLRequest(url: endpoint)
            request.httpMethod = "POST"
            request.timeoutInterval = 15
            request.setValue("application/x-www-form-urlencoded", forHTTPHeaderField: "Content-Type")
            request.setValue(AppInfo.userAgent, forHTTPHeaderField: "User-Agent")
            request.httpBody = body
            do {
                let (data, response) = try await URLSession.shared.data(for: request)
                try Task.checkCancellation()
                guard (response as? HTTPURLResponse)?.statusCode == 200,
                      let result = try? JSONDecoder().decode(Response.self, from: data) else { continue }
                return matches(result, category: category)
            } catch {
                try Task.checkCancellation()
            }
        }
        // An outage is not a successful search with zero places.
        throw URLError(.cannotConnectToHost)
    }

    private static func matches(_ result: Response, category: PlaceCategory) -> [GeocodeMatch] {
        return result.elements.prefix(60).compactMap { e in
            guard let lat = e.lat ?? e.center?.lat, let lon = e.lon ?? e.center?.lon else { return nil }
            let point = LatLon(lat: lat, lon: lon)
            guard point.isValidCoordinate else { return nil }
            let tags = e.tags ?? [:]
            let name = tags["name"] ?? tags["brand"] ?? tags["operator"] ?? category.title
            return .picked(name: name, at: point, street: tags["addr:street"], houseNumber: tags["addr:housenumber"],
                           locality: tags["addr:city"], country: nil, category: category.word)
        }
    }

    struct Response: Decodable {
        let elements: [Element]
    }

    struct Element: Decodable {
        let lat: Double?
        let lon: Double?
        let center: Center?
        let tags: [String: String]?
    }

    struct Center: Decodable {
        let lat: Double
        let lon: Double
    }
}
#endif
