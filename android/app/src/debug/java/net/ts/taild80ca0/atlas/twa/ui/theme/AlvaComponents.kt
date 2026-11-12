package net.ts.taild80ca0.atlas.twa.ui.theme

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.NavigationBarItemColors
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/** `rounded-full` check of a list row: 1.4rem, 1.5px border, accent fill with the tick when bought. */
@Composable
fun AlvaCheck(checked: Boolean, modifier: Modifier = Modifier) {
    val colors = AlvaTheme.colors
    val tick = remember { PathParser().parsePathString("M2.5 6.5l2.5 2.5 4.5-5").toPath() }
    Box(
        modifier.size(22.4.dp).background(if (checked) colors.accent else Color.Transparent, CircleShape)
            .border(1.5.dp, if (checked) colors.accent else colors.inkFaint, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (checked) Canvas(Modifier.size(14.dp)) {
            val scale = size.width / 12f
            withTransform({ scale(scale, scale, pivot = Offset.Zero) }) {
                drawPath(tick, colors.accentInk, style = Stroke(2f, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
    }
}

/** Outline paths of the category icons (24×24, from `ListItemsHelper::CATEGORY_ICONS`). */
internal object CategoryIcons {
    const val FALLBACK = "M3 7l9-5 9 5v10l-9 5-9-5V7Z M3 7l9 5 9-5 M12 12v10 M7 4l10 6"
    private val byCategory = mapOf(
        "Hortifruti" to ("M20 4C10 2 3 7 5 14c2 6 13 6 15-10Z M4 21 15 10" to CategoryTone.Produce),
        "Laticínios" to ("M9 3h6v4l3 4v10H6V11l3-4V3Z M9 7h6 M6 13h12" to CategoryTone.Dairy),
        "Mercearia" to ("M5 8h14l1 13H4L5 8Z M9 8V6a3 3 0 0 1 6 0v2" to CategoryTone.Pantry),
        "Açougue" to ("M15 3c5 0 7 5 4 9l-6 7c-3 3-9 1-10-3S6 8 9 7s2-4 6-4Z M14 7c3-1 5 2 3 4s-5-1-3-4Z" to CategoryTone.Meat),
        "Padaria" to ("M4 20c-3-3 1-8 5-12s9-7 12-4-1 8-5 12-9 7-12 4Z M8 10l3 3 M12 6l3 3 M5 14l3 3" to CategoryTone.Bakery),
        "Bebidas" to ("M6 8h12l-2 13H8L6 8Z M5 8h14 M12 8l3-6h4" to CategoryTone.Drinks),
        "Limpeza" to ("M8 10h8l3 4v7H5v-7l3-4Z M10 10V6h4v4 M10 6V3h7l2 2 M8 16h8" to CategoryTone.Cleaning),
        "Higiene" to ("M8 8h8l2 4v9H6v-9l2-4Z M10 8V4h4v4 M10 4V2h7 M10 15h4 M12 13v4" to CategoryTone.Hygiene),
    )

    fun path(category: String?): String = byCategory[category]?.first ?: FALLBACK
    fun tone(category: String?): CategoryTone = byCategory[category]?.second ?: CategoryTone.Other
}

/** The tone of the category icon for a category name; unknown names get the neutral one. */
fun categoryTone(category: String?): CategoryTone = CategoryIcons.tone(category)

/** `.category-icon`: a 1.75rem rounded chip with a 1.05rem outline icon in the category's tint. */
@Composable
fun CategoryIcon(category: String?, modifier: Modifier = Modifier) {
    val tone = AlvaTheme.colors.tone(categoryTone(category))
    val path = remember(category) { PathParser().parsePathString(CategoryIcons.path(category)).toPath() }
    Box(modifier.size(28.dp).background(tone.background, RoundedCornerShape(9.6.dp)), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(16.8.dp)) {
            val scale = size.width / 24f
            withTransform({ scale(scale, scale, pivot = Offset.Zero) }) {
                drawPath(path, tone.icon, style = Stroke(1.6f, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
    }
}

/** The add field and the search field: `rounded-2xl border-line bg-surface`, accent border on focus. */
@Composable
fun alvaTextFieldColors(): TextFieldColors {
    val c = AlvaTheme.colors
    return OutlinedTextFieldDefaults.colors(
        focusedTextColor = c.ink, unfocusedTextColor = c.ink,
        focusedContainerColor = c.surface, unfocusedContainerColor = c.surface,
        focusedBorderColor = c.accent, unfocusedBorderColor = c.line,
        cursorColor = c.accent,
        focusedLabelColor = c.inkSoft, unfocusedLabelColor = c.inkFaint,
        focusedPlaceholderColor = c.inkFaint, unfocusedPlaceholderColor = c.inkFaint,
        errorBorderColor = c.danger,
    )
}

/** Bottom navigation: the current tab in `accent-strong` over `accent-soft`, the others in `ink-faint`. */
@Composable
fun alvaNavigationItemColors(): NavigationBarItemColors {
    val c = AlvaTheme.colors
    return NavigationBarItemDefaults.colors(
        selectedIconColor = c.accentStrong, selectedTextColor = c.accentStrong, indicatorColor = c.accentSoft,
        unselectedIconColor = c.inkFaint, unselectedTextColor = c.inkFaint,
    )
}
