package io.github.elija7580.publit.data

import io.github.elija7580.publit.BuildConfigInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/** One feature request on publit.eu/wuensche (Wish on iOS). */
data class Wish(val id: Int, val title: String, val body: String, val status: Status, val votes: Int, val mine: Boolean, val created: Long) {
    enum class Status(val key: String) { OPEN("open"), PLANNED("planned"), PROGRESS("doing"), DONE("done");
        companion object { fun of(s: String?) = entries.firstOrNull { it.key == s } ?: OPEN } }
}

/** The wish list on publit.eu: read, vote, suggest (WishesClient on iOS). No account: a random voter ID on this device. */
class WishesClient(val voter: String, private val base: String = "https://publit.eu/api/wishes") {
    enum class Sort(val key: String) { TOP("top"), NEW("new") }
    enum class Failure { TOO_SHORT, LINKS, EXISTS, SLOW_DOWN, UNAVAILABLE }
    class Error(val failure: Failure) : Exception(failure.name)

    companion object {
        const val PAGE = "https://publit.eu/wuensche/"
        const val MIN_TITLE = 6
        const val VOTER_KEY = "publit.wishes.voter"
        private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

        fun newVoter(): String { val r = java.security.SecureRandom(); return (1..24).map { ALPHABET[r.nextInt(ALPHABET.length)] }.joinToString("") }
        fun isValidVoter(v: String) = v.length in 16..64 && v.all { it.isLetterOrDigit() && it.code < 128 || it == '-' || it == '_' }
        fun storedVoter(p: android.content.SharedPreferences): String =
            p.getString(VOTER_KEY, null)?.takeIf(::isValidVoter) ?: newVoter().also { p.edit().putString(VOTER_KEY, it).apply() }
        fun language(locale: Locale = Locale.getDefault()) = if (locale.language == "de") "de" else "en"

        fun parse(json: String): List<Wish> = Json.parseToJsonElement(json).jsonObject["wishes"]!!.jsonArray.map { e ->
            val o = e.jsonObject
            Wish(o.int("id"), o.str("title"), o.str("body"), Wish.Status.of(o["status"]?.jsonPrimitive?.contentOrNull),
                o.int("votes"), o["mine"]?.jsonPrimitive?.booleanOrNull ?: false, o["created"]?.jsonPrimitive?.longOrNull ?: 0)
        }
        private fun JsonObject.int(k: String) = this[k]?.jsonPrimitive?.intOrNull ?: 0
        private fun JsonObject.str(k: String) = this[k]?.jsonPrimitive?.contentOrNull ?: ""

        fun failure(status: Int, body: String): Failure {
            val error = runCatching { Json.parseToJsonElement(body).jsonObject["error"]?.jsonPrimitive?.contentOrNull }.getOrNull()
            return when (status) { 409 -> Failure.EXISTS; 429 -> Failure.SLOW_DOWN; 422 -> if (error == "links") Failure.LINKS else Failure.TOO_SHORT; else -> Failure.UNAVAILABLE }
        }
    }

    suspend fun list(sort: Sort): List<Wish> {
        val (status, body) = send("GET", "$base?sort=${sort.key}&voter=$voter", null)
        if (status != 200) throw Error(Failure.UNAVAILABLE)
        return runCatching { parse(body) }.getOrElse { throw Error(Failure.UNAVAILABLE) }
    }

    /** Votes, or takes the vote back. Returns the new count and whether the vote is yours. */
    suspend fun toggleVote(id: Int): Pair<Int, Boolean> {
        val (status, body) = send("POST", "$base/$id/vote", JsonObject(mapOf("voter" to json(voter))).toString())
        if (status != 200) throw Error(failure(status, body))
        val o = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrElse { throw Error(Failure.UNAVAILABLE) }
        return (o["votes"]?.jsonPrimitive?.intOrNull ?: 0) to (o["mine"]?.jsonPrimitive?.booleanOrNull ?: false)
    }

    suspend fun suggest(title: String, details: String) {
        val t = title.trim()
        if (t.length < MIN_TITLE) throw Error(Failure.TOO_SHORT)
        val payload = JsonObject(mapOf("title" to json(t), "body" to json(details.trim()), "lang" to json(language()), "voter" to json(voter), "website" to json("")))
        val (status, body) = send("POST", base, payload.toString())
        if (status != 200 && status != 201) throw Error(failure(status, body))
    }

    private fun json(s: String) = kotlinx.serialization.json.JsonPrimitive(s)

    private suspend fun send(method: String, url: String, body: String?): Pair<Int, String> = withContext(Dispatchers.IO) {
        try {
            val c = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = method; connectTimeout = 15_000; readTimeout = 15_000; useCaches = false
                setRequestProperty("User-Agent", BuildConfigInfo.userAgent)
                if (body != null) { doOutput = true; setRequestProperty("Content-Type", "application/json") }
            }
            body?.let { c.outputStream.use { o -> o.write(it.toByteArray()) } }
            val code = c.responseCode
            val text = (if (code < 400) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
            code to text
        } catch (e: Exception) { throw Error(Failure.UNAVAILABLE) }
    }
}

/**
 * Supporting Publit (Support on iOS, same product IDs): monthly support first, one-time tips below.
 * Nothing is unlocked; prices come from Google Play.
 */
object Support {
    const val PREFIX = "io.github.elija7580.publit.support"
    val monthlyCents = listOf(150, 300, 500, 1000, 2000, 5000)
    val oneTimeCents = listOf(300, 500, 1000, 2000, 5000)
    const val SUGGESTED_MONTHLY_CENTS = 300

    fun monthlyId(cents: Int) = "$PREFIX.monthly.$cents"
    fun oneTimeId(cents: Int) = "$PREFIX.tip.$cents"
    val monthlyIds get() = monthlyCents.map(::monthlyId)
    val oneTimeIds get() = oneTimeCents.map(::oneTimeId)

    enum class Kind { MONTHLY, ONE_TIME }

    fun parse(id: String): Pair<Kind, Int>? {
        if (!id.startsWith("$PREFIX.")) return null
        val parts = id.removePrefix("$PREFIX.").split(".")
        val cents = parts.getOrNull(1)?.toIntOrNull() ?: return null
        if (parts.size != 2) return null
        return when {
            parts[0] == "monthly" && cents in monthlyCents -> Kind.MONTHLY to cents
            parts[0] == "tip" && cents in oneTimeCents -> Kind.ONE_TIME to cents
            else -> null
        }
    }

    fun <T> sorted(items: List<T>, id: (T) -> String, kind: Kind): List<T> =
        items.mapNotNull { item -> parse(id(item))?.takeIf { it.first == kind }?.let { item to it.second } }.sortedBy { it.second }.map { it.first }
}

/** The short note after an update (WhatsNew on iOS). Each Android release with visible changes adds an entry. */
object WhatsNew {
    const val SEEN_KEY = "whatsNewRelease"
    data class Item(val icon: String, val title: String, val text: String)
    data class Entry(val release: Int, val items: List<Item>)

    /** Newest first. `release` counts Android releases with a note; raise it for each new entry. */
    val entries = listOf(
        // 0.14.0: leave earlier and still catch the connection (iOS build 109, Publit#196).
        Entry(9, listOf(
            Item("history", "Leave earlier, still catch it", "If a delay breaks a change before you leave, Publit shows an earlier way that still catches the same train."),
        )),
        // 0.13.0: plan from the vehicle you are on (iOS build 108, Publit#192; idea: BudapestGO by BKK Budapest).
        Entry(8, listOf(
            Item("swap", "Plan from your vehicle", "Already on a tram, bus or train? Open it and tap “Plan from this vehicle”: stay on or change at a stop ahead, with live times (idea: BudapestGO)."),
        )),
        // 0.12.1: the station map opens smoothly from Nearby (iOS build 102, Publit#173).
        Entry(7, listOf(
            Item("map", "Smoother station map", "Pull the list in Nearby all the way down and let go: the station map opens."),
        )),
        // 0.12.0: build your own connection (Publit#142, #143), exits in the live trip, way back from the station map.
        Entry(6, listOf(
            Item("swap", "Go on another way", "Open a ride in a route and choose another way to go on from there; the way up to it stays."),
            Item("map", "Change where you want", "Open a ride in a route and choose another stop or any place to change; Publit plans the way on from there."),
        )),
        // 0.11.0: community wishes from iOS build 100 (Publit#6, #18, #19, #20, #21, #22, #23, #24, #28).
        Entry(5, listOf(
            Item("widget", "Home screen widgets", "Home, Work, your own shortcuts and departures nearby on the home screen, and your trip on the watch face."),
            Item("star", "Save connections and lines", "Star a connection in Plan or a line in its direction; Saved opens them, and the way back, in one tap."),
            Item("ticket", "Wallet passes", "Open a .pkpass file in Publit or add it under Tickets: its code and details are taken over as they are."),
        )),
        // 0.10.0: iOS builds 87–88 (measured change chances, change alerts; same texts as iOS, translated there).
        Entry(4, listOf(
            Item("swap", "Honest change chances", "Changes get a percentage only where Publit has measured how late the vehicles really run; elsewhere it says Estimate."),
            Item("bell", "Warned before a change breaks", "On a started trip Publit rechecks each change with live times and warns you, with alternatives, when it becomes unlikely."),
        )),
        // 0.9.0: iOS Build 82–84 caught up (same texts as iOS 83, already translated; at most three items).
        Entry(3, listOf(
            Item("bell", "Get off on time", "On a started trip Publit follows your ride and alerts you one stop before yours."),
            Item("scope", "Track your vehicle", "Follow a departure on the map and see where its position comes from."),
            Item("walk", "Easier on foot", "Start walking navigation in one tap, or pick From and To right on the map."),
        )),
        Entry(2, listOf(
            Item("ticket", "Routes for your ticket", "Choose Deutschlandticket, KlimaTicket, GA and more in Route options; routes stay within what they cover."),
            Item("map", "Regional KlimaTickets", "VOR, Vienna and each state's ticket only use rides in their region."),
        )),
        Entry(1, listOf(
            Item("swap", "Clearer changes", "Each change shows times, platforms and your buffer in one glance."),
            Item("community", "Community first", "Wishes, voting and support are now at the top of Settings."),
            Item("heart", "Support Publit", "Monthly or once – every feature stays free for everyone."),
        )),
    )
    val latest get() = entries.maxOf { it.release }

    fun items(seen: Int, current: Int = latest, limit: Int = 4): List<Item> =
        if (current <= seen) emptyList()
        else entries.filter { it.release in (seen + 1)..current }.sortedByDescending { it.release }.flatMap { it.items }.take(limit)
}
