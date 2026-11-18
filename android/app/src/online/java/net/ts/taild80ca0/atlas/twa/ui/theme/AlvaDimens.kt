package net.ts.taild80ca0.atlas.twa.ui.theme

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The spacing scale, in steps of 4dp: the PWA's `--spacing` base (`p-1` … `p-12`). Screens name a
 * step, never a length: a value that looks like a transliterated `rem` (18.9, 25.6, 22.4…) is a bug,
 * so round it to the nearest step and use the token. [PwaThemeParityTest] keeps the steps and the CSS
 * base in sync.
 */
object AlvaSpacing {
    /** 1 step: 4dp (`p-1`). */
    val xs = 4.dp

    /** 2 steps: 8dp (`p-2`). */
    val sm = 8.dp

    /** 3 steps: 12dp (`p-3`). */
    val md = 12.dp

    /** 4 steps: 16dp (`p-4`). */
    val lg = 16.dp

    /** 5 steps: 20dp (`p-5`). */
    val xl = 20.dp

    /** 6 steps: 24dp (`p-6`). */
    val xxl = 24.dp

    /** 8 steps: 32dp (`p-8`). */
    val xxxl = 32.dp

    /** 12 steps: 48dp (`p-12`). */
    val huge = 48.dp
}

/**
 * The corner radii of `application.css` (`--radius-*`), as lengths: [AlvaShapes] turns them into
 * Compose shapes. `full` has no length (it is a circle), so it only exists as a shape.
 */
object AlvaRadius {
    /** The category chip and the small square buttons (`rounded-xs`). */
    val xs = 8.dp

    /** Fields, buttons and rows (`rounded-control`). */
    val control = 12.dp

    /** Cards, the add field and the toast (`rounded-card`). */
    val card = 16.dp

    /** The selected tab inside the floating capsule (`rounded-pill`). */
    val pill = 21.dp

    /** The floating navigation capsule itself (`rounded-capsule`). */
    val capsule = 26.dp

    /** Bottom sheets, such as the quick add panel (`rounded-sheet`). */
    val sheet = 28.dp
}

/** The rounded corners the PWA uses, as shapes; the names are the `--radius-*` tokens. */
object AlvaShapes {
    val Chip = RoundedCornerShape(AlvaRadius.xs)
    val Control = RoundedCornerShape(AlvaRadius.control)
    val Card = RoundedCornerShape(AlvaRadius.card)
    val Pill = RoundedCornerShape(AlvaRadius.pill)
    val Capsule = RoundedCornerShape(AlvaRadius.capsule)
    val Sheet = RoundedCornerShape(AlvaRadius.sheet)
    val Full = CircleShape
}

/**
 * Elevation: either flat ([None]) or floating above the canvas ([Float], the navigation capsule and
 * the undo toast). One elevation and one alpha for both, the single value the CSS shares as
 * `--shadow-float`; the CSS expresses it as a blur (`0 4px 20px`), the platform as an elevation in dp.
 */
object AlvaElevation {
    val None = 0.dp

    /** Card and toast elevation: 8dp here, `0 4px 20px` in the CSS. */
    val Float = 8.dp

    /** The shadow color of [Float], over black. */
    const val FloatAlpha = 0.18f
}

/**
 * Icon sizes: [Inline] inside text, [Bar] for the header/tab glyphs and [Action] for a primary
 * action. [Stroke] is the default stroke width of an icon drawn in a 20-unit box (the PWA's
 * `stroke-width="1.75"`).
 */
object AlvaIconSize {
    /** 16dp: the category icon, the notice icon. */
    val Inline = 16.dp

    /** 20dp: bar and menu glyphs (⋯, magnifier, tabs, pencil). */
    val Bar = 20.dp

    /** 24dp: a primary action inside a 48dp button (the add field's plus). */
    val Action = 24.dp

    /** Default stroke of an outline icon, in its own 20-unit view box. */
    const val Stroke = 1.75f
}

/**
 * Sizes that are not spacing steps: the minimum touch target per platform, the round check, the
 * category chip, the pill and colour swatches of the menu, and the widths of the content column, the
 * focused-checklist search and the widget preview.
 */
object AlvaSize {
    /** Minimum touch target. The PWA asks for 44 CSS px (`--size-touch`); Android requires 48dp. */
    val TouchMin: Dp = 48.dp

    /** The round check of a list row (`--size-check`). */
    val Check = 22.dp

    /** The same check inside the shopping checklist, where rows are larger (`--size-check-shopping`). */
    val CheckShopping = 28.dp

    /** The category chip around its icon (`--size-chip`). */
    val Chip = 28.dp

    /** The content column of the PWA (`--container-content`, 28rem): the add screen and the toast. */
    val Content: Dp = 448.dp

    /** The toast keeps the content column minus its 16dp gutters. */
    val Toast: Dp = Content - AlvaSpacing.lg * 2

    // The few fixed dimensions that are not a step of a scale but are shared by two or more places.

    /** A header icon button, the ⋯ of both screens (`size-10`): 40dp. */
    val IconButton = 40.dp

    /** The colour swatch of the theme menu (`size-20dp` inside its 32dp ring). */
    val Swatch = 20.dp

    /** The selected tab of the menu wants a fixed row, 224dp (`w-56`). */
    val MenuWidth: Dp = 224.dp

    /** The widget preview of the configuration screen, one width per widget size. */
    val WidgetPreviewCompact = 190.dp
    val WidgetPreviewWide = 340.dp

    /** The previous/next month button of the history: a legible 96dp, `min-96`. */
    val MonthButton = 96.dp

    /** The optional diagnostics block of the ⋯ menu (dev/test callers): 288dp. */
    val MenuHelper = 288.dp

    /** The tallest "Acabou de adicionar" list before it scrolls: four row heights, 112dp. */
    val RecentAdds: Dp = AlvaSpacing.huge * 2 + AlvaSpacing.lg

    /** The category picker of a recent row: `min(9rem, 32vw)`, capped at 144dp. */
    val RecentCategory = 144.dp
}
