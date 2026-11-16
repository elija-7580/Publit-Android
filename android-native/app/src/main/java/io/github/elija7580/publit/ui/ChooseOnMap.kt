package io.github.elija7580.publit.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Place
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.elija7580.publit.core.L10n
import io.github.elija7580.publit.core.LatLon
import io.github.elija7580.publit.data.MapPick
import kotlinx.coroutines.delay

/**
 * "Choose on map" from the From and To fields (ChooseOnMapView on iOS): a pin in the middle of the map.
 * Move the map, or tap a spot, to put the pin there; "Confirm location" fills the field with that point,
 * named after its street once known. Nothing else in the planner changes.
 */
@Composable
fun ChooseOnMapScreen(start: suspend () -> LatLon?, addressAt: suspend (LatLon) -> String?, onBack: () -> Unit, onConfirm: (Endpoint.At) -> Unit) {
    val ink = LocalInk.current
    BackHandler(onBack = onBack)
    // Until a fix is known the map shows central Europe from afar; the pin only counts once the map has settled.
    var camera by remember { mutableStateOf<MapCamera>(MapCamera.Around(LatLon(48.5, 12.0), 1_500_000.0)) }
    var center by remember { mutableStateOf<LatLon?>(null) }
    var span by remember { mutableStateOf(600.0) }
    var address by remember { mutableStateOf<String?>(null) }
    var lookingUp by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { start()?.takeIf { it.isValid }?.let { camera = MapCamera.Around(it, 600.0) } }
    // A later move wins: the lookup for an older point is cancelled.
    LaunchedEffect(center) {
        val c = center ?: return@LaunchedEffect
        address = null; lookingUp = true
        delay(300)
        address = addressAt(c)
        lookingUp = false
    }
    Column(Modifier.fillMaxSize().background(ink.ground).statusBarsPadding().navigationBarsPadding()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, L10n.t("Back")) }
            Text(L10n.t("Choose on map"), fontWeight = FontWeight.Bold, fontSize = 18.sp)
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            PublitMap(MapContent(), camera, Modifier.fillMaxSize(), base = "standard",
                onRegion = { r -> if (r.center.isValid) { center = r.center; span = MapPick.spanMeters(r.latSpan) } },
                // A tap puts the pin there: the map moves so the tapped spot lies under the pin.
                onTapAt = { p -> camera = MapCamera.Around(p, span) })
            CenterPin(Modifier.align(Alignment.Center))
        }
        Surface(Modifier.fillMaxWidth().padding(12.dp), shape = RoundedCornerShape(20.dp), color = ink.surface) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Place, null, tint = ink.muted)
                    Spacer(Modifier.width(8.dp))
                    val a = address
                    if (a != null) Text(a, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 2)
                    else Text(L10n.t(if (lookingUp) "Finding the address…" else "Move the map or tap to place the pin"), color = ink.muted, fontSize = 15.sp)
                }
                Spacer(Modifier.height(10.dp))
                Button(onClick = { center?.let { onConfirm(Endpoint.At(address ?: L10n.t("Dropped pin"), it)) } }, enabled = center != null,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = ink.ink, contentColor = ink.onInk)) { Text(L10n.t("Confirm location")) }
            }
        }
    }
}

/** The pin in the middle of the map, its tip (not the middle of the symbol) on the point that will be used. */
@Composable
private fun CenterPin(modifier: Modifier) {
    val ink = LocalInk.current
    Box(modifier) {
        Box(Modifier.size(6.dp, 3.dp).background(Color.Black.copy(alpha = 0.35f), CircleShape).align(Alignment.Center))
        Icon(Icons.Filled.Place, null, tint = ink.danger, modifier = Modifier.size(40.dp).align(Alignment.Center).offset(y = (-17).dp))
    }
}
