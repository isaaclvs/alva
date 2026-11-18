package net.ts.taild80ca0.atlas.twa

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.ts.taild80ca0.atlas.twa.access.AccessState
import net.ts.taild80ca0.atlas.twa.access.HouseAccess
import net.ts.taild80ca0.atlas.twa.data.list.AddOutcome
import net.ts.taild80ca0.atlas.twa.data.matching.ItemNameSplitter
import net.ts.taild80ca0.atlas.twa.online.*

/** The name this add created, which the panel can take back while it is open. */
internal data class QuickPanelUndo(val itemPublicId: String, val name: String)

internal data class QuickPanelState(val draft: String = "", val loading: Boolean = true, val connected: Boolean = false,
    val busy: Boolean = false, val error: String? = null, val success: String? = null, val close: Boolean = false,
    val undo: QuickPanelUndo? = null, val undoing: Boolean = false)

/**
 * Retains one in-flight command through rotation; drafts alone survive a new process, never a pending send.
 *
 * A single name this add created gets the PWA's own confirmation ("Leite adicionado em Laticínios") and a
 * "Desfazer" that stays for [UNDO_MILLIS]. Taking it back sends the Lista's own `remove_item`, so the name leaves
 * the server's live list; the panel then shows the field again, empty, for the name that was meant. A revived name
 * offers no undo (it is a bought item that came back, not something this add created), and a batch of several
 * names gets the shared summary without one.
 */
internal class QuickPanelModel(private val saved: SavedStateHandle, private val access: HouseAccess,
    private val repository: OnlineRepository?,
    /** How long the confirmation stays before the panel closes by itself; instrumentation stretches it. */
    private val confirmationMillis: Long = UNDO_MILLIS,
) : ViewModel() {
    private val mutable = MutableStateFlow(QuickPanelState(draft = saved["draft"] ?: ""))
    val state = mutable.asStateFlow()

    /** Which confirmation is on screen: the delayed close only fires for the one that scheduled it. */
    private var confirmation = 0

    init {
        viewModelScope.launch {
            access.state.collect { state ->
                if (state != null) mutable.update {
                    val connected = state == AccessState.Connected && repository != null
                    it.copy(loading = false, connected = connected, error = if (connected != it.connected) null else it.error)
                }
            }
        }
    }
    fun resume() { viewModelScope.launch { access.refresh(); if (access.credentialForSync() != null) repository?.refresh() } }
    fun edit(value: String) {
        if (mutable.value.busy || mutable.value.success != null) return
        // Each name stops at the limit the server validates, like the PWA's field.
        val limited = ItemNameSplitter.limitDraft(value)
        saved["draft"] = limited
        mutable.update { it.copy(draft = limited) }
    }
    fun submit() {
        val current = mutable.value
        if (current.busy || current.success != null || !current.connected || repository == null) return
        val names = ItemNameSplitter.split(current.draft)
        if (names.isEmpty()) { mutable.update { it.copy(error = "Digite um produto") }; return }
        mutable.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                when (val result = repository.execute(AddItems(names))) {
                    is ActionResult.Success -> {
                        val added = result.receipt.addedItems()
                        val snapshot = (repository.state.value as? OnlineState.Ready)?.snapshot
                        val message = OnlineMessages.addFeedback(added) { snapshot?.categoryLabel(it.itemPublicId) ?: "Sem categoria" }
                        saved["draft"] = ""
                        val mine = ++confirmation
                        mutable.update { it.copy(draft = "", success = message, undo = undoFor(added)) }
                        delay(confirmationMillis)
                        if (confirmation == mine) mutable.update { it.copy(close = true) }
                    }
                    else -> mutable.update { it.copy(error = OnlineMessages.failure(result, keepsText = true)) }
                }
            } finally { mutable.update { it.copy(busy = false) } }
        }
    }

    /** "Desfazer" of the panel: removes what this add created, through the Lista's own command, and stays for a retry. */
    fun undo() {
        val pending = mutable.value.undo ?: return
        if (mutable.value.undoing || repository == null) return
        mutable.update { it.copy(undoing = true) }
        viewModelScope.launch {
            try {
                when (val result = repository.execute(RemoveItem(pending.itemPublicId))) {
                    is ActionResult.Success -> {
                        // The scheduled close belongs to the confirmation this undo took back.
                        confirmation++
                        mutable.update { it.copy(undo = null, success = null, error = null) }
                    }
                    else -> mutable.update {
                        it.copy(undo = null, success = null, error = OnlineMessages.failure(result, keepsText = true))
                    }
                }
            } finally { mutable.update { it.copy(undoing = false) } }
        }
    }

    /** One name this add created. A batch is several rows (each with its own `×` on the Adicionar screen). */
    private fun undoFor(added: List<AddedItem>): QuickPanelUndo? {
        val only = added.singleOrNull() ?: return null
        return if (only.outcome == AddOutcome.CREATED && only.itemPublicId.isNotBlank()) QuickPanelUndo(only.itemPublicId, only.name) else null
    }

    companion object {
        /** How long the confirmation (and its Desfazer) stays before the panel closes by itself. */
        const val UNDO_MILLIS = 3_000L
    }
}
