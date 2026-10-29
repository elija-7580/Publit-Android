package io.github.elija7580.publit.ui

import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.filled.DirectionsBoat
import androidx.compose.material.icons.filled.DirectionsBus
import androidx.compose.material.icons.filled.DirectionsRailway
import androidx.compose.material.icons.filled.Subway
import androidx.compose.material.icons.filled.Tram
import androidx.compose.material.icons.filled.Train
import androidx.compose.material.icons.filled.Terrain
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.elija7580.publit.core.Fmt
import io.github.elija7580.publit.core.L10n
import io.github.elija7580.publit.data.LineColors
import io.github.elija7580.publit.data.ModeGroup
import io.github.elija7580.publit.data.ModeStyle
import io.github.elija7580.publit.data.StopGroup
import io.github.elija7580.publit.data.StopTime
import io.github.elija7580.publit.data.Reach
import java.time.Instant

fun hexColor(hex: String): Color = Color(("FF" + hex).toLong(16).toInt())

fun modeIcon(mode: String): ImageVector = when (ModeGroup.of(mode)) {
    ModeGroup.BUS -> Icons.Filled.DirectionsBus
    ModeGroup.TRAM -> Icons.Filled.Tram
    ModeGroup.SUBWAY -> Icons.Filled.Subway
    ModeGroup.FERRY -> Icons.Filled.DirectionsBoat
    ModeGroup.SUBURBAN -> Icons.Filled.DirectionsRailway
    ModeGroup.OTHER -> Icons.Filled.Terrain
    ModeGroup.TRAIN -> Icons.Filled.Train
}

@Composable
fun LineBadge(stopTime: io.github.elija7580.publit.data.RouteStyled) {
    val ink = LocalInk.current
    val colors = stopTime.lineColors
    val bg = hexColor(colors.background)
    val edge = if (LineColors.blendsIn(colors.background, ink.dark)) ink.ink.copy(alpha = 0.45f) else ink.ink.copy(alpha = 0.12f)
    Row(
        Modifier.defaultMinSize(minWidth = 44.dp).clip(RoundedCornerShape(6.dp)).background(bg)
            .border(BorderStroke(1.dp, edge), RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(modeIcon(stopTime.mode), null, Modifier.size(16.dp), tint = hexColor(colors.text))
        Text(stopTime.lineName, color = hexColor(colors.text), fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 2)
    }
}

@Composable
fun DirectionLabel(stopTime: StopTime) {
    val ink = LocalInk.current
    val dir = stopTime.shownDirection
    when {
        dir != null -> Row {
            Text(L10n.t("Towards") + " ", color = ink.muted, fontSize = 14.sp)
            Text(dir, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        stopTime.isCircular -> Text(L10n.t("Circle line"), fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        else -> Text(L10n.t("Direction unavailable"), color = ink.muted, fontSize = 14.sp)
    }
}

/** The minutes are the figure; clock time, delay and live state small underneath. */
@Composable
fun DepartureTime(st: StopTime, now: Instant, offline: Boolean = false) {
    val ink = LocalInk.current
    val minutes = Math.ceil((st.departureTime.epochSecond - now.epochSecond) / 60.0).toInt()
    val delayed = st.delayMinutes > 0
    Column(horizontalAlignment = Alignment.End) {
        if (st.isCancelled) {
            Text(Fmt.time(st.departureTime, st.place.tz), color = ink.danger, fontWeight = FontWeight.Bold, fontSize = 20.sp,
                style = MaterialTheme.typography.titleMedium.copy(textDecoration = androidx.compose.ui.text.style.TextDecoration.LineThrough))
            Text(L10n.t("Cancelled"), color = ink.danger, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
        } else {
            when {
                minutes <= 0 -> Text(L10n.t("now"), fontWeight = FontWeight.Bold, fontSize = 28.sp)
                minutes < 60 -> Row(verticalAlignment = Alignment.Bottom) {
                    Text(minutes.toString(), fontWeight = FontWeight.Bold, fontSize = 28.sp, color = if (delayed) ink.delay else ink.ink)
                    Text(" min", color = ink.muted, fontSize = 12.sp, modifier = Modifier.padding(bottom = 4.dp))
                }
                else -> Text(Fmt.time(st.departureTime, st.place.tz), fontWeight = FontWeight.Bold, fontSize = 20.sp)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (delayed) Text(L10n.f("+%lld min", st.delayMinutes), color = ink.delay, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                if (minutes < 60 || delayed) Text(Fmt.time(st.departureTime, st.place.tz), color = ink.muted, fontSize = 12.sp)
            }
            val live = st.realTime && !offline
            Text(if (live) L10n.t("Live") else L10n.t("Timetable"), color = if (live) ink.live else ink.muted, fontSize = 11.sp, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
fun DepartureRow(st: StopTime, now: Instant, onClick: (() -> Unit)? = null) {
    val ink = LocalInk.current
    Row((if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LineBadge(st)
                st.serviceDetail?.let { Text(it, fontWeight = FontWeight.SemiBold, fontSize = 12.sp) }
            }
            DirectionLabel(st)
            TransitLabelsPlatform(st)
            if (st.isReplacementBus) Text(L10n.t("Rail replacement bus"), color = ink.delay, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
            st.serviceAlerts.firstOrNull()?.let {
                Row(verticalAlignment = Alignment.Top) {
                    Icon(Icons.Filled.Warning, null, Modifier.size(14.dp).padding(top = 2.dp), tint = ink.delay)
                    Text(" " + it.headerText, color = ink.delay, fontSize = 12.sp)
                }
            }
        }
        Spacer(Modifier.width(12.dp))
        DepartureTime(st, now)
    }
}

@Composable
private fun TransitLabelsPlatform(st: StopTime) {
    val track = st.place.track?.trim()?.takeIf { it.isNotEmpty() } ?: return
    Text(L10n.f("Platform %@", track), color = LocalInk.current.muted, fontSize = 12.sp)
}

@Composable
fun StationHeader(group: StopGroup, nearest: Boolean) {
    val ink = LocalInk.current
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(group.name, fontWeight = FontWeight.Bold, fontSize = 20.sp, modifier = Modifier.weight(1f), maxLines = 2)
            group.distance?.let { d ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    if (nearest) Box(Modifier.size(7.dp).clip(CircleShape).background(Signature))
                    Text(Fmt.distance(d), color = ink.muted, fontWeight = FontWeight.Medium, fontSize = 14.sp)
                    if (d >= Reach.WALK_HINT_DISTANCE) {
                        Icon(Icons.AutoMirrored.Filled.DirectionsWalk, null, Modifier.size(15.dp), tint = ink.muted)
                        Text(L10n.f("%lld min", Reach.walkMinutes(d)), color = ink.muted, fontWeight = FontWeight.Medium, fontSize = 14.sp)
                    }
                }
            }
        }
        if (group.isPole && group.directions.isNotEmpty()) {
            Text("→ " + group.directions.joinToString(" · "), color = ink.muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
fun ModeChips(available: List<ModeGroup>, selected: Set<ModeGroup>, active: Boolean, onToggle: (ModeGroup) -> Unit) {
    androidx.compose.foundation.lazy.LazyRow(
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(available.size) { i ->
            val g = available[i]
            FilterChip(selected = active && g in selected, onClick = { onToggle(g) },
                label = { Text(g.title) }, leadingIcon = { Icon(modeIcon(g.mode), null, Modifier.size(18.dp)) })
        }
    }
}

/** "Offline. Showing data from 14:05." while screens show a disk copy (OfflineNote on iOS). */
@Composable
fun OfflineNote(client: io.github.elija7580.publit.data.TransitousClient) {
    val from by client.offlineFrom.collectAsState()
    val at = from ?: return
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Filled.WifiOff, null, Modifier.size(14.dp), tint = LocalInk.current.muted)
        Text(" " + L10n.f("Offline. Showing data from %@.", io.github.elija7580.publit.core.Fmt.time(Instant.ofEpochMilli(at))),
            color = LocalInk.current.muted, fontSize = 13.sp)
    }
}
