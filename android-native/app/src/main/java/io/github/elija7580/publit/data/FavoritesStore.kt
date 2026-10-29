package io.github.elija7580.publit.data

import android.content.SharedPreferences
import io.github.elija7580.publit.core.L10n
import io.github.elija7580.publit.core.LatLon
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

@Serializable
enum class PlaceRole {
    @SerialName("home") HOME, @SerialName("work") WORK;
    val title: String get() = if (this == HOME) L10n.t("Home") else L10n.t("Work")
}

/** A saved place or stop. Stored locally on the device only. */
@Serializable
data class SavedPlace(
    val id: String,
    val name: String,
    val subtitle: String? = null,
    val lat: Double,
    val lon: Double,
    val stopId: String? = null,
    val role: PlaceRole? = null,
    /** One street stop only (one side of the road), not the whole station. */
    val exact: Boolean? = null,
) {
    val isStop: Boolean get() = stopId != null
    val coordinate: LatLon get() = LatLon(lat, lon)

    /** Same saved thing: same ID, or the same station reached via a different stop ID. */
    fun isSame(other: SavedPlace): Boolean {
        if (id == other.id) return true
        if (!isStop || !other.isStop) return false
        if (stopId == other.stopId) return true
        if (exact == true || other.exact == true) return false
        return StopGrouping.normalizedName(name) == StopGrouping.normalizedName(other.name) &&
            coordinate.distanceTo(other.coordinate) < StopGrouping.MERGE_RADIUS
    }

    companion object {
        fun of(m: GeocodeMatch) = SavedPlace(m.id, m.name, m.subtitle, m.lat, m.lon, if (m.isStop) m.id else null)
    }
}

/** A saved line at one stop, e.g. "U6 at Marienplatz". Shows that line's next departures there. */
@Serializable
data class SavedLine(
    val routeKey: String,
    override val mode: String,
    override val routeShortName: String? = null,
    override val displayName: String? = null,
    override val routeColor: String? = null,
    override val routeTextColor: String? = null,
    val stopId: String,
    val stopName: String,
    val lat: Double,
    val lon: Double,
) : RouteStyled {
    val id: String get() = "$routeKey@$stopId"
    val coordinate: LatLon get() = LatLon(lat, lon)
    fun matches(st: StopTime) = key(st) == routeKey

    companion object {
        /** Route identity: the feed's route ID when present, else agency and line name. */
        fun key(st: StopTime) = st.routeId ?: "${st.agencyName ?: ""}|${st.lineName}"
        fun of(st: StopTime, stopId: String, stopName: String, at: LatLon) = SavedLine(key(st), st.mode, st.routeShortName,
            st.displayName, st.routeColor, st.routeTextColor, stopId, stopName, at.lat, at.lon)
    }
}

/** Saved places, stops and lines, as FavoritesStore on iOS (same storage keys). */
class FavoritesStore(private val prefs: SharedPreferences) {
    data class State(val items: List<SavedPlace> = emptyList(), val lines: List<SavedLine> = emptyList()) {
        val stops get() = items.filter { it.isStop && it.role == null }
        val places get() = items.filter { !it.isStop && it.role == null }
        fun place(role: PlaceRole) = items.firstOrNull { it.role == role }
        fun containsLine(id: String) = lines.any { it.id == id }
        fun contains(p: SavedPlace) = items.any { it.isSame(p) }
    }

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val placesSer = ListSerializer(SavedPlace.serializer())
    private val linesSer = ListSerializer(SavedLine.serializer())
    private val _state = MutableStateFlow(State(
        prefs.getString(KEY, null)?.let { runCatching { json.decodeFromString(placesSer, it) }.getOrNull() }.orEmpty(),
        prefs.getString(LINES_KEY, null)?.let { runCatching { json.decodeFromString(linesSer, it) }.getOrNull() }.orEmpty(),
    ))
    val state = _state.asStateFlow()

    fun toggle(line: SavedLine) {
        val l = _state.value.lines
        saveLines(if (l.any { it.id == line.id }) l.filter { it.id != line.id } else l + line)
    }
    fun removeLine(id: String) = saveLines(_state.value.lines.filter { it.id != id })

    /** Sets `place` as Home or Work, replacing any earlier one for that role. */
    fun set(place: SavedPlace, role: PlaceRole) {
        val items = _state.value.items.map { if (it.role == role) it.copy(role = null) else it }
        val p = place.copy(role = role)
        savePlaces(if (items.any { it.id == place.id }) items.map { if (it.id == place.id) p else it } else items + p)
    }
    fun clear(role: PlaceRole) = savePlaces(_state.value.items.filter { it.role != role })

    fun toggle(place: SavedPlace) {
        val items = _state.value.items
        savePlaces(if (items.any { it.isSame(place) }) items.filter { !it.isSame(place) } else items + place)
    }
    fun remove(id: String) = savePlaces(_state.value.items.filter { it.id != id })

    private fun savePlaces(items: List<SavedPlace>) {
        _state.value = _state.value.copy(items = items)
        prefs.edit().putString(KEY, json.encodeToString(placesSer, items)).apply()
    }
    private fun saveLines(lines: List<SavedLine>) {
        _state.value = _state.value.copy(lines = lines)
        prefs.edit().putString(LINES_KEY, json.encodeToString(linesSer, lines)).apply()
    }

    companion object {
        const val KEY = "publit.favorites.v1"
        const val LINES_KEY = "publit.lines.v1"
    }
}
