package io.github.elija7580.publit.data

import android.content.SharedPreferences
import io.github.elija7580.publit.core.L10n
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Instant
import kotlin.math.exp
import kotlin.math.roundToInt

/** How to get to the first stop (MOTIS preTransitModes). Bus, tram and train are always part of the route. */
@Serializable
enum class FirstMile {
    @SerialName("walk") WALK, @SerialName("bike") BIKE, @SerialName("car") CAR;
    /** Added to WALK; null = walking only. */
    val apiMode: String? get() = when (this) { WALK -> null; BIKE -> "BIKE"; CAR -> "CAR_PARKING" }
    val summary: String get() = L10n.t(when (this) { WALK -> "Walk"; BIKE -> "Bike & Ride"; CAR -> "Park & Ride" })
}

/** How the planner orders its routes. */
@Serializable
enum class RouteSort {
    @SerialName("recommended") RECOMMENDED, @SerialName("departure") DEPARTURE, @SerialName("fastest") FASTEST,
    @SerialName("fewestChanges") FEWEST_CHANGES, @SerialName("leastWalking") LEAST_WALKING;

    val title: String get() = L10n.t(when (this) {
        RECOMMENDED -> "Recommended"; DEPARTURE -> "Departure"; FASTEST -> "Fastest"; FEWEST_CHANGES -> "Fewest changes"; LEAST_WALKING -> "Least walking"
    })
    /** "Recommended" falls back to departure while smart routing is off. */
    val effective: RouteSort get() = if (this == RECOMMENDED && !SmartRouting.isEnabled) DEPARTURE else this

    /** Stable: ties keep the planner's order. */
    fun apply(list: List<Itinerary>, arriveBy: Boolean = false): List<Itinerary> = when (effective) {
        DEPARTURE -> list
        RECOMMENDED -> SmartRouting.ranked(list, arriveBy)
        FASTEST -> list.withIndex().sortedWith(compareBy({ it.value.duration }, { it.value.transfers }, { it.index })).map { it.value }
        FEWEST_CHANGES -> list.withIndex().sortedWith(compareBy({ it.value.transfers }, { it.value.duration }, { it.index })).map { it.value }
        LEAST_WALKING -> list.withIndex().sortedWith(compareBy({ walkSeconds(it.value) }, { it.value.duration }, { it.index })).map { it.value }
    }

    companion object {
        val offered: List<RouteSort> get() = if (SmartRouting.isEnabled) entries else entries.filter { it != RECOMMENDED }
        /** Seconds on foot. */
        fun walkSeconds(it: Itinerary) = it.legs.filter { l -> l.mode == "WALK" }.sumOf { l -> l.duration }
    }
}

/** Route options (RouteOptions on iOS), stored under the same key. */
@Serializable
data class RouteOptions(
    val modes: List<String> = emptyList(),
    /** null = any number of changes. */
    val maxTransfers: Int? = null,
    val maxWalkMinutes: Int = DEFAULT_WALK,
    val wheelchair: Boolean = false,
    val compareStreetModes: Boolean = true,
    val sharing: Boolean = false,
    val firstMile: FirstMile = FirstMile.WALK,
    @SerialName("order") val sort: RouteSort = RouteSort.RECOMMENDED,
) {
    val filter: ModeFilter get() = ModeFilter(modes.mapNotNull { ModeGroup.fromRaw(it) }.toSet())
    val isDefault get() = !filter.isActive && maxTransfers == null && maxWalkMinutes == DEFAULT_WALK && !wheelchair && !sharing && firstMile == FirstMile.WALK
    val preTransitModes: List<String> get() = listOfNotNull("WALK", firstMile.apiMode, if (sharing) "RENTAL" else null)
    val postTransitModes: List<String> get() = if (sharing) listOf("WALK", "RENTAL") else listOf("WALK")
    val comparisonModes: List<String> get() = if (compareStreetModes) listOf("BIKE", "CAR") else emptyList()
    /** Park & Ride and sharing take the server much longer. */
    val isSlowSearch get() = firstMile == FirstMile.CAR || sharing

    /** One line for the planner, e.g. "Train, S-Bahn · Up to 1 change · Walk up to 10 min". */
    val summary: String?
        get() {
            val parts = ArrayList<String>()
            filter.summary?.let { parts += it }
            maxTransfers?.let { parts += if (it == 0) L10n.t("Direct only") else L10n.f("Changes: up to %lld", it) }
            if (maxWalkMinutes != DEFAULT_WALK) parts += L10n.f("Walk up to %lld min", maxWalkMinutes)
            if (wheelchair) parts += L10n.t("Step-free")
            if (firstMile != FirstMile.WALK) parts += firstMile.summary
            if (sharing) parts += L10n.t("With sharing")
            return parts.joinToString(" · ").ifEmpty { null }
        }

    companion object {
        const val DEFAULT_WALK = 30
        val walkChoices = listOf(5, 10, 15, 20, 30)
        val transferChoices = listOf(null, 0, 1, 2)
        fun transferTitle(n: Int?) = when (n) { null -> L10n.t("Any"); 0 -> L10n.t("Direct only"); else -> L10n.f("Up to %lld", n) }
        private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
        fun load(p: SharedPreferences) = p.getString("routeOptions", null)?.let { runCatching { json.decodeFromString(serializer(), it) }.getOrNull() } ?: RouteOptions()
        fun save(p: SharedPreferences, o: RouteOptions) = p.edit().putString("routeOptions", json.encodeToString(serializer(), o)).apply()
    }
}

/** Walking, cycling or driving the whole way, for comparison with public transport. */
data class StreetComparison(val mode: String, val itinerary: Itinerary) {
    val duration get() = itinerary.duration
    val distance: Double? get() = itinerary.legs.mapNotNull { it.distance }.sum().takeIf { it > 0 }

    /** Worth showing next to public transport: nobody walks three hours to save ten minutes. */
    fun isRealistic(bestTransit: Int?): Boolean {
        val minutes = duration / 60
        val close = bestTransit?.let { duration <= it * 5 / 4 } ?: true
        return when (mode) { "WALK" -> minutes <= 45 || (close && minutes <= 120); "BIKE" -> minutes <= 90 || (close && minutes <= 240); else -> true }
    }

    companion object {
        val order = listOf("WALK", "BIKE", "CAR")
        fun from(direct: List<Itinerary>): List<StreetComparison> {
            val best = HashMap<String, Itinerary>()
            for (it in direct) {
                val modes = it.legs.map { l -> l.mode }.toSet()
                val m = modes.singleOrNull()?.takeIf { it in order } ?: continue
                if ((best[m]?.duration ?: Int.MAX_VALUE) > it.duration) best[m] = it
            }
            return order.mapNotNull { m -> best[m]?.let { StreetComparison(m, it) } }
        }
    }
}

/**
 * Smart routing (SmartRouting on iOS): routes ranked by how long they feel, changes rated by how
 * likely they work, a backup for each change. Weights from transport research: waiting feels about
 * 1.7× and walking 1.65× as long as riding; a change costs about 15 minutes of riding. On the device only.
 */
object SmartRouting {
    const val ENABLED = "smartRouting"; const val TRANSFER_MINUTES = "smartTransferMinutes"; const val WALK_WEIGHT = "smartWalkWeight"
    const val EXTRA_TRANSFER = "smartExtraTransfer"; const val FLAT_WALKS = "smartFlatWalks"; const val LEARN = "smartLearn"
    const val DEFAULT_TRANSFER_MINUTES = 15.0
    val transferMinutesRange = 3.0..30.0
    const val DEFAULT_WALK_WEIGHT = 1.65
    val walkWeightRange = 1.1..2.5
    val extraTransferChoices = listOf(0, 2, 5, 10)

    @Volatile var prefs: SharedPreferences? = null
    val isEnabled get() = prefs?.getBoolean(ENABLED, true) ?: true
    val learns get() = prefs?.getBoolean(LEARN, true) ?: true

    data class Weights(val transferMinutes: Double = DEFAULT_TRANSFER_MINUTES, val wait: Double = 1.7, val walk: Double = DEFAULT_WALK_WEIGHT,
                       val lateness: Double = 0.5, val missedChangeMinutes: Double = 20.0)

    val weights: Weights get() {
        val p = prefs ?: return Weights()
        return Weights(
            transferMinutes = if (p.contains(TRANSFER_MINUTES)) p.getFloat(TRANSFER_MINUTES, 15f).toDouble().coerceIn(transferMinutesRange) else DEFAULT_TRANSFER_MINUTES,
            walk = if (p.contains(WALK_WEIGHT)) p.getFloat(WALK_WEIGHT, 1.65f).toDouble().coerceIn(walkWeightRange) else DEFAULT_WALK_WEIGHT)
    }

    /** How long a route feels, in minutes. */
    fun feltMinutes(it: Itinerary, w: Weights = weights): Double {
        var ride = 0.0; var walk = 0.0; var other = 0.0; var wait = 0.0
        it.legs.forEachIndexed { i, leg ->
            val m = leg.duration / 60.0
            if (leg.isTransit) ride += m else if (leg.mode == "WALK") walk += m else other += m
            if (i > 0) { val gap = (leg.startTime.epochSecond - it.legs[i - 1].endTime.epochSecond) / 60.0; if (gap > 0) wait += gap }
        }
        val risk = (1 - reliability(it)) * w.missedChangeMinutes
        return ride + other + w.walk * walk + w.wait * wait + w.transferMinutes * transitChanges(it) + risk
    }

    fun transitChanges(it: Itinerary) = maxOf(0, it.transitLegs.size - 1)

    fun costs(list: List<Itinerary>, arriveBy: Boolean, w: Weights = weights): List<Double> {
        if (list.isEmpty()) return emptyList()
        val earliestEnd = list.minOf { it.endTime }; val latestStart = list.maxOf { it.startTime }
        return list.map { it ->
            val shift = if (arriveBy) latestStart.epochSecond - it.startTime.epochSecond else it.endTime.epochSecond - earliestEnd.epochSecond
            feltMinutes(it, w) + w.lateness * maxOf(0L, shift) / 60.0 + if (it.isCancelled) 10_000.0 else 0.0
        }
    }

    fun ranked(list: List<Itinerary>, arriveBy: Boolean = false, w: Weights = weights): List<Itinerary> {
        val c = costs(list, arriveBy, w)
        return list.indices.sortedWith(compareBy({ c[it] }, { it })).map { list[it] }
    }

    fun recommended(list: List<Itinerary>, arriveBy: Boolean = false, w: Weights = weights): Itinerary? {
        if (list.count { !it.isCancelled } <= 1) return null
        return ranked(list, arriveBy, w).firstOrNull { !it.isCancelled }
    }

    /** Drops routes that another one beats on everything. Walking the whole way is never dropped. */
    fun pruned(list: List<Itinerary>): List<Itinerary> {
        val walks = list.map { RouteSort.walkSeconds(it).toDouble() }
        return list.filterIndexed { i, a ->
            if (a.isDirect) return@filterIndexed true
            list.withIndex().none { (j, b) ->
                if (j == i || b.isDirect || b.isCancelled) return@none false
                val noWorse = b.startTime >= a.startTime && b.endTime <= a.endTime && transitChanges(b) <= transitChanges(a) && walks[j] <= walks[i] + 60
                val better = b.startTime > a.startTime || b.endTime < a.endTime || transitChanges(b) < transitChanges(a)
                noWorse && better
            }
        }
    }

    enum class Badge { RECOMMENDED, FASTEST, FEWEST_CHANGES, LEAST_WALKING;
        val title: String get() = L10n.t(when (this) { RECOMMENDED -> "Recommended"; FASTEST -> "Fastest"; FEWEST_CHANGES -> "Fewest changes"; LEAST_WALKING -> "Least walking" })
    }

    /** Badges by routeKey. A badge goes to one route only, and only where the routes differ. */
    fun badges(list: List<Itinerary>, arriveBy: Boolean = false, w: Weights = weights): Map<String, List<Badge>> {
        val live = list.filter { !it.isCancelled && !it.isDirect }
        if (live.size <= 1) return emptyMap()
        val out = HashMap<String, MutableList<Badge>>()
        fun give(b: Badge, it: Itinerary?) { it?.let { out.getOrPut(it.routeKey) { ArrayList() } += b } }
        give(Badge.RECOMMENDED, recommended(live, arriveBy, w))
        fun best(value: (Itinerary) -> Double, minGap: Double): Itinerary? {
            val v = live.map(value); val lo = v.min(); val hi = v.max()
            if (hi - lo < minGap || v.count { it == lo } != 1) return null
            return live[v.indexOf(lo)]
        }
        give(Badge.FASTEST, best({ it.duration.toDouble() }, 120.0))
        give(Badge.FEWEST_CHANGES, best({ transitChanges(it).toDouble() }, 1.0))
        give(Badge.LEAST_WALKING, best({ RouteSort.walkSeconds(it).toDouble() }, 180.0))
        return out
    }

    /** Why the suggested route beats the fastest one, in a few words. */
    fun reason(rec: Itinerary, list: List<Itinerary>): String? {
        val fastest = list.filter { !it.isCancelled && !it.isDirect }.minByOrNull { it.duration } ?: return null
        if (fastest.routeKey == rec.routeKey) return null
        val fewer = transitChanges(fastest) - transitChanges(rec)
        if (fewer > 0) return if (fewer == 1) L10n.t("One change fewer") else L10n.f("%lld changes fewer", fewer)
        if (reliability(rec) - reliability(fastest) >= 0.1) return L10n.t("Safer change")
        val saved = (RouteSort.walkSeconds(fastest) - RouteSort.walkSeconds(rec)) / 60
        return if (saved >= 3) L10n.f("%lld min less walking", saved) else null
    }

    data class Change(val stopName: String, val incoming: Leg, val outgoing: Leg, val outgoingIndex: Int, val walkSeconds: Int,
                      val spareMinutes: Double, val chance: Double) {
        enum class Level { SAFE, LIKELY, RISKY }
        val level get() = if (chance >= 0.9) Level.SAFE else if (chance >= 0.7) Level.LIKELY else Level.RISKY
        /** Whole percent, never 100 (nothing is certain) and at least 1. */
        val percent get() = minOf(99, maxOf(1, (chance * 100).roundToInt()))
    }

    fun changes(it: Itinerary): List<Change> {
        val out = ArrayList<Change>(); val legs = it.legs
        var i = 0
        while (i < legs.size) {
            if (!legs[i].isTransit) { i++; continue }
            var j = i + 1; var walk = 0
            while (j < legs.size && !legs[j].isTransit) { walk += legs[j].duration; j++ }
            if (j >= legs.size) break
            val spare = (legs[j].startTime.epochSecond - legs[i].endTime.epochSecond - walk) / 60.0
            out += Change(legs[i].to.name, legs[i], legs[j], j, walk, spare, chance(spare, walk, legs[i]))
            i = j
        }
        return out
    }

    /** How spread out arrivals of this kind of vehicle are, in minutes. */
    fun spread(mode: String) = when (mode) {
        "HIGHSPEED_RAIL", "LONG_DISTANCE", "NIGHT_RAIL", "AIRPLANE" -> 4.0
        "RAIL", "REGIONAL_RAIL", "REGIONAL_FAST_RAIL", "SUBURBAN", "COACH" -> 2.5
        "BUS", "FERRY", "FLEX", "ODM" -> 2.0
        "TRAM", "CABLE_CAR", "FUNICULAR", "AERIAL_LIFT", "AREAL_LIFT" -> 1.5
        "SUBWAY", "METRO" -> 1.0
        else -> 2.0
    }

    /** Chance the change works: a logistic curve over the minutes left. */
    fun chance(spareMinutes: Double, walkSeconds: Int, incoming: Leg): Double {
        var s = spread(incoming.mode)
        if (incoming.realTime) s *= 0.6
        val margin = spareMinutes - walkSeconds / 60.0 * 0.1
        val x = (margin + 0.5 - s / 2) / (s * 0.6)
        return (1 / (1 + exp(-x))).coerceIn(0.01, 0.995)
    }

    fun reliability(it: Itinerary) = changes(it).fold(1.0) { acc, c -> acc * c.chance }

    /** The next vehicle that can replace a leg if its change is missed (from numLegAlternatives). */
    data class Backup(val leg: Leg, val arrival: Instant)

    fun backup(leg: Leg): Backup? = leg.alternatives.orEmpty().mapNotNull { alt ->
        val ride = alt.firstOrNull { it.isTransit } ?: return@mapNotNull null
        if (ride.startTime <= leg.startTime.plusSeconds(59) || ride.cancelled == true) null else Backup(ride, alt.lastOrNull()?.endTime ?: ride.endTime)
    }.minByOrNull { it.leg.startTime }

    /** A change still ahead that has become unlikely: time to look for another way. */
    fun changeAtRisk(it: Itinerary, now: Instant): Change? =
        changes(it).firstOrNull { c -> c.outgoing.startTime > now && c.incoming.endTime > now.minusSeconds(120) && c.chance < 0.5 }

    /** What smart routing adds to the search. */
    fun queryItems(): List<Pair<String, String>> {
        if (!isEnabled) return emptyList()
        val q = mutableListOf("numLegAlternatives" to "2", "slowDirect" to "true", "fastestSlowDirectFactor" to "1.5")
        val extra = prefs?.getInt(EXTRA_TRANSFER, 0) ?: 0
        if (extra > 0) q += "additionalTransferTime" to extra.toString()
        if (prefs?.getBoolean(FLAT_WALKS, false) == true) q += "elevationCosts" to "LOW"
        return q
    }

    /** Routes last offered in the planner, to learn from the one that is saved. */
    @Volatile var offered: List<Itinerary> = emptyList()
    @Volatile var offeredArriveBy = false

    /** Picking a route other than the suggested one moves the weights a little towards it. */
    fun learn(chosen: Itinerary) {
        val p = prefs ?: return
        if (!isEnabled || !learns || offered.none { it.routeKey == chosen.routeKey }) return
        val w = learned(chosen, offered, offeredArriveBy, weights)
        p.edit().putFloat(TRANSFER_MINUTES, w.transferMinutes.toFloat()).putFloat(WALK_WEIGHT, w.walk.toFloat()).apply()
    }

    fun learned(chosen: Itinerary, offered: List<Itinerary>, arriveBy: Boolean, weights: Weights): Weights {
        val rec = recommended(offered, arriveBy, weights) ?: return weights
        if (rec.routeKey == chosen.routeKey) return weights
        var t = weights.transferMinutes; var walk = weights.walk
        val changeDiff = transitChanges(chosen) - transitChanges(rec)
        if (changeDiff < 0) t += 2 else if (changeDiff > 0) t -= 2
        val walkDiff = (RouteSort.walkSeconds(chosen) - RouteSort.walkSeconds(rec)) / 60.0
        if (walkDiff <= -2) walk += 0.1 else if (walkDiff >= 2) walk -= 0.1
        return weights.copy(transferMinutes = t.coerceIn(transferMinutesRange), walk = (Math.round(walk * 100) / 100.0).coerceIn(walkWeightRange))
    }

    fun resetLearning() { prefs?.edit()?.remove(TRANSFER_MINUTES)?.remove(WALK_WEIGHT)?.apply() }
}
