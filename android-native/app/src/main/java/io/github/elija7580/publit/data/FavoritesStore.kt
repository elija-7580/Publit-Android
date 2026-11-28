package io.github.elija7580.publit.data

import android.content.SharedPreferences
import io.github.elija7580.publit.core.L10n
import io.github.elija7580.publit.core.LatLon
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.util.Locale

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
    /** A shortcut next to Home and Work (iOS Publit#22), with its own symbol (`ShortcutSymbol` name). */
    val shortcut: Boolean? = null,
    val symbol: String? = null,
) {
    val isStop: Boolean get() = stopId != null
    val isShortcut: Boolean get() = shortcut == true && role == null
    /** What a pinned place is called: Home, Work, or its own name. */
    val keyTitle: String get() = role?.title ?: name
    val coordinate: LatLon get() = LatLon(lat, lon)

    /** Same saved thing: same ID, or the same station reached via a different stop ID. */
    fun isSame(other: SavedPlace): Boolean {
        if (id == other.id) {
            // A stop is known by its ID. An address ID is not unique: the search can hand out the
            // same one for two addresses, so two addresses are the same only at the same spot.
            if (isStop || other.isStop) return true
            return coordinate.distanceTo(other.coordinate) < SAME_SPOT_RADIUS
        }
        if (!isStop || !other.isStop) return false
        if (stopId == other.stopId) return true
        if (exact == true || other.exact == true) return false
        return StopGrouping.normalizedName(name) == StopGrouping.normalizedName(other.name) &&
            coordinate.distanceTo(other.coordinate) < StopGrouping.MERGE_RADIUS
    }

    /** A stable ID of its own for an address whose ID another saved address already has. */
    val ownId: String get() = "$id|${String.format(Locale.US, "%.5f,%.5f", lat, lon)}"

    /** The same saved thing, also when this address was saved under its own ID (`ownId`). */
    fun isSaved(saved: SavedPlace): Boolean = saved.isSame(this) || (!isStop && saved.id == ownId)

    companion object {
        /** Two addresses with the same ID closer than this (metres) are one saved place. */
        const val SAME_SPOT_RADIUS = 30.0

        fun of(m: GeocodeMatch) = SavedPlace(m.id, m.name, m.subtitle, m.lat, m.lon, if (m.isStop) m.id else null)
    }
}

/** The rules of the saved list, as in FavoritesStore on iOS (pure, unit-tested). */
object SavedPlaces {
    /** Sets `place` as Home or Work, replacing any earlier one for that role. Never touches another saved place. */
    fun set(saved: List<SavedPlace>, place: SavedPlace, role: PlaceRole): List<SavedPlace> {
        val items = saved.map { if (it.role == role) it.copy(role = null) else it }
        val p = place.copy(role = role)
        val same = items.indexOfFirst { it.id == place.id && it.isSame(place) }
        return when {
            // The same saved place becomes Home or Work.
            same >= 0 && items[same].role == null -> items.mapIndexed { i, it -> if (i == same) p else it }
            // The ID is taken: by the other role (same place, or an address ID the geocoder reuses) or by
            // another saved address. Keep that entry and store this role under an ID of its own.
            items.any { it.id == place.id } -> slotId(role, place).let { id -> items.filter { it.id != id } + p.copy(id = id) }
            else -> items + p
        }
    }

    /** Saves `place`, or removes it when it is saved already. */
    fun toggle(saved: List<SavedPlace>, place: SavedPlace): List<SavedPlace> = when {
        saved.any { place.isSaved(it) } -> saved.filter { !place.isSaved(it) }
        // Another address with this ID is saved already: both stay, each under its own ID.
        saved.any { it.id == place.id } -> saved + place.copy(id = place.ownId)
        else -> saved + place
    }

    /** A stable ID of its own for a place whose ID already holds the other role. */
    fun slotId(role: PlaceRole, place: SavedPlace): String =
        "${if (role == PlaceRole.HOME) "home" else "work"}|${place.id}|${String.format(Locale.US, "%.5f,%.5f", place.lat, place.lon)}"
}

/** Saved stops, places and lines in an order of your own, as `FavoritesStore.moved` on iOS (Publit#124; pure, unit-tested). */
object SavedOrder {
    /**
     * `all` with the entries of one kind (`isKind`) put in a new order among themselves: those at
     * `offsets` (counted within the kind) go before the entry at `destination`, like a list's drag
     * on iOS. Entries of other kinds keep their places, so stops, places, Home and Work stay one
     * stored list.
     */
    fun <T> moved(all: List<T>, offsets: Collection<Int>, destination: Int, isKind: (T) -> Boolean): List<T> {
        val slots = all.indices.filter { isKind(all[it]) }
        val valid = offsets.filter { it in slots.indices }.distinct().sorted()
        if (valid.isEmpty() || destination !in 0..slots.size) return all
        val order = slots.indices.filter { it !in valid }.toMutableList()
        order.addAll(destination - valid.count { it < destination }, valid)
        val result = all.toMutableList()
        slots.forEachIndexed { i, slot -> result[slot] = all[slots[order[i]]] }
        return result
    }

    /** The entry at `index` (counted within the kind) one place up or down; unchanged at either end. */
    fun <T> stepped(all: List<T>, index: Int, up: Boolean, isKind: (T) -> Boolean): List<T> =
        moved(all, listOf(index), if (up) index - 1 else index + 2, isKind)
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
    /** One direction only (iOS Publit#21): its identity (`direction`) and what it shows, e.g. "Ottakring". */
    val directionId: String? = null,
    val headsign: String? = null,
) : RouteStyled {
    /** The route identity doubles as route ID, so saved ÖBB lines show their line ("REX 4"), not one train. */
    override val routeId: String? get() = routeKey
    val id: String get() = "$routeKey@$stopId" + (directionId?.let { ">$it" } ?: "")
    val coordinate: LatLon get() = LatLon(lat, lon)
    /** The line in both directions, as saved before directions existed. */
    val anyDirection: SavedLine get() = copy(directionId = null, headsign = null)
    fun matches(st: StopTime) = key(st) == routeKey && (directionId == null || direction(st) == directionId)

    /** The same line at the same stop in `st`'s direction. */
    fun towards(st: StopTime) = copy(directionId = direction(st), headsign = st.shownDirection ?: st.headsign)

    companion object {
        /** Route identity: the feed's route ID when present, else agency and line name. */
        fun key(st: StopTime) = TransitLabels.lineKey(st.routeId) ?: "${st.agencyName ?: ""}|${st.lineName}"
        /** Direction identity, as the direction chips on a line. */
        fun direction(st: StopTime) = st.directionName?.let(TransitLabels::identity) ?: st.headsign
        fun of(st: StopTime, stopId: String, stopName: String, at: LatLon) = SavedLine(key(st), st.mode, st.routeShortName,
            st.displayName, st.routeColor, st.routeTextColor, stopId, stopName, at.lat, at.lon)
    }
}

/**
 * A saved connection from one place to another (SavedConnection on iOS, Publit#28): one tap plans it
 * again, leaving now; "Way back" plans it the other way round.
 */
@Serializable
data class SavedConnection(
    val fromName: String,
    val fromLat: Double,
    val fromLon: Double,
    val toName: String,
    val toLat: Double,
    val toLon: Double,
) {
    val from: LatLon get() = LatLon(fromLat, fromLon)
    val to: LatLon get() = LatLon(toLat, toLon)
    val id: String get() = key(from) + ">" + key(to)
    val title: String get() = "$fromName → $toName"
    val reversed: SavedConnection get() = SavedConnection(toName, toLat, toLon, fromName, fromLat, fromLon)

    companion object {
        private fun key(p: LatLon) = String.format(Locale.US, "%.4f,%.4f", p.lat, p.lon)
        fun of(fromName: String, from: LatLon, toName: String, to: LatLon) =
            SavedConnection(fromName, from.lat, from.lon, toName, to.lat, to.lon)

        /** Saves `c`, or removes it when the same connection (same ends, rounded to ~10 m) is saved already. */
        fun toggled(saved: List<SavedConnection>, c: SavedConnection): List<SavedConnection> =
            if (saved.any { it.id == c.id }) saved.filter { it.id != c.id } else saved + c
    }
}

/** Saved places, stops and lines, as FavoritesStore on iOS (same storage keys). */
class FavoritesStore(private val prefs: SharedPreferences) {
    data class State(val items: List<SavedPlace> = emptyList(), val lines: List<SavedLine> = emptyList(),
                     val connections: List<SavedConnection> = emptyList()) {
        fun containsConnection(c: SavedConnection) = connections.any { it.id == c.id }
        val stops get() = items.filter { it.isStop && it.role == null && !it.isShortcut }
        val places get() = items.filter { !it.isStop && it.role == null && !it.isShortcut }
        /** Own shortcuts, in their own order (iOS Publit#22). */
        val shortcuts get() = items.filter { it.isShortcut }
        /** Home, Work, then own shortcuts: the one-tap destinations. */
        val pinned get() = PlaceRole.entries.mapNotNull { place(it) } + shortcuts
        fun place(role: PlaceRole) = items.firstOrNull { it.role == role }
        fun containsLine(id: String) = lines.any { it.id == id }
        fun contains(p: SavedPlace) = items.any { p.isSaved(it) }
    }

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val placesSer = ListSerializer(SavedPlace.serializer())
    private val linesSer = ListSerializer(SavedLine.serializer())
    private val connectionsSer = ListSerializer(SavedConnection.serializer())
    /**
     * A saved list, entry by entry: one entry the app cannot read no longer empties the list (Publit#207). The
     * text as it was is kept under "<key>.unreadable", because the next change writes the list anew.
     */
    private fun <T : Any> read(key: String, element: KSerializer<T>): List<T> {
        val text = prefs.getString(key, null) ?: return emptyList()
        val read = SavedLists.decode(json, element, text)
        if (!read.complete) prefs.edit().putString("$key.unreadable", text).apply()
        return read.items
    }

    private val _state = MutableStateFlow(State(
        read(KEY, SavedPlace.serializer()), read(LINES_KEY, SavedLine.serializer()), read(CONNECTIONS_KEY, SavedConnection.serializer()),
    ))
    val state = _state.asStateFlow()

    fun toggle(line: SavedLine) {
        val l = _state.value.lines
        saveLines(if (l.any { it.id == line.id }) l.filter { it.id != line.id } else l + line)
    }
    fun removeLine(id: String) = saveLines(_state.value.lines.filter { it.id != id })

    /** Sets `place` as Home or Work, replacing any earlier one for that role. */
    fun set(place: SavedPlace, role: PlaceRole) = savePlaces(SavedPlaces.set(_state.value.items, place, role))
    fun clear(role: PlaceRole) = savePlaces(_state.value.items.filter { it.role != role })

    fun toggle(place: SavedPlace) = savePlaces(SavedPlaces.toggle(_state.value.items, place))

    // Own shortcuts (iOS Publit#22).
    /** Adds `place` as a shortcut, or makes the saved place one. */
    fun addShortcut(place: SavedPlace, symbol: String) {
        val items = _state.value.items
        val i = items.indexOfFirst { place.isSaved(it) && it.role == null }
        savePlaces(if (i >= 0) items.mapIndexed { j, it -> if (j == i) it.copy(shortcut = true, symbol = symbol) else it }
            else SavedPlaces.toggle(items, place.copy(shortcut = true, symbol = symbol)))
    }
    /** Back to an ordinary saved place. */
    fun removeShortcut(id: String) = savePlaces(_state.value.items.map { if (it.id == id) it.copy(shortcut = null, symbol = null) else it })
    fun setSymbol(id: String, symbol: String) = savePlaces(_state.value.items.map { if (it.id == id) it.copy(symbol = symbol) else it })
    fun moveShortcut(index: Int, up: Boolean) = savePlaces(SavedOrder.stepped(_state.value.items, index, up) { it.isShortcut })
    fun remove(id: String) = savePlaces(_state.value.items.filter { it.id != id })

    // Own order (iOS Publit#124). `index` counts within `stops`, `places` or `lines`.
    fun moveStop(index: Int, up: Boolean) = savePlaces(SavedOrder.stepped(_state.value.items, index, up) { it.isStop && it.role == null && !it.isShortcut })
    fun movePlace(index: Int, up: Boolean) = savePlaces(SavedOrder.stepped(_state.value.items, index, up) { !it.isStop && it.role == null && !it.isShortcut })
    fun moveLine(index: Int, up: Boolean) = saveLines(SavedOrder.stepped(_state.value.lines, index, up) { true })

    // Saved connections (iOS Publit#28).
    fun toggle(connection: SavedConnection) = saveConnections(SavedConnection.toggled(_state.value.connections, connection))
    fun removeConnection(id: String) = saveConnections(_state.value.connections.filter { it.id != id })
    fun moveConnection(index: Int, up: Boolean) = saveConnections(SavedOrder.stepped(_state.value.connections, index, up) { true })

    private fun saveConnections(list: List<SavedConnection>) {
        _state.value = _state.value.copy(connections = list)
        prefs.edit().putString(CONNECTIONS_KEY, json.encodeToString(connectionsSer, list)).apply()
    }

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
        const val CONNECTIONS_KEY = "publit.connections.v1"
    }
}
