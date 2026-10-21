// Publit for Android: small Compose building blocks that SwiftUI on Skip has no equivalent for.
package publit.module

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.Modifier
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.vector.toPath

/** SwiftUI children laid out in rows that wrap (FlowRow in ItineraryDetailView and LinesView). */
class FlowComposer(val spacing: Double, columnCount: Double, val content: skip.ui.View?) : skip.ui.ContentComposer {
    val columns = columnCount.toInt()

    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    override fun Compose(context: skip.ui.ComposeContext) {
        val view = content ?: return
        val renderables = view.Evaluate(context = context, options = 0)
        val contentContext = context.content()
        if (columns > 0) {
            // Equal columns: every cell the same share of the row.
            FlowRow(
                modifier = context.modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(spacing.dp),
                verticalArrangement = Arrangement.spacedBy(spacing.dp),
                maxItemsInEachRow = columns
            ) {
                for (r in renderables) {
                    Box(modifier = Modifier.weight(1f).fillMaxHeight()) { r.Render(context = contentContext) }
                }
                // Fill the last row so its cells keep the same width.
                val rest = (columns - renderables.size % columns) % columns
                repeat(rest) { Spacer(modifier = Modifier.weight(1f)) }
            }
            return
        }
        FlowRow(
            modifier = context.modifier,
            horizontalArrangement = Arrangement.spacedBy(spacing.dp),
            verticalArrangement = Arrangement.spacedBy(spacing.dp),
            itemVerticalAlignment = Alignment.CenterVertically
        ) {
            for (r in renderables) r.Render(context = contentContext)
        }
    }
}

/** Material icons drawn into bitmaps (launcher shortcuts, notifications). */
object PublitIcons {
    fun draw(c: android.graphics.Canvas, v: androidx.compose.ui.graphics.vector.ImageVector, cx: Float, cy: Float, size: Float, color: Int) {
        val scale = size / kotlin.math.max(v.viewportWidth, v.viewportHeight)
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        paint.color = color; paint.style = android.graphics.Paint.Style.FILL
        c.save()
        c.translate(cx - v.viewportWidth * scale / 2, cy - v.viewportHeight * scale / 2)
        c.scale(scale, scale)
        fun walk(g: androidx.compose.ui.graphics.vector.VectorGroup) {
            for (node in g) {
                when (node) {
                    is androidx.compose.ui.graphics.vector.VectorPath ->
                        c.drawPath(node.pathData.toPath().asAndroidPath(), paint)
                    is androidx.compose.ui.graphics.vector.VectorGroup -> walk(node)
                }
            }
        }
        walk(v.root)
        c.restore()
    }

    /** A round badge with the icon in the middle, `sizeDp` across. */
    fun bitmap(ctx: android.content.Context, v: androidx.compose.ui.graphics.vector.ImageVector, fg: Int, bg: Int, sizeDp: Int): android.graphics.Bitmap {
        val px = (sizeDp * ctx.resources.displayMetrics.density).toInt().coerceAtLeast(24)
        val bmp = android.graphics.Bitmap.createBitmap(px, px, android.graphics.Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(bmp)
        val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG); p.color = bg
        c.drawCircle(px / 2f, px / 2f, px / 2f, p)
        draw(c, v, px / 2f, px / 2f, px * 0.58f, fg)
        return bmp
    }
}
