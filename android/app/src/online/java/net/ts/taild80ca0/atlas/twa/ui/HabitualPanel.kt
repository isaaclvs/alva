package net.ts.taild80ca0.atlas.twa.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import net.ts.taild80ca0.atlas.twa.sync.SnapshotHabitual
import net.ts.taild80ca0.atlas.twa.ui.theme.AlvaTheme
import net.ts.taild80ca0.atlas.twa.ui.theme.AlvaShapes
import androidx.compose.foundation.shape.RoundedCornerShape
import net.ts.taild80ca0.atlas.twa.ui.theme.AlvaIconSize
import net.ts.taild80ca0.atlas.twa.ui.theme.AlvaSize
import net.ts.taild80ca0.atlas.twa.ui.theme.AlvaSpacing

/** The 3dp corner of the 16dp habitual checkbox: the smallest step of the radius scale (8dp) would make it a
 * circle, so this one stays its own value. */
private val CheckboxShape = RoundedCornerShape(3.dp)

@Composable
internal fun HabitualPanel(state: HabitualPanelState, habits: List<SnapshotHabitual>) {
    val colors = AlvaTheme.colors
    LaunchedEffect(habits) { state.selected.retainAll(habits.map { it.publicId }.toSet()) }
    Column(Modifier.padding(top = AlvaSpacing.sm).fillMaxWidth().background(colors.surface, AlvaShapes.Card)
        .border(1.dp, colors.line, AlvaShapes.Card).testTag("habitual-panel")) {
        Row(Modifier.fillMaxWidth().clickable(role = Role.Button) { state.expanded = !state.expanded }
            .testTag("habitual-toggle").padding(horizontal = AlvaSpacing.lg, vertical = AlvaSpacing.md),
            horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Habituais", color = colors.inkSoft, style = AlvaTheme.type.bodySmall)
            Text(if (state.expanded) "⌃" else "⌄", color = colors.inkSoft)
        }
        if (state.expanded) {
            Box(Modifier.fillMaxWidth().height(1.dp).background(colors.line))
            Column(Modifier.padding(horizontal = AlvaSpacing.lg, vertical = AlvaSpacing.md),
                verticalArrangement = Arrangement.spacedBy(AlvaSpacing.md)) {
                Row(horizontalArrangement = Arrangement.spacedBy(AlvaSpacing.sm), verticalAlignment = Alignment.CenterVertically) {
                    BasicTextField(state.draft, { state.draft = it }, singleLine = true,
                        textStyle = AlvaTheme.type.bodySmall.copy(color = colors.ink), cursorBrush = SolidColor(colors.accent),
                        modifier = Modifier.weight(1f).background(colors.canvas, AlvaShapes.Control)
                            .border(1.dp, colors.line, AlvaShapes.Control).padding(horizontal = AlvaSpacing.md, vertical = AlvaSpacing.sm)
                            .testTag("habitual-name").semantics { contentDescription = "Salvar item habitual" },
                        decorationBox = { field -> Box { if (state.draft.isEmpty()) Text("Ex.: café", color = colors.inkFaint, style = AlvaTheme.type.bodySmall); field() } })
                    HabitualButton("Salvar", !state.busy, "habitual-save", false, state::create)
                }
                if (habits.isEmpty()) Text("Ainda não há itens habituais salvos.", color = colors.inkFaint, style = AlvaTheme.type.bodySmall)
                habits.sortedWith(compareBy({ it.normalizedName }, { it.publicId })).forEach { habit ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val checked = habit.publicId in state.selected
                        Row(Modifier.weight(1f).heightIn(min = AlvaSize.TouchMin).toggleable(checked, enabled = !state.busy, role = Role.Checkbox) {
                            if (it) { if (habit.publicId !in state.selected) state.selected.add(habit.publicId) }
                            else state.selected.remove(habit.publicId)
                        }.testTag("habitual-select-${habit.name}").semantics { contentDescription = "Selecionar ${habit.name}" },
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AlvaSpacing.sm)) {
                            Canvas(Modifier.size(AlvaIconSize.Inline).background(if (checked) colors.accent else colors.canvas, CheckboxShape)
                                .border(1.dp, if (checked) colors.accent else colors.inkFaint, CheckboxShape)) {
                                if (checked) {
                                    drawLine(colors.accentInk, Offset(size.width * .22f, size.height * .5f), Offset(size.width * .43f, size.height * .72f), 2.dp.toPx(), StrokeCap.Round)
                                    drawLine(colors.accentInk, Offset(size.width * .43f, size.height * .72f), Offset(size.width * .8f, size.height * .27f), 2.dp.toPx(), StrokeCap.Round)
                                }
                            }
                            Text(habit.name, color = colors.inkSoft, style = AlvaTheme.type.bodySmall)
                        }
                        Box(Modifier.heightIn(min = AlvaSize.TouchMin).clickable(enabled = !state.busy, role = Role.Button) { state.remove(habit.publicId, habit.name) }
                            .padding(horizontal = AlvaSpacing.sm).semantics { contentDescription = "Remover item habitual ${habit.name}" }, contentAlignment = Alignment.Center) {
                            Text("Remover", color = colors.inkFaint, style = AlvaTheme.type.caption.copy(textDecoration = TextDecoration.Underline))
                        }
                    }
                }
                if (habits.isNotEmpty()) {
                    HabitualButton("Adicionar selecionados", !state.busy, "habitual-add-selected", true, { state.add(false) }, Modifier.fillMaxWidth())
                    HabitualButton("Adicionar todos", !state.busy, "habitual-add-all", false, { state.add(true) }, Modifier.fillMaxWidth())
                }
                state.message?.let { Text(it, color = colors.inkSoft, style = AlvaTheme.type.caption,
                    modifier = Modifier.testTag("habitual-feedback").semantics { liveRegion = LiveRegionMode.Polite }) }
            }
        }
    }
}

@Composable
private fun HabitualButton(label: String, enabled: Boolean, tag: String, accent: Boolean, click: () -> Unit, modifier: Modifier = Modifier) {
    val colors = AlvaTheme.colors
    Box(modifier.background(if (accent) colors.accent else colors.surface2, AlvaShapes.Control)
        .border(1.dp, if (accent) colors.accent else colors.line, AlvaShapes.Control)
        .clickable(enabled = enabled, role = Role.Button, onClick = click).testTag(tag)
        .padding(horizontal = AlvaSpacing.md, vertical = AlvaSpacing.md), contentAlignment = Alignment.Center) {
        Text(label, color = if (accent) colors.accentInk else colors.inkSoft, style = AlvaTheme.type.bodySmall)
    }
}
