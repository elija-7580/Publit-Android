package io.github.elija7580.publit.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import io.github.elija7580.publit.core.Fmt
import io.github.elija7580.publit.core.L10n
import io.github.elija7580.publit.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** One direction of a line at its stop: departures grouped by where they go. */
private data class LineDirection(val id: String, val headsign: String, val sampleTripId: String)

private fun directionId(st: StopTime) = st.directionName?.let(TransitLabels::identity) ?: st.headsign

/**
 * A line (LineDetailView on iOS): direction, the route on a map with estimated vehicle positions,
 * its stops (tap one for its timetable), the next departures here, save the line.
 */
@Composable
fun LineDetailScreen(client: TransitousClient, favorites: FavoritesStore, line: SavedLine, onBack: () -> Unit, onOpenTrip: (StopTime) -> Unit) {
    val ink = LocalInk.current
    var departures by remember { mutableStateOf(emptyList<StopTime>()) }
    var error by remember { mutableStateOf<String?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var directionKey by remember { mutableStateOf<String?>(null) }
    val legs = remember { mutableStateMapOf<String, Leg>() }
    var timetableStop by remember { mutableStateOf<Place?>(null) }
    val now by produceState(Instant.now()) { while (true) { value = Instant.now(); delay(5_000) } }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(line.id) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                try {
                    departures = StopTime.upcoming(client.departures(line.stopId, 100, 150).stopTimes).filter(line::matches)
                    error = null
                } catch (e: CancellationException) { throw e } catch (e: Exception) { error = e.message ?: e.javaClass.simpleName }
                loaded = true
                delay(30_000)
            }
        }
    }
    val directions = remember(departures) {
        departures.groupBy(::directionId).map { (k, l) -> LineDirection(k, l.first().directionName ?: l.first().headsign, l.first().tripId) }
    }
    val direction = directions.firstOrNull { it.id == directionKey } ?: directions.firstOrNull()
    LaunchedEffect(direction?.id) {
        val d = direction ?: return@LaunchedEffect
        if (legs[d.id] != null) return@LaunchedEffect
        runCatching { client.trip(d.sampleTripId).legs.firstOrNull { it.isTransit } }.getOrNull()?.let { legs[d.id] = it }
    }
    val leg = direction?.let { legs[it.id] }
    timetableStop?.let { s ->
        androidx.activity.compose.BackHandler { timetableStop = null }
        LineTimetableScreen(client, line, s, direction?.headsign, direction?.id) { timetableStop = null }
        return
    }
    val saved by favorites.state.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, L10n.t("Back")) }
            Text(line.lineName, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, modifier = Modifier.weight(1f))
            val isSaved = saved.containsLine(line.id)
            IconButton(onClick = { favorites.toggle(line) }) {
                Icon(if (isSaved) Icons.Filled.Star else Icons.Filled.StarBorder, L10n.t(if (isSaved) "Saved line" else "Save line"))
            }
        }
        LazyColumn(contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item("head") {
                Card {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            LineBadge(line); Spacer(Modifier.width(8.dp))
                            Text(L10n.f("At %@", line.stopName), color = ink.muted, fontSize = 14.sp)
                        }
                        if (directions.size > 1) {
                            Text(L10n.t("Direction"), color = ink.muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            directions.take(4).forEach { d ->
                                FilterChip(selected = d.id == direction?.id, onClick = { directionKey = d.id }, label = { Text("→ " + d.headsign, maxLines = 1) })
                            }
                        } else direction?.let { d -> Text(L10n.f("Towards %@", d.headsign), fontWeight = FontWeight.SemiBold) }
                    }
                }
            }
            val alerts = departures.flatMap { it.serviceAlerts }.distinctBy { it.headerText }
            if (alerts.isNotEmpty()) item("alerts") {
                Card { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    alerts.take(3).forEach { a -> Text(a.headerText, color = ink.delay, fontWeight = FontWeight.SemiBold, fontSize = 14.sp) }
                } }
            }
            when {
                leg != null -> {
                    val stops = leg.allStops
                    item("map") {
                        val mine = departures.filter { directionId(it) == direction?.id }
                        val vehicles = estimateVehicles(leg, line, mine, now)
                        val color = lineStroke(line, ink.dark)
                        val content = remember(leg.tripId, vehicles, ink.dark) {
                            val dots = stops.map { p -> MapContent.Dot(p.coordinate, Color.White, color, 8f, 2f) } +
                                vehicles.map { v -> MapContent.Dot(v, Color(0xFFFFC21A), ink.ink, 16f, 3f) }
                            MapContent(listOf(MapContent.Line(leg.mapPath, color, 5f)), dots)
                        }
                        val camera = remember(leg.tripId) { MapCamera.Fit(leg.mapPath + stops.map { it.coordinate }) }
                        Card { PublitMap(content, camera, Modifier.fillMaxWidth().height(280.dp), line.lineName) }
                        Text(L10n.t("Vehicle positions are estimated from live departure times."), color = ink.muted, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 28.dp, vertical = 4.dp))
                    }
                    item("stops") {
                        Text(L10n.f("%lld stops", stops.size).uppercase(), color = ink.muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 28.dp, bottom = 6.dp))
                        Card {
                            Column {
                                val t0 = stops.first().let { it.departure ?: it.arrival }
                                stops.forEachIndexed { i, p ->
                                    val here = p.stopId == line.stopId || p.parentId == line.stopId || p.name == line.stopName
                                    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).clickable { timetableStop = p }.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                                        val color = lineStroke(line, ink.dark)
                                        Box(Modifier.width(20.dp).fillMaxHeight(), contentAlignment = Alignment.Center) {
                                            Column(Modifier.fillMaxHeight()) {
                                                Box(Modifier.width(4.dp).weight(1f).background(if (i == 0) Color.Transparent else color))
                                                Box(Modifier.width(4.dp).weight(1f).background(if (i == stops.lastIndex) Color.Transparent else color))
                                            }
                                            Box(Modifier.size(if (here) 13.dp else 9.dp).background(if (here) color else Color.White, CircleShape).border(2.dp, color, CircleShape))
                                        }
                                        Spacer(Modifier.width(10.dp))
                                        Text(p.name, fontWeight = if (here) FontWeight.Bold else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f).padding(vertical = 11.dp))
                                        val t = p.arrival ?: p.departure
                                        if (t0 != null && t != null && i > 0) Text("${(t.epochSecond - t0.epochSecond + 30) / 60} min", color = ink.muted, fontSize = 13.sp)
                                    }
                                }
                            }
                        }
                        Text(L10n.t("Tap a stop for its timetable."), color = ink.muted, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 28.dp, vertical = 4.dp))
                    }
                }
                error != null && departures.isEmpty() -> item { Text(error!!, color = ink.muted, modifier = Modifier.padding(28.dp)) }
                loaded && departures.isEmpty() -> item { Text(L10n.f("No departures of %@ in the next hours.", line.lineName), color = ink.muted, modifier = Modifier.padding(28.dp)) }
                else -> item { Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = ink.ink) } }
            }
            if (departures.isNotEmpty()) item("deps") {
                Text(L10n.t("Departures").uppercase(), color = ink.muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 28.dp, bottom = 6.dp))
                Card {
                    Column {
                        departures.filter { direction == null || directionId(it) == direction.id }.take(12).forEachIndexed { i, st ->
                            if (i > 0) HorizontalDivider(color = ink.hairline, modifier = Modifier.padding(horizontal = 16.dp))
                            DepartureRow(st, now) { onOpenTrip(st) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    Surface(Modifier.fillMaxWidth().padding(horizontal = 12.dp), shape = RoundedCornerShape(14.dp), color = LocalInk.current.surface) { content() }
}

/**
 * Where the vehicles of this direction are now (LineVehicles on iOS): each upcoming departure at our stop
 * runs like the sample trip, shifted in time; its position is the sample trip's position at the shifted time.
 */
private fun estimateVehicles(sample: Leg, line: SavedLine, departures: List<StopTime>, now: Instant): List<io.github.elija7580.publit.core.LatLon> {
    val stops = sample.allStops
    val k = stops.indexOfFirst { it.stopId == line.stopId || it.parentId == line.stopId || it.name == line.stopName }.takeIf { it >= 0 } ?: return emptyList()
    val atK = stops[k].departure ?: stops[k].arrival ?: return emptyList()
    val start = stops.first().departure ?: return emptyList()
    val end = stops.last().arrival ?: return emptyList()
    return departures.mapNotNull { d ->
        val shift = d.departureTime.epochSecond - atK.epochSecond
        val t = now.minusSeconds(shift)
        if (t < start || t > end) return@mapNotNull null
        TripProgress.position(TripProgress.at(t, stops), stops)
    }
}

/** All departures of the line at one stop on a day (LineTimetableView on iOS), by hour. */
@Composable
fun LineTimetableScreen(client: TransitousClient, line: SavedLine, stop: Place, direction: String?, directionKey: String?, onBack: () -> Unit) {
    val ink = LocalInk.current
    val zone: ZoneId = Fmt.zone(stop.tz)
    var day by remember { mutableStateOf(LocalDate.now(zone)) }
    var rows by remember { mutableStateOf<List<Pair<Int, List<Int>>>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(day) {
        rows = null
        val start = day.atStartOfDay(zone).toInstant()
        try {
            val res = client.departures(stop.stopId ?: stop.stationId, 300, null, null, time = start, window = 24 * 3600, maxAge = 6 * 3600)
            val mine = res.stopTimes.filter { line.matches(it) && (directionKey == null || directionId(it) == directionKey) }
                .map { (it.scheduledDepartureTime ?: it.departureTime).atZone(zone) }
                .filter { it.toLocalDate() == day }.sorted().distinct()
            rows = mine.groupBy { it.hour }.toSortedMap().map { (h, l) -> h to l.map { it.minute } }
            error = null
        } catch (e: CancellationException) { throw e } catch (e: Exception) { error = e.message ?: e.javaClass.simpleName; rows = emptyList() }
    }
    val nowLocal = java.time.ZonedDateTime.now(zone)
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, L10n.t("Back")) }
            Text(stop.name, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        LazyColumn(contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Card {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(L10n.t("Day"), modifier = Modifier.weight(1f))
                            IconButton(onClick = { day = day.minusDays(1) }) { Icon(Icons.Filled.ChevronLeft, null) }
                            Text(day.format(java.time.format.DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.MEDIUM)), fontWeight = FontWeight.SemiBold)
                            IconButton(onClick = { day = day.plusDays(1) }) { Icon(Icons.Filled.ChevronRight, null) }
                        }
                        Row { LineBadge(line); Spacer(Modifier.width(8.dp)); direction?.let { Text(L10n.t("Direction") + ": " + it, color = ink.muted) } }
                    }
                }
            }
            val r = rows
            when {
                r == null -> item { Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = ink.ink) } }
                r.isEmpty() -> item { Text(error ?: L10n.t("No departures on this day."), color = ink.muted, modifier = Modifier.padding(28.dp)) }
                else -> item {
                    Text(L10n.f("%lld departures", r.sumOf { it.second.size }).uppercase(), color = ink.muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 28.dp, bottom = 6.dp))
                    Card {
                        Column(Modifier.padding(vertical = 6.dp)) {
                            r.forEach { (h, mins) ->
                                Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                                    Text("%02d".format(h), fontWeight = FontWeight.Bold, modifier = Modifier.width(34.dp))
                                    @OptIn(ExperimentalLayoutApi::class)
                                    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                        mins.forEach { m ->
                                            val past = day < nowLocal.toLocalDate() || (day == nowLocal.toLocalDate() && (h to m).let { (a, b) -> a < nowLocal.hour || (a == nowLocal.hour && b < nowLocal.minute) })
                                            Text("%02d".format(m), color = if (past) ink.muted else ink.ink)
                                        }
                                    }
                                }
                            }
                        }
                    }
                    Text(L10n.t("Scheduled times. Live delays are on the departure board."), color = ink.muted, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 28.dp, vertical = 4.dp))
                }
            }
        }
    }
}
