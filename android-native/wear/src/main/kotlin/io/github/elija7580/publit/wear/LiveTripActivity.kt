// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2026 Elija Samuel Friedrich-Ulrich

package io.github.elija7580.publit.wear

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.material.*
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The trip under way, sent by the phone: what to do now with a labelled countdown, the vehicle (line, direction,
 * platform, time), how far along the trip is, and every stage with its time, the current one highlighted.
 */
class LiveTripActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val flow = LiveTripStore.trip(this)
        setContent {
            MaterialTheme(colors = Colors(primary = Color.White, onPrimary = Color.Black)) {
                val trip by flow.collectAsStateWithLifecycle()
                var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
                LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(1_000) } }
                val list = rememberScalingLazyListState()
                Scaffold(timeText = { TimeText() }, positionIndicator = { PositionIndicator(list) }, vignette = { Vignette(vignettePosition = VignettePosition.TopAndBottom) }) {
                    val t = trip
                    ScalingLazyColumn(state = list, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 30.dp)) {
                        if (t == null) {
                            item { Text(stringResource(R.string.no_live_trip), textAlign = TextAlign.Center, modifier = Modifier.padding(8.dp)) }
                            return@ScalingLazyColumn
                        }
                        item {
                            Text("● " + stringResource(R.string.live), color = Green, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                        item {
                            Text(t.headline, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, maxLines = 3)
                        }
                        val cue = LiveTripStore.cueLabel(this@LiveTripActivity, t.cue)
                        if (cue.isNotEmpty() && t.next > now) item {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(cue, fontSize = 12.sp, color = Color.LightGray)
                                Text(countdown(t.next - now), fontSize = 30.sp, fontWeight = FontWeight.Bold)
                                if (t.delay > 0) Text("+${t.delay} min", fontSize = 12.sp, color = Amber, fontWeight = FontWeight.Bold)
                            }
                        }
                        if (t.line != null) item { VehicleRow(t) }
                        if (t.legs.any { it.points.size > 1 }) item {
                            Chip(onClick = { startActivity(android.content.Intent(this@LiveTripActivity, LiveMapActivity::class.java)) },
                                label = { Text(stringResource(R.string.map)) },
                                icon = { Text("🗺") },
                                colors = ChipDefaults.secondaryChipColors(),
                                modifier = Modifier.fillMaxWidth())
                        }
                        if (t.phase == "BEFORE_LEAVING" && t.boardAt != null) item {
                            Text(stringResource(R.string.from_stop, t.boardAt), fontSize = 12.sp, color = Color.LightGray, textAlign = TextAlign.Center)
                        } else if ((t.riding || t.line == null) && t.detail.isNotEmpty()) item {
                            Text(t.detail, fontSize = 12.sp, color = Color.LightGray, textAlign = TextAlign.Center, maxLines = 3)
                        }
                        if (t.riding && t.then != null) item {
                            Text(listOfNotNull(t.then, t.platform).joinToString(" · "), fontSize = 12.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                        }
                        item { Progress(t, now) }
                        item {
                            Text("🏁 " + t.destination + " · " + clock(t.end), fontSize = 12.sp, textAlign = TextAlign.Center, maxLines = 2)
                        }
                        if (t.stages.isNotEmpty()) {
                            item { Text(stringResource(R.string.stages), fontSize = 12.sp, color = Color.Gray, modifier = Modifier.padding(top = 6.dp)) }
                            t.stages.forEach { stage -> item { StageRow(stage) } }
                        }
                    }
                }
            }
        }
    }
}

private val Green = Color(0xFF72BC8F)
private val Amber = Color(0xFFEAC26B)

private fun parseColor(hex: String?, fallback: Color): Color = runCatching {
    Color(android.graphics.Color.parseColor("#" + hex!!.removePrefix("#")))
}.getOrDefault(fallback)

private fun countdown(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600; val m = (total % 3600) / 60; val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

private fun clock(ms: Long): String =
    DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(ms))

@Composable private fun VehicleRow(t: LiveTrip) = Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center, modifier = Modifier.fillMaxWidth()) {
        Text(t.line ?: "", fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 1,
            color = parseColor(t.lineText, Color.White),
            modifier = Modifier.clip(RoundedCornerShape(5.dp)).background(parseColor(t.lineColor, Color.DarkGray)).padding(horizontal = 6.dp, vertical = 1.dp))
        t.direction?.let { Text(" → $it", fontSize = 13.sp, maxLines = 1, modifier = Modifier.weight(1f, fill = false)) }
    }
    Spacer(Modifier.height(2.dp))
    Row(horizontalArrangement = Arrangement.Center, modifier = Modifier.fillMaxWidth()) {
        if (!t.riding) t.platform?.let {
            Text((if (t.platformChanged) "⚠ " else "") + it + "  ", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = if (t.platformChanged) Amber else Color.White)
        }
        t.legTime?.let { Text(clock(it), fontSize = 12.sp, fontWeight = FontWeight.Bold) }
    }
}

@Composable private fun Progress(t: LiveTrip, now: Long) {
    val total = (t.end - t.start).coerceAtLeast(1)
    val done = ((now - t.start).toFloat() / total).coerceIn(0f, 1f)
    Box(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(Color.DarkGray)) {
        Box(Modifier.fillMaxWidth(done).fillMaxHeight().background(parseColor(t.lineColor, Green)))
    }
}

@Composable private fun StageRow(stage: LiveTrip.Stage) {
    Text(
        (if (stage.done) "✓ " else if (stage.now) "▶ " else "· ") + stage.text,
        fontSize = 12.sp, maxLines = 2,
        fontWeight = if (stage.now) FontWeight.Bold else FontWeight.Normal,
        color = if (stage.done) Color.Gray else Color.White,
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
    )
}
