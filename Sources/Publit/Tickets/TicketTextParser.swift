import Foundation

/// Operators the recogniser knows by name or by UIC company code. Colours are approximations of
/// the operators' own colours and only a starting point; the user picks the final look.
enum KnownOperators {
    struct Operator: Sendable {
        let name: String
        let patterns: [String]
        let color: String
        let symbol: TicketPass.Symbol
        let uicCode: String?
        /// Wikidata item, for the logo (Wikimedia Commons) and the website.
        var wikidata: String? = nil
        var domain: String? = nil
    }

    static let all: [Operator] = [
        Operator(name: "ÖBB", patterns: ["ÖBB", "OEBB", "Österreichische Bundesbahnen", "oebb.at", "Nightjet"], color: "D70015", symbol: .train, uicCode: "1181", wikidata: "Q83822", domain: "oebb.at"),
        Operator(name: "Deutsche Bahn", patterns: ["Deutsche Bahn", "DB Fernverkehr", "DB Regio", "DB Navigator", "bahn.de", "DB"], color: "EC0016", symbol: .train, uicCode: "1080", wikidata: "Q9322", domain: "bahn.de"),
        Operator(name: "SBB", patterns: ["SBB CFF FFS", "SBB", "sbb.ch"], color: "D70015", symbol: .train, uicCode: "1185", wikidata: "Q83835", domain: "sbb.ch"),
        Operator(name: "WESTbahn", patterns: ["WESTbahn"], color: "00578A", symbol: .train, uicCode: nil, wikidata: "Q266280", domain: "westbahn.at"),
        Operator(name: "Wiener Linien", patterns: ["Wiener Linien", "wienerlinien"], color: "D70015", symbol: .metro, uicCode: nil, wikidata: "Q668849", domain: "wienerlinien.at"),
        Operator(name: "MVV", patterns: ["MVV", "Münchner Verkehrs"], color: "0065AE", symbol: .metro, uicCode: nil, wikidata: "Q259000", domain: "mvv-muenchen.de"),
        Operator(name: "BVG", patterns: ["BVG", "Berliner Verkehrsbetriebe"], color: "F0D722", symbol: .metro, uicCode: nil, wikidata: "Q99633", domain: "bvg.de"),
        Operator(name: "Trenitalia", patterns: ["Trenitalia"], color: "C8102E", symbol: .train, uicCode: "1183", wikidata: "Q286650", domain: "trenitalia.com"),
        Operator(name: "SNCF", patterns: ["SNCF", "TGV INOUI", "OUIGO"], color: "8C1D5A", symbol: .train, uicCode: "1187", wikidata: "Q13646", domain: "sncf-connect.com"),
        Operator(name: "NS", patterns: ["NS International", "Nederlandse Spoorwegen"], color: "FFC917", symbol: .train, uicCode: "1184", wikidata: "Q23076", domain: "ns.nl"),
        Operator(name: "SNCB", patterns: ["SNCB", "NMBS"], color: "0069B4", symbol: .train, uicCode: "1188", wikidata: "Q524255", domain: "belgiantrain.be"),
        Operator(name: "CFL", patterns: ["CFL", "Chemins de fer luxembourgeois"], color: "B5121B", symbol: .train, uicCode: "1182", wikidata: "Q525359", domain: "cfl.lu"),
        Operator(name: "ČD", patterns: ["České dráhy", "Ceske drahy", "ČD"], color: "0B4EA2", symbol: .train, uicCode: "1154", wikidata: "Q304944", domain: "cd.cz"),
        Operator(name: "Renfe", patterns: ["Renfe"], color: "6C2C91", symbol: .train, uicCode: "1071", wikidata: "Q2476154", domain: "renfe.com"),
        Operator(name: "RegioJet", patterns: ["RegioJet"], color: "FECB00", symbol: .train, uicCode: nil, wikidata: "Q221186", domain: "regiojet.com"),
        Operator(name: "FlixBus", patterns: ["FlixBus", "FlixTrain"], color: "73D700", symbol: .bus, uicCode: nil, wikidata: "Q1881378", domain: "flixbus.com"),
        // Without a Wikidata item the logo suggestion falls back to the website icon.
        Operator(name: "Italo", patterns: ["Italo Treno", "italotreno", "Italo"], color: "9E1B32", symbol: .train, uicCode: nil, domain: "italotreno.com"),
        Operator(name: "Trenord", patterns: ["Trenord"], color: "009A44", symbol: .train, uicCode: nil, domain: "trenord.it"),
        Operator(name: "Eurostar", patterns: ["Eurostar"], color: "1E2A5A", symbol: .train, uicCode: nil, domain: "eurostar.com"),
        Operator(name: "iryo", patterns: ["iryo"], color: "C8102E", symbol: .train, uicCode: nil, domain: "iryo.eu"),
        Operator(name: "PKP Intercity", patterns: ["PKP Intercity", "PKP IC", "intercity.pl"], color: "002F6C", symbol: .train, uicCode: nil, domain: "intercity.pl"),
        Operator(name: "MÁV", patterns: ["MÁV-START", "MAV-START", "MÁV", "mavcsoport"], color: "003F87", symbol: .train, uicCode: "1155", domain: "mavcsoport.hu"),
        Operator(name: "ZSSK", patterns: ["ZSSK", "Železničná spoločnosť Slovensko"], color: "D31E25", symbol: .train, uicCode: "1156", domain: "zssk.sk"),
        Operator(name: "SŽ", patterns: ["Slovenske železnice", "SŽ"], color: "005BAA", symbol: .train, uicCode: "1179", domain: "sz.si"),
        Operator(name: "DSB", patterns: ["DSB", "dsb.dk"], color: "C8102E", symbol: .train, uicCode: "1186", domain: "dsb.dk"),
        Operator(name: "SJ", patterns: ["SJ AB", "Statens Järnvägar", "sj.se"], color: "2B2B2B", symbol: .train, uicCode: "1174", domain: "sj.se"),
        Operator(name: "Leo Express", patterns: ["Leo Express"], color: "202020", symbol: .train, uicCode: nil, domain: "leoexpress.com"),
        Operator(name: "BLS", patterns: ["BLS AG", "bls.ch"], color: "6AB023", symbol: .train, uicCode: nil, domain: "bls.ch"),
        Operator(name: "ZVV", patterns: ["ZVV", "Zürcher Verkehrsverbund"], color: "0479CC", symbol: .tram, uicCode: nil, domain: "zvv.ch"),
        Operator(name: "PostAuto", patterns: ["PostAuto", "CarPostal", "AutoPostale"], color: "FFCC00", symbol: .bus, uicCode: nil, domain: "postauto.ch"),
        Operator(name: "RATP", patterns: ["RATP"], color: "00A0A0", symbol: .metro, uicCode: nil, domain: "ratp.fr"),
        Operator(name: "VOR", patterns: ["Verkehrsverbund Ost-Region", "vor.at"], color: "C8102E", symbol: .train, uicCode: nil, domain: "vor.at"),
        Operator(name: "OÖVV", patterns: ["OÖVV", "OÖ Verkehrsverbund", "ooevv.at"], color: "E30613", symbol: .bus, uicCode: nil, domain: "ooevv.at"),
        Operator(name: "VVT", patterns: ["VVT", "Verkehrsverbund Tirol"], color: "009FE3", symbol: .bus, uicCode: nil, domain: "vvt.at"),
        Operator(name: "Salzburg Verkehr", patterns: ["Salzburg Verkehr", "Salzburger Verkehrsverbund"], color: "D40F14", symbol: .bus, uicCode: nil, domain: "salzburg-verkehr.at"),
        Operator(name: "Verbund Linie", patterns: ["Verbund Linie", "Steirischer Verkehrsverbund", "verbundlinie.at"], color: "E2001A", symbol: .bus, uicCode: nil, domain: "verbundlinie.at"),
        Operator(name: "Graz Linien", patterns: ["Graz Linien", "Holding Graz"], color: "00A04B", symbol: .tram, uicCode: nil, domain: "holding-graz.at"),
        Operator(name: "LINZ AG LINIEN", patterns: ["LINZ AG", "Linz Linien"], color: "004F9F", symbol: .tram, uicCode: nil, domain: "linzag.at"),
        Operator(name: "Wiener Lokalbahnen", patterns: ["Wiener Lokalbahnen", "Badner Bahn"], color: "004C97", symbol: .tram, uicCode: nil, domain: "wlb.at"),
        Operator(name: "HVV", patterns: ["HVV", "Hamburger Verkehrsverbund"], color: "E2001A", symbol: .metro, uicCode: nil, domain: "hvv.de"),
        Operator(name: "VBB", patterns: ["VBB", "Verkehrsverbund Berlin-Brandenburg"], color: "E3000F", symbol: .metro, uicCode: nil, domain: "vbb.de"),
        Operator(name: "MVG", patterns: ["MVG", "Münchner Verkehrsgesellschaft"], color: "0065AE", symbol: .metro, uicCode: nil, domain: "mvg.de"),
        Operator(name: "RMV", patterns: ["RMV", "Rhein-Main-Verkehrsverbund"], color: "0068B4", symbol: .train, uicCode: nil, domain: "rmv.de"),
        Operator(name: "VRR", patterns: ["VRR", "Verkehrsverbund Rhein-Ruhr"], color: "00A1DE", symbol: .tram, uicCode: nil, domain: "vrr.de"),
        Operator(name: "VVS", patterns: ["VVS", "Verkehrs- und Tarifverbund Stuttgart"], color: "F9B000", symbol: .train, uicCode: nil, domain: "vvs.de"),
        Operator(name: "KVB", patterns: ["KVB", "Kölner Verkehrs-Betriebe"], color: "E30613", symbol: .tram, uicCode: nil, domain: "kvb.koeln"),
        Operator(name: "VGN", patterns: ["VGN", "Verkehrsverbund Großraum Nürnberg"], color: "E3000F", symbol: .train, uicCode: nil, domain: "vgn.de"),
        Operator(name: "DPP", patterns: ["DPP", "Dopravní podnik hl. m. Prahy", "Lítačka"], color: "E30613", symbol: .metro, uicCode: nil, domain: "dpp.cz"),
    ]

    static func find(_ name: String) -> Operator? {
        let folded = TicketTextParser.fold(name)
        return all.first { $0.name == name || TicketTextParser.fold($0.name) == folded }
            ?? all.first { op in op.patterns.contains { TicketTextParser.matches($0, in: name) } }
    }

    static func uic(_ code: String) -> Operator? { all.first { $0.uicCode == code } }
}

/// What the recogniser read from a ticket. Every field is a suggestion the user checks.
struct TicketDraft: Equatable, Sendable {
    var title: String?
    var operatorName: String?
    var category: TicketPass.Category?
    var symbol: TicketPass.Symbol?
    var origin: String?
    var destination: String?
    var via: String?
    var passenger: String?
    var travelClass: String?
    var price: String?
    var reference: String?
    var validFrom: Date?
    var validUntil: Date?
}

extension TicketPass {
    init(draft: TicketDraft, barcode: Barcode?, now: Date = .now) {
        let category = draft.category ?? .single
        self.init(title: draft.title ?? "", operatorName: draft.operatorName, category: category,
                  symbol: draft.symbol ?? .ticket, origin: draft.origin, destination: draft.destination,
                  via: draft.via, passenger: draft.passenger, travelClass: draft.travelClass, price: draft.price,
                  reference: draft.reference, validFrom: draft.validFrom, validUntil: draft.validUntil,
                  barcode: barcode, style: PassStyle.suggested(forOperator: draft.operatorName) ?? .standard,
                  createdAt: now)
    }
}

/// Reads ticket details from recognised text lines, entirely on the device. Rules, not guesses:
/// a field stays empty when no label or pattern supports it. Covers common German, English,
/// French and Italian ticket wording, and the usual labels in Spanish, Portuguese, Dutch, Polish,
/// Czech, Swedish, Hungarian and Turkish.
enum TicketTextParser {
    static func parse(lines raw: [String], barcodes: [TicketPass.Barcode] = [], calendar: Calendar = .current, strictValidity: Bool = false) -> TicketDraft {
        let lines = raw.map(clean).filter { !$0.isEmpty }
        let text = lines.joined(separator: "\n")
        var d = TicketDraft()

        // The issuer in a rail barcode is certain; otherwise the operator named nearest the top
        // (partners and sales agents are usually named further down).
        let op = barcodes.lazy.compactMap { $0.uicIssuerCode }.compactMap(KnownOperators.uic).first
            ?? lines.lazy.compactMap { line in KnownOperators.all.first { op in op.patterns.contains { matches($0, in: line) } } }.first
        d.operatorName = op?.name
        d.symbol = op?.symbol

        let product = products.first { p in p.patterns.contains { matches($0, in: text) } }
        d.title = product?.title
        d.category = product?.category ?? category(in: text)

        (d.origin, d.destination, d.via) = route(lines)
        let name = labelled(lines, passengerLabels, accept: { v in
            v.first?.isLetter == true && v.count <= 60 && v.filter(\.isNumber).count < 3
        })
        let body = lines.filter { l in !["www", "http", "jetzt", "bestell", "order now", "sparen", "save "].contains { fold(l).contains($0) } }
        let discount = product.map { p in discountProducts.contains(p.title) } == true ? nil : self.discount(body.joined(separator: "\n"))
        let people = travellers(body)
        d.passenger = [name, people, discount].compactMap { $0 }.joined(separator: " · ").nilIfEmpty
        d.travelClass = travelClass(text)
        d.price = price(lines)
        d.reference = labelled(lines, referenceLabels, accept: { v in
            (v.count >= 5 && v.count <= 40 && v.contains(where: \.isNumber))
                || (v.count >= 6 && v.count <= 40 && v.allSatisfy { $0.isUppercase || $0.isNumber || $0 == "-" })
        })

        let validity = validity(lines, calendar: calendar, strict: strictValidity, category: d.category)
        d.validFrom = validity.from
        d.validUntil = validity.until

        if d.category == nil || (product == nil && d.category == .single), let from = d.validFrom, let until = d.validUntil,
           until.timeIntervalSince(from) > 36 * 3600 {
            d.category = until.timeIntervalSince(from) > 40 * 24 * 3600 ? .subscription : .period
        }
        return d
    }

    // MARK: - Products and categories

    struct Product: Sendable {
        let title: String
        let patterns: [String]
        let category: TicketPass.Category
    }

    static let products: [Product] = [
        Product(title: "Deutschlandticket", patterns: ["Deutschlandticket", "Deutschland-Ticket", "D-Ticket"], category: .subscription),
        Product(title: "KlimaTicket Ö", patterns: ["KlimaTicket Ö", "Klimaticket Österreich", "KlimaTicket Österreich"], category: .subscription),
        Product(title: "KlimaTicket", patterns: ["KlimaTicket"], category: .subscription),
        Product(title: "Interrail Pass", patterns: ["Interrail"], category: .period),
        Product(title: "Eurail Pass", patterns: ["Eurail"], category: .period),
        Product(title: "Jahreskarte", patterns: ["Jahreskarte", "Jahresabo"], category: .subscription),
        Product(title: "Monatskarte", patterns: ["Monatskarte"], category: .period),
        Product(title: "Wochenkarte", patterns: ["Wochenkarte"], category: .period),
        Product(title: "Tageskarte", patterns: ["Tageskarte", "Tagesticket", "24-Stunden-Karte", "24 Stunden"], category: .dayPass),
        Product(title: "Sparschiene", patterns: ["Sparschiene"], category: .single),
        Product(title: "Super Sparpreis", patterns: ["Super Sparpreis"], category: .single),
        Product(title: "Sparpreis", patterns: ["Sparpreis"], category: .single),
        Product(title: "Flexpreis", patterns: ["Flexpreis"], category: .single),
        Product(title: "Standard-Ticket", patterns: ["Standard-Ticket", "Standardticket"], category: .single),
        Product(title: "Einfach-Raus-Ticket", patterns: ["Einfach-Raus-Ticket", "Einfach Raus Ticket"], category: .dayPass),
        Product(title: "Quer-durchs-Land-Ticket", patterns: ["Quer-durchs-Land", "Quer durchs Land"], category: .dayPass),
        Product(title: "Bayern-Ticket", patterns: ["Bayern-Ticket", "Bayernticket"], category: .dayPass),
        Product(title: "Niedersachsenticket", patterns: ["Niedersachsenticket", "Niedersachsen-Ticket"], category: .dayPass),
        Product(title: "Semesterticket", patterns: ["Semesterticket"], category: .period),
        Product(title: "Jobticket", patterns: ["Jobticket", "Job-Ticket"], category: .subscription),
        Product(title: "Kurzstrecke", patterns: ["Kurzstrecke"], category: .single),
        Product(title: "Streifenkarte", patterns: ["Streifenkarte", "Mehrfahrtenkarte", "4-Fahrten-Karte", "Mehrfahrtenticket"], category: .other),
        Product(title: "Supersparbillett", patterns: ["Supersparbillett"], category: .single),
        Product(title: "Sparbillett", patterns: ["Sparbillett"], category: .single),
        Product(title: "GA", patterns: ["Generalabonnement", "Abonnement général"], category: .subscription),
        Product(title: "Navigo", patterns: ["Navigo"], category: .period),
        Product(title: "Travelcard", patterns: ["Travelcard"], category: .period),
        Product(title: "Frecciarossa", patterns: ["Frecciarossa"], category: .single),
        // Discount cards last: on a ticket they are usually only the reduction it was bought with.
        Product(title: "Halbtax", patterns: ["Halbtax"], category: .subscription),
        Product(title: "BahnCard", patterns: ["BahnCard"], category: .subscription),
        Product(title: "Vorteilscard", patterns: ["Vorteilscard"], category: .subscription),
    ]

    private static let discountProducts: Set<String> = ["Halbtax", "BahnCard", "Vorteilscard"]

    private static let categoryWords: [(TicketPass.Category, [String])] = [
        (.returnTrip, ["rückfahrt", "hin- und rückfahrt", "hin und rück", "hin-/rückfahrt", "return ticket", "aller-retour", "aller retour",
                       "andata e ritorno", "ida y vuelta", "ida e volta", "retour", "heen en terug", "tam i z powrotem", "zpáteční",
                       "tur och retur", "tur-retur", "menettérti", "gidiş-dönüş", "gidiş dönüş"]),
        (.dayPass, ["tageskarte", "tagesticket", "day ticket", "day pass", "24h", "24 h", "24-stunden", "48h", "48-stunden", "72h",
                    "72-stunden", "billet journée", "biglietto giornaliero", "dagkaart", "dagbiljett", "bilet dobowy", "napijegy",
                    "billete diario", "bilhete diário", "celodenní", "jednodenní"]),
        (.subscription, ["jahreskarte", "annual pass", "abonnement", "abo", "jaarabonnement", "årskort", "bilet roczny", "roční",
                         "éves bérlet", "abbonamento annuale", "abono anual", "passe anual"]),
        (.period, ["wochenkarte", "monatskarte", "weekly", "monthly", "week pass", "month pass", "abbonamento mensile", "mensile",
                   "settimanale", "mensuel", "hebdomadaire", "weekkaart", "maandkaart", "månadskort", "periodkort", "bilet miesięczny",
                   "miesięczny", "tygodniowy", "měsíční", "týdenní", "havi bérlet", "bérlet", "abono", "passe mensal", "aylık"]),
        (.single, ["einzelfahrt", "einzelticket", "einzelfahrschein", "einfache fahrt", "single ticket", "one-way", "one way", "aller simple",
                   "corsa semplice", "corsa singola", "biglietto ordinario", "hinfahrt", "enkele reis", "enkelbiljett", "bilet jednorazowy",
                   "jednorazowy", "jednosměrná", "menetjegy", "billete sencillo", "solo ida", "bilhete simples", "tek yön"]),
    ]

    private static func category(in text: String) -> TicketPass.Category? {
        let f = fold(text)
        let ticketWords = ["fahrkarte", "fahrschein", "ticket", "billet", "biglietto", "billete", "bilhete", "kaartje", "vervoerbewijs",
                           "bilet", "jízdenka", "biljett", "menetjegy", "bilet"]
        if ["reservierung", "reservation", "réservation", "prenotazione", "reserva", "reservering", "rezerwacja", "místenka",
            "platsbiljett", "helyjegy"].contains(where: { f.contains($0) }),
           !ticketWords.contains(where: { f.contains($0) }) {
            return .reservation
        }
        return categoryWords.first { _, words in words.contains { containsWord(fold($0), in: f) } }?.0
    }

    // MARK: - Route

    private static let fromLabels = ["von", "from", "start", "abfahrt", "departure", "ab", "départ", "depart", "partenza", "da",
                                     "abfahrtsbahnhof", "startbahnhof", "einstieg", "origin", "departure station", "gare de départ",
                                     "stazione di partenza", "origen", "origem", "vertrek", "van", "från", "skąd", "odkud", "honnan",
                                     "indulás", "kalkış", "stacja początkowa"]
    private static let toLabels = ["nach", "to", "ziel", "ankunft", "arrival", "an", "arrivée", "arrivee", "arrivo", "destination", "a",
                                   "zielbahnhof", "ausstieg", "arrival station", "gare d'arrivée", "stazione di arrivo", "destino",
                                   "aankomst", "naar", "till", "dokąd", "kam", "hova", "érkezés", "varış", "stacja docelowa"]
    private static let viaLabels = ["über", "via", "über:", "via:"]
    private static let arrows = ["→", "->", "➔", "➜", "⇒", "⇨", "►", "▶", "—>", "–>", "⟶", "»", " > "]

    private static func route(_ lines: [String]) -> (String?, String?, String?) {
        for line in lines {
            if let arrow = arrows.first(where: { line.contains($0) }) {
                // "A → B" or "A → B → C" (the middle as via).
                let parts = line.components(separatedBy: arrow).map(place)
                if parts.count >= 2, parts.allSatisfy({ $0 != nil }), let a = parts.first!, let b = parts.last! {
                    let via = parts.dropFirst().dropLast().compactMap { $0 }.joined(separator: ", ")
                    return (a, b, via.isEmpty ? viaOnly(lines) : via)
                }
            }
        }
        for line in lines {
            // "Von Wien Hbf nach Salzburg Hbf", "From Paris to Lyon", "Van Utrecht naar Amsterdam" …
            for (from, to) in [("von", "nach"), ("from", "to"), ("van", "naar"), ("från", "till"), ("de", "à"), ("da", "a"), ("de", "a")] {
                let pattern = "^\(from)\\s+(.+?)\\s+\(to)\\s+(.+)$"
                if let m = firstMatch(pattern, in: line, caseInsensitive: true), m.count == 3, let a = place(m[1]) {
                    let (b, via) = splitVia(m[2])
                    if let b { return (a, b, via ?? viaOnly(lines)) }
                }
            }
            // "Strecke: Wien Hbf – Salzburg Hbf"
            if let m = firstMatch(#"^(?:strecke|route|relation|verbindung|itinéraire|tratta|trasa|trayecto|traject|sträcka|útvonal|güzergah)\s*:?\s*(.+?)\s+[-–—]\s+(.+)$"#, in: line, caseInsensitive: true),
               m.count == 3, let a = place(m[1]) {
                let (b, via) = splitVia(m[2])
                if let b { return (a, b, via ?? viaOnly(lines)) }
            }
        }
        if let rows = timetableRows(lines) { return (rows.0, rows.1, viaOnly(lines)) }
        let a = labelled(lines, fromLabels, requireSeparatorForShort: true, accept: { place($0) != nil }).flatMap(place)
        let b = labelled(lines, toLabels, requireSeparatorForShort: true, accept: { place($0) != nil }).map(splitVia)
        guard let a, let b, let destination = b.0, a != destination else { return (nil, nil, nil) }
        return (a, destination, b.1 ?? viaOnly(lines))
    }

    /// "Salzburg Hbf über Linz" → ("Salzburg Hbf", "Linz").
    private static func splitVia(_ raw: String) -> (String?, String?) {
        for word in [" über ", " via ", " (via ", " por ", " przez ", " přes "] {
            if let r = raw.range(of: word, options: .caseInsensitive) {
                return (place(String(raw[..<r.lowerBound])), place(String(raw[r.upperBound...]).replacingOccurrences(of: ")", with: "")))
            }
        }
        return (place(raw), nil)
    }

    private static func viaOnly(_ lines: [String]) -> String? {
        labelled(lines, viaLabels, requireSeparatorForShort: true, accept: { place($0) != nil }).flatMap(place)
    }

    /// Timetable rows as on DB and ÖBB tickets: "Wien Hbf 14.10. ab 08:30 7", "Salzburg Hbf an 10:52".
    /// The first departure is the start, the last arrival the destination.
    private static func timetableRows(_ lines: [String]) -> (String, String)? {
        var first: String?, last: String?
        for line in lines {
            guard let m = firstMatch(#"^(.+?)\s+(?:\d{1,2}\.\d{1,2}\.(?:\d{2,4})?\s+)?(ab|an|dep\.?|arr\.?|dép\.?)\s+\d{1,2}[:.]\d{2}\b"#,
                                     in: line, caseInsensitive: true), m.count == 3,
                  let station = place(m[1]) else { continue }
            let f = fold(station)
            guard !validityWords.contains(where: { containsWord($0, in: f) }) else { continue }
            let kind = fold(m[2])
            if (kind == "ab" || kind.hasPrefix("dep") || kind.hasPrefix("dép")), first == nil { first = station }
            if kind == "an" || kind.hasPrefix("arr") { last = station }
        }
        guard let first, let last, first != last else { return nil }
        return (first, last)
    }

    /// A station name without times, dates, platforms or prices.
    private static func place(_ raw: String) -> String? {
        var s = raw
        for pattern in [#"\b\d{1,2}[:h]\d{2}\b(\s*Uhr)?"#, #"\b\d{1,2}\.\d{1,2}\.\d{2,4}\b"#, #"\b\d{4}-\d{2}-\d{2}\b"#,
                        #"\b\d{1,2}/\d{1,2}/\d{2,4}\b"#, #"\b\d{1,2}\.\d{1,2}\.(?=\s|$)"#, #"(?i)\s(?:ab|an|dep\.?|arr\.?)\s*$"#,
                        #"(?i)\b(gleis|gl\.|bahnsteig|steig|platform|track|voie|quai|binario|andén|peron|nástupiště|spår|vágány|perron)(?!\p{L}).*$"#,
                        #"(€|EUR|CHF)\s*\d.*$"#] {
            s = s.replacingOccurrences(of: pattern, with: "", options: .regularExpression)
        }
        s = s.trimmingCharacters(in: CharacterSet(charactersIn: " ,;:-–—|()").union(.whitespaces))
        guard s.count >= 2, s.count <= 60, s.first?.isLetter == true, !s.lowercased().contains("http") else { return nil }
        return s
    }

    // MARK: - Labelled values

    private static let passengerLabels = ["name", "reisende", "reisender", "fahrgast", "passenger", "passagier", "inhaber",
                                          "karteninhaber", "ticketinhaber", "holder", "voyageur", "passager", "passeggero", "reisende/r",
                                          "name des reisenden", "fahrgastname", "passenger name", "traveller", "traveler", "nom du voyageur",
                                          "nom", "nome", "nombre", "pasajero", "passageiro", "naam", "reiziger", "imię i nazwisko",
                                          "pasażer", "cestující", "jméno", "resenär", "namn", "utas", "név", "yolcu", "titular"]
    private static let referenceLabels = ["ticketnummer", "ticket-nr", "ticket nr", "ticket-id", "ticket id", "ticket number",
                                          "auftragsnummer", "auftrags-nr", "auftrag", "buchungsnummer", "buchungscode",
                                          "order number", "booking reference", "booking code", "reference", "referenz",
                                          "kartennummer", "fahrscheinnummer", "pnr", "numéro de dossier", "codice biglietto",
                                          "auftragsnr", "buchungsnr", "bestellnummer", "bestellnr", "vorgangsnummer", "ticketcode",
                                          "ticket code", "fahrkartennummer", "reservierungsnummer", "online-ticket-nr", "confirmation number",
                                          "confirmation code", "order no", "booking number", "booking no", "ticket no", "ticket-no",
                                          "référence du dossier", "référence", "numéro de billet", "n° de billet", "code de réservation",
                                          "codice prenotazione", "codice pnr", "numero biglietto", "n. biglietto", "localizador",
                                          "código de reserva", "número de billete", "localizador de reserva", "boekingsnummer",
                                          "reserveringsnummer", "numer biletu", "nr biletu", "kod biletu", "číslo dokladu", "kód dokladu",
                                          "číslo jízdenky", "kód jízdenky", "biljettnummer", "bokningsnummer", "jegyszám", "bilet no",
                                          "rezervasyon kodu"]

    /// The value after `Label:` / `Label value`, or on the next line when the label stands alone.
    /// Short labels ("ab", "an", "to") need a colon so ordinary words do not match.
    private static func labelled(_ lines: [String], _ labels: [String], requireSeparatorForShort: Bool = false,
                                 accept: (String) -> Bool) -> String? {
        for (i, line) in lines.enumerated() {
            let f = fold(line)
            for label in labels.sorted(by: { $0.count > $1.count }) {
                let l = fold(label)
                guard f.hasPrefix(l) else { continue }
                let restStart = line.index(line.startIndex, offsetBy: min(label.count, line.count))
                var rest = String(line[restStart...])
                // "Name" must not match "Namensänderung".
                if let next = rest.first, next.isLetter { continue }
                let hasColon = rest.trimmingCharacters(in: .whitespaces).hasPrefix(":")
                rest = rest.trimmingCharacters(in: CharacterSet(charactersIn: " :.").union(.whitespaces))
                if rest.isEmpty {
                    if i + 1 < lines.count, accept(lines[i + 1]), !isLabel(lines[i + 1]) { return lines[i + 1] }
                    continue
                }
                if requireSeparatorForShort, label.count <= 3, !hasColon { continue }
                if accept(rest) { return rest }
            }
        }
        return nil
    }

    private static func isLabel(_ line: String) -> Bool {
        let f = fold(line).trimmingCharacters(in: CharacterSet(charactersIn: " :"))
        return (fromLabels + toLabels + viaLabels + passengerLabels + referenceLabels).contains(f)
    }

    // MARK: - Class, price, travellers

    private static func travelClass(_ text: String) -> String? {
        let f = fold(text)
        let numbered = [#"\b([12])\.\s*(?:klasse|kl\.?)(?![a-z])"#, #"klasse\s*:?\s*([12])\b"#, #"\b([12])(?:st|nd)\s+class\b"#,
                        #"\bclass\s*:?\s*([12])\b"#, #"\b([12])\s*(?:e|ère|ème|re)\s+classe\b"#, #"\b([12])\s*[ªa°]?\s+classe\b"#,
                        #"\bkl\.?\s*([12])\b"#, #"\b([12])\s+klasse\b"#, #"\b([12])e\s+klas\b"#, #"\bklas\s*:?\s*([12])\b"#,
                        #"\b([12])\.\s*(?:klasa|třída|trida|tř\.|osztály|klass)"#, #"\b(?:klasa|třída|klass|osztály)\s*:?\s*([12])\b"#,
                        #"\b([12])\s*[ªa]?\s*clase\b"#, #"\bclasse\s*:?\s*([12])\b"#]
        for p in numbered {
            if let m = firstMatch(p, in: f, caseInsensitive: true), m.count == 2 { return m[1] }
        }
        let words: [(String, String)] = [
            ("first class", "1"), ("erste klasse", "1"), ("première classe", "1"), ("premiere classe", "1"), ("prima classe", "1"),
            ("primera clase", "1"), ("eerste klas", "1"), ("första klass", "1"),
            ("second class", "2"), ("standard class", "2"), ("zweite klasse", "2"), ("seconde classe", "2"), ("seconda classe", "2"),
            ("segunda clase", "2"), ("tweede klas", "2"), ("andra klass", "2"),
        ]
        return words.first { containsWord($0.0, in: f) }?.1
    }

    /// Ranked: totals first, then other price labels.
    private static let priceLabels = ["gesamtpreis", "gesamtbetrag", "gesamt", "summe", "total", "zu zahlen", "bezahlt", "paid",
                                      "endpreis", "betrag", "amount", "montant", "importo", "importe", "fahrpreis", "preis", "price",
                                      "prix", "prezzo", "precio", "preço", "prijs", "cena", "pris", "fiyat", "tutar", "ár"]
    private static let taxWords = ["mwst", "ust", "vat", "tva", "iva", "steuer", "tax", "btw", "moms", "áfa", "kdv", "dph"]
    private static let pricePattern = #"(?:(?:€|EUR|CHF|Fr\.|£|GBP|CZK|PLN|HUF|RON|SEK|DKK|NOK|USD|\$|TRY|₺)\s?(?:\d{1,3}(?:[.\u00A0\u202F’\']\d{3})+|\d{1,6})(?:[.,](?:\d{2}|[-–]))?)|(?:(?:\d{1,3}(?:[.\u00A0\u202F’\']\d{3})+|\d{1,6})[.,](?:\d{2}|[-–])\s?(?:€|EUR|CHF|Fr\.|Kč|CZK|zł|PLN|Ft|HUF|lei|RON|kr|SEK|DKK|NOK|£|TL|₺))"#

    private static func price(_ lines: [String]) -> String? {
        var ranked: [(Int, String)] = []
        var other: [String] = []
        for line in lines {
            let f = fold(line)
            // "MwSt 19 %: 3,18 €" is a share of the price, not the price.
            let isTax = taxWords.contains { containsWord($0, in: f) }
            if isTax, firstMatch(#"\d\s?%"#, in: f, caseInsensitive: false) != nil { continue }
            if let rank = priceLabels.firstIndex(where: { containsWord($0, in: f) || f.hasPrefix($0) }), !(isTax && rank > 7) {
                ranked.append((rank, line))
            } else if !isTax {
                other.append(line)
            }
        }
        for line in ranked.sorted(by: { $0.0 < $1.0 }).map(\.1) + other {
            let found = allMatches(pricePattern, in: line).map { $0.groups[0].trimmingCharacters(in: .whitespaces) }
            // "2 x 19,90 € = 39,80 €": the result.
            if let value = line.contains("=") ? found.last : found.first { return value }
        }
        return nil
    }

    /// Reductions printed with the passenger: BahnCard, Vorteilscard, Halbtax, railcards.
    private static func discount(_ text: String) -> String? {
        let patterns = [#"\bBahnCard\s?(?:25|50|100)(?:\s?(?:Business|1\.\s?Klasse|2\.\s?Klasse))?"#, #"\bBC\s?(?:25|50|100)\b"#,
                        #"\bVorteilscard(?:\s(?:Classic|Jugend|Senior|Family|Familie|Spezial|66))?"#, #"\bHalbtax\b"#, #"\bGA\s+(?:1\.|2\.)\s?Kl"#,
                        #"\bCarte\s(?:Avantage|Liberté)(?:\s(?:Jeune|Adulte|Senior|Week-end))?"#,
                        #"\b(?:16-25|26-30|Senior|Two Together|Family & Friends|Disabled Persons)\sRailcard\b"#, #"\bInterrail Pass\b"#]
        for p in patterns {
            if let m = firstMatch(p, in: text, caseInsensitive: true) { return m[0].trimmingCharacters(in: .whitespaces) }
        }
        return nil
    }

    /// "2 Erwachsene, 1 Kind" as printed; nil when the ticket names no counts.
    private static func travellers(_ lines: [String]) -> String? {
        let words = #"Erwachsene[rn]?|Kinder|Kind|Jugendliche[r]?|Senior(?:en|in)?|Personen|Person|Reisende|Adults?|Child(?:ren)?|Youths?|Seniors?|Passengers?|Adultes?|Enfants?|Adulti|Adulto|Bambin[io]|Ragazz[io]|Adultos?|Niños?|Niño|Volwassenen?|Kinderen|Dorośl[iy]|Dzieci|Dziecko|Dospěl[ýí]|Vuxna?|Felnőtt|Yetişkin"#
        var out: [String] = []
        for line in lines {
            let ns = line as NSString
            for m in allMatches(#"(?<![\d.,:])([1-9])\s*(?:x\s*)?("# + words + #")(?![\p{L}])"#, in: line) {
                // "bis zu 5 Personen", "Kinder unter 6 Jahren frei": conditions, not the people on this ticket.
                let before = fold(ns.substring(to: m.range.location)).suffix(10)
                let after = fold(ns.substring(from: m.range.location + m.range.length)).prefix(14)
                if ["bis zu", "max", "up to", "jusqu", "fino a", "hasta"].contains(where: { before.contains($0) })
                    || ["bis ", "unter", "under", "frei", "free", "gratis", "jusqu", "fino"].contains(where: { after.contains($0) }) { continue }
                let item = "\(m.groups[1]) \(m.groups[2])"
                if !out.contains(where: { fold($0) == fold(item) }) { out.append(item) }
            }
        }
        return out.isEmpty ? nil : out.prefix(4).joined(separator: ", ")
    }

    // MARK: - Validity

    private static let excludedDateWords = ["geb", "geburt", "birth", "né le", "née le", "nato", "kauf", "gekauft", "purchase",
                                            "bought", "ausgestellt", "issued", "erstellt", "created", "buchung am", "gebucht",
                                            "booked", "bestellt", "ordered", "druck", "printed", "rechnung", "invoice", "acquist", "achat",
                                            "emis le", "émis le", "fecha de compra", "data emissione", "data di emissione", "aangeschaft",
                                            "gekocht", "zakup", "data zakupu", "datum nákupu", "zakoupeno", "köpt", "vásárlás", "satın"]
    private static let untilWords = ["bis", "until", "valid to", "valid till", "expires", "expiry", "ablauf", "geltungsende",
                                     "jusqu", "fino al", "fino a", "ende", "tot", "t/m", "tot en met", "ważny do", "platnost do", "platí do", "hasta", "até",
                                     "till", "gültigkeitsende", "verfällt", "expire", "scadenza", "scade"]
    private static let fromWords = ["ab", "from", "valid from", "gültig von", "vom", "geltungsbeginn", "beginn", "à partir",
                                    "a partire", "dal", "start", "vanaf", "ważny od", "platnost od", "platí od", "desde", "från", "dès", "entwertet", "entwertung",
                                    "aktiviert", "activated", "validated", "validé", "convalidato", "gestempelt"]
    private static let validityWords = ["gültig", "gueltig", "gültigkeit", "geltungsdauer", "geltungszeitraum", "valid", "validity",
                                        "reisedatum", "reisetag", "travel date", "date of travel", "fahrtantritt", "hinfahrt", "fahrt am",
                                        "datum", "date", "data", "valable", "valido", "validità", "geltungstag", "reisezeitraum", "fahrtag",
                                        "date of journey", "journey date", "outbound", "date du voyage", "data viaggio", "data di viaggio",
                                        "fecha", "válido", "validez", "validade", "geldig", "reisdatum", "ważny", "ważność",
                                        "data podróży", "platnost", "platí", "datum cesty", "giltig", "resdag", "érvényes",
                                        "érvényesség", "geçerli", "tarih", "voyage", "viaggio", "viaje"]
    /// The second date on a return ticket.
    private static let returnWords = ["rückfahrt", "rückreise", "return", "retour", "ritorno", "vuelta", "regreso", "terugreis",
                                      "powrót", "zpět", "återresa", "visszaút", "dönüş"]

    struct FoundDate {
        var components: DateComponents
        var hasTime: Bool
        var position: Int
        var length = 1
        /// Whole month ("Oktober 2025") without a day.
        var wholeMonth = false
    }

    private static func validity(_ lines: [String], calendar: Calendar, strict: Bool,
                                 category: TicketPass.Category? = nil) -> (from: Date?, until: Date?) {
        var from: FoundDate?
        var until: FoundDate?
        var day: FoundDate?
        var returnDay: FoundDate?
        var loose: [FoundDate] = []
        var month: FoundDate?

        for (i, line) in lines.enumerated() {
            let f = fold(line)
            if excludedDateWords.contains(where: { f.contains($0) }) { continue }
            var dates = findDates(line)
            var context = f
            if dates.isEmpty {
                // Label alone on its line, date on the next.
                guard i + 1 < lines.count, validityWords.contains(where: { f.contains($0) }) || untilWords.contains(where: { containsWord($0, in: f) }) || fromWords.contains(where: { containsWord($0, in: f) }) else { continue }
                let next = findDates(lines[i + 1])
                guard !next.isEmpty, !excludedDateWords.contains(where: { fold(lines[i + 1]).contains($0) }) else { continue }
                dates = next.map { var d = $0; d.position += f.count + 1; return d }
                context = f + " " + fold(lines[i + 1])
            }
            if strict {
                let explicitWords = validityWords.filter { !["datum", "date", "data"].contains($0) }
                let labelled = explicitWords.contains { context.contains($0) }
                    || untilWords.contains { containsWord($0, in: context) }
                    || fromWords.contains { containsWord($0, in: context) }
                guard labelled else { continue }
            }
            if dates.count == 1, dates[0].wholeMonth {
                if month == nil { month = dates[0] }
                continue
            }
            if dates.count >= 2 {
                let sorted = dates.sorted { (date($0, end: false, calendar) ?? .distantPast) < (date($1, end: false, calendar) ?? .distantPast) }
                if from == nil { from = sorted.first }
                if until == nil { until = sorted.last }
                continue
            }
            guard let found = dates.first else { continue }
            let before = String(context.prefix(found.position))
            if returnWords.contains(where: { containsWord($0, in: before) }) {
                if returnDay == nil { returnDay = found }
            } else if untilWords.contains(where: { containsWord($0, in: before) }) {
                if until == nil { until = found }
            } else if fromWords.contains(where: { containsWord($0, in: before) }) {
                if from == nil { from = found }
            } else if validityWords.contains(where: { context.contains($0) }) {
                if day == nil { day = found }
            } else {
                loose.append(found)
            }
        }

        var start: Date?
        var end: Date?
        // "Gültig 90 Minuten ab 14.10.2025 10:15", "7 Tage gültig ab 01.10.2025".
        if until == nil, let f = from ?? (category == .dayPass ? day : nil), let span = duration(lines, dayPass: category == .dayPass),
           span.days > 0 || f.hasTime, let s = date(f, end: false, calendar) {
            start = s
            if span.days > 0 {
                let last = calendar.date(byAdding: .day, value: span.days - (f.hasTime ? 0 : 1), to: s)
                end = f.hasTime ? last : last.flatMap { calendar.date(bySettingHour: 23, minute: 59, second: 59, of: $0) }
            } else {
                end = s.addingTimeInterval(span.seconds)
            }
            return (start, end)
        }
        if from == nil, until == nil {
            if let day {
                from = day
                until = FoundDate(components: (returnDay ?? day).components, hasTime: false, position: 0)
            } else if let returnDay {
                from = returnDay
                until = FoundDate(components: returnDay.components, hasTime: false, position: 0)
            } else if let month {
                from = month
                until = month
            } else if loose.count >= 2 {
                let sorted = loose.sorted { (date($0, end: false, calendar) ?? .distantPast) < (date($1, end: false, calendar) ?? .distantPast) }
                from = sorted.first
                until = sorted.last
            } else if let only = loose.first {
                from = only
                until = FoundDate(components: only.components, hasTime: false, position: 0)
            }
        }
        start = from.flatMap { date($0, end: false, calendar) }
        end = until.flatMap { date($0, end: true, calendar) }
        if !strict, let s = start, let e = end, s > e { (start, end) = (e, s) }
        return (start, end)
    }

    /// A validity length printed with the validity: minutes and hours, or whole days.
    private static func duration(_ lines: [String], dayPass: Bool) -> (seconds: TimeInterval, days: Int)? {
        let units: [(String, TimeInterval)] = [
            (#"min(?:uten|utes|uti|utos|ut|\.)?|perc|dakika"#, 60),
            (#"std\.?|stunden|stunde|h|hours?|heures?|ore|horas|uur|godzin[y]?|hodin[y]?|timmar|óra|saat"#, 3600),
            (#"tage|tagen|days?|jours?|giorni|días|dias|dagen|dni|dní|dagar|nap|gün"#, 86400),
        ]
        let validWords = ["gültig", "gilt", "valid", "geldig", "valable", "valido", "válido", "ważny", "platn", "giltig", "érvényes", "geçerli"]
        for line in lines {
            let f = fold(line)
            guard validWords.contains(where: { f.contains($0) }) || dayPass else { continue }
            for (unit, seconds) in units {
                guard let m = firstMatch(#"(?<![\d.,:])(\d{1,3})\s*-?\s*(?:"# + unit + #")(?![\p{L}\d])"#, in: f, caseInsensitive: true),
                      let n = Int(m[1]), n > 0 else { continue }
                if seconds >= 86400 { return n <= 400 ? (Double(n) * seconds, n) : nil }
                return n * Int(seconds) <= 72 * 3600 ? (Double(n) * seconds, 0) : nil
            }
        }
        return nil
    }

    /// Explicit field assignment: the user chose this text, so no label inference is needed.
    /// A printed range supplies its first date for "from" and its last for "until".
    static func date(in text: String, end: Bool, calendar: Calendar = .current) -> Date? {
        let found = findDates(clean(text)).sorted { $0.position < $1.position }
        guard let value = end ? found.last : found.first else { return nil }
        return date(value, end: end, calendar)
    }

    /// Dates without a time start at midnight and end at 23:59:59; whole months cover the month.
    private static func date(_ found: FoundDate, end: Bool, _ calendar: Calendar) -> Date? {
        var c = found.components
        c.calendar = calendar
        c.timeZone = calendar.timeZone
        if found.wholeMonth {
            c.day = 1
            guard let first = calendar.date(from: c) else { return nil }
            guard end else { return first }
            guard let next = calendar.date(byAdding: .month, value: 1, to: first) else { return nil }
            return next.addingTimeInterval(-1)
        }
        if !found.hasTime {
            c.hour = end ? 23 : 0
            c.minute = end ? 59 : 0
            c.second = end ? 59 : 0
        }
        guard let date = calendar.date(from: c), calendar.component(.day, from: date) == c.day else { return nil }
        return date
    }

    /// Months whose abbreviations ("Okt", "Sept", "févr") are read too.
    private static let monthNames: [[String]] = [
        ["januar", "jänner", "january", "janvier", "gennaio"], ["februar", "feber", "february", "février", "fevrier", "febbraio"],
        ["märz", "march", "mars", "marzo", "mrz"], ["april", "avril", "aprile"], ["mai", "may", "maggio"],
        ["juni", "june", "juin", "giugno"], ["juli", "july", "juillet", "luglio"], ["august", "août", "aout", "agosto"],
        ["september", "septembre", "settembre", "sept"], ["oktober", "october", "octobre", "ottobre"],
        ["november", "novembre"], ["dezember", "december", "décembre", "decembre", "dicembre"],
    ]
    /// Further languages, full names only (also the genitive forms printed in dates), so short words stay words.
    private static let moreMonthNames: [[String]] = [
        ["enero", "janeiro", "januari", "stycznia", "styczeń", "ledna", "leden", "január", "ocak", "ianuarie"],
        ["febrero", "fevereiro", "februari", "lutego", "luty", "února", "únor", "február", "şubat", "februarie"],
        ["marzo", "março", "maart", "marca", "marzec", "března", "březen", "mars", "március", "mart", "martie"],
        ["abril", "april", "kwietnia", "kwiecień", "dubna", "duben", "április", "nisan", "aprilie"],
        ["mayo", "maio", "mei", "maja", "maj", "května", "květen", "május", "mayıs"],
        ["junio", "junho", "juni", "czerwca", "czerwiec", "června", "červen", "június", "haziran", "iunie"],
        ["julio", "julho", "juli", "lipca", "lipiec", "července", "červenec", "július", "temmuz", "iulie"],
        ["agosto", "augustus", "sierpnia", "sierpień", "srpna", "srpen", "augusti", "augusztus", "ağustos"],
        ["septiembre", "setiembre", "setembro", "września", "wrzesień", "září", "szeptember", "eylül", "septembrie"],
        ["octubre", "outubro", "października", "październik", "října", "říjen", "október", "ekim", "octombrie"],
        ["noviembre", "novembro", "listopada", "listopad", "listopadu", "kasım", "noiembrie"],
        ["diciembre", "dezembro", "grudnia", "grudzień", "prosince", "prosinec", "aralık", "decembrie"],
    ]

    /// A month name or its abbreviation of three or four letters ("Okt", "Sept"), never a longer word that only starts like one.
    private static func month(_ word: String) -> Int? {
        let w = word.lowercased().trimmingCharacters(in: CharacterSet(charactersIn: "."))
        guard w.count >= 3 else { return nil }
        for (i, names) in monthNames.enumerated() {
            if names.contains(w) { return i + 1 }
            if w.count <= 4, names.contains(where: { $0.hasPrefix(w) }) { return i + 1 }
        }
        for (i, names) in moreMonthNames.enumerated() where names.contains(w) { return i + 1 }
        return nil
    }

    static func findDates(_ line: String) -> [FoundDate] {
        var out: [FoundDate] = []
        let ns = line as NSString
        func year(_ s: String) -> Int? {
            guard let y = Int(s) else { return nil }
            return s.count == 2 ? 2000 + y : y
        }
        func time(after end: Int) -> (Int, Int)? {
            let rest = ns.substring(from: end)
            guard let m = firstMatch(#"^\s*(?:,|um|at|ab|à|alle|-|om|o|kl\.?|saat)?\s*(\d{1,2})[:h](\d{2})"#, in: rest, caseInsensitive: true),
                  let h = Int(m[1]), let mi = Int(m[2]), h < 24, mi < 60 else { return nil }
            return (h, mi)
        }
        func add(_ y: Int?, _ m: Int?, _ d: Int?, _ range: NSRange, wholeMonth: Bool = false) {
            guard let y, let m, (1...12).contains(m), (2000...2100).contains(y) else { return }
            if !wholeMonth { guard let d, (1...31).contains(d) else { return } }
            guard !out.contains(where: { NSIntersectionRange(NSRange(location: $0.position, length: $0.length), range).length > 0 }) else { return }
            var c = DateComponents(year: y, month: m, day: wholeMonth ? 1 : d)
            var hasTime = false
            if !wholeMonth, let t = time(after: range.location + range.length) {
                c.hour = t.0; c.minute = t.1; c.second = 0
                hasTime = true
            }
            out.append(FoundDate(components: c, hasTime: hasTime, position: range.location, length: range.length, wholeMonth: wholeMonth))
        }
        // Ranges sharing month and year: "14.–20.10.2025", "14.10.–20.10.2025" (a December start takes the year before).
        for m in allMatches(#"(?<![\d.:])(\d{1,2})\.\s?(\d{1,2})\.?\s?[-–—]\s?(\d{1,2})\.\s?(\d{1,2})\.\s?(\d{4}|\d{2})(?!\d)"#, in: line) {
            guard let y = year(m.groups[5]), let m1 = Int(m.groups[2]), let m2 = Int(m.groups[4]) else { continue }
            add(m1 > m2 ? y - 1 : y, m1, Int(m.groups[1]), NSRange(location: m.ranges[1].location, length: m.ranges[2].upperBound - m.ranges[1].location))
            add(y, m2, Int(m.groups[3]), NSRange(location: m.ranges[3].location, length: m.ranges[5].upperBound - m.ranges[3].location))
        }
        for m in allMatches(#"(?<![\d.:])(\d{1,2})\.?\s?[-–—]\s?(\d{1,2})\.\s?(\d{1,2})\.\s?(\d{4}|\d{2})(?!\d)"#, in: line) {
            let y = year(m.groups[4]), mo = Int(m.groups[3])
            add(y, mo, Int(m.groups[1]), m.ranges[1])
            add(y, mo, Int(m.groups[2]), NSRange(location: m.ranges[2].location, length: m.ranges[4].upperBound - m.ranges[2].location))
        }
        for m in allMatches(#"(?<!\d)(\d{1,2})\.\s?(\d{1,2})\.\s?(\d{4}|\d{2})(?!\d)"#, in: line) {
            add(year(m.groups[3]), Int(m.groups[2]), Int(m.groups[1]), m.range)
        }
        for m in allMatches(#"(?<!\d)(\d{4})-(\d{2})-(\d{2})(?!\d)"#, in: line) {
            add(Int(m.groups[1]), Int(m.groups[2]), Int(m.groups[3]), m.range)
        }
        for m in allMatches(#"(?<![\d/])(\d{1,2})/(\d{1,2})/(\d{4}|\d{2})(?![\d/])"#, in: line) {
            add(year(m.groups[3]), Int(m.groups[2]), Int(m.groups[1]), m.range)
        }
        for m in allMatches(#"(?<![\d-])(\d{1,2})-(\d{1,2})-(\d{4})(?!\d)"#, in: line) {
            add(Int(m.groups[3]), Int(m.groups[2]), Int(m.groups[1]), m.range)
        }
        // "14 Okt 2025", "14. Oktober 2025", "14th October 2025", "14 de octubre de 2025", "14 października 2025"
        for m in allMatches(#"(?<!\d)(\d{1,2})(?:st|nd|rd|th)?\.?\s+(?:of\s+|de\s+)?(\p{L}{3,})\.?,?\s+(?:de\s+)?(\d{4})(?!\d)"#, in: line) {
            if let mo = month(m.groups[2]) { add(Int(m.groups[3]), mo, Int(m.groups[1]), m.range) }
        }
        // "Oct 14, 2025", "October 14th 2025"
        for m in allMatches(#"(?<![\p{L}\d.])(\p{L}{3,})\.?\s+(\d{1,2})(?:st|nd|rd|th)?,?\s+(\d{4})(?!\d)"#, in: line) {
            if let mo = month(m.groups[1]) { add(Int(m.groups[3]), mo, Int(m.groups[2]), m.range) }
        }
        for m in allMatches(#"(?<![\p{L}\d.])(\p{L}{3,})\.?\s+(\d{4})(?!\d)"#, in: line) {
            if let mo = month(m.groups[1]) { add(Int(m.groups[2]), mo, nil, m.range, wholeMonth: true) }
        }
        return out.sorted { $0.position < $1.position }
    }

    // MARK: - Text helpers

    static func clean(_ line: String) -> String {
        line.replacingOccurrences(of: #"\s+"#, with: " ", options: .regularExpression)
            .trimmingCharacters(in: .whitespacesAndNewlines)
    }

    /// Lowercased for label matching. Accents stay: "Gültig" and "à partir" are matched as written.
    static func fold(_ s: String) -> String { s.lowercased() }

    /// Case-insensitive match; short all-caps patterns ("SBB", "MVV") need whole words in capitals.
    static func matches(_ pattern: String, in text: String) -> Bool {
        if pattern.count <= 4, pattern == pattern.uppercased() {
            return firstMatch("(?<![\\p{L}\\p{N}])" + NSRegularExpression.escapedPattern(for: pattern) + "(?![\\p{L}\\p{N}])",
                              in: text, caseInsensitive: false) != nil
        }
        return text.range(of: pattern, options: [.caseInsensitive]) != nil
    }

    static func containsWord(_ word: String, in text: String) -> Bool {
        firstMatch("(?<![\\p{L}\\p{N}])" + NSRegularExpression.escapedPattern(for: word) + "(?![\\p{L}\\p{N}])",
                   in: text, caseInsensitive: true) != nil
    }

    /// Compiled once: a page runs the same few hundred patterns over every line.
    private static let cache = RegexCache()

    private final class RegexCache: @unchecked Sendable {
        private let lock = NSLock()
        private var compiled: [String: NSRegularExpression] = [:]

        func get(_ pattern: String, _ caseInsensitive: Bool) -> NSRegularExpression? {
            let key = (caseInsensitive ? "i:" : "c:") + pattern
            lock.lock(); defer { lock.unlock() }
            if let re = compiled[key] { return re }
            guard let re = try? NSRegularExpression(pattern: pattern, options: caseInsensitive ? [.caseInsensitive] : []) else { return nil }
            if compiled.count > 2000 { compiled.removeAll() }
            compiled[key] = re
            return re
        }
    }

    static func regex(_ pattern: String, caseInsensitive: Bool) -> NSRegularExpression? { cache.get(pattern, caseInsensitive) }

    struct Match {
        let range: NSRange
        let groups: [String]
        /// Range of each group (location NSNotFound when it did not take part).
        var ranges: [NSRange] = []
    }

    static func allMatches(_ pattern: String, in text: String) -> [Match] {
        guard let re = regex(pattern, caseInsensitive: true) else { return [] }
        let ns = text as NSString
        return re.matches(in: text, range: NSRange(location: 0, length: ns.length)).map { m in
            Match(range: m.range, groups: (0..<m.numberOfRanges).map { i in
                let r = m.range(at: i)
                return r.location == NSNotFound ? "" : ns.substring(with: r)
            }, ranges: (0..<m.numberOfRanges).map { m.range(at: $0) })
        }
    }

    static func firstMatch(_ pattern: String, in text: String, caseInsensitive: Bool) -> [String]? {
        guard let re = regex(pattern, caseInsensitive: caseInsensitive) else { return nil }
        let ns = text as NSString
        guard let m = re.firstMatch(in: text, range: NSRange(location: 0, length: ns.length)) else { return nil }
        return (0..<m.numberOfRanges).map { i in
            let r = m.range(at: i)
            return r.location == NSNotFound ? "" : ns.substring(with: r)
        }
    }
}
