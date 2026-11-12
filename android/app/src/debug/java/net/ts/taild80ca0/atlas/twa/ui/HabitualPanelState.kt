package net.ts.taild80ca0.atlas.twa.ui

import androidx.compose.runtime.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import net.ts.taild80ca0.atlas.twa.online.*
import net.ts.taild80ca0.atlas.twa.data.list.AddOutcome
import net.ts.taild80ca0.atlas.twa.data.matching.RubyText
import org.json.JSONObject

/** Local form state, independent of broadcasts and of the recent-adds feedback. */
@Stable
class HabitualPanelState(private val repository: OnlineRepository, private val scope: CoroutineScope) {
    var draft by mutableStateOf("")
    var expanded by mutableStateOf(false)
    val selected = mutableStateListOf<String>()
    var message by mutableStateOf<String?>(null)
        private set
    var busy by mutableStateOf(false)
        private set

    fun create() {
        val submitted = draft
        val name = RubyText.squish(submitted)
        if (name.isEmpty()) { message = "Digite o nome do item habitual"; return }
        if (name.codePointCount(0, name.length) > 100) { message = "O item habitual deve ter até 100 caracteres"; return }
        perform(CreateHabitual(name)) { result ->
            val data = JSONObject(result.receipt.dataJson ?: "{}")
            if (data.optString("outcome") == "duplicate") "Esse item habitual já está salvo"
            else {
                if (draft == submitted) draft = ""
                "${data.optJSONObject("item")?.optString("name", name) ?: name} salvo nos habituais"
            }
        }
    }

    fun remove(id: String, name: String) = perform(RemoveHabitual(id)) {
        selected.remove(id)
        "$name removido dos habituais"
    }

    fun add(all: Boolean) {
        if (!all && selected.isEmpty()) { message = "Selecione hábitos válidos desta casa"; return }
        perform(AddHabituals(if (all) null else selected.toList())) {
            val items = it.receipt.addedItems()
            val added = items.count { item -> item.outcome != AddOutcome.DUPLICATE }
            val duplicates = items.size - added
            if (duplicates > 0) "$added adicionados, $duplicates já estavam na lista" else "$added adicionados à lista"
        }
    }

    private fun perform(action: OnlineAction, success: (ActionResult.Success) -> String) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                message = when (val result = repository.execute(action)) {
                    is ActionResult.Success -> success(result)
                    is ActionResult.Refused -> when (result.code) {
                        "no_habituals" -> "Ainda não há itens habituais salvos"
                        "not_found" -> "Selecione hábitos válidos desta casa"
                        else -> OnlineMessages.failure(result, keepsText = true)
                    }
                    else -> OnlineMessages.failure(result, keepsText = true)
                }
            } finally { busy = false }
        }
    }
}
