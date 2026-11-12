package net.ts.taild80ca0.atlas.twa.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/** The device's accent color preference, like the PWA's `data-color` (teal is the default). */
enum class AlvaColor { Teal, Pink }

/**
 * The tint of a category icon (`data-tone` in the PWA). Bakery shares the pantry hue,
 * and [Other] has no hue: it uses the neutral surface colors.
 */
enum class CategoryTone { Produce, Dairy, Pantry, Bakery, Meat, Drinks, Cleaning, Hygiene, Other }

/** Icon color and the translucent chip behind it. */
@Immutable
class ToneColors(val icon: Color, val background: Color)

/**
 * Semantic color tokens, one set per theme (dark/light) and accent color (teal/pink).
 * The names are the PWA's: `--color-canvas` is [canvas], `--color-ink-soft` is [inkSoft]…
 * The values live in [AlvaPalettes] and are checked against `app/assets/tailwind`
 * by the JVM tests, so a CSS change that is not copied here fails the build.
 */
@Immutable
class AlvaColors(
    val dark: Boolean,
    val color: AlvaColor,
    val canvas: Color,
    val surface: Color,
    val surface2: Color,
    val line: Color,
    val ink: Color,
    val inkSoft: Color,
    val inkFaint: Color,
    val accent: Color,
    val accentStrong: Color,
    val accentInk: Color,
    val accentSoft: Color,
    val warn: Color,
    val danger: Color,
    private val tones: Map<CategoryTone, ToneColors>,
) {
    /** Status and navigation bars use the page color, which is also the PWA's `theme-color`. */
    val systemBar: Color get() = canvas

    fun tone(tone: CategoryTone): ToneColors = tones.getValue(tone)
}
