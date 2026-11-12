package net.ts.taild80ca0.atlas.twa.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import net.ts.taild80ca0.atlas.twa.R

/**
 * Nunito Sans in the three weights the PWA loads (400, 600, 700). The PWA never loads a
 * 500, so the browser falls back to 400 for `font-medium`; [FontWeight.Medium] does the same here.
 */
val NunitoSans = FontFamily(
    Font(R.font.nunito_sans_regular, FontWeight.Normal),
    Font(R.font.nunito_sans_regular, FontWeight.Medium),
    Font(R.font.nunito_sans_semibold, FontWeight.SemiBold),
    Font(R.font.nunito_sans_bold, FontWeight.Bold),
)

/**
 * The text styles the PWA's views use. Sizes and line heights are Tailwind's (xs 12/16, sm 14/20,
 * base 16/24, lg 18/28, 2xl 24/32) with the overrides of `application.css`: page titles at
 * 1.75rem with -0.045em tracking, category labels at 0.65rem with 0.04em, rows at 0.95rem.
 * Sizes are sp, so the system font scale still applies.
 */
@Immutable
class AlvaTextStyles(
    /** `#add_screen_title`, `#list_screen_title`: 1.75rem, semibold, tracking -0.045em, line height 1.2. */
    val pageTitle: TextStyle,
    /** `text-2xl font-semibold tracking-tight`: titles of secondary screens (e.g. house access). */
    val heading: TextStyle,
    /** Brand name beside the mark: `text-sm font-semibold`. */
    val brand: TextStyle,
    /** Default text, `text-base`. */
    val body: TextStyle,
    /** Subtitles and descriptions, `text-sm`. */
    val bodySmall: TextStyle,
    /** Hints and feedback, `text-xs`. */
    val caption: TextStyle,
    /** The add field, `text-lg`. */
    val input: TextStyle,
    /** A list row, `.shopping-toggle > span:last-child` (0.95rem). */
    val row: TextStyle,
    /** Category heading above a group (`.shopping-category > h2`). */
    val sectionLabel: TextStyle,
    /** List summary under the title (`#list_summary > p`, 0.8rem). */
    val summary: TextStyle,
    /** Bottom navigation labels (0.8rem). */
    val navigation: TextStyle,
    /** Buttons such as "Iniciar compras": `text-sm font-semibold`. */
    val button: TextStyle,
)

private fun style(size: Float, line: Float, weight: FontWeight = FontWeight.Normal, tracking: TextUnit = 0.sp) =
    TextStyle(fontFamily = NunitoSans, fontSize = size.sp, lineHeight = line.sp, fontWeight = weight, letterSpacing = tracking)

val AlvaTypographyTokens = AlvaTextStyles(
    pageTitle = style(28f, 33.6f, FontWeight.SemiBold, (-0.045).em),
    heading = style(24f, 32f, FontWeight.SemiBold, (-0.025).em),
    brand = style(14f, 20f, FontWeight.SemiBold),
    body = style(16f, 24f),
    bodySmall = style(14f, 20f),
    caption = style(12f, 16f),
    input = style(18f, 28f),
    row = style(15.2f, 24f),
    sectionLabel = style(10.4f, 15f, FontWeight.SemiBold, 0.04.em),
    summary = style(12.8f, 18f),
    navigation = style(12.8f, 18f),
    button = style(14f, 20f, FontWeight.SemiBold),
)

/** Material's slots, so stock components (buttons, fields, menus) already speak Nunito Sans at PWA sizes. */
fun alvaMaterialTypography(t: AlvaTextStyles = AlvaTypographyTokens): Typography = Typography(
    displayLarge = t.pageTitle, displayMedium = t.pageTitle, displaySmall = t.pageTitle,
    headlineLarge = t.pageTitle, headlineMedium = t.pageTitle, headlineSmall = t.heading,
    titleLarge = t.heading, titleMedium = t.button, titleSmall = t.button,
    bodyLarge = t.body, bodyMedium = t.bodySmall, bodySmall = t.caption,
    labelLarge = t.button, labelMedium = t.caption, labelSmall = t.caption,
)
