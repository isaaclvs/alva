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
