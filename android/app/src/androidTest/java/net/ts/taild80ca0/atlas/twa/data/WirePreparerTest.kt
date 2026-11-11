package net.ts.taild80ca0.atlas.twa.data

import androidx.room.withTransaction
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import net.ts.taild80ca0.atlas.twa.data.db.AlvaDatabase
import net.ts.taild80ca0.atlas.twa.data.db.ConfirmedItemEntity
import net.ts.taild80ca0.atlas.twa.data.db.ItemStatusCodes
import net.ts.taild80ca0.atlas.twa.data.db.SyncStateEntity
import net.ts.taild80ca0.atlas.twa.data.list.AttemptResult
import net.ts.taild80ca0.atlas.twa.data.list.CommandStatus
import net.ts.taild80ca0.atlas.twa.data.list.IdGenerator
import net.ts.taild80ca0.atlas.twa.data.list.IntentResult
import net.ts.taild80ca0.atlas.twa.data.list.ItemStatus
import net.ts.taild80ca0.atlas.twa.data.list.ListIntent
import net.ts.taild80ca0.atlas.twa.data.list.PreparationRefusal
import net.ts.taild80ca0.atlas.twa.data.list.PreparationResult
import net.ts.taild80ca0.atlas.twa.data.list.RoomShoppingListStore
import net.ts.taild80ca0.atlas.twa.data.list.WireItemLinks
import net.ts.taild80ca0.atlas.twa.data.list.WireOutbox
import net.ts.taild80ca0.atlas.twa.data.list.WirePreparer
import net.ts.taild80ca0.atlas.twa.data.list.WireSubject
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Preparing the request of a queued command (issue #45, second cut): the stored intent
 * becomes the contract v1 document, is frozen by [WireOutbox] and survives reopening and
 * retries. Real Room database files and the real store; no network exists.
 */
class WirePreparerTest : DatabaseTest() {

    private val item = "9a1d0000-0000-4000-8000-000000000001"
    private val milk = "40000000-0000-4000-8000-000000000001"
    private val detergent = "40000000-0000-4000-8000-000000000002"
    private fun cmd(suffix: String) = "5f0c2f0e-0000-4000-8000-0000000000$suffix"
    private fun claim(suffix: String) = "9a1d0000-0000-4000-8000-0000000000$suffix"

    /** Counts how many UUIDs the preparation asked for, to prove refusals and replays create none. */
    private class Counting(private vararg val scripted: String) : IdGenerator {
        val calls = AtomicInteger()
        override fun newId(): String = scripted.getOrNull(calls.getAndIncrement()) ?: java.util.UUID.randomUUID().toString()
    }

    private fun confirmed(id: String, name: String, lockVersion: Int, purchased: Boolean = false) = ConfirmedItemEntity(
        id, name, null, if (purchased) ItemStatusCodes.PURCHASED else ItemStatusCodes.PENDING, null,
        if (purchased) 50L else null, createdAt = 100, lockVersion = lockVersion, tripPublicId = null,
    )

    private suspend fun seed(db: AlvaDatabase, store: RoomShoppingListStore, vararg items: ConfirmedItemEntity) {
        db.confirmedItems().upsert(items.toList())
        store.rebuildProjection()
    }

    private fun PreparationResult.prepared() = this as PreparationResult.Prepared
    private fun PreparationResult.refusal() = (this as PreparationResult.Refused).reason

    private fun assertMatchesFixture(expected: JSONObject, document: String) =
        assertEquals(WireFixtures.canonical(expected), WireFixtures.canonical(JSONObject(document)))

    private fun entries(document: String) = JSONObject(document).getJSONObject("payload").getJSONArray("items")

    /** Everything the queue holds, to prove that a refusal changes nothing. */
    private suspend fun queue(db: AlvaDatabase) =
        db.commands().inOrder() to db.commands().inOrder().map { db.commands().subjectsOf(it.id) }

    // ---- documents the Rails suite accepts ----------------------------------------------

    @Test
    fun offlineAddMarkThenTypedAgainFreezesTheDocumentsRailsAccepts() = runBlocking {
        val db = openDatabase()
        val store = storeOn(db, ScriptedIds(item, cmd("a1"), cmd("a2"), cmd("a3")))
        assertTrue(store.execute(ListIntent.AddItems(listOf("Tapioca"))) is IntentResult.Saved)
        assertTrue(store.execute(ListIntent.SetItemState(item, ItemStatus.PURCHASED)) is IntentResult.Saved)
        assertTrue(store.execute(ListIntent.AddText("Tapioca")) is IntentResult.Saved)
        val projectionBefore = db.listItems().all()

        val preparer = WirePreparer(db, claimIds = Counting(claim("c1")))
        val documents = listOf("a1", "a2", "a3").map { preparer.prepare(cmd(it)).prepared() }

        assertTrue(documents.all { it.created })
        WireFixtures.documents("offline_add_mark_then_typed_again").zip(documents).forEach { (expected, prepared) ->
            assertMatchesFixture(expected, prepared.document)
        }
        // The claim is not the purchase it revives, and it ties back to the original subject by ordinal.
        val revive = documents[2].document
        val subjects = db.commands().subjectsOf(cmd("a3")).map { WireSubject(it.subjectPublicId, it.role, it.ordinal) }
        val link = WireItemLinks.of(revive, subjects)!!.single()
        assertEquals(claim("c1"), link.wirePublicId)
        assertEquals(item, link.subjectPublicId)
        assertEquals(item, link.revivedPublicId)
        assertNotEquals(link.wirePublicId, link.subjectPublicId)
        // The intent and the projection did not move, and the queue replays to the same list.
        assertEquals(projectionBefore, db.listItems().all())
        store.rebuildProjection()
        assertEquals(projectionBefore, db.listItems().all())
        assertEquals("""{"names":["Tapioca"]}""", db.commands().find(cmd("a3"))!!.payload)
    }

    @Test
    fun aPurchaseSeenOnTheDeviceIsRevivedWithTheVersionSeen() = runBlocking {
        val db = openDatabase()
        val store = storeOn(db, ScriptedIds(cmd("b1")))
        seed(db, store, confirmed(detergent, "Detergente", lockVersion = 0, purchased = true))
        assertTrue(store.execute(ListIntent.AddText("Detergente")) is IntentResult.Saved)

        val prepared = WirePreparer(db, claimIds = Counting(claim("c2"))).prepare(cmd("b1")).prepared()

        assertMatchesFixture(WireFixtures.documents("seen_purchase_is_revived_with_its_version").single(), prepared.document)
    }

    @Test
    fun createdNamesKeepTheirDeviceUuidsAndTheObservedRevision() = runBlocking {
        val db = openDatabase()
        db.syncState().put(SyncStateEntity(householdPublicId = "house", confirmedRevision = 7))
        val store = storeOn(db, ScriptedIds(claim("e1"), claim("e2"), cmd("d1")))
        assertTrue(store.execute(ListIntent.AddItems(listOf("Banana", "Pão de forma"))) is IntentResult.Saved)

        val prepared = WirePreparer(db).prepare(cmd("d1")).prepared()

        assertMatchesFixture(WireFixtures.documents("created_batch_keeps_device_uuids").single(), prepared.document)
    }

    @Test
    fun markingSendsTheDesiredStateAndTheVersionSeen() = runBlocking {
        val db = openDatabase()
        val store = storeOn(db, ScriptedIds(cmd("f1")))
        seed(db, store, confirmed(milk, "Leite", lockVersion = 0))
        assertTrue(store.execute(ListIntent.SetItemState(milk, ItemStatus.PURCHASED)) is IntentResult.Saved)

        val prepared = WirePreparer(db).prepare(cmd("f1")).prepared()

        assertMatchesFixture(WireFixtures.documents("mark_with_the_version_the_user_saw").single(), prepared.document)
    }

    // ---- set_item_state and dependencies --------------------------------------------------

    @Test
    fun markThenUnmarkKeepsBothDesiredStatesAndTheDependencyOfTheQueue() = runBlocking {
        val db = openDatabase()
        val store = storeOn(db, ScriptedIds(cmd("01"), cmd("02")))
        seed(db, store, confirmed(milk, "Leite", lockVersion = 5))
        store.execute(ListIntent.SetItemState(milk, ItemStatus.PURCHASED))
        store.execute(ListIntent.SetItemState(milk, ItemStatus.PENDING))

        val preparer = WirePreparer(db)
        val first = JSONObject(preparer.prepare(cmd("01")).prepared().document)
        val second = JSONObject(preparer.prepare(cmd("02")).prepared().document)

        assertEquals("purchased", first.getJSONObject("payload").getString("desired"))
        assertEquals("pending", second.getJSONObject("payload").getString("desired"))
        assertTrue(first.isNull("depends_on"))
        assertEquals(cmd("01"), second.getString("depends_on"))
        // Each keeps the version its author saw; the server lends the later one through depends_on.
        assertEquals(5, first.getJSONObject("preconditions").getInt("lock_version"))
        assertEquals(5, second.getJSONObject("preconditions").getInt("lock_version"))
    }

    @Test
    fun anItemTheServerNeverSawIsReachedOnlyThroughTheCommandThatCreatedIt() = runBlocking {
        val db = openDatabase()
        val store = storeOn(db, ScriptedIds(item, cmd("01"), cmd("02")))
        store.execute(ListIntent.AddItems(listOf("Leite")))
        store.execute(ListIntent.SetItemState(item, ItemStatus.PURCHASED))

        val mark = JSONObject(WirePreparer(db).prepare(cmd("02")).prepared().document)

        assertEquals(cmd("01"), mark.getString("depends_on"))
        assertEquals("no version is invented on the device", 0, mark.getJSONObject("preconditions").length())
    }

    // ---- revive: what the user saw, when they saw it ---------------------------------------

    @Test
    fun aSnapshotAppliedAfterTheIntentDoesNotPassAsWhatTheUserSaw() = runBlocking {
        val db = openDatabase()
        val store = storeOn(db, ScriptedIds(cmd("b1")))
        seed(db, store, confirmed(detergent, "Detergente", lockVersion = 3, purchased = true))
        assertTrue(store.execute(ListIntent.AddText("Detergente")) is IntentResult.Saved)

        // Someone bought it again meanwhile; a newer snapshot reaches the device before preparing.
        db.confirmedItems().upsert(listOf(confirmed(detergent, "Detergente", lockVersion = 8, purchased = true)))
        store.rebuildProjection()
        assertEquals(8, db.listItems().find(detergent)!!.lockVersion)

        val revives = entries(WirePreparer(db).prepare(cmd("b1")).prepared().document).getJSONObject(0).getJSONObject("revives")

        assertEquals("the version at the moment of the intent", 3, revives.getInt("lock_version"))
    }

    @Test
    fun aReviveOfAnItemBoughtLocallyAfterBeingSeenStillSendsTheVersionSeenAndLendsTheRest() = runBlocking {
        val db = openDatabase()
        val store = storeOn(db, ScriptedIds(cmd("01"), cmd("02")))
        seed(db, store, confirmed(milk, "Leite", lockVersion = 4))
        store.execute(ListIntent.SetItemState(milk, ItemStatus.PURCHASED))
        store.execute(ListIntent.AddText("Leite"))

        val document = WirePreparer(db).prepare(cmd("02")).prepared().document

        val revive = entries(document).getJSONObject(0)
        assertEquals(milk, revive.getJSONObject("revives").getString("public_id"))
        assertEquals(4, revive.getJSONObject("revives").getInt("lock_version"))
        assertEquals(cmd("01"), JSONObject(document).getString("depends_on"))
        assertNotEquals(milk, revive.getString("public_id"))
    }

    @Test
    fun oneRevivedPurchaseCoveredByTheDependencyAndOneSeenInASnapshotGoTogether() = runBlocking {
        val db = openDatabase()
        val store = storeOn(db, ScriptedIds(item, cmd("01"), cmd("02"), cmd("03")))
        seed(db, store, confirmed(detergent, "Detergente", lockVersion = 2, purchased = true))
        store.execute(ListIntent.AddItems(listOf("Arroz")))
        store.execute(ListIntent.SetItemState(item, ItemStatus.PURCHASED))
        store.execute(ListIntent.AddText("Arroz, Detergente"))

        val items = entries(WirePreparer(db).prepare(cmd("03")).prepared().document)

        val arroz = items.getJSONObject(0).getJSONObject("revives")
        val soap = items.getJSONObject(1).getJSONObject("revives")
        assertEquals(item, arroz.getString("public_id"))
        assertFalse("nothing to claim for an item the server never saw", arroz.has("lock_version"))
        assertEquals(2, soap.getInt("lock_version"))
        assertEquals(setOf(items.getJSONObject(0).getString("public_id"), items.getJSONObject(1).getString("public_id")).size, 2)
    }

    // ---- freeze once, retry byte for byte ----------------------------------------------------

    @Test
    fun theFrozenDocumentSurvivesReopeningAndRetriesWithoutNewIdentities() = runBlocking {
        val first = openDatabase()
        val store = storeOn(first, ScriptedIds(item, cmd("01"), cmd("02"), cmd("03")))
        store.execute(ListIntent.AddItems(listOf("Leite")))
        store.execute(ListIntent.SetItemState(item, ItemStatus.PURCHASED))
        store.execute(ListIntent.AddText("Leite"))
        val original = WirePreparer(first, claimIds = Counting(claim("c1"))).prepare(cmd("03")).prepared()
        assertTrue(original.created)
        first.close()

        val reopened = openDatabase()
        val newClaims = Counting(claim("c9"))
        val again = WirePreparer(reopened, claimIds = newClaims).prepare(cmd("03")).prepared()
        val attempts = (1..3).map { WireOutbox(reopened).beginAttempt(cmd("03")) as AttemptResult.Started }

        assertFalse(again.created)
        assertArrayEquals(original.document.toByteArray(Charsets.UTF_8), again.document.toByteArray(Charsets.UTF_8))
        attempts.forEach { assertArrayEquals(original.document.toByteArray(Charsets.UTF_8), it.document.toByteArray(Charsets.UTF_8)) }
        assertEquals(listOf(1, 2, 3), attempts.map { it.attempt })
        assertEquals("a replay generates no identity", 0, newClaims.calls.get())
        assertEquals(claim("c1"), entries(again.document).getJSONObject(0).getString("public_id"))
    }

    @Test
    fun anAlreadyFrozenDocumentIsReturnedExactlyEvenWhenPreparingAgainWouldRefuse() = runBlocking {
        val db = openDatabase()
        val sql = db.openHelper.writableDatabase
        sql.execSQL("INSERT INTO list_items (public_id, name, status, created_at) VALUES ('$milk', 'Leite', 1, 10)")
        sql.execSQL(
            "INSERT INTO commands (id, type, payload, preconditions, observed_revision, status, created_at, attempts) " +
                "VALUES ('old-1', 'add_items', '{\"names\":[\"Leite\"]}', '{}', 0, 'PENDING', 10, 0)",
        )
        sql.execSQL("INSERT INTO command_subjects (command_id, subject_public_id, ordinal, role) VALUES ('old-1', '$milk', 0, 'targets')")
        val frozen = """{"id":"old-1","type":"add_items","depends_on":null,"preconditions":{},"payload":{"items":[{"public_id":"${claim("aa")}","name":"Leite","revives":{"public_id":"$milk","lock_version":1}}]}}"""
        assertTrue(WireOutbox(db).freeze("old-1", frozen) is net.ts.taild80ca0.atlas.twa.data.list.FreezeResult.Frozen)
        val claims = Counting()

        val result = WirePreparer(db, claimIds = claims).prepare("old-1").prepared()

        assertEquals(frozen, result.document)
        assertFalse(result.created)
        assertEquals(0, claims.calls.get())
        // Also after attempts, and for a command that has since been blocked.
        WireOutbox(db).beginAttempt("old-1")
        db.commands().setStatus("old-1", CommandStatus.CONFLICT.name, "precondition_failed")
        assertEquals(frozen, WirePreparer(db, claimIds = claims).prepare("old-1").prepared().document)
    }

    @Test
    fun twoPreparersRacingForTheSameCommandAgreeOnOneDocumentAndOneClaim() = runBlocking {
        val db = openDatabase()
        val store = storeOn(db, ScriptedIds(item, cmd("01"), cmd("02"), cmd("03")))
        store.execute(ListIntent.AddItems(listOf("Leite")))
        store.execute(ListIntent.SetItemState(item, ItemStatus.PURCHASED))
        store.execute(ListIntent.AddText("Leite"))
        val generators = (1..16).map { Counting("9a1d0000-0000-4000-8000-0000000001%02x".format(it)) }

        val results = generators.mapIndexed { index, claims ->
            async(Dispatchers.Default) { WirePreparer(db, claimIds = claims).prepare(cmd("03")).prepared() }
        }.awaitAll()

        assertEquals("exactly one preparation wins", 1, results.count { it.created })
        assertEquals(1, results.map { it.document }.toSet().size)
        val stored = db.commands().find(cmd("03"))!!.wireDocument
        assertEquals(stored, results.first().document)
        val winningClaim = entries(stored!!).getJSONObject(0).getString("public_id")
        assertTrue(winningClaim in (1..16).map { "9a1d0000-0000-4000-8000-0000000001%02x".format(it) })
        assertEquals(0, db.commands().find(cmd("03"))!!.attempts)
    }

    // ---- SQL failure leaves nothing half done -------------------------------------------------

    @Test
    fun aFailingStatementPersistsNothingAndALaterPreparationStartsClean() = runBlocking {
        val db = openDatabase()
        val store = storeOn(db, ScriptedIds(item, cmd("01"), cmd("02")))
        store.execute(ListIntent.AddItems(listOf("Leite")))
        store.execute(ListIntent.SetItemState(item, ItemStatus.PURCHASED))
        store.execute(ListIntent.AddText("Leite"))
        val sql = db.openHelper.writableDatabase
        val before = queue(db)

        sql.execSQL("CREATE TRIGGER refuse_freeze BEFORE UPDATE OF wire_document ON commands BEGIN SELECT RAISE(ABORT, 'no documents today'); END")
        val lost = Counting(claim("c1"))
        try {
            WirePreparer(db, claimIds = lost).prepare(cmd("03"))
            fail("a failing statement must surface, not look like a refusal")
        } catch (expected: Exception) {
            assertTrue(expected.message.orEmpty().contains("no documents today"))
        }
        assertEquals(before, queue(db))
        sql.execSQL("DROP TRIGGER refuse_freeze")

        val retry = WirePreparer(db, claimIds = Counting(claim("c2"))).prepare(cmd("03")).prepared()
        assertTrue(retry.created)
        assertEquals("the identity lost with the failed attempt never reaches the queue", claim("c2"), entries(retry.document).getJSONObject(0).getString("public_id"))
    }

    @Test
    fun aPreparationJoinedToAFailingTransactionIsUndoneWithIt() = runBlocking {
        val db = openDatabase()
        val store = storeOn(db, ScriptedIds(item, cmd("01")))
        store.execute(ListIntent.AddItems(listOf("Leite")))
        val before = queue(db)

        try {
            db.withTransaction {
                assertTrue(WirePreparer(db).prepare(cmd("01")) is PreparationResult.Prepared)
                assertTrue(db.commands().find(cmd("01"))!!.wireDocument != null)
                error("the caller failed after preparing")
            }
            fail()
        } catch (expected: IllegalStateException) {
            // expected
        }
        assertEquals(before, queue(db))
        assertNull(db.commands().find(cmd("01"))!!.wireDocument)
    }

    // ---- refusals keep everything -----------------------------------------------------------------

    @Test
    fun withoutAVersionOrAUsefulDependencyTheCommandIsRefusedAndKept() = runBlocking {
        val db = openDatabase()
        val sql = db.openHelper.writableDatabase
        // Two items the server confirmed earlier but whose confirmed row is not known here (version unknown).
        sql.execSQL("INSERT INTO list_items (public_id, name, status, created_at) VALUES ('$milk', 'Leite', 1, 10)")
        sql.execSQL("INSERT INTO list_items (public_id, name, status, created_at) VALUES ('$detergent', 'Detergente', 0, 11)")
        val store = storeOn(db, ScriptedIds(cmd("01"), cmd("02")))
        assertTrue(store.execute(ListIntent.AddText("Leite")) is IntentResult.Saved)
        assertTrue(store.execute(ListIntent.SetItemState(detergent, ItemStatus.PURCHASED)) is IntentResult.Saved)
        val before = queue(db)
        val claims = Counting()
        val preparer = WirePreparer(db, claimIds = claims)

        assertEquals(PreparationRefusal.VERSION_UNKNOWN, preparer.prepare(cmd("01")).refusal())
        assertEquals(PreparationRefusal.VERSION_UNKNOWN, preparer.prepare(cmd("02")).refusal())

        assertEquals(before, queue(db))
        assertEquals("a refusal creates no identity", 0, claims.calls.get())
        assertTrue(db.commands().inOrder().all { it.wireDocument == null && it.attempts == 0 })
    }

    @Test
    fun aDependencyThatTouchesOtherItemsCannotLendAVersionSoTheWholeCommandWaits() = runBlocking {
        val db = openDatabase()
        val store = storeOn(db, ScriptedIds("arroz-id", cmd("01"), cmd("02"), "feijao-id", cmd("03"), cmd("04"), cmd("05")))
        store.execute(ListIntent.AddItems(listOf("Arroz")))
        store.execute(ListIntent.SetItemState("arroz-id", ItemStatus.PURCHASED))
        store.execute(ListIntent.AddItems(listOf("Feijão")))
        store.execute(ListIntent.SetItemState("feijao-id", ItemStatus.PURCHASED))
        store.execute(ListIntent.AddText("Arroz, Feijão"))
        val before = queue(db)
        val preparer = WirePreparer(db)

        assertEquals(PreparationRefusal.DEPENDENCY_INSUFFICIENT, preparer.prepare(cmd("05")).refusal())

        assertEquals(before, queue(db))
        assertEquals("""{"names":["Arroz","Feijão"]}""", db.commands().find(cmd("05"))!!.payload)
        // The earlier commands are unaffected and still prepare.
        assertTrue(listOf("01", "02", "03", "04").all { preparer.prepare(cmd(it)) is PreparationResult.Prepared })
    }

    @Test
    fun aLegacyReviveHasNoObservationUnlessItsDependencyStillLendsTheVersion() = runBlocking {
        val db = openDatabase()
        val sql = db.openHelper.writableDatabase
        sql.execSQL("INSERT INTO list_items (public_id, name, status, created_at, lock_version) VALUES ('$milk', 'Leite', 0, 10, 2)")
        sql.execSQL("INSERT INTO list_items (public_id, name, status, created_at, lock_version) VALUES ('$detergent', 'Detergente', 1, 11, 6)")
        fun legacy(id: String, dependsOn: String?, subject: String, name: String, type: String = "add_items") {
            val payload = if (type == "add_items") "{\"names\":[\"$name\"]}" else "{\"public_id\":\"$subject\",\"desired\":\"purchased\"}"
            val depends = dependsOn?.let { "'$it'" } ?: "NULL"
            sql.execSQL(
                "INSERT INTO commands (id, type, payload, preconditions, depends_on, observed_revision, status, created_at, attempts) " +
                    "VALUES ('$id', '$type', '$payload', '{}', $depends, 0, 'PENDING', 20, 0)",
            )
            sql.execSQL("INSERT INTO command_subjects (command_id, subject_public_id, ordinal, role) VALUES ('$id', '$subject', 0, 'targets')")
        }
        // Detergente: purchased long ago, revived with no record and no dependency. Leite: marked then revived.
        legacy("lost", null, detergent, "Detergente")
        legacy("mark", null, milk, "Leite", type = "set_item_state")
        sql.execSQL("UPDATE commands SET preconditions = '{\"lock_version\":2}' WHERE id = 'mark'")
        legacy("lent", "mark", milk, "Leite")
        val before = queue(db)
        val preparer = WirePreparer(db)

        assertEquals(PreparationRefusal.REVIVE_OBSERVATION_LOST, preparer.prepare("lost").refusal())
        assertEquals("the current version 6 is never read back as an old observation", before, queue(db))

        val lent = preparer.prepare("lent").prepared().document
        assertFalse("no version is claimed, only the dependency lends one", entries(lent).getJSONObject(0).getJSONObject("revives").has("lock_version"))
        assertEquals("mark", JSONObject(lent).getString("depends_on"))
    }

    @Test
    fun aLegacyCommandAlreadyAttemptedKeepsItsBytesUnknown() = runBlocking {
        val db = openDatabase()
        val sql = db.openHelper.writableDatabase
        sql.execSQL(
            "INSERT INTO commands (id, type, payload, preconditions, observed_revision, status, created_at, attempts, last_error_code) " +
                "VALUES ('old-1', 'add_items', '{\"names\":[\"Leite\"]}', '{}', 3, 'IN_FLIGHT', 10, 2, 'timeout')",
        )
        sql.execSQL("INSERT INTO command_subjects (command_id, subject_public_id, ordinal, role) VALUES ('old-1', '$item', 0, 'creates')")
        val before = queue(db)

        assertEquals(PreparationRefusal.LEGACY_ALREADY_ATTEMPTED, WirePreparer(db).prepare("old-1").refusal())

        assertEquals(before, queue(db))
    }

    @Test
    fun moreNamesThanTheContractTakesAreRefusedWholeAndTheThirtyOfTheTextFieldStillGo() = runBlocking {
        val db = openDatabase()
        val store = storeOn(db, ScriptedIds())
        val habitual = (1..31).map { "Habitual $it" }
        val typed = (1..30).joinToString(", ") { "Digitado $it" }
        val big = (store.execute(ListIntent.AddItems(habitual)) as IntentResult.Saved).commandId
        val text = (store.execute(ListIntent.AddText(typed)) as IntentResult.Saved).commandId
        val before = queue(db)
        val preparer = WirePreparer(db)

        assertEquals(PreparationRefusal.ENVELOPE_TOO_LARGE, preparer.prepare(big).refusal())
        assertEquals(31, org.json.JSONObject(db.commands().find(big)!!.payload).getJSONArray("names").length())
        assertEquals(before.first.map { it.copy(wireDocument = null) }, db.commands().inOrder().map { it.copy(wireDocument = null) }.also {
            assertNull(db.commands().find(big)!!.wireDocument)
        })

        assertEquals(30, entries(preparer.prepare(text).prepared().document).length())
    }

    @Test
    fun commandsTheSenderCannotSendAreNotPrepared() = runBlocking {
        val db = openDatabase()
        val store = storeOn(db, ScriptedIds(item, cmd("01"), cmd("02")))
        store.execute(ListIntent.AddItems(listOf("Leite")))
        store.execute(ListIntent.SetItemState(item, ItemStatus.PURCHASED))
        db.commands().setStatus(cmd("01"), CommandStatus.CONFLICT.name, "precondition_failed")
        db.commands().setStatus(cmd("02"), CommandStatus.BLOCKED.name, "dependency_not_applied")
        db.openHelper.writableDatabase.execSQL(
            "INSERT INTO commands (id, type, payload, preconditions, observed_revision, status, created_at) VALUES ('rm', 'remove_item', '{}', '{}', 0, 'PENDING', 1)",
        )
        val before = queue(db)
        val preparer = WirePreparer(db)

        assertEquals(PreparationRefusal.NOT_SENDABLE, preparer.prepare(cmd("01")).refusal())
        assertEquals(PreparationRefusal.NOT_SENDABLE, preparer.prepare(cmd("02")).refusal())
        assertEquals(PreparationRefusal.UNSUPPORTED_TYPE, preparer.prepare("rm").refusal())
        assertEquals(PreparationRefusal.UNKNOWN_COMMAND, preparer.prepare("missing").refusal())
        assertEquals(before, queue(db))
    }

    @Test
    fun theStoreAndTheProjectionKeepWorkingAfterPreparing() = runBlocking {
        val db = openDatabase()
        val store = storeOn(db, ScriptedIds(item, cmd("01"), cmd("02")))
        store.execute(ListIntent.AddItems(listOf("Leite")))
        WirePreparer(db).prepare(cmd("01")).prepared()

        assertTrue(store.execute(ListIntent.SetItemState(item, ItemStatus.PURCHASED)) is IntentResult.Saved)
        store.rebuildProjection()

        assertEquals(listOf("cmd-order"), listOf("cmd-order"))
        assertEquals(listOf(cmd("01"), cmd("02")), db.commands().inOrder().map { it.id })
        assertNull(db.commands().find(cmd("02"))!!.wireDocument)
        assertEquals(ItemStatusCodes.PURCHASED, db.listItems().find(item)!!.status)
    }
}
