package net.ts.taild80ca0.atlas.twa.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.ts.taild80ca0.atlas.twa.ui.theme.AlvaColor
import net.ts.taild80ca0.atlas.twa.ui.theme.AlvaShapes
import net.ts.taild80ca0.atlas.twa.ui.theme.AlvaTheme

/**
 * The PWA's `active:bg-surface-2`: the color shows only while the element is pressed. Elements built with this use
 * `indication = null`, so there is no Material ripple or focus overlay that the PWA does not have.
 */
@Composable
fun Modifier.pressedSurface(source: MutableInteractionSource, color: Color, shape: Shape = RectangleShape): Modifier {
    val pressed by source.collectIsPressedAsState()
    return if (pressed) background(color, shape) else this
}

/** Strokes [paths] (in a [viewBox]×[viewBox] space) scaled to the canvas, like an inline `<svg>` of the PWA. */
private fun DrawScope.strokePaths(paths: List<androidx.compose.ui.graphics.Path>, viewBox: Float, color: Color, width: Float) {
    withTransform({ scale(size.width / viewBox, size.width / viewBox, pivot = Offset.Zero) }) {
        paths.forEach { drawPath(it, color, style = Stroke(width, cap = StrokeCap.Round, join = StrokeJoin.Round)) }
    }
}

/** `_brand_mark`: the horizon and list lines and the check, all in the accent color (the span is `text-accent`). */
@Composable
fun BrandMark(modifier: Modifier = Modifier, size: Dp = 32.dp) {
    val accent = AlvaTheme.colors.accent
    val lines = remember { PathParser().parsePathString("M18 57H34.5M57.5 57H82M30 70H70M40 82H60").toPath() }
    val check = remember { PathParser().parsePathString("M32 42L46 57L72 24").toPath() }
    Canvas(modifier.size(size)) {
        withTransform({
            scale(this.size.width / 100f, this.size.width / 100f, pivot = Offset.Zero)
            translate(0f, -2f)
        }) {
            drawPath(lines, accent, style = Stroke(6.5f, cap = StrokeCap.Round, join = StrokeJoin.Round))
            drawPath(check, accent, style = Stroke(9f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}

/**
 * The brand row: mark, "Alva" and the house name, with [trailing] at the end (the ⋯ menu on the Add screen).
 * `leading-tight` is a 1.25 line height: 14/17.5 and 12/15. The Add screen centers the mark in a 36dp box ([markBox]).
 */
@Composable
fun BrandHeader(
    modifier: Modifier = Modifier, markSize: Dp = 32.dp, gap: Dp = 10.dp, markBox: Dp = markSize,
    trailing: @Composable () -> Unit = {},
) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(gap)) {
            Box(Modifier.size(markBox), contentAlignment = Alignment.Center) { BrandMark(size = markSize) }
            Column {
                Text("Alva", style = AlvaTheme.type.brand.copy(lineHeight = 17.5.sp), color = AlvaTheme.colors.ink,
                    modifier = Modifier.testTag("brand-name"))
                Text("Nossa casa", style = AlvaTheme.type.caption.copy(lineHeight = 15.sp), color = AlvaTheme.colors.inkFaint,
                    modifier = Modifier.padding(top = 2.dp))
            }
        }
        trailing()
    }
}

/** The ⋯ button of both headers (`size-10`, round, `text-ink-soft`). It only opens what the caller puts in the menu. */
@Composable
fun MoreButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val color = AlvaTheme.colors.inkSoft
    val source = remember { MutableInteractionSource() }
    Box(
        modifier.size(40.dp).clip(CircleShape).pressedSurface(source, AlvaTheme.colors.surface2)
            .clickable(interactionSource = source, indication = null, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = "Mais opções" },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(20.dp)) {
            val scale = size.width / 20f
            listOf(4.5f, 10f, 15.5f).forEach { drawCircle(color, 1.5f * scale, Offset(it * scale, 10f * scale)) }
        }
    }
}

/** The pending counter inside the "Lista" tab (`#list_counter`): accent pill with items to buy, muted at zero. */
@Composable
fun ListCounter(pending: Int, modifier: Modifier = Modifier) {
    val c = AlvaTheme.colors
    Box(
        modifier.defaultMinSize(minWidth = 20.dp).background(if (pending > 0) c.accent else c.surface2, CircleShape)
            .padding(horizontal = 6.dp, vertical = 1.dp)
            .semantics { contentDescription = "$pending ${if (pending == 1) "pendente" else "pendentes"}" },
        contentAlignment = Alignment.Center,
    ) {
        Text("$pending", style = AlvaTheme.type.caption.copy(fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum"),
            color = if (pending > 0) c.accentInk else c.inkFaint)
    }
}

/** A toast with one action. [onAction] runs when it is tapped; the toast disappears after [UNDO_TOAST_MILLIS]. */
class ToastMessage(val text: String, val actionLabel: String, val onAction: suspend () -> Unit)

/** `toast_controller.js`: the toast goes away by itself after about five seconds. */
const val UNDO_TOAST_MILLIS = 5_000L

enum class AppScreen(val tag: String, val label: String) {
    Add("add", "Adicionar"), List("list", "Lista");
}

/** Geometry of the floating navigation capsule (approved prototype, variant A). */
object FloatingNavigation {
    /** Space at the sides of the capsule: the canvas stays visible there. */
    val SideMargin = 16.dp
    /** Space between the capsule and the system gesture/navigation area, and above it between it and the content. */
    val EdgeGap = 8.dp
    val InnerPadding = 6.dp
    val CapsuleRadius = 26.dp
    val PillRadius = 21.dp
    /** Touch target of each tab; it grows with the font, never below this. */
    val MinTabHeight = 48.dp
    val MaxWidth = 448.dp
    const val CapsuleTag = "bottom-navigation"
}

/**
 * The bottom navigation: a single floating capsule with the two tabs side by side (icon and label), on `surface`
 * with a hairline and a soft shadow, the current tab a pill over `accent-soft` (`accent` in pink, `accent-strong`
 * in teal) and the "Lista" tab with the pending counter. The bar itself paints nothing, so the canvas shows at the
 * sides and below the capsule. It sits in the Scaffold's bottom bar slot, so the content ends above it, and it
 * clears the system gesture/3-button area and the cutout. It is not shown in shopping mode (the host decides).
 */
@Composable
fun BottomNavigation(current: AppScreen, pending: Int, onSelect: (AppScreen) -> Unit, modifier: Modifier = Modifier) {
    val c = AlvaTheme.colors
    val capsule = RoundedCornerShape(FloatingNavigation.CapsuleRadius)
    Box(
        modifier.fillMaxWidth()
            .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.displayCutout)
                .only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
            .padding(start = FloatingNavigation.SideMargin, end = FloatingNavigation.SideMargin,
                top = FloatingNavigation.EdgeGap, bottom = FloatingNavigation.EdgeGap),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier.widthIn(max = FloatingNavigation.MaxWidth).fillMaxWidth().height(IntrinsicSize.Min)
                .shadow(8.dp, capsule, ambientColor = Color.Black.copy(alpha = 0.15f), spotColor = Color.Black.copy(alpha = 0.15f))
                .background(c.surface, capsule).border(1.dp, c.line, capsule)
                .padding(FloatingNavigation.InnerPadding)
                .selectableGroup()
                .semantics { contentDescription = "Navegação do app" }
                .testTag(FloatingNavigation.CapsuleTag),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            AppScreen.entries.forEach { screen ->
                val selected = screen == current
                val tint = when {
                    !selected -> c.inkFaint
                    c.color == AlvaColor.Pink -> c.accent
                    else -> c.accentStrong
                }
                Row(
                    Modifier.weight(1f).fillMaxHeight().heightIn(min = FloatingNavigation.MinTabHeight)
                        .clip(RoundedCornerShape(FloatingNavigation.PillRadius))
                        .background(if (selected) c.accentSoft else Color.Transparent)
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, role = Role.Tab) { onSelect(screen) }
                        .semantics { this.selected = selected }
                        .testTag("nav-${screen.tag}")
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    NavIcon(screen, tint)
                    Text(screen.label, style = AlvaTheme.type.navigation.copy(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
                        color = tint, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (screen == AppScreen.List) ListCounter(pending, Modifier.testTag("list-counter"))
                }
            }
        }
    }
}

@Composable
private fun NavIcon(screen: AppScreen, tint: Color) {
    val paths = remember(screen) {
        if (screen == AppScreen.Add) listOf(PathParser().parsePathString("M10 4v12M4 10h12").toPath())
        else listOf(PathParser().parsePathString("M7 5.5h9M7 10h9M7 14.5h9").toPath())
    }
    Canvas(Modifier.size(20.dp)) {
        strokePaths(paths, 20f, tint, if (screen == AppScreen.Add) 2f else 1.75f)
        if (screen == AppScreen.List) {
            val scale = size.width / 20f
            listOf(5.5f, 10f, 14.5f).forEach {
                drawCircle(tint, 0.75f * scale, Offset(3.75f * scale, it * scale), style = Stroke(1.75f * scale))
            }
        }
    }
}

/**
 * `_toast`: the message, a dot and the accent action, in a rounded `surface-2` card with a border and shadow.
 * Shown only to the device that acted, because it comes from the action's own result and never from a refresh.
 */
@Composable
fun UndoToast(message: String, actionLabel: String, onAction: () -> Unit, modifier: Modifier = Modifier) {
    val c = AlvaTheme.colors
    Row(
        modifier.widthIn(max = 448.dp - 32.dp)
            .shadow(8.dp, AlvaShapes.Card, ambientColor = Color.Black.copy(alpha = 0.2f), spotColor = Color.Black.copy(alpha = 0.2f))
            .background(c.surface2, AlvaShapes.Card).border(1.dp, c.line, AlvaShapes.Card)
            .padding(start = 16.dp, top = 4.dp, bottom = 4.dp, end = 4.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(message, style = AlvaTheme.type.bodySmall, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false).testTag("toast-message"))
        Text("·", style = AlvaTheme.type.bodySmall, color = c.inkFaint)
        Text(
            actionLabel, style = AlvaTheme.type.bodySmall.copy(fontWeight = FontWeight.SemiBold), color = c.accentStrong,
            modifier = Modifier.clip(RoundedCornerShape(12.dp))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, role = Role.Button, onClick = onAction)
                .padding(horizontal = 12.dp, vertical = 10.dp).testTag("toast-undo"),
        )
    }
}

