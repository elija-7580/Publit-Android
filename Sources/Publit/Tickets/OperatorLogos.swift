import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

/// A logo Publit can offer for a ticket's operator.
struct LogoCandidate: Identifiable, Hashable, Sendable {
    enum Source: String, Sendable { case wikimedia, website }
    let url: URL
    /// The organisation, e.g. "Österreichische Bundesbahnen".
    let title: String
    /// Shown under the logo: the Commons file name or the website.
    let credit: String
    let source: Source
    var id: String { url.absoluteString }
}

/// Finds operator logos: the logo image (P154) of the operator's Wikidata item, drawn by
/// Wikimedia Commons as PNG, then the website's app icon. Only the operator's name or
/// Wikidata ID is sent, never anything from the ticket itself.
enum OperatorLogos {
    static let api = URL(string: "https://www.wikidata.org/w/api.php")!

    static func searchURL(_ name: String, language: String) -> URL {
        var c = URLComponents(url: api, resolvingAgainstBaseURL: false)!
        c.queryItems = [.init(name: "action", value: "wbsearchentities"), .init(name: "search", value: name),
                        .init(name: "language", value: language), .init(name: "uselang", value: language),
                        .init(name: "type", value: "item"), .init(name: "limit", value: "5"), .init(name: "format", value: "json")]
        return c.url!
    }

    static func entitiesURL(_ ids: [String], language: String) -> URL {
        var c = URLComponents(url: api, resolvingAgainstBaseURL: false)!
        c.queryItems = [.init(name: "action", value: "wbgetentities"), .init(name: "ids", value: ids.joined(separator: "|")),
                        .init(name: "props", value: "claims|labels"), .init(name: "languages", value: language == "en" ? "en" : "\(language)|en"),
                        .init(name: "format", value: "json")]
        return c.url!
    }

    /// PNG rendering of a Commons file (SVG logos included), about `width` pixels wide.
    static func commonsURL(file: String, width: Int = 320) -> URL {
        let name = file.replacingOccurrences(of: " ", with: "_")
        var c = URLComponents(string: "https://commons.wikimedia.org/wiki/Special:FilePath/")!
        c.path += name
        c.queryItems = [.init(name: "width", value: String(width))]
        return c.url!
    }

    /// The website's icon in a usable size (many sites have no apple-touch-icon at a fixed place).
    static func websiteIconURL(_ website: String) -> URL? {
        let host = (URLComponents(string: website)?.host ?? website).lowercased()
        let domain = host.hasPrefix("www.") ? String(host.dropFirst(4)) : host
        guard domain.contains("."), !domain.contains(" ") else { return nil }
        var c = URLComponents(string: "https://www.google.com/s2/favicons")!
        c.queryItems = [.init(name: "domain", value: domain), .init(name: "sz", value: "256")]
        return c.url
    }

    static func ids(fromSearch data: Data) -> [String] {
        guard let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let hits = root["search"] as? [[String: Any]] else { return [] }
        return hits.compactMap { $0["id"] as? String }
    }

    struct Entity: Equatable, Sendable {
        let id: String
        let label: String?
        /// Current logos first.
        let logos: [String]
        let website: String?
    }

    static func entities(_ data: Data, language: String) -> [String: Entity] {
        guard let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let all = root["entities"] as? [String: [String: Any]] else { return [:] }
        var out: [String: Entity] = [:]
        for (id, e) in all {
            let labels = e["labels"] as? [String: [String: Any]]
            let label = (labels?[language]?["value"] ?? labels?["en"]?["value"]) as? String
            let claims = e["claims"] as? [String: [[String: Any]]] ?? [:]
            out[id] = Entity(id: id, label: label, logos: logos(claims["P154"] ?? []),
                             website: values(claims["P856"] ?? []).first)
        }
        return out
    }

    private static func values(_ claims: [[String: Any]]) -> [String] {
        claims.compactMap { (($0["mainsnak"] as? [String: Any])?["datavalue"] as? [String: Any])?["value"] as? String }
    }

    /// Ordered by how likely the logo is the one in use: preferred rank, no end date, not a
    /// white or negative variant (made for dark backgrounds). Deprecated ones are left out.
    static func logos(_ claims: [[String: Any]]) -> [String] {
        let scored: [(file: String, score: Int, index: Int)] = claims.enumerated().compactMap { i, claim in
            guard let file = values([claim]).first else { return nil }
            let rank = claim["rank"] as? String ?? "normal"
            if rank == "deprecated" { return nil }
            let qualifiers = claim["qualifiers"] as? [String: Any] ?? [:]
            var score = 0
            if rank == "preferred" { score += 4 }
            if qualifiers["P582"] != nil { score -= 6 }
            let lower = file.lowercased()
            if ["white", "weiß", "weiss", "negativ", "negative", "inverted", "blanc", "bianco"].contains(where: lower.contains) { score -= 3 }
            return (file, score, i)
        }
        return scored.sorted { ($0.score, -$0.index) > ($1.score, -$1.index) }.map(\.file)
    }

    /// Candidates for the entities in `order`: each one's logos, then its website icon.
    static func candidates(_ entities: [String: Entity], order: [String], fallbackDomain: String? = nil) -> [LogoCandidate] {
        var out: [LogoCandidate] = []
        var seen = Set<String>()
        func add(_ c: LogoCandidate) { if seen.insert(c.id).inserted { out.append(c) } }
        for id in order {
            guard let e = entities[id] else { continue }
            let title = e.label ?? id
            for file in e.logos.prefix(3) {
                add(LogoCandidate(url: commonsURL(file: file), title: title, credit: "Wikimedia Commons: \(file)", source: .wikimedia))
            }
        }
        let websites = order.compactMap { entities[$0]?.website } + (fallbackDomain.map { [$0] } ?? [])
        for site in websites.prefix(2) {
            guard let url = websiteIconURL(site) else { continue }
            let host = URLComponents(string: site)?.host ?? site
            add(LogoCandidate(url: url, title: host, credit: host, source: .website))
        }
        return out
    }

    /// Wikidata items to look at: the known operator's own item, otherwise the best search hits.
    static func lookup(name: String, language: String,
                       fetch: @Sendable (URL) async throws -> Data) async -> [LogoCandidate] {
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard trimmed.count >= 2 else { return [] }
        let known = KnownOperators.find(trimmed)
        var ids = known?.wikidata.map { [$0] } ?? []
        if ids.isEmpty, let data = try? await fetch(searchURL(trimmed, language: language)) {
            ids = Array(Self.ids(fromSearch: data).prefix(4))
        }
        guard !ids.isEmpty, let data = try? await fetch(entitiesURL(ids, language: language)) else {
            return known?.domain.flatMap(websiteIconURL).map {
                [LogoCandidate(url: $0, title: known?.name ?? trimmed, credit: known?.domain ?? "", source: .website)]
            } ?? []
        }
        let found = entities(data, language: language)
        // Search hits without a logo or website are people, places or vehicles of the same name.
        let useful = ids.filter { found[$0].map { !$0.logos.isEmpty || $0.website != nil } ?? false }
        return candidates(found, order: useful, fallbackDomain: known?.domain)
    }

    /// Download with Wikimedia's required identifying User-Agent; images only, at most 2 MB.
    static func download(_ url: URL, session: URLSession = .shared) async throws -> Data {
        var req = URLRequest(url: url, timeoutInterval: 15)
        req.setValue(AppInfo.userAgent, forHTTPHeaderField: "User-Agent")
        let (data, response) = try await session.data(for: req)
        let http = response as? HTTPURLResponse
        guard let http, (200..<300).contains(http.statusCode), data.count <= 2_000_000 else {
            throw URLError(.badServerResponse)
        }
        return data
    }
}
