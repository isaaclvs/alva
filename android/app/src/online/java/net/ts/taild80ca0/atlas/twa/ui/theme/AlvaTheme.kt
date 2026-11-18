package net.ts.taild80ca0.atlas.twa.ui.theme

import android.graphics.drawable.ColorDrawable
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb

internal val LocalAlvaColors = staticCompositionLocalOf { AlvaPalettes.TealDark }

/**
 * Access to the tokens inside [AlvaTheme]: `AlvaTheme.colors.accent`, `AlvaTheme.type.row`,
 * `AlvaTheme.space.lg`, `AlvaTheme.radius.card`, `AlvaTheme.icon.bar`. The dimension scales live in
 * `AlvaDimens.kt` as plain objects, like [AlvaShapes], so screens name a token instead of a length.
 */
object AlvaTheme {
    val colors: AlvaColors
        @Composable @ReadOnlyComposable get() = LocalAlvaColors.current
    val type: AlvaTextStyles
        get() = AlvaTypographyTokens
    val space: AlvaSpacing
        get() = AlvaSpacing
    val radius: AlvaRadius
        get() = AlvaRadius
    val elevation: AlvaElevation
        get() = AlvaElevation
    val icon: AlvaIconSize
        get() = AlvaIconSize
    val size: AlvaSize
        get() = AlvaSize
}

/**
 * The Alva look: PWA colors for [dark]/light and [color] teal/pink, Nunito Sans, PWA shapes.
 * Defaults are the PWA's (dark, teal); MainActivity loads the device preferences before drawing.
 * Stock Material components are mapped onto the tokens, so screens only name tokens for what Material lacks.
 */
@Composable
fun AlvaTheme(dark: Boolean = true, color: AlvaColor = AlvaColor.Teal, content: @Composable () -> Unit) {
    val colors = AlvaPalettes.of(dark, color)
    CompositionLocalProvider(LocalAlvaColors provides colors) {
        MaterialTheme(
            colorScheme = colors.toMaterial(),
            typography = alvaMaterialTypography(),
            shapes = Shapes(
                extraSmall = AlvaShapes.Control, small = AlvaShapes.Control,
                medium = AlvaShapes.Card, large = AlvaShapes.Card, extraLarge = AlvaShapes.Card,
            ),
            content = content,
        )
    }
}

internal fun AlvaColors.toMaterial() = (if (dark) darkColorScheme() else lightColorScheme()).copy(
    primary = accent, onPrimary = accentInk,
    primaryContainer = accentSoft, onPrimaryContainer = accentStrong,
    secondary = accent, onSecondary = accentInk,
    secondaryContainer = accentSoft, onSecondaryContainer = accentStrong,
    tertiary = accent, onTertiary = accentInk,
    background = canvas, onBackground = ink,
    surface = surface, onSurface = ink,
    surfaceVariant = surface2, onSurfaceVariant = inkSoft,
    surfaceTint = Color.Transparent,
    surfaceDim = canvas, surfaceBright = surface,
    surfaceContainerLowest = canvas, surfaceContainerLow = surface, surfaceContainer = surface,
    surfaceContainerHigh = surface2, surfaceContainerHighest = surface2,
    outline = inkFaint, outlineVariant = line,
    error = danger, onError = canvas,
    errorContainer = surface2, onErrorContainer = danger,
    inverseSurface = ink, inverseOnSurface = canvas, inversePrimary = accentStrong,
    scrim = Color.Black,
)

/**
 * Paints the status and navigation bars (and the window behind them) with the PWA's
 * `theme-color`, the page color of the current palette, and picks light or dark icons for it.
 */
fun ComponentActivity.applyAlvaSystemBars(colors: AlvaColors) {
    val bar = colors.systemBar.toArgb()
    val style = if (colors.dark) SystemBarStyle.dark(bar) else SystemBarStyle.light(bar, bar)
    enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
    window.setBackgroundDrawable(ColorDrawable(bar))
}
