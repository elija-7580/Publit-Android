import Foundation

/// The city and its transport network for a route ("Venice · ACTV"), with where tickets are sold.
/// A rough box per network is enough: it only labels a route, it never routes.
struct TransitRegion: Sendable, Equatable {
    /// English city name, translated for display.
    let city: String
    /// Network or tariff association, as it calls itself.
    let network: String
    let box: GeoBox
    /// The network's own ticket app.
    let app: String?
    let notes: [Note]
    let url: String

    enum Note: Sendable, Equatable { case contactless, deutschlandticket, validate, zones }

    static func == (a: TransitRegion, b: TransitRegion) -> Bool { a.network == b.network && a.city == b.city }

    /// "Venice · ACTV".
    var title: String { "\(L10n.t(city)) · \(network)" }

    /// One line on buying a ticket here.
    var ticketText: String {
        var parts: [String] = [app.map { L10n.f("Tickets in the %@ app or at ticket machines.", $0) } ?? L10n.t("Tickets at ticket machines and sales points.")]
        for n in notes {
            switch n {
            case .contactless: parts.append(L10n.t("You can also pay by contactless card."))
            case .deutschlandticket: parts.append(L10n.t("The Deutschlandticket is valid."))
            case .validate: parts.append(L10n.t("Validate paper tickets before boarding."))
            case .zones: parts.append(L10n.t("The price depends on the zones you cross."))
            }
        }
        return parts.joined(separator: " ")
    }

    var link: URL? { URL(string: url) }

    static let all: [TransitRegion] = [
        .init(city: "Venice", network: "ACTV", box: .init(minLat: 45.20, maxLat: 45.62, minLon: 12.10, maxLon: 12.62),
              app: "AVM Venezia", notes: [.validate], url: "https://actv.avmspa.it/en/content/tickets-and-fares"),
        .init(city: "Vienna", network: "Wiener Linien", box: .init(minLat: 48.11, maxLat: 48.33, minLon: 16.18, maxLon: 16.58),
              app: "WienMobil", notes: [], url: "https://www.wienerlinien.at/tickets"),
        .init(city: "Munich", network: "MVV", box: .init(minLat: 47.90, maxLat: 48.40, minLon: 11.20, maxLon: 11.90),
              app: "MVGO", notes: [.deutschlandticket, .zones], url: "https://www.mvv-muenchen.de/tickets/"),
        .init(city: "Berlin", network: "VBB", box: .init(minLat: 52.33, maxLat: 52.68, minLon: 13.08, maxLon: 13.77),
              app: "BVG Tickets", notes: [.deutschlandticket, .zones], url: "https://www.bvg.de/en/tickets"),
        .init(city: "Hamburg", network: "HVV", box: .init(minLat: 53.39, maxLat: 53.74, minLon: 9.73, maxLon: 10.33),
              app: "HVV switch", notes: [.deutschlandticket], url: "https://www.hvv.de/en/tickets"),
        .init(city: "Zurich", network: "ZVV", box: .init(minLat: 47.32, maxLat: 47.46, minLon: 8.44, maxLon: 8.63),
              app: "SBB Mobile", notes: [.zones], url: "https://www.zvv.ch/zvv/en/tickets.html"),
        .init(city: "Stockholm", network: "SL", box: .init(minLat: 59.20, maxLat: 59.46, minLon: 17.75, maxLon: 18.25),
              app: "SL", notes: [.contactless], url: "https://sl.se/en/in-english/fares--tickets"),
        .init(city: "London", network: "TfL", box: .init(minLat: 51.28, maxLat: 51.70, minLon: -0.52, maxLon: 0.34),
              app: nil, notes: [.contactless], url: "https://tfl.gov.uk/fares"),
        .init(city: "Paris", network: "Île-de-France Mobilités", box: .init(minLat: 48.70, maxLat: 49.02, minLon: 2.15, maxLon: 2.60),
              app: "Bonjour RATP", notes: [], url: "https://www.iledefrance-mobilites.fr/en/tickets-fares"),
        .init(city: "Prague", network: "PID", box: .init(minLat: 49.94, maxLat: 50.18, minLon: 14.22, maxLon: 14.71),
              app: "PID Lítačka", notes: [.validate], url: "https://pid.cz/en/tickets/"),
        .init(city: "Milan", network: "ATM", box: .init(minLat: 45.38, maxLat: 45.56, minLon: 9.04, maxLon: 9.28),
              app: "ATM Milano", notes: [.contactless], url: "https://www.atm.it/en/ViaggiaConNoi/Biglietti/Pages/Tipologie.aspx"),
        .init(city: "Rome", network: "ATAC", box: .init(minLat: 41.79, maxLat: 41.99, minLon: 12.37, maxLon: 12.62),
              app: "Roma Mobile", notes: [.contactless, .validate], url: "https://www.atac.roma.it/en/tickets"),
        .init(city: "Amsterdam", network: "GVB", box: .init(minLat: 52.30, maxLat: 52.43, minLon: 4.75, maxLon: 5.02),
              app: "GVB", notes: [.contactless], url: "https://www.gvb.nl/en/travel-information/tickets"),
        .init(city: "Copenhagen", network: "DOT", box: .init(minLat: 55.60, maxLat: 55.74, minLon: 12.45, maxLon: 12.66),
              app: "DOT Tickets", notes: [.zones], url: "https://dinoffentligetransport.dk/en/"),
    ]

    static func at(_ p: LatLon) -> TransitRegion? { all.first { $0.box.contains(p) } }

    /// Networks a route rides in, in order, each once (walks alone do not count).
    static func along(_ it: Itinerary) -> [TransitRegion] {
        var out: [TransitRegion] = []
        for leg in it.legs where leg.isTransit {
            for p in [leg.from.coordinate, leg.to.coordinate] {
                if let r = at(p), !out.contains(r) { out.append(r) }
            }
        }
        return out
    }
}
