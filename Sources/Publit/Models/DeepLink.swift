import Foundation

/// `publit://trip/<saved trip id>` opens a saved trip with its live status,
/// `publit://ticket/<ticket id>` (or `publit://ticket` for the default pass) shows a ticket.
/// Used in calendar events, on the Live Activity, by the departures widget and by Siri.
/// `publit://route?…` carries a whole route (see `SharedRoute`), so it also works on someone else's
/// iPhone; `ShareLinks.web` wraps any link in an https address that can be sent to anyone.
enum DeepLink: Hashable, Identifiable {
    case trip(String)
    /// A route someone shared: fetched again from Transitous with its live state.
    case route(SharedRoute)
    case ticket(String?)
    /// Tickets for one saved trip (its single tickets plus valid passes): `publit://tickets/<trip id>`.
    case tripTickets(String)
    /// Departure board: `publit://stop/<stop id>?name=…&lat=…&lon=…` (from the widget).
    case stop(id: String, name: String, lat: Double, lon: Double)
    /// Routes from here to Home or Work, leaving now: `publit://go/home` (from Siri).
    case go(PlaceRole)
    /// Routes from here to a place shared from another map app: `publit://place?name=…&lat=…&lon=…`
    /// (from the share extension, see `MapLink`). Without coordinates the name is searched.
    case place(name: String, lat: Double?, lon: Double?)

    static let scheme = "publit"

    var id: String { url.absoluteString }

    init?(url: URL) {
        if let inner = ShareLinks.appURL(fromWeb: url) { self.init(url: inner); return }
        guard url.scheme?.lowercased() == Self.scheme, let host = url.host?.lowercased() else { return nil }
        let arg = url.pathComponents.dropFirst().first.flatMap { $0.removingPercentEncoding }
        switch host {
        case "trip":
            guard let arg, !arg.isEmpty else { return nil }
            self = .trip(arg)
        case "route":
            guard let route = SharedRoute(query: url.query ?? "") else { return nil }
            self = .route(route)
        case "ticket":
            self = .ticket(arg?.isEmpty == false ? arg : nil)
        case "tickets":
            guard let arg, !arg.isEmpty else { return nil }
            self = .tripTickets(arg)
        case "stop":
            let items = URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems ?? []
            func value(_ n: String) -> String? { items.first { $0.name == n }?.value }
            guard let arg, !arg.isEmpty, let lat = value("lat").flatMap(Double.init),
                  let lon = value("lon").flatMap(Double.init),
                  lat.isFinite, lon.isFinite, abs(lat) <= 90, abs(lon) <= 180 else { return nil }
            self = .stop(id: arg, name: value("name") ?? arg, lat: lat, lon: lon)
        case "go":
            guard let role = arg.flatMap(PlaceRole.init(rawValue:)) else { return nil }
            self = .go(role)
        case "place":
            let items = URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems ?? []
            func value(_ n: String) -> String? { items.first { $0.name == n }?.value.flatMap { $0.isEmpty ? nil : $0 } }
            let lat = value("lat").flatMap(Double.init), lon = value("lon").flatMap(Double.init)
            if let lat, let lon {
                guard lat.isFinite, lon.isFinite, abs(lat) <= 90, abs(lon) <= 180 else { return nil }
                self = .place(name: value("name") ?? String(format: "%.5f, %.5f", lat, lon), lat: lat, lon: lon)
            } else {
                guard let name = value("name") else { return nil }
                self = .place(name: name, lat: nil, lon: nil)
            }
        default:
            return nil
        }
    }

    var url: URL {
        var c = URLComponents()
        c.scheme = Self.scheme
        switch self {
        case .trip(let id): c.host = "trip"; c.path = "/" + id
        case .route(let r): c.host = "route"; c.percentEncodedQuery = r.query
        case .ticket(let id): c.host = "ticket"; c.path = id.map { "/" + $0 } ?? ""
        case .tripTickets(let id): c.host = "tickets"; c.path = "/" + id
        case .stop(let id, let name, let lat, let lon):
            c.host = "stop"; c.path = "/" + id
            c.queryItems = [URLQueryItem(name: "name", value: name),
                            URLQueryItem(name: "lat", value: String(lat)), URLQueryItem(name: "lon", value: String(lon))]
        case .go(let role): c.host = "go"; c.path = "/" + role.rawValue
        case .place(let name, let lat, let lon):
            c.host = "place"
            var items = [URLQueryItem(name: "name", value: name)]
            if let lat, let lon {
                items += [URLQueryItem(name: "lat", value: String(lat)), URLQueryItem(name: "lon", value: String(lon))]
            }
            c.queryItems = items
        }
        return c.url!
    }
}

/// A route as a link: what is needed to fetch it again on any iPhone, nothing from this device's storage.
/// `itineraryId` is the MOTIS itinerary ID (it encodes the vehicles and stops; `refresh-itinerary` answers
/// with the live state). Places and departure are there for the label and, when the ID no longer works
/// (old timetable), for searching the same route again.
struct SharedRoute: Hashable, Sendable {
    var itineraryId: String?
    var fromName: String
    var from: LatLon
    var toName: String
    var to: LatLon
    var departure: Date

    init(itineraryId: String?, fromName: String, from: LatLon, toName: String, to: LatLon, departure: Date) {
        self.itineraryId = itineraryId
        self.fromName = fromName
        self.from = from
        self.toName = toName
        self.to = to
        self.departure = departure
    }

    init(_ it: Itinerary, fromName: String, toName: String) {
        let start = it.legs.first?.from, end = it.legs.last?.to
        self.init(itineraryId: it.id.isEmpty ? nil : it.id,
                  fromName: fromName.isEmpty ? (start?.name ?? "") : fromName,
                  from: LatLon(lat: start?.lat ?? 0, lon: start?.lon ?? 0),
                  toName: toName.isEmpty ? (end?.name ?? "") : toName,
                  to: LatLon(lat: end?.lat ?? 0, lon: end?.lon ?? 0),
                  departure: it.legs.first?.scheduledStartTime ?? it.startTime)
    }

    /// `i=…&f=…&fl=…&t=…&tl=…&d=…`, every value percent-encoded (no `+`, safe in a URL fragment too).
    var query: String {
        var items: [(String, String)] = []
        if let itineraryId { items.append(("i", ShareLinks.pack(itineraryId))) }
        items += [("f", fromName), ("fl", from.apiValue), ("t", toName), ("tl", to.apiValue),
                  ("d", String(Int(departure.timeIntervalSince1970)))]
        return items.map { "\($0)=\(ShareLinks.encode($1))" }.joined(separator: "&")
    }

    init?(query: String) {
        var v: [String: String] = [:]
        for pair in query.split(separator: "&") {
            let kv = pair.split(separator: "=", maxSplits: 1).map(String.init)
            guard kv.count == 2, let value = kv[1].removingPercentEncoding else { continue }
            v[kv[0]] = value
        }
        guard let from = v["fl"].flatMap(LatLon.init(apiValue:)), let to = v["tl"].flatMap(LatLon.init(apiValue:)),
              let d = v["d"].flatMap(Double.init), d.isFinite else { return nil }
        self.init(itineraryId: v["i"].flatMap(ShareLinks.unpack), fromName: v["f"] ?? "", from: from,
                  toName: v["t"] ?? "", to: to, departure: Date(timeIntervalSince1970: d))
    }
}

/// Links for other people: `https://elija-7580.github.io/publit-site/open/#route?…` opens a small page
/// (docs/open/index.html, published with the website on GitHub Pages) that shows what was shared, opens Publit, or falls back to maps.
/// The part after `#` never reaches the web server.
enum ShareLinks {
    static let webHost = "elija-7580.github.io"
    static let webPath = "/publit-site/open/"
    /// Paths of links sent by older builds; still read when pasted.
    static let legacyPaths = ["/Publit-/open/", "/Publit/open/"]

    /// The https link for sending; the app link without `publit://` goes after `#`.
    static func web(_ link: DeepLink) -> URL {
        let app = link.url.absoluteString
        let rest = app.hasPrefix(DeepLink.scheme + "://") ? String(app.dropFirst(DeepLink.scheme.count + 3)) : app
        return URL(string: "https://\(webHost)\(webPath)#\(rest)")!
    }

    /// The `publit://` link inside a web share link (pasted, or opened as a universal link later).
    static func appURL(fromWeb url: URL) -> URL? {
        guard url.scheme?.lowercased() == "https", url.host?.lowercased() == webHost,
              ([webPath] + legacyPaths).contains(where: { url.path.hasPrefix(String($0.dropLast())) }),
              let fragment = URLComponents(url: url, resolvingAgainstBaseURL: false)?.percentEncodedFragment,
              !fragment.isEmpty else { return nil }
        return URL(string: "\(DeepLink.scheme)://\(fragment)")
    }

    private static let unreserved = CharacterSet(charactersIn:
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~")

    static func encode(_ s: String) -> String { s.addingPercentEncoding(withAllowedCharacters: unreserved) ?? "" }

    /// The itinerary ID is base64 protobuf (1–2 KB). Compressed it is about half as long:
    /// `z` + base64url of raw DEFLATE; `b` + base64url when compression is not available.
    static func pack(_ id: String) -> String {
        guard let data = Data(base64Encoded: id) else { return "s" + id }
        #if canImport(Darwin)
        if let z = try? (data as NSData).compressed(using: .zlib) as Data, z.count < data.count {
            return "z" + base64url(z)
        }
        #endif
        return "b" + base64url(data)
    }

    static func unpack(_ s: String) -> String? {
        guard let kind = s.first else { return nil }
        let body = String(s.dropFirst())
        switch kind {
        case "s": return body.isEmpty ? nil : body
        case "b": return data(base64url: body)?.base64EncodedString()
        case "z":
            #if canImport(Darwin)
            guard let z = data(base64url: body),
                  let raw = try? (z as NSData).decompressed(using: .zlib) as Data else { return nil }
            return raw.base64EncodedString()
            #else
            return nil
            #endif
        default: return nil
        }
    }

    static func base64url(_ data: Data) -> String {
        data.base64EncodedString().replacingOccurrences(of: "+", with: "-").replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }

    static func data(base64url s: String) -> Data? {
        var b = s.replacingOccurrences(of: "-", with: "+").replacingOccurrences(of: "_", with: "/")
        while b.count % 4 != 0 { b += "=" }
        return Data(base64Encoded: b)
    }
}
