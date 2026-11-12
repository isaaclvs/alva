package net.ts.taild80ca0.atlas.twa.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * The four palettes of the PWA, copied from `app/assets/tailwind/application.css`
 * (`@theme`, `html[data-theme="light"]`, `html[data-color="pink"]`) and `pink_light.css`.
 * Pink dark only re-points some tokens; `warn` and `danger` keep the base dark values,
 * as in the CSS cascade. PwaThemeParityTest parses those files and compares.
 */
object AlvaPalettes {
    fun of(dark: Boolean, color: AlvaColor): AlvaColors = when {
        dark && color == AlvaColor.Teal -> TealDark
        !dark && color == AlvaColor.Teal -> TealLight
        dark -> PinkDark
        else -> PinkLight
    }

    val all: List<AlvaColors> get() = listOf(TealDark, TealLight, PinkDark, PinkLight)

    // Category icon tints: oklch(78% .07 hue) over oklch(78% .07 hue / .12) in dark,
    // oklch(46% .07 hue) over oklch(65% .07 hue / .10) in light, converted to sRGB.
    private fun darkTones(surface2: Color, inkSoft: Color) = mapOf(
        CategoryTone.Produce to tone(0x99C49E, 0.12f),
        CategoryTone.Dairy to tone(0x8FBEE0, 0.12f),
        CategoryTone.Pantry to tone(0xD2B285, 0.12f),
        CategoryTone.Bakery to tone(0xD2B285, 0.12f),
        CategoryTone.Meat to tone(0xE1A6A9, 0.12f),
        CategoryTone.Drinks to tone(0x80C5CD, 0.12f),
        CategoryTone.Cleaning to tone(0xB2B2E3, 0.12f),
        CategoryTone.Hygiene to tone(0xD7A6C6, 0.12f),
        CategoryTone.Other to ToneColors(inkSoft, surface2),
    )

    private fun lightTones(surface2: Color, inkSoft: Color) = mapOf(
        CategoryTone.Produce to tone(0x3C6341, 0.10f, 0x729B77),
        CategoryTone.Dairy to tone(0x305D7B, 0.10f, 0x6795B6),
        CategoryTone.Pantry to tone(0x6F5228, 0.10f, 0xA9895E),
        CategoryTone.Bakery to tone(0x6F5228, 0.10f, 0xA9895E),
        CategoryTone.Meat to tone(0x7A474B, 0.10f, 0xB67E81),
        CategoryTone.Drinks to tone(0x19636B, 0.10f, 0x589CA3),
        CategoryTone.Cleaning to tone(0x54527E, 0.10f, 0x8A8AB9),
        CategoryTone.Hygiene to tone(0x724864, 0.10f, 0xAD7F9D),
        CategoryTone.Other to ToneColors(inkSoft, surface2),
    )

    private fun tone(icon: Int, alpha: Float, background: Int = icon) =
        ToneColors(Color(0xFF000000.toInt() or icon), Color(0xFF000000.toInt() or background).copy(alpha = alpha))

    private fun rgb(hex: Int) = Color(0xFF000000.toInt() or hex)

    val TealDark: AlvaColors = run {
        val surface2 = rgb(0x232327); val inkSoft = rgb(0xB4B4BC)
        AlvaColors(
            dark = true, color = AlvaColor.Teal,
            canvas = rgb(0x09090B), surface = rgb(0x18181B), surface2 = surface2, line = rgb(0x2C2C31),
            ink = rgb(0xF4F4F5), inkSoft = inkSoft, inkFaint = rgb(0x8B8B94),
            accent = rgb(0x2DD4BF), accentStrong = rgb(0x5EEAD4), accentInk = rgb(0x09090B),
            accentSoft = rgb(0x2DD4BF).copy(alpha = 0.14f),
            warn = rgb(0xFBBF24), danger = rgb(0xF87171),
            tones = darkTones(surface2, inkSoft),
        )
    }

    val TealLight: AlvaColors = run {
        val surface2 = rgb(0xECECEF); val inkSoft = rgb(0x52525B)
        AlvaColors(
            dark = false, color = AlvaColor.Teal,
            canvas = rgb(0xF4F4F5), surface = rgb(0xFFFFFF), surface2 = surface2, line = rgb(0xE0E0E5),
            ink = rgb(0x18181B), inkSoft = inkSoft, inkFaint = rgb(0x6B6B74),
            accent = rgb(0x0F766E), accentStrong = rgb(0x115E59), accentInk = rgb(0xFFFFFF),
            accentSoft = rgb(0x0F766E).copy(alpha = 0.10f),
            warn = rgb(0xB45309), danger = rgb(0xDC2626),
            tones = lightTones(surface2, inkSoft),
        )
    }

    val PinkDark: AlvaColors = run {
        val surface2 = rgb(0x30252D); val inkSoft = rgb(0xB6A5AF)
        AlvaColors(
            dark = true, color = AlvaColor.Pink,
            canvas = rgb(0x151014), surface = rgb(0x211B20), surface2 = surface2, line = rgb(0x3B2D36),
            ink = rgb(0xFAF4F7), inkSoft = inkSoft, inkFaint = rgb(0x95818C),
            accent = rgb(0xF59ABE), accentStrong = rgb(0xFFB6D1), accentInk = rgb(0x351323),
            accentSoft = rgb(0xF59ABE).copy(alpha = 0.14f),
            warn = rgb(0xFBBF24), danger = rgb(0xF87171),
            tones = darkTones(surface2, inkSoft),
        )
    }

    val PinkLight: AlvaColors = run {
        val surface2 = rgb(0xF6EDF2); val inkSoft = rgb(0x806D78)
        AlvaColors(
            dark = false, color = AlvaColor.Pink,
            canvas = rgb(0xFCF8FA), surface = rgb(0xFFFFFF), surface2 = surface2, line = rgb(0xEADDE4),
            ink = rgb(0x30242C), inkSoft = inkSoft, inkFaint = rgb(0x806D78),
            accent = rgb(0xB73570), accentStrong = rgb(0x922955), accentInk = rgb(0xFFFFFF),
            accentSoft = rgb(0xF8E8F0),
            warn = rgb(0x805000), danger = rgb(0xA32636),
            tones = lightTones(surface2, inkSoft),
        )
    }
}
