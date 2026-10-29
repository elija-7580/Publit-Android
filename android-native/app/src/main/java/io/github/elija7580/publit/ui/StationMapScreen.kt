package io.github.elija7580.publit.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.elija7580.publit.core.L10n
import io.github.elija7580.publit.core.LatLon
import io.github.elija7580.publit.data.GeocodeMatch
import io.github.elija7580.publit.data.MapStation
import io.github.elija7580.publit.data.MapStop
import io.github.elija7580.publit.data.ModeFilter
import io.github.elija7580.publit.data.ModeGroup
import io.github.elija7580.publit.data.ModeStyle
import io.github.elija7580.publit.data.TransitousClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Spans (degrees latitude) above which stations are not loaded: too much to show or download. */
private const val MAX_STATION_SPAN = 0.08

/**
 * The station map (StationMapView on iOS): stations around you, mode filter, place search.
 * Tap a station for its departures. Live vehicles, line shapes and sharing follow later.
 */
@Composable
fun StationMapScreen(client: TransitousClient, start: LatLon?, onBack: () -> Unit, onOpenStation: (id: String, name: String, exact: Boolean) -> Unit) {
    val ink = LocalInk.current
    var region by remember { mutableStateOf<MapRegion?>(null) }
    var raw by remember { mutableStateOf(emptyList<MapStop>()) }
    var hidden by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var filter by remember { mutableStateOf(ModeFilter()) }
    val initial = start ?: LatLon(48.2082, 16.3738)
    var camera by remember { mutableStateOf<MapCamera>(MapCamera.Around(initial, 1200.0)) }
    var query by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf(emptyList<GeocodeMatch>()) }
    var focus by remember { mutableStateOf<GeocodeMatch?>(null) }

    LaunchedEffect(region) {
        val r = region ?: return@LaunchedEffect
        delay(250)
        if (r.latSpan > MAX_STATION_SPAN) { hidden = true; raw = emptyList(); return@LaunchedEffect }
        hidden = false
        val zoom = max(14, min(16, log2(360 / max(r.lonSpan, 0.0001)).roundToInt()))
        val dl = r.latSpan * 0.8; val dn = r.lonSpan * 0.8
        try {
            raw = client.mapStops(LatLon(r.center.lat - dl, r.center.lon - dn), LatLon(r.center.lat + dl, r.center.lon + dn), zoom)
            error = null
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            error = L10n.t("Stations could not be loaded.")
        }
    }
    LaunchedEffect(query) {
        val text = query.trim()
        if (text.length < 2) { results = emptyList(); return@LaunchedEffect }
        delay(300)
        results = runCatching { client.geocode(text, region?.center ?: start, L10n.locale.language.ifEmpty { "en" }) }.getOrDefault(emptyList())
    }

    val separate = (region?.latSpan ?: 1.0) <= MapStation.POLE_SPAN
    val stations = remember(raw, separate, filter) {
        MapStation.group(raw, separate).filter { s -> !filter.isActive || s.modes.any { filter.allows(it) } }
    }
    val content = remember(stations, focus, ink.dark) {
        val dots = stations.map { s ->
            val c = hexColor(ModeStyle.defaultHex(s.mainMode))
            MapContent.Dot(s.coordinate, if (s.isMajor) c else Color.White, if (s.isMajor) Color.White else c,
                if (s.isMajor) 14f else 10f, 2f, id = "s|" + s.id, label = if (s.isMajor || separate) MapStation.shortName(s.name) else null)
        } + listOfNotNull(focus?.let { MapContent.Dot(LatLon(it.lat, it.lon), ink.ink, Color.White, 18f, 3f, label = it.name) })
        MapContent(dots = dots)
    }

    Box(Modifier.fillMaxSize()) {
        PublitMap(content, camera, Modifier.fillMaxSize(), onRegion = { region = it }, onTap = { id ->
            val s = id?.removePrefix("s|")?.let { sid -> stations.firstOrNull { it.id == sid } }
            if (s != null) onOpenStation(s.id, s.name, s.isPole)
        })
        Column(Modifier.statusBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Surface(shape = RoundedCornerShape(14.dp), color = ink.surface, shadowElevation = 3.dp) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { if (searching) { searching = false; query = "" } else onBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, L10n.t("Back"))
                    }
                    TextField(query, { query = it; searching = true }, Modifier.weight(1f), singleLine = true,
                        placeholder = { Text(L10n.t("Search places, sights and stops"), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        leadingIcon = { Icon(Icons.Filled.Search, null) },
                        colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent))
                }
            }
            if (searching && results.isNotEmpty()) {
                Surface(shape = RoundedCornerShape(14.dp), color = ink.surface, shadowElevation = 3.dp) {
                    LazyColumn {
                        items(results, key = { it.id + it.name }) { m ->
                            Row(Modifier.fillMaxWidth().clickable {
                                searching = false; query = ""
                                if (m.isStop) { onOpenStation(m.id, m.name, false) } else {
                                    focus = m
                                    camera = MapCamera.Around(LatLon(m.lat, m.lon), if (m.type == "ADDRESS") 450.0 else 900.0)
                                }
                            }.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(if (m.isStop) modeIcon("BUS") else Icons.Filled.Place, null, tint = ink.muted, modifier = Modifier.size(20.dp))
                                Spacer(Modifier.width(12.dp))
                                Column {
                                    Text(m.name, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    if (m.subtitle.isNotEmpty()) Text(m.subtitle, fontSize = 12.sp, color = ink.muted, maxLines = 1)
                                }
                            }
                            HorizontalDivider(color = ink.hairline)
                        }
                    }
                }
            } else {
                ModeChips(ModeGroup.entries.filter { it != ModeGroup.OTHER }, filter.included, filter.isActive) { filter = filter.toggleOnly(it) }
                val hint = error ?: if (hidden) L10n.t("Zoom in to see stations") else null
                hint?.let {
                    Surface(shape = RoundedCornerShape(50), color = ink.surface, shadowElevation = 2.dp, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                        Text(it, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
                    }
                }
            }
        }
        if (start != null) Surface(shape = CircleShape, color = ink.surface, shadowElevation = 3.dp,
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 12.dp, bottom = 28.dp)) {
            IconButton(onClick = { focus = null; camera = MapCamera.Around(start, 1200.0 + (System.nanoTime() % 7)) }) {
                Icon(Icons.Filled.MyLocation, L10n.t("My location"))
            }
        }
    }
}
