package net.ts.taild80ca0.atlas.twa.ui.theme

import android.graphics.drawable.ColorDrawable
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.unit.dp

internal val LocalAlvaColors = staticCompositionLocalOf { AlvaPalettes.TealDark }

/** Rounded corners the PWA uses: `rounded-xl` (controls), `rounded-2xl` (cards, fields), `rounded-full`. */
object AlvaShapes {
    val Control = RoundedCornerShape(12.dp)
    val Card = RoundedCornerShape(16.dp)
}

/** Access to the tokens inside [AlvaTheme]: `AlvaTheme.colors.accent`, `AlvaTheme.type.row`. */
object AlvaTheme {
    val colors: AlvaColors
        @Composable @ReadOnlyComposable get() = LocalAlvaColors.current
    val type: AlvaTextStyles
        get() = AlvaTypographyTokens
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
