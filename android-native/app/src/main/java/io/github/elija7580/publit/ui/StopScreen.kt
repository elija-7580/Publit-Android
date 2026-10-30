package io.github.elija7580.publit.ui

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import io.github.elija7580.publit.core.Fmt
import io.github.elija7580.publit.core.L10n
import io.github.elija7580.publit.data.Alerts
import io.github.elija7580.publit.data.LineAtStop
import io.github.elija7580.publit.data.LineGrouping
import io.github.elija7580.publit.data.ModeFilter
import io.github.elija7580.publit.data.ModeGroup
import io.github.elija7580.publit.data.StopTime
import io.github.elija7580.publit.data.TransitousClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.time.Instant

/** A station's departures, as StopDeparturesView on iOS: refreshed every 30 s while visible. */
@Composable
fun StopScreen(client: TransitousClient, favorites: io.github.elija7580.publit.data.FavoritesStore, stopId: String, name: String, exact: Boolean, onBack: () -> Unit,
               onOpenTrip: (StopTime) -> Unit = {}) {
    val ink = LocalInk.current
    var departures by remember { mutableStateOf<List<StopTime>>(emptyList()) }
    var alerts by remember { mutableStateOf(emptyList<io.github.elija7580.publit.data.Alert>()) }
    var error by remember { mutableStateOf<String?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var at by remember { mutableStateOf<io.github.elija7580.publit.core.LatLon?>(null) }
    var showLines by rememberSaveable { mutableStateOf(false) }
    var filter by remember { mutableStateOf(ModeFilter()) }
    var available by remember { mutableStateOf(emptyList<ModeGroup>()) }
    val now by produceState(Instant.now()) { while (true) { value = Instant.now(); delay(15_000) } }
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    LaunchedEffect(stopId, filter) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                try {
                    val res = client.departures(stopId, 60, if (exact) null else 150, filter.apiValue)
                    departures = StopTime.upcoming(res.stopTimes)
                    if (at == null) at = (res.place ?: res.stopTimes.firstOrNull()?.place)?.coordinate
                    if (!filter.isActive) available = ModeGroup.present(res.stopTimes.map { it.mode })
                    alerts = Alerts.relevant(res.place?.alertsList.orEmpty() + res.stopTimes.flatMap { it.place.alertsList })
                    error = null
                } catch (e: CancellationException) { throw e } catch (e: Exception) {
                    error = e.message ?: e.javaClass.simpleName
                }
                loaded = true
                delay(30_000)
            }
        }
    }

    val shown = departures.filter { filter.allows(it.mode) }
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 4.dp)) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = L10n.t("Back")) }
            Text(name, fontWeight = FontWeight.Bold, fontSize = 22.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            val saved by favorites.state.collectAsStateWithLifecycle()
            at?.let { c ->
                val place = io.github.elija7580.publit.data.SavedPlace(stopId, name, null, c.lat, c.lon, stopId, exact = if (exact) true else null)
                val isSaved = saved.contains(place)
                IconButton(onClick = { favorites.toggle(place) }) {
                    Icon(if (isSaved) Icons.Filled.Star else Icons.Filled.StarBorder, L10n.t("Save"))
                }
            }
        }
        LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
            item("offline") { OfflineNote(client) }
            io.github.elija7580.publit.data.CoverageGuide.notice(at)?.let { n -> item("coverage") { CoverageBanner(listOf(n)) } }
            if (alerts.isNotEmpty()) item("alerts") {
                Surface(Modifier.fillMaxWidth().padding(horizontal = 12.dp), shape = RoundedCornerShape(14.dp), color = ink.surface) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        alerts.take(3).forEach { Text(it.headerText, color = ink.delay, fontWeight = FontWeight.SemiBold, fontSize = 14.sp) }
                    }
                }
            }
            item("mode") {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                    SegmentedButton(selected = !showLines, onClick = { showLines = false }, shape = SegmentedButtonDefaults.itemShape(0, 2)) { Text(L10n.t("Departures")) }
                    SegmentedButton(selected = showLines, onClick = { showLines = true }, shape = SegmentedButtonDefaults.itemShape(1, 2)) { Text(L10n.t("Lines")) }
                }
            }
            if (available.size > 1 || filter.isActive) item("chips") {
                ModeChips(available, filter.included, filter.isActive) { filter = filter.toggleOnly(it) }
            }
            if (shown.isEmpty()) item("status") {
                Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    when {
                        error != null -> Text(error!!, color = ink.muted)
                        !loaded -> CircularProgressIndicator(color = ink.ink)
                        filter.isActive -> Text(L10n.t("No departures for these modes"), color = ink.muted)
                        else -> Text(L10n.t("No upcoming departures."), color = ink.muted)
                    }
                }
            }
            if (shown.isNotEmpty()) item("list") {
                Surface(Modifier.fillMaxWidth().padding(horizontal = 12.dp), shape = RoundedCornerShape(14.dp), color = ink.surface) {
                    Column {
                        if (error != null) Text(L10n.t("Couldn't refresh. Times may be out of date."), color = ink.muted, fontSize = 13.sp, modifier = Modifier.padding(16.dp))
                        if (showLines) {
                            LineGrouping.lines(shown).forEachIndexed { i, line ->
                                if (i > 0) HorizontalDivider(color = ink.hairline, modifier = Modifier.padding(horizontal = 16.dp))
                                LineRow(line, now)
                            }
                        } else {
                            shown.forEachIndexed { i, st ->
                                if (i > 0) HorizontalDivider(color = ink.hairline, modifier = Modifier.padding(horizontal = 16.dp))
                                DepartureRow(st, now) { onOpenTrip(st) }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** One line: badge, then the next departures per direction ("now · 8 min · 14:35"). */
@Composable
private fun LineRow(line: LineAtStop, now: Instant) {
    val ink = LocalInk.current
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            LineBadge(line.first)
            line.first.serviceDetail?.let { Spacer(Modifier.width(8.dp)); Text(it, color = ink.muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold) }
        }
        line.byDirection(3).take(3).forEach { (direction, deps) ->
            Row {
                Text("→ " + (direction ?: L10n.t("Direction unavailable")), fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                Text(times(deps, now), color = ink.muted, fontSize = 14.sp, maxLines = 1)
            }
        }
    }
}

private fun times(departures: List<StopTime>, now: Instant): String =
    departures.filter { !it.isCancelled }.joinToString(" · ") {
        val m = Math.ceil((it.departureTime.epochSecond - now.epochSecond) / 60.0).toInt()
        when {
            m <= 0 -> L10n.t("now")
            m < 60 -> L10n.f("%lld min", m)
            else -> Fmt.time(it.departureTime, it.place.tz)
        }
    }
