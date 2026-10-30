package io.github.elija7580.publit.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * Shown for a moment at launch while location and the first departures load underneath (LaunchSplash on iOS):
 * the "p" of the logo is drawn like a line on a map, then its stop appears.
 */
@Composable
fun LaunchSplash() {
    val drawn = remember { Animatable(0f) }
    val stop = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        launch { drawn.animateTo(1f, tween(550, easing = FastOutSlowInEasing)) }
        launch { kotlinx.coroutines.delay(450); stop.animateTo(1f, spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMediumLow)) }
    }
    Box(Modifier.fillMaxSize().background(LocalInk.current.ground), contentAlignment = Alignment.Center) {
        Box(Modifier.size(112.dp).background(Color(0xFF111111), RoundedCornerShape(112.dp * 0.225f))) {
            Canvas(Modifier.fillMaxSize()) {
                val s = size.minDimension / 1024f
                fun pt(x: Float, y: Float) = Offset(x * s, y * s)
                // Same geometry as the logo (1024-unit grid), drawn from the bottom up.
                val full = Path().apply {
                    moveTo(362 * s, 772 * s); lineTo(362 * s, 402 * s)
                    arcTo(Rect(pt(362f, 252f), pt(662f, 552f)), 180f, 270f, false)
                    lineTo(362 * s, 552 * s)
                }
                val measure = PathMeasure().apply { setPath(full, false) }
                val part = Path()
                measure.getSegment(0f, measure.length * drawn.value, part, true)
                drawPath(part, Color.White, style = Stroke(96 * s, cap = StrokeCap.Round, join = StrokeJoin.Round))
                if (stop.value > 0f) drawCircle(Color(0xFFFFC531).copy(alpha = stop.value.coerceIn(0f, 1f)),
                    radius = 58 * s * (0.1f + 0.9f * stop.value), center = Offset(size.width / 2, size.height / 2 + (402 - 512) * s))
            }
        }
    }
}
