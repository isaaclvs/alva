package net.ts.taild80ca0.atlas.twa.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.input.ImeAction
import net.ts.taild80ca0.atlas.twa.data.list.ItemStatus
import net.ts.taild80ca0.atlas.twa.data.list.ListItem
import net.ts.taild80ca0.atlas.twa.data.matching.TextNormalizer
import net.ts.taild80ca0.atlas.twa.ui.theme.*

/** The same accent/case-insensitive substring rule as list_filter_controller.js. */
data class ListFilters(val query: String = "", val pendingOnly: Boolean = false) {
    val active get() = query.isNotEmpty() || pendingOnly
    fun apply(items: List<ListItem>): List<ListItem> {
        val query = TextNormalizer.plain(query)
        return items.filter { (!pendingOnly || it.status == ItemStatus.PENDING) && TextNormalizer.plain(it.name).contains(query) }
    }
}

@Stable
class ListFilterState(initial: ListFilters = ListFilters()) {
    var value by mutableStateOf(initial)
    companion object {
        val Saver = listSaver<ListFilterState, Any>(
            save = { listOf(it.value.query, it.value.pendingOnly) },
            restore = { ListFilterState(ListFilters(it[0] as String, it[1] as Boolean)) },
        )
    }
}

@Composable
fun rememberListFilterState() = rememberSaveable(saver = ListFilterState.Saver) { ListFilterState() }

@Composable
internal fun SearchButton(focus: FocusRequester) {
    val keyboard = LocalSoftwareKeyboardController.current
    val color = AlvaTheme.colors.inkSoft
    IconButton(onClick = { focus.requestFocus(); keyboard?.show() },
        modifier = Modifier.size(40.dp).background(AlvaTheme.colors.surface2, CircleShape)
            .semantics { contentDescription = "Buscar na lista" }.testTag("search-icon")) {
        Canvas(Modifier.size(20.dp)) {
            val u = size.width / 20f
            drawCircle(color, 5.25f*u, Offset(9*u, 9*u), style = Stroke(1.75f*u))
            drawLine(color, Offset(13*u, 13*u), Offset(17*u, 17*u), 1.75f*u, StrokeCap.Round)
        }
    }
}

@Composable
internal fun ListSearchFilters(state: ListFilterState, focus: FocusRequester, shoppingMode: Boolean = false) {
    val c = AlvaTheme.colors
    val filters = state.value
    val keyboard = LocalSoftwareKeyboardController.current
    Column(Modifier.fillMaxWidth().padding(top = 12.dp).testTag("list-filters"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).background(c.surface, AlvaShapes.Control)
            .border(1.dp, c.line, AlvaShapes.Control), verticalAlignment = Alignment.CenterVertically) {
            BasicTextField(filters.query, onValueChange = { state.value = filters.copy(query = it) }, singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                textStyle = AlvaTheme.type.body.copy(color = c.ink), cursorBrush = SolidColor(c.accent),
                decorationBox = { field -> Box {
                    if (filters.query.isEmpty()) Text("Buscar na lista", style = AlvaTheme.type.body, color = c.inkFaint)
                    field()
                } },
                modifier = Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 8.dp).focusRequester(focus)
                    .semantics { contentDescription = "Buscar na lista" }.testTag("list-search"))
            if (filters.query.isNotEmpty()) IconButton(onClick = {
                state.value = filters.copy(query = ""); focus.requestFocus(); keyboard?.show()
            }, modifier = Modifier.size(44.dp).semantics { contentDescription = "Limpar busca" }.testTag("clear-search")) {
                Text("×", color = c.inkSoft)
            } else Spacer(Modifier.width(44.dp))
        }
        if (!shoppingMode) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Row(Modifier.heightIn(min = 44.dp).toggleable(filters.pendingOnly, role = Role.Switch,
                onValueChange = { state.value = filters.copy(pendingOnly = it) }).testTag("pending-only"),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Canvas(Modifier.size(36.dp, 22.dp)) {
                    val u = size.width / 36f
                    drawRoundRect(if (filters.pendingOnly) c.accent else c.surface2, cornerRadius = CornerRadius(11*u))
                    drawRoundRect(if (filters.pendingOnly) c.accent else c.inkFaint, cornerRadius = CornerRadius(11*u), style = Stroke(u))
                    drawCircle(if (filters.pendingOnly) c.accentInk else c.inkSoft, 8*u, Offset((if (filters.pendingOnly) 25 else 11)*u, 11*u))
                }
                Text("Só pendentes", style = AlvaTheme.type.bodySmall, color = c.inkSoft)
            }
            Text("Seções · A–Z", style = AlvaTheme.type.caption, color = c.inkFaint)
        }
    }
}
