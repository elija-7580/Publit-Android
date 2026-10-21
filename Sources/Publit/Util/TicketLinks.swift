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
            return TicketLink(operatorName: name, url: u, source: .booking)
        }
        if let u = url(leg.agencyFareUrl) { return TicketLink(operatorName: name, url: u, source: .fares) }
        if let u = url(leg.agencyUrl) { return TicketLink(operatorName: name, url: u, source: .website) }
        if let u = url(userLink(for: name, in: knownShops)) { return TicketLink(operatorName: name, url: u, source: .fares) }
        return nil
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

extension String {
    var nilIfEmpty: String? { isEmpty ? nil : self }
}
