package io.github.elija7580.publit.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.elija7580.publit.core.L10n
import io.github.elija7580.publit.data.CoverageNotice

/** "Not reliable here": open timetable data is missing at a place, and which data (CoverageBanner on iOS). */
@Composable
fun CoverageBanner(notices: List<CoverageNotice>, compact: Boolean = false, modifier: Modifier = Modifier.padding(horizontal = 12.dp)) {
    val first = notices.firstOrNull() ?: return
    val ink = LocalInk.current
    val ctx = LocalContext.current
    Column(modifier.fillMaxWidth().background(ink.delay.copy(alpha = 0.13f), RoundedCornerShape(14.dp))
        .border(1.dp, ink.delay.copy(alpha = 0.55f), RoundedCornerShape(14.dp)).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Warning, null, Modifier.size(18.dp), tint = ink.delay); Spacer(Modifier.width(6.dp))
            Text(L10n.t("Not reliable here"), fontWeight = FontWeight.Bold, fontSize = 15.sp)
        }
        notices.forEach { n -> Column { Text(n.heading, fontWeight = FontWeight.SemiBold, fontSize = 13.sp); Text(n.area.summary, fontSize = 13.sp) } }
        if (!compact) {
            Text(L10n.t("Publit uses open timetable data (Transitous). Where it is missing, connections can be incomplete or wrong."), color = ink.muted, fontSize = 12.sp)
            Text(L10n.t("Coverage map"), fontWeight = FontWeight.SemiBold, fontSize = 12.sp, modifier = Modifier.clickable {
                runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(first.area.mapUrl)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            })
        }
    }
}

/** Over the map: the warning for the area in the middle, small; a tap opens the whole explanation. */
@Composable
fun CoverageMapNote(notice: CoverageNotice) {
    var open by remember(notice.area.id) { mutableStateOf(false) }
    val ink = LocalInk.current
    if (open) Column(Modifier.background(ink.surface, RoundedCornerShape(14.dp)).clickable { open = false }) { CoverageBanner(listOf(notice), modifier = Modifier) }
    else Row(Modifier.background(ink.surface, RoundedCornerShape(14.dp)).border(1.dp, ink.delay.copy(alpha = 0.6f), RoundedCornerShape(14.dp))
        .clickable { open = true }.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Filled.Warning, null, Modifier.size(16.dp), tint = ink.delay); Spacer(Modifier.width(8.dp))
        Column { Text(L10n.t("Not reliable here"), fontWeight = FontWeight.Bold, fontSize = 13.sp); Text(notice.area.summary, color = ink.muted, fontSize = 12.sp, maxLines = 2) }
    }
}
