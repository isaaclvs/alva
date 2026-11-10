package net.ts.taild80ca0.atlas.twa.data.matching

import net.ts.taild80ca0.atlas.twa.data.list.Category

/** One dictionary entry as the server publishes it (`catalog_items` in the snapshot). */
data class DictionaryEntry(val normalizedName: String, val categoryPublicId: String)

/**
 * Kotlin twin of Rails' `ItemCategorizer.call`, working on the dictionary and
 * categories synced to the device: an exact lookup of the normalized name, with
 * "Outros" as the fallback. No AI and no fuzzy matching.
 *
 * It answers with the server's category UUID. When there is no match and the
 * categories of the household were never synced (so there is no "Outros" yet), it
 * answers null (uncategorized) instead of inventing an id; the server categorizes
 * the item when the command reaches it.
 */
class ItemCategorizer(entries: Collection<DictionaryEntry>, categories: Collection<Category>) {
    private val known = categories.mapTo(HashSet()) { it.publicId }
    private val byName = HashMap<String, String>().also { map ->
        // An entry pointing at a category that is not here would be a dangling reference.
        entries.filter { it.categoryPublicId in known }.forEach { map[it.normalizedName] = it.categoryPublicId }
    }
    private val fallback: String? = categories.firstOrNull { it.name == FALLBACK_CATEGORY }?.publicId

    fun categoryFor(name: String?): String? = byName[TextNormalizer.key(name)] ?: fallback

    companion object {
        const val FALLBACK_CATEGORY = "Outros"
    }
}
