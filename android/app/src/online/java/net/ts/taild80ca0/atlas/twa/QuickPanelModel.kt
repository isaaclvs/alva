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

internal data class QuickPanelState(val draft: String = "", val loading: Boolean = true, val connected: Boolean = false,
    val busy: Boolean = false, val error: String? = null, val success: String? = null, val close: Boolean = false)

/** Retains one in-flight command through rotation; drafts alone survive a new process, never a pending send. */
internal class QuickPanelModel(private val saved: SavedStateHandle, private val access: HouseAccess,
    private val repository: OnlineRepository?) : ViewModel() {
    private val mutable = MutableStateFlow(QuickPanelState(draft = saved["draft"] ?: ""))
    val state = mutable.asStateFlow()
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
        saved["draft"] = value
        mutable.update { it.copy(draft = value) }
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
                        val added = result.receipt.addedItems().any { it.outcome != AddOutcome.DUPLICATE }
                        saved["draft"] = ""
                        mutable.update { it.copy(draft = "", success = if (added) "Adicionado à lista" else "Já estava na lista") }
                        delay(1_500)
                        mutable.update { it.copy(close = true) }
                    }
                    else -> mutable.update { it.copy(error = OnlineMessages.failure(result, keepsText = true)) }
                }
            } finally { mutable.update { it.copy(busy = false) } }
        }
    }
}
