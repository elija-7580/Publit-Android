// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2026 Elija Samuel Friedrich-Ulrich
package io.github.elija7580.publit.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.elija7580.publit.core.Fmt
import io.github.elija7580.publit.core.L10n
import io.github.elija7580.publit.data.Itinerary
import io.github.elija7580.publit.data.RoutesOnMap

/**
 * All routes found in Plan on one map (CompareRoutesMapView on iOS, build 99). The picked route is drawn in
 * its line colours on top, the others grey below it; the row under the map picks the route, "Show route"
 * opens it. The map frames all routes and stays there when another one is picked. `recommended` is the
 * route picked at first (the list's recommended one), otherwise the first.
 */
@Composable
fun RoutesMapScreen(routes: List<Itinerary>, recommended: String?, title: String, onBack: () -> Unit, onOpen: (Itinerary) -> Unit) {
    val ink = LocalInk.current
    BackHandler(onBack = onBack)
    var picked by rememberSaveable { mutableStateOf(RoutesOnMap.current(routes, recommended)?.routeKey) }
    val current = RoutesOnMap.current(routes, picked)
    val keys = routes.map { it.routeKey }
    val ahead = if (ink.dark) Color(0xFF409CFF) else Color(0xFF0A7CFF)
    val content = remember(keys, current?.routeKey, ink.dark) {
        val lines = RoutesOnMap.ordered(routes, current?.routeKey).flatMap { r ->
            val strong = r.routeKey == current?.routeKey
            r.legs.map { l ->
                MapContent.Line(l.mapPath,
                    if (!strong) Color.Gray.copy(alpha = 0.45f) else if (l.isTransit) lineStroke(l, ink.dark) else ahead,
                    if (!strong) 3f else if (l.isTransit) 5f else 4f)
            }
        }
        // Change points, start and destination of the picked route, as on the map of a single route.
        val dots = ArrayList<MapContent.Dot>()
        if (current != null) {
            current.legs.filter { l -> l.isTransit }.drop(1).forEach { l -> dots += MapContent.Dot(l.from.coordinate, Color.White, ink.ink, 10f, 2f) }
            current.legs.firstOrNull()?.let { l -> dots += MapContent.Dot(l.from.coordinate, Color.White, ink.ink, 16f, 4f) }
            current.legs.lastOrNull()?.let { l -> dots += MapContent.Dot(l.to.coordinate, ink.ink, Color.White, 18f, 3f) }
        }
        MapContent(lines, dots)
    }
    val frame = remember(keys) { MapCamera.Fit(RoutesOnMap.frame(routes)) }
    val row = rememberLazyListState()
    // The route picked at first can sit further right in the row: bring it into view once.
    LaunchedEffect(Unit) { val i = routes.indexOfFirst { it.routeKey == picked }; if (i > 0) row.scrollToItem(i) }
    Column(Modifier.fillMaxSize().background(ink.ground).statusBarsPadding().navigationBarsPadding()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, L10n.t("Back")) }
            Text(title, fontWeight = FontWeight.Bold, fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(end = 16.dp))
        }
        PublitMap(content, frame, Modifier.weight(1f).fillMaxWidth(), L10n.t("Routes"))
        Column(Modifier.fillMaxWidth().background(ink.surface).padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            LazyRow(state = row, contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                itemsIndexed(routes) { _, r -> RouteChip(r, r.routeKey == current?.routeKey) { picked = r.routeKey } }
            }
            if (current != null) Button(onClick = { onOpen(current) }, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = ink.ink, contentColor = ink.onInk)) {
                Text(L10n.t("Show route"), fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/** One route in the row under the map: its times, then how long it takes and how often you change. */
@Composable
private fun RouteChip(r: Itinerary, isPicked: Boolean, onPick: () -> Unit) {
    val ink = LocalInk.current
    Surface(onClick = onPick, shape = RoundedCornerShape(12.dp), color = if (isPicked) ink.fill else ink.surface,
        border = BorderStroke(if (isPicked) 1.5.dp else 1.dp, if (isPicked) ink.ink else ink.hairline),
        modifier = Modifier.semantics { selected = isPicked }) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(Fmt.time(r.startTime, r.legs.firstOrNull()?.from?.tz) + " – " + Fmt.time(r.endTime, r.legs.lastOrNull()?.to?.tz),
                fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1)
            // Walking the whole way is "Walk", not "Direct" (that is a ride without a change), as on the route cards.
            Text(Fmt.duration(r.duration) + " · " + (if (r.isDirect) L10n.t("Walk") else if (r.transfers == 0) L10n.t("Direct")
                else if (r.transfers == 1) L10n.t("1 change") else L10n.f("%lld changes", r.transfers)),
                color = ink.muted, fontSize = 13.sp, maxLines = 1)
        }
    }
}
