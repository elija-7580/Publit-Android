package io.github.elija7580.publit.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetScaffoldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.elija7580.publit.core.L10n
import io.github.elija7580.publit.core.LatLon
import io.github.elija7580.publit.data.StopGroup
import kotlin.math.max

/** Settings → Nearby → "Map" (iOS NearbyMap.defaultsKey). */
const val NEARBY_MAP_KEY = "nearbyMap"

/** "Where to now?": the route search from here, leaving now (iOS whereToButton). */
@Composable
fun WhereToRow(onClick: () -> Unit) {
    val ink = LocalInk.current
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(L10n.t("To"), color = ink.muted, fontSize = 14.sp, modifier = Modifier.width(56.dp))
        Text(L10n.t("Where to now?"), fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = ink.muted, modifier = Modifier.weight(1f))
        Icon(Icons.Filled.Search, null, Modifier.background(ink.ink, CircleShape).padding(8.dp), tint = ink.onInk)
    }
}

/**
 * Nearby with the map (iOS NearbyView + NearbyMap): the map is the screen, the whole board a sheet over it
 * like the live trip. It starts half way up; "Where to now?" heads the sheet, so the search is there at
 * every height. Up, a strip of the map stays visible. Settings and the station map (with its search) stay
 * as keys at the top. The map frames you and the nearest stations once, then it is free to move.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NearbyMapLayout(state: NearbyState, onOpenMap: () -> Unit, onSettings: () -> Unit, onOpenStop: (StopGroup) -> Unit,
                    header: @Composable () -> Unit, board: LazyListScope.() -> Unit) {
    val ink = LocalInk.current
    val here = state.fix?.coordinate
    val shown = state.groups.take(8)
    // Framed once on you and the stations; the location key frames it again.
    var recenter by remember { mutableIntStateOf(0) }
    var handled by remember { mutableIntStateOf(0) }
    var framed by remember { mutableStateOf<MapCamera?>(null) }
    LaunchedEffect(here != null, state.groups.firstOrNull()?.id, recenter) {
        if (here == null || (framed != null && recenter == handled)) return@LaunchedEffect
        handled = recenter
        val far = shown.take(6).maxOfOrNull { here.distanceTo(it.coordinate) } ?: 0.0
        // A new value each time, so the map moves back even when nothing else changed.
        framed = MapCamera.Around(here, max(500.0, far * 2.5) + recenter % 2 * 0.01)
    }
    val dots = shown.map { g ->
        val nearest = g.id == state.groups.first().id
        MapContent.Dot(g.coordinate, ink.ink, if (nearest) Signature else Color.White, 9f, if (nearest) 3.5f else 2f, id = g.id, label = g.name)
    } + listOfNotNull(here?.let { MapContent.Dot(it, Color(0xFF378ADD), Color.White, 7f, 2.5f) })

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val half = maxHeight * 0.55f
        val sheet = rememberBottomSheetScaffoldState()
        BottomSheetScaffold(
            scaffoldState = sheet,
            sheetPeekHeight = half,
            sheetContainerColor = ink.ground,
            sheetShadowElevation = 8.dp,
            sheetContent = {
                // Up, a strip of the map stays above the sheet.
                Column(Modifier.fillMaxHeight(0.9f)) {
                    Surface(Modifier.fillMaxWidth().padding(horizontal = 12.dp), shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp), color = ink.surface) {
                        header()
                    }
                    Spacer(Modifier.size(12.dp))
                    LazyColumn(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp),
                        contentPadding = PaddingValues(bottom = 24.dp), content = board)
                }
            },
        ) { inner ->
            Box(Modifier.fillMaxSize()) {
                PublitMap(MapContent(dots = dots), framed ?: MapCamera.Around(here ?: LatLon(48.2082, 16.3738), 1500.0),
                    modifier = Modifier.fillMaxSize().padding(bottom = inner.calculateBottomPadding()),
                    description = L10n.t("Map"),
                    onTap = { id -> state.groups.firstOrNull { it.id == id }?.let(onOpenStop) })
                Row(Modifier.statusBarsPadding().padding(8.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    MapKey(Icons.Filled.Settings, L10n.t("Settings"), onSettings)
                    Spacer(Modifier.weight(1f))
                    MapKey(Icons.Filled.MyLocation, L10n.t("My location")) { recenter++ }
                    Spacer(Modifier.width(8.dp))
                    MapKey(Icons.Filled.Map, L10n.t("Station map"), onOpenMap)
                }
            }
        }
    }
}

@Composable
private fun MapKey(icon: ImageVector, label: String, onClick: () -> Unit) {
    val ink = LocalInk.current
    Surface(onClick = onClick, shape = CircleShape, color = ink.surface, shadowElevation = 3.dp, modifier = Modifier.size(44.dp)) {
        Box(contentAlignment = Alignment.Center) { Icon(icon, label) }
    }
}
