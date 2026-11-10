package net.ts.taild80ca0.atlas.twa.data

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import net.ts.taild80ca0.atlas.twa.data.catalog.CatalogEntry
import net.ts.taild80ca0.atlas.twa.data.catalog.CatalogSnapshot
import net.ts.taild80ca0.atlas.twa.data.catalog.LocalCatalog
import net.ts.taild80ca0.atlas.twa.data.db.AlvaDatabase
import net.ts.taild80ca0.atlas.twa.data.db.ConfirmedItemEntity
import net.ts.taild80ca0.atlas.twa.data.db.ItemStatusCodes
import net.ts.taild80ca0.atlas.twa.data.db.ListItemEntity
import net.ts.taild80ca0.atlas.twa.data.db.SubjectRole
import net.ts.taild80ca0.atlas.twa.data.list.AddOutcome
import net.ts.taild80ca0.atlas.twa.data.list.Category
import net.ts.taild80ca0.atlas.twa.data.list.CommandStatus
import net.ts.taild80ca0.atlas.twa.data.list.IntentResult
import net.ts.taild80ca0.atlas.twa.data.list.InvalidReason
import net.ts.taild80ca0.atlas.twa.data.list.ItemAddition
import net.ts.taild80ca0.atlas.twa.data.list.ItemStatus
import net.ts.taild80ca0.atlas.twa.data.list.ListIntent
import net.ts.taild80ca0.atlas.twa.data.matching.TextNormalizer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Duplicate detection and categorization through the real store and Room: offline,
 * from the dictionary synced earlier, with the command left exactly in the shape
 * the contract defines (`add_items` + `names`).
 */
class LocalMatchingStoreTest : DatabaseTest() {
    private val produce = Category("20000000-0000-4000-8000-000000000001", "Hortifruti", 1)
    private val dairy = Category("20000000-0000-4000-8000-000000000002", "Laticínios", 2)
    private val other = Category("20000000-0000-4000-8000-000000000003", "Outros", 99)

    private fun entry(n: Int, name: String, category: Category, learned: Boolean = false) =
        CatalogEntry("30000000-0000-4000-8000-00000000000$n", name, TextNormalizer.key(name), category.publicId, learned)

    private fun snapshot(revision: Long = 7, vararg extra: CatalogEntry) = CatalogSnapshot(
        categories = listOf(produce, dairy, other),
        entries = listOf(entry(1, "Leite", dairy), entry(2, "Banana", produce)) + extra,
        dictionaryRevision = revision,
    )

    private suspend fun AlvaDatabase.categoryOf(name: String) = listItems().all().first { it.name == name }.categoryPublicId

    @Test
    fun namesAreCategorizedOfflineFromTheSyncedDictionaryWithOutrosAsFallback() = runBlocking {
        val db = openDatabase()
        LocalCatalog(db).replace(snapshot())
        val store = storeOn(db)

        val result = store.execute(ListIntent.AddText("leites, Bananas; Coisa rara")) as IntentResult.Saved

        assertEquals(listOf(dairy.publicId, produce.publicId, other.publicId), result.itemIds.map { db.listItems().find(it)!!.categoryPublicId })
        assertEquals(List(3) { AddOutcome.CREATED }, result.additions.map { it.outcome })
        // The queued command keeps the contract shape: only `names`, no category on the wire.
        val command = db.commands().find(result.commandId)!!
        assertEquals(listOf("names"), JSONObject(command.payload).keys().asSequence().toList())
        assertEquals("{}", command.preconditions)
        // What the device decided stays with the command as local metadata, per name.
        assertEquals(
            listOf(dairy.publicId, produce.publicId, other.publicId),
            db.commands().subjectsOf(result.commandId).map { it.categoryPublicId },
        )
    }

    @Test
    fun withoutASyncedCatalogNoCategoryIsMadeUpAndTheNextAddUsesTheCatalogOnceItArrives() = runBlocking {
        val db = openDatabase()
        val store = storeOn(db)

        store.execute(ListIntent.AddText("Leite"))
        assertNull(db.categoryOf("Leite"))
        assertNull(db.commands().inOrder().single().let { db.commands().subjectsOf(it.id).single().categoryPublicId })

        LocalCatalog(db).replace(snapshot())
        store.execute(ListIntent.AddText("Banana"))

        assertEquals(produce.publicId, db.categoryOf("Banana"))
        assertNull("items already added keep what they had until the server answers", db.categoryOf("Leite"))
    }

    @Test
    fun aNameAlreadyPendingIsReportedAndNothingIsWrittenOrQueued() = runBlocking {
        val db = openDatabase()
        LocalCatalog(db).replace(snapshot())
        val store = storeOn(db)
        val first = store.execute(ListIntent.AddText("Leite, Pão")) as IntentResult.Saved
        val milk = first.itemIds.first()

        val again = store.execute(ListIntent.AddText("LEITES, pão")) as IntentResult.AlreadyOnList

        assertEquals(
            listOf(ItemAddition("LEITES", AddOutcome.DUPLICATE, milk), ItemAddition("pão", AddOutcome.DUPLICATE, first.itemIds[1])),
            again.additions,
        )
        assertEquals(1, db.commands().count())
        assertEquals(2, db.listItems().all().size)
    }

    @Test
    fun aMixedTextQueuesOnlyTheNamesThatChangeTheList() = runBlocking {
        val db = openDatabase()
        LocalCatalog(db).replace(snapshot())
        val store = storeOn(db)
        store.execute(ListIntent.AddText("Leite"))

        val result = store.execute(ListIntent.AddText("leite, Banana")) as IntentResult.Saved

        assertEquals(listOf(AddOutcome.DUPLICATE, AddOutcome.CREATED), result.additions.map { it.outcome })
        assertEquals(1, result.itemIds.size)
        assertEquals(listOf("Banana"), JSONObject(db.commands().find(result.commandId)!!.payload).getJSONArray("names").let { listOf(it.getString(0)) })
        assertEquals(listOf(0), db.commands().subjectsOf(result.commandId).map { it.ordinal })
    }

    @Test
    fun aPurchasedNameTypedAgainRevivesTheSameRowAndTheProjectionSurvivesARebuild() = runBlocking {
        val db = openDatabase()
        LocalCatalog(db).replace(snapshot())
        db.confirmedItems().upsert(
            listOf(
                ConfirmedItemEntity("old-id", "Leite", other.publicId, ItemStatusCodes.PURCHASED, null, 50, 10, 4, null),
                ConfirmedItemEntity("bread-id", "Pão", other.publicId, ItemStatusCodes.PURCHASED, null, 60, 11, 1, null),
            ),
        )
        val store = storeOn(db)
        store.rebuildProjection()

        val result = store.execute(ListIntent.AddText("LEITE")) as IntentResult.Saved

        assertEquals(listOf(ItemAddition("LEITE", AddOutcome.REVIVED, "old-id")), result.additions)
        val revived = db.listItems().find("old-id")!!
        assertEquals("keeps its own spelling", "Leite", revived.name)
        assertEquals(ItemStatusCodes.PENDING, revived.status)
        assertNull(revived.purchasedAt)
        assertEquals("lands where a new item would", dairy.publicId, revived.categoryPublicId)
        assertEquals(10L, revived.createdAt)
        assertEquals("the untouched purchased item stays as it was", ItemStatusCodes.PURCHASED, db.listItems().find("bread-id")!!.status)
        // Same command shape as a plain add: the typed name, no UUIDs invented for a record that exists.
        val command = db.commands().find(result.commandId)!!
        assertEquals(listOf("LEITE"), JSONObject(command.payload).getJSONArray("names").let { listOf(it.getString(0)) })
        db.commands().subjectsOf(result.commandId).single().let {
            assertEquals("old-id", it.subjectPublicId)
            assertEquals(SubjectRole.TARGETS, it.role)
            assertEquals(0, it.ordinal)
            assertEquals(dairy.publicId, it.categoryPublicId)
        }

        // Rebuilding confirmed + queue lands on exactly the same rows.
        val before = db.listItems().all()
        store.rebuildProjection()
        assertEquals(before, db.listItems().all())
    }

    @Test
    fun reviveFollowsTheQueueOrderOfWhatTouchedTheItemBeforeAndBlocksBehindAConflict() = runBlocking {
        val db = openDatabase()
        LocalCatalog(db).replace(snapshot())
        val store = storeOn(db)
        val add = store.execute(ListIntent.AddText("Leite")) as IntentResult.Saved
        val milk = add.itemIds.single()
        val bought = store.execute(ListIntent.SetItemState(milk, ItemStatus.PURCHASED)) as IntentResult.Saved

        val revive = store.execute(ListIntent.AddText("leite")) as IntentResult.Saved

        assertEquals(listOf(AddOutcome.REVIVED), revive.additions.map { it.outcome })
        assertEquals(bought.commandId, db.commands().find(revive.commandId)!!.dependsOn)
        assertEquals(CommandStatus.PENDING.name, db.commands().find(revive.commandId)!!.status)

        db.commands().setStatus(revive.commandId, CommandStatus.CONFLICT.name, "precondition_failed")
        store.execute(ListIntent.SetItemState(milk, ItemStatus.PURCHASED))
        val again = store.execute(ListIntent.AddText("leite")) as IntentResult.Saved
        assertEquals(CommandStatus.BLOCKED.name, db.commands().find(again.commandId)!!.status)
    }

    @Test
    fun theTextFieldKeepsTheLimitOf30ButAddedNamesHaveNone() = runBlocking {
        val db = openDatabase()
        val store = storeOn(db)
        val names = (1..40).map { "item $it" }

        val typed = store.execute(ListIntent.AddText(names.joinToString(", "))) as IntentResult.Saved
        assertEquals(30, typed.itemIds.size)
        assertEquals(30, db.listItems().all().size)

        val habituals = store.execute(ListIntent.AddItems(names.map { "hábito $it" })) as IntentResult.Saved
        assertEquals(40, habituals.itemIds.size)
        assertEquals(70, db.listItems().all().size)
        assertEquals(2, db.commands().count())
    }

    @Test
    fun repeatedNamesInOneBatchOfHabitualsBecomeOneItem() = runBlocking {
        val db = openDatabase()
        LocalCatalog(db).replace(snapshot())
        val store = storeOn(db)

        val result = store.execute(ListIntent.AddItems(listOf("Leite", "leite", "Banana", "bananas"))) as IntentResult.Saved

        assertEquals(
            listOf(AddOutcome.CREATED, AddOutcome.DUPLICATE, AddOutcome.CREATED, AddOutcome.DUPLICATE),
            result.additions.map { it.outcome },
        )
        assertEquals(listOf("Leite", "Banana"), db.listItems().all().map { it.name })
        assertEquals(result.additions[0].itemPublicId, result.additions[1].itemPublicId)
    }

    @Test
    fun nothingUsableIsInvalidAndLeavesNoTrace() = runBlocking {
        val db = openDatabase()
        val store = storeOn(db)

        assertEquals(IntentResult.Invalid(InvalidReason.NO_NAMES), store.execute(ListIntent.AddText(" , ;\n")))
        assertEquals(IntentResult.Invalid(InvalidReason.NO_NAMES), store.execute(ListIntent.AddText(" ")))
        assertEquals(0, db.commands().count())
        assertTrue(db.listItems().all().isEmpty())
    }

    @Test
    fun archivedItemsAreHistoryAndDoNotCountAsDuplicates() = runBlocking {
        val db = openDatabase()
        LocalCatalog(db).replace(snapshot())
        db.listItems().upsert(
            listOf(ListItemEntity("hist-id", "Leite", dairy.publicId, ItemStatusCodes.PURCHASED, null, 5, 1, 2, "trip-1")),
        )
        val store = storeOn(db)

        val result = store.execute(ListIntent.AddText("Leite")) as IntentResult.Saved

        assertEquals(listOf(AddOutcome.CREATED), result.additions.map { it.outcome })
        assertEquals(ItemStatusCodes.PURCHASED, db.listItems().find("hist-id")!!.status)
    }

    // --- the catalog on disk: server authority, learned entries, older snapshots ---

    @Test
    fun theCatalogSurvivesReopeningAndKeepsTheServersIdentifiers() = runBlocking {
        val first = openDatabase()
        assertNull(LocalCatalog(first).dictionaryRevision())
        assertTrue(LocalCatalog(first).replace(snapshot(revision = 7, entry(3, "Ração", other, learned = true))))
        first.close()

        val reopened = openDatabase()
        assertEquals(7L, LocalCatalog(reopened).dictionaryRevision())
        assertEquals(listOf(dairy.publicId, produce.publicId, other.publicId).toSet(), reopened.categories().all().map { it.publicId }.toSet())
        val stored = reopened.catalog().entries()
        assertEquals(3, stored.size)
        assertEquals(entry(3, "Ração", other, learned = true).publicId, stored.single { it.learned }.publicId)
        assertEquals(other.publicId, LocalCatalog(reopened).categorizer().categoryFor("Coisa desconhecida"))
    }

    @Test
    fun learnedEntriesCountAndTheServerStaysTheAuthorityOnTheNextSnapshot() = runBlocking {
        val db = openDatabase()
        val catalog = LocalCatalog(db)
        val store = storeOn(db)
        // The household taught the server that Leite belongs elsewhere.
        catalog.replace(snapshot(7, entry(3, "Leite", produce, learned = true)).let { it.copy(entries = it.entries.filter { e -> e.publicId != entry(1, "Leite", dairy).publicId }) })
        store.execute(ListIntent.AddText("leite"))
        assertEquals(produce.publicId, db.categoryOf("leite"))

        // The server later moves it back: its snapshot replaces the local catalog wholesale.
        assertTrue(catalog.replace(snapshot(8)))
        store.execute(ListIntent.AddText("Banana, Leites"))

        assertEquals("the item already on the list is not recategorized by a new dictionary", produce.publicId, db.categoryOf("leite"))
        assertEquals(listOf(false), db.catalog().entries().map { it.learned }.distinct())
        assertEquals(listOf("leite", "Banana"), db.listItems().all().map { it.name })
    }

    @Test
    fun aSnapshotOlderThanTheStoredCatalogChangesNothing() = runBlocking {
        val db = openDatabase()
        val catalog = LocalCatalog(db)
        catalog.replace(snapshot(revision = 9))

        val applied = catalog.replace(CatalogSnapshot(listOf(other), emptyList(), dictionaryRevision = 8))

        assertFalse(applied)
        assertEquals(9L, catalog.dictionaryRevision())
        assertEquals(3, db.categories().all().size)
        assertEquals(2, db.catalog().entries().size)
        assertTrue("the same revision is applied again (idempotent)", catalog.replace(snapshot(revision = 9)))
    }

    @Test
    fun aFailingReplaceLeavesTheOldCatalogUntouched() = runBlocking {
        val db = openDatabase()
        val catalog = LocalCatalog(db)
        catalog.replace(snapshot(revision = 7))

        // Two entries with the same normalized name break the unique index mid-way.
        val broken = snapshot(revision = 8, entry(4, "Leite", other))
        val failure = try { catalog.replace(broken); null } catch (expected: Exception) { expected }

        assertTrue(failure != null)
        assertEquals(7L, catalog.dictionaryRevision())
        assertEquals(2, db.catalog().entries().size)
        assertEquals(3, db.categories().all().size)
    }

    @Test
    fun theSnapshotJsonOfTheServerIsReadAsIs() {
        val json = JSONObject(
            """
            {"schema_version":1,"revision":12,"dictionary_revision":4,
             "categories":[{"public_id":"c-1","name":"Hortifruti","position":1},{"public_id":"c-2","name":"Outros","position":99}],
             "catalog_items":[{"public_id":"e-1","name":"Maçã","normalized_name":"maca","category_public_id":"c-1","learned":true}],
             "list_items":[],"archived_items":[]}
            """.trimIndent(),
        )

        val snapshot = CatalogSnapshot.fromJson(json)

        assertEquals(4L, snapshot.dictionaryRevision)
        assertEquals(listOf(Category("c-1", "Hortifruti", 1), Category("c-2", "Outros", 99)), snapshot.categories)
        assertEquals(listOf(CatalogEntry("e-1", "Maçã", "maca", "c-1", true)), snapshot.entries)
    }

    @Test
    fun adoptingAnItemThatWasQueuedBeforeTheMigrationKeepsItsEmptyCategory() = runBlocking {
        // A subject written by version 1 has no category column value at all.
        val db = openDatabase()
        val store = storeOn(db, ScriptedIds("old-id", "cmd-1"))
        store.execute(ListIntent.AddText("Leite"))
        db.openHelper.writableDatabase.execSQL("UPDATE command_subjects SET category_public_id = NULL")

        store.rebuildProjection()

        assertEquals("old-id", db.listItems().live().single().publicId)
        assertNull(db.listItems().live().single().categoryPublicId)
    }
}
