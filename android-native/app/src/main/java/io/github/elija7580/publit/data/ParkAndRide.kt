package io.github.elija7580.publit.data

import io.github.elija7580.publit.core.L10n

/**
 * Driving to the station (Park & Ride), port of `ParkAndRide` in iOS Reach.swift.
 * First up to 15 min; when the nearest station is further than 5 min, once more up to that drive + 10 min,
 * so a second station on another line competes too (two stations 10 km from home, both towards Milan).
 * The station in the next town past the local one does not (Gföhl: 8 min to Krems, not 25 to St. Pölten).
 */
object ParkAndRide {
    /** Longest drive in the first search. */
    const val MAX_DRIVE_SECONDS = 900
    /** On top of the nearest station. */
    const val EXTRA_DRIVE_SECONDS = 600
    /** Longest drive in the second search. */
    const val MAX_WIDER_DRIVE_SECONDS = 1500

    /** Seconds in the car before the first ride. */
    fun driveSeconds(it: Itinerary): Int =
        it.legs.takeWhile { l -> !l.isTransit }.filter { l -> l.mode == "CAR" }.sumOf { l -> l.duration }

    fun rideSeconds(it: Itinerary): Int = it.transitLegs.sumOf { l -> l.duration }

    /** The car only brings you to public transport: the drive is shorter than the rides. */
    fun sensible(list: List<Itinerary>): List<Itinerary> =
        list.filter { val d = driveSeconds(it); d == 0 || d < rideSeconds(it) }

    fun nearestDrive(list: List<Itinerary>): Int? = list.map(::driveSeconds).filter { it > 0 }.minOrNull()

    /** Limit for a second search, or null when the first one already covers it. */
    fun widerLimit(firstSearch: List<Itinerary>, kept: List<Itinerary>): Int? {
        val nearest = nearestDrive(kept) ?: return if (nearestDrive(firstSearch) == null) MAX_WIDER_DRIVE_SECONDS else null
        val limit = minOf(nearest + EXTRA_DRIVE_SECONDS, MAX_WIDER_DRIVE_SECONDS)
        return if (limit > MAX_DRIVE_SECONDS) limit else null
    }

    /** Routes that drive at most 10 min longer than to the nearest station. */
    fun nearStations(list: List<Itinerary>, nearest: Int?): List<Itinerary> {
        val n = nearest ?: nearestDrive(list) ?: return list
        return list.filter { driveSeconds(it) <= n + EXTRA_DRIVE_SECONDS }
    }

    /** Routes kept, the drive limit they were found with (for later pages) and the nearest drive. */
    data class Result(val response: PlanResponse, val routes: List<Itinerary>, val drive: Int, val nearest: Int?)

    suspend fun search(limit: Int, plan: suspend (Int) -> PlanResponse): Result? {
        val first = plan(limit)
        var res = first
        var kept = sensible(first.itineraries)
        var drive = limit
        var nearest = nearestDrive(kept)
        // Not when the user chose a shorter walk (the drive limit follows it): that limit is theirs.
        val wider = if (limit == MAX_DRIVE_SECONDS) widerLimit(first.itineraries, kept) else null
        if (wider != null) {
            val second = runCatching { plan(wider) }.getOrElse { e -> if (e is kotlinx.coroutines.CancellationException) throw e; null }
            if (second != null) {
                val s = sensible(second.itineraries)
                val near = nearest ?: nearestDrive(s)
                val more = nearStations(s, near)
                if (more.isNotEmpty()) { res = second; kept = more; drive = wider; nearest = near }
            }
        }
        return if (kept.isEmpty()) null else Result(res, kept, drive, nearest)
    }

    /** Above routes that start with a drive because no stop was within walking reach. */
    val fallbackNote: String get() = L10n.t("No stop within walking distance: these routes start with a drive to the station (Park & Ride).")

    /** What a search with walking first found: routes behind a walk (and how long), or Park & Ride in its place. */
    sealed class Found {
        data class Walk(val walk: Int, val response: PlanResponse?, val routes: List<Itinerary>) : Found()
        data class Car(val result: Result) : Found()
    }

    /**
     * Walking first, with longer walks (45 min, then 2 h) while nothing is found (Reach.walkSteps on iOS).
     * Before a two-hour walk: drive to the nearest station instead, when there is one. Not when the user chose
     * a shorter walk: that limit is theirs. `walk` returns the response and the routes kept from it.
     */
    suspend fun walkingFirst(firstWalk: Int, userLimit: Boolean, walk: suspend (Int) -> Pair<PlanResponse, List<Itinerary>>,
                             car: suspend () -> Result?): Found {
        var last = Found.Walk(firstWalk, null, emptyList())
        var triedCar = false
        for (w in listOf(firstWalk, maxOf(firstWalk, 2700), maxOf(firstWalk, 7200)).distinct()) {
            if (!userLimit && !triedCar && w > 2700) {
                triedCar = true
                val found = try { car() } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { null }
                if (found != null && found.routes.isNotEmpty()) return Found.Car(found)
            }
            val (res, routes) = walk(w)
            last = Found.Walk(w, res, routes)
            if (routes.isNotEmpty()) break
        }
        return last
    }
}
