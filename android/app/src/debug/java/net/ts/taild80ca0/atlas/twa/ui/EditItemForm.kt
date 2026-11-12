package net.ts.taild80ca0.atlas.twa.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import net.ts.taild80ca0.atlas.twa.data.list.Category
import net.ts.taild80ca0.atlas.twa.data.list.ListItem
import net.ts.taild80ca0.atlas.twa.ui.theme.*

/** Lives above lazy rows and screen navigation, so broadcasts, scrolling and recreation keep the draft. */
@Stable
class EditItemState {
    var itemId by mutableStateOf<String?>(null)
    var name by mutableStateOf("")
    var categoryId by mutableStateOf<String?>(null)
    var categoryChosen by mutableStateOf(false)
    var version by mutableIntStateOf(0)
    var dictionaryRevision by mutableLongStateOf(0)
    var error by mutableStateOf<String?>(null)
    var saving by mutableStateOf(false)

    fun open(item: ListItem, lockVersion: Int, revision: Long) {
        itemId = item.publicId; name = item.name; categoryId = item.categoryPublicId
        categoryChosen = false; version = lockVersion; dictionaryRevision = revision; error = null
    }
    fun close() { itemId = null; error = null }

    companion object {
        val Saver = listSaver<EditItemState, Any>(
            save = { listOf(it.itemId.orEmpty(), it.name, it.categoryId.orEmpty(), it.categoryChosen, it.version, it.dictionaryRevision, it.error.orEmpty()) },
            restore = { values -> EditItemState().apply {
                itemId = (values[0] as String).ifEmpty { null }; name = values[1] as String
                categoryId = (values[2] as String).ifEmpty { null }; categoryChosen = values[3] as Boolean
                version = values[4] as Int; dictionaryRevision = values[5] as Long
                error = (values[6] as String).ifEmpty { null }
            } },
        )
    }
}

@Composable
fun rememberEditItemState() = rememberSaveable(saver = EditItemState.Saver) { EditItemState() }

@Composable
internal fun EditItemButton(name: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val color = AlvaTheme.colors.inkFaint
    IconButton(onClick = onClick, modifier = modifier.size(44.dp).semantics { contentDescription = "Corrigir $name" }) {
        Canvas(Modifier.size(20.dp)) {
            val u = size.width / 20f
            drawLine(color, Offset(5*u, 14*u), Offset(14*u, 5*u), 2*u, StrokeCap.Round)
            drawLine(color, Offset(14*u, 5*u), Offset(17*u, 8*u), 2*u, StrokeCap.Round)
            drawLine(color, Offset(17*u, 8*u), Offset(8*u, 17*u), 2*u, StrokeCap.Round)
            drawLine(color, Offset(8*u, 17*u), Offset(4*u, 18*u), 2*u, StrokeCap.Round)
            drawLine(color, Offset(4*u, 18*u), Offset(5*u, 14*u), 2*u, StrokeCap.Round)
        }
    }
}

@Composable
internal fun EditItemForm(state: EditItemState, categories: List<Category>, onCancel: () -> Unit, onSave: () -> Unit,
    remove: (@Composable () -> Unit)? = null) {
    val colors = AlvaTheme.colors
    val focus = remember { FocusRequester() }
    var menu by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().background(colors.surface).padding(12.dp).testTag("edit-form-${state.itemId}"),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        BasicTextField(state.name, onValueChange = { state.name = it }, singleLine = true, enabled = !state.saving,
            textStyle = AlvaTheme.type.body.copy(color = colors.ink), cursorBrush = SolidColor(colors.accent),
            modifier = Modifier.fillMaxWidth().background(colors.canvas, AlvaShapes.Control)
                .border(1.dp, colors.line, AlvaShapes.Control).padding(horizontal = 12.dp, vertical = 8.dp)
                .focusRequester(focus).semantics { contentDescription = "Nome" }.testTag("edit-name"))
        Box {
            OutlinedButton(onClick = { menu = true }, enabled = !state.saving, shape = AlvaShapes.Control,
                modifier = Modifier.fillMaxWidth().testTag("edit-category")) {
                Text(categories.firstOrNull { it.publicId == state.categoryId }?.name ?: "Sem categoria", color = colors.ink)
            }
            DropdownMenu(menu, onDismissRequest = { menu = false }) {
                categories.sortedBy { it.position }.forEach { category ->
                    DropdownMenuItem(text = { Text(category.name, color = colors.ink) },
                        onClick = { state.categoryId = category.publicId; state.categoryChosen = true; menu = false },
                        modifier = Modifier.testTag("edit-category-${category.name}"))
                }
            }
        }
        state.error?.let { Text(it, style = AlvaTheme.type.bodySmall, color = colors.danger,
            modifier = Modifier.testTag("edit-error").semantics { liveRegion = LiveRegionMode.Polite }) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            remove?.invoke()
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onCancel, enabled = !state.saving, modifier = Modifier.testTag("edit-cancel")) { Text("Cancelar", color = colors.inkSoft) }
            Button(onClick = onSave, enabled = !state.saving, shape = AlvaShapes.Control, modifier = Modifier.testTag("edit-save"),
                colors = ButtonDefaults.buttonColors(containerColor = colors.accent, contentColor = colors.accentInk)) { Text("Salvar") }
        }
    }
    LaunchedEffect(state.itemId) { focus.requestFocus() }
}
