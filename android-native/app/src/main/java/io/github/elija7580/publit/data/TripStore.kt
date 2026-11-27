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

/**
 * Which trip the bar above the tabs shows (LiveTripBar on iOS, build 99): the bar's trip, unless it was hidden
 * by hand. A started trip always shows: hidden before it was started, it kept running out of sight (live
 * notification on, reminders set), with no way to end it from the bar.
 */
object LiveBar {
    fun shown(current: SavedTrip?, activeId: String?, hiddenId: String): SavedTrip? =
        current?.takeIf { it.id != hiddenId || it.id == activeId }
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

    @Synchronized fun save(it: Itinerary, from: String, to: String): SavedTrip {
        saved(it)?.let { t -> return t }
        val t = SavedTrip(UUID.randomUUID().toString(), it, from, to, Instant.now())
        set(trips + t)
        return t
    }

    @Synchronized fun remove(id: String) {
        if (_state.value.activeId == id) stop(id)
        set(trips.filter { it.id != id })
    }

    /** Only one manually started trip at a time. */
    @Synchronized fun start(id: String) {
        if (trip(id) == null) return
        _state.value.activeId?.takeIf { it != id }?.let { setFollowLive(it, false) }
        _state.value = _state.value.copy(activeId = id)
        setFollowLive(id, true)
        runCatching { activeFile.writeText(id) }
    }

    @Synchronized fun stop(id: String) {
        if (_state.value.activeId != id) return
        _state.value = _state.value.copy(activeId = null)
        setFollowLive(id, false)
        runCatching { activeFile.writeText("") }
    }

    @Synchronized fun setFollowLive(id: String, on: Boolean) = set(trips.map { if (it.id == id) it.copy(followLive = on) else it })

    /** Replace the stored route with a fresh real-time version. */
    @Synchronized fun update(id: String, with: Itinerary) = set(trips.map { if (it.id == id) it.copy(itinerary = with, refreshedAt = Instant.now()) else it })

    /**
     * The same, but only while the trip still holds the route with the ID `expected` (the one a live refresh was asked
     * for). False when the trip is gone or holds another route by now (rerouted, or refreshed meanwhile).
     */
    @Synchronized fun updateIfCurrent(id: String, expected: String, with: Itinerary): Boolean {
        if (trip(id)?.itinerary?.id != expected) return false
        update(id, with)
        return true
    }

    /** A new connection for a trip under way (missed, cancelled, off the path): same trip, new route from where you are. */
    @Synchronized fun reroute(id: String, with: Itinerary, from: String) =
        set(trips.map { if (it.id == id) it.copy(itinerary = with, fromName = from, refreshedAt = Instant.now()) else it })

    // Changes come from the main thread and from the live refresh of a started trip (a background thread, see
    // TripLearningSession): every change is one step under the store's lock, so none is lost and the file is
    // never written by two threads at once.
    @Synchronized private fun set(list: List<SavedTrip>) {
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
