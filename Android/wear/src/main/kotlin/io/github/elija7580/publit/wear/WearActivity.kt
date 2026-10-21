package io.github.elija7580.publit.wear

import android.Manifest
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.lifecycleScope
import androidx.wear.compose.material.*
import kotlinx.coroutines.*
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.Normalizer
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.math.ceil

/** Standalone Wear OS counterpart of WatchNearbyView. No phone or Google account required. */
class WearActivity : ComponentActivity() {
    private var groups by mutableStateOf<List<StopGroup>>(emptyList())
    private var status by mutableStateOf(R.string.waiting_location)
    private var busy by mutableStateOf(false)
    private var updated by mutableStateOf<Instant?>(null)
    private var job: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colors = Colors(primary = Color.White, onPrimary = Color.Black)) {
                val list = rememberScalingLazyListState()
                var now by remember { mutableStateOf(Instant.now()) }
                LaunchedEffect(Unit) { while (true) { now = Instant.now(); delay(30_000) } }
                val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refresh() }
                fun request() {
                    if (hasLocation()) refresh() else permission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                }
                LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { refresh() }
                Scaffold(timeText = { TimeText() }, positionIndicator = { PositionIndicator(list) }, vignette = { Vignette(vignettePosition = VignettePosition.TopAndBottom) }) {
                    ScalingLazyColumn(state = list, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 32.dp)) {
                        item { Text(stringResource(R.string.nearby), fontWeight = FontWeight.Bold, textAlign = TextAlign.Center) }
                        if (busy) item { CircularProgressIndicator(modifier = Modifier.size(26.dp)) }
                        if (groups.isEmpty() && !busy) {
                            item { Text(stringResource(status), textAlign = TextAlign.Center, modifier = Modifier.padding(8.dp)) }
                        }
                        val far = groups.minOfOrNull { it.distance }
                        if (far != null && far >= 1500) item {
                            Text(stringResource(R.string.far_note, distance(far), ceil(far / 80).toLong()), color = Color(0xFFEAC26B), textAlign = TextAlign.Center, fontSize = 12.sp)
                        }
                        groups.forEach { group ->
                            item {
                                Text(group.name + " · " + distance(group.distance), color = Color.LightGray, fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp))
                            }
                            group.departures.filter { it.time.isAfter(now.minusSeconds(60)) }.take(4).forEach { departure ->
                                item { Departure(departure, now) }
                            }
                        }
                        item { Chip(onClick = { request() }, enabled = !busy, label = { Text(stringResource(if (hasLocation()) R.string.refresh else R.string.allow_location)) }, modifier = Modifier.fillMaxWidth()) }
                        updated?.let { time -> item { Text(stringResource(R.string.updated, clock(time)), fontSize = 11.sp, color = Color.Gray) } }
                    }
                }
            }
        }
    }

    private fun hasLocation() = checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED || checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun refresh() {
        if (job?.isActive == true) return
        job = lifecycleScope.launch {
            if (!hasLocation()) { status = R.string.location_off; return@launch }
            busy = true
            try {
                status = R.string.waiting_location
                val here = locate()
                if (here == null) { status = R.string.location_off; return@launch }
                val result = withContext(Dispatchers.IO) {
                    var stops = emptyList<Departure>()
                    for (radius in listOf(500, 1500, 5000)) {
                        stops = fetch(here, radius)
                        if (stops.isNotEmpty()) break
                    }
                    group(stops, here)
                }
                groups = result
                status = R.string.no_departures
                updated = Instant.now()
            } catch (cancelled: CancellationException) { throw cancelled }
              catch (_: Exception) { status = R.string.try_again }
            finally { busy = false }
        }
    }

    @Suppress("MissingPermission")
    private suspend fun locate(): Location? = withTimeoutOrNull(20_000) {
        val manager = getSystemService(LocationManager::class.java)
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).filter { manager.isProviderEnabled(it) }
        val cached = providers.mapNotNull { manager.getLastKnownLocation(it) }.maxByOrNull { it.elapsedRealtimeNanos }
        if (cached != null && (android.os.SystemClock.elapsedRealtimeNanos() - cached.elapsedRealtimeNanos) < 300_000_000_000L) return@withTimeoutOrNull cached
        if (providers.isEmpty()) return@withTimeoutOrNull null
        suspendCancellableCoroutine { cont ->
            val listener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    manager.removeUpdates(this)
                    if (cont.isActive) cont.resume(location)
                }
            }
            cont.invokeOnCancellation { manager.removeUpdates(listener) }
            for (provider in providers) manager.requestLocationUpdates(provider, 0L, 0f, listener, Looper.getMainLooper())
        }
    }
}

private data class Departure(val stopId: String, val name: String, val lat: Double, val lon: Double, val mode: String, val line: String, val direction: String, val time: Instant, val zone: String, val delay: Long, val cancelled: Boolean, val live: Boolean, val trip: String)
private data class StopGroup(val name: String, val lat: Double, val lon: Double, val pole: Boolean, val ids: MutableSet<String>, val distance: Float, val departures: MutableList<Departure>)
private val street = setOf("BUS", "COACH", "TRAM", "TROLLEYBUS", "CABLE_CAR")

private fun fetch(here: Location, radius: Int): List<Departure> {
    val connection = URL("https://api.transitous.org/api/v6/stoptimes?center=${here.latitude},${here.longitude}&radius=$radius&n=40&withAlerts=true").openConnection() as HttpURLConnection
    connection.connectTimeout = 15_000; connection.readTimeout = 20_000
    connection.setRequestProperty("User-Agent", "Publit/0.6.0 (WearOS; +https://github.com/elija-7580/Publit-Android)")
    try {
        check(connection.responseCode == 200)
        val times = JSONObject(connection.inputStream.bufferedReader().use { it.readText() }).getJSONArray("stopTimes")
        return (0 until times.length()).mapNotNull { index ->
            runCatching {
                val row = times.getJSONObject(index); val p = row.getJSONObject("place")
                val time = Instant.parse(p.optString("departure").ifBlank { p.getString("arrival") })
                val scheduled = runCatching { Instant.parse(p.optString("scheduledDeparture").ifBlank { p.getString("scheduledArrival") }) }.getOrDefault(time)
                Departure(p.optString("stopId", p.getString("name")), p.getString("name"), p.getDouble("lat"), p.getDouble("lon"), row.optString("mode"), row.optString("routeShortName").ifBlank { row.optString("displayName") }, row.optString("headsign"), time, p.optString("tz", "UTC"), (time.epochSecond - scheduled.epochSecond) / 60, row.optBoolean("cancelled") || row.optBoolean("tripCancelled") || p.optBoolean("cancelled"), row.optBoolean("realTime"), row.optString("tripId"))
            }.getOrNull()
        }.filter { it.time.isAfter(Instant.now().minusSeconds(60)) }.distinctBy { "${it.trip}|${it.stopId}|${it.time}" }.sortedBy { it.time }
    } finally { connection.disconnect() }
}
private fun normalized(name: String) = Normalizer.normalize(name.replace('ı', 'i').replace('İ', 'I'), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").trim().lowercase(Locale.ROOT)
private fun meters(a: Double, b: Double, c: Double, d: Double): Float { val result = FloatArray(1); Location.distanceBetween(a,b,c,d,result); return result[0] }
private fun group(rows: List<Departure>, here: Location): List<StopGroup> {
    val rail = rows.filter { it.mode !in street }.map { it.stopId }.toSet()
    val groups = mutableListOf<StopGroup>()
    for (row in rows) {
        val pole = row.stopId !in rail
        val existing = groups.firstOrNull { it.pole == pole && normalized(it.name) == normalized(row.name) && ((pole && row.stopId in it.ids) || meters(it.lat,it.lon,row.lat,row.lon) <= if (pole) 8 else 300) }
        if (existing != null) { existing.ids.add(row.stopId); existing.departures.add(row) }
        else groups.add(StopGroup(row.name,row.lat,row.lon,pole,mutableSetOf(row.stopId),meters(here.latitude,here.longitude,row.lat,row.lon), mutableListOf(row)))
    }
    return groups.sortedBy { it.distance }.take(6)
}
private fun distance(meters: Float) = if (meters < 1000) "${(meters / 10).toInt() * 10} m" else String.format(Locale.getDefault(), "%.1f km", meters / 1000)
private fun clock(time: Instant, zone: String = ZoneId.systemDefault().id): String = DateTimeFormatter.ofPattern("HH:mm").withZone(runCatching { ZoneId.of(zone) }.getOrDefault(ZoneId.systemDefault())).format(time)

@Composable private fun Departure(row: Departure, now: Instant) {
    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(row.line, fontWeight = FontWeight.Bold, maxLines = 1)
            Text(row.direction, fontSize = 12.sp, maxLines = 2, color = Color.LightGray)
        }
        Spacer(Modifier.width(4.dp))
        Column(horizontalAlignment = Alignment.End) {
            val minutes = ceil((row.time.epochSecond - now.epochSecond) / 60.0).toInt()
            val label = if (row.cancelled || minutes >= 60) clock(row.time,row.zone) else if (minutes <= 0) stringResource(R.string.now) else "$minutes ${stringResource(R.string.min)}"
            Text(label, fontWeight = FontWeight.Bold, color = if (row.cancelled) Color(0xFFE97366) else if (row.delay > 0) Color(0xFFEAC26B) else Color.White, textDecoration = if (row.cancelled) TextDecoration.LineThrough else null)
            if (row.cancelled) Text(stringResource(R.string.cancelled), fontSize = 10.sp, color = Color(0xFFE97366))
            else if (row.live) Text("● " + stringResource(R.string.live), fontSize = 10.sp, color = Color(0xFF72BC8F))
        }
    }
}
