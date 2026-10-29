package io.github.elija7580.publit.data

import kotlinx.serialization.Serializable
import java.time.Instant

@Serializable
data class EncodedPolyline(val points: String = "", val precision: Int = 6, val length: Int = 0) {
    /** Decoded and thinned for drawing. */
    val mapPoints: List<io.github.elija7580.publit.core.LatLon> by lazy {
        io.github.elija7580.publit.core.Polyline.run { simplify(decode(points, precision)) }
    }
}

@Serializable
data class Leg(
    override val mode: String,
    val from: Place,
    val to: Place,
    val duration: Int = 0,
    val startTime: ApiInstant,
    val endTime: ApiInstant,
    val scheduledStartTime: ApiInstant? = null,
    val scheduledEndTime: ApiInstant? = null,
    val realTime: Boolean = false,
    val distance: Double? = null,
    val headsign: String? = null,
    override val routeColor: String? = null,
    override val routeTextColor: String? = null,
    override val routeShortName: String? = null,
    override val displayName: String? = null,
    val agencyName: String? = null,
    val routeId: String? = null,
    val tripId: String? = null,
    val cancelled: Boolean? = null,
    val intermediateStops: List<Place>? = null,
    val alerts: List<Alert>? = null,
    val legGeometry: EncodedPolyline? = null,
    /** Extra live source that corrected this ride ("ÖBB"). */
    val liveSource: String? = null,
    val fareTransferIndex: Int? = null,
    val effectiveFareLegIndex: Int? = null,
    /** Other vehicles for this ride (numLegAlternatives), for the backup of a change. */
    val alternatives: List<List<Leg>>? = null,
) : RouteStyled {
    val isTransit: Boolean get() = ModeStyle.isTransit(mode)
    val allStops: List<Place> get() = listOf(from) + intermediateStops.orEmpty() + listOf(to)
    val scheduledEnd: Instant get() = scheduledEndTime ?: endTime
    /** The leg's shape for maps, or a straight line between its ends. */
    val mapPath: List<io.github.elija7580.publit.core.LatLon>
        get() {
            val pts = legGeometry?.mapPoints.orEmpty()
            if (pts.size > 1) return pts
            return if (from.coordinate == to.coordinate) emptyList() else listOf(from.coordinate, to.coordinate)
        }
    val directionName: String? get() = TransitLabels.clean(headsign)
    val delayMinutes: Int
        get() = scheduledStartTime?.let { Math.round((startTime.epochSecond - it.epochSecond) / 60.0).toInt() } ?: 0
    val isCircular: Boolean get() = isTransit && CircularLine.isLoop(headsign, from.name)
    /** On a circle line the stop you get off at, otherwise the headsign. */
    val shownDirection: String? get() = if (isCircular) TransitLabels.clean(to.name) else directionName
    val serviceAlerts: List<Alert>
        get() = Alerts.relevant(alerts.orEmpty() + from.alertsList + to.alertsList, from = startTime, to = endTime)
}

@Serializable
data class Itinerary(
    val id: String = "",
    val duration: Int,
    val startTime: ApiInstant,
    val endTime: ApiInstant,
    val transfers: Int = 0,
    val legs: List<Leg>,
    val fareTransfers: List<FareTransfer>? = null,
) {
    val transitLegs: List<Leg> get() = legs.filter { it.isTransit }
    val isDirect: Boolean get() = transitLegs.isEmpty()
    val isCancelled: Boolean get() = legs.any { it.isTransit && it.cancelled == true }
    val firstDeparture: Instant? get() = transitLegs.firstOrNull()?.startTime
    /** Same route across live refreshes (the server id changes with every delay update). */
    val routeKey: String
        get() = legs.joinToString("|") { "${it.mode}~${it.tripId ?: it.from.name}~${(it.scheduledStartTime ?: it.startTime).epochSecond}" }

    /** Rides past the destination (or out and back): never the way to go. */
    val isDetour: Boolean
        get() {
            if (legs.size < 2) return false
            val dest = legs.last().to.coordinate
            if (legs.first().from.coordinate.distanceTo(dest) < 400) return false
            legs.forEachIndexed { i, leg ->
                val ridesOn = legs.drop(i + 1).any { it.isTransit }
                if (ridesOn && leg.to.coordinate.distanceTo(dest) < 250) return true
                if (leg.isTransit && legs.take(i).any { it.from.coordinate.distanceTo(leg.to.coordinate) < 150 }) return true
            }
            return false
        }
}

@Serializable
data class PlanResponse(
    val direct: List<Itinerary> = emptyList(),
    val itineraries: List<Itinerary> = emptyList(),
    val previousPageCursor: String? = null,
    val nextPageCursor: String? = null,
)

@Serializable
data class GeocodeArea(val name: String, val adminLevel: Double? = null, val default: Boolean? = null)

@Serializable
data class GeocodeMatch(
    val type: String = "",
    val name: String,
    val id: String = "",
    val lat: Double,
    val lon: Double,
    val street: String? = null,
    val houseNumber: String? = null,
    val country: String? = null,
    val areas: List<GeocodeArea> = emptyList(),
) {
    val isStop: Boolean get() = type == "STOP"
    val subtitle: String
        get() {
            val parts = ArrayList<String>()
            if (!isStop && street != null && street != name) parts += listOfNotNull(street, houseNumber).joinToString(" ")
            (areas.firstOrNull { it.default == true } ?: areas.lastOrNull())?.let { parts += it.name }
            country?.let { parts += it }
            return parts.joinToString(", ")
        }
}
