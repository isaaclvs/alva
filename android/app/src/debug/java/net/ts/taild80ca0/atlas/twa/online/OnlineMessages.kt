package net.ts.taild80ca0.atlas.twa.online

import net.ts.taild80ca0.atlas.twa.data.list.AddOutcome

/**
 * What to tell the person, in Portuguese and without contract codes, ids or server text — like the PWA.
 * [keepsText] says the screen keeps what was typed, which the message promises.
 */
object OnlineMessages {
    /** The text of an action that did not succeed. */
    fun failure(result: ActionResult, keepsText: Boolean = false): String {
        val retry = if (keepsText) "Seu texto foi mantido; tente de novo." else "Tente de novo."
        return when (result) {
            ActionResult.Offline -> "Sem conexão com o servidor. Nada foi salvo. $retry"
            ActionResult.ServerUnavailable -> "O servidor não está disponível agora. Nada foi salvo. $retry"
            ActionResult.NoAccess -> OnlineProblem.NO_ACCESS.message
            is ActionResult.Refused -> refusal(result.code, retry)
            is ActionResult.Invalid -> when (result.reason) {
                InvalidAction.NO_NAMES -> "Digite pelo menos um item."
                InvalidAction.TOO_MANY_NAMES -> "São itens demais de uma vez (no máximo ${AddItems.MAX_NAMES}). Adicione em partes."
                InvalidAction.ITEM_NOT_FOUND -> "Este item não está mais na lista."
                InvalidAction.INVALID_NAME -> "Digite um nome para o item."
                InvalidAction.CATEGORY_NOT_FOUND -> "Escolha uma categoria válida."
                InvalidAction.NO_HABITUALS -> "Selecione hábitos válidos desta casa"
            }
            is ActionResult.Success -> ""
        }
    }

    private fun refusal(code: String, retry: String): String = when (code) {
        "gone", "not_found" -> "Este item não está mais na lista."
        "precondition_failed", "stale_revision" -> "A lista mudou em outro aparelho. Ela foi atualizada; confira e tente de novo."
        "invalid_name" -> "Digite pelo menos um item."
        "duplicate_pending" -> "Este item já está pendente na lista."
        "forbidden_scope", "unauthorized" -> OnlineProblem.NO_ACCESS.message
        else -> "Não foi possível fazer isso agora. $retry"
    }

    /** The feedback of an add, in the PWA's words: "3 itens adicionados · Leite já estava na lista". */
    fun added(items: List<AddedItem>): String {
        val added = items.count { it.outcome != AddOutcome.DUPLICATE }
        val already = items.filter { it.outcome == AddOutcome.DUPLICATE }.map { it.name }.distinct()
        val parts = buildList {
            add(if (added == 0) "Nada adicionado" else if (added == 1) "1 item adicionado" else "$added itens adicionados")
            if (already.isNotEmpty()) {
                add("${sentence(already)} ${if (already.size == 1) "já estava" else "já estavam"} na lista")
            }
        }
        return parts.joinToString(" · ")
    }

    /**
     * What the add field says after an add, as the PWA does: one name gets its own sentence ("Leite adicionado em
     * Laticínios", "Leite voltou para a lista", "Leite já está na lista"), several get the summary of [added].
     * [categoryOf] is the label of the category the item landed in.
     */
    fun addFeedback(items: List<AddedItem>, categoryOf: (AddedItem) -> String): String {
        val only = items.singleOrNull() ?: return added(items)
        return when (only.outcome) {
            AddOutcome.CREATED -> "${only.name} adicionado em ${categoryOf(only)}"
            AddOutcome.REVIVED -> "${only.name} voltou para a lista"
            AddOutcome.DUPLICATE -> "${only.name} já está na lista"
        }
    }

    /** `duplicate_hint_controller.js`: "Leite já está na lista", "Leite e Pão já estão na lista". */
    fun alreadyOnList(names: List<String>): String =
        if (names.size == 1) "${names.single()} já está na lista" else "${sentence(names)} já estão na lista"

    private fun sentence(names: List<String>): String =
        if (names.size <= 1) names.joinToString() else names.dropLast(1).joinToString(", ") + " e " + names.last()
}
