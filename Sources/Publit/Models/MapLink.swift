import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

/// A place shared from another map app: Google Maps (also its short links), Apple Maps,
/// OpenStreetMap, `geo:` links, or plain coordinates. Publit plans the route there.
/// Only the link is read; short links are followed once to their full map address.
struct MapLink: Equatable, Sendable {
    var name: String?
    var lat: Double?
    var lon: Double?
    /// A short link (maps.app.goo.gl) that has to be followed before it says where.
    var shortLink: URL?

    var hasCoordinate: Bool { lat != nil && lon != nil }

    /// `publit://place?name=…&lat=…&lon=…`: what the share extension hands to the app.
    var appURL: URL? {
        guard hasCoordinate || name != nil else { return nil }
        var c = URLComponents()
        c.scheme = "publit"
        c.host = "place"
        var items: [URLQueryItem] = []
        if let name { items.append(URLQueryItem(name: "name", value: name)) }
        if let lat, let lon {
            items.append(URLQueryItem(name: "lat", value: String(format: "%.6f", lat)))
            items.append(URLQueryItem(name: "lon", value: String(format: "%.6f", lon)))
        }
        c.queryItems = items
        return c.url
    }

    // MARK: Parsing

    /// The first map link in shared text ("Caffè Cannaregio\nhttps://maps.app.goo.gl/…"); the text
    /// before it names the place when the link does not.
    static func parse(text: String) -> MapLink? {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        if let c = coordinates(trimmed) { return MapLink(lat: c.0, lon: c.1) }
        // Also typed or cut-off links: "https:www.google.com/maps/…" (slashes lost), "www.google.com/maps/…"
        // or "httwww.google.com/maps/…" (keys lost while typing): the host is found wherever it starts.
        guard let r = trimmed.range(of: #"(?:https?:/{0,2}|geo:|(?:www\.google\.|maps\.google\.|google\.[a-z.]+/maps|maps\.apple\.com|maps\.app\.goo\.gl|goo\.gl/maps))[^\s<>"]+"#,
                                    options: [.regularExpression, .caseInsensitive]),
              let url = URL(string: normalizedLink(String(trimmed[r]))), var link = parse(url: url) else { return nil }
        // Text before the link names the place, unless it is the rest of a garbled link ("htt").
        let standsAlone = r.lowerBound == trimmed.startIndex || trimmed[trimmed.index(before: r.lowerBound)].isWhitespace
        if link.name == nil, standsAlone {
            let before = trimmed[..<r.lowerBound].split(whereSeparator: \.isNewline)
                .map { $0.trimmingCharacters(in: .whitespaces) }.first { !$0.isEmpty }
            if let before, before.count <= 120 { link.name = before }
        }
        return link
    }

    /// "https:host/…", "https:/host/…" and "host/…" become "https://host/…"; other links stay as they are.
    static func normalizedLink(_ s: String) -> String {
        if s.lowercased().hasPrefix("geo:") { return s }
        if let m = s.range(of: #"^https?:/{0,2}"#, options: [.regularExpression, .caseInsensitive]) {
            let scheme = s[m].lowercased().hasPrefix("https") ? "https://" : "http://"
            return scheme + s[m.upperBound...]
        }
        return "https://" + s
    }

    static func parse(url: URL) -> MapLink? {
        let s = url.absoluteString
        if url.scheme?.lowercased() == "geo" { return geo(s) }
        guard let host = url.host?.lowercased() else { return nil }
        let items = URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems ?? []
        func value(_ n: String) -> String? {
            items.first { $0.name.lowercased() == n }?.value.flatMap { $0.isEmpty ? nil : $0 }
        }
        if host == "maps.app.goo.gl" || (host == "goo.gl" && url.path.hasPrefix("/maps")) {
            return MapLink(shortLink: url)
        }
        if host.hasPrefix("consent.google."), let next = value("continue").flatMap(URL.init(string:)) {
            return parse(url: next)
        }
        if host.contains("google.") && (url.path.hasPrefix("/maps") || host.hasPrefix("maps.google.")) {
            return google(url, s, value)
        }
        if host == "maps.apple.com" || host == "maps.apple" || host.hasSuffix(".maps.apple.com") {
            let name = value("name") ?? value("q").flatMap { coordinates($0) == nil ? clean($0) : nil }
            for key in ["coordinate", "ll", "daddr", "q", "sll", "center"] {
                if let v = value(key), let c = coordinates(v) { return MapLink(name: name, lat: c.0, lon: c.1) }
            }
            if name == nil, let d = value("daddr") { return MapLink(name: clean(d)) }
            return name.map { MapLink(name: $0) }
        }
        if host.hasSuffix("openstreetmap.org") || host == "osm.org" {
            if let la = value("mlat").flatMap(Double.init), let lo = value("mlon").flatMap(Double.init), valid(la, lo) {
                return MapLink(lat: la, lon: lo)
            }
            if let f = url.fragment, let m = match(#"map=\d+/(-?\d+(?:\.\d+)?)/(-?\d+(?:\.\d+)?)"#, f), valid(m.0, m.1) {
                return MapLink(lat: m.0, lon: m.1)
            }
        }
        return nil
    }

    private static func google(_ url: URL, _ s: String, _ value: (String) -> String?) -> MapLink? {
        let parts = url.pathComponents
        var name: String?
        if let i = parts.firstIndex(of: "place"), i + 1 < parts.count, !parts[i + 1].hasPrefix("@") {
            name = clean(parts[i + 1])
            if let c = coordinates(parts[i + 1]) { return MapLink(lat: c.0, lon: c.1) }
        }
        // The pin of a place: …!3d45.4436!4d12.3264 (the last one is the place, not the map).
        if let c = matches(#"!3d(-?\d+(?:\.\d+)?)!4d(-?\d+(?:\.\d+)?)"#, s).last { return MapLink(name: name, lat: c.0, lon: c.1) }
        for key in ["destination", "daddr", "q", "query", "ll", "center"] {
            guard let v = value(key) else { continue }
            if let c = coordinates(v) { return MapLink(name: name, lat: c.0, lon: c.1) }
            if name == nil, ["destination", "daddr", "q", "query"].contains(key) { name = clean(v) }
        }
        for marker in ["search", "dir"] {
            if let i = parts.firstIndex(of: marker) {
                for p in parts[(i + 1)...] where !p.hasPrefix("@") {
                    if let c = coordinates(p) { return MapLink(name: name, lat: c.0, lon: c.1) }
                }
                if name == nil, let p = parts[(i + 1)...].last(where: { !$0.hasPrefix("@") && !$0.isEmpty && $0 != "/" }) {
                    name = clean(p)
                }
            }
        }
        // Only the visible map: its centre, when nothing else says where.
        if let c = match(#"@(-?\d+\.\d+),(-?\d+\.\d+)"#, s), valid(c.0, c.1) { return MapLink(name: name, lat: c.0, lon: c.1) }
        return name.map { MapLink(name: $0) }
    }

    /// `geo:45.4436,12.3264?q=45.4436,12.3264(Caffè Cannaregio)` or `geo:0,0?q=Caffè+Cannaregio`.
    private static func geo(_ s: String) -> MapLink? {
        let body = String(s.dropFirst(4))
        let point = body.split(whereSeparator: { $0 == "?" || $0 == ";" }).first.map(String.init) ?? ""
        var name: String?
        var c = coordinates(point)
        if let c0 = c, c0.0 == 0, c0.1 == 0 { c = nil }
        if let qr = body.range(of: "q=") {
            let q = String(body[qr.upperBound...]).components(separatedBy: "&")[0]
            let decoded = clean(q) ?? q
            if let open = decoded.firstIndex(of: "("), decoded.hasSuffix(")") {
                name = clean(String(decoded[decoded.index(after: open)..<decoded.index(before: decoded.endIndex)]))
                if let qc = coordinates(String(decoded[..<open])) { c = qc }
            } else if let qc = coordinates(decoded) {
                c = qc
            } else {
                name = decoded
            }
        }
        guard c != nil || name != nil else { return nil }
        return MapLink(name: name, lat: c?.0, lon: c?.1)
    }

    /// "45.4436, 12.3264", "45.4436,+12.3264", "loc:45.4436,12.3264" (the whole text, nothing else).
    static func coordinates(_ text: String) -> (Double, Double)? {
        var t = (text.removingPercentEncoding ?? text).replacingOccurrences(of: "+", with: " ")
            .trimmingCharacters(in: .whitespacesAndNewlines)
        if t.lowercased().hasPrefix("loc:") { t = String(t.dropFirst(4)) }
        guard let m = match(#"^\s*(-?\d{1,2}(?:\.\d+)?)\s*,\s*(-?\d{1,3}(?:\.\d+)?)\s*$"#, t), valid(m.0, m.1) else { return nil }
        return m
    }

    private static func valid(_ lat: Double, _ lon: Double) -> Bool {
        lat.isFinite && lon.isFinite && abs(lat) <= 90 && abs(lon) <= 180
    }

    private static func clean(_ s: String) -> String? {
        let t = (s.replacingOccurrences(of: "+", with: " ").removingPercentEncoding ?? s.replacingOccurrences(of: "+", with: " "))
            .trimmingCharacters(in: .whitespacesAndNewlines)
        return t.isEmpty ? nil : t
    }

    private static func match(_ pattern: String, _ s: String) -> (Double, Double)? { matches(pattern, s).first }

    private static func matches(_ pattern: String, _ s: String) -> [(Double, Double)] {
        guard let re = try? NSRegularExpression(pattern: pattern) else { return [] }
        let ns = s as NSString
        return re.matches(in: s, range: NSRange(location: 0, length: ns.length)).compactMap { m in
            guard m.numberOfRanges >= 3, let a = Double(ns.substring(with: m.range(at: 1))),
                  let b = Double(ns.substring(with: m.range(at: 2))) else { return nil }
            return (a, b)
        }
    }

    // MARK: Short links

    /// Follows a short link once and reads the full map address it leads to. Nothing else is sent
    /// or kept. When the address has no coordinates, the page's map centre is used.
    static func resolve(_ link: MapLink, session: URLSession = .shared) async -> MapLink? {
        guard let short = link.shortLink else { return link }
        var request = URLRequest(url: short, timeoutInterval: 10)
        request.httpShouldHandleCookies = false
        guard let (data, response) = try? await session.data(for: request), let final = response.url else { return nil }
        var parsed = parse(url: final)
        if parsed?.shortLink != nil { parsed = nil }
        if parsed?.hasCoordinate != true, let page = String(data: data.prefix(400_000), encoding: .utf8),
           let c = coordinatesInPage(page) {
            parsed = MapLink(name: parsed?.name, lat: c.0, lon: c.1)
        }
        guard var result = parsed else { return nil }
        if result.name == nil { result.name = link.name }
        return result
    }

    /// The place in a Google Maps page: its pin (!3d…!4d…), else the map centre of its preview image.
    static func coordinatesInPage(_ page: String) -> (Double, Double)? {
        if let c = matches(#"!3d(-?\d+(?:\.\d+)?)!4d(-?\d+(?:\.\d+)?)"#, page).last, valid(c.0, c.1) { return c }
        if let c = match(#"center=(-?\d+\.\d+)%2C(-?\d+\.\d+)"#, page), valid(c.0, c.1) { return c }
        if let c = match(#"@(-?\d+\.\d+),(-?\d+\.\d+),\d+(?:\.\d+)?z"#, page), valid(c.0, c.1) { return c }
        return nil
    }
}
