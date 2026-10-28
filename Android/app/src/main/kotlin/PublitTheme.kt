package publit.module

import skip.ui.*

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/// Publit's own palette (Module.xcassets `Palette-*`) as the Material 3 colour scheme.
///
/// Without this, Android 12+ derives every container colour from the wallpaper (dynamic colour):
/// lavender top and tab bars, tinted cards, a purple selection pill. Publit is ink on a warm
/// off-white ground with white cards, the same on every device.
internal object PublitPalette {
    fun ground(dark: Boolean) = if (dark) Color(0xFF0B0B0C) else Color(0xFFF4F4F2)
    fun surface(dark: Boolean) = if (dark) Color(0xFF19191B) else Color(0xFFFFFFFF)
    fun ink(dark: Boolean) = if (dark) Color(0xFFF4F4F2) else Color(0xFF111111)
    fun onInk(dark: Boolean) = if (dark) Color(0xFF111111) else Color(0xFFFFFFFF)
    fun fill(dark: Boolean) = if (dark) Color(0xFF2A2A2D) else Color(0xFFEBEBE7)
    fun muted(dark: Boolean) = if (dark) Color(0xFF98989D) else Color(0xFF6E6E6A)
    fun hairline(dark: Boolean) = if (dark) Color(0xFF2C2C2F) else Color(0xFFE2E2DE)
    fun danger(dark: Boolean) = if (dark) Color(0xFFFF453A) else Color(0xFFD70015)
    val signature = Color(0xFFFFC531)
}

@Composable
internal fun publitColorScheme(base: ColorScheme, dark: Boolean): ColorScheme {
    val ground = PublitPalette.ground(dark)
    val surface = PublitPalette.surface(dark)
    val ink = PublitPalette.ink(dark)
    val onInk = PublitPalette.onInk(dark)
    val fill = PublitPalette.fill(dark)
    val muted = PublitPalette.muted(dark)
    val danger = PublitPalette.danger(dark)
    return base.copy(
        primary = ink, onPrimary = onInk,
        primaryContainer = fill, onPrimaryContainer = ink,
        inversePrimary = onInk,
        secondary = ink, onSecondary = onInk,
        secondaryContainer = fill, onSecondaryContainer = ink,
        tertiary = PublitPalette.signature, onTertiary = Color(0xFF111111),
        tertiaryContainer = fill, onTertiaryContainer = ink,
        // `surface` is the screen behind the lists: SkipUI paints list corners, headers and the
        // cover over the last row's divider in surfaceColorAtElevation(3.dp). With the tint equal
        // to the surface that is exactly the ground, so section cards get round corners and no
        // stray line underneath.
        background = ground, onBackground = ink,
        surface = ground, onSurface = ink,
        surfaceVariant = fill, onSurfaceVariant = muted,
        surfaceTint = ground,
        inverseSurface = ink, inverseOnSurface = ground,
        error = danger, onError = Color.White,
        errorContainer = danger.copy(alpha = 0.12f), onErrorContainer = danger,
        outline = if (dark) Color(0xFF48484C) else Color(0xFFC9C9C4),
        outlineVariant = PublitPalette.hairline(dark),
        scrim = Color.Black,
        surfaceBright = surface, surfaceDim = ground,
        // Menus, dialogs and sheets sit on white like the iOS cards.
        surfaceContainerLowest = surface, surfaceContainerLow = surface,
        surfaceContainer = surface, surfaceContainerHigh = surface,
        surfaceContainerHighest = fill,
    )
}

/// The tab bar as on iOS: a white bar with a hairline on top, ink for the current tab, grey for the
/// rest, no Material selection pill.
@Composable
internal fun publitNavigationBar(options: Material3NavigationBarOptions, dark: Boolean): Material3NavigationBarOptions {
    val ink = PublitPalette.ink(dark)
    val muted = PublitPalette.muted(dark)
    val hairline = PublitPalette.hairline(dark)
    val bar = PublitPalette.surface(dark)
    return options.copy(
        modifier = options.modifier.drawBehind {
            drawLine(hairline, Offset(0f, 0f), Offset(size.width, 0f), strokeWidth = 1.dp.toPx())
        },
        containerColor = bar,
        contentColor = ink,
        tonalElevation = 0.dp,
        itemColors = NavigationBarItemDefaults.colors(
            selectedIconColor = ink,
            selectedTextColor = ink,
            indicatorColor = Color.Transparent,
            unselectedIconColor = muted,
            unselectedTextColor = muted,
            disabledIconColor = muted.copy(alpha = 0.4f),
            disabledTextColor = muted.copy(alpha = 0.4f),
        ),
    )
}
