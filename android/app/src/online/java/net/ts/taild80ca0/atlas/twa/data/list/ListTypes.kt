package net.ts.taild80ca0.atlas.twa.data.list

enum class AddOutcome { CREATED, REVIVED, DUPLICATE }

data class ItemAddition(val name: String, val outcome: AddOutcome, val itemPublicId: String)

enum class ItemStatus(val code: Int) {
    PENDING(0),
    PURCHASED(1),
    ;

    companion object {
        fun fromCode(code: Int): ItemStatus = entries.first { it.code == code }
    }
}

data class ListState(
    /** Live items (not archived), in creation order. */
    val items: List<ListItem>,
    val categories: List<Category>,
    /** Commands the server has not consumed yet, whatever their status (a consumed one waits for a snapshot but is not "awaiting sync"). */
    val queuedCommands: Int,
    /** Queued commands that need the user: conflict, rejected or blocked. */
    val needsAttention: Int,
    val confirmedRevision: Long,
)

data class ListItem(
    val publicId: String,
    val name: String,
    val categoryPublicId: String?,
    val status: ItemStatus,
    val purchasedAt: Long?,
    /** True while a queued command touches the item ("Aguardando sincronização"). */
    val awaitingSync: Boolean,
)

data class Category(val publicId: String, val name: String, val position: Int)

/** Source of the UUIDs used as public ids and command ids. Injectable so tests can force failures. */
fun interface IdGenerator {
    fun newId(): String
}

fun interface Clock {
    fun nowMillis(): Long
}
