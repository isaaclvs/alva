package net.ts.taild80ca0.atlas.twa.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
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
import net.ts.taild80ca0.atlas.twa.online.CorrectItem
import net.ts.taild80ca0.atlas.twa.online.UndoItemState
import net.ts.taild80ca0.atlas.twa.online.liveItems
import net.ts.taild80ca0.atlas.twa.online.markedItem
import net.ts.taild80ca0.atlas.twa.online.FinishShopping
import net.ts.taild80ca0.atlas.twa.online.UndoShopping
import net.ts.taild80ca0.atlas.twa.online.shoppingTripPublicId
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
    editState: EditItemState = rememberEditItemState(),
    filterState: ListFilterState = rememberListFilterState(),
    shoppingMode: Boolean = false,
    rowActions: @Composable RowScope.(ListItem, () -> Unit) -> Unit = { _, _ -> },
    onMarked: () -> Unit = {},
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
    val purchased = items.size - pending

    var finishing by remember { mutableStateOf(false) }

    fun finishShopping() {
        if (finishing || busy.isNotEmpty()) return
        val observed = snapshot.listItems
        finishing = true
        scope.launch {
            try {
                when (val result = repository.execute(FinishShopping(observed))) {
                    is ActionResult.Success -> result.receipt.shoppingTripPublicId()?.let { tripId ->
                        showToast(ToastMessage("Compra registrada", "Desfazer") {
                            val undone = repository.execute(UndoShopping(tripId))
                            if (undone !is ActionResult.Success) onFailure(OnlineMessages.failure(undone))
                        })
                    } ?: onFailure("A compra foi registrada, mas não foi possível preparar o desfazer. Atualize a lista.")
                    else -> onFailure(OnlineMessages.failure(result))
                }
            } finally { finishing = false }
        }
    }

    fun remove(item: ListItem, observedVersion: Int? = null) {
        if (item.publicId in busy || shoppingMode || finishing) return
        busy += item.publicId
        val editing = editState.itemId == item.publicId
        if (editing) editState.saving = true
        scope.launch {
            try {
                when (val result = repository.execute(RemoveItem(item.publicId, observedVersion))) {
                    is ActionResult.Success -> {
                        if (editState.itemId == item.publicId) editState.close()
                        showToast(ToastMessage("${item.name} removido", "Desfazer") {
                            val undone = repository.execute(UndoRemove(result.receipt.commandId))
                            if (undone !is ActionResult.Success) onFailure(OnlineMessages.failure(undone))
                        })
                    }
                    else -> {
                        if (editing) editState.error = OnlineMessages.failure(result, keepsText = true)
                        else onFailure(OnlineMessages.failure(result))
                    }
                }
            } finally {
                busy -= item.publicId
                if (editing) editState.saving = false
            }
        }
    }

    fun mark(item: ListItem) {
        if (item.publicId in busy || finishing) return
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
                        if (result.receipt.status != ReceiptStatus.NOOP) onMarked()
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

    val header: @Composable () -> Unit = {
        Column(Modifier.fillMaxWidth().padding(start = if (shoppingMode) 20.dp else 0.dp, end = if (shoppingMode) 20.dp else 0.dp, top = 24.dp, bottom = 16.dp)) {
            if (!shoppingMode) {
                BrandHeader(Modifier.padding(bottom = 4.dp))
                Spacer(Modifier.height(18.9.dp))
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (shoppingMode) "Em compras" else "Lista de compras", style = AlvaTheme.type.pageTitle.copy(lineHeight = 33.6.sp), color = AlvaTheme.colors.ink,
                    modifier = Modifier.weight(1f).testTag("list-title"))
                if (!shoppingMode) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    SearchButton(searchFocus)
                    headerActions()
                }
            }
            Text(
                ListGroups.summary(pending, items.size - pending), style = AlvaTheme.type.summary.copy(lineHeight = 18.sp),
                color = AlvaTheme.colors.inkSoft, modifier = Modifier.padding(top = 7.7.dp).testTag("list-summary"),
            )
            if (shoppingMode && items.isNotEmpty()) {
                val bought = items.size - pending
                Box(Modifier.fillMaxWidth().padding(top = 8.dp).height(4.dp).background(AlvaTheme.colors.surface2, CircleShape)
                    .testTag("shopping-progress").semantics {
                        contentDescription = "Itens comprados"
                        progressBarRangeInfo = ProgressBarRangeInfo(bought.toFloat(), 0f..items.size.toFloat())
                    }) {
                    Box(Modifier.fillMaxWidth(bought.toFloat() / items.size).fillMaxHeight().background(AlvaTheme.colors.accent, CircleShape))
                }
            }
            afterSummary()
            if (!shoppingMode) ListSearchFilters(filterState, searchFocus)
        }
    }
    Column(modifier.fillMaxSize().testTag("list-screen")) {
        if (shoppingMode) header()
        notice()
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 24.dp)) {
            if (!shoppingMode) item(key = "header") { header() }
            if (groups.isEmpty()) item(key = "empty") {
                if (filters.active) Text("Nenhum item encontrado.", style = AlvaTheme.type.body, color = AlvaTheme.colors.inkSoft,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp).testTag("filter-empty")
                        .semantics { liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite })
                else if (!shoppingMode) EmptyList(onAddItems)
                else Text("Nada na lista por enquanto.", color = AlvaTheme.colors.inkSoft)
            }
            groups.forEach { group ->
                val key = group.category?.publicId ?: "none"
                item(key = "category-$key") { CategoryHeading(group, shoppingMode) }
                itemsIndexed(group.items.filter { it.publicId in visibleIds }, key = { _, item -> item.publicId }) { index, item ->
                    if (!shoppingMode && editState.itemId == item.publicId) {
                        EditItemForm(editState, snapshot.categories, onCancel = { editState.close() }, onSave = {
                            editState.saving = true
                            scope.launch {
                                try {
                                    val result = repository.execute(CorrectItem(item.publicId, name = editState.name,
                                        categoryPublicId = editState.categoryId.takeIf { editState.categoryChosen },
                                        observedLockVersion = editState.version, observedDictionaryRevision = editState.dictionaryRevision))
                                    if (result is ActionResult.Success) editState.close() else {
                                        editState.error = OnlineMessages.failure(result, keepsText = true)
                                        val fresh = (repository.state.value as? net.ts.taild80ca0.atlas.twa.online.OnlineState.Ready)?.snapshot
                                        fresh?.listItems?.firstOrNull { it.publicId == item.publicId }?.let { editState.version = it.lockVersion }
                                        fresh?.let { editState.dictionaryRevision = it.dictionaryRevision }
                                    }
                                } finally { editState.saving = false }
                            }
                        }, remove = {
                            RemoveItemButton(enabled = !editState.saving && item.publicId !in busy) { remove(item, editState.version) }
                        })
                    } else ListRow(item, first = index == 0, enabled = item.publicId !in busy && !finishing, onToggle = { mark(item) }, shoppingMode = shoppingMode,
                        modifier = Modifier.swipeToRemove(!shoppingMode && item.publicId !in busy) { remove(item) }, trailing = {
                        if (!shoppingMode) rowActions(item) { remove(item) }
                        if (!shoppingMode) EditItemButton(item.name, Modifier.testTag("edit-${item.publicId}")) {
                            editState.open(item, snapshot.listItems.first { it.publicId == item.publicId }.lockVersion, snapshot.dictionaryRevision)
                        }
                    })
                }
                item(key = "gap-$key") { Spacer(Modifier.height(25.6.dp)) }
            }
            if (!shoppingMode && purchased > 0) item(key = "finish-shopping") {
                FinishShoppingButton(purchased, enabled = !finishing && busy.isEmpty()) { finishShopping() }
            }
        }
        if (shoppingMode) Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            if (purchased > 0) FinishShoppingButton(purchased, enabled = !finishing && busy.isEmpty()) { finishShopping() }
            ListSearchFilters(filterState, searchFocus, shoppingMode = true)
        }
    }
}

/** `.shopping-category > h2`: category chip, name, and "x/y" once something in it is bought. */
@Composable
private fun CategoryHeading(group: ListGroup, shoppingMode: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, bottom = 14.dp).testTag("category-${group.label}"),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        CategoryIcon(group.label)
        Text(group.label, style = AlvaTheme.type.sectionLabel, color = AlvaTheme.colors.inkSoft, modifier = Modifier.weight(1f))
        if (shoppingMode || group.purchased > 0) Text(
            if (shoppingMode) { if (group.purchased == group.items.size) "tudo pego" else "${group.items.size - group.purchased} restantes" } else "${group.purchased}/${group.items.size}",
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
    shoppingMode: Boolean = false,
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
                .padding(start = 5.6.dp, top = if (shoppingMode) 16.dp else 12.dp, bottom = if (shoppingMode) 16.dp else 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AlvaCheck(checked = bought, modifier = if (shoppingMode) Modifier.size(28.dp) else Modifier)
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
