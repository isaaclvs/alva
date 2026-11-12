package net.ts.taild80ca0.atlas.twa.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import net.ts.taild80ca0.atlas.twa.data.list.ItemStatus
import net.ts.taild80ca0.atlas.twa.data.list.ListItem
import net.ts.taild80ca0.atlas.twa.data.matching.TextNormalizer
import net.ts.taild80ca0.atlas.twa.ui.theme.AlvaTheme

/** Reusable list filter state and matching rules (also consumed by the upcoming shopping mode). */
data class ListFilters(val query: String = "", val pendingOnly: Boolean = false) {
    fun apply(items: List<ListItem>): List<ListItem> {
        val normalizedQuery = TextNormalizer.plain(query)
        return items.filter { item ->
            (!pendingOnly || item.status == ItemStatus.PENDING) &&
                (normalizedQuery.isBlank() || TextNormalizer.plain(item.name).contains(normalizedQuery))
        }
    }
}

@Composable
internal fun ListSearchFilters(filters: ListFilters, onChange: (ListFilters) -> Unit) {
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    Column(Modifier.fillMaxWidth().padding(top = 12.dp).testTag("list-filters")) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("⌕", color = AlvaTheme.colors.inkSoft,
                modifier = Modifier.clickable { focusRequester.requestFocus(); keyboard?.show() }.testTag("search-icon"))
            BasicTextField(
                value = filters.query,
                onValueChange = { onChange(filters.copy(query = it)) },
                singleLine = true,
                textStyle = AlvaTheme.type.body.copy(color = AlvaTheme.colors.ink),
                decorationBox = { inner -> Box {
                    if (filters.query.isEmpty()) Text("Buscar na lista", style = AlvaTheme.type.body, color = AlvaTheme.colors.inkFaint)
                    inner()
                } },
                modifier = Modifier.weight(1f).focusRequester(focusRequester).testTag("list-search"),
            )
        }
        Row(Modifier.padding(top = 10.dp).clickable { onChange(filters.copy(pendingOnly = !filters.pendingOnly)) }
            .testTag("pending-only"), verticalAlignment = Alignment.CenterVertically) {
            Text(if (filters.pendingOnly) "☑" else "□", color = AlvaTheme.colors.accentStrong)
            Text("  Só pendentes", style = AlvaTheme.type.caption, color = AlvaTheme.colors.inkSoft)
        }
    }
}
