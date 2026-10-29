package io.github.elija7580.publit.data

import io.github.elija7580.publit.core.Fmt
import io.github.elija7580.publit.core.L10n
import io.github.elija7580.publit.core.LatLon
import java.text.Normalizer

/** Departures of one station, or of one street stop (one side of the road). */
data class StopGroup(
    val id: String,
    val name: String,
    val coordinate: LatLon,
    val stopIds: List<String>,
    val departures: List<StopTime>,
    val distance: Double?,
    /** A single street stop: only its own departures, at its own position. */
    val isPole: Boolean = false,
    /** Main destinations from this street stop, to tell it from the one across the road. */
    val directions: List<String> = emptyList(),
)

object StopGrouping {
    /** Same-name stops closer than this are one station. */
    const val MERGE_RADIUS = 300.0
    /** Same-name street stops closer than this are the same stop in two feeds. */
    const val DUPLICATE_RADIUS = 8.0

    private val marks = Regex("\\p{Mn}+")

    fun normalizedName(name: String): String {
        val s = name.replace("ı", "i").replace("İ", "I")
        return Normalizer.normalize(s, Normalizer.Form.NFD).replace(marks, "").lowercase(java.util.Locale.ROOT).trim()
    }

    fun isStreetMode(mode: String) = mode in setOf("BUS", "COACH", "TRAM", "TROLLEYBUS", "CABLE_CAR")

    private class Building(
        val id: String, val name: String, val coordinate: LatLon,
        val stopIds: MutableList<String>, val departures: MutableList<StopTime>,
        val distance: Double?, val isPole: Boolean,
    )

    fun group(stopTimes: List<StopTime>, here: LatLon?, separatePoles: Boolean = true): List<StopGroup> {
        // A stop is a street stop when everything leaving from it is a bus or tram.
        val railStops = HashSet<String>()
        if (separatePoles) for (st in stopTimes) if (!isStreetMode(st.mode)) railStops.add(st.place.stopId ?: st.place.stationId)
        val groups = ArrayList<Building>()
        val candidates = HashMap<String, MutableList<Int>>()
        val names = HashMap<String, String>()
        for (st in stopTimes.sortedBy { it.departureTime }) {
            val name = names.getOrPut(st.place.name) { normalizedName(st.place.name) }
            val c = st.place.coordinate
            val sid = st.place.stopId ?: st.place.stationId
            val pole = separatePoles && sid !in railStops
            val key = if (pole) "pole|$name" else name
            val radius = if (pole) DUPLICATE_RADIUS else MERGE_RADIUS
            val i = candidates[key]?.firstOrNull {
                (pole && sid in groups[it].stopIds) || groups[it].coordinate.distanceTo(c) <= radius
            }
            if (i != null) {
                groups[i].departures.add(st)
                st.place.stopId?.let { if (it !in groups[i].stopIds) groups[i].stopIds.add(it) }
            } else {
                candidates.getOrPut(key) { mutableListOf() }.add(groups.size)
                groups.add(Building(if (pole) sid else st.place.stationId, st.place.name, c,
                    st.place.stopId?.let { mutableListOf(it) } ?: mutableListOf(), mutableListOf(st),
                    here?.distanceTo(c), pole))
            }
        }
        // IDs must be unique for the list (two stations can share a parent ID).
        val seen = HashSet<String>()
        val out = groups.mapIndexed { i, g ->
            val id = if (seen.add(g.id)) g.id else "${g.id}#$i".also { seen.add(it) }
            StopGroup(id, g.name, g.coordinate, g.stopIds, g.departures, g.distance, g.isPole,
                if (g.isPole) directions(g.departures) else emptyList())
        }
        return out.sortedBy { it.distance ?: Double.POSITIVE_INFINITY }
    }

    /** The most frequent destinations, at most `limit`. */
    fun directions(departures: List<StopTime>, limit: Int = 2): List<String> {
        val counts = LinkedHashMap<String, Int>()
        val first = HashMap<String, Int>()
        departures.forEachIndexed { i, st ->
            val d = st.directionName ?: st.headsign
            if (d.isEmpty()) return@forEachIndexed
            counts[d] = (counts[d] ?: 0) + 1
            if (d !in first) first[d] = i
        }
        return counts.keys.sortedWith(compareBy<String>({ -(counts[it] ?: 0) }, { first[it] ?: 0 })).take(limit)
    }
}

/** How far Publit looks for stops, and what it says when the nearest service is far. */
object Reach {
    /** Radii (m) for Nearby, tried in order until one has departures. */
    val nearbyRadii = listOf(500, 1500, 5000)
    const val FAR_DISTANCE = 1500.0
    const val WALK_HINT_DISTANCE = 400.0

    /** Streets add about 30 %, people walk about 80 m a minute. */
    fun walkMinutes(meters: Double): Int {
        if (!meters.isFinite() || meters <= 0) return 1
        return maxOf(1, Math.ceil(meters * 1.3 / 80).toInt())
    }

    fun farNote(meters: Double): String =
        L10n.f("The nearest departures are %@ away, about %lld min on foot.", Fmt.distance(meters), walkMinutes(meters))
}
