package io.github.elija7580.publit.data

import io.github.elija7580.publit.core.LatLon
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.time.Instant
import java.time.OffsetDateTime

/** ISO 8601 times as sent by MOTIS ("2026-10-29T18:01:00Z", with or without fractions or offset). */
object InstantSerializer : KSerializer<Instant> {
    override val descriptor = PrimitiveSerialDescriptor("Instant", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: Instant) = encoder.encodeString(value.toString())
    override fun deserialize(decoder: Decoder): Instant = parse(decoder.decodeString())
    fun parse(s: String): Instant = runCatching { Instant.parse(s) }.getOrElse { OffsetDateTime.parse(s).toInstant() }
}

typealias ApiInstant = @Serializable(with = InstantSerializer::class) Instant

@Serializable
data class AlertPeriod(val start: ApiInstant? = null, val end: ApiInstant? = null) {
    fun contains(date: Instant) = (start == null || start <= date) && (end == null || date < end)
    fun overlaps(first: Instant, last: Instant) = (start == null || start <= last) && (end == null || first < end)
}

@Serializable
data class Alert(
    val headerText: String = "",
    val descriptionText: String = "",
    val url: String? = null,
    val effect: String? = null,
    val severityLevel: String? = null,
    val communicationPeriod: List<AlertPeriod>? = null,
    val impactPeriod: List<AlertPeriod>? = null,
)

@Serializable
data class Place(
    val name: String,
    val stopId: String? = null,
    val parentId: String? = null,
    val lat: Double,
    val lon: Double,
    val tz: String? = null,
    val arrival: ApiInstant? = null,
    val departure: ApiInstant? = null,
    val scheduledArrival: ApiInstant? = null,
    val scheduledDeparture: ApiInstant? = null,
    val track: String? = null,
    val scheduledTrack: String? = null,
    val cancelled: Boolean? = null,
    val alerts: List<Alert>? = null,
) {
    val coordinate get() = LatLon(lat, lon)
    val alertsList get() = alerts.orEmpty()
    /** Groups platforms of one station. */
    val stationId get() = parentId ?: stopId ?: "$name@$lat,$lon"
}

@Serializable
data class SegmentStop(val name: String, val stopId: String? = null, val lat: Double = 0.0, val lon: Double = 0.0)

/** Line styling shared by departures and legs. */
interface RouteStyled {
    val mode: String
    val routeColor: String?
    val routeTextColor: String?
    val routeShortName: String?
    val displayName: String?

    /** The compact, operator-defined line identifier. Never a destination. */
    val lineName: String
        get() = TransitLabels.clean(routeShortName) ?: TransitLabels.clean(displayName) ?: ModeStyle.label(mode)

    /** Additional service identity (e.g. ICE 615), kept apart from the line badge. */
    val serviceDetail: String?
        get() {
            val display = TransitLabels.clean(displayName) ?: return null
            return display.takeIf { TransitLabels.identity(it) != TransitLabels.identity(lineName) }
        }

    val lineColors: LineColors.Pair
        get() = LineColors.resolve(routeColor, routeTextColor, ModeStyle.defaultHex(mode))
}

@Serializable
data class StopTime(
    val place: Place,
    override val mode: String,
    val realTime: Boolean = false,
    val headsign: String = "",
    val agencyName: String? = null,
    val routeId: String? = null,
    val tripId: String,
    override val routeShortName: String? = null,
    override val displayName: String? = null,
    override val routeColor: String? = null,
    override val routeTextColor: String? = null,
    val cancelled: Boolean? = null,
    val tripCancelled: Boolean? = null,
    val directionId: String? = null,
    val tripTo: SegmentStop? = null,
) : RouteStyled {
    val departureTime: Instant get() = place.departure ?: place.arrival ?: Instant.EPOCH
    val scheduledDepartureTime: Instant? get() = place.scheduledDeparture ?: place.scheduledArrival

    /** By the timetable time: a new delay must not turn a row into a different row. */
    val id: String get() = "$tripId|${place.stopId ?: place.name}|${(scheduledDepartureTime ?: departureTime).epochSecond}"
    val directionName: String? get() = TransitLabels.clean(headsign)
    val isCancelled: Boolean get() = cancelled == true || tripCancelled == true || place.cancelled == true
    val delayMinutes: Int
        get() {
            val s = scheduledDepartureTime ?: return 0
            return Math.round((departureTime.epochSecond - s.epochSecond) / 60.0).toInt()
        }
    val isCircular: Boolean
        get() = CircularLine.isLoop(headsign, place.name) || (tripTo?.let { CircularLine.isLoop(it.name, place.name) } == true)
    /** A circle line has no useful destination: it says "Circle line" instead. */
    val shownDirection: String? get() = if (isCircular) null else directionName
    val serviceAlerts: List<Alert> get() = Alerts.relevant(place.alertsList, from = departureTime)
    val isReplacementBus: Boolean
        get() {
            if (mode != "BUS" && mode != "COACH") return false
            val name = (routeShortName ?: "").uppercase()
            return name == "SEV" || name.startsWith("SEV ") || (displayName ?: "").lowercase().contains("schienenersatz")
        }

    companion object {
        /** Drops departures that have left, and repeated entries (list IDs stay unique). */
        fun upcoming(list: List<StopTime>, now: Instant = Instant.now()): List<StopTime> {
            val seen = HashSet<String>()
            val limit = now.minusSeconds(60)
            return list.filter { it.departureTime > limit && seen.add(it.id) }
        }
    }
}

@Serializable
data class StopTimesResponse(
    val stopTimes: List<StopTime> = emptyList(),
    val place: Place? = null,
    val nextPageCursor: String? = null,
)

@Serializable
data class MapStop(
    val name: String,
    val stopId: String,
    val parentId: String? = null,
    val lat: Double,
    val lon: Double,
    val importance: Double? = null,
    val modes: List<String>? = null,
) {
    val coordinate get() = LatLon(lat, lon)
    val stationId get() = parentId ?: stopId
}
