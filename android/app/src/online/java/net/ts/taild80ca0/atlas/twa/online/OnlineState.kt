package net.ts.taild80ca0.atlas.twa.online

import net.ts.taild80ca0.atlas.twa.data.list.ItemStatus
import net.ts.taild80ca0.atlas.twa.data.list.ListItem
import net.ts.taild80ca0.atlas.twa.data.matching.TextNormalizer
import net.ts.taild80ca0.atlas.twa.sync.ServerSnapshot

/** Why the list could not be read, with the words the person sees (Portuguese, no codes). */
enum class OnlineProblem(val message: String) {
    /** No connectivity, DNS or timeout. */
    OFFLINE("Sem conexão com o servidor. Confira a internet e tente de novo."),

    /** 5xx, 429 or a refused TLS handshake. */
    SERVER("O servidor não está disponível agora. Tente de novo em instantes."),

    /** The credential is gone or was refused: the house must be connected again. */
    NO_ACCESS("Sem acesso à casa. Reconecte pelo menu ⋯."),

    /** A 4xx or a body that is not a snapshot this client accepts. */
    UNEXPECTED("O servidor respondeu algo inesperado. Tente de novo."),
}

/**
 * What the screens show. The server is the source of truth: [Ready] holds the last snapshot it sent, verbatim
 * (items, categories, dictionary, habituals, archived items, trips), and nothing else is kept or queued.
 */
sealed interface OnlineState {
    /** Nothing was read yet, or the first read is being retried. */
    data object Loading : OnlineState

    /**
     * The list is on screen. [problem] is set when the latest reload failed: the snapshot shown is then the last
     * one the server gave, and the screen says it may be out of date.
     */
    data class Ready(val snapshot: ServerSnapshot, val problem: OnlineProblem? = null) : OnlineState

    /** The first read failed; there is no list to show. */
    data class Failed(val problem: OnlineProblem) : OnlineState
}

/** The live (not archived) items of the snapshot as the screens and the duplicate hint read them. */
fun ServerSnapshot.liveItems(): List<ListItem> = listItems.map {
    ListItem(
        publicId = it.publicId,
        name = it.name,
        categoryPublicId = it.categoryPublicId,
        status = ItemStatus.fromCode(it.status),
        purchasedAt = it.purchasedAtMicros,
        awaitingSync = false,
    )
}

/**
 * Every name the house already uses, for the add field's suggestions: what Rails' `NameSuggestions` builds for the
 * PWA's datalist. Live and archived items count (any status, so history counts too) plus the dictionary, one name
 * per [TextNormalizer.key]: the house's own spelling wins over the dictionary's, and the latest spelling it used
 * wins over older ones. The snapshot's item lists come in ascending id order, and a created item's instant grows
 * with that id, so taking the newest first is the same "latest spelling wins" the server applies. Sorted like the
 * datalist: ignoring accents and case, then by the name itself.
 */
fun ServerSnapshot.suggestionNames(): List<String> {
    val household = (listItems + archivedItems).sortedByDescending { it.createdAtMicros }.map { it.name }
    return (household + catalogItems.map { it.name })
        .distinctBy { TextNormalizer.key(it) }
        .sortedWith(compareBy({ TextNormalizer.plain(it) }, { it }))
}

/**
 * The label of the category the snapshot holds for [itemPublicId]: what the add feedback says ("Leite adicionado
 * em Laticínios") and what "Acabou de adicionar" picks in its row. [fallback] is for an item or a category the
 * snapshot no longer shows.
 */
fun ServerSnapshot.categoryLabel(itemPublicId: String, fallback: String = "Sem categoria"): String {
    val item = listItems.firstOrNull { it.publicId == itemPublicId }
    return categories.firstOrNull { it.publicId == item?.categoryPublicId }?.name ?: fallback
}
