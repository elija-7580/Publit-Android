package io.github.elija7580.publit.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Work
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import io.github.elija7580.publit.core.L10n
import io.github.elija7580.publit.data.FavoritesStore
import io.github.elija7580.publit.data.PlaceRole
import io.github.elija7580.publit.data.SavedLine
import io.github.elija7580.publit.data.SavedPlace
import io.github.elija7580.publit.data.StopTime
import io.github.elija7580.publit.data.TransitousClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.time.Instant

/** The Saved tab (FavoritesView on iOS): Home and Work, lines, stops, places. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SavedScreen(store: FavoritesStore, onRoute: (SavedPlace) -> Unit, onOpenStop: (SavedPlace) -> Unit,
                onOpenLine: (SavedLine) -> Unit, onSearch: (title: String, (SavedPlace) -> Unit) -> Unit, onSettings: () -> Unit) {
    val ink = LocalInk.current
    val s by store.state.collectAsStateWithLifecycle()
    LazyColumn(Modifier.fillMaxSize().statusBarsPadding(), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
        item("title") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(L10n.t("Saved"), fontWeight = FontWeight.Bold, fontSize = 32.sp, modifier = Modifier.weight(1f).padding(horizontal = 16.dp, vertical = 12.dp))
                IconButton(onClick = onSettings) { Icon(Icons.Filled.Settings, L10n.t("Settings")) }
                IconButton(onClick = { onSearch(L10n.t("Save a place")) { p -> if (!store.state.value.contains(p)) store.toggle(p) } },
                    modifier = Modifier.padding(end = 8.dp)) { Icon(Icons.Filled.Add, L10n.t("Add")) }
            }
        }
        item("roles") {
            Section(L10n.t("Home and Work")) {
                PlaceRole.entries.forEachIndexed { i, role ->
                    if (i > 0) Divider()
                    val p = s.place(role)
                    val icon = if (role == PlaceRole.HOME) Icons.Filled.Home else Icons.Filled.Work
                    val change = { onSearch(L10n.f("Set %@", role.title)) { picked -> store.set(picked, role) } }
                    if (p == null) {
                        KeyRow(Icons.Filled.Add, L10n.f("Set %@", role.title), null, onClick = change)
                    } else {
                        var menu by remember { mutableStateOf(false) }
                        Box {
                            KeyRow(icon, role.title, p.name, onClick = { onRoute(p) }, onLong = { menu = true })
                            DropdownMenu(menu, { menu = false }) {
                                DropdownMenuItem({ Text(L10n.t("Change")) }, { menu = false; change() })
                                DropdownMenuItem({ Text(L10n.t("Remove")) }, { menu = false; store.clear(role) })
                            }
                        }
                    }
                }
            }
        }
        if (s.lines.isNotEmpty()) item("lines") {
            Section(L10n.t("Lines")) {
                s.lines.forEachIndexed { i, line ->
                    if (i > 0) Divider()
                    RemovableRow(onClick = { onOpenLine(line) }, onRemove = { store.removeLine(line.id) }) {
                        LineBadge(line)
                        Spacer(Modifier.width(10.dp))
                        Text(L10n.f("At %@", line.stopName), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        if (s.stops.isEmpty() && s.places.isEmpty() && s.lines.isEmpty()) item("empty") {
            Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Filled.Star, null, tint = ink.muted, modifier = Modifier.size(32.dp))
                Text(L10n.t("Nothing else saved yet"), fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                Text(L10n.t("Save stops from their departure board, lines from a trip, or add places with +."), color = ink.muted, fontSize = 14.sp)
            }
        }
        if (s.stops.isNotEmpty()) item("stops") {
            Section(L10n.t("Stops")) {
                s.stops.forEachIndexed { i, p ->
                    if (i > 0) Divider()
                    RemovableRow(onClick = { onOpenStop(p) }, onRemove = { store.remove(p.id) }) { PlaceText(modeIcon("BUS"), p) }
                }
            }
        }
        if (s.places.isNotEmpty()) item("places") {
            Section(L10n.t("Places")) {
                s.places.forEachIndexed { i, p ->
                    if (i > 0) Divider()
                    RemovableRow(onClick = { onRoute(p) }, onRemove = { store.remove(p.id) },
                        extra = listOf(L10n.t("Home") to { store.set(p, PlaceRole.HOME) }, L10n.t("Work") to { store.set(p, PlaceRole.WORK) })) {
                        PlaceText(Icons.Filled.Place, p)
                    }
                }
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    val ink = LocalInk.current
    Column {
        Text(title, color = ink.muted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 28.dp, top = 8.dp, bottom = 6.dp))
        Surface(Modifier.fillMaxWidth().padding(horizontal = 12.dp), shape = RoundedCornerShape(14.dp), color = ink.surface) { Column { content() } }
    }
}

@Composable private fun Divider() = HorizontalDivider(color = LocalInk.current.hairline, modifier = Modifier.padding(horizontal = 16.dp))

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun KeyRow(icon: ImageVector, title: String, detail: String?, onClick: () -> Unit, onLong: (() -> Unit)? = null) {
    val ink = LocalInk.current
    Row(Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLong).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Text(title, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
        Spacer(Modifier.weight(1f))
        detail?.let { Text(it, color = ink.muted, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 12.dp)) }
    }
}

/** Tap opens; long press offers Remove (swipe-to-delete on iOS). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RemovableRow(onClick: () -> Unit, onRemove: () -> Unit, extra: List<Pair<String, () -> Unit>> = emptyList(),
                         content: @Composable () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Box {
        Row(Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = { menu = true }).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically) { content() }
        DropdownMenu(menu, { menu = false }) {
            extra.forEach { (t, a) -> DropdownMenuItem({ Text(t) }, { menu = false; a() }) }
            DropdownMenuItem({ Text(L10n.t("Remove")) }, { menu = false; onRemove() })
        }
    }
}

@Composable
private fun PlaceText(icon: ImageVector, p: SavedPlace) {
    val ink = LocalInk.current
    Icon(icon, null, Modifier.size(20.dp), tint = ink.muted)
    Spacer(Modifier.width(12.dp))
    Column {
        Text(p.name, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        p.subtitle?.takeIf { it.isNotEmpty() }?.let { Text(it, color = ink.muted, fontSize = 13.sp, maxLines = 1) }
    }
}

/** Next departures of one saved line at its saved stop (LineDeparturesView on iOS). */
@Composable
fun LineDeparturesScreen(client: TransitousClient, line: SavedLine, onBack: () -> Unit, onOpenTrip: (StopTime) -> Unit) {
    val ink = LocalInk.current
    var departures by remember { mutableStateOf(emptyList<StopTime>()) }
    var error by remember { mutableStateOf<String?>(null) }
    var loaded by remember { mutableStateOf(false) }
    val now by produceState(Instant.now()) { while (true) { value = Instant.now(); delay(15_000) } }
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
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, L10n.t("Back")) }
            Text(line.lineName, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
        }
        LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                Row(Modifier.padding(start = 28.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    LineBadge(line); Spacer(Modifier.width(8.dp))
                    Text(L10n.f("At %@", line.stopName), color = ink.muted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
                Surface(Modifier.fillMaxWidth().padding(horizontal = 12.dp), shape = RoundedCornerShape(14.dp), color = ink.surface) {
                    Column {
                        when {
                            error != null && departures.isEmpty() -> Text(error!!, color = ink.muted, modifier = Modifier.padding(16.dp))
                            !loaded -> Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = ink.ink) }
                            departures.isEmpty() -> Text(L10n.f("No departures of %@ in the next hours.", line.lineName), color = ink.muted, modifier = Modifier.padding(16.dp))
                        }
                        departures.forEachIndexed { i, st ->
                            if (i > 0) Divider()
                            DepartureRow(st, now) { onOpenTrip(st) }
                        }
                    }
                }
            }
        }
    }
}
