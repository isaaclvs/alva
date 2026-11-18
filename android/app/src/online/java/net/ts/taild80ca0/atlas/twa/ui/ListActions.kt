package net.ts.taild80ca0.atlas.twa.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import net.ts.taild80ca0.atlas.twa.data.list.ItemStatus
import net.ts.taild80ca0.atlas.twa.data.list.ListItem
import net.ts.taild80ca0.atlas.twa.online.ActionResult
import net.ts.taild80ca0.atlas.twa.online.CorrectItem
import net.ts.taild80ca0.atlas.twa.online.FinishShopping
import net.ts.taild80ca0.atlas.twa.online.OnlineMessages
import net.ts.taild80ca0.atlas.twa.online.OnlineRepository
import net.ts.taild80ca0.atlas.twa.online.OnlineState
import net.ts.taild80ca0.atlas.twa.online.RemoveItem
import net.ts.taild80ca0.atlas.twa.online.SetItemState
import net.ts.taild80ca0.atlas.twa.online.UndoItemState
import net.ts.taild80ca0.atlas.twa.online.UndoRemove
import net.ts.taild80ca0.atlas.twa.online.UndoShopping
import net.ts.taild80ca0.atlas.twa.online.markedItem
import net.ts.taild80ca0.atlas.twa.online.shoppingTripPublicId
import net.ts.taild80ca0.atlas.twa.sync.ReceiptStatus
import net.ts.taild80ca0.atlas.twa.sync.ServerSnapshot

/**
 * The Lista's operations (mark, remove, correct, finish) and what the screen shows while they are in flight.
 *
 * It lives in the host, next to [AddScreenState], and not in the Lista tab: the tab is only composed while it is
 * open, and an operation sent from it has to keep its scope, its busy markers and its "sending" hints when the
 * person goes to Adicionar before the server answers. That also makes each operation single: coming back to the
 * list finds the row still busy, so the same command cannot be sent twice. The result (toast with undo, failure
 * message, the edit form closing or keeping its draft) reaches the host's own state, whichever tab is open.
 */
@Stable
class ListActions internal constructor(
    private val repository: OnlineRepository,
    private val scope: CoroutineScope,
    private val editState: EditItemState,
    private val showToast: (ToastMessage) -> Unit,
    private val onFailure: (String, (() -> Unit)?) -> Unit,
    private val onActionStart: () -> Unit,
    private val onMarked: () -> Unit,
    private val shoppingMode: () -> Boolean,
) {
    private val busy = mutableStateListOf<String>()

    // What the person just asked for, shown at once while the server has not answered: a "sending" hint, never a
    // result. It is dropped when execute() returns, and by then the list is the server's again (the reload is part
    // of execute), so a refusal, no connection or another phone winning put the row back by themselves.
    private val pendingMarks = mutableStateMapOf<String, ItemStatus>()

    var finishing by mutableStateOf(false)
        private set

    val anyBusy: Boolean get() = busy.isNotEmpty()

    fun isBusy(itemPublicId: String) = itemPublicId in busy

    fun pendingStatus(itemPublicId: String): ItemStatus? = pendingMarks[itemPublicId]

    fun finishShopping(observed: ServerSnapshot) {
        if (finishing || busy.isNotEmpty()) return
        onActionStart()
        val observedItems = observed.listItems
        finishing = true
        scope.launch {
            try {
                when (val result = repository.execute(FinishShopping(observedItems))) {
                    is ActionResult.Success -> result.receipt.shoppingTripPublicId()?.let { tripId ->
                        showToast(ToastMessage("Compra registrada", "Desfazer") {
                            val undone = repository.execute(UndoShopping(tripId))
                            if (undone !is ActionResult.Success) onFailure(OnlineMessages.failure(undone), null)
                        })
                    } ?: onFailure("A compra foi registrada, mas não foi possível preparar o desfazer. Atualize a lista.", null)
                    // "Tentar de novo" repeats the request from the list the failure's own reload brought back: a
                    // command the server had applied answers `duplicate` instead of finishing the trip twice.
                    else -> onFailure(OnlineMessages.failure(result)) { retryFinish() }
                }
            } finally { finishing = false }
        }
    }

    fun remove(item: ListItem, observedVersion: Int? = null) {
        if (item.publicId in busy || shoppingMode() || finishing) return
        onActionStart()
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
                            if (undone !is ActionResult.Success) onFailure(OnlineMessages.failure(undone), null)
                        })
                    }
                    else -> {
                        if (editing) editState.error = OnlineMessages.failure(result, keepsText = true)
                        // The reload of the failed answer has already brought a fresh version for the row.
                        else onFailure(OnlineMessages.failure(result)) { retryRemove(item.publicId) }
                    }
                }
            } finally {
                busy -= item.publicId
                if (editing) editState.saving = false
            }
        }
    }

    /** The tap asks for the opposite of what the row shows now. */
    fun mark(item: ListItem) = sendMark(item, if (item.status == ItemStatus.PURCHASED) ItemStatus.PENDING else ItemStatus.PURCHASED)

    /** [desired] is decided by the caller so a retry keeps the intent, even if another phone changed the item since. */
    private fun sendMark(item: ListItem, desired: ItemStatus) {
        if (item.publicId in busy || finishing) return
        onActionStart()
        busy += item.publicId
        val purchasing = desired == ItemStatus.PURCHASED
        pendingMarks[item.publicId] = desired
        scope.launch {
            try {
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
                                if (undone !is ActionResult.Success) onFailure(OnlineMessages.failure(undone), null)
                            })
                        }
                    }
                    // Repeat the same intent from the list the answer brought back, not from the row as it was before it.
                    else -> onFailure(OnlineMessages.failure(result)) { retryMark(item.publicId, desired) }
                }
            } finally {
                busy -= item.publicId
                pendingMarks.remove(item.publicId)
            }
        }
    }

    /** "Tentar de novo" of a mark, remove or finish: the same intent on the list the server has right now. */
    private fun retryMark(itemPublicId: String, desired: ItemStatus) {
        val fresh = freshItem(itemPublicId) ?: return
        sendMark(ListItem(fresh.publicId, fresh.name, fresh.categoryPublicId,
            ItemStatus.fromCode(fresh.status), fresh.purchasedAtMicros, awaitingSync = false), desired)
    }

    private fun retryRemove(itemPublicId: String) {
        val fresh = freshItem(itemPublicId) ?: return
        remove(ListItem(fresh.publicId, fresh.name, fresh.categoryPublicId,
            ItemStatus.fromCode(fresh.status), fresh.purchasedAtMicros, awaitingSync = false), fresh.lockVersion)
    }

    private fun retryFinish() {
        val fresh = (repository.state.value as? OnlineState.Ready)?.snapshot ?: return
        finishShopping(fresh)
    }

    /** The item as the last snapshot has it (the list is read again after every answer); null if it left the list. */
    private fun freshItem(itemPublicId: String): net.ts.taild80ca0.atlas.twa.sync.SnapshotItem? =
        (repository.state.value as? OnlineState.Ready)?.snapshot?.listItems?.firstOrNull { it.publicId == itemPublicId }

    /** Saves the inline correction form. The draft stays in [editState]: only a server answer closes or annotates it. */
    fun saveEdit(itemPublicId: String) {
        if (editState.saving) return
        onActionStart()
        editState.saving = true
        scope.launch {
            try {
                val result = repository.execute(CorrectItem(itemPublicId, name = editState.name,
                    categoryPublicId = editState.categoryId.takeIf { editState.categoryChosen },
                    observedLockVersion = editState.version, observedDictionaryRevision = editState.dictionaryRevision))
                if (result is ActionResult.Success) editState.close() else {
                    editState.error = OnlineMessages.failure(result, keepsText = true)
                    val fresh = (repository.state.value as? OnlineState.Ready)?.snapshot
                    fresh?.listItems?.firstOrNull { it.publicId == itemPublicId }?.let { editState.version = it.lockVersion }
                    fresh?.let { editState.dictionaryRevision = it.dictionaryRevision }
                }
            } finally { editState.saving = false }
        }
    }
}

/**
 * Hosted above the tabs, with their scope. The callbacks may change between recompositions, so the actions always
 * call the latest ones.
 */
@Composable
fun rememberListActions(
    repository: OnlineRepository,
    editState: EditItemState,
    showToast: (ToastMessage) -> Unit,
    onFailure: (String, (() -> Unit)?) -> Unit,
    onActionStart: () -> Unit = {},
    onMarked: () -> Unit = {},
    shoppingMode: () -> Boolean = { false },
): ListActions {
    val scope = rememberCoroutineScope()
    val latestToast by rememberUpdatedState(showToast)
    val latestFailure by rememberUpdatedState(onFailure)
    val latestStart by rememberUpdatedState(onActionStart)
    val latestMarked by rememberUpdatedState(onMarked)
    val latestShopping by rememberUpdatedState(shoppingMode)
    return remember(repository, editState, scope) {
        ListActions(repository, scope, editState, { latestToast(it) }, { message, retry -> latestFailure(message, retry) },
            { latestStart() }, { latestMarked() }, { latestShopping() })
    }
}
