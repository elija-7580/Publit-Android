package io.github.elija7580.publit.ui

import io.github.elija7580.publit.data.ParkAndRide
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.elija7580.publit.CrashLog
import io.github.elija7580.publit.core.L10n
import io.github.elija7580.publit.core.LatLon
import io.github.elija7580.publit.data.GeocodeMatch
import io.github.elija7580.publit.data.Itinerary
import io.github.elija7580.publit.data.TransitousClient
import io.github.elija7580.publit.location.LocationAccess
import io.github.elija7580.publit.location.LocationRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant

/** Start or destination: where the phone is, or a place from the search. */
sealed interface Endpoint {
    data object Here : Endpoint
    data class At(val name: String, val point: LatLon) : Endpoint
}

val Endpoint.title: String get() = when (this) { Endpoint.Here -> L10n.t("Current location"); is Endpoint.At -> name }

data class PlanState(
    val from: Endpoint = Endpoint.Here,
    val to: Endpoint? = null,
    /** null = leave now. */
    val time: Instant? = null,
    val arriveBy: Boolean = false,
    /** "Last": tonight's last connection (LastConnection), asked as arrive by 4 a.m. */
    val lastConnection: Boolean = false,
    val routes: List<Itinerary> = emptyList(),
    val walkOnly: Itinerary? = null,
    /** Said above the routes: they start with a drive because no stop was within walking reach,
     *  or they were only found with a long walk to the stop (`noteIsWalk`). */
    val note: String? = null,
    val noteIsWalk: Boolean = false,
    val loading: Boolean = false,
    val error: String? = null,
    val searched: Boolean = false,
    val opened: Itinerary? = null,
    val nextCursor: String? = null,
    val previousCursor: String? = null,
    val options: io.github.elija7580.publit.data.RouteOptions = io.github.elija7580.publit.data.RouteOptions(),
    /** Walking, cycling and driving the whole way, for comparison. */
    val street: List<io.github.elija7580.publit.data.StreetComparison> = emptyList(),
    val badges: Map<String, List<io.github.elija7580.publit.data.SmartRouting.Badge>> = emptyMap(),
    /** The calendar event this search goes to (its title and "Leave by" above the routes). */
    val appointment: io.github.elija7580.publit.data.CalendarAppointment? = null,
) {
    /** The routes in the chosen order. */
    val sorted: List<Itinerary> get() = options.sort.apply(routes, arriveBy)
}

data class Recent(val from: Endpoint, val to: Endpoint.At) {
    val key get() = from.title + "|" + to.name
}

class PlanViewModel(
    private val client: TransitousClient,
    private val location: LocationRepository,
    private val log: CrashLog,
    private val prefs: android.content.SharedPreferences,
) : ViewModel() {
    private val _state = MutableStateFlow(PlanState(options = io.github.elija7580.publit.data.RouteOptions.load(prefs)))
    val state = _state.asStateFlow()
    private var search: Job? = null

    private val _matches = MutableStateFlow<List<GeocodeMatch>>(emptyList())
    val matches = _matches.asStateFlow()
    private var geocode: Job? = null
    var lastFix: LatLon? = null
        private set

    fun setFrom(e: Endpoint) { _state.update { it.copy(from = e) }; plan() }
    fun setTo(e: Endpoint) { _state.update { it.copy(to = e, appointment = null) }; plan() }
    /** Opens Plan with these endpoints (from a stop of a line: "Route here" / "Route from here"). */
    fun route(from: Endpoint, to: Endpoint?) { _state.update { it.copy(from = from, to = to, opened = null, appointment = null) }; plan() }

    /** A message in place of the routes (a calendar place that was not found). */
    fun showError(message: String) { _state.update { it.copy(loading = false, error = message, routes = emptyList(), searched = true) } }

    /** A place of an event is being looked up (one at a time). */
    val resolving = MutableStateFlow<String?>(null)

    /**
     * Routes from here to a calendar event, arriving a few minutes before it starts (CalendarRouting on iOS).
     * Without a pin the place is looked up now, and only now. Returns false when no place was found.
     */
    suspend fun routeTo(a: io.github.elija7580.publit.data.CalendarAppointment): Boolean {
        val point = a.coordinate ?: run {
            if (resolving.value != null) return true
            resolving.value = a.id
            try {
                client.geocode(a.searchText, lastFix, L10n.locale.language.ifEmpty { "en" }).firstOrNull()?.let { LatLon(it.lat, it.lon) }
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                log.note("calendar geocode error ${e.javaClass.simpleName}"); null
            } finally { resolving.value = null }
        } ?: return false
        _state.update {
            it.copy(from = Endpoint.Here, to = Endpoint.At(a.placeName, point), time = a.arriveBy, arriveBy = true,
                lastConnection = false, opened = null, appointment = a)
        }
        plan()
        return true
    }
    /** "Where to now?" from Nearby: Plan opens with the destination search. */
    val searchRequest = MutableStateFlow(0)
    fun requestSearch() { searchRequest.value++ }
    fun swap() { _state.update { s -> s.copy(from = s.to ?: Endpoint.Here, to = s.from, appointment = null) }; plan() }
    fun setTime(time: Instant?, arriveBy: Boolean) { _state.update { it.copy(time = time, arriveBy = arriveBy, lastConnection = false) }; plan() }
    /** "Last": the last connection tonight, from the same places. */
    fun setLastConnection() { _state.update { it.copy(time = null, arriveBy = false, lastConnection = true) }; plan() }
    fun setOptions(o: io.github.elija7580.publit.data.RouteOptions) {
        val old = _state.value.options
        io.github.elija7580.publit.data.RouteOptions.save(prefs, o)
        _state.update { it.copy(options = o) }
        // Only the order changed: the same routes, sorted again.
        if (old.copy(sort = o.sort) != o) plan()
    }
    fun open(it: Itinerary?) { _state.update { s -> s.copy(opened = it) } }

    /** A search for building a route leg by leg (#142, #143): from a point onward, with the own route options. */
    suspend fun planOnward(from: LatLon, to: LatLon, time: Instant, cursor: String? = null): io.github.elija7580.publit.data.PlanResponse {
        val o = _state.value.options
        return client.plan(from, to, time, false, o.maxWalkMinutes * 60, cursor, o)
    }

    /** A change no longer works: an earlier way that still catches the same connection (CatchEarlier). */
    suspend fun catchEarlier(route: Itinerary): io.github.elija7580.publit.data.CatchEarlier.Rescue? {
        val o = _state.value.options
        return io.github.elija7580.publit.data.CatchEarlier.search(route, { from, to, at ->
            client.plan(from.coordinate, to.coordinate, at, true, o.maxWalkMinutes * 60, options = o, fromStop = from.stopId).itineraries
        })
    }

    /** The whole trip of one vehicle, all its stops. */
    suspend fun trip(id: String): Itinerary = client.trip(id)

    /** Search as you type, 300 ms after the last key. */
    fun query(text: String) {
        geocode?.cancel()
        if (text.trim().length < 2) { _matches.value = emptyList(); return }
        geocode = viewModelScope.launch {
            delay(300)
            try {
                _matches.value = client.geocode(text.trim(), lastFix, L10n.locale.language.ifEmpty { "en" })
            } catch (e: CancellationException) { throw e } catch (e: Exception) { log.note("geocode error ${e.javaClass.simpleName}") }
        }
    }

    /** Street and number at a point picked on the map, null when unknown (ChooseOnMapView on iOS). */
    suspend fun addressAt(p: LatLon): String? = try {
        io.github.elija7580.publit.data.MapPick.label(client.reverseGeocode(p, L10n.locale.language.ifEmpty { "en" }))
    } catch (e: CancellationException) { throw e } catch (e: Exception) { log.note("reverse geocode error ${e.javaClass.simpleName}"); null }
    /** Where "Choose on map" opens: the last fix, else a fresh one if location is allowed. */
    suspend fun mapStart(): LatLon? = lastFix ?: withTimeoutOrNull(4_000) { here() }

    private suspend fun here(): LatLon? {
        if (location.access() == LocationAccess.NOT_ASKED) return null
        return withTimeoutOrNull(10_000) { location.fixes.firstOrNull()?.coordinate }?.also { lastFix = it }
    }

    private suspend fun point(e: Endpoint): LatLon? = when (e) { Endpoint.Here -> here(); is Endpoint.At -> e.point }

    /** Start/destination pairs searched before (RecentSearches on iOS): at most 20, newest first. */
    val recents = MutableStateFlow(loadRecents())

    private fun loadRecents(): List<Recent> = runCatching {
        val a = org.json.JSONArray(prefs.getString("publit.recents.v1", "[]"))
        (0 until a.length()).map { i -> a.getJSONObject(i).let { o ->
            Recent(o.optString("fromName").takeIf { n -> n.isNotEmpty() && o.has("fromLat") }?.let { n -> Endpoint.At(n, LatLon(o.getDouble("fromLat"), o.getDouble("fromLon"))) } ?: Endpoint.Here,
                Endpoint.At(o.getString("toName"), LatLon(o.getDouble("toLat"), o.getDouble("toLon")))) } }
    }.getOrDefault(emptyList())

    private fun saveRecents(list: List<Recent>) {
        val a = org.json.JSONArray()
        list.forEach { r -> a.put(org.json.JSONObject().apply {
            (r.from as? Endpoint.At)?.let { f -> put("fromName", f.name); put("fromLat", f.point.lat); put("fromLon", f.point.lon) }
            put("toName", r.to.name); put("toLat", r.to.point.lat); put("toLon", r.to.point.lon) }) }
        prefs.edit().putString("publit.recents.v1", a.toString()).apply()
        recents.value = list
    }

    private fun remember(from: Endpoint, to: Endpoint) {
        val t = to as? Endpoint.At ?: return
        val r = Recent(from, t)
        saveRecents((listOf(r) + recents.value.filter { it.key != r.key }).take(20))
    }

    fun removeRecent(r: Recent) = saveRecents(recents.value.filter { it.key != r.key })
    fun clearRecents() = saveRecents(emptyList())

    fun plan() {
        val s = _state.value
        val to = s.to ?: return
        remember(s.from, to)
        search?.cancel()
        // Last connection: searched backwards from the end of the service day.
        val qt = if (s.lastConnection) io.github.elija7580.publit.data.LastConnection.deadline() else s.time
        val qa = s.lastConnection || s.arriveBy
        pageTime = qt
        search = viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null, routes = emptyList(), note = null, noteIsWalk = false, walkOnly = null, searched = true, street = emptyList(), badges = emptyMap()) }
            try {
                val a = point(s.from)
                val b = point(to)
                if (a == null || b == null) {
                    _state.update { it.copy(loading = false, error = L10n.t("Location is off. Choose a start point, or allow location access in Settings.")) }
                    return@launch
                }
                // Longer walks only when nothing is found (as Reach.walkSteps on iOS).
                var found = emptyList<Itinerary>()
                var res: io.github.elija7580.publit.data.PlanResponse? = null
                val o = s.options
                val street = (if (s.lastConnection) emptyList() else o.comparisonModes).map { m -> async { runCatching { client.streetRoutes(a, b, qt, qa, m) }.getOrDefault(emptyList()) } }
                val firstWalk = o.maxWalkMinutes * 60
                pageWalk = firstWalk; pageOptions = o; pageNearest = null
                var note: String? = null
                // The routes were searched with a longer walk than the usual 30 min.
                var longWalk = false
                // Park & Ride: drive to a station near home (one or two searches, see ParkAndRide); when every
                // route drives most of the way, the usual routes are shown instead.
                val car = if (o.firstMile == io.github.elija7580.publit.data.FirstMile.CAR)
                    ParkAndRide.search(minOf(firstWalk, ParkAndRide.MAX_DRIVE_SECONDS)) { d -> client.plan(a, b, qt, qa, d, options = o) }
                else null
                if (car != null) {
                    res = car.response
                    found = io.github.elija7580.publit.data.SmartRouting.run { if (isEnabled) pruned(car.routes) else car.routes }
                        .filter { !it.isDetour }.distinctBy { it.routeKey }
                    pageWalk = car.drive; pageNearest = car.nearest
                } else {
                    val w = if (o.firstMile == io.github.elija7580.publit.data.FirstMile.CAR) o.copy(firstMile = io.github.elija7580.publit.data.FirstMile.WALK) else o
                    pageOptions = w
                    fun keep(list: List<Itinerary>) = io.github.elija7580.publit.data.SmartRouting.run { if (isEnabled) pruned(list) else list }
                        .filter { !it.isDetour }.distinctBy { it.routeKey }
                    val carOptions = w.copy(firstMile = io.github.elija7580.publit.data.FirstMile.CAR)
                    when (val f = ParkAndRide.walkingFirst(firstWalk, w.maxWalkMinutes != io.github.elija7580.publit.data.RouteOptions.DEFAULT_WALK,
                        walk = { walk -> val r = client.plan(a, b, qt, qa, walk, options = w); r to keep(r.itineraries) },
                        car = { ParkAndRide.search(ParkAndRide.MAX_DRIVE_SECONDS) { d -> client.plan(a, b, qt, qa, d, options = carOptions) } })) {
                        is ParkAndRide.Found.Walk -> {
                            res = f.response; found = f.routes; pageWalk = f.walk
                            longWalk = f.walk > io.github.elija7580.publit.data.RouteOptions.DEFAULT_WALK * 60
                        }
                        is ParkAndRide.Found.Car -> {
                            res = f.result.response; found = keep(f.result.routes)
                            pageWalk = f.result.drive; pageNearest = f.result.nearest; pageOptions = carOptions
                            note = ParkAndRide.fallbackNote
                        }
                    }
                }
                // Last connection: only rides that have not left yet.
                val fetched = found
                if (s.lastConnection) found = io.github.elija7580.publit.data.LastConnection.upcoming(found)
                // Walking joins the routes when it is a real choice (iOS PlanModel.merge, Publit#110): about as fast as
                // the fastest ride, short, or there before any ride door to door. Not for "Last": a walk is no connection.
                // With no ride at all the walk stays the quiet row below, as before.
                val listedWalk = if (s.lastConnection || found.isEmpty()) null
                    else io.github.elija7580.publit.data.StreetComparison.merge(found, res?.direct.orEmpty()).firstOrNull()?.takeIf { it.isDirect }
                if (listedWalk != null) found = listOf(listedWalk) + found
                // Found with a longer walk: say so, the walk is part of the decision (Reach.longWalkNote on iOS).
                val walkNote = if (longWalk) io.github.elija7580.publit.data.Reach.longWalkNote(found) else null
                if (walkNote != null) note = walkNote
                io.github.elija7580.publit.data.SmartRouting.offered = found
                if (io.github.elija7580.publit.data.SmartRouting.isEnabled) Thread { runCatching { io.github.elija7580.publit.data.DelayHistoryStore.refresh(found) } }.start()
                io.github.elija7580.publit.data.SmartRouting.offeredArriveBy = qa
                val best = found.filter { !it.isDirect }.minOfOrNull { it.duration }
                val comparisons = io.github.elija7580.publit.data.StreetComparison.from(res?.direct.orEmpty() + street.flatMap { it.await() })
                    // Walking appears once: in the list when it is a choice, otherwise in the comparison.
                    .filter { it.isRealistic(best) && !(it.mode == "WALK" && listedWalk != null) }
                log.note("plan: ${found.size} routes")
                _state.update {
                    it.copy(loading = false, routes = found, note = note, noteIsWalk = walkNote != null, nextCursor = res?.nextPageCursor, previousCursor = res?.previousPageCursor, street = comparisons,
                        badges = withLast(if (io.github.elija7580.publit.data.SmartRouting.isEnabled) io.github.elija7580.publit.data.SmartRouting.badges(found, qa) else emptyMap(), found, s.lastConnection),
                        walkOnly = if (s.lastConnection || listedWalk != null) null else res?.direct?.firstOrNull(),
                        error = if (s.lastConnection && found.isEmpty() && fetched.isNotEmpty()) L10n.t("The last connection there has already left. Choose another day.")
                        else if (found.isEmpty() && (s.lastConnection || res?.direct.isNullOrEmpty())) (if (o.ownTickets.isNotEmpty()) L10n.t("No connection is fully covered by your tickets. Change them in Route options to see all.") else L10n.t("No connections found.")) else null)
                }
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                log.note("plan error ${e.javaClass.simpleName}")
                _state.update { it.copy(loading = false, error = e.message ?: e.javaClass.simpleName) }
            }
        }
    }

    /** What the routes on screen were found with, so later pages search the same way. */
    private var pageWalk: Int? = null
    private var pageOptions: io.github.elija7580.publit.data.RouteOptions? = null
    private var pageNearest: Int? = null
    private var pageTime: Instant? = null

    /** The route that leaves last carries "Last connection", first on its line. */
    private fun withLast(badges: Map<String, List<io.github.elija7580.publit.data.SmartRouting.Badge>>, routes: List<Itinerary>, on: Boolean):
        Map<String, List<io.github.elija7580.publit.data.SmartRouting.Badge>> {
        if (!on) return badges
        val l = io.github.elija7580.publit.data.LastConnection.last(routes) ?: return badges
        val last = io.github.elija7580.publit.data.SmartRouting.Badge.LAST_CONNECTION
        return badges + (l.routeKey to (listOf(last) + badges[l.routeKey].orEmpty().filter { it != last }))
    }

    fun later() = page(true)
    fun earlier() = page(false)

    private fun page(next: Boolean) {
        val s = _state.value
        val cursor = (if (next) s.nextCursor else s.previousCursor) ?: return
        val to = s.to ?: return
        viewModelScope.launch {
            try {
                val a = point(s.from) ?: return@launch
                val b = point(to) ?: return@launch
                val o = pageOptions ?: s.options
                val res = client.plan(a, b, if (s.lastConnection) pageTime else s.time, s.lastConnection || s.arriveBy, pageWalk ?: (s.options.maxWalkMinutes * 60), cursor, o)
                _state.update { st ->
                    var more = res.itineraries.filter { !it.isDetour }
                    if (o.firstMile == io.github.elija7580.publit.data.FirstMile.CAR)
                        more = ParkAndRide.nearStations(ParkAndRide.sensible(more), pageNearest)
                    if (st.lastConnection) more = io.github.elija7580.publit.data.LastConnection.upcoming(more)
                    if (next) st.copy(routes = (st.routes + more).distinctBy { it.routeKey }, nextCursor = res.nextPageCursor)
                    else st.copy(routes = (more + st.routes).distinctBy { it.routeKey }, previousCursor = res.previousPageCursor)
                }
            } catch (e: CancellationException) { throw e } catch (_: Exception) {}
        }
    }
}
