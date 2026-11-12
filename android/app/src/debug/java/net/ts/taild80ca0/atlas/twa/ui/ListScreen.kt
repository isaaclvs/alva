package net.ts.taild80ca0.atlas.twa.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import net.ts.taild80ca0.atlas.twa.data.list.ItemStatus
import net.ts.taild80ca0.atlas.twa.data.list.ListItem
import net.ts.taild80ca0.atlas.twa.online.ActionResult
import net.ts.taild80ca0.atlas.twa.online.OnlineMessages
import net.ts.taild80ca0.atlas.twa.online.OnlineRepository
import net.ts.taild80ca0.atlas.twa.online.RemoveItem
import net.ts.taild80ca0.atlas.twa.online.UndoRemove
import net.ts.taild80ca0.atlas.twa.online.SetItemState
import net.ts.taild80ca0.atlas.twa.online.UndoItemState
import net.ts.taild80ca0.atlas.twa.online.liveItems
import net.ts.taild80ca0.atlas.twa.online.markedItem
import net.ts.taild80ca0.atlas.twa.sync.ReceiptStatus
import net.ts.taild80ca0.atlas.twa.sync.ServerSnapshot
import net.ts.taild80ca0.atlas.twa.ui.theme.*

/**
 * The Lista screen (`#list_screen`): brand row, title with its actions, summary, then the categories.
 * Everything it shows is the server's list; marking sends the desired state and waits for the answer.
 *
 * The pieces later issues extend are separate on purpose: [headerActions] (search #101, the ⋯ menu #109),
 * [afterSummary] ("Iniciar compras" #102, the search panel), and [ListRow]'s `trailing` (edit pencil #100,
 * swipe to remove #99).
 */
@Composable
fun ListScreen(
    repository: OnlineRepository,
    snapshot: ServerSnapshot,
    onAddItems: () -> Unit,
    onFailure: (String) -> Unit,
    showToast: (ToastMessage) -> Unit,
    modifier: Modifier = Modifier,
    notice: @Composable () -> Unit = {},
    headerActions: @Composable RowScope.() -> Unit = {},
    afterSummary: @Composable ColumnScope.() -> Unit = {},
    filterState: ListFilterState = rememberListFilterState(),
    shoppingMode: Boolean = false,
    rowActions: @Composable RowScope.(ListItem, () -> Unit) -> Unit = { _, _ -> },
) {
    val scope = rememberCoroutineScope()
    val items = remember(snapshot) { snapshot.liveItems() }
    val filters = filterState.value
    val searchFocus = remember { FocusRequester() }
    val visibleItems = remember(items, filters) { filters.apply(items) }
    val visibleIds = remember(visibleItems) { visibleItems.map { it.publicId }.toSet() }
    val groups = remember(snapshot.categories, items, visibleIds) {
        ListGroups.of(snapshot.categories, items).filter { group -> group.items.any { it.publicId in visibleIds } }
    }
    val busy = remember { mutableStateListOf<String>() }
    val pending = items.count { it.status == ItemStatus.PENDING }

    fun remove(item: ListItem) {
        if (item.publicId in busy || shoppingMode) return
        busy += item.publicId
        scope.launch {
            try {
                when (val result = repository.execute(RemoveItem(item.publicId))) {
                    is ActionResult.Success -> showToast(ToastMessage("${item.name} removido", "Desfazer") {
                        val undone = repository.execute(UndoRemove(result.receipt.commandId))
                        if (undone !is ActionResult.Success) onFailure(OnlineMessages.failure(undone))
                    })
                    else -> onFailure(OnlineMessages.failure(result))
                }
            } finally { busy -= item.publicId }
        }
    }

    fun mark(item: ListItem) {
        if (item.publicId in busy) return
        busy += item.publicId
        scope.launch {
            try {
                val purchasing = item.status != ItemStatus.PURCHASED
                val desired = if (purchasing) ItemStatus.PURCHASED else ItemStatus.PENDING
                when (val result = repository.execute(SetItemState(item.publicId, desired))) {
                    is ActionResult.Success -> {
                        // Only a mark that took effect on this device's request can be undone; "already in that
                        // state" (another phone got there first) has nothing to take back.
                        val marked = result.receipt.markedItem()
                        if (marked != null && result.receipt.status != ReceiptStatus.NOOP) {
                            val undo = UndoItemState(item.publicId, item.status, item.purchasedAt, marked.lockVersion)
                            showToast(ToastMessage(
                                text = if (purchasing) "${item.name} marcado" else "${item.name} desmarcado",
                                actionLabel = "Desfazer",
                            ) {
                                val undone = repository.execute(undo)
                                if (undone !is ActionResult.Success) onFailure(OnlineMessages.failure(undone))
                            })
                        }
                    }
                    else -> onFailure(OnlineMessages.failure(result))
                }
            } finally { busy -= item.publicId }
        }
    }

    Column(modifier.fillMaxSize().testTag("list-screen")) {
        Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 16.dp)) {
            BrandHeader(Modifier.padding(bottom = 4.dp))
            Spacer(Modifier.height(18.9.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Lista de compras", style = AlvaTheme.type.pageTitle.copy(lineHeight = 33.6.sp), color = AlvaTheme.colors.ink,
                    modifier = Modifier.weight(1f).testTag("list-title"))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    SearchButton(searchFocus)
                    headerActions()
                }
            }
            Text(
                ListGroups.summary(pending, items.size - pending), style = AlvaTheme.type.summary.copy(lineHeight = 18.sp),
                color = AlvaTheme.colors.inkSoft, modifier = Modifier.padding(top = 7.7.dp).testTag("list-summary"),
            )
            afterSummary()
            ListSearchFilters(filterState, searchFocus)
        }
        notice()
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 24.dp)) {
            if (groups.isEmpty()) item(key = "empty") {
                if (filters.active) Text("Nenhum item encontrado.", style = AlvaTheme.type.body, color = AlvaTheme.colors.inkSoft,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp).testTag("filter-empty")
                        .semantics { liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite })
                else EmptyList(onAddItems)
            }
            groups.forEach { group ->
                val key = group.category?.publicId ?: "none"
                item(key = "category-$key") { CategoryHeading(group) }
                itemsIndexed(group.items.filter { it.publicId in visibleIds }, key = { _, item -> item.publicId }) { index, item ->
                    ListRow(item, first = index == 0, enabled = item.publicId !in busy, onToggle = { mark(item) },
                        modifier = Modifier.swipeToRemove(!shoppingMode && item.publicId !in busy) { remove(item) },
                        trailing = { rowActions(item) { remove(item) } })
                }
                item(key = "gap-$key") { Spacer(Modifier.height(25.6.dp)) }
            }
        }
    }
}

/** `.shopping-category > h2`: category chip, name, and "x/y" once something in it is bought. */
@Composable
private fun CategoryHeading(group: ListGroup) {
    Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, bottom = 14.dp).testTag("category-${group.label}"),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        CategoryIcon(group.label)
        Text(group.label, style = AlvaTheme.type.sectionLabel, color = AlvaTheme.colors.inkSoft, modifier = Modifier.weight(1f))
        if (group.purchased > 0) Text(
            "${group.purchased}/${group.items.size}",
            // `h2 > span:last-child` is the (hidden) shopping span, so the count keeps text-xs; it inherits the 0.04em tracking.
            style = AlvaTheme.type.caption.copy(fontWeight = FontWeight.Medium, letterSpacing = 0.416.sp, fontFeatureSettings = "tnum"),
            color = AlvaTheme.colors.inkFaint,
            modifier = Modifier.testTag("category-count-${group.label}")
                .semantics { contentDescription = "${group.purchased} de ${group.items.size} comprados" },
        )
    }
}

/** `_row` without the edit pencil: round check and name, struck through when bought, divided from the row above. */
@Composable
internal fun ListRow(
    item: ListItem,
    first: Boolean,
    enabled: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    val bought = item.status == ItemStatus.PURCHASED
    val source = remember { MutableInteractionSource() }
    // `divide-y`: a 1dp line on top of every row but the first, which takes room of its own.
    Row(
        modifier.fillMaxWidth()
            .then(if (first) Modifier else Modifier.rowDivider(AlvaTheme.colors.line).padding(top = 1.dp)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier.weight(1f).testTag("item-${item.publicId}")
                .pressedSurface(source, AlvaTheme.colors.surface2)
                .toggleable(value = bought, enabled = enabled, role = Role.Checkbox, interactionSource = source, indication = null,
                    onValueChange = { onToggle() })
                .padding(start = 5.6.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AlvaCheck(checked = bought)
            // `text-base` keeps a 1.5 line height, so at the 0.95rem the row is 22.8dp tall, not 24.
            Text(
                item.name, style = AlvaTheme.type.row.copy(lineHeight = 22.8.sp),
                color = if (bought) AlvaTheme.colors.inkFaint else AlvaTheme.colors.ink,
                textDecoration = if (bought) TextDecoration.LineThrough else null,
                modifier = Modifier.weight(1f),
            )
        }
        trailing()
    }
}

/** `list-empty`: shown when nothing is on the live list. */
@Composable
private fun EmptyList(onAddItems: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 64.dp).testTag("list-empty"), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("🛒", fontSize = 36.sp, lineHeight = 40.sp)
        Text("Nada na lista por enquanto.", style = AlvaTheme.type.body, color = AlvaTheme.colors.inkSoft,
            textAlign = TextAlign.Center, modifier = Modifier.padding(top = 12.dp))
        Text(
            "Adicionar itens",
            style = AlvaTheme.type.button.copy(textDecoration = TextDecoration.Underline), color = AlvaTheme.colors.accentStrong,
            modifier = Modifier.padding(top = 12.dp).clickable(role = Role.Button, onClick = onAddItems).testTag("empty-add"),
        )
    }
}
