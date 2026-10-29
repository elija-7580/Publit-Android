package io.github.elija7580.publit.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** "Ink & Dot": warm grey ground, white cards, ink for the main action, yellow dot as the signature. */
class Ink(
    val ground: Color, val surface: Color, val fill: Color, val ink: Color, val onInk: Color,
    val muted: Color, val hairline: Color, val live: Color, val delay: Color, val danger: Color, val dark: Boolean,
)

val LightInk = Ink(Color(0xFFF4F4F2), Color(0xFFFFFFFF), Color(0xFFEBEBE7), Color(0xFF111111), Color(0xFFFFFFFF),
    Color(0xFF6E6E6A), Color(0xFFE2E2DE), Color(0xFF1B7A33), Color(0xFFB25000), Color(0xFFD70015), false)
val DarkInk = Ink(Color(0xFF0B0B0C), Color(0xFF19191B), Color(0xFF2A2A2D), Color(0xFFF4F4F2), Color(0xFF111111),
    Color(0xFF98989D), Color(0xFF2C2C2F), Color(0xFF30D158), Color(0xFFFF9F0A), Color(0xFFFF453A), true)
val Signature = Color(0xFFFFC531)

val LocalInk = staticCompositionLocalOf { LightInk }

@Composable
fun PublitTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ink = if (dark) DarkInk else LightInk
    val base = if (dark) darkColorScheme() else lightColorScheme()
    val scheme = base.copy(
        primary = ink.ink, onPrimary = ink.onInk, background = ink.ground, onBackground = ink.ink,
        surface = ink.ground, onSurface = ink.ink, surfaceVariant = ink.fill, onSurfaceVariant = ink.muted,
        outlineVariant = ink.hairline, error = ink.danger, surfaceContainer = ink.surface,
        surfaceContainerHigh = ink.surface, secondaryContainer = ink.fill, onSecondaryContainer = ink.ink,
    )
    CompositionLocalProvider(LocalInk provides ink) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
