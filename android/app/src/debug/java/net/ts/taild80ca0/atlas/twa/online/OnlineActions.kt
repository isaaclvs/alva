package net.ts.taild80ca0.atlas.twa.online

import net.ts.taild80ca0.atlas.twa.data.list.AddOutcome
import net.ts.taild80ca0.atlas.twa.data.list.ItemStatus
import net.ts.taild80ca0.atlas.twa.data.matching.ItemNameSplitter
import net.ts.taild80ca0.atlas.twa.data.matching.TextNormalizer
import net.ts.taild80ca0.atlas.twa.sync.CommandReceipt
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

/**
 * `correct_item`: fixes the category (and, later, the name) of an item on the list, in place. A category chosen by
 * hand is also what teaches the server's dictionary, like the pencil of the PWA, so the same name lands there next
 * time. [name] null keeps the name the snapshot shows. Preconditions come from the last snapshot: the item's
 * `lock_version` and, with a category, the `dictionary_revision` the person was looking at (the server still
 * applies the category if the dictionary moved on; only the learning waits).
 */
class CorrectItem(
    private val publicId: String,
    private val categoryPublicId: String? = null,
    private val name: String? = null,
) : OnlineAction {
    override fun plan(context: PlanContext): ActionPlan {
        val item = context.snapshot.listItems.firstOrNull { it.publicId.equals(publicId, ignoreCase = true) }
            ?: return ActionPlan.Invalid(InvalidAction.ITEM_NOT_FOUND)
        val cleaned = ItemNameSplitter.clean(name ?: item.name) ?: return ActionPlan.Invalid(InvalidAction.NO_NAMES)
        val payload = buildString {
            append("""{"public_id":""").append(JSONObject.quote(item.publicId))
            append(""","name":""").append(JSONObject.quote(cleaned))
            if (categoryPublicId != null) append(""","category_id":""").append(JSONObject.quote(categoryPublicId))
            append('}')
        }
        val preconditions = buildString {
            append("""{"lock_version":""").append(item.lockVersion)
            if (categoryPublicId != null) append(""","dictionary_revision":""").append(context.snapshot.dictionaryRevision)
            append('}')
        }
        return ActionPlan.Send(context.document("correct_item", payload, preconditions))
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
