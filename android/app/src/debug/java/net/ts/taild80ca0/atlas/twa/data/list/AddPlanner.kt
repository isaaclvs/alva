package net.ts.taild80ca0.atlas.twa.data.list

import net.ts.taild80ca0.atlas.twa.data.db.ItemStatusCodes
import net.ts.taild80ca0.atlas.twa.data.db.ListItemEntity
import net.ts.taild80ca0.atlas.twa.data.matching.ItemCategorizer
import net.ts.taild80ca0.atlas.twa.data.matching.ItemNameSplitter
import net.ts.taild80ca0.atlas.twa.data.matching.TextNormalizer

/** What typing one name does to the list; the local twin of `Household::AddResult`. */
sealed interface AddStep {
    /** A new pending item, already categorized. */
    data class Create(val name: String, val publicId: String, val categoryPublicId: String?) : AddStep

    /** A purchased item typed again goes back to pending, keeping its own name. [categoryPublicId] is null when its category stays. */
    data class Revive(val name: String, val item: ListItemEntity, val categoryPublicId: String?) : AddStep

    /** The name is already pending on the list; nothing to do. */
    data class Duplicate(val name: String, val itemPublicId: String) : AddStep

    /** Nothing usable was typed. */
    data object Invalid : AddStep
}

/**
 * The rules of `Household#add_one_item`, run on the device against the live list:
 *
 * 1. a pending item with the same normalized name makes the name a duplicate;
 * 2. otherwise the most recently purchased one is revived, recategorized like a
 *    freshly typed name (the dictionary may have learned something since);
 * 3. otherwise a new item is created in the category the dictionary gives.
 *
 * Names are processed in order and see each other's effects, so "Leite" twice in
 * one batch is one item. There is no limit of names here: the cap of 30 belongs to
 * the text field ([ItemNameSplitter.split]); the habituals panel adds without it.
 * Only a local decision: the server stays the authority and resolves divergences
 * when the command reaches it.
 */
class AddPlanner(private val categorizer: ItemCategorizer, private val ids: IdGenerator) {

    /** A working copy of a live item, updated as the batch is planned. */
    private class Row(val item: ListItemEntity?, val publicId: String, val key: String, var purchased: Boolean, val order: Int)

    fun plan(names: List<String>, live: List<ListItemEntity>): List<AddStep> {
        val cleaned = names.mapNotNull { ItemNameSplitter.clean(it) }
        if (cleaned.isEmpty()) return listOf(AddStep.Invalid)

        val rows = live.filter { it.tripPublicId == null }.mapIndexedTo(ArrayList()) { index, item ->
            Row(item, item.publicId, TextNormalizer.key(item.name), item.status == ItemStatusCodes.PURCHASED, index)
        }

        return cleaned.map { name ->
            val key = TextNormalizer.key(name)
            val pending = if (key.isEmpty()) null else rows.firstOrNull { !it.purchased && it.key == key }
            if (pending != null) return@map AddStep.Duplicate(name, pending.publicId)

            val purchased = if (key.isEmpty()) null else mostRecentlyPurchased(rows, key)
            if (purchased?.item != null) {
                purchased.purchased = false
                val automatic = categorizer.categoryFor(purchased.item.name)
                return@map AddStep.Revive(name, purchased.item, automatic?.takeIf { it != purchased.item.categoryPublicId })
            }

            val publicId = ids.newId()
            rows += Row(null, publicId, key, purchased = false, order = rows.size)
            AddStep.Create(name, publicId, categorizer.categoryFor(name))
        }
    }

    /** Latest `purchased_at`, ties (and missing dates) going to the one lower in the list, like Rails' `id DESC`. */
    private fun mostRecentlyPurchased(rows: List<Row>, key: String): Row? =
        rows.filter { it.purchased && it.key == key }
            .maxWithOrNull(compareBy<Row> { it.item?.purchasedAt ?: Long.MIN_VALUE }.thenBy { it.order })
}
