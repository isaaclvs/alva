package net.ts.taild80ca0.atlas.twa.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import java.io.File
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Compose palettes must be the PWA's: this test reads the real CSS (and the Ruby helper with the
 * `theme-color`) from the repository and resolves them the way the browser's cascade does, for each of
 * the four theme × color combinations. Changing a token in the CSS without copying it here fails.
 */
class PwaThemeParityTest {
    private val combos = listOf(
        Triple("dark teal", true, AlvaColor.Teal), Triple("light teal", false, AlvaColor.Teal),
        Triple("dark pink", true, AlvaColor.Pink), Triple("light pink", false, AlvaColor.Pink),
    )

    private fun repoFile(path: String): File =
        listOf("../../$path", "../$path", path).map(::File).firstOrNull { it.isFile }
            ?: error("$path not found from ${File(".").absoluteFile}; run the tests from the repository checkout")

    private val application by lazy { repoFile("app/assets/tailwind/application.css").readText() }
    private val pinkLight by lazy { repoFile("app/assets/tailwind/pink_light.css").readText() }

    /** Declarations of the first rule whose selector is exactly [selector]. */
    private fun block(css: String, selector: String): Map<String, String> {
        val start = css.indexOf("$selector {")
        check(start >= 0) { "rule not found: $selector" }
        val body = css.substring(start + selector.length + 2).substringBefore("}")
        return Regex("""(--color-[\w-]+):\s*([^;]+);""").findAll(body).associate { it.groupValues[1] to it.groupValues[2].trim() }
    }

    /** The cascade of application.css and pink_light.css for one combination. */
    private fun cssTokens(dark: Boolean, color: AlvaColor): Map<String, String> {
        val tokens = block(application, "@theme").toMutableMap()
        if (!dark) tokens += block(application, """html[data-theme="light"]""")
        if (color == AlvaColor.Pink) {
            tokens += if (dark) block(application, """html[data-color="pink"]:not([data-theme="light"])""")
            else block(pinkLight, """html[data-color="pink"][data-theme="light"]""")
        }
        return tokens
    }

    private fun parseColor(css: String): Color {
        Regex("""#([0-9a-fA-F]{6})""").matchEntire(css)?.let { return Color(0xFF000000.toInt() or it.groupValues[1].toInt(16)) }
        val rgb = Regex("""rgb\((\d+) (\d+) (\d+) / ([\d.]+)\)""").matchEntire(css) ?: error("unsupported color: $css")
        val (r, g, b) = rgb.groupValues.drop(1).take(3).map { it.toInt() }
        return Color(r, g, b, (rgb.groupValues[4].toFloat() * 255).roundToInt())
    }

    private fun assertSame(label: String, expected: Color, actual: Color) =
        assertEquals("$label: expected #%08X but was #%08X".format(expected.toArgb(), actual.toArgb()), expected.toArgb(), actual.toArgb())

    @Test fun everySemanticTokenMatchesTheCascadeInAllFourCombinations() {
        for ((name, dark, color) in combos) {
            val css = cssTokens(dark, color)
            val palette = AlvaPalettes.of(dark, color)
            val expected = mapOf(
                "canvas" to palette.canvas, "surface" to palette.surface, "surface-2" to palette.surface2, "line" to palette.line,
                "ink" to palette.ink, "ink-soft" to palette.inkSoft, "ink-faint" to palette.inkFaint,
                "accent" to palette.accent, "accent-strong" to palette.accentStrong, "accent-ink" to palette.accentInk,
                "accent-soft" to palette.accentSoft, "warn" to palette.warn, "danger" to palette.danger,
            )
            for ((token, actual) in expected) assertSame("$name --color-$token", parseColor(css.getValue("--color-$token")), actual)
            assertEquals(name, dark, palette.dark)
            assertEquals(name, color, palette.color)
        }
    }

    @Test fun everyColorTokenOfTheCssIsCoveredByTheTheme() {
        val covered = setOf("canvas", "surface", "surface-2", "line", "ink", "ink-soft", "ink-faint",
            "accent", "accent-strong", "accent-ink", "accent-soft", "warn", "danger")
        assertEquals(covered, block(application, "@theme").keys.map { it.removePrefix("--color-") }.toSet())
    }

    @Test fun systemBarsUseThePwaThemeColorOfEachCombination() {
        val helper = repoFile("app/helpers/application_helper.rb").readText()
        val themeColors = Regex(""""(teal|pink)" => \{ "dark" => "(#\w+)", "light" => "(#\w+)" \}""").findAll(helper)
            .associate { it.groupValues[1] to (it.groupValues[2] to it.groupValues[3]) }
        assertEquals(setOf("teal", "pink"), themeColors.keys)
        for ((name, dark, color) in combos) {
            val (darkHex, lightHex) = themeColors.getValue(color.name.lowercase())
            assertSame("$name theme-color", parseColor(if (dark) darkHex else lightHex), AlvaPalettes.of(dark, color).systemBar)
        }
    }

    @Test fun categoryIconTonesMatchTheOklchRulesOfTheCss() {
        val hues = mutableMapOf<String, Int>()
        Regex("""((?:\.category-icon\[data-tone="\w+"\](?:, )?)+) \{ --category-hue: (\d+); \}""").findAll(application).forEach { rule ->
            Regex("""data-tone="(\w+)"""").findAll(rule.groupValues[1]).forEach { hues[it.groupValues[1]] = rule.groupValues[2].toInt() }
        }
        val oklch = Regex("""oklch\((\d+)% \.(\d+) var\(--category-hue\)(?: / \.(\d+))?\)""").findAll(application).toList()
        assertEquals("dark color, dark background, light color, light background", 4, oklch.size)
        fun spec(i: Int) = Triple(oklch[i].groupValues[1].toDouble() / 100, "0.${oklch[i].groupValues[2]}".toDouble(),
            oklch[i].groupValues[3].takeIf { it.isNotEmpty() }?.let { "0.$it".toFloat() } ?: 1f)

        val tones = mapOf(
            "produce" to CategoryTone.Produce, "dairy" to CategoryTone.Dairy, "pantry" to CategoryTone.Pantry, "bakery" to CategoryTone.Bakery,
            "meat" to CategoryTone.Meat, "drinks" to CategoryTone.Drinks, "cleaning" to CategoryTone.Cleaning, "hygiene" to CategoryTone.Hygiene,
        )
        assertEquals(tones.keys, hues.keys)
        for ((dark, iconRule, backgroundRule) in listOf(Triple(true, 0, 1), Triple(false, 2, 3))) {
            val palette = AlvaPalettes.of(dark, AlvaColor.Teal)
            for ((cssName, tone) in tones) {
                val label = "${if (dark) "dark" else "light"} $cssName"
                val (iconL, chroma, _) = spec(iconRule)
                val (backgroundL, _, alpha) = spec(backgroundRule)
                assertNear("$label icon", oklchToSrgb(iconL, chroma, hues.getValue(cssName)), palette.tone(tone).icon)
                val background = palette.tone(tone).background
                assertNear("$label background", oklchToSrgb(backgroundL, chroma, hues.getValue(cssName)), background)
                assertEquals("$label background alpha", alpha, background.alpha, 0.004f)
            }
        }
        // `data-tone="other"` keeps the plain ink-soft on surface-2 chip, in all four combinations.
        for ((name, dark, color) in combos) {
            val palette = AlvaPalettes.of(dark, color)
            assertSame("$name other icon", palette.inkSoft, palette.tone(CategoryTone.Other).icon)
            assertSame("$name other chip", palette.surface2, palette.tone(CategoryTone.Other).background)
        }
    }

    /** Components within one 8-bit step of the converted value (the CSS gets it from the browser's conversion). */
    private fun assertNear(label: String, expected: Triple<Int, Int, Int>, actual: Color) {
        val argb = actual.toArgb()
        val got = Triple((argb shr 16) and 0xFF, (argb shr 8) and 0xFF, argb and 0xFF)
        assertTrue("$label: expected $expected but was $got",
            abs(expected.first - got.first) <= 1 && abs(expected.second - got.second) <= 1 && abs(expected.third - got.third) <= 1)
    }

    private fun oklchToSrgb(l: Double, c: Double, hue: Int): Triple<Int, Int, Int> {
        val a = c * cos(Math.toRadians(hue.toDouble())); val b = c * sin(Math.toRadians(hue.toDouble()))
        val lp = (l + 0.3963377774 * a + 0.2158037573 * b).pow(3)
        val mp = (l - 0.1055613458 * a - 0.0638541728 * b).pow(3)
        val sp = (l - 0.0894841775 * a - 1.2914855480 * b).pow(3)
        fun encode(x: Double): Int {
            val v = x.coerceIn(0.0, 1.0)
            return ((if (v <= 0.0031308) 12.92 * v else 1.055 * v.pow(1 / 2.4) - 0.055) * 255).roundToInt()
        }
        return Triple(
            encode(4.0767416621 * lp - 3.3077115913 * mp + 0.2309699292 * sp),
            encode(-1.2684380046 * lp + 2.6097574011 * mp - 0.3413193965 * sp),
            encode(-0.0041960863 * lp - 0.7034186147 * mp + 1.7076147010 * sp),
        )
    }

    @Test fun theFourPalettesAreDistinctAndDefaultToDarkTeal() {
        assertEquals(4, AlvaPalettes.all.map { it.canvas.toArgb() }.toSet().size)
        assertEquals(AlvaPalettes.TealDark, AlvaPalettes.of(dark = true, color = AlvaColor.Teal))
        assertNotEquals(AlvaPalettes.PinkDark.accent, AlvaPalettes.TealDark.accent)
    }

    @Test fun materialSchemeIsDrivenByTheTokens() {
        for (palette in AlvaPalettes.all) {
            val scheme = palette.toMaterial()
            assertSame("primary", palette.accent, scheme.primary)
            assertSame("onPrimary", palette.accentInk, scheme.onPrimary)
            assertSame("background", palette.canvas, scheme.background)
            assertSame("surface", palette.surface, scheme.surface)
            assertSame("onSurface", palette.ink, scheme.onSurface)
            assertSame("outlineVariant", palette.line, scheme.outlineVariant)
            assertSame("error", palette.danger, scheme.error)
        }
    }
}
