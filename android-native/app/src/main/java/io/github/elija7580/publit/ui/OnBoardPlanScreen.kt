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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Tram
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.elija7580.publit.core.L10n
import io.github.elija7580.publit.data.Itinerary
import io.github.elija7580.publit.data.Leg
import io.github.elija7580.publit.data.OnBoardPlan
import io.github.elija7580.publit.data.TransitousClient
import io.github.elija7580.publit.data.TripStore
import kotlinx.coroutines.CancellationException
import java.time.Instant

/**
 * "Plan from this vehicle" (OnBoardPlanView on iOS): you are on board and pick where to go; the routes start with
 * this ride (stay on, or change at a stop still ahead), with live times. Idea from BudapestGO (BKK Budapest).
 */
@Composable
fun OnBoardPlanScreen(client: TransitousClient, plan: PlanViewModel, trips: TripStore, trip: Leg, lineName: String,
                      onBack: () -> Unit, onStarted: (io.github.elija7580.publit.data.SavedTrip) -> Unit) {
    val ink = LocalInk.current
    var destination by remember { mutableStateOf<Endpoint.At?>(null) }
    var picking by rememberSaveable { mutableStateOf(true) }
    var routes by remember { mutableStateOf(emptyList<Itinerary>()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var opened by remember { mutableStateOf<Itinerary?>(null) }
    var generation by remember { mutableStateOf(0) }

    LaunchedEffect(destination, generation) {
        val dest = destination ?: return@LaunchedEffect
        val pos = OnBoardPlan.position(trip.allStops, Instant.now())
        if (pos == null) { error = L10n.t("This ride has ended."); return@LaunchedEffect }
        loading = true
        try {
            val start = trip.allStops[pos.next]
            val res = client.plan(start.coordinate, dest.point, pos.at, arriveBy = false, fromStop = start.stopId)
            routes = OnBoardPlan.routes(res.itineraries + res.direct, trip, pos)
            error = if (routes.isEmpty()) L10n.t("No connections found.") else null
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            error = e.message ?: e.javaClass.simpleName
        } finally { loading = false }
    }

    val open = opened
    if (picking) {
        PlaceSearch(plan, allowHere = false, onBack = { picking = false; if (destination == null) onBack() }) { e ->
            if (e is Endpoint.At) { destination = e; routes = emptyList(); error = null }
            picking = false
        }
        return
    }
    if (open != null) {
        BackHandler { opened = null }
        ItineraryDetail(open, onBack = { opened = null }, title = destination?.name,
            bar = { TripActionBar(trips, open, L10n.f("On board %@", lineName), destination?.name.orEmpty(), onStarted) })
        return
    }
    BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = L10n.t("Back")) }
            Text(L10n.t("Plan from this vehicle"), fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
        }
        LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
            item("head") {
                Surface(shape = RoundedCornerShape(14.dp), color = ink.surface, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                    Column {
                        val now = Instant.now()
                        val pos = OnBoardPlan.position(trip.allStops, now)
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Tram, null, Modifier.size(22.dp), tint = lineStroke(trip, ink.dark))
                            Spacer(Modifier.size(10.dp))
                            Column {
                                Text(L10n.f("On board %@", lineName), fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                                if (pos == null) Text(L10n.t("This ride has ended."), color = ink.muted, fontSize = 14.sp) else {
                                    val next = trip.allStops[pos.next]
                                    val min = maxOf(0L, Math.round((pos.at.epochSecond - now.epochSecond) / 60.0))
                                    Text(if (min == 0L) L10n.f("Now at %@", next.name) else L10n.f("Next stop: %@, in %lld min", next.name, min.toInt()),
                                        color = ink.muted, fontSize = 14.sp)
                                }
                            }
                        }
                        androidx.compose.material3.HorizontalDivider(color = ink.hairline, modifier = Modifier.padding(horizontal = 16.dp))
                        Row(Modifier.fillMaxWidth().clickable { picking = true }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Place, null, Modifier.size(20.dp))
                            Spacer(Modifier.size(10.dp))
                            Text(destination?.name ?: L10n.t("Where to?"), fontSize = 15.sp,
                                color = if (destination == null) ink.muted else ink.ink, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
            item("note") {
                Text(L10n.t("Routes start with this ride: stay on, or change at a stop still ahead. Idea: BudapestGO (BKK Budapest)."),
                    color = ink.muted, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 28.dp))
            }
            if (destination != null) {
                if (loading && routes.isEmpty()) item("loading") {
                    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = ink.ink) }
                } else if (error != null && routes.isEmpty()) item("error") {
                    Text(error!!, color = ink.muted, modifier = Modifier.padding(horizontal = 28.dp))
                }
                val axis = routes.minOfOrNull { it.startTime }?.let { s -> s to (routes.maxOf { it.endTime }) }
                items(routes, key = { it.routeKey }) { r ->
                    RouteCard(r, emptyList(), null, axis) { opened = r }
                }
            }
        }
    }
}
