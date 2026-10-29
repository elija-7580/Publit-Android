package io.github.elija7580.publit.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Instant
import java.util.UUID

/** A connection the user saved (e.g. "Friday 18:12 to Bari airport"). Stored on the device only. */
@Serializable
data class SavedTrip(
    val id: String,
    val itinerary: Itinerary,
    val fromName: String,
    val toName: String,
    val savedAt: ApiInstant,
    val refreshedAt: ApiInstant? = null,
    /** "Remind me to leave" is on. */
    val followLive: Boolean? = null,
) {
    val startTime: Instant get() = itinerary.startTime
    val endTime: Instant get() = itinerary.endTime
    val title: String get() = "$fromName → $toName"
}

/** Saved trips and the one started by hand (TripStore on iOS). */
class TripStore(dir: File) {
    data class State(val trips: List<SavedTrip> = emptyList(), val activeId: String? = null)

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; coerceInputValues = true }
    private val ser = ListSerializer(SavedTrip.serializer())
    private val file = File(dir, "trips.json")
    private val activeFile = File(dir, "active-trip.json")
    private val _state: MutableStateFlow<State>
    val state get() = _state.asStateFlow()

    init {
        val trips = runCatching { json.decodeFromString(ser, file.readText()) }.getOrDefault(emptyList())
        val active = runCatching { activeFile.readText().trim() }.getOrDefault("")
        _state = MutableStateFlow(State(trips, active.takeIf { a -> trips.any { it.id == a } }))
    }

    val trips get() = _state.value.trips
    fun trip(id: String) = trips.firstOrNull { it.id == id }

    val activeTrip: SavedTrip?
        get() = _state.value.activeId?.let(::trip)?.takeIf { it.endTime.plusSeconds(15 * 60) >= Instant.now() }

    /** Not yet arrived, soonest first. */
    fun upcoming(now: Instant = Instant.now()) = trips.filter { it.endTime >= now }.sortedBy { it.startTime }
    /** Already arrived, most recent first. */
    fun past(now: Instant = Instant.now()) = trips.filter { it.endTime < now }.sortedByDescending { it.startTime }

    /** The trip for the live bar: the one started by hand, otherwise one under way or leaving within 30 min. */
    fun current(now: Instant = Instant.now()): SavedTrip? {
        _state.value.activeId?.let(::trip)?.let { t ->
            if (t.endTime.plusSeconds(300) >= now && t.startTime.minusSeconds(3 * 3600) <= now) return t
        }
        return trips.filter { it.startTime.minusSeconds(LIVE_LEAD) <= now && it.endTime.plusSeconds(300) >= now }.minByOrNull { it.startTime }
    }

    fun saved(it: Itinerary): SavedTrip? {
        val key = matchKey(it)
        return trips.firstOrNull { t -> t.itinerary.id == it.id || matchKey(t.itinerary) == key }
    }

    fun save(it: Itinerary, from: String, to: String): SavedTrip {
        saved(it)?.let { t -> return t }
        val t = SavedTrip(UUID.randomUUID().toString(), it, from, to, Instant.now())
        set(trips + t)
        return t
    }

    fun remove(id: String) {
        if (_state.value.activeId == id) stop(id)
        set(trips.filter { it.id != id })
    }

    /** Only one manually started trip at a time. */
    fun start(id: String) {
        if (trip(id) == null) return
        _state.value.activeId?.takeIf { it != id }?.let { setFollowLive(it, false) }
        _state.value = _state.value.copy(activeId = id)
        setFollowLive(id, true)
        runCatching { activeFile.writeText(id) }
    }

    fun stop(id: String) {
        if (_state.value.activeId != id) return
        _state.value = _state.value.copy(activeId = null)
        setFollowLive(id, false)
        runCatching { activeFile.writeText("") }
    }

    fun setFollowLive(id: String, on: Boolean) = set(trips.map { if (it.id == id) it.copy(followLive = on) else it })

    /** Replace the stored route with a fresh real-time version. */
    fun update(id: String, with: Itinerary) = set(trips.map { if (it.id == id) it.copy(itinerary = with, refreshedAt = Instant.now()) else it })

    private fun set(list: List<SavedTrip>) {
        _state.value = _state.value.copy(trips = list)
        runCatching {
            val tmp = File(file.parentFile, "trips.json.tmp")
            tmp.writeText(json.encodeToString(ser, list)); tmp.renameTo(file)
        }
    }

    companion object {
        const val LIVE_LEAD = 30 * 60L

        /** Same vehicles from the same stops on the same scheduled times. */
        fun matchKey(it: Itinerary): String {
            val legs = it.transitLegs.map { l -> "${l.tripId ?: l.lineName}@${l.from.name}@${(l.scheduledStartTime ?: l.startTime).epochSecond}" }
            return if (legs.isEmpty()) "walk@${it.startTime.epochSecond}@${it.legs.lastOrNull()?.to?.name ?: ""}" else legs.joinToString(">")
        }
    }
}
