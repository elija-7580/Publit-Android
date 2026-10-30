package io.github.elija7580.publit.data

import io.github.elija7580.publit.core.LatLon
import io.github.elija7580.publit.core.Polyline
import kotlinx.serialization.Serializable
import java.time.Instant
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/** One vehicle trip between two consecutive stops, with the shape it travels on (/api/v6/map/trips). */
@Serializable
data class TripSegment(
    val trips: List<TripInfo> = emptyList(), val routeColor: String? = null, val mode: String, val distance: Double = 0.0,
    val from: SegmentStop, val to: SegmentStop, val departure: ApiInstant, val arrival: ApiInstant,
    val scheduledDeparture: ApiInstant, val scheduledArrival: ApiInstant? = null, val realTime: Boolean = false, val polyline: String = "",
) {
    @Serializable data class TripInfo(val tripId: String, val displayName: String? = null, val routeShortName: String? = null)
}

/** Bike and scooter sharing in a map rectangle (/api/v1/rentals). */
@Serializable
data class RentalsResponse(val providerGroups: List<Group> = emptyList(), val stations: List<Station> = emptyList(), val vehicles: List<Vehicle> = emptyList()) {
    @Serializable data class Group(val id: String, val name: String, val color: String? = null)
    @Serializable data class Station(val id: String, val providerGroupId: String, val name: String, val lat: Double, val lon: Double,
                                     val numVehiclesAvailable: Int? = null, val formFactors: List<String>? = null, val rentalUriWeb: String? = null,
                                     val rentalUriAndroid: String? = null)
    @Serializable data class Vehicle(val id: String, val providerGroupId: String, val lat: Double, val lon: Double, val formFactor: String? = null,
                                     val isReserved: Boolean? = null, val isDisabled: Boolean? = null, val rentalUriWeb: String? = null,
                                     val rentalUriAndroid: String? = null) { val isAvailable get() = isReserved != true && isDisabled != true }
    fun groupName(id: String) = providerGroups.firstOrNull { it.id == id }?.name ?: id
}

/** Distances along a polyline, to place a vehicle at a share of the way between two stops. */
class PolylineRuler(points: List<LatLon>) {
    val points = points.filter { it.lat in -90.0..90.0 && it.lon in -180.0..180.0 }
    private val cumulative = DoubleArray(this.points.size).also { acc ->
        for (i in 1 until this.points.size) acc[i] = acc[i - 1] + this.points[i - 1].distanceTo(this.points[i])
    }
    val length get() = cumulative.lastOrNull() ?: 0.0

    /** Point `meters` along the line, and the direction of travel there (degrees from north). */
    fun point(meters: Double): Pair<LatLon, Double?>? {
        val first = points.firstOrNull() ?: return null
        if (points.size == 1 || !meters.isFinite()) return first to null
        val d = meters.coerceIn(0.0, length)
        var lo = 0; var hi = cumulative.size - 1
        while (hi - lo > 1) { val mid = (lo + hi) / 2; if (cumulative[mid] <= d) lo = mid else hi = mid }
        val a = points[lo]; val b = points[hi]
        val span = cumulative[hi] - cumulative[lo]
        val f = if (span > 0) (d - cumulative[lo]) / span else 0.0
        return LatLon(a.lat + (b.lat - a.lat) * f, a.lon + (b.lon - a.lon) * f) to bearing(a, b)
    }

    private fun bearing(a: LatLon, b: LatLon): Double? {
        if (a == b) return null
        val p1 = Math.toRadians(a.lat); val p2 = Math.toRadians(b.lat); val dl = Math.toRadians(b.lon - a.lon)
        val deg = Math.toDegrees(atan2(sin(dl) * cos(p2), cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)))
        return if (deg < 0) deg + 360 else deg
    }
}

/** A vehicle drawn on the map at its estimated position. */
data class LiveVehicle(val id: String, val name: String, val mode: String, val colorHex: String, val coordinate: LatLon,
                       val heading: Double?, val nextStop: String, val delayMinutes: Int, val realTime: Boolean)

/** Part of a line's route, drawn once however many vehicles use it. */
data class LineShape(val id: String, val mode: String, val colorHex: String, val points: List<LatLon>)

/** The way one vehicle takes through the visible area, stop to stop. */
class VehicleTrack(val id: String, val name: String, val mode: String, val colorHex: String, val realTime: Boolean, val pieces: List<Piece>) {
    class Piece(val departure: Instant, val arrival: Instant, val ruler: PolylineRuler, val toName: String, val delayMinutes: Int)

    /** Where the vehicle is at `t`; null before it comes or after it has left the area. */
    fun vehicle(t: Instant): LiveVehicle? {
        pieces.forEachIndexed { i, p ->
            if (t < p.departure) {
                val waiting = if (i > 0) t >= pieces[i - 1].arrival else p.departure.epochSecond - t.epochSecond < 60
                val start = p.ruler.points.firstOrNull()
                return if (waiting && start != null) make(start, null, p.toName, p.delayMinutes) else null
            }
            if (t <= p.arrival) {
                val span = (p.arrival.toEpochMilli() - p.departure.toEpochMilli()).toDouble()
                val f = if (span > 0) (t.toEpochMilli() - p.departure.toEpochMilli()) / span else 1.0
                val (pos, heading) = p.ruler.point(f * p.ruler.length) ?: return null
                return make(pos, heading, p.toName, p.delayMinutes)
            }
        }
        return null
    }

    private fun make(pos: LatLon, heading: Double?, next: String, delay: Int) = LiveVehicle(id, name, mode, colorHex, pos, heading, next, delay, realTime)
}

/** Turns /map/trips segments into line shapes and vehicle tracks (LiveMap on iOS). */
object LiveMap {
    private val excluded = setOf("AIRPLANE")
    private const val MAX_SEGMENT = 200_000.0
    const val MAX_SHAPES = 1500
    const val MAX_VEHICLES = 300

    class Snapshot(val shapes: List<LineShape>, val tracks: List<VehicleTrack>)

    fun colorHex(routeColor: String?, mode: String): String {
        val c = (routeColor ?: "").trim('#', ' ').uppercase()
        return if (c.length == 6 && c.all { it in "0123456789ABCDEF" }) c else ModeStyle.defaultHex(mode)
    }

    /** Drawing order: buses at the bottom, rail on top. */
    fun rank(mode: String) = when (ModeGroup.of(mode)) {
        ModeGroup.BUS -> 0; ModeGroup.OTHER, ModeGroup.FERRY -> 1; ModeGroup.TRAM -> 2; ModeGroup.SUBWAY -> 3; ModeGroup.SUBURBAN -> 4; ModeGroup.TRAIN -> 5
    }

    fun build(segments: List<TripSegment>, filter: ModeFilter, precision: Int, tolerance: Double): Snapshot {
        val shapes = LinkedHashMap<String, LineShape>()
        val pieces = HashMap<String, MutableList<VehicleTrack.Piece>>()
        val info = HashMap<String, VehicleTrack>()
        for (s in segments) {
            if (s.mode in excluded || s.distance > MAX_SEGMENT || !filter.allows(s.mode) || s.arrival < s.departure) continue
            val trip = s.trips.firstOrNull() ?: continue
            val color = colorHex(s.routeColor, s.mode)
            val points = Polyline.simplify(Polyline.decode(s.polyline, precision), tolerance)
            if (points.size < 2) continue
            val key = "${s.from.stopId ?: s.from.name}>${s.to.stopId ?: s.to.name}|$color"
            shapes.getOrPut(key) { LineShape(key, s.mode, color, points) }
            val delay = ((s.departure.epochSecond - s.scheduledDeparture.epochSecond) / 60.0).let { Math.round(it).toInt().coerceIn(-1440, 1440) }
            pieces.getOrPut(trip.tripId) { ArrayList() } += VehicleTrack.Piece(s.departure, s.arrival, PolylineRuler(points), s.to.name, delay)
            if (trip.tripId !in info) {
                val name = TransitLabels.clean(trip.routeShortName) ?: TransitLabels.clean(trip.displayName) ?: ModeStyle.label(s.mode)
                info[trip.tripId] = VehicleTrack(trip.tripId, name, s.mode, color, s.realTime, emptyList())
            }
        }
        val tracks = pieces.mapNotNull { (id, list) -> info[id]?.let { VehicleTrack(id, it.name, it.mode, it.colorHex, it.realTime, list.sortedBy { p -> p.departure }) } }
        return Snapshot(shapes.values.sortedWith(compareBy({ rank(it.mode) }, { it.id })).take(MAX_SHAPES), tracks)
    }

    /** Vehicles inside the box at `t`, rail first when there are too many to draw. */
    fun vehicles(tracks: List<VehicleTrack>, t: Instant, min: LatLon, max: LatLon): List<LiveVehicle> {
        val list = tracks.mapNotNull { it.vehicle(t) }.filter { it.coordinate.lat in min.lat..max.lat && it.coordinate.lon in min.lon..max.lon }
        return if (list.size <= MAX_VEHICLES) list else list.sortedByDescending { rank(it.mode) }.take(MAX_VEHICLES)
    }

    /** Simplification for a map showing `spanMeters` across: about a pixel or two. */
    fun tolerance(spanMeters: Double) = (spanMeters / 800).coerceIn(2.0, 40.0)
}
