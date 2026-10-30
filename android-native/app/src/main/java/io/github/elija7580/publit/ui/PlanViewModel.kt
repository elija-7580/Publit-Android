package io.github.elija7580.publit.ui

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
    val routes: List<Itinerary> = emptyList(),
    val walkOnly: Itinerary? = null,
    val loading: Boolean = false,
    val error: String? = null,
    val searched: Boolean = false,
    val opened: Itinerary? = null,
    val nextCursor: String? = null,
    val options: io.github.elija7580.publit.data.RouteOptions = io.github.elija7580.publit.data.RouteOptions(),
    /** Walking, cycling and driving the whole way, for comparison. */
    val street: List<io.github.elija7580.publit.data.StreetComparison> = emptyList(),
    val badges: Map<String, List<io.github.elija7580.publit.data.SmartRouting.Badge>> = emptyMap(),
) {
    /** The routes in the chosen order. */
    val sorted: List<Itinerary> get() = options.sort.apply(routes, arriveBy)
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
    private var lastFix: LatLon? = null

    fun setFrom(e: Endpoint) { _state.update { it.copy(from = e) }; plan() }
    fun setTo(e: Endpoint) { _state.update { it.copy(to = e) }; plan() }
    /** Opens Plan with these endpoints (from a stop of a line: "Route here" / "Route from here"). */
    fun route(from: Endpoint, to: Endpoint?) { _state.update { it.copy(from = from, to = to, opened = null) }; plan() }
    /** "Where to now?" from Nearby: Plan opens with the destination search. */
    val searchRequest = MutableStateFlow(0)
    fun requestSearch() { searchRequest.value++ }
    fun swap() { _state.update { s -> s.copy(from = s.to ?: Endpoint.Here, to = s.from) }; plan() }
    fun setTime(time: Instant?, arriveBy: Boolean) { _state.update { it.copy(time = time, arriveBy = arriveBy) }; plan() }
    fun setOptions(o: io.github.elija7580.publit.data.RouteOptions) {
        val old = _state.value.options
        io.github.elija7580.publit.data.RouteOptions.save(prefs, o)
        _state.update { it.copy(options = o) }
        // Only the order changed: the same routes, sorted again.
        if (old.copy(sort = o.sort) != o) plan()
    }
    fun open(it: Itinerary?) { _state.update { s -> s.copy(opened = it) } }

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

    private suspend fun here(): LatLon? {
        if (location.access() == LocationAccess.NOT_ASKED) return null
        return withTimeoutOrNull(10_000) { location.fixes.firstOrNull()?.coordinate }?.also { lastFix = it }
    }

    private suspend fun point(e: Endpoint): LatLon? = when (e) { Endpoint.Here -> here(); is Endpoint.At -> e.point }

    fun plan() {
        val s = _state.value
        val to = s.to ?: return
        search?.cancel()
        search = viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null, routes = emptyList(), walkOnly = null, searched = true, street = emptyList(), badges = emptyMap()) }
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
                val street = o.comparisonModes.map { m -> async { runCatching { client.streetRoutes(a, b, s.time, s.arriveBy, m) }.getOrDefault(emptyList()) } }
                val firstWalk = o.maxWalkMinutes * 60
                for (walk in listOf(firstWalk, maxOf(firstWalk, 2700), maxOf(firstWalk, 7200)).distinct()) {
                    res = client.plan(a, b, s.time, s.arriveBy, walk, options = o)
                    found = io.github.elija7580.publit.data.SmartRouting.run { if (isEnabled) pruned(res.itineraries) else res.itineraries }
                        .filter { !it.isDetour }.distinctBy { it.routeKey }
                    if (found.isNotEmpty()) break
                }
                io.github.elija7580.publit.data.SmartRouting.offered = found
                io.github.elija7580.publit.data.SmartRouting.offeredArriveBy = s.arriveBy
                val best = found.filter { !it.isDirect }.minOfOrNull { it.duration }
                val comparisons = io.github.elija7580.publit.data.StreetComparison.from(res?.direct.orEmpty() + street.flatMap { it.await() })
                    .filter { it.isRealistic(best) }
                log.note("plan: ${found.size} routes")
                _state.update {
                    it.copy(loading = false, routes = found, walkOnly = res?.direct?.firstOrNull(), nextCursor = res?.nextPageCursor, street = comparisons,
                        badges = if (io.github.elija7580.publit.data.SmartRouting.isEnabled) io.github.elija7580.publit.data.SmartRouting.badges(found, s.arriveBy) else emptyMap(),
                        error = if (found.isEmpty() && res?.direct.isNullOrEmpty()) L10n.t("No connections found.") else null)
                }
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                log.note("plan error ${e.javaClass.simpleName}")
                _state.update { it.copy(loading = false, error = e.message ?: e.javaClass.simpleName) }
            }
        }
    }

    fun later() {
        val s = _state.value
        val cursor = s.nextCursor ?: return
        val to = s.to ?: return
        viewModelScope.launch {
            try {
                val a = point(s.from) ?: return@launch
                val b = point(to) ?: return@launch
                val res = client.plan(a, b, s.time, s.arriveBy, s.options.maxWalkMinutes * 60, cursor, s.options)
                _state.update { st ->
                    st.copy(routes = (st.routes + res.itineraries.filter { !it.isDetour }).distinctBy { it.routeKey }, nextCursor = res.nextPageCursor)
                }
            } catch (e: CancellationException) { throw e } catch (_: Exception) {}
        }
    }
}
