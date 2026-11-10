package net.ts.taild80ca0.atlas.twa.data.catalog

import androidx.room.withTransaction
import net.ts.taild80ca0.atlas.twa.data.db.AlvaDatabase
import net.ts.taild80ca0.atlas.twa.data.db.CatalogItemEntity
import net.ts.taild80ca0.atlas.twa.data.db.CatalogStateEntity
import net.ts.taild80ca0.atlas.twa.data.db.CategoryEntity
import net.ts.taild80ca0.atlas.twa.data.list.Category
import net.ts.taild80ca0.atlas.twa.data.matching.DictionaryEntry
import net.ts.taild80ca0.atlas.twa.data.matching.ItemCategorizer
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

/**
 * The categories and dictionary kept on the device so that adding an item can be
 * categorized without the network.
 *
 * The server is the authority: [replace] swaps the whole catalog for the one the
 * server sent, learned entries included, and refuses one older than what is
 * stored (an old snapshot never replaces a newer one). It touches no list item:
 * a category the user picked by hand stays on its item, and an item already
 * categorized is not moved by a new dictionary.
 */
class LocalCatalog(private val db: AlvaDatabase) {
    /** Applies [snapshot] in one transaction. Returns false (changing nothing) when it is older than the stored one. */
    suspend fun replace(snapshot: CatalogSnapshot): Boolean =
        db.withTransaction {
            val stored = db.catalog().state()?.dictionaryRevision
            if (stored != null && snapshot.dictionaryRevision < stored) return@withTransaction false

            db.categories().clear()
            db.categories().upsert(snapshot.categories.map { CategoryEntity(it.publicId, it.name, it.position) })
            db.catalog().clearEntries()
            db.catalog().insertEntries(
                snapshot.entries.map {
                    CatalogItemEntity(it.publicId, it.name, it.normalizedName, it.categoryPublicId, it.learned)
                },
            )
            db.catalog().putState(CatalogStateEntity(dictionaryRevision = snapshot.dictionaryRevision))
            true
        }

    /** The dictionary revision stored, or null before the first catalog was applied. */
    suspend fun dictionaryRevision(): Long? = db.catalog().state()?.dictionaryRevision

    /** A categorizer over what is stored now. Call it inside the transaction that uses it for a consistent read. */
    suspend fun categorizer(): ItemCategorizer = categorizerOn(db)

    companion object {
        suspend fun categorizerOn(db: AlvaDatabase): ItemCategorizer =
            ItemCategorizer(
                entries = db.catalog().entries().map { DictionaryEntry(it.normalizedName, it.categoryPublicId) },
                categories = db.categories().all().map { Category(it.publicId, it.name, it.position) },
            )
    }
}
