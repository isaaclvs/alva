package net.ts.taild80ca0.atlas.twa.data

import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import net.ts.taild80ca0.atlas.twa.data.db.AlvaDatabase
import net.ts.taild80ca0.atlas.twa.data.list.AttemptResult
import net.ts.taild80ca0.atlas.twa.data.list.CommandStatus
import net.ts.taild80ca0.atlas.twa.data.list.FreezeResult
import net.ts.taild80ca0.atlas.twa.data.list.IntentResult
import net.ts.taild80ca0.atlas.twa.data.list.ItemStatus
import net.ts.taild80ca0.atlas.twa.data.list.ListIntent
import net.ts.taild80ca0.atlas.twa.data.list.WireOutbox
import net.ts.taild80ca0.atlas.twa.data.list.WireRefusal
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The frozen wire document (issue #45, first cut): one command's request is prepared once,
 * kept byte for byte on disk, repeated on every retry, and every attempt is counted before
 * any future network call. No test here touches the network; there is none.
 */
class WireOutboxTest : DatabaseTest() {

    private val itemUuid = "9a1d0000-0000-4000-8000-000000000001"

    /** A wire add_items document with deliberately awkward bytes: key order, spacing, non-ASCII, escapes. */
    private fun addWire(
        id: String = "cmd-1",
        name: String = "Pão de açúcar \\u00e7 \\\"quente\\\"",
        dependsOn: String? = null,
    ): String =
        "{ \"payload\" : {\"items\":[ {\"name\":\"$name\" , \"public_id\":\"$itemUuid\"} ]},\n" +
            "  \"id\":\"$id\",\"depends_on\":${dependsOn?.let { "\"$it\"" } ?: "null"},\"type\":\"add_items\",\"preconditions\":{} }"

    private fun setStateWire(id: String = "cmd-2", dependsOn: String = "cmd-1", desired: String = "purchased") =
        """{"id":"$id","type":"set_item_state","depends_on":"$dependsOn","preconditions":{},"payload":{"public_id":"$itemUuid","desired":"$desired"}}"""

    /** Queues add (cmd-1) and then mark purchased (cmd-2) through the real store. */
    private suspend fun queueAddAndMark(db: AlvaDatabase) {
        val store = storeOn(db, ScriptedIds(itemUuid, "cmd-1", "cmd-2"))
        assertTrue(store.execute(ListIntent.AddItems(listOf("Leite"))) is IntentResult.Saved)
        assertTrue(store.execute(ListIntent.SetItemState(itemUuid, ItemStatus.PURCHASED)) is IntentResult.Saved)
    }

    @Test
    fun aFrozenDocumentSurvivesReopeningAndEveryRetryRepeatsItByteForByte() = runBlocking {
        val candidate = addWire()
        val first = openDatabase()
        queueAddAndMark(first)
        val frozen = WireOutbox(first).freeze("cmd-1", candidate)
        assertEquals(FreezeResult.Frozen("cmd-1", candidate, created = true), frozen)
        first.close()

        val reopened = openDatabase()
        val outbox = WireOutbox(reopened)
        val one = outbox.beginAttempt("cmd-1") as AttemptResult.Started
        val two = outbox.beginAttempt("cmd-1") as AttemptResult.Started
        val three = outbox.beginAttempt("cmd-1") as AttemptResult.Started

        for (started in listOf(one, two, three)) {
            assertArrayEquals(candidate.toByteArray(Charsets.UTF_8), started.document.toByteArray(Charsets.UTF_8))
        }
        assertEquals(listOf(1, 2, 3), listOf(one, two, three).map { it.attempt })
        val row = reopened.commands().find("cmd-1")!!
        assertEquals(3, row.attempts)
        assertEquals(CommandStatus.IN_FLIGHT.name, row.status)
        assertEquals(candidate, row.wireDocument)
        // The decoded document keeps what the contract needs: UUID, dependency, payload, preconditions.
        val decoded = JSONObject(one.document)
        assertEquals("cmd-1", decoded.getString("id"))
        assertTrue(decoded.isNull("depends_on"))
        assertEquals(itemUuid, decoded.getJSONObject("payload").getJSONArray("items").getJSONObject(0).getString("public_id"))
        // The dependent command has nothing frozen: freezing one does not freeze the queue.
        assertNull(outbox.frozenDocument("cmd-2"))
    }

    @Test
    fun aLaterCandidateOrAChangedProjectionNeverRewritesTheFrozenDocument() = runBlocking {
        val db = openDatabase()
        queueAddAndMark(db)
        val outbox = WireOutbox(db)
        val original = addWire()
        outbox.freeze("cmd-1", original)
        val intentBefore = db.commands().find("cmd-1")!!

        // The local intent moves on: another action, a rebuilt projection, an edited intent JSON.
        val store = storeOn(db, ScriptedIds("cmd-3"))
        assertTrue(store.execute(ListIntent.SetItemState(itemUuid, ItemStatus.PENDING)) is IntentResult.Saved)
        store.rebuildProjection()
        db.openHelper.writableDatabase.execSQL("UPDATE commands SET payload = '{\"names\":[\"Outro\"]}' WHERE id = 'cmd-1'")

        // A different (valid) candidate, an invalid one, and a malformed one all get the original back.
        val different = addWire(name = "Outro nome")
        assertEquals(FreezeResult.Frozen("cmd-1", original, created = false), outbox.freeze("cmd-1", different))
        assertEquals(FreezeResult.Frozen("cmd-1", original, created = false), outbox.freeze("cmd-1", addWire(id = "nope")))
        assertEquals(FreezeResult.Frozen("cmd-1", original, created = false), outbox.freeze("cmd-1", "garbage"))

        val started = outbox.beginAttempt("cmd-1") as AttemptResult.Started
        assertEquals(original, started.document)
        assertEquals(original, db.commands().find("cmd-1")!!.wireDocument)
        // The intent column is independent of the frozen request.
        assertEquals("{\"names\":[\"Outro\"]}", db.commands().find("cmd-1")!!.payload)
        assertEquals(intentBefore.copy(payload = "{\"names\":[\"Outro\"]}", attempts = 1, status = CommandStatus.IN_FLIGHT.name), db.commands().find("cmd-1")!!)
        // The visible list is unaffected by outbox calls.
        assertEquals(listOf("Leite"), store.observe().first().items.map { it.name })
    }

    @Test
    fun aCandidateThatIsNotTheCommandIsRefusedAndNothingIsStored() = runBlocking {
        val db = openDatabase()
        queueAddAndMark(db)
        val outbox = WireOutbox(db)
        val before = db.commands().inOrder()

        fun refusedReason(result: FreezeResult) = (result as FreezeResult.Refused).reason

        assertEquals(WireRefusal.INVALID_DOCUMENT, refusedReason(outbox.freeze("cmd-1", addWire(id = "cmd-2"))))
        assertEquals(WireRefusal.INVALID_DOCUMENT, refusedReason(outbox.freeze("cmd-1", setStateWire("cmd-1", "cmd-0"))))
        assertEquals(WireRefusal.INVALID_DOCUMENT, refusedReason(outbox.freeze("cmd-1", addWire(dependsOn = "cmd-0"))))
        assertEquals(WireRefusal.INVALID_DOCUMENT, refusedReason(outbox.freeze("cmd-2", setStateWire(dependsOn = "other"))))
        assertEquals(WireRefusal.INVALID_DOCUMENT, refusedReason(outbox.freeze("cmd-1", "{}")))
        assertEquals(
            "the legacy local form must not be frozen",
            WireRefusal.INVALID_DOCUMENT,
            refusedReason(outbox.freeze("cmd-1", """{"id":"cmd-1","type":"add_items","depends_on":null,"preconditions":{},"payload":{"names":["Leite"]}}""")),
        )
        assertEquals(WireRefusal.UNKNOWN_COMMAND, refusedReason(outbox.freeze("ghost", addWire(id = "ghost"))))
        assertEquals(WireRefusal.UNKNOWN_COMMAND, (outbox.beginAttempt("ghost") as AttemptResult.Refused).reason)

        assertEquals(before, db.commands().inOrder())
        // The right document for the dependent command is accepted.
        assertTrue((outbox.freeze("cmd-2", setStateWire()) as FreezeResult.Frozen).created)
    }

    @Test
    fun anAttemptNeedsAFrozenDocumentAndASendableStatus() = runBlocking {
        val db = openDatabase()
        queueAddAndMark(db)
        val outbox = WireOutbox(db)

        // Nothing frozen: refused without counting an attempt.
        assertEquals(WireRefusal.NOT_FROZEN, (outbox.beginAttempt("cmd-1") as AttemptResult.Refused).reason)
        assertEquals(0, db.commands().find("cmd-1")!!.attempts)
        assertEquals(CommandStatus.PENDING.name, db.commands().find("cmd-1")!!.status)

        // Frozen but blocked, in conflict or rejected: refused, intent and counter untouched.
        outbox.freeze("cmd-2", setStateWire())
        for (status in listOf(CommandStatus.BLOCKED, CommandStatus.CONFLICT, CommandStatus.REJECTED)) {
            db.commands().setStatus("cmd-2", status.name, "precondition_failed")
            assertEquals(WireRefusal.NOT_SENDABLE, (outbox.beginAttempt("cmd-2") as AttemptResult.Refused).reason)
            val row = db.commands().find("cmd-2")!!
            assertEquals(0, row.attempts)
            assertEquals(status.name, row.status)
            assertEquals("precondition_failed", row.lastErrorCode)
        }
    }

    @Test
    fun aLegacyCommandAlreadyAttemptedIsRefusedWithoutTouchingTheQueueOrTheIntent() = runBlocking {
        val db = openDatabase()
        val sql = db.openHelper.writableDatabase
        sql.execSQL("INSERT INTO list_items (public_id, name, status, created_at) VALUES ('$itemUuid', 'Leite', 0, 10)")
        sql.execSQL(
            "INSERT INTO commands (id, type, payload, preconditions, observed_revision, status, created_at, attempts, last_error_code) " +
                "VALUES ('old-1', 'add_items', '{\"names\":[\"Leite\"]}', '{}', 3, 'IN_FLIGHT', 10, 2, 'timeout')",
        )
        sql.execSQL(
            "INSERT INTO commands (id, type, payload, preconditions, observed_revision, status, created_at, attempts) " +
                "VALUES ('old-0', 'set_item_state', '{\"public_id\":\"$itemUuid\",\"desired\":\"purchased\"}', '{}', 3, 'PENDING', 11, 0)",
        )
        sql.execSQL("INSERT INTO command_subjects (command_id, subject_public_id, ordinal, role) VALUES ('old-1', '$itemUuid', 0, 'creates')")
        val outbox = WireOutbox(db)
        val queueBefore = db.commands().inOrder()
        val subjectsBefore = db.commands().subjectsOf("old-1")
        val projectionBefore = db.listItems().all()

        val legacyWire = addWire(id = "old-1")
        val frozen = outbox.freeze("old-1", legacyWire) as FreezeResult.Refused
        val attempt = outbox.beginAttempt("old-1") as AttemptResult.Refused
        assertEquals(WireRefusal.LEGACY_ALREADY_ATTEMPTED, frozen.reason)
        assertEquals(WireRefusal.LEGACY_ALREADY_ATTEMPTED, attempt.reason)
        assertTrue(frozen.detail.contains("old-1") && frozen.detail.contains("2"))

        // The not-yet-attempted legacy row stays available: no document, no attempt, nothing sent.
        assertEquals(WireRefusal.NOT_FROZEN, (outbox.beginAttempt("old-0") as AttemptResult.Refused).reason)

        assertEquals(queueBefore, db.commands().inOrder())
        assertEquals(subjectsBefore, db.commands().subjectsOf("old-1"))
        assertEquals(projectionBefore, db.listItems().all())
        assertNull(db.commands().find("old-1")!!.wireDocument)
        // The guard is in SQL too, not only in the checks above.
        val unguarded = db.commands().freezeWireDocument("old-1", legacyWire)
        assertEquals(0, unguarded)
        assertEquals(queueBefore, db.commands().inOrder())
    }

    @Test
    fun aFailingSqlStatementRollsBackTheAttemptAndTheFreeze() = runBlocking {
        val db = openDatabase()
        queueAddAndMark(db)
        val outbox = WireOutbox(db)
        val sql = db.openHelper.writableDatabase
        val queueBefore = db.commands().inOrder()

        // The database itself refuses to store the document.
        sql.execSQL(
            "CREATE TRIGGER refuse_freeze BEFORE UPDATE OF wire_document ON commands " +
                "BEGIN SELECT RAISE(ABORT, 'no documents today'); END",
        )
        try {
            outbox.freeze("cmd-1", addWire())
            fail("a failing statement must surface, not look like a refusal")
        } catch (expected: Exception) {
            assertTrue(expected.message.orEmpty().contains("no documents today"))
        }
        assertEquals(queueBefore, db.commands().inOrder())
        sql.execSQL("DROP TRIGGER refuse_freeze")

        outbox.freeze("cmd-1", addWire())
        val frozenRow = db.commands().find("cmd-1")!!
        sql.execSQL("CREATE TRIGGER refuse_attempt BEFORE UPDATE OF attempts ON commands BEGIN SELECT RAISE(ABORT, 'no attempts today'); END")
        try {
            outbox.beginAttempt("cmd-1")
            fail("a failing statement must surface")
        } catch (expected: Exception) {
            assertTrue(expected.message.orEmpty().contains("no attempts today"))
        }
        assertEquals(frozenRow, db.commands().find("cmd-1")!!)
        sql.execSQL("DROP TRIGGER refuse_attempt")

        // Joined to a larger transaction: when the caller fails afterwards, the attempt is undone too.
        try {
            db.withTransaction {
                assertTrue(outbox.beginAttempt("cmd-1") is AttemptResult.Started)
                assertEquals(1, db.commands().find("cmd-1")!!.attempts)
                error("the send step failed before it could count")
            }
            fail()
        } catch (expected: IllegalStateException) {
            // expected
        }
        assertEquals(frozenRow, db.commands().find("cmd-1")!!)
        assertEquals(0, db.commands().find("cmd-1")!!.attempts)
        assertEquals(CommandStatus.PENDING.name, db.commands().find("cmd-1")!!.status)
    }

    @Test
    fun twoConsumersFreezeOnceAndAttemptEachOnceInOrder() = runBlocking {
        val db = openDatabase()
        queueAddAndMark(db)
        val outbox = WireOutbox(db)
        val other = WireOutbox(db) // a second consumer of the same store

        val candidates = (1..16).map { addWire(name = "Nome $it") }
        val results = candidates.mapIndexed { index, candidate ->
            async(Dispatchers.Default) { (if (index % 2 == 0) outbox else other).freeze("cmd-1", candidate) }
        }.awaitAll()

        val frozen = results.map { it as FreezeResult.Frozen }
        assertEquals("exactly one candidate wins", 1, frozen.count { it.created })
        assertEquals(1, frozen.map { it.document }.toSet().size)
        val winner = frozen.first { it.created }.document
        assertTrue(winner in candidates)
        assertEquals(winner, db.commands().find("cmd-1")!!.wireDocument)

        val attempts = (1..20).map { index ->
            async(Dispatchers.Default) { (if (index % 2 == 0) outbox else other).beginAttempt("cmd-1") }
        }.awaitAll().map { it as AttemptResult.Started }
        assertEquals((1..20).toList(), attempts.map { it.attempt }.sorted())
        assertEquals(setOf(winner), attempts.map { it.document }.toSet())
        assertEquals(20, db.commands().find("cmd-1")!!.attempts)
        assertFalse(db.commands().find("cmd-1")!!.wireDocument.isNullOrEmpty())
    }

    @Test
    fun theStoreKeepsWorkingOnADatabaseWithFrozenDocuments() = runBlocking {
        val db = openDatabase()
        queueAddAndMark(db)
        val outbox = WireOutbox(db)
        outbox.freeze("cmd-1", addWire())
        outbox.beginAttempt("cmd-1")

        val store = storeOn(db, ScriptedIds("pao-id", "cmd-3"))
        assertTrue(store.execute(ListIntent.AddItems(listOf("Pão"))) is IntentResult.Saved)
        store.rebuildProjection()
        val state = store.observe().first()
        assertEquals(listOf("Leite", "Pão"), state.items.map { it.name })
        assertEquals(ItemStatus.PURCHASED, state.items.first().status)
        assertEquals(3, state.queuedCommands)
        assertEquals(0, state.needsAttention)
        assertEquals(listOf("cmd-1", "cmd-2", "cmd-3"), db.commands().inOrder().map { it.id })
        assertNull(db.commands().find("cmd-3")!!.wireDocument)
    }
}
