import Foundation

/// Where to buy a ticket for each operator of a route.
///
/// Order: the app or page the user linked for this operator, the feed's booking link
/// (iOS, then web), the operator's fare page, the operator's website.
struct TicketLink: Hashable, Identifiable, Sendable {
    enum Source: String, Sendable { case userLinked, booking, fares, website }
    let operatorName: String
    let url: URL
    let source: Source
    /// The link goes through a partner program (Publit may get a commission).
    var isPartner = false

    var id: String { operatorName + "|" + url.absoluteString }
    var title: String {
        switch source {
        case .userLinked: return L10n.f("Open your %@ app", operatorName)
        case .booking: return L10n.f("Buy ticket: %@", operatorName)
        case .fares: return L10n.f("Fares and tickets: %@", operatorName)
        case .website: return L10n.f("%@ website", operatorName)
        }
    }
}

enum TicketLinks {
    /// Key used to link an operator to the user's own ticket app.
    static func operatorKey(_ name: String) -> String {
        name.folding(options: [.caseInsensitive, .diacriticInsensitive], locale: nil)
            .components(separatedBy: .whitespacesAndNewlines).filter { !$0.isEmpty }.joined(separator: " ")
    }

    /// Ticket shops of large operators whose feeds publish no URL (keys are operatorKey word sets).
    static let knownShops: [String: String] = [
        "u-bahn munchen": "https://www.mvg.de/tickets-tarife.html",
        "mvg": "https://www.mvg.de/tickets-tarife.html",
        "s-bahn munchen": "https://www.mvv-muenchen.de/",
        "db": "https://int.bahn.de",
        "deutsche bahn": "https://int.bahn.de",
        "obb": "https://tickets.oebb.at",
        "wiener linien": "https://shop.wienerlinien.at",
        "trenitalia": "https://www.trenitalia.com",
        "sbb": "https://www.sbb.ch",
    ]

    /// A link saved for "DB" also applies to "DB Regio AG Bayern"; the most specific entry wins.
    static func userLink(for operatorName: String, in linked: [String: String]) -> String? {
        let words = Set(operatorKey(operatorName).split(separator: " "))
        return linked.filter { key, _ in
            let k = Set(key.split(separator: " "))
            return !k.isEmpty && k.isSubset(of: words)
        }.max { $0.key.count < $1.key.count }?.value
    }

    static func url(_ s: String?) -> URL? {
        guard let s = s?.trimmingCharacters(in: .whitespacesAndNewlines), !s.isEmpty else { return nil }
        let withScheme = s.contains("://") ? s : "https://" + s
        guard let u = URL(string: withScheme), let scheme = u.scheme, !scheme.isEmpty else { return nil }
        if scheme == "http" || scheme == "https" { return u.host?.isEmpty == false ? u : nil }
        return u // custom app scheme, e.g. a ticket app the user linked
    }

    /// Links that come from feeds (alerts, booking links) open only as web pages. A custom scheme
    /// from third-party data could otherwise start a call, a message or another app.
    static func webURL(_ s: String?) -> URL? {
        guard let u = url(s), let scheme = u.scheme?.lowercased(), scheme == "http" || scheme == "https" else { return nil }
        return u
    }

    static func link(for leg: Leg, userLinked: [String: String] = [:]) -> TicketLink? {
        guard leg.isTransit else { return nil }
        let name = leg.agencyName?.trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty ?? leg.lineName
        if let u = url(userLink(for: name, in: userLinked)) { return TicketLink(operatorName: name, url: u, source: .userLinked) }
        if let u = webURL(leg.ticketUrls?.ios) ?? webURL(leg.ticketUrls?.web) {
            return shop(name, u, .booking)
        }
        if let u = url(leg.agencyFareUrl) { return shop(name, u, .fares) }
        if let u = url(leg.agencyUrl) { return shop(name, u, .website) }
        if let u = url(userLink(for: name, in: knownShops)) { return shop(name, u, .fares) }
        return nil
    }

    /// A shop link, sent through the shop's partner program when Publit has one.
    /// The user's own linked apps are never changed.
    private static func shop(_ name: String, _ u: URL, _ source: TicketLink.Source) -> TicketLink {
        if let p = PartnerLinks.wrap(u) { return TicketLink(operatorName: name, url: p, source: source, isPartner: true) }
        return TicketLink(operatorName: name, url: u, source: source)
    }

    /// One link per operator, in the order the operators are used.
    static func links(for itinerary: Itinerary, userLinked: [String: String] = [:]) -> [TicketLink] {
        var seen = Set<String>()
        return itinerary.legs.compactMap { link(for: $0, userLinked: userLinked) }.filter {
            seen.insert(operatorKey($0.operatorName)).inserted
        }
    }

    /// Operators of a route that have no link at all (shown so the user can link an app).
    static func operatorsWithoutLink(_ itinerary: Itinerary) -> [String] {
        var seen = Set<String>()
        return itinerary.transitLegs.compactMap { leg -> String? in
            guard link(for: leg) == nil, let n = leg.agencyName?.nilIfEmpty else { return nil }
            return seen.insert(operatorKey(n)).inserted ? n : nil
        }
    }
}

/// A ticket shop's affiliate program. `template` is the program's tracking link with `{url}` where the
/// shop link goes (percent-encoded). An empty template means the program is off.
struct PartnerProgram: Codable, Sendable {
    let name: String
    /// Hosts of the shop; subdomains match too ("bahn.de" covers "int.bahn.de").
    let hosts: [String]
    let template: String
}

/// Partner links for ticket shops, so a ticket bought through Publit can earn a commission.
/// The app records nothing about the user: the network only sees the click, as with any web link.
/// Templates are filled in once the program has accepted Publit; until then every link stays as it was.
/// The ticket-link service (services/ticket-shop in the iOS repo, `GET /v1/partners`) can switch programs on
/// without an app update.
enum PartnerLinks {
    /// The programs in use: the ones last loaded from the ticket-link service, else the built-in list.
    static var programs: [PartnerProgram] {
        if remote == nil { remote = cached() }
        return remote ?? builtIn
    }

    private static var remote: [PartnerProgram]? = nil
    private static let cacheKey = "partnerPrograms"

    static let builtIn: [PartnerProgram] = [
        // Awin:       https://www.awin1.com/cread.php?awinmid=<advertiser id>&awinaffid=<publisher id>&ued={url}
        PartnerProgram(name: "Deutsche Bahn", hosts: ["bahn.de", "bahn.com"], template: ""),
        PartnerProgram(name: "FlixBus", hosts: ["flixbus.de", "flixbus.com", "flixbus.at", "flixbus.ch", "flixbus.fr", "flixbus.it",
                                                "flixbus.es", "flixbus.nl", "flixbus.pl", "flixbus.se", "flixbus.cz", "flixbus.co.uk",
                                                "flixtrain.de", "flixtrain.com"], template: ""),
        // Partnerize: https://prf.hn/click/camref:<camref>/destination:{url}
        PartnerProgram(name: "Trainline", hosts: ["thetrainline.com", "trainline.eu", "trainline.de", "trainline.fr", "trainline.it",
                                                  "trainline.es"], template: ""),
        // Impact:     https://<brand>.sjv.io/c/<publisher id>/<ad id>/<campaign id>?u={url}
        PartnerProgram(name: "Omio", hosts: ["omio.com", "omio.de", "omio.at", "omio.ch", "omio.fr", "omio.it", "omio.es", "omio.nl",
                                             "omio.pl", "omio.se", "omio.co.uk"], template: ""),
        PartnerProgram(name: "SNCF Connect", hosts: ["sncf-connect.com"], template: ""),
        PartnerProgram(name: "Eurostar", hosts: ["eurostar.com"], template: ""),
    ]

    struct Response: Codable { let programs: [PartnerProgram] }

    /// Programs from a service answer, keeping only https templates with one {url}. Nil when the answer is unusable.
    static func decode(_ data: Data) -> [PartnerProgram]? {
        guard let response = try? JSONDecoder().decode(Response.self, from: data) else { return nil }
        return response.programs.filter { p in
            !p.hosts.isEmpty && p.template.hasPrefix("https://") && p.template.components(separatedBy: "{url}").count == 2
        }
    }

    /// Loads the current programs from the ticket shop's address (one small request, no user data).
    /// Keeps the last list when offline.
    static func refresh() async {
        guard let base = TicketShop.baseURL, let url = URL(string: (base.absoluteString.hasSuffix("/") ? String(base.absoluteString.dropLast()) : base.absoluteString) + "/v1/partners") else { return }
        guard let (data, response) = try? await URLSession.shared.data(from: url),
              (response as? HTTPURLResponse)?.statusCode == 200, let list = decode(data),
              let text = String(data: data, encoding: .utf8) else { return }
        UserDefaults.standard.set(text, forKey: cacheKey)
        set(list)
    }

    static func set(_ list: [PartnerProgram]?) { remote = list }

    private static func cached() -> [PartnerProgram]? {
        guard let text = UserDefaults.standard.string(forKey: cacheKey) else { return nil }
        return decode(Data(text.utf8))
    }

    static func program(for url: URL, in programs: [PartnerProgram] = PartnerLinks.programs) -> PartnerProgram? {
        guard let scheme = url.scheme?.lowercased(), scheme == "http" || scheme == "https",
              let host = url.host?.lowercased() else { return nil }
        return programs.first { p in
            !p.template.isEmpty && p.hosts.contains { host == $0 || host.hasSuffix("." + $0) }
        }
    }

    /// The partner link for a shop link, or nil when the shop has no active program.
    static func wrap(_ url: URL, in programs: [PartnerProgram] = PartnerLinks.programs) -> URL? {
        guard let p = program(for: url, in: programs) else { return nil }
        var allowed = CharacterSet.alphanumerics
        allowed.insert(charactersIn: "-._~")
        guard let encoded = url.absoluteString.addingPercentEncoding(withAllowedCharacters: allowed) else { return nil }
        let wrapped = URL(string: p.template.replacingOccurrences(of: "{url}", with: encoded))
        return wrapped?.scheme?.lowercased() == "https" ? wrapped : nil
    }
}

extension String {
    var nilIfEmpty: String? { isEmpty ? nil : self }
}
