// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2026 Elija Samuel Friedrich-Ulrich
package io.github.elija7580.publit.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.elija7580.publit.core.Fmt
import io.github.elija7580.publit.core.L10n
import io.github.elija7580.publit.core.LatLon
import io.github.elija7580.publit.data.Itinerary
import io.github.elija7580.publit.data.Place
import io.github.elija7580.publit.data.RouteBuilder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** What a ride's "Go on another way" (#142) or "Change somewhere else" (#143) opened. */
data class LegChange(val index: Int, val elsewhere: Boolean)

/** The new change point: a stop of the ride (with its vehicle) or any place. */
private data class ChangeTarget(val name: String, val point: LatLon, val tripId: String?, val tz: String?)

/**
 * Build your own connection on Android (LegChangeView and ChangePointView on iOS). "Go on another way" lists
 * the ways on from where the ride starts; "Change somewhere else" first lists the stops of the ride (also after
 * the planned one) and "Another place, by search or on the map", then the ways on from there.
 */
@Composable
fun RouteChangeScreen(vm: PlanViewModel, route: Itinerary, change: LegChange, onBack: () -> Unit, onChoose: (Itinerary) -> Unit) {
    var target by remember { mutableStateOf<ChangeTarget?>(null) }
    var searching by remember { mutableStateOf(false) }
    val leg = route.legs.getOrNull(change.index) ?: run { onBack(); return }
    when {
        searching -> PlaceSearch(vm, allowHere = false, onBack = { searching = false }, chooseOnMap = true) { e ->
            if (e is Endpoint.At) target = ChangeTarget(e.name, e.point, null, null)
            searching = false
        }
        !change.elsewhere -> {
            BackHandler(onBack = onBack)
            WaysOn(vm, route, change.index, null, onBack, onChoose)
        }
        target != null -> {
            BackHandler { target = null }
            WaysOn(vm, route, change.index, target, { target = null }, onChoose)
        }
        else -> {
            BackHandler(onBack = onBack)
            val ink = LocalInk.current
            var stops by remember(leg) { mutableStateOf(RouteBuilder.exitStops(leg, null)) }
            var loading by remember(leg) { mutableStateOf(true) }
            LaunchedEffect(leg) {
                leg.tripId?.let { id -> runCatching { vm.trip(id).transitLegs.firstOrNull() }.getOrNull()?.let { stops = RouteBuilder.exitStops(leg, it) } }
                loading = false
            }
            Column(Modifier.fillMaxSize().statusBarsPadding()) {
                Header(L10n.t("Change somewhere else"), onBack)
                LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    item {
                        Text(L10n.f("%@: get off at", leg.lineName), color = ink.muted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(start = 16.dp, bottom = 2.dp))
                        Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), color = ink.surface) {
                            Column {
                                if (loading && stops.isEmpty()) Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                                stops.forEach { stop -> StopChoice(stop, leg.to, leg.to.tz) { target = ChangeTarget(stop.name, stop.coordinate, leg.tripId, stop.tz) } }
                            }
                        }
                        Text(L10n.t("The way up to the ride stays as it is. From the new stop, Publit looks for the way on."),
                            color = ink.muted, fontSize = 13.sp, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp))
                    }
                    item {
                        Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), color = ink.surface) {
                            Row(Modifier.fillMaxWidth().clickable { searching = true }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.Search, null)
                                Spacer(Modifier.width(10.dp))
                                Text(L10n.t("Another place, by search or on the map"), fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Header(title: String, onBack: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) }
        Text(title, fontWeight = FontWeight.Bold, fontSize = 20.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun StopChoice(stop: Place, planned: Place, tz: String?, onClick: () -> Unit) {
    val ink = LocalInk.current
    val isPlanned = RouteBuilder.samePlace(stop, planned)
    Row(Modifier.fillMaxWidth().clickable(enabled = !isPlanned, onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text((stop.arrival ?: stop.departure)?.let { Fmt.time(it, stop.tz ?: tz) } ?: "", color = ink.muted, fontSize = 13.sp, modifier = Modifier.width(52.dp))
        Text(stop.name, fontSize = 15.sp, fontWeight = if (isPlanned) FontWeight.SemiBold else FontWeight.Normal, maxLines = 2, modifier = Modifier.weight(1f))
        if (isPlanned) Text(L10n.t("Planned"), color = ink.muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** The ways on, each as the whole route: from where the ride starts (target null) or from the new change point. */
@Composable
private fun WaysOn(vm: PlanViewModel, route: Itinerary, index: Int, target: ChangeTarget?, onBack: () -> Unit, onChoose: (Itinerary) -> Unit) {
    val ink = LocalInk.current
    val restart = RouteBuilder.restart(route, index) ?: run { onBack(); return }
    var way by remember(target) { mutableStateOf<Itinerary?>(null) }
    var choices by remember(target) { mutableStateOf<List<Itinerary>>(emptyList()) }
    var loading by remember(target) { mutableStateOf(true) }
    var failure by remember(target) { mutableStateOf<String?>(null) }
    var cursor by remember(target) { mutableStateOf<String?>(null) }
    var attempt by remember(target) { mutableStateOf(0) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(target, attempt) {
        loading = true; failure = null
        try {
            if (target == null) {
                val res = vm.planOnward(restart.from.coordinate, restart.to.coordinate, restart.earliest)
                choices = RouteBuilder.choices(route, index, res.itineraries)
                cursor = res.nextPageCursor
                if (choices.all { it.routeKey == route.routeKey }) failure = L10n.t("No other way to go on from here.")
            } else {
                val there = vm.planOnward(restart.from.coordinate, target.point, restart.earliest)
                val found = RouteBuilder.wayToChange(route, index, target.tripId, there.itineraries)
                if (found == null) { failure = L10n.t("No way found to this place in time. Choose another stop."); return@LaunchedEffect }
                way = found
                val on = vm.planOnward(target.point, restart.to.coordinate, found.endTime)
                choices = RouteBuilder.choices(route, index, found, on.itineraries)
                if (choices.isEmpty()) failure = L10n.t("No way on from this place to your destination. Choose another stop.")
            }
        } catch (e: CancellationException) { throw e } catch (_: Exception) {
            failure = L10n.t("The ways to go on could not be loaded. Check your connection and try again.")
        } finally { loading = false }
    }
    val firstNew = index + (way?.legs?.size ?: 0)
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Header(target?.name ?: L10n.t("Go on another way"), onBack)
        LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                val from = target?.name ?: restart.from.name
                val after = way?.endTime ?: restart.earliest
                Text(L10n.f("From %@, after %@", from, Fmt.time(after, target?.tz ?: restart.from.tz)), color = ink.muted, fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 16.dp, bottom = 2.dp))
                Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), color = ink.surface) {
                    Column {
                        choices.forEach { c ->
                            val current = c.routeKey == route.routeKey
                            ChoiceRow(c, firstNew, current, Modifier.clickable(enabled = !current) { onChoose(c) })
                        }
                        if (loading) Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                        failure?.let { f ->
                            Text(f, color = ink.muted, fontSize = 13.sp, modifier = Modifier.padding(16.dp))
                            if (choices.isEmpty()) TextButton({ attempt++ }, Modifier.padding(start = 4.dp)) { Text(L10n.t("Try again")) }
                        }
                        if (target == null && cursor != null && !loading) TextButton({
                            val page = cursor ?: return@TextButton
                            scope.launch {
                                runCatching { vm.planOnward(restart.from.coordinate, restart.to.coordinate, restart.earliest, page) }.getOrNull()?.let { res ->
                                    val known = choices.map { it.routeKey }.toSet()
                                    choices = choices + RouteBuilder.choices(route, index, res.itineraries).filter { it.routeKey !in known }
                                    cursor = res.nextPageCursor
                                }
                            }
                        }, Modifier.padding(start = 4.dp)) { Text(L10n.t("Later")) }
                    }
                }
                val foot = when {
                    target != null && way != null && target.tripId != null && way?.transitLegs?.firstOrNull()?.tripId != target.tripId ->
                        L10n.t("The ride does not stop there in time, so another way there is shown.")
                    target == null && index > 0 -> L10n.t("The way up to here stays as it is. Only ways you can still reach are listed.")
                    else -> null
                }
                foot?.let { Text(it, color = ink.muted, fontSize = 13.sp, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp)) }
            }
        }
    }
}

/** One way on: when it leaves, when the whole route arrives, and its lines. */
@Composable
private fun ChoiceRow(choice: Itinerary, from: Int, current: Boolean, modifier: Modifier) {
    val ink = LocalInk.current
    val rest = choice.legs.drop(from)
    val rides = rest.filter { it.isTransit }
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val start = rides.firstOrNull()?.startTime ?: rest.firstOrNull()?.startTime ?: choice.startTime
            Text(Fmt.time(start, rest.firstOrNull()?.from?.tz) + " – " + Fmt.time(choice.endTime, choice.legs.lastOrNull()?.to?.tz),
                fontWeight = FontWeight.Bold, fontSize = 16.sp, color = if (current) ink.muted else ink.ink, modifier = Modifier.weight(1f))
            if (current) Text(L10n.t("Your route"), color = ink.muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
        if (rides.isEmpty()) Text(L10n.t("On foot"), fontSize = 14.sp)
        else Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            rides.take(5).forEach { LineBadge(it) }
        }
        rides.firstOrNull()?.shownDirection?.let { Text("→ $it", color = ink.muted, fontSize = 13.sp, maxLines = 1) }
    }
}
