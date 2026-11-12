package net.ts.taild80ca0.atlas.twa.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
) {
    val scope = rememberCoroutineScope()
    val items = remember(snapshot) { snapshot.liveItems() }
    val groups = remember(snapshot) { ListGroups.of(snapshot.categories, items) }
    val busy = remember { mutableStateListOf<String>() }
    val pending = items.count { it.status == ItemStatus.PENDING }

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
            Spacer(Modifier.height(20.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Lista de compras", style = AlvaTheme.type.pageTitle.copy(lineHeight = 33.6.sp), color = AlvaTheme.colors.ink,
                    modifier = Modifier.weight(1f).testTag("list-title"))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) { headerActions() }
            }
            Text(
                ListGroups.summary(pending, items.size - pending), style = AlvaTheme.type.summary.copy(lineHeight = 18.sp),
                color = AlvaTheme.colors.inkSoft, modifier = Modifier.padding(top = 10.4.dp).testTag("list-summary"),
            )
            afterSummary()
        }
        notice()
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 24.dp)) {
            if (groups.isEmpty()) item(key = "empty") { EmptyList(onAddItems) }
            groups.forEach { group ->
                val key = group.category?.publicId ?: "none"
                item(key = "category-$key") { CategoryHeading(group) }
                itemsIndexed(group.items, key = { _, item -> item.publicId }) { index, item ->
                    ListRow(item, first = index == 0, enabled = item.publicId !in busy, onToggle = { mark(item) })
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
            style = AlvaTheme.type.sectionLabel.copy(fontWeight = FontWeight.Medium, letterSpacing = 0.sp),
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
    Row(
        modifier.fillMaxWidth().heightIn(min = 48.dp)
            .then(if (first) Modifier else Modifier.rowDivider(AlvaTheme.colors.line)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier.weight(1f).heightIn(min = 48.dp).testTag("item-${item.publicId}")
                .toggleable(value = bought, enabled = enabled, role = Role.Checkbox, onValueChange = { onToggle() })
                .padding(start = 5.6.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AlvaCheck(checked = bought)
            Text(
                item.name, style = AlvaTheme.type.row,
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
