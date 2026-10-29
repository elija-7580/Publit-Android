package io.github.elija7580.publit.tickets

import java.time.Instant
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap
import java.util.regex.Pattern

/** Operators the recogniser knows by name or UIC company code (KnownOperators on iOS). */
object KnownOperators {
    data class Operator(val name: String, val patterns: List<String>, val color: String, val symbol: TicketPass.Symbol,
                        val uicCode: String?, val wikidata: String? = null, val domain: String? = null)

    val all = listOf(
        Operator("ÖBB", listOf("ÖBB", "OEBB", "Österreichische Bundesbahnen", "oebb.at", "Nightjet"), "D70015", TicketPass.Symbol.TRAIN, "1181", "Q83822", "oebb.at"),
        Operator("Deutsche Bahn", listOf("Deutsche Bahn", "DB Fernverkehr", "DB Regio", "DB Navigator", "bahn.de", "DB"), "EC0016", TicketPass.Symbol.TRAIN, "1080", "Q9322", "bahn.de"),
        Operator("SBB", listOf("SBB CFF FFS", "SBB", "sbb.ch"), "D70015", TicketPass.Symbol.TRAIN, "1185", "Q83835", "sbb.ch"),
        Operator("WESTbahn", listOf("WESTbahn"), "00578A", TicketPass.Symbol.TRAIN, null, "Q266280", "westbahn.at"),
        Operator("Wiener Linien", listOf("Wiener Linien", "wienerlinien"), "D70015", TicketPass.Symbol.METRO, null, "Q668849", "wienerlinien.at"),
        Operator("MVV", listOf("MVV", "Münchner Verkehrs"), "0065AE", TicketPass.Symbol.METRO, null, "Q259000", "mvv-muenchen.de"),
        Operator("BVG", listOf("BVG", "Berliner Verkehrsbetriebe"), "F0D722", TicketPass.Symbol.METRO, null, "Q99633", "bvg.de"),
        Operator("Trenitalia", listOf("Trenitalia"), "C8102E", TicketPass.Symbol.TRAIN, "1183", "Q286650", "trenitalia.com"),
        Operator("SNCF", listOf("SNCF", "TGV INOUI", "OUIGO"), "8C1D5A", TicketPass.Symbol.TRAIN, "1187", "Q13646", "sncf-connect.com"),
        Operator("NS", listOf("NS International", "Nederlandse Spoorwegen"), "FFC917", TicketPass.Symbol.TRAIN, "1184", "Q23076", "ns.nl"),
        Operator("SNCB", listOf("SNCB", "NMBS"), "0069B4", TicketPass.Symbol.TRAIN, "1188", "Q524255", "belgiantrain.be"),
        Operator("CFL", listOf("CFL", "Chemins de fer luxembourgeois"), "B5121B", TicketPass.Symbol.TRAIN, "1182", "Q525359", "cfl.lu"),
        Operator("ČD", listOf("České dráhy", "Ceske drahy", "ČD"), "0B4EA2", TicketPass.Symbol.TRAIN, "1154", "Q304944", "cd.cz"),
        Operator("Renfe", listOf("Renfe"), "6C2C91", TicketPass.Symbol.TRAIN, "1071", "Q2476154", "renfe.com"),
        Operator("RegioJet", listOf("RegioJet"), "FECB00", TicketPass.Symbol.TRAIN, null, "Q221186", "regiojet.com"),
        Operator("FlixBus", listOf("FlixBus", "FlixTrain"), "73D700", TicketPass.Symbol.BUS, null, "Q1881378", "flixbus.com"),
        Operator("Italo", listOf("Italo Treno", "italotreno", "Italo"), "9E1B32", TicketPass.Symbol.TRAIN, null, null, "italotreno.com"),
        Operator("Trenord", listOf("Trenord"), "009A44", TicketPass.Symbol.TRAIN, null, null, "trenord.it"),
        Operator("Eurostar", listOf("Eurostar"), "1E2A5A", TicketPass.Symbol.TRAIN, null, null, "eurostar.com"),
        Operator("iryo", listOf("iryo"), "C8102E", TicketPass.Symbol.TRAIN, null, null, "iryo.eu"),
        Operator("PKP Intercity", listOf("PKP Intercity", "PKP IC", "intercity.pl"), "002F6C", TicketPass.Symbol.TRAIN, null, null, "intercity.pl"),
        Operator("MÁV", listOf("MÁV-START", "MAV-START", "MÁV", "mavcsoport"), "003F87", TicketPass.Symbol.TRAIN, "1155", null, "mavcsoport.hu"),
        Operator("ZSSK", listOf("ZSSK", "Železničná spoločnosť Slovensko"), "D31E25", TicketPass.Symbol.TRAIN, "1156", null, "zssk.sk"),
        Operator("SŽ", listOf("Slovenske železnice", "SŽ"), "005BAA", TicketPass.Symbol.TRAIN, "1179", null, "sz.si"),
        Operator("DSB", listOf("DSB", "dsb.dk"), "C8102E", TicketPass.Symbol.TRAIN, "1186", null, "dsb.dk"),
        Operator("SJ", listOf("SJ AB", "Statens Järnvägar", "sj.se"), "2B2B2B", TicketPass.Symbol.TRAIN, "1174", null, "sj.se"),
        Operator("Leo Express", listOf("Leo Express"), "202020", TicketPass.Symbol.TRAIN, null, null, "leoexpress.com"),
        Operator("BLS", listOf("BLS AG", "bls.ch"), "6AB023", TicketPass.Symbol.TRAIN, null, null, "bls.ch"),
        Operator("ZVV", listOf("ZVV", "Zürcher Verkehrsverbund"), "0479CC", TicketPass.Symbol.TRAM, null, null, "zvv.ch"),
        Operator("PostAuto", listOf("PostAuto", "CarPostal", "AutoPostale"), "FFCC00", TicketPass.Symbol.BUS, null, null, "postauto.ch"),
        Operator("RATP", listOf("RATP"), "00A0A0", TicketPass.Symbol.METRO, null, null, "ratp.fr"),
        Operator("VOR", listOf("Verkehrsverbund Ost-Region", "vor.at"), "C8102E", TicketPass.Symbol.TRAIN, null, null, "vor.at"),
        Operator("OÖVV", listOf("OÖVV", "OÖ Verkehrsverbund", "ooevv.at"), "E30613", TicketPass.Symbol.BUS, null, null, "ooevv.at"),
        Operator("VVT", listOf("VVT", "Verkehrsverbund Tirol"), "009FE3", TicketPass.Symbol.BUS, null, null, "vvt.at"),
        Operator("Salzburg Verkehr", listOf("Salzburg Verkehr", "Salzburger Verkehrsverbund"), "D40F14", TicketPass.Symbol.BUS, null, null, "salzburg-verkehr.at"),
        Operator("Verbund Linie", listOf("Verbund Linie", "Steirischer Verkehrsverbund", "verbundlinie.at"), "E2001A", TicketPass.Symbol.BUS, null, null, "verbundlinie.at"),
        Operator("Graz Linien", listOf("Graz Linien", "Holding Graz"), "00A04B", TicketPass.Symbol.TRAM, null, null, "holding-graz.at"),
        Operator("LINZ AG LINIEN", listOf("LINZ AG", "Linz Linien"), "004F9F", TicketPass.Symbol.TRAM, null, null, "linzag.at"),
        Operator("Wiener Lokalbahnen", listOf("Wiener Lokalbahnen", "Badner Bahn"), "004C97", TicketPass.Symbol.TRAM, null, null, "wlb.at"),
        Operator("HVV", listOf("HVV", "Hamburger Verkehrsverbund"), "E2001A", TicketPass.Symbol.METRO, null, null, "hvv.de"),
        Operator("VBB", listOf("VBB", "Verkehrsverbund Berlin-Brandenburg"), "E3000F", TicketPass.Symbol.METRO, null, null, "vbb.de"),
        Operator("MVG", listOf("MVG", "Münchner Verkehrsgesellschaft"), "0065AE", TicketPass.Symbol.METRO, null, null, "mvg.de"),
        Operator("RMV", listOf("RMV", "Rhein-Main-Verkehrsverbund"), "0068B4", TicketPass.Symbol.TRAIN, null, null, "rmv.de"),
        Operator("VRR", listOf("VRR", "Verkehrsverbund Rhein-Ruhr"), "00A1DE", TicketPass.Symbol.TRAM, null, null, "vrr.de"),
        Operator("VVS", listOf("VVS", "Verkehrs- und Tarifverbund Stuttgart"), "F9B000", TicketPass.Symbol.TRAIN, null, null, "vvs.de"),
        Operator("KVB", listOf("KVB", "Kölner Verkehrs-Betriebe"), "E30613", TicketPass.Symbol.TRAM, null, null, "kvb.koeln"),
        Operator("VGN", listOf("VGN", "Verkehrsverbund Großraum Nürnberg"), "E3000F", TicketPass.Symbol.TRAIN, null, null, "vgn.de"),
        Operator("DPP", listOf("DPP", "Dopravní podnik hl. m. Prahy", "Lítačka"), "E30613", TicketPass.Symbol.METRO, null, null, "dpp.cz"),
    )

    fun find(name: String): Operator? {
        val folded = TicketTextParser.fold(name)
        return all.firstOrNull { it.name == name || TicketTextParser.fold(it.name) == folded }
            ?: all.firstOrNull { op -> op.patterns.any { TicketTextParser.matches(it, name) } }
    }

    fun uic(code: String) = all.firstOrNull { it.uicCode == code }
}

/** What the recogniser read from a ticket. Every field is a suggestion the user checks. */
data class TicketDraft(
    var title: String? = null, var operatorName: String? = null, var category: TicketPass.Category? = null,
    var symbol: TicketPass.Symbol? = null, var origin: String? = null, var destination: String? = null, var via: String? = null,
    var passenger: String? = null, var travelClass: String? = null, var price: String? = null, var reference: String? = null,
    var validFrom: Instant? = null, var validUntil: Instant? = null,
)

/**
 * Reads ticket details from recognised text lines, entirely on the device (TicketTextParser on iOS).
 * Rules, not guesses: a field stays empty when no label or pattern supports it.
 */
object TicketTextParser {
    fun parse(raw: List<String>, barcodes: List<TicketPass.Barcode> = emptyList(), zone: ZoneId = ZoneId.systemDefault(),
              strictValidity: Boolean = false): TicketDraft {
        val lines = raw.map(::clean).filter { it.isNotEmpty() }
        val text = lines.joinToString("\n")
        val d = TicketDraft()
        // The issuer in a rail barcode is certain; otherwise the operator named nearest the top.
        val op = barcodes.firstNotNullOfOrNull { b -> b.uicIssuerCode?.let { KnownOperators.uic(it) } }
            ?: lines.firstNotNullOfOrNull { line -> KnownOperators.all.firstOrNull { op -> op.patterns.any { matches(it, line) } } }
        d.operatorName = op?.name
        d.symbol = op?.symbol

        val product = products.firstOrNull { p -> p.patterns.any { matches(it, text) } }
        d.title = product?.title
        d.category = product?.category ?: category(text)

        route(lines).let { d.origin = it.first; d.destination = it.second; d.via = it.third }
        val name = labelled(lines, passengerLabels) { v -> v.firstOrNull()?.isLetter() == true && v.length <= 60 && v.count { it.isDigit() } < 3 }
        val body = lines.filter { l -> listOf("www", "http", "jetzt", "bestell", "order now", "sparen", "save ").none { fold(l).contains(it) } }
        val discount = if (product != null && product.title in discountProducts) null else discount(body.joinToString("\n"))
        val people = travellers(body)
        d.passenger = listOfNotNull(name, people, discount).joinToString(" · ").ifEmpty { null }
        d.travelClass = travelClass(text)
        d.price = price(lines)
        d.reference = labelled(lines, referenceLabels) { v ->
            (v.length in 5..40 && v.any { it.isDigit() }) || (v.length in 6..40 && v.all { it.isUpperCase() || it.isDigit() || it == '-' })
        }
        val (from, until) = validity(lines, zone, strictValidity, d.category)
        d.validFrom = from; d.validUntil = until
        if ((d.category == null || (product == null && d.category == TicketPass.Category.SINGLE)) && from != null && until != null &&
            until.epochSecond - from.epochSecond > 36 * 3600) {
            d.category = if (until.epochSecond - from.epochSecond > 40 * 24 * 3600) TicketPass.Category.SUBSCRIPTION else TicketPass.Category.PERIOD
        }
        return d
    }

    // Products and categories

    data class Product(val title: String, val patterns: List<String>, val category: TicketPass.Category)

    val products = listOf(
        Product("Deutschlandticket", listOf("Deutschlandticket", "Deutschland-Ticket", "D-Ticket"), TicketPass.Category.SUBSCRIPTION),
        Product("KlimaTicket Ö", listOf("KlimaTicket Ö", "Klimaticket Österreich", "KlimaTicket Österreich"), TicketPass.Category.SUBSCRIPTION),
        Product("KlimaTicket", listOf("KlimaTicket"), TicketPass.Category.SUBSCRIPTION),
        Product("Interrail Pass", listOf("Interrail"), TicketPass.Category.PERIOD),
        Product("Eurail Pass", listOf("Eurail"), TicketPass.Category.PERIOD),
        Product("Jahreskarte", listOf("Jahreskarte", "Jahresabo"), TicketPass.Category.SUBSCRIPTION),
        Product("Monatskarte", listOf("Monatskarte"), TicketPass.Category.PERIOD),
        Product("Wochenkarte", listOf("Wochenkarte"), TicketPass.Category.PERIOD),
        Product("Tageskarte", listOf("Tageskarte", "Tagesticket", "24-Stunden-Karte", "24 Stunden"), TicketPass.Category.DAY_PASS),
        Product("Sparschiene", listOf("Sparschiene"), TicketPass.Category.SINGLE),
        Product("Super Sparpreis", listOf("Super Sparpreis"), TicketPass.Category.SINGLE),
        Product("Sparpreis", listOf("Sparpreis"), TicketPass.Category.SINGLE),
        Product("Flexpreis", listOf("Flexpreis"), TicketPass.Category.SINGLE),
        Product("Standard-Ticket", listOf("Standard-Ticket", "Standardticket"), TicketPass.Category.SINGLE),
        Product("Einfach-Raus-Ticket", listOf("Einfach-Raus-Ticket", "Einfach Raus Ticket"), TicketPass.Category.DAY_PASS),
        Product("Quer-durchs-Land-Ticket", listOf("Quer-durchs-Land", "Quer durchs Land"), TicketPass.Category.DAY_PASS),
        Product("Bayern-Ticket", listOf("Bayern-Ticket", "Bayernticket"), TicketPass.Category.DAY_PASS),
        Product("Niedersachsenticket", listOf("Niedersachsenticket", "Niedersachsen-Ticket"), TicketPass.Category.DAY_PASS),
        Product("Semesterticket", listOf("Semesterticket"), TicketPass.Category.PERIOD),
        Product("Jobticket", listOf("Jobticket", "Job-Ticket"), TicketPass.Category.SUBSCRIPTION),
        Product("Kurzstrecke", listOf("Kurzstrecke"), TicketPass.Category.SINGLE),
        Product("Streifenkarte", listOf("Streifenkarte", "Mehrfahrtenkarte", "4-Fahrten-Karte", "Mehrfahrtenticket"), TicketPass.Category.OTHER),
        Product("Supersparbillett", listOf("Supersparbillett"), TicketPass.Category.SINGLE),
        Product("Sparbillett", listOf("Sparbillett"), TicketPass.Category.SINGLE),
        Product("GA", listOf("Generalabonnement", "Abonnement général"), TicketPass.Category.SUBSCRIPTION),
        Product("Navigo", listOf("Navigo"), TicketPass.Category.PERIOD),
        Product("Travelcard", listOf("Travelcard"), TicketPass.Category.PERIOD),
        Product("Frecciarossa", listOf("Frecciarossa"), TicketPass.Category.SINGLE),
        Product("Halbtax", listOf("Halbtax"), TicketPass.Category.SUBSCRIPTION),
        Product("BahnCard", listOf("BahnCard"), TicketPass.Category.SUBSCRIPTION),
        Product("Vorteilscard", listOf("Vorteilscard"), TicketPass.Category.SUBSCRIPTION),
    )

    private val discountProducts = setOf("Halbtax", "BahnCard", "Vorteilscard")

    private val categoryWords: List<Pair<TicketPass.Category, List<String>>> = listOf(
        TicketPass.Category.RETURN_TRIP to listOf("rückfahrt", "hin- und rückfahrt", "hin und rück", "hin-/rückfahrt", "return ticket", "aller-retour", "aller retour",
            "andata e ritorno", "ida y vuelta", "ida e volta", "retour", "heen en terug", "tam i z powrotem", "zpáteční",
            "tur och retur", "tur-retur", "menettérti", "gidiş-dönüş", "gidiş dönüş"),
        TicketPass.Category.DAY_PASS to listOf("tageskarte", "tagesticket", "day ticket", "day pass", "24h", "24 h", "24-stunden", "48h", "48-stunden", "72h",
            "72-stunden", "billet journée", "biglietto giornaliero", "dagkaart", "dagbiljett", "bilet dobowy", "napijegy",
            "billete diario", "bilhete diário", "celodenní", "jednodenní"),
        TicketPass.Category.SUBSCRIPTION to listOf("jahreskarte", "annual pass", "abonnement", "abo", "jaarabonnement", "årskort", "bilet roczny", "roční",
            "éves bérlet", "abbonamento annuale", "abono anual", "passe anual"),
        TicketPass.Category.PERIOD to listOf("wochenkarte", "monatskarte", "weekly", "monthly", "week pass", "month pass", "abbonamento mensile", "mensile",
            "settimanale", "mensuel", "hebdomadaire", "weekkaart", "maandkaart", "månadskort", "periodkort", "bilet miesięczny",
            "miesięczny", "tygodniowy", "měsíční", "týdenní", "havi bérlet", "bérlet", "abono", "passe mensal", "aylık"),
        TicketPass.Category.SINGLE to listOf("einzelfahrt", "einzelticket", "einzelfahrschein", "einfache fahrt", "single ticket", "one-way", "one way", "aller simple",
            "corsa semplice", "corsa singola", "biglietto ordinario", "hinfahrt", "enkele reis", "enkelbiljett", "bilet jednorazowy",
            "jednorazowy", "jednosměrná", "menetjegy", "billete sencillo", "solo ida", "bilhete simples", "tek yön"),
    )

    private fun category(text: String): TicketPass.Category? {
        val f = fold(text)
        val ticketWords = listOf("fahrkarte", "fahrschein", "ticket", "billet", "biglietto", "billete", "bilhete", "kaartje", "vervoerbewijs",
            "bilet", "jízdenka", "biljett", "menetjegy")
        if (listOf("reservierung", "reservation", "réservation", "prenotazione", "reserva", "reservering", "rezerwacja", "místenka",
                "platsbiljett", "helyjegy").any { f.contains(it) } && ticketWords.none { f.contains(it) }) return TicketPass.Category.RESERVATION
        return categoryWords.firstOrNull { (_, words) -> words.any { containsWord(fold(it), f) } }?.first
    }

    // Route

    private val fromLabels = listOf("von", "from", "start", "abfahrt", "departure", "ab", "départ", "depart", "partenza", "da",
        "abfahrtsbahnhof", "startbahnhof", "einstieg", "origin", "departure station", "gare de départ",
        "stazione di partenza", "origen", "origem", "vertrek", "van", "från", "skąd", "odkud", "honnan",
        "indulás", "kalkış", "stacja początkowa")
    private val toLabels = listOf("nach", "to", "ziel", "ankunft", "arrival", "an", "arrivée", "arrivee", "arrivo", "destination", "a",
        "zielbahnhof", "ausstieg", "arrival station", "gare d'arrivée", "stazione di arrivo", "destino",
        "aankomst", "naar", "till", "dokąd", "kam", "hova", "érkezés", "varış", "stacja docelowa")
    private val viaLabels = listOf("über", "via", "über:", "via:")
    private val arrows = listOf("→", "->", "➔", "➜", "⇒", "⇨", "►", "▶", "—>", "–>", "⟶", "»", " > ")

    private fun route(lines: List<String>): Triple<String?, String?, String?> {
        for (line in lines) {
            val arrow = arrows.firstOrNull { line.contains(it) } ?: continue
            val parts = line.split(arrow).map(::place)
            if (parts.size >= 2 && parts.all { it != null }) {
                val via = parts.drop(1).dropLast(1).filterNotNull().joinToString(", ")
                return Triple(parts.first(), parts.last(), via.ifEmpty { viaOnly(lines) })
            }
        }
        for (line in lines) {
            for ((from, to) in listOf("von" to "nach", "from" to "to", "van" to "naar", "från" to "till", "de" to "à", "da" to "a", "de" to "a")) {
                val m = firstMatch("^$from\\s+(.+?)\\s+$to\\s+(.+)$", line, true) ?: continue
                val a = place(m[1]) ?: continue
                val (b, via) = splitVia(m[2])
                if (b != null) return Triple(a, b, via ?: viaOnly(lines))
            }
            val m = firstMatch("^(?:strecke|route|relation|verbindung|itinéraire|tratta|trasa|trayecto|traject|sträcka|útvonal|güzergah)\\s*:?\\s*(.+?)\\s+[-–—]\\s+(.+)$", line, true)
            if (m != null) {
                val a = place(m[1])
                val (b, via) = splitVia(m[2])
                if (a != null && b != null) return Triple(a, b, via ?: viaOnly(lines))
            }
        }
        timetableRows(lines)?.let { return Triple(it.first, it.second, viaOnly(lines)) }
        val a = labelled(lines, fromLabels, true) { place(it) != null }?.let(::place)
        val b = labelled(lines, toLabels, true) { place(it) != null }?.let(::splitVia)
        val dest = b?.first
        if (a == null || dest == null || a == dest) return Triple(null, null, null)
        return Triple(a, dest, b.second ?: viaOnly(lines))
    }

    private fun splitVia(raw: String): Pair<String?, String?> {
        for (word in listOf(" über ", " via ", " (via ", " por ", " przez ", " přes ")) {
            val i = raw.indexOf(word, ignoreCase = true)
            if (i >= 0) return place(raw.substring(0, i)) to place(raw.substring(i + word.length).replace(")", ""))
        }
        return place(raw) to null
    }

    private fun viaOnly(lines: List<String>) = labelled(lines, viaLabels, true) { place(it) != null }?.let(::place)

    /** Timetable rows as on DB and ÖBB tickets: "Wien Hbf 14.10. ab 08:30 7", "Salzburg Hbf an 10:52". */
    private fun timetableRows(lines: List<String>): Pair<String, String>? {
        var first: String? = null; var last: String? = null
        for (line in lines) {
            val m = firstMatch("^(.+?)\\s+(?:\\d{1,2}\\.\\d{1,2}\\.(?:\\d{2,4})?\\s+)?(ab|an|dep\\.?|arr\\.?|dép\\.?)\\s+\\d{1,2}[:.]\\d{2}\\b", line, true) ?: continue
            val station = place(m[1]) ?: continue
            val f = fold(station)
            if (validityWords.any { containsWord(it, f) }) continue
            val kind = fold(m[2])
            if ((kind == "ab" || kind.startsWith("dep") || kind.startsWith("dép")) && first == null) first = station
            if (kind == "an" || kind.startsWith("arr")) last = station
        }
        return if (first != null && last != null && first != last) first to last else null
    }

    private val placeStrip = listOf("\\b\\d{1,2}[:h]\\d{2}\\b(\\s*Uhr)?", "\\b\\d{1,2}\\.\\d{1,2}\\.\\d{2,4}\\b", "\\b\\d{4}-\\d{2}-\\d{2}\\b",
        "\\b\\d{1,2}/\\d{1,2}/\\d{2,4}\\b", "\\b\\d{1,2}\\.\\d{1,2}\\.(?=\\s|$)", "(?i)\\s(?:ab|an|dep\\.?|arr\\.?)\\s*$",
        "(?i)\\b(gleis|gl\\.|bahnsteig|steig|platform|track|voie|quai|binario|andén|peron|nástupiště|spår|vágány|perron)(?!\\p{L}).*$",
        "(€|EUR|CHF)\\s*\\d.*$").map { Pattern.compile(it, Pattern.UNICODE_CASE) }

    /** A station name without times, dates, platforms or prices. */
    private fun place(raw: String): String? {
        var s = raw
        for (p in placeStrip) s = p.matcher(s).replaceAll("")
        s = s.trim { it.isWhitespace() || it in " ,;:-–—|()" }
        if (s.length !in 2..60 || s.first().isLetter().not() || s.lowercase().contains("http")) return null
        return s
    }

    // Labelled values

    private val passengerLabels = listOf("name", "reisende", "reisender", "fahrgast", "passenger", "passagier", "inhaber",
        "karteninhaber", "ticketinhaber", "holder", "voyageur", "passager", "passeggero", "reisende/r",
        "name des reisenden", "fahrgastname", "passenger name", "traveller", "traveler", "nom du voyageur",
        "nom", "nome", "nombre", "pasajero", "passageiro", "naam", "reiziger", "imię i nazwisko",
        "pasażer", "cestující", "jméno", "resenär", "namn", "utas", "név", "yolcu", "titular")
    private val referenceLabels = listOf("ticketnummer", "ticket-nr", "ticket nr", "ticket-id", "ticket id", "ticket number",
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
        "rezervasyon kodu")

    /** The value after "Label:" / "Label value", or on the next line when the label stands alone. */
    private fun labelled(lines: List<String>, labels: List<String>, requireSeparatorForShort: Boolean = false, accept: (String) -> Boolean): String? {
        val sorted = labels.sortedByDescending { it.length }
        for ((i, line) in lines.withIndex()) {
            val f = fold(line)
            for (label in sorted) {
                val l = fold(label)
                if (!f.startsWith(l)) continue
                var rest = line.substring(minOf(label.length, line.length))
                // "Name" must not match "Namensänderung".
                if (rest.firstOrNull()?.isLetter() == true) continue
                val hasColon = rest.trim().startsWith(":")
                rest = rest.trim { it.isWhitespace() || it == ':' || it == '.' }
                if (rest.isEmpty()) {
                    if (i + 1 < lines.size && accept(lines[i + 1]) && !isLabel(lines[i + 1])) return lines[i + 1]
                    continue
                }
                if (requireSeparatorForShort && label.length <= 3 && !hasColon) continue
                if (accept(rest)) return rest
            }
        }
        return null
    }

    private fun isLabel(line: String): Boolean {
        val f = fold(line).trim { it == ' ' || it == ':' }
        return f in fromLabels || f in toLabels || f in viaLabels || f in passengerLabels || f in referenceLabels
    }

    // Class, price, travellers

    private fun travelClass(text: String): String? {
        val f = fold(text)
        val numbered = listOf("\\b([12])\\.\\s*(?:klasse|kl\\.?)(?![a-z])", "klasse\\s*:?\\s*([12])\\b", "\\b([12])(?:st|nd)\\s+class\\b",
            "\\bclass\\s*:?\\s*([12])\\b", "\\b([12])\\s*(?:e|ère|ème|re)\\s+classe\\b", "\\b([12])\\s*[ªa°]?\\s+classe\\b",
            "\\bkl\\.?\\s*([12])\\b", "\\b([12])\\s+klasse\\b", "\\b([12])e\\s+klas\\b", "\\bklas\\s*:?\\s*([12])\\b",
            "\\b([12])\\.\\s*(?:klasa|třída|trida|tř\\.|osztály|klass)", "\\b(?:klasa|třída|klass|osztály)\\s*:?\\s*([12])\\b",
            "\\b([12])\\s*[ªa]?\\s*clase\\b", "\\bclasse\\s*:?\\s*([12])\\b")
        for (p in numbered) firstMatch(p, f, true)?.let { if (it.size == 2) return it[1] }
        val words = listOf("first class" to "1", "erste klasse" to "1", "première classe" to "1", "premiere classe" to "1", "prima classe" to "1",
            "primera clase" to "1", "eerste klas" to "1", "första klass" to "1",
            "second class" to "2", "standard class" to "2", "zweite klasse" to "2", "seconde classe" to "2", "seconda classe" to "2",
            "segunda clase" to "2", "tweede klas" to "2", "andra klass" to "2")
        return words.firstOrNull { containsWord(it.first, f) }?.second
    }

    /** Ranked: totals first, then other price labels. */
    private val priceLabels = listOf("gesamtpreis", "gesamtbetrag", "gesamt", "summe", "total", "zu zahlen", "bezahlt", "paid",
        "endpreis", "betrag", "amount", "montant", "importo", "importe", "fahrpreis", "preis", "price",
        "prix", "prezzo", "precio", "preço", "prijs", "cena", "pris", "fiyat", "tutar", "ár")
    private val taxWords = listOf("mwst", "ust", "vat", "tva", "iva", "steuer", "tax", "btw", "moms", "áfa", "kdv", "dph")
    private const val PRICE = "(?:(?:€|EUR|CHF|Fr\\.|£|GBP|CZK|PLN|HUF|RON|SEK|DKK|NOK|USD|\\$|TRY|₺)\\s?(?:\\d{1,3}(?:[.\\u00A0\\u202F’']\\d{3})+|\\d{1,6})(?:[.,](?:\\d{2}|[-–]))?)|(?:(?:\\d{1,3}(?:[.\\u00A0\\u202F’']\\d{3})+|\\d{1,6})[.,](?:\\d{2}|[-–])\\s?(?:€|EUR|CHF|Fr\\.|Kč|CZK|zł|PLN|Ft|HUF|lei|RON|kr|SEK|DKK|NOK|£|TL|₺))"

    private fun price(lines: List<String>): String? {
        val ranked = ArrayList<Pair<Int, String>>(); val other = ArrayList<String>()
        for (line in lines) {
            val f = fold(line)
            val isTax = taxWords.any { containsWord(it, f) }
            if (isTax && firstMatch("\\d\\s?%", f, false) != null) continue
            val rank = priceLabels.indexOfFirst { containsWord(it, f) || f.startsWith(it) }
            if (rank >= 0 && !(isTax && rank > 7)) ranked += rank to line else if (!isTax) other += line
        }
        for (line in ranked.sortedBy { it.first }.map { it.second } + other) {
            val found = allMatches(PRICE, line).map { it.groups[0].trim() }
            val v = if (line.contains("=")) found.lastOrNull() else found.firstOrNull()
            if (v != null) return v
        }
        return null
    }

    /** Reductions printed with the passenger: BahnCard, Vorteilscard, Halbtax, railcards. */
    private fun discount(text: String): String? {
        val patterns = listOf("\\bBahnCard\\s?(?:25|50|100)(?:\\s?(?:Business|1\\.\\s?Klasse|2\\.\\s?Klasse))?", "\\bBC\\s?(?:25|50|100)\\b",
            "\\bVorteilscard(?:\\s(?:Classic|Jugend|Senior|Family|Familie|Spezial|66))?", "\\bHalbtax\\b", "\\bGA\\s+(?:1\\.|2\\.)\\s?Kl",
            "\\bCarte\\s(?:Avantage|Liberté)(?:\\s(?:Jeune|Adulte|Senior|Week-end))?",
            "\\b(?:16-25|26-30|Senior|Two Together|Family & Friends|Disabled Persons)\\sRailcard\\b", "\\bInterrail Pass\\b")
        for (p in patterns) firstMatch(p, text, true)?.let { return it[0].trim() }
        return null
    }

    /** "2 Erwachsene, 1 Kind" as printed; null when the ticket names no counts. */
    private fun travellers(lines: List<String>): String? {
        val words = "Erwachsene[rn]?|Kinder|Kind|Jugendliche[r]?|Senior(?:en|in)?|Personen|Person|Reisende|Adults?|Child(?:ren)?|Youths?|Seniors?|Passengers?|Adultes?|Enfants?|Adulti|Adulto|Bambin[io]|Ragazz[io]|Adultos?|Niños?|Niño|Volwassenen?|Kinderen|Dorośl[iy]|Dzieci|Dziecko|Dospěl[ýí]|Vuxna?|Felnőtt|Yetişkin"
        val out = ArrayList<String>()
        for (line in lines) for (m in allMatches("(?<![\\d.,:])([1-9])\\s*(?:x\\s*)?($words)(?![\\p{L}])", line)) {
            val before = fold(line.substring(0, m.start)).takeLast(10)
            val after = fold(line.substring(m.end)).take(14)
            if (listOf("bis zu", "max", "up to", "jusqu", "fino a", "hasta").any { before.contains(it) } ||
                listOf("bis ", "unter", "under", "frei", "free", "gratis", "jusqu", "fino").any { after.contains(it) }) continue
            val item = "${m.groups[1]} ${m.groups[2]}"
            if (out.none { fold(it) == fold(item) }) out += item
        }
        return if (out.isEmpty()) null else out.take(4).joinToString(", ")
    }

    // Validity

    private val excludedDateWords = listOf("geb", "geburt", "birth", "né le", "née le", "nato", "kauf", "gekauft", "purchase",
        "bought", "ausgestellt", "issued", "erstellt", "created", "buchung am", "gebucht",
        "booked", "bestellt", "ordered", "druck", "printed", "rechnung", "invoice", "acquist", "achat",
        "emis le", "émis le", "fecha de compra", "data emissione", "data di emissione", "aangeschaft",
        "gekocht", "zakup", "data zakupu", "datum nákupu", "zakoupeno", "köpt", "vásárlás", "satın")
    private val untilWords = listOf("bis", "until", "valid to", "valid till", "expires", "expiry", "ablauf", "geltungsende",
        "jusqu", "fino al", "fino a", "ende", "tot", "t/m", "tot en met", "ważny do", "platnost do", "platí do", "hasta", "até",
        "till", "gültigkeitsende", "verfällt", "expire", "scadenza", "scade")
    private val fromWords = listOf("ab", "from", "valid from", "gültig von", "vom", "geltungsbeginn", "beginn", "à partir",
        "a partire", "dal", "start", "vanaf", "ważny od", "platnost od", "platí od", "desde", "från", "dès", "entwertet", "entwertung",
        "aktiviert", "activated", "validated", "validé", "convalidato", "gestempelt")
    private val validityWords = listOf("gültig", "gueltig", "gültigkeit", "geltungsdauer", "geltungszeitraum", "valid", "validity",
        "reisedatum", "reisetag", "travel date", "date of travel", "fahrtantritt", "hinfahrt", "fahrt am",
        "datum", "date", "data", "valable", "valido", "validità", "geltungstag", "reisezeitraum", "fahrtag",
        "date of journey", "journey date", "outbound", "date du voyage", "data viaggio", "data di viaggio",
        "fecha", "válido", "validez", "validade", "geldig", "reisdatum", "ważny", "ważność",
        "data podróży", "platnost", "platí", "datum cesty", "giltig", "resdag", "érvényes",
        "érvényesség", "geçerli", "tarih", "voyage", "viaggio", "viaje")
    /** The second date on a return ticket. */
    private val returnWords = listOf("rückfahrt", "rückreise", "return", "retour", "ritorno", "vuelta", "regreso", "terugreis",
        "powrót", "zpět", "återresa", "visszaút", "dönüş")

    data class FoundDate(val y: Int, val m: Int, val d: Int, val hour: Int? = null, val minute: Int? = null,
                         val position: Int, val length: Int = 1, val wholeMonth: Boolean = false) {
        val hasTime get() = hour != null
    }

    private fun validity(lines: List<String>, zone: ZoneId, strict: Boolean, category: TicketPass.Category?): Pair<Instant?, Instant?> {
        var from: FoundDate? = null; var until: FoundDate? = null; var day: FoundDate? = null
        var returnDay: FoundDate? = null; val loose = ArrayList<FoundDate>(); var month: FoundDate? = null
        fun key(f: FoundDate) = date(f, false, zone) ?: Instant.MIN

        for ((i, line) in lines.withIndex()) {
            val f = fold(line)
            if (excludedDateWords.any { f.contains(it) }) continue
            var dates = findDates(line)
            var context = f
            if (dates.isEmpty()) {
                if (i + 1 >= lines.size || !(validityWords.any { f.contains(it) } || untilWords.any { containsWord(it, f) } || fromWords.any { containsWord(it, f) })) continue
                val next = findDates(lines[i + 1])
                if (next.isEmpty() || excludedDateWords.any { fold(lines[i + 1]).contains(it) }) continue
                dates = next.map { it.copy(position = it.position + f.length + 1) }
                context = f + " " + fold(lines[i + 1])
            }
            if (strict) {
                val explicit = validityWords.filter { it !in listOf("datum", "date", "data") }
                if (!(explicit.any { context.contains(it) } || untilWords.any { containsWord(it, context) } || fromWords.any { containsWord(it, context) })) continue
            }
            if (dates.size == 1 && dates[0].wholeMonth) { if (month == null) month = dates[0]; continue }
            if (dates.size >= 2) {
                val sorted = dates.sortedBy(::key)
                if (from == null) from = sorted.first()
                if (until == null) until = sorted.last()
                continue
            }
            val found = dates.first()
            val before = context.take(found.position)
            when {
                returnWords.any { containsWord(it, before) } -> { if (returnDay == null) returnDay = found }
                untilWords.any { containsWord(it, before) } -> { if (until == null) until = found }
                fromWords.any { containsWord(it, before) } -> { if (from == null) from = found }
                validityWords.any { context.contains(it) } -> { if (day == null) day = found }
                else -> loose += found
            }
        }

        // "Gültig 90 Minuten ab 14.10.2025 10:15", "7 Tage gültig ab 01.10.2025".
        val dayPass = category == TicketPass.Category.DAY_PASS
        val f0 = from ?: if (dayPass) day else null
        if (until == null && f0 != null) {
            val span = duration(lines, dayPass)
            val s = date(f0, false, zone)
            if (span != null && (span.second > 0 || f0.hasTime) && s != null) {
                val end = if (span.second > 0) {
                    val last = s.atZone(zone).plusDays((span.second - if (f0.hasTime) 0 else 1).toLong())
                    if (f0.hasTime) last.toInstant() else last.withHour(23).withMinute(59).withSecond(59).toInstant()
                } else s.plusSeconds(span.first)
                return s to end
            }
        }
        if (from == null && until == null) {
            when {
                day != null -> { from = day; until = (returnDay ?: day).let { it.copy(hour = null, minute = null) } }
                returnDay != null -> { from = returnDay; until = returnDay.copy(hour = null, minute = null) }
                month != null -> { from = month; until = month }
                loose.size >= 2 -> { val s = loose.sortedBy(::key); from = s.first(); until = s.last() }
                loose.size == 1 -> { from = loose[0]; until = loose[0].copy(hour = null, minute = null) }
            }
        }
        var start = from?.let { date(it, false, zone) }
        var end = until?.let { date(it, true, zone) }
        if (!strict && start != null && end != null && start > end) { val t = start; start = end; end = t }
        return start to end
    }

    /** A validity length printed with the validity: (seconds, whole days). */
    private fun duration(lines: List<String>, dayPass: Boolean): Pair<Long, Int>? {
        val units = listOf("min(?:uten|utes|uti|utos|ut|\\.)?|perc|dakika" to 60L,
            "std\\.?|stunden|stunde|h|hours?|heures?|ore|horas|uur|godzin[y]?|hodin[y]?|timmar|óra|saat" to 3600L,
            "tage|tagen|days?|jours?|giorni|días|dias|dagen|dni|dní|dagar|nap|gün" to 86400L)
        val valid = listOf("gültig", "gilt", "valid", "geldig", "valable", "valido", "válido", "ważny", "platn", "giltig", "érvényes", "geçerli")
        for (line in lines) {
            val f = fold(line)
            if (!(valid.any { f.contains(it) } || dayPass)) continue
            for ((unit, seconds) in units) {
                val m = firstMatch("(?<![\\d.,:])(\\d{1,3})\\s*-?\\s*(?:$unit)(?![\\p{L}\\d])", f, true) ?: continue
                val n = m[1].toIntOrNull()?.takeIf { it > 0 } ?: continue
                if (seconds >= 86400) return if (n <= 400) n * seconds to n else null
                return if (n * seconds <= 72 * 3600) n * seconds to 0 else null
            }
        }
        return null
    }

    /** Explicit field assignment: a printed range supplies its first date for "from" and its last for "until". */
    fun date(text: String, end: Boolean, zone: ZoneId = ZoneId.systemDefault()): Instant? {
        val found = findDates(clean(text)).sortedBy { it.position }
        val v = (if (end) found.lastOrNull() else found.firstOrNull()) ?: return null
        return date(v, end, zone)
    }

    /** Dates without a time start at midnight and end at 23:59:59; whole months cover the month. */
    private fun date(f: FoundDate, end: Boolean, zone: ZoneId): Instant? = runCatching {
        if (f.wholeMonth) {
            val ym = YearMonth.of(f.y, f.m)
            return@runCatching if (end) ym.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant().minusSeconds(1) else ym.atDay(1).atStartOfDay(zone).toInstant()
        }
        val t = if (f.hasTime) LocalDateTime.of(f.y, f.m, f.d, f.hour!!, f.minute!!) else if (end) LocalDateTime.of(f.y, f.m, f.d, 23, 59, 59) else LocalDateTime.of(f.y, f.m, f.d, 0, 0)
        t.atZone(zone).toInstant()
    }.getOrNull()

    private val monthNames = listOf(
        listOf("januar", "jänner", "january", "janvier", "gennaio"), listOf("februar", "feber", "february", "février", "fevrier", "febbraio"),
        listOf("märz", "march", "mars", "marzo", "mrz"), listOf("april", "avril", "aprile"), listOf("mai", "may", "maggio"),
        listOf("juni", "june", "juin", "giugno"), listOf("juli", "july", "juillet", "luglio"), listOf("august", "août", "aout", "agosto"),
        listOf("september", "septembre", "settembre", "sept"), listOf("oktober", "october", "octobre", "ottobre"),
        listOf("november", "novembre"), listOf("dezember", "december", "décembre", "decembre", "dicembre"))
    private val moreMonthNames = listOf(
        listOf("enero", "janeiro", "januari", "stycznia", "styczeń", "ledna", "leden", "január", "ocak", "ianuarie"),
        listOf("febrero", "fevereiro", "februari", "lutego", "luty", "února", "únor", "február", "şubat", "februarie"),
        listOf("marzo", "março", "maart", "marca", "marzec", "března", "březen", "mars", "március", "mart", "martie"),
        listOf("abril", "april", "kwietnia", "kwiecień", "dubna", "duben", "április", "nisan", "aprilie"),
        listOf("mayo", "maio", "mei", "maja", "maj", "května", "květen", "május", "mayıs"),
        listOf("junio", "junho", "juni", "czerwca", "czerwiec", "června", "červen", "június", "haziran", "iunie"),
        listOf("julio", "julho", "juli", "lipca", "lipiec", "července", "červenec", "július", "temmuz", "iulie"),
        listOf("agosto", "augustus", "sierpnia", "sierpień", "srpna", "srpen", "augusti", "augusztus", "ağustos"),
        listOf("septiembre", "setiembre", "setembro", "września", "wrzesień", "září", "szeptember", "eylül", "septembrie"),
        listOf("octubre", "outubro", "października", "październik", "října", "říjen", "október", "ekim", "octombrie"),
        listOf("noviembre", "novembro", "listopada", "listopad", "listopadu", "kasım", "noiembrie"),
        listOf("diciembre", "dezembro", "grudnia", "grudzień", "prosince", "prosinec", "aralık", "decembrie"))

    /** A month name or its abbreviation of three or four letters ("Okt", "Sept"). */
    private fun month(word: String): Int? {
        val w = word.lowercase().trim('.')
        if (w.length < 3) return null
        for ((i, names) in monthNames.withIndex()) {
            if (w in names) return i + 1
            if (w.length <= 4 && names.any { it.startsWith(w) }) return i + 1
        }
        for ((i, names) in moreMonthNames.withIndex()) if (w in names) return i + 1
        return null
    }

    fun findDates(line: String): List<FoundDate> {
        val out = ArrayList<FoundDate>()
        fun year(s: String) = s.toIntOrNull()?.let { if (s.length == 2) 2000 + it else it }
        fun time(after: Int): Pair<Int, Int>? {
            val m = firstMatch("^\\s*(?:,|um|at|ab|à|alle|-|om|o|kl\\.?|saat)?\\s*(\\d{1,2})[:h](\\d{2})", line.substring(after), true) ?: return null
            val h = m[1].toInt(); val mi = m[2].toInt()
            return if (h < 24 && mi < 60) h to mi else null
        }
        fun add(y: Int?, m: Int?, d: Int?, start: Int, end: Int, wholeMonth: Boolean = false) {
            if (y == null || m == null || m !in 1..12 || y !in 2000..2100) return
            if (!wholeMonth && (d == null || d !in 1..31)) return
            if (out.any { start < it.position + it.length && it.position < end }) return
            val t = if (wholeMonth) null else time(end)
            out += FoundDate(y, m, if (wholeMonth) 1 else d!!, t?.first, t?.second, start, end - start, wholeMonth)
        }
        for (m in allMatches("(?<![\\d.:])(\\d{1,2})\\.\\s?(\\d{1,2})\\.?\\s?[-–—]\\s?(\\d{1,2})\\.\\s?(\\d{1,2})\\.\\s?(\\d{4}|\\d{2})(?!\\d)", line)) {
            val y = year(m.groups[5]) ?: continue; val m1 = m.groups[2].toIntOrNull() ?: continue; val m2 = m.groups[4].toIntOrNull() ?: continue
            add(if (m1 > m2) y - 1 else y, m1, m.groups[1].toIntOrNull(), m.starts[1], m.ends[2])
            add(y, m2, m.groups[3].toIntOrNull(), m.starts[3], m.ends[5])
        }
        for (m in allMatches("(?<![\\d.:])(\\d{1,2})\\.?\\s?[-–—]\\s?(\\d{1,2})\\.\\s?(\\d{1,2})\\.\\s?(\\d{4}|\\d{2})(?!\\d)", line)) {
            val y = year(m.groups[4]); val mo = m.groups[3].toIntOrNull()
            add(y, mo, m.groups[1].toIntOrNull(), m.starts[1], m.ends[1])
            add(y, mo, m.groups[2].toIntOrNull(), m.starts[2], m.ends[4])
        }
        for (m in allMatches("(?<!\\d)(\\d{1,2})\\.\\s?(\\d{1,2})\\.\\s?(\\d{4}|\\d{2})(?!\\d)", line)) add(year(m.groups[3]), m.groups[2].toIntOrNull(), m.groups[1].toIntOrNull(), m.start, m.end)
        for (m in allMatches("(?<!\\d)(\\d{4})-(\\d{2})-(\\d{2})(?!\\d)", line)) add(m.groups[1].toIntOrNull(), m.groups[2].toIntOrNull(), m.groups[3].toIntOrNull(), m.start, m.end)
        for (m in allMatches("(?<![\\d/])(\\d{1,2})/(\\d{1,2})/(\\d{4}|\\d{2})(?![\\d/])", line)) add(year(m.groups[3]), m.groups[2].toIntOrNull(), m.groups[1].toIntOrNull(), m.start, m.end)
        for (m in allMatches("(?<![\\d-])(\\d{1,2})-(\\d{1,2})-(\\d{4})(?!\\d)", line)) add(m.groups[3].toIntOrNull(), m.groups[2].toIntOrNull(), m.groups[1].toIntOrNull(), m.start, m.end)
        for (m in allMatches("(?<!\\d)(\\d{1,2})(?:st|nd|rd|th)?\\.?\\s+(?:of\\s+|de\\s+)?(\\p{L}{3,})\\.?,?\\s+(?:de\\s+)?(\\d{4})(?!\\d)", line))
            month(m.groups[2])?.let { add(m.groups[3].toIntOrNull(), it, m.groups[1].toIntOrNull(), m.start, m.end) }
        for (m in allMatches("(?<![\\p{L}\\d.])(\\p{L}{3,})\\.?\\s+(\\d{1,2})(?:st|nd|rd|th)?,?\\s+(\\d{4})(?!\\d)", line))
            month(m.groups[1])?.let { add(m.groups[3].toIntOrNull(), it, m.groups[2].toIntOrNull(), m.start, m.end) }
        for (m in allMatches("(?<![\\p{L}\\d.])(\\p{L}{3,})\\.?\\s+(\\d{4})(?!\\d)", line))
            month(m.groups[1])?.let { add(m.groups[2].toIntOrNull(), it, null, m.start, m.end, wholeMonth = true) }
        return out.sortedBy { it.position }
    }

    // Text helpers

    fun clean(line: String) = line.replace(Regex("\\s+"), " ").trim()

    /** Lowercased for label matching. Accents stay. */
    fun fold(s: String) = s.lowercase()

    /** Case-insensitive match; short all-caps patterns ("SBB", "MVV") need whole words in capitals. */
    fun matches(pattern: String, text: String): Boolean {
        if (pattern.length <= 4 && pattern == pattern.uppercase())
            return firstMatch("(?<![\\p{L}\\p{N}])" + Pattern.quote(pattern) + "(?![\\p{L}\\p{N}])", text, false) != null
        return text.contains(pattern, ignoreCase = true)
    }

    fun containsWord(word: String, text: String) =
        firstMatch("(?<![\\p{L}\\p{N}])" + Pattern.quote(word) + "(?![\\p{L}\\p{N}])", text, true) != null

    private val cache = ConcurrentHashMap<String, Pattern>()
    private fun regex(p: String, ci: Boolean): Pattern = cache.getOrPut((if (ci) "i:" else "c:") + p) {
        Pattern.compile(p, if (ci) Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE else 0)
    }

    class Match(val groups: List<String>, val starts: List<Int>, val ends: List<Int>) {
        val start get() = starts[0]; val end get() = ends[0]
    }

    fun allMatches(pattern: String, text: String): List<Match> {
        val m = regex(pattern, true).matcher(text)
        val out = ArrayList<Match>()
        while (m.find()) out += Match((0..m.groupCount()).map { m.group(it) ?: "" }, (0..m.groupCount()).map { m.start(it) }, (0..m.groupCount()).map { m.end(it) })
        return out
    }

    fun firstMatch(pattern: String, text: String, caseInsensitive: Boolean): List<String>? {
        val m = regex(pattern, caseInsensitive).matcher(text)
        return if (m.find()) (0..m.groupCount()).map { m.group(it) ?: "" } else null
    }
}
