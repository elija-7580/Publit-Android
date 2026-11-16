package io.github.elija7580.publit.data

import io.github.elija7580.publit.core.L10n
import java.util.Locale

/**
 * Where a stop is, read from its Transitous stop ID (StopArea on iOS): the national stop ID after the feed name
 * ("de-DELFI_at:45:50002" → Austria, Salzburg), else the feed's country ("de-VBN_000000100063" → Germany).
 * [state]: Austrian state by the stop ID's second part, 41 Burgenland … 49 Vienna.
 */
data class StopArea(val country: String, val state: Int? = null) {
    companion object {
        fun of(stopId: String?): StopArea? {
            if (stopId.isNullOrEmpty()) return null
            for (part in stopId.split('_').drop(1)) {
                val f = part.split(':')
                if (f.size < 2 || f[0].length != 2 || !f[0].all { it in 'a'..'z' }) continue
                val state = if (f[0] == "at") f[1].toIntOrNull()?.takeIf { it in 41..49 } else null
                return StopArea(f[0], state)
            }
            val feed = stopId.substringBefore('_')
            val cc = feed.take(2)
            if (feed.length <= 3 || feed[2] != '-' || !cc.all { it in 'a'..'z' }) return null
            return StopArea(cc)
        }
    }
}

/**
 * Tickets that cover a whole country or region (OwnTicket on iOS, same raw values). With one chosen in Route options,
 * routes only use rides it covers. Checked per ride by mode, train type and where it starts and ends; tourist and
 * mountain railways, supplements and single exceptions are not modelled.
 */
enum class OwnTicket(val raw: String, val country: Country, val title: String, private val detailKey: String, val states: Set<Int>? = null) {
    DEUTSCHLANDTICKET("deutschlandticket", Country.DE, "Deutschlandticket", "Local and regional transport in Germany"),
    BAHNCARD100("bahncard100", Country.DE, "BahnCard 100", "DB trains and local transport in Germany"),
    KLIMATICKET("klimaticket", Country.AT, "KlimaTicket Ö", "All public transport in Austria"),
    VOR_METROPOLREGION("vorMetropolregion", Country.AT, "KlimaTicket MetropolRegion", "Vienna, Lower Austria and Burgenland", setOf(41, 43, 49)),
    VOR_REGION("vorRegion", Country.AT, "KlimaTicket Region", "Lower Austria and Burgenland, without Vienna", setOf(41, 43)),
    WIEN_JAHRESKARTE("wienJahreskarte", Country.AT, "Jahreskarte Wien", "Vienna", setOf(49)),
    KLIMATICKET_OOE("klimaticketOOE", Country.AT, "KlimaTicket OÖ", "Upper Austria", setOf(44)),
    KLIMATICKET_SALZBURG("klimaticketSalzburg", Country.AT, "KlimaTicket Salzburg", "Salzburg", setOf(45)),
    KLIMATICKET_TIROL("klimaticketTirol", Country.AT, "KlimaTicket Tirol", "Tyrol", setOf(47)),
    KLIMATICKET_STEIERMARK("klimaticketSteiermark", Country.AT, "KlimaTicket Steiermark", "Styria", setOf(46)),
    KLIMATICKET_KAERNTEN("klimaticketKaernten", Country.AT, "KlimaTicket Kärnten", "Carinthia", setOf(42)),
    KLIMATICKET_VORARLBERG("klimaticketVorarlberg", Country.AT, "KlimaTicket VMOBIL", "Vorarlberg", setOf(48)),
    GENERALABONNEMENT("generalabonnement", Country.CH, "GA", "All public transport in Switzerland");

    enum class Country(val code: String) {
        DE("de"), AT("at"), CH("ch");
        /** "Germany", "Österreich" … in the app language. */
        val title: String get() = Locale("", code.uppercase()).getDisplayCountry(L10n.locale).ifEmpty { code.uppercase() }
    }

    /** What it covers, in a few words. */
    val detail: String get() = L10n.t(detailKey)

    /** MOTIS modes the ticket never covers; left out of the search when no other chosen ticket covers them. */
    val excludedModes: Set<String> get() = if (this == DEUTSCHLANDTICKET) ALWAYS_EXCLUDED + LONG_DISTANCE_MODES else ALWAYS_EXCLUDED

    fun covers(leg: Leg): Boolean {
        if (!leg.isTransit) return true
        if (leg.mode in ALWAYS_EXCLUDED) return false
        val a = StopArea.of(leg.from.stopId)
        val b = StopArea.of(leg.to.stopId)
        return when (this) {
            DEUTSCHLANDTICKET -> {
                if (!coversByMode(leg)) false
                // Unknown stops: the mode decides. Otherwise one end in Germany (regional trains to Salzburg, Kufstein, Basel).
                else if (a == null && b == null) true
                else a?.country == "de" || b?.country == "de"
            }
            BAHNCARD100 -> DEUTSCHLANDTICKET.covers(leg) ||
                (ModeGroup.of(leg.mode) == ModeGroup.TRAIN && !isFlix(leg) && a?.country == "de" && b?.country == "de")
            GENERALABONNEMENT -> !isFlix(leg) && isSwiss(a) && isSwiss(b)
            KLIMATICKET -> !isFlix(leg) && !isAustrianExtraFare(leg) && a?.country == "at" && b?.country == "at"
            else -> {
                val s = states ?: return false
                !isFlix(leg) && !isAustrianExtraFare(leg) && a?.state?.let { it in s } == true && b?.state?.let { it in s } == true
            }
        }
    }

    companion object {
        /** No ticket here covers planes, long-distance coaches or night trains (reservation or couchette). */
        val ALWAYS_EXCLUDED = setOf("COACH", "AIRPLANE", "NIGHT_RAIL")
        val LONG_DISTANCE_MODES = setOf("HIGHSPEED_RAIL", "LONG_DISTANCE")
        /** Long-distance trains by line name, for feeds that file them as plain RAIL. */
        val LONG_DISTANCE_LINES = setOf("ICE", "IC", "EC", "ECE", "FLX", "NJ", "EN", "TGV", "RJ", "RJX", "ES", "EST", "WB")

        fun of(raw: String) = entries.firstOrNull { it.raw == raw }
        fun inCountry(c: Country) = entries.filter { it.country == c }

        fun excludedModes(tickets: Set<OwnTicket>): Set<String> =
            tickets.map { it.excludedModes }.reduceOrNull { acc, s -> acc intersect s } ?: emptySet()

        fun isLongDistance(leg: Leg): Boolean {
            if (leg.mode in LONG_DISTANCE_MODES || leg.mode == "NIGHT_RAIL") return true
            if (ModeGroup.of(leg.mode) != ModeGroup.TRAIN) return false
            if (isFlix(leg)) return true
            return listOfNotNull(leg.routeShortName, leg.displayName).any { n -> n.uppercase().takeWhile { it.isLetter() } in LONG_DISTANCE_LINES }
        }

        /** The Deutschlandticket's check by mode and train alone. */
        fun coversByMode(leg: Leg) = !leg.isTransit || (leg.mode !in ALWAYS_EXCLUDED && !isLongDistance(leg))

        private fun isFlix(leg: Leg) = leg.agencyName?.lowercase()?.contains("flix") == true
        private fun isSwiss(a: StopArea?) = a?.country == "ch" || a?.country == "li"
        /** Own fares in Austria: City Airport Train, Vienna Airport Lines. */
        private fun isAustrianExtraFare(leg: Leg): Boolean {
            val agency = leg.agencyName?.lowercase().orEmpty()
            return agency.contains("city air terminal") || agency.contains("city airport train") || leg.routeShortName.orEmpty().uppercase().startsWith("VAB")
        }

        /** Every ride covered by at least one of the tickets. No tickets: every route. */
        fun covers(it: Itinerary, tickets: Set<OwnTicket>) = tickets.isEmpty() || it.legs.all { leg -> tickets.any { t -> t.covers(leg) } }

        /** "Deutschlandticket" or "KlimaTicket Ö, GA" in list order; "3 tickets" for more. */
        fun summary(tickets: Set<OwnTicket>): String {
            val chosen = entries.filter { it in tickets }
            return if (chosen.size > 2) L10n.f("%lld tickets", chosen.size) else chosen.joinToString(", ") { it.title }
        }

        /** The ticket a saved pass is, by its title ("D-Ticket", "KlimaTicket Ö Classic", "GA 2. Klasse"). */
        fun recognise(title: String): OwnTicket? {
            val t = title.lowercase().replace('-', ' ')
            val words = t.split(Regex("[^\\p{L}\\p{N}]+")).toSet()
            if ("deutschlandticket" in t || "deutschland ticket" in t || "d ticket" in t) return DEUTSCHLANDTICKET
            if ("bahncard 100" in t || "bc100" in words) return BAHNCARD100
            if ("metropolregion" in t) return VOR_METROPOLREGION
            if ("klimaticket region" in t) return VOR_REGION
            if ("jahreskarte" in t && ("wien" in t || "wiener linien" in t)) return WIEN_JAHRESKARTE
            if ("vmobil" in t || "klimaticket vorarlberg" in t) return KLIMATICKET_VORARLBERG
            if ("klimaticket" in t) return when {
                "oö" in t || "oberösterreich" in t -> KLIMATICKET_OOE
                "salzburg" in t -> KLIMATICKET_SALZBURG
                "tirol" in t -> KLIMATICKET_TIROL
                "steiermark" in t -> KLIMATICKET_STEIERMARK
                "kärnten" in t -> KLIMATICKET_KAERNTEN
                else -> KLIMATICKET
            }
            if ("generalabonnement" in t || "general abo" in t || "abonnement général" in t || "abbonamento generale" in t || t == "ga" || t.startsWith("ga ")) return GENERALABONNEMENT
            return null
        }
    }
}
