package net.ts.taild80ca0.atlas.twa.online

import net.ts.taild80ca0.atlas.twa.data.list.AddOutcome
import net.ts.taild80ca0.atlas.twa.data.list.ItemStatus
import net.ts.taild80ca0.atlas.twa.data.matching.ItemNameSplitter
import net.ts.taild80ca0.atlas.twa.data.matching.TextNormalizer
import net.ts.taild80ca0.atlas.twa.sync.CommandReceipt
import net.ts.taild80ca0.atlas.twa.sync.ServerTime
import net.ts.taild80ca0.atlas.twa.sync.SnapshotItem
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener

/**
 * `add_items`: the names typed in the add field (or chosen elsewhere), already split. The server decides
 * categories and duplicates; the only thing decided here is the one it cannot guess: a name that matches a
 * purchased item on the list travels with `revives` (that purchase and the `lock_version` seen), because the
 * server brings a purchase back only when told which one was seen.
 */
class AddItems(private val names: List<String>) : OnlineAction {
    override fun plan(context: PlanContext): ActionPlan {
        val cleaned = names.mapNotNull { ItemNameSplitter.clean(it) }
        if (cleaned.isEmpty()) return ActionPlan.Invalid(InvalidAction.NO_NAMES)
        if (cleaned.size > MAX_NAMES) return ActionPlan.Invalid(InvalidAction.TOO_MANY_NAMES)

        val live = context.snapshot.listItems
        val keys = live.map { TextNormalizer.key(it.name) }
        val revivedKeys = HashSet<String>()
        val entries = cleaned.map { name ->
            val key = TextNormalizer.key(name)
            val purchased = if (key.isEmpty() || key in revivedKeys) null else purchasedNamed(live, keys, key)
            if (purchased != null) revivedKeys += key
            entry(context.newId(), name, purchased)
        }
        return ActionPlan.Send(context.document("add_items", """{"items":[${entries.joinToString(",")}]}"""))
    }

    /** The purchased item to bring back, unless a pending one already has the name (then the server unifies them). */
    private fun purchasedNamed(live: List<SnapshotItem>, keys: List<String>, key: String): SnapshotItem? {
        val matching = live.indices.filter { keys[it] == key }
        if (matching.any { live[it].status == PENDING }) return null
        // The latest purchase wins; ties go to the one lower in the list, like Rails' `id DESC`.
        return matching.filter { live[it].status == PURCHASED }
            .maxWithOrNull(compareBy<Int> { live[it].purchasedAtMicros ?: Long.MIN_VALUE }.thenBy { it })
            ?.let { live[it] }
    }

    private fun entry(claimId: String, name: String, revives: SnapshotItem?): String = buildString {
        append("""{"public_id":""").append(JSONObject.quote(claimId)).append(""","name":""").append(JSONObject.quote(name))
        if (revives != null) {
            append(""","revives":{"public_id":""").append(JSONObject.quote(revives.publicId))
            append(""","lock_version":""").append(revives.lockVersion).append('}')
        }
        append('}')
    }

    companion object {
        const val MAX_NAMES = 30
        private const val PENDING = 0
        private const val PURCHASED = 1

        /** What was typed in the add field: split like the server does (comma, semicolon, line feed). */
        fun fromText(text: String?) = AddItems(ItemNameSplitter.split(text))
    }
}

/** `set_item_state`: the state the person wants (never a toggle), with the `lock_version` the snapshot showed. */
class SetItemState(private val publicId: String, private val desired: ItemStatus) : OnlineAction {
    override fun plan(context: PlanContext): ActionPlan {
        val item = context.snapshot.listItems.firstOrNull { it.publicId.equals(publicId, ignoreCase = true) }
            ?: return ActionPlan.Invalid(InvalidAction.ITEM_NOT_FOUND)
        val wire = if (desired == ItemStatus.PURCHASED) "purchased" else "pending"
        return ActionPlan.Send(
            context.document(
                type = "set_item_state",
                payload = """{"public_id":${JSONObject.quote(item.publicId)},"desired":${JSONObject.quote(wire)}}""",
                preconditions = """{"lock_version":${item.lockVersion}}""",
            ),
        )
    }
}

/** `correct_item`: save the edited name and optional manually selected category against the shown versions. */
class CorrectItem(
    private val publicId: String,
    private val categoryPublicId: String? = null,
    private val name: String? = null,
    private val observedLockVersion: Int? = null,
    private val observedDictionaryRevision: Long? = null,
) : OnlineAction {
    override fun plan(context: PlanContext): ActionPlan {
        val item = context.snapshot.listItems.firstOrNull { it.publicId.equals(publicId, ignoreCase = true) }
            ?: return ActionPlan.Invalid(InvalidAction.ITEM_NOT_FOUND)
        val cleaned = ItemNameSplitter.clean(name ?: item.name) ?: return ActionPlan.Invalid(InvalidAction.INVALID_NAME)
        if (categoryPublicId != null && context.snapshot.categories.none { it.publicId.equals(categoryPublicId, true) }) {
            return ActionPlan.Invalid(InvalidAction.CATEGORY_NOT_FOUND)
        }
        val category = categoryPublicId?.let(JSONObject::quote) ?: "null"
        return ActionPlan.Send(context.document(
            "correct_item",
            """{"public_id":${JSONObject.quote(item.publicId)},"name":${JSONObject.quote(cleaned)},"category_id":$category}""",
            """{"lock_version":${observedLockVersion ?: item.lockVersion},"dictionary_revision":${observedDictionaryRevision ?: context.snapshot.dictionaryRevision}}""",
        ))
    }
}

/** The item as a `set_item_state` receipt reports it after the command: the version the next command must name. */
data class MarkedItem(val publicId: String, val lockVersion: Int)

/** `data.item` of a successful `set_item_state`; null for any other receipt or one this client cannot read. */
fun CommandReceipt.markedItem(): MarkedItem? {
    val data = dataJson ?: return null
    return try {
        val item = (JSONTokener(data).nextValue() as? JSONObject)?.optJSONObject("item") ?: return null
        val id = item.optString("canonical_public_id").ifBlank { item.optString("public_id") }
        if (id.isBlank() || !item.has("lock_version")) null else MarkedItem(id, item.getInt("lock_version"))
    } catch (_: JSONException) {
        null
    }
}

/**
 * `undo_item_state`: the toast's "Desfazer" after a mark. It carries the state to restore (what this device saw
 * before marking: [restoreStatus] and the original purchase instant) and the `lock_version` the mark left, so it
 * only applies while nothing touched the item since. There is no deadline on the server; the ~5 s window is the
 * toast's.
 */
class UndoItemState(
    private val publicId: String,
    private val restoreStatus: ItemStatus,
    private val restorePurchasedAtMicros: Long?,
    private val markedLockVersion: Int,
) : OnlineAction {
    override fun plan(context: PlanContext): ActionPlan {
        val item = context.snapshot.listItems.firstOrNull { it.publicId.equals(publicId, ignoreCase = true) }
            ?: return ActionPlan.Invalid(InvalidAction.ITEM_NOT_FOUND)
        val purchased = restoreStatus == ItemStatus.PURCHASED
        val status = if (purchased) "purchased" else "pending"
        val at = restorePurchasedAtMicros.takeIf { purchased }?.let { JSONObject.quote(ServerTime.format(it)) } ?: "null"
        return ActionPlan.Send(
            context.document(
                type = "undo_item_state",
                payload = """{"public_id":${JSONObject.quote(item.publicId)},"status":"$status","purchased_at":$at}""",
                preconditions = """{"lock_version":$markedLockVersion}""",
            ),
        )
    }
}

/** What the server did with one name of an `add_items` command. */
data class AddedItem(val name: String, val itemPublicId: String, val outcome: AddOutcome)

/** The per-name results of a successful `add_items`; empty for any other receipt or one this client cannot read. */
fun CommandReceipt.addedItems(): List<AddedItem> {
    val data = dataJson ?: return emptyList()
    return try {
        val entries = (JSONTokener(data).nextValue() as? JSONObject)?.optJSONArray("items") ?: return emptyList()
        (0 until entries.length()).mapNotNull { index ->
            val entry = entries.optJSONObject(index) ?: return@mapNotNull null
            val outcome = when (entry.optString("outcome")) {
                "created" -> AddOutcome.CREATED
                "revived" -> AddOutcome.REVIVED
                "duplicate" -> AddOutcome.DUPLICATE
                else -> return@mapNotNull null
            }
            val id = entry.optString("canonical_public_id").ifBlank { entry.optString("public_id") }
            AddedItem(entry.optString("name"), id, outcome)
        }
    } catch (_: JSONException) {
        emptyList()
    }
}
