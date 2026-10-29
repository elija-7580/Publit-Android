package io.github.elija7580.publit.data

import io.github.elija7580.publit.core.LatLon

/**
 * A pin on the station map: the platforms of one station together, or one street stop
 * (when zoomed in, bus and tram stops on either side of the road are separate pins).
 */
data class MapStation(
    val id: String,
    val name: String,
    val coordinate: LatLon,
    val modes: List<String>,
    val importance: Double,
    /** One street stop at its own position; its departures are only those leaving from there. */
    val isPole: Boolean = false,
) {
    val mainMode: String
        get() = ORDER.firstOrNull { it in modes } ?: modes.firstOrNull() ?: "BUS"
    val isMajor: Boolean get() = modes.any { it != "BUS" && it != "COACH" } && !isPole

    companion object {
        private val ORDER = listOf("HIGHSPEED_RAIL", "LONG_DISTANCE", "NIGHT_RAIL", "REGIONAL_FAST_RAIL", "REGIONAL_RAIL", "RAIL",
            "SUBURBAN", "SUBWAY", "METRO", "TRAM", "FERRY", "AIRPLANE", "FUNICULAR", "AERIAL_LIFT", "COACH", "BUS")

        /** At most this many pins. */
        const val MAX_STATIONS = 250
        /** Street stops get their own pins below this map span (degrees latitude, about 1.5 km). */
        const val POLE_SPAN = 0.014

        /** Map label without a leading "City, " (the city is obvious on the map). */
        fun shortName(name: String): String {
            val i = name.indexOf(", ")
            if (i < 0) return name
            val city = name.substring(0, i); val rest = name.substring(i + 2)
            return if (city.length <= 24 && rest.length >= 3) rest else name
        }

        /** A stop where only buses or trams call. */
        fun isPole(s: MapStop): Boolean {
            val modes = s.modes ?: return false
            return modes.isNotEmpty() && modes.all { StopGrouping.isStreetMode(it) }
        }

        /** Order-independent; a pin sits on its most important platform, so pins do not jump. */
        fun group(stops: List<MapStop>, separatePoles: Boolean = false): List<MapStation> {
            val keys = HashMap<String, String>()
            for (s in stops) if (s.name !in keys) keys[s.name] = StopGrouping.normalizedName(s.name)
            val sorted = stops.sortedWith(Comparator { a, b ->
                val ia = a.importance ?: 0.0; val ib = b.importance ?: 0.0
                if (ia != ib) return@Comparator ib.compareTo(ia)
                val pa = isPole(a); val pb = isPole(b)
                if (pa != pb) return@Comparator if (pa) 1 else -1
                a.stopId.compareTo(b.stopId)
            })
            data class Anchor(val index: Int, val at: LatLon, val pole: Boolean)
            val out = ArrayList<MapStation>()
            val anchors = HashMap<String, MutableList<Anchor>>()
            val members = ArrayList<MutableList<MapStop>>()
            val used = HashSet<String>()
            for (s in sorted) {
                val pole = separatePoles && isPole(s)
                val k = keys[s.name]!!
                val radius = if (pole) StopGrouping.DUPLICATE_RADIUS else StopGrouping.MERGE_RADIUS
                val a = anchors[k]?.firstOrNull { it.pole == pole && it.at.distanceTo(s.coordinate) < radius }
                if (a != null) { members[a.index].add(s); continue }
                val base = if (pole) s.stopId else s.stationId
                val id = if (base in used) s.stopId else base
                used += id
                anchors.getOrPut(k) { ArrayList() }.add(Anchor(out.size, s.coordinate, pole))
                members.add(mutableListOf(s))
                out.add(MapStation(id, s.name, s.coordinate, emptyList(), s.importance ?: 0.0, pole))
            }
            return out.mapIndexed { i, o -> o.copy(modes = members[i].flatMap { it.modes.orEmpty() }.toSet().sorted()) }.take(MAX_STATIONS)
        }
    }
}
