package net.ts.taild80ca0.atlas.twa.ui.theme

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The Alva mark of `application/_brand_mark.html.erb` (source: `docs/brand/mark.svg`): the horizon and list
 * lines in the text color ([AlvaColors.ink]), the check in the accent. Drawn from the same 100×100 paths,
 * so it matches the PWA at any size.
 */
@Composable
fun AlvaBrandMark(modifier: Modifier = Modifier, size: Dp = 48.dp) {
    val colors = AlvaTheme.colors
    val lines = remember { PathParser().parsePathString("M18 57H34.5M57.5 57H82M30 70H70M40 82H60").toPath() }
    val check = remember { PathParser().parsePathString("M32 42L46 57L72 24").toPath() }
    Canvas(modifier.size(size)) {
        val scale = this.size.width / 100f
        withTransform({
            scale(scale, scale, pivot = Offset.Zero)
            translate(0f, -2f)
        }) {
            drawPath(lines, colors.ink, style = Stroke(6.5f, cap = StrokeCap.Round, join = StrokeJoin.Round))
            drawPath(check, colors.accent, style = Stroke(9f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}
