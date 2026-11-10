package net.ts.taild80ca0.atlas.twa.data.list

import kotlinx.coroutines.flow.Flow
import net.ts.taild80ca0.atlas.twa.data.matching.ItemNameSplitter

/**
 * The single entry point to the shopping list for screens and the widget.
 *
 * Callers never touch the database, the queue or the network: they observe a
 * [ListState] and execute [ListIntent]s. [execute] returns [IntentResult.Saved]
 * only after the change **and** its queued command are committed together, so
 * the caller may then clear its input or show success. Any other result means
 * nothing was saved and the input must be kept.
 */
interface ShoppingListStore {
    /** Emits the current state, then again after every change. Each emission is one consistent read. */
    fun observe(): Flow<ListState>

    suspend fun execute(intent: ListIntent): IntentResult
}

/** What the user asked for. New intents (correct, remove, finish...) are added by their own tasks. */
sealed interface ListIntent {
    /**
     * What was typed in the add field. It is split like the server does (comma,
     * semicolon, line feed; repeats dropped; at most [ItemNameSplitter.MAX_NAMES]
     * names) and then added as [AddItems].
     */
    data class AddText(val text: String) : ListIntent

    /**
     * Names already separated, such as the habituals panel's: added with the same
     * duplicate and category rules as [AddText] but without its limit of 30.
     */
    data class AddItems(val names: List<String>) : ListIntent

    /** Desired state, never a toggle: repeating it is harmless. */
    data class SetItemState(val publicId: String, val desired: ItemStatus) : ListIntent
}

sealed interface IntentResult {
    /**
     * Committed locally and queued for the server. For an add, [itemIds] are the
     * items that were created or revived and [additions] says what happened to
     * every typed name, duplicates included.
     */
    data class Saved(
        val commandId: String,
        val itemIds: List<String>,
        val additions: List<ItemAddition> = emptyList(),
    ) : IntentResult

    /**
     * Every typed name was already pending on the list: nothing was written or
     * queued. The caller shows [additions] (which item each name matched) instead
     * of success.
     */
    data class AlreadyOnList(val additions: List<ItemAddition>) : IntentResult

    /** Already in the desired state; nothing was written or queued. */
    data object Unchanged : IntentResult

    /** The request cannot be applied; nothing was written. */
    data class Invalid(val reason: InvalidReason) : IntentResult

    /** Storage failed; the transaction was rolled back and nothing was saved. */
    data class Failed(val cause: Throwable) : IntentResult
}

enum class InvalidReason { NO_NAMES, ITEM_NOT_FOUND }

/** What adding one typed name did: a new item, a purchased one back to pending, or nothing (already pending). */
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
    /** Commands in the queue, whatever their status. */
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
