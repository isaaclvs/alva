package net.ts.taild80ca0.atlas.twa.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import net.ts.taild80ca0.atlas.twa.preferences.DevicePreferences
import net.ts.taild80ca0.atlas.twa.ui.theme.*

@Composable
internal fun HouseMenu(saved: DevicePreferences, list: Boolean, working: Boolean, toggleTheme: () -> Unit,
    selectColor: (AlvaColor) -> Unit, exportPdf: (() -> Unit)?, signOut: (() -> Unit)?, actions: (@Composable () -> Unit)?) {
    var open by remember { mutableStateOf(false) }
    val colors = AlvaTheme.colors
    Box {
        MoreButton(onClick = { open = true }, modifier = Modifier.testTag("house-menu"))
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = colors.surface, shape = AlvaShapes.Card) {
            DropdownMenuItem(text = { Text(if (saved.dark) "Tema claro" else "Tema escuro", style = AlvaTheme.type.bodySmall) },
                leadingIcon = { MenuIcon(if (saved.dark) SUN else MOON) }, enabled = !working,
                onClick = { open = false; toggleTheme() }, modifier = Modifier.testTag("toggle-theme"))
            Row(Modifier.width(224.dp).padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Cor", Modifier.weight(1f), color = colors.inkSoft, style = AlvaTheme.type.bodySmall)
                listOf(AlvaColor.Teal to "Teal", AlvaColor.Pink to "Rosa").forEach { (color, label) ->
                    Box(Modifier.size(32.dp).border(2.dp, if (color == saved.color) colors.accent else Color.Transparent, CircleShape)
                        .selectable(color == saved.color, enabled = !working, role = Role.RadioButton) { open = false; selectColor(color) }
                        .testTag("color-${color.name}").semantics { contentDescription = "Cor $label" }, contentAlignment = Alignment.Center) {
                        Box(Modifier.size(20.dp).background(if (color == AlvaColor.Teal) Color(0xff0f766e) else Color(0xffb73570), CircleShape))
                    }
                }
            }
            if (list && exportPdf != null) DropdownMenuItem(text = { Text("Baixar PDF", style = AlvaTheme.type.bodySmall) }, leadingIcon = { MenuIcon(DOWNLOAD) }, enabled = !working,
                onClick = { open = false; exportPdf() }, modifier = Modifier.testTag("export-pdf"))
            if (signOut != null) DropdownMenuItem(text = { Text("Sair", style = AlvaTheme.type.bodySmall) }, enabled = !working,
                onClick = { open = false; signOut() }, modifier = Modifier.testTag("sign-out"))
            // Existing connection diagnostic remains available to development/test callers.
            if (actions != null) Column(Modifier.widthIn(max = 280.dp).padding(12.dp)) { actions() }
        }
    }
}

private const val SUN = "M13.5 10a3.5 3.5 0 1 1-7 0a3.5 3.5 0 1 1 7 0 M10 2.5v1.5 M10 16v1.5 M2.5 10H4 M16 10h1.5 M4.7 4.7l1.05 1.05 M14.25 14.25l1.05 1.05 M4.7 15.3l1.05-1.05 M14.25 5.75l1.05-1.05"
private const val MOON = "M16.5 11.5A6.5 6.5 0 0 1 8.5 3.5a6.5 6.5 0 1 0 8 8z"
private const val DOWNLOAD = "M10 3v9m0 0-3.5-3.5 M10 12l3.5-3.5 M4 14v1.5A1.5 1.5 0 0 0 5.5 17h9a1.5 1.5 0 0 0 1.5-1.5V14"

@Composable
private fun MenuIcon(data: String) {
    val color = AlvaTheme.colors.inkSoft
    val path = remember(data) { PathParser().parsePathString(data).toPath() }
    Canvas(Modifier.size(20.dp)) {
        val unit = size.width / 20f
        withTransform({ scale(unit, unit, pivot = Offset.Zero) }) {
            drawPath(path, color, style = Stroke(1.75f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}
