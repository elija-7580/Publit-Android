package io.github.elija7580.publit.data

import io.github.elija7580.publit.core.L10n
import io.github.elija7580.publit.core.LatLon
import java.time.Instant

/** One stage of a trip in the step-by-step guide: walk, wait for the vehicle, ride, arrive. */
data class GuideStep(val kind: Kind, val legIndex: Int) {
    enum class Kind { WALK, WAIT, RIDE, ARRIVE }
    val id get() = kind.name + legIndex
}

/** Something worth saying (and a short vibration) during a started trip; each key comes once. */
data class GuideCue(val kind: Kind, val key: String, val text: String) { enum class Kind { STAGE, GET_OFF, PLATFORM } }

/** The guide through a started trip (TripGuide on iOS): the stage now, by the live times and the phone's position. */
object TripGuide {
    /** Walks shorter than this between two vehicles are part of the change, not a stage. */
    const val MIN_WALK = 45

    fun steps(it: Itinerary): List<GuideStep> {
        val out = ArrayList<GuideStep>()
        it.legs.forEachIndexed { i, leg ->
            if (leg.isTransit) { out += GuideStep(GuideStep.Kind.WAIT, i); out += GuideStep(GuideStep.Kind.RIDE, i) }
            else if (leg.duration >= MIN_WALK || it.legs.size == 1) out += GuideStep(GuideStep.Kind.WALK, i)
        }
        out += GuideStep(GuideStep.Kind.ARRIVE, maxOf(it.legs.size - 1, 0))
        return out
    }

    fun timeIndex(steps: List<GuideStep>, it: Itinerary, now: Instant): Int {
        steps.forEachIndexed { k, s ->
            val leg = it.legs.getOrNull(s.legIndex) ?: return@forEachIndexed
            when (s.kind) {
                GuideStep.Kind.WALK, GuideStep.Kind.RIDE -> if (now < leg.endTime) return k
                GuideStep.Kind.WAIT -> if (now < leg.startTime) return k
                GuideStep.Kind.ARRIVE -> return k
            }
        }
        return maxOf(steps.size - 1, 0)
    }

    /** The stage now. A fix moves it on when the stop is reached early, and back while the stop is still far away. */
    fun current(steps: List<GuideStep>, it: Itinerary, now: Instant, location: LatLon?): Int {
        val k = timeIndex(steps, it, now)
        val loc = location ?: return k
        val s = steps.getOrNull(k) ?: return k
        if (s.kind == GuideStep.Kind.WALK && k + 1 < steps.size) {
            val next = steps[k + 1]
            val target = if (next.kind == GuideStep.Kind.WAIT) it.legs[next.legIndex].from.coordinate else it.legs[s.legIndex].to.coordinate
            if (loc.distanceTo(target) < if (next.kind == GuideStep.Kind.WAIT) 50 else 30) return k + 1
        }
        if (s.kind == GuideStep.Kind.WAIT && k > 0 && steps[k - 1].kind == GuideStep.Kind.WALK && loc.distanceTo(it.legs[s.legIndex].from.coordinate) > 250) return k - 1
        return k
    }

    /** Over: at the destination once the last ride is over, or ten minutes after the arrival. */
    fun isFinished(it: Itinerary, now: Instant, location: LatLon?): Boolean {
        val last = it.legs.lastOrNull() ?: return true
        if (now > it.endTime.plusSeconds(600)) return true
        val lastRideEnd = it.legs.lastOrNull { l -> l.isTransit }?.endTime ?: it.startTime
        return location != null && now >= lastRideEnd && location.distanceTo(last.to.coordinate) < 80
    }

    /** Where you are on a ride, and whether that comes from your own position (on board) or the clock. */
    data class RidePosition(val progress: TripProgress, val fromGps: Boolean)

    /**
     * Your place on a ride (TripGuide.ridePosition on iOS): from your fix when it lies on the line during the
     * ride and is no more than three stops off the timetable estimate, else by the clock.
     */
    fun ridePosition(leg: Leg, now: Instant, location: LatLon?): RidePosition {
        val stops = leg.allStops
        val byTime = TripProgress.at(now, stops)
        if (location == null || now < leg.startTime.minusSeconds(60) || now > leg.endTime.plusSeconds(600)) return RidePosition(byTime, false)
        val gps = TripProgress.located(location, stops, leg.legGeometry?.mapPoints.orEmpty()) ?: return RidePosition(byTime, false)
        if (kotlin.math.abs(gps.stopValue(stops.size) - byTime.stopValue(stops.size)) > 3) return RidePosition(byTime, false)
        return RidePosition(gps, true)
    }

    /** Stops still to come before (and including) the one to get off at. */
    fun stopsLeft(p: TripProgress, count: Int): Int = when (p) {
        TripProgress.NotStarted -> maxOf(count - 1, 0)
        is TripProgress.AtStop -> maxOf(count - 1 - p.index, 0)
        is TripProgress.Between -> maxOf(count - 1 - p.index - 1, 0) + 1
        TripProgress.Finished -> 0
    }

    enum class Check { FINE, OFF_ROUTE, MISSED }

    /** Something to offer a new route for: walking away from the path, or still at the stop after the vehicle left. */
    fun check(step: GuideStep, it: Itinerary, now: Instant, location: LatLon?, accuracy: Double): Check {
        val loc = location ?: return Check.FINE
        val leg = it.legs.getOrNull(step.legIndex) ?: return Check.FINE
        return when (step.kind) {
            GuideStep.Kind.WALK -> {
                // Away from the walking path (or the straight line where the server sent none).
                val path = leg.mapPath.ifEmpty { return Check.FINE }
                val off = path.zipWithNext().minOfOrNull { (a, b) -> distanceToSegment(loc, a, b) } ?: loc.distanceTo(path[0])
                if (off > maxOf(80.0, accuracy * 1.5) + 40) Check.OFF_ROUTE else Check.FINE
            }
            GuideStep.Kind.RIDE -> {
                if (now <= leg.startTime.plusSeconds(120) || loc.distanceTo(leg.from.coordinate) >= 150) return Check.FINE
                val stops = leg.allStops
                val vehicle = TripProgress.position(TripProgress.at(now, stops), stops) ?: return if (now < leg.endTime) Check.FINE else Check.MISSED
                if (vehicle.distanceTo(leg.from.coordinate) > 600) Check.MISSED else Check.FINE
            }
            else -> Check.FINE
        }
    }

    private fun distanceToSegment(p: LatLon, a: LatLon, b: LatLon): Double {
        val kx = 111_320 * Math.cos(Math.toRadians(p.lat)); val ky = 110_540.0
        val ax = (a.lon - p.lon) * kx; val ay = (a.lat - p.lat) * ky; val bx = (b.lon - p.lon) * kx; val by = (b.lat - p.lat) * ky
        val dx = bx - ax; val dy = by - ay; val len = dx * dx + dy * dy
        val t = if (len == 0.0) 0.0 else (-(ax * dx + ay * dy) / len).coerceIn(0.0, 1.0)
        val x = ax + t * dx; val y = ay + t * dy
        return Math.sqrt(x * x + y * y)
    }

    /** The most important thing to say now: a platform change first, then "get off at the next stop", then the stage. */
    fun cue(step: GuideStep, it: Itinerary, now: Instant, progress: TripProgress?): GuideCue? {
        val leg = it.legs.getOrNull(step.legIndex) ?: return null
        val platformLeg = if (step.kind == GuideStep.Kind.WAIT) step.legIndex
            else if (step.kind != GuideStep.Kind.ARRIVE && step.legIndex + 1 < it.legs.size) it.legs.withIndex().drop(step.legIndex + 1).firstOrNull { l -> l.value.isTransit }?.index else null
        platformLeg?.let { i ->
            val l = it.legs[i]
            val nowT = TransitLabels.clean(l.from.track); val planned = TransitLabels.clean(l.from.scheduledTrack)
            if (l.startTime > now && nowT != null && planned != null && TransitLabels.identity(nowT) != TransitLabels.identity(planned))
                return GuideCue(GuideCue.Kind.PLATFORM, "pl|$i|$nowT", L10n.f("Platform change: %@ now leaves from platform %@.", l.lineName, nowT))
        }
        return when (step.kind) {
            GuideStep.Kind.WALK -> GuideCue(GuideCue.Kind.STAGE, "stage|${step.id}",
                L10n.f("Walk to %@", it.legs.drop(step.legIndex + 1).firstOrNull { l -> l.isTransit }?.from?.name ?: leg.to.name))
            GuideStep.Kind.WAIT -> {
                val d = leg.shownDirection; val t = TransitLabels.clean(leg.from.track)
                GuideCue(GuideCue.Kind.STAGE, "stage|${step.id}", when {
                    d != null && t != null -> L10n.f("Take %@ towards %@ from platform %@", leg.lineName, d, t)
                    d != null -> L10n.f("Take %@ towards %@", leg.lineName, d)
                    else -> L10n.f("Take %@", leg.lineName)
                })
            }
            GuideStep.Kind.RIDE ->
                if (progress != null && stopsLeft(progress, leg.allStops.size) == 1) GuideCue(GuideCue.Kind.GET_OFF, "getoff|${step.legIndex}", L10n.f("Next stop: %@. Get off there.", leg.to.name))
                else GuideCue(GuideCue.Kind.STAGE, "stage|${step.id}", L10n.f("Get off at %@", leg.to.name))
            GuideStep.Kind.ARRIVE -> if (now >= it.endTime) GuideCue(GuideCue.Kind.STAGE, "stage|${step.id}", L10n.t("You have arrived")) else null
        }
    }
}

/** A new connection from where you are (TripAlternative on iOS): here the look for a clearly faster one. */
object TripAlternative {
    /** A better stop is only worth a word when it gets you there at least this much earlier (seconds). */
    const val FASTER_MUST_SAVE = 180L
    /** In the last minutes before the vehicle leaves nobody turns round for another stop (seconds). */
    const val FASTER_NEEDS_SLACK = 180L

    /** Those you can still catch, the earliest arrival first, each route once. */
    fun choices(options: List<Itinerary>, now: Instant, limit: Int = 5): List<Itinerary> =
        options.distinctBy { it.routeKey }
            .filter { !it.isCancelled && (it.firstDeparture ?: it.startTime) > now.plusSeconds(30) }
            .sortedBy { it.endTime }
            .take(limit)

    /**
     * Nothing is wrong and you are walking to a ride: worth one look whether another stop, or an
     * earlier vehicle, gets you there sooner from where you are (Publit#110). Not on the walk at the end,
     * and not before the walk is due: a trip planned for later is not slow because one could leave now.
     */
    fun worthLooking(step: GuideStep, it: Itinerary, now: Instant): Boolean {
        if (step.kind != GuideStep.Kind.WALK) return false
        val walk = it.legs.getOrNull(step.legIndex) ?: return false
        if (now < walk.startTime.minusSeconds(60)) return false
        val next = it.legs.getOrNull(step.legIndex + 1) ?: return false
        if (!next.isTransit || next.cancelled == true) return false
        return java.time.Duration.between(now, next.startTime).seconds >= FASTER_NEEDS_SLACK
    }

    /**
     * Routes from where you are that you can still catch and that arrive clearly earlier than the
     * trip as it stands, the earliest first. The same vehicles as the trip ahead are not another way,
     * even when a fresher live time makes them look a few minutes earlier.
     */
    fun faster(options: List<Itinerary>, trip: Itinerary, now: Instant, limit: Int = 3): List<Itinerary> {
        val ahead = rides(trip.legs.filter { it.endTime > now })
        return choices(options, now, Int.MAX_VALUE)
            .filter { java.time.Duration.between(it.endTime, trip.endTime).seconds >= FASTER_MUST_SAVE && rides(it.legs) != ahead }
            .take(limit)
    }

    /** The vehicles of these legs, in order (the run, or line, direction and scheduled departure). */
    private fun rides(legs: List<Leg>): List<String> = legs.filter { it.isTransit }.map { leg ->
        leg.tripId ?: "${leg.routeShortName ?: ""}>${leg.headsign ?: ""}~${(leg.scheduledStartTime ?: leg.startTime).epochSecond}"
    }

    /** Minutes a new route arrives after (+) or before (−) the trip as it stands. */
    fun minutesLater(option: Itinerary, trip: Itinerary): Int =
        Math.round(java.time.Duration.between(trip.endTime, option.endTime).seconds / 60.0).toInt()
}
