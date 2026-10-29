package io.github.elija7580.publit.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.elija7580.publit.CrashLog
import io.github.elija7580.publit.core.LatLon
import io.github.elija7580.publit.data.ModeFilter
import io.github.elija7580.publit.data.ModeGroup
import io.github.elija7580.publit.data.Reach
import io.github.elija7580.publit.data.StopGroup
import io.github.elija7580.publit.data.StopGrouping
import io.github.elija7580.publit.data.StopTime
import io.github.elija7580.publit.data.TransitousClient
import io.github.elija7580.publit.location.Fix
import io.github.elija7580.publit.location.LocationAccess
import io.github.elija7580.publit.location.LocationRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant

data class NearbyState(
    val access: LocationAccess = LocationAccess.NOT_ASKED,
    val servicesOn: Boolean = true,
    /** Waiting for the first position. */
    val locating: Boolean = false,
    val fix: Fix? = null,
    val groups: List<StopGroup> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    val farDistance: Double? = null,
    val filter: ModeFilter = ModeFilter(),
    val seen: Set<ModeGroup> = emptySet(),
    /** Seconds since start without a position: the screen offers help after a while. */
    val locatingSeconds: Int = 0,
) {
    val available: List<ModeGroup> get() = ModeGroup.entries.filter { it in seen || it in filter.included }
}

/**
 * The Nearby board as NearbyView in the iOS app: position → departures within 500 m, 1.5 km, 5 km
 * (wider until something runs) → grouped by station. Position and board refresh only while the
 * screen is visible (`setActive`).
 */
class NearbyViewModel(
    private val client: TransitousClient,
    private val location: LocationRepository,
    private val log: CrashLog,
    private val prefs: android.content.SharedPreferences? = null,
) : ViewModel() {
    private val _state = MutableStateFlow(NearbyState(filter = ModeFilter.fromStorage(prefs?.getString("nearbyModes", "") ?: "")))
    val state: StateFlow<NearbyState> = _state.asStateFlow()

    private var tracking: Job? = null
    private var loop: Job? = null
    private var lastStopTimes: List<StopTime> = emptyList()
    private var loadedCenter: LatLon? = null
    private var loadedAt = 0L
    private var loadedModes: String? = null

    /** Call when the screen appears/disappears or the permission changes. */
    fun setActive(active: Boolean) {
        log.note("nearby active=$active")
        if (!active) { tracking?.cancel(); loop?.cancel(); tracking = null; loop = null; return }
        refreshAccess()
        if (_state.value.access == LocationAccess.NOT_ASKED || _state.value.access == LocationAccess.DENIED) return
        if (tracking == null) startTracking()
        if (loop == null) loop = viewModelScope.launch { showLastBoard(); runLoop() }
    }

    fun refreshAccess() {
        val a = location.access()
        _state.update { it.copy(access = a, servicesOn = location.servicesOn()) }
    }

    fun permissionResult() {
        refreshAccess()
        log.note("permission result: ${_state.value.access}")
        tracking?.cancel(); loop?.cancel(); tracking = null; loop = null
        setActive(true)
    }

    private fun startTracking() {
        _state.update { it.copy(locating = it.fix == null) }
        tracking = viewModelScope.launch {
            val timer = launch {
                while (_state.value.fix == null) {
                    delay(1000)
                    _state.update { it.copy(locatingSeconds = it.locatingSeconds + 1, servicesOn = location.servicesOn()) }
                }
            }
            try {
                location.fixes.collect { fix ->
                    log.note("fix ${fix.source} ±${fix.accuracy.toInt()} m")
                    _state.update { it.copy(fix = fix, locating = false, locatingSeconds = 0) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.note("location error: ${e.javaClass.simpleName}")
            } finally {
                timer.cancel()
            }
        }
    }

    fun setFilter(filter: ModeFilter) {
        prefs?.edit()?.putString("nearbyModes", filter.storageValue)?.apply()
        _state.update { it.copy(filter = filter) }
        regroup()
        viewModelScope.launch { load(force = true) }
    }

    fun refresh() { viewModelScope.launch { load(force = true) } }

    private suspend fun showLastBoard() {
        if (_state.value.groups.isNotEmpty()) return
        val modes = _state.value.filter.apiValue
        val (res, center) = client.lastNearby(1800, modes) ?: return
        lastStopTimes = res.stopTimes
        val here = _state.value.fix?.coordinate ?: center
        if (here.distanceTo(center) > 400) return
        _state.update { it.copy(seen = ModeGroup.present(res.stopTimes.map { s -> s.mode }).toSet()) }
        regroup(here)
    }

    private fun regroup(around: LatLon? = _state.value.fix?.coordinate) {
        val filter = _state.value.filter
        val grouped = StopGrouping.group(StopTime.upcoming(lastStopTimes).filter { filter.allows(it.mode) }, around)
        _state.update { it.copy(groups = grouped, farDistance = far(grouped)) }
    }

    private fun far(groups: List<StopGroup>): Double? {
        val nearest = groups.mapNotNull { it.distance }.minOrNull() ?: return null
        return nearest.takeIf { it >= Reach.FAR_DISTANCE }
    }

    private suspend fun runLoop() {
        while (true) {
            load()
            delay(if (_state.value.fix != null) 5_000 else 500)
        }
    }

    private suspend fun load(force: Boolean = false) {
        val here = _state.value.fix?.coordinate ?: return
        val modes = _state.value.filter.apiValue
        if (_state.value.loading && !force) return
        val c = loadedCenter
        if (!force && loadedModes == modes && c != null && here.distanceTo(c) < RELOAD_DISTANCE &&
            System.currentTimeMillis() - loadedAt < REFRESH_MS) return
        _state.update { it.copy(loading = true) }
        try {
            var res = io.github.elija7580.publit.data.StopTimesResponse()
            var radius = 0
            for (r in Reach.nearbyRadii) {
                if (res.stopTimes.isNotEmpty()) break
                radius = r
                res = client.departures(here, r, 40, modes)
            }
            lastStopTimes = res.stopTimes
            val present = ModeGroup.present(res.stopTimes.map { it.mode }).toSet()
            val filter = _state.value.filter
            val grouped = withContext(Dispatchers.Default) {
                StopGrouping.group(StopTime.upcoming(res.stopTimes, Instant.now()).filter { filter.allows(it.mode) }, here)
            }
            log.note("nearby loaded: ${grouped.size} stops, ${res.stopTimes.size} departures, radius $radius m")
            _state.update {
                it.copy(groups = grouped, farDistance = far(grouped), error = null,
                    seen = if (filter.isActive) it.seen + present else present)
            }
            loadedCenter = here; loadedModes = modes; loadedAt = System.currentTimeMillis()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.note("nearby error: ${e.javaClass.simpleName}")
            _state.update { it.copy(error = e.message ?: e.javaClass.simpleName) }
            // Try again soon, not after the full refresh interval.
            loadedAt = System.currentTimeMillis() - REFRESH_MS + 10_000
        } finally {
            _state.update { it.copy(loading = false) }
        }
    }

    companion object {
        const val RELOAD_DISTANCE = 150.0
        const val REFRESH_MS = 30_000L
    }
}
