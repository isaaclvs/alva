package net.ts.taild80ca0.atlas.twa.data.catalog

import net.ts.taild80ca0.atlas.twa.data.list.Category
import org.json.JSONObject

/** A dictionary entry exactly as the server publishes it. */
data class CatalogEntry(
    val publicId: String,
    val name: String,
    val normalizedName: String,
    val categoryPublicId: String,
    val learned: Boolean,
)

/**
 * The categories and the dictionary of one server snapshot, with the dictionary
 * revision that describes them. Everything refers to categories by the server's
 * `public_id`; the device never makes up a category id.
 */
data class CatalogSnapshot(
    val categories: List<Category>,
    val entries: List<CatalogEntry>,
    val dictionaryRevision: Long,
) {
    companion object {
        /** Reads `categories`, `catalog_items` and `dictionary_revision` of `GET /api/v1/snapshot`. */
        fun fromJson(snapshot: JSONObject): CatalogSnapshot {
            val categories = snapshot.getJSONArray("categories")
            val entries = snapshot.getJSONArray("catalog_items")
            return CatalogSnapshot(
                categories = (0 until categories.length()).map {
                    categories.getJSONObject(it).let { c -> Category(c.getString("public_id"), c.getString("name"), c.getInt("position")) }
                },
                entries = (0 until entries.length()).map {
                    entries.getJSONObject(it).let { e ->
                        CatalogEntry(
                            publicId = e.getString("public_id"),
                            name = e.getString("name"),
                            normalizedName = e.getString("normalized_name"),
                            categoryPublicId = e.getString("category_public_id"),
                            learned = e.getBoolean("learned"),
                        )
                    }
                },
                dictionaryRevision = snapshot.getLong("dictionary_revision"),
            )
        }
    }
}

