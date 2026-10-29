package io.github.elija7580.publit.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.elija7580.publit.core.Fmt
import io.github.elija7580.publit.core.L10n
import io.github.elija7580.publit.core.LatLon
import io.github.elija7580.publit.data.Itinerary
import io.github.elija7580.publit.data.Leg
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

private enum class Field { FROM, TO }

@Composable
fun PlanScreen(vm: PlanViewModel, actions: @Composable (Itinerary, String, String) -> Unit = { _, _, _ -> }) {
    val state by vm.state.collectAsStateWithLifecycle()
    var searching by remember { mutableStateOf<Field?>(null) }
    val opened = state.opened
    when {
        searching != null -> PlaceSearch(vm, allowHere = searching == Field.FROM, onBack = { searching = null }) { e ->
            if (searching == Field.FROM) vm.setFrom(e) else vm.setTo(e)
            searching = null
        }
        opened != null -> { BackHandler { vm.open(null) }; ItineraryDetail(opened, { vm.open(null) }) { actions(opened, state.from.title, state.to?.title ?: "") } }
        else -> PlanForm(state, vm, onPick = { searching = it })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlanForm(state: PlanState, vm: PlanViewModel, onPick: (Field) -> Unit) {
    val ink = LocalInk.current
    var picking by remember { mutableStateOf<Boolean?>(null) } // arriveBy?
    LazyColumn(Modifier.fillMaxSize().statusBarsPadding(), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
        item { Text(L10n.t("Plan"), fontWeight = FontWeight.Bold, fontSize = 32.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) }
        item {
            Surface(Modifier.fillMaxWidth().padding(horizontal = 12.dp), shape = RoundedCornerShape(14.dp), color = ink.surface) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        EndpointRow(L10n.t("From"), state.from.title) { onPick(Field.FROM) }
                        HorizontalDivider(color = ink.hairline, modifier = Modifier.padding(start = 16.dp))
                        EndpointRow(L10n.t("To"), state.to?.title ?: L10n.t("Where to?"), placeholder = state.to == null) { onPick(Field.TO) }
                    }
                    IconButton(onClick = vm::swap) { Icon(Icons.Filled.SwapVert, contentDescription = null) }
                }
            }
        }
        item {
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = state.time == null, onClick = { vm.setTime(null, false) }, label = { Text(L10n.t("Now")) })
                FilterChip(selected = state.time != null && !state.arriveBy, onClick = { picking = false },
                    label = { Text(if (state.time != null && !state.arriveBy) L10n.t("Depart") + " " + Fmt.time(state.time) else L10n.t("Depart")) })
                FilterChip(selected = state.time != null && state.arriveBy, onClick = { picking = true },
                    label = { Text(if (state.time != null && state.arriveBy) L10n.t("Arrive") + " " + Fmt.time(state.time) else L10n.t("Arrive")) })
            }
        }
        when {
            state.loading -> item { Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = ink.ink) } }
            state.error != null -> item { Text(state.error, color = ink.muted, modifier = Modifier.padding(horizontal = 16.dp)) }
        }
        items(state.routes, key = { it.routeKey }) { RouteCard(it) { vm.open(it) } }
        state.walkOnly?.let { w -> item("walk") {
            Row(Modifier.fillMaxWidth().clickable { vm.open(w) }.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.AutoMirrored.Filled.DirectionsWalk, null, tint = ink.muted)
                Spacer(Modifier.width(8.dp))
                Text(L10n.f("%@ walk", Fmt.duration(w.duration)), color = ink.muted)
            }
        } }
        if (state.routes.isNotEmpty() && state.nextCursor != null) item("later") {
            TextButton(onClick = vm::later, modifier = Modifier.padding(horizontal = 8.dp)) { Text(L10n.t("Later connections")) }
        }
    }
    picking?.let { arrive ->
        val now = java.time.LocalTime.now()
        val tp = rememberTimePickerState(now.hour, now.minute, is24Hour = Fmt.timePattern.startsWith("H"))
        AlertDialog(onDismissRequest = { picking = null },
            confirmButton = { TextButton(onClick = {
                val zone = ZoneId.systemDefault()
                var t = LocalDate.now().atTime(LocalTime.of(tp.hour, tp.minute)).atZone(zone).toInstant()
                // An earlier time than now means tomorrow.
                if (t.isBefore(Instant.now().minusSeconds(600))) t = t.plusSeconds(86_400)
                vm.setTime(t, arrive); picking = null
            }) { Text("OK") } },
            dismissButton = { TextButton(onClick = { picking = null }) { Text(L10n.t("Cancel")) } },
            text = { TimePicker(tp) })
    }
}

@Composable
private fun EndpointRow(label: String, value: String, placeholder: Boolean = false, onClick: () -> Unit) {
    val ink = LocalInk.current
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = ink.muted, fontSize = 14.sp, modifier = Modifier.width(56.dp))
        Text(value, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = if (placeholder) ink.muted else ink.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun RouteCard(it: Itinerary, onClick: () -> Unit) {
    val ink = LocalInk.current
    val tz = it.legs.firstOrNull()?.from?.tz
    Surface(Modifier.fillMaxWidth().padding(horizontal = 12.dp).clickable(onClick = onClick), shape = RoundedCornerShape(14.dp), color = ink.surface) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(Fmt.time(it.startTime, tz) + " – " + Fmt.time(it.endTime, it.legs.lastOrNull()?.to?.tz),
                    fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.weight(1f),
                    color = if (it.isCancelled) ink.danger else ink.ink)
                Text(Fmt.duration(it.duration), fontWeight = FontWeight.SemiBold)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                it.transitLegs.forEach { leg -> LegBadge(leg) }
            }
            val first = it.transitLegs.firstOrNull()
            Text(listOfNotNull(
                if (it.transfers == 0) L10n.t("Direct") else if (it.transfers == 1) L10n.t("1 change") else L10n.f("%lld changes", it.transfers),
                first?.let { f -> L10n.f("%@ from %@", f.lineName, f.from.name) },
                if (it.isCancelled) L10n.t("Connection cancelled") else null,
            ).joinToString(" · "), color = if (it.isCancelled) ink.danger else ink.muted, fontSize = 13.sp, maxLines = 2)
        }
    }
}

@Composable
private fun LegBadge(leg: Leg) {
    val c = leg.lineColors
    Row(Modifier.background(hexColor(c.background), RoundedCornerShape(6.dp)).padding(horizontal = 7.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(modeIcon(leg.mode), null, Modifier.size(14.dp), tint = hexColor(c.text))
        Spacer(Modifier.width(3.dp))
        Text(leg.lineName, color = hexColor(c.text), fontWeight = FontWeight.Bold, fontSize = 13.sp)
    }
}

@Composable
fun ItineraryDetail(it: Itinerary, onBack: () -> Unit, title: String? = null, top: @Composable () -> Unit = {}) {
    val ink = LocalInk.current
    LazyColumn(Modifier.fillMaxSize().statusBarsPadding(), contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) }
                if (title != null) Text(title, fontWeight = FontWeight.Bold, fontSize = 20.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) else
                Text(Fmt.time(it.startTime, it.legs.firstOrNull()?.from?.tz) + " – " + Fmt.time(it.endTime, it.legs.lastOrNull()?.to?.tz) + " · " + Fmt.duration(it.duration),
                    fontWeight = FontWeight.Bold, fontSize = 20.sp)
            }
        }
        item("top") { top() }
        item("map") {
            var full by remember { mutableStateOf(false) }
            Surface(Modifier.fillMaxWidth().padding(horizontal = 12.dp), shape = RoundedCornerShape(14.dp), color = ink.surface) {
                Box(Modifier.height(200.dp)) {
                    RouteMap(it, Modifier.fillMaxSize())
                    Box(Modifier.matchParentSize().clickable { full = true })
                }
            }
            if (full) androidx.compose.ui.window.Dialog(onDismissRequest = { full = false },
                properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
                Box(Modifier.fillMaxSize()) {
                    RouteMap(it, Modifier.fillMaxSize())
                    androidx.compose.material3.TextButton(onClick = { full = false },
                        modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(8.dp)) { Text(L10n.t("Done"), fontWeight = FontWeight.SemiBold) }
                }
            }
        }
        items(it.legs.size) { i ->
            val leg = it.legs[i]
            Surface(Modifier.fillMaxWidth().padding(horizontal = 12.dp), shape = RoundedCornerShape(14.dp), color = ink.surface) {
                if (leg.isTransit) TransitLeg(leg) else Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.AutoMirrored.Filled.DirectionsWalk, null, tint = ink.muted)
                    Spacer(Modifier.width(8.dp))
                    val dist = leg.distance?.let { d -> " · " + Fmt.distance(d) } ?: ""
                    Text(L10n.f("%@ walk", Fmt.duration(leg.duration)) + dist + " → " + leg.to.name, color = ink.muted, fontSize = 14.sp)
                }
            }
        }
    }
}

@Composable
private fun TransitLeg(leg: Leg) {
    val ink = LocalInk.current
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            LegBadge(leg)
            Spacer(Modifier.width(8.dp))
            Text(leg.shownDirection?.let { L10n.t("Towards") + " " + it } ?: "", fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 2)
        }
        StopLine(leg.startTime, leg.from.tz, leg.from.name, leg.from.track, leg.delayMinutes, leg.realTime)
        val n = leg.intermediateStops?.size ?: 0
        Text(Fmt.duration(leg.duration) + if (n > 0) " · " + (if (n == 1) L10n.t("1 stop") else L10n.f("%lld stops", n)) else "",
            color = ink.muted, fontSize = 13.sp, modifier = Modifier.padding(start = 56.dp))
        StopLine(leg.endTime, leg.to.tz, leg.to.name, leg.to.track, 0, leg.realTime)
        if (leg.cancelled == true) Text(L10n.t("Cancelled"), color = ink.danger, fontWeight = FontWeight.SemiBold)
        leg.serviceAlerts.firstOrNull()?.let { Text(it.headerText, color = ink.delay, fontSize = 13.sp) }
    }
}

@Composable
private fun StopLine(time: Instant, tz: String?, name: String, track: String?, delay: Int, live: Boolean) {
    val ink = LocalInk.current
    Row(verticalAlignment = Alignment.Top) {
        Column(Modifier.width(56.dp)) {
            Text(Fmt.time(time, tz), fontWeight = FontWeight.Bold, fontSize = 15.sp, color = if (delay > 0) ink.delay else ink.ink)
            if (delay > 0) Text(L10n.f("+%lld min", delay), color = ink.delay, fontSize = 11.sp)
        }
        Column {
            Text(name, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            track?.takeIf { it.isNotBlank() }?.let { Text(L10n.f("Platform %@", it), color = ink.muted, fontSize = 12.sp) }
        }
    }
}

/** Search for a place or stop, as PlaceSearchView on iOS. */
@Composable
fun PlaceSearch(vm: PlanViewModel, allowHere: Boolean, onBack: () -> Unit, onMatch: ((io.github.elija7580.publit.data.GeocodeMatch) -> Unit)? = null,
                onPick: (Endpoint) -> Unit) {
    val ink = LocalInk.current
    BackHandler(onBack = onBack)
    var text by remember { mutableStateOf("") }
    val matches by vm.matches.collectAsStateWithLifecycle()
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { vm.query(""); focus.requestFocus() }
    Column(Modifier.fillMaxSize().statusBarsPadding().imePadding()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 12.dp)) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) }
            OutlinedTextField(text, { text = it; vm.query(it) }, singleLine = true, placeholder = { Text(L10n.t("Search")) },
                modifier = Modifier.fillMaxWidth().focusRequester(focus))
        }
        LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
            if (allowHere) item {
                Row(Modifier.fillMaxWidth().clickable { onPick(Endpoint.Here) }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.MyLocation, null)
                    Spacer(Modifier.width(12.dp))
                    Text(L10n.t("Current location"), fontWeight = FontWeight.SemiBold)
                }
            }
            items(matches, key = { it.id + it.lat + it.lon }) { m ->
                Row(Modifier.fillMaxWidth().clickable { if (onMatch != null) onMatch(m) else onPick(Endpoint.At(m.name, LatLon(m.lat, m.lon))) }.padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (m.isStop) modeIcon("BUS") else Icons.Filled.Place, null, tint = ink.muted)
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(m.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (m.subtitle.isNotEmpty()) Text(m.subtitle, color = ink.muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}
