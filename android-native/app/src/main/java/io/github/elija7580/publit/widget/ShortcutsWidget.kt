// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2026 Elija Samuel Friedrich-Ulrich

package io.github.elija7580.publit.widget

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import io.github.elija7580.publit.core.L10n
import io.github.elija7580.publit.data.PlaceRole
import io.github.elija7580.publit.data.SavedPlace

/**
 * Home, Work and saved places on the home screen (ShortcutsWidget on iOS, Publit#6): one tap opens the
 * routes from where you are, leaving now. Without Home or Work it opens Saved to set them.
 */
class ShortcutsWidget : GlanceAppWidget() {
    override val sizeMode: SizeMode = SizeMode.Exact

    /** One tile: title, detail (the place's name), and the link it opens. */
    data class Tile(val symbol: String, val title: String, val detail: String?, val link: String)

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val app = context.applicationContext as io.github.elija7580.publit.PublitApplication
        val tiles = tiles(app.favorites.state.value.items)
        val empty = L10n.t("Set Home and Work in Saved")
        provideContent { Content(context, tiles, empty) }
    }

    companion object {
        suspend fun refreshAll(context: Context) = ShortcutsWidget().updateAll(context)

        /** Home, Work, then saved places, at most four (pure, unit-tested). */
        fun tiles(items: List<SavedPlace>): List<Tile> {
            val roles = PlaceRole.entries.mapNotNull { r ->
                items.firstOrNull { it.role == r }?.let { p ->
                    Tile(if (r == PlaceRole.HOME) "⌂" else "▣", r.title, p.name, "publit://go/" + if (r == PlaceRole.HOME) "home" else "work")
                }
            }
            // Own shortcuts (iOS Publit#22) before other saved places.
            val places = items.filter { it.role == null && !it.isStop }.sortedBy { if (it.isShortcut) 0 else 1 }.map { p ->
                Tile(if (p.isShortcut) "★" else "●", p.name, null, "publit://place?name=" + java.net.URLEncoder.encode(p.name, "UTF-8").replace("+", "%20") + "&lat=" + p.lat + "&lon=" + p.lon)
            }
            return (roles + places).take(4)
        }

        fun intent(context: Context, link: String): Intent =
            Intent(Intent.ACTION_VIEW, Uri.parse(link), context, io.github.elija7580.publit.MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }
}

class ShortcutsWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = ShortcutsWidget()
}

private fun color(context: Context, name: String, fallback: Color): ColorProvider {
    val id = context.resources.getIdentifier(name, "color", context.packageName)
    return if (id != 0) ColorProvider(id) else ColorProvider(fallback)
}

@Composable
private fun Content(context: Context, tiles: List<ShortcutsWidget.Tile>, empty: String) {
    val ink = color(context, "publit_ink", Color(0xFF111111))
    val ground = color(context, "publit_ground", Color(0xFFF4F4F2))
    val fill = color(context, "publit_surface", Color(0xFFFFFFFF))
    val dot = color(context, "publit_signature", Color(0xFFFFC531))
    val size = LocalSize.current
    Column(GlanceModifier.fillMaxSize().background(ground).cornerRadius(18.dp).padding(8.dp)) {
        if (tiles.isEmpty()) {
            Column(GlanceModifier.fillMaxSize().padding(4.dp).clickable(actionStartActivity(ShortcutsWidget.intent(context, "publit://go/home")))) {
                Text("●", style = TextStyle(color = dot, fontSize = 18.sp))
                Text(empty, maxLines = 3, style = TextStyle(color = ink, fontSize = 13.sp, fontWeight = FontWeight.Bold))
            }
            return@Column
        }
        // Small: one column; wide: two tiles per row.
        val perRow = if (size.width >= 220.dp && tiles.size > 1) 2 else 1
        val rows = tiles.chunked(perRow).take(if (perRow == 1) maxOf(1, ((size.height.value - 16) / 56).toInt()) else 2)
        rows.forEachIndexed { i, row ->
            if (i > 0) Spacer(GlanceModifier.height(6.dp))
            Row(GlanceModifier.fillMaxWidth().defaultWeight()) {
                row.forEachIndexed { j, t ->
                    if (j > 0) Spacer(GlanceModifier.width(6.dp))
                    Row(GlanceModifier.defaultWeight().fillMaxSize().background(fill).cornerRadius(14.dp).padding(horizontal = 10.dp)
                        .clickable(actionStartActivity(ShortcutsWidget.intent(context, t.link))), verticalAlignment = Alignment.CenterVertically) {
                        Text(t.symbol, style = TextStyle(color = ink, fontSize = 18.sp, fontWeight = FontWeight.Bold))
                        Spacer(GlanceModifier.width(8.dp))
                        Column {
                            Text(t.title, maxLines = 1, style = TextStyle(color = ink, fontSize = 14.sp, fontWeight = FontWeight.Bold))
                            t.detail?.let { Text(it, maxLines = 1, style = TextStyle(color = ink, fontSize = 11.sp)) }
                        }
                    }
                }
            }
        }
    }
}
