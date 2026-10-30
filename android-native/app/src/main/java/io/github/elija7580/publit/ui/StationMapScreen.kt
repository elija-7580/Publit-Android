package io.github.elija7580.publit.ui

import androidx.compose.material.icons.filled.Close
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.background
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
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.material.icons.filled.Layers
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
fun StationMapScreen(client: TransitousClient, start: LatLon?, onBack: () -> Unit, onRoute: ((String, LatLon, Boolean) -> Unit)? = null,
                     onOpenStation: (id: String, name: String, exact: Boolean) -> Unit) {
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
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("publit", android.content.Context.MODE_PRIVATE) }
    var showStations by remember { mutableStateOf(prefs.getBoolean("mapStations", true)) }
    var showLines by remember { mutableStateOf(prefs.getBoolean("mapLines", true)) }
    var showVehicles by remember { mutableStateOf(prefs.getBoolean("mapVehicles", true)) }
    var showSharing by remember { mutableStateOf(prefs.getBoolean("mapSharing", false)) }
    var base by remember { mutableStateOf(prefs.getString("mapBase", "muted") ?: "muted") }
    var category by remember { mutableStateOf<io.github.elija7580.publit.data.PlaceCategory?>(null) }
    var categoryHits by remember { mutableStateOf(emptyList<io.github.elija7580.publit.data.CategoryPlace>()) }
    var categoryRegion by remember { mutableStateOf<MapRegion?>(null) }
    var categoryLoading by remember { mutableStateOf(false) }
    var categoryFailed by remember { mutableStateOf(false) }
    var categoryRequest by remember { mutableStateOf(0) }
    LaunchedEffect(category, categoryRequest) {
        val c = category ?: return@LaunchedEffect
        val r = region ?: return@LaunchedEffect
        categoryLoading = true; categoryFailed = false
        runCatching { io.github.elija7580.publit.data.OSMPlaces.places(r.center, r.latSpan, r.lonSpan, c) }
            .onSuccess { categoryHits = it; categoryRegion = r }.onFailure { categoryFailed = true }
        categoryLoading = false
    }
    var layers by remember { mutableStateOf(false) }
    var tracks by remember { mutableStateOf(emptyList<io.github.elija7580.publit.data.VehicleTrack>()) }
    var shapes by remember { mutableStateOf(emptyList<io.github.elija7580.publit.data.LineShape>()) }
    var liveHidden by remember { mutableStateOf(false) }
    var rentals by remember { mutableStateOf(io.github.elija7580.publit.data.RentalsResponse()) }
    var tick by remember { mutableStateOf(java.time.Instant.now()) }
    var selected by remember { mutableStateOf<String?>(null) }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    // Vehicles glide every 2 s and are fetched again every 30 s, only while the map is in front.
    LaunchedEffect(region, showLines, showVehicles, filter) {
        val r = region ?: return@LaunchedEffect
        lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.RESUMED) {
            while (true) {
                if (r.latSpan > 0.06 || !(showLines || showVehicles)) { liveHidden = r.latSpan > 0.06; tracks = emptyList(); shapes = emptyList() }
                else {
                    liveHidden = false
                    val dl = r.latSpan * 0.75; val dn = r.lonSpan * 0.75
                    val zoom = max(14, log2(360 / max(r.lonSpan, 0.0001)).roundToInt())
                    val now = java.time.Instant.now()
                    runCatching { client.mapTrips(LatLon(r.center.lat - dl, r.center.lon - dn), LatLon(r.center.lat + dl, r.center.lon + dn), zoom, now.minusSeconds(60), now.plusSeconds(300)) }
                        .onSuccess { segs ->
                            val snap = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                                io.github.elija7580.publit.data.LiveMap.build(segs, filter, if (zoom >= 14) 5 else 4, io.github.elija7580.publit.data.LiveMap.tolerance(r.latSpan * 111_000))
                            }
                            tracks = snap.tracks; shapes = snap.shapes
                        }
                }
                delay(30_000)
            }
        }
    }
    LaunchedEffect(Unit) { lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.RESUMED) { while (true) { tick = java.time.Instant.now(); delay(2_000) } } }
    LaunchedEffect(region, showSharing) {
        val r = region ?: return@LaunchedEffect
        if (!showSharing || r.latSpan > 0.03) { rentals = io.github.elija7580.publit.data.RentalsResponse(); return@LaunchedEffect }
        delay(300)
        val dl = r.latSpan * 0.75; val dn = r.lonSpan * 0.75
        runCatching { client.rentals(LatLon(r.center.lat - dl, r.center.lon - dn), LatLon(r.center.lat + dl, r.center.lon + dn)) }.onSuccess { rentals = it }
    }

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
    val vehicles = remember(tracks, tick, showVehicles, region) {
        val r = region
        if (!showVehicles || r == null) emptyList() else io.github.elija7580.publit.data.LiveMap.vehicles(tracks, tick,
            LatLon(r.center.lat - r.latSpan, r.center.lon - r.lonSpan), LatLon(r.center.lat + r.latSpan, r.center.lon + r.lonSpan))
    }
    val content = remember(stations, focus, ink.dark, shapes, showLines, vehicles, rentals, showStations, categoryHits, region?.center) {
        val categoryDots = categoryHits.mapIndexed { i, c -> MapContent.Dot(c.at, hexColor(c.category.colorHex), Color.White, 14f, 2f, id = "c|$i", label = c.name) }
        val lines = if (showLines) shapes.map { MapContent.Line(it.points, hexColor(io.github.elija7580.publit.data.LineColors.strokeHex(it.colorHex, ink.dark)), 3f) } else emptyList()
        val rentalDots = rentals.stations.map { MapContent.Dot(LatLon(it.lat, it.lon), Color(0xFF1B7A33), Color.White, 9f, 1.5f, id = "rs|" + it.id) } +
            rentals.vehicles.filter { it.isAvailable }.map { MapContent.Dot(LatLon(it.lat, it.lon), Color(0xFF00727A), Color.White, 7f, 1f, id = "rv|" + it.id) }
        val vehicleDots = vehicles.map { v -> MapContent.Dot(v.coordinate, hexColor(v.colorHex), Color.White, 13f, 2f, id = "v|" + v.id, label = v.name) }
        val dots = (if (showStations) stations else emptyList()).map { s ->
            val c = hexColor(ModeStyle.defaultHex(s.mainMode))
            MapContent.Dot(s.coordinate, if (s.isMajor) c else Color.White, if (s.isMajor) Color.White else c,
                if (s.isMajor) 14f else 10f, 2f, id = "s|" + s.id, label = if (s.isMajor || separate) MapStation.shortName(s.name) else null)
        } + rentalDots + vehicleDots + categoryDots + listOfNotNull(focus?.let { MapContent.Dot(LatLon(it.lat, it.lon), ink.ink, Color.White, 18f, 3f, label = it.name) })
        val gaps = region?.let { r -> io.github.elija7580.publit.data.CoverageAtlas.areas(r.center.lat - r.latSpan, r.center.lat + r.latSpan, r.center.lon - r.lonSpan, r.center.lon + r.lonSpan) }.orEmpty()
            .map { MapContent.Area(it.outline, it.level == io.github.elija7580.publit.data.CoverageLevel.NONE) }
        MapContent(lines = lines, dots = dots, areas = gaps)
    }

    Box(Modifier.fillMaxSize()) {
        PublitMap(content, camera, Modifier.fillMaxSize(), onRegion = { region = it }, base = base, onTap = { id ->
            when {
                id == null -> selected = null
                id.startsWith("s|") -> stations.firstOrNull { it.id == id.removePrefix("s|") }?.let { s -> onOpenStation(s.id, s.name, s.isPole) }
                else -> selected = id
            }
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
            val typed = io.github.elija7580.publit.data.PlaceCategory.matching(query)
            if (searching && (query.isBlank() || typed != null)) {
                Surface(shape = RoundedCornerShape(14.dp), color = ink.surface, shadowElevation = 3.dp) {
                    LazyColumn(Modifier.heightIn(max = 360.dp)) {
                        items(if (typed != null) listOf(typed) else io.github.elija7580.publit.data.PlaceCategory.entries.toList()) { c ->
                            Row(Modifier.fillMaxWidth().clickable { searching = false; query = ""; selected = null; category = c; categoryRequest++ }
                                .padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(14.dp).background(hexColor(c.colorHex), CircleShape)); Spacer(Modifier.width(12.dp)); Text(c.title)
                            }
                        }
                    }
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
                io.github.elija7580.publit.data.CoverageGuide.notice(region?.center)?.let { n -> Box(Modifier.align(Alignment.CenterHorizontally)) { CoverageMapNote(n) } }
                category?.let { c ->
                    val moved = categoryRegion?.let { a -> region?.let { b -> a.center.distanceTo(b.center) > 300 || kotlin.math.abs(a.latSpan - b.latSpan) > a.latSpan * 0.3 } } == true
                    Row(Modifier.align(Alignment.CenterHorizontally), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Surface(shape = RoundedCornerShape(50), color = ink.surface, shadowElevation = 2.dp) {
                            Row(Modifier.padding(start = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(12.dp).background(hexColor(c.colorHex), CircleShape)); Spacer(Modifier.width(6.dp))
                                Text(c.title, fontWeight = FontWeight.SemiBold, fontSize = 13.sp); Spacer(Modifier.width(6.dp))
                                Text(if (categoryLoading) "…" else if (categoryFailed) "!" else "${categoryHits.size}", color = ink.muted, fontSize = 13.sp)
                                IconButton(onClick = { category = null; categoryHits = emptyList(); selected = null }, modifier = Modifier.size(32.dp)) {
                                    Icon(androidx.compose.material.icons.Icons.Filled.Close, L10n.t("Close"), Modifier.size(16.dp))
                                }
                            }
                        }
                        if (moved || categoryFailed) Surface(onClick = { categoryRequest++ }, shape = RoundedCornerShape(50), color = ink.surface, shadowElevation = 2.dp) {
                            Text(L10n.t(if (categoryFailed) "Try again" else "Search this area"), fontWeight = FontWeight.SemiBold, fontSize = 13.sp,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
                        }
                    }
                }
                val hint = error ?: if (hidden && showStations) L10n.t("Zoom in to see stations") else if (liveHidden && (showLines || showVehicles)) L10n.t("Zoom in to see lines and vehicles") else null
                hint?.let {
                    Surface(shape = RoundedCornerShape(50), color = ink.surface, shadowElevation = 2.dp, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                        Text(it, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
                    }
                }
            }
        }
        selected?.let { sel ->
            Surface(Modifier.align(Alignment.BottomCenter).padding(12.dp).padding(bottom = 64.dp).fillMaxWidth(), shape = RoundedCornerShape(14.dp), color = ink.surface, shadowElevation = 4.dp) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    when {
                        sel.startsWith("v|") -> vehicles.firstOrNull { it.id == sel.removePrefix("v|") }?.let { v ->
                            Text(v.name, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                            Text(L10n.f("Next: %@", v.nextStop), color = ink.muted)
                            Text(if (!v.realTime) L10n.t("Position estimated from the timetable.") else if (v.delayMinutes > 0) L10n.f("+%lld min", v.delayMinutes) else L10n.t("On time"),
                                color = if (v.delayMinutes > 0) ink.delay else ink.muted, fontSize = 13.sp)
                        } ?: Text(L10n.t("Trip"), color = ink.muted)
                        sel.startsWith("c|") -> categoryHits.getOrNull(sel.removePrefix("c|").toIntOrNull() ?: -1)?.let { c ->
                            Text(c.name, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                            Text(listOfNotNull(c.category.title, c.street).joinToString(" · "), color = ink.muted)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                androidx.compose.material3.TextButton({ onRoute?.invoke(c.name, c.at, true) }) { Text(L10n.t("Route here"), color = ink.ink) }
                                androidx.compose.material3.TextButton({ onRoute?.invoke(c.name, c.at, false) }) { Text(L10n.t("Route from here"), color = ink.ink) }
                            }
                        }
                        sel.startsWith("rs|") -> rentals.stations.firstOrNull { it.id == sel.removePrefix("rs|") }?.let { st ->
                            Text(st.name, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                            Text(rentals.groupName(st.providerGroupId) + (st.numVehiclesAvailable?.let { " · $it" } ?: ""), color = ink.muted)
                            (st.rentalUriAndroid ?: st.rentalUriWeb)?.let { u -> androidx.compose.material3.TextButton({ runCatching { ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(u)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) } }) { Text(L10n.t("Open in the provider's app"), color = ink.ink) } }
                        }
                        sel.startsWith("rv|") -> rentals.vehicles.firstOrNull { it.id == sel.removePrefix("rv|") }?.let { v ->
                            Text(rentals.groupName(v.providerGroupId), fontWeight = FontWeight.Bold, fontSize = 17.sp)
                            (v.rentalUriAndroid ?: v.rentalUriWeb)?.let { u -> androidx.compose.material3.TextButton({ runCatching { ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(u)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) } }) { Text(L10n.t("Open in the provider's app"), color = ink.ink) } }
                        }
                    }
                }
            }
        }
        Surface(shape = CircleShape, color = ink.surface, shadowElevation = 3.dp,
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 12.dp, bottom = 88.dp)) {
            IconButton(onClick = { layers = !layers }) { Icon(androidx.compose.material.icons.Icons.Filled.Layers, L10n.t("Map layers")) }
        }
        if (layers) Surface(Modifier.align(Alignment.BottomEnd).padding(end = 64.dp, bottom = 28.dp).width(260.dp), shape = RoundedCornerShape(14.dp), color = ink.surface, shadowElevation = 4.dp) {
            Column(Modifier.padding(vertical = 8.dp)) {
                fun set(key: String, v: Boolean) = prefs.edit().putBoolean(key, v).apply()
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("muted" to "Transit", "standard" to "Standard", "satellite" to "Satellite").forEach { (k, t) ->
                        androidx.compose.material3.FilterChip(base == k, { base = k; prefs.edit().putString("mapBase", k).apply() }, { Text(L10n.t(t), fontSize = 12.sp) })
                    }
                }
                SwitchRow(L10n.t("Stations"), showStations) { showStations = it; set("mapStations", it) }
                SwitchRow(L10n.t("Lines"), showLines) { showLines = it; set("mapLines", it) }
                SwitchRow(L10n.t("Vehicles"), showVehicles) { showVehicles = it; set("mapVehicles", it) }
                SwitchRow(L10n.t("Bikes and scooters"), showSharing) { showSharing = it; set("mapSharing", it) }
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
