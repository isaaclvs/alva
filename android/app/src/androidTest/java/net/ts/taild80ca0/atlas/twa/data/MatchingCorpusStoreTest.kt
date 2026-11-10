package net.ts.taild80ca0.atlas.twa.data

import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import net.ts.taild80ca0.atlas.twa.data.catalog.CatalogEntry
import net.ts.taild80ca0.atlas.twa.data.catalog.CatalogSnapshot
import net.ts.taild80ca0.atlas.twa.data.catalog.LocalCatalog
import net.ts.taild80ca0.atlas.twa.data.db.AlvaDatabaseFactory
import net.ts.taild80ca0.atlas.twa.data.db.ItemStatusCodes
import net.ts.taild80ca0.atlas.twa.data.db.ListItemEntity
import net.ts.taild80ca0.atlas.twa.data.list.AddOutcome
import net.ts.taild80ca0.atlas.twa.data.list.Category
import net.ts.taild80ca0.atlas.twa.data.list.IntentResult
import net.ts.taild80ca0.atlas.twa.data.list.ListIntent
import net.ts.taild80ca0.atlas.twa.data.list.RoomShoppingListStore
import net.ts.taild80ca0.atlas.twa.data.matching.ItemNameSplitter
import net.ts.taild80ca0.atlas.twa.data.matching.TextNormalizer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The shared corpus (test/fixtures/files/matching_corpus.json, packaged as a test asset)
 * on the Android runtime: the text rules on ART, and every `add` example through the
 * real store on Room, comparing outcomes and the resulting list with what the Rails
 * suite expects from `Household#add_items`.
 */
class MatchingCorpusStoreTest {
    private val corpus: JSONObject = InstrumentationRegistry.getInstrumentation().context.assets
        .open("matching_corpus.json").bufferedReader(Charsets.UTF_8).use { JSONObject(it.readText()) }

    private val categories: List<Category> = corpus.getJSONObject("dictionary").getJSONArray("categories").let { array ->
        (0 until array.length()).map {
            val c = array.getJSONObject(it)
            Category(idOf(c.getString("name")), c.getString("name"), c.getInt("position"))
        }
    }

    private val entries: List<CatalogEntry> = corpus.getJSONObject("dictionary").getJSONArray("entries").let { array ->
        (0 until array.length()).map {
            val e = array.getJSONObject(it)
            CatalogEntry(
                "entry-$it", e.getString("name"), TextNormalizer.key(e.getString("name")),
                idOf(e.getString("category")), e.getBoolean("learned"),
            )
        }
    }

    private fun idOf(categoryName: String) = UUID.nameUUIDFromBytes(categoryName.toByteArray()).toString()

    private fun nameOf(publicId: String?) = categories.firstOrNull { it.publicId == publicId }?.name

    private fun JSONArray.strings() = (0 until length()).map { getString(it) }

    private fun JSONArray.objects() = (0 until length()).map { getJSONObject(it) }

    @Test
    fun theTextRulesGiveTheCorpusResultsOnTheAndroidRuntime() {
        corpus.getJSONArray("normalize").objects().forEach {
            assertEquals("plain(${it.getString("input")})", it.getString("plain"), TextNormalizer.plain(it.getString("input")))
            assertEquals("key(${it.getString("input")})", it.getString("key"), TextNormalizer.key(it.getString("input")))
        }
        corpus.getJSONArray("split").objects().forEach {
            assertEquals("split(${it.getString("input")})", it.getJSONArray("names").strings(), ItemNameSplitter.split(it.getString("input")))
        }
    }

    @Test
    fun theCategoriesComeFromTheDictionaryStoredInRoom() = runBlocking {
        val db = AlvaDatabaseFactory.create(InstrumentationRegistry.getInstrumentation().targetContext, null)
        try {
            LocalCatalog(db).replace(CatalogSnapshot(categories, entries, dictionaryRevision = 1))
            val categorizer = LocalCatalog(db).categorizer()

            corpus.getJSONArray("categorize").objects().forEach {
                assertEquals(
                    "categorize(${it.getString("input")})",
                    it.getString("category"),
                    nameOf(categorizer.categoryFor(it.getString("input"))),
                )
            }
        } finally {
            db.close()
        }
    }

    @Test
    fun everyAddExampleHasTheSameOutcomesAndFinalListAsRails() = runBlocking {
        val cases = corpus.getJSONArray("add").objects()
        assertEquals(13, cases.size)
        for (case in cases) {
            val title = case.getString("title")
            val db = AlvaDatabaseFactory.create(InstrumentationRegistry.getInstrumentation().targetContext, null)
            try {
                LocalCatalog(db).replace(CatalogSnapshot(categories, entries, dictionaryRevision = 1))
                seed(db, case.getJSONArray("existing"))
                val store = RoomShoppingListStore(db)

                val intent = if (case.has("names")) {
                    ListIntent.AddItems(case.getJSONArray("names").strings())
                } else {
                    ListIntent.AddText(case.getString("input"))
                }
                val outcomes = when (val result = store.execute(intent)) {
                    is IntentResult.Saved -> result.additions.map { it.outcome.name.lowercase() }
                    is IntentResult.AlreadyOnList -> result.additions.map { it.outcome.name.lowercase() }
                    is IntentResult.Invalid -> listOf("invalid")
                    else -> error("$title: unexpected $result")
                }

                assertEquals(title, case.getJSONArray("outcomes").strings(), outcomes)
                val expected = case.getJSONArray("final").objects().map {
                    Triple(it.getString("name"), it.getString("status"), if (it.isNull("category")) null else it.getString("category"))
                }
                val actual = store.observe().first().items.map {
                    Triple(it.name, if (it.status.code == ItemStatusCodes.PURCHASED) "purchased" else "pending", nameOf(it.categoryPublicId))
                }
                assertEquals(title, expected, actual)
                assertEquals(
                    "$title: the queue holds one command only when the list changed",
                    if (outcomes.any { it == AddOutcome.CREATED.name.lowercase() || it == AddOutcome.REVIVED.name.lowercase() }) 1 else 0,
                    db.commands().count(),
                )
            } finally {
                db.close()
            }
        }
    }

    /** Existing items go straight into the projection, like rows reconciled from a snapshot. */
    private suspend fun seed(db: net.ts.taild80ca0.atlas.twa.data.db.AlvaDatabase, existing: JSONArray) {
        db.listItems().upsert(
            existing.objects().mapIndexed { index, row ->
                val purchased = row.getString("status") == "purchased"
                ListItemEntity(
                    publicId = "old-$index",
                    name = row.getString("name"),
                    categoryPublicId = if (row.isNull("category")) null else idOf(row.getString("category")),
                    status = if (purchased) ItemStatusCodes.PURCHASED else ItemStatusCodes.PENDING,
                    position = null,
                    purchasedAt = if (purchased) 1_000L + index else null,
                    createdAt = index.toLong(),
                    lockVersion = 1,
                    tripPublicId = if (row.optBoolean("archived")) "trip" else null,
                )
            },
        )
    }
}
