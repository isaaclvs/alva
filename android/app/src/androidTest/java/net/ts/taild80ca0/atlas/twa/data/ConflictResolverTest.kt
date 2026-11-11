package net.ts.taild80ca0.atlas.twa.data

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeout
import net.ts.taild80ca0.atlas.twa.data.db.AlvaDatabase
import net.ts.taild80ca0.atlas.twa.data.db.CommandEntity
import net.ts.taild80ca0.atlas.twa.data.db.CommandSubjectEntity
import net.ts.taild80ca0.atlas.twa.data.db.SubjectRole
import net.ts.taild80ca0.atlas.twa.data.list.Clock
import net.ts.taild80ca0.atlas.twa.data.list.ConflictResolver
import net.ts.taild80ca0.atlas.twa.data.list.IdGenerator
import net.ts.taild80ca0.atlas.twa.data.list.ProjectionRebuilder
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

class ConflictResolverTest : DatabaseTest() {
    private fun resolver(db: AlvaDatabase) = ConflictResolver(db, IdGenerator { "decision-1" }, Clock { 123L })

    private suspend fun add(
        db: AlvaDatabase,
        id: String,
        parent: String? = null,
        attempts: Int = if (parent == null) 1 else 0,
        answered: Int = attempts,
        status: String = if (parent == null) "CONFLICT" else "BLOCKED",
        wire: String? = if (attempts > 0) "frozen-$id" else null,
        error: String = "precondition_failed",
    ) {
        db.commands().insert(CommandEntity(
            id = id, type = "add_items", payload = """{"names":["$id"]}""", preconditions = "{}",
            dependsOn = parent, observedRevision = 12, status = status, createdAt = 100,
            attempts = attempts, answeredAttempt = answered, wireDocument = wire, lastErrorCode = error,
        ))
        db.commands().insertSubjects(listOf(CommandSubjectEntity(id, "item-$id", 0, SubjectRole.CREATES)))
    }

    @Test fun discardsTransitiveGroupButKeepsIndependentAndAuditsExactIntentAfterReopen() = runBlocking {
        var db = openDatabase()
        add(db, "root")
        add(db, "child", "root")
        add(db, "grandchild", "child")
        add(db, "independent", status = "PENDING", attempts = 0)
        ProjectionRebuilder(db).rebuild()
        val before = db.commands().inOrder().take(3)
        val preview = resolver(db).preview("root")!!
        assertEquals(listOf("root", "child", "grandchild"), preview.commandIds)
        assertTrue(resolver(db).discard(preview) is ConflictResolver.Result.Discarded)
        assertEquals(listOf("independent"), db.commands().inOrder().map { it.id })
        assertEquals(listOf("independent"), db.listItems().live().map { it.name })
        db.close()
        db = openDatabase()
        val audit = db.resolutions().all().single()
        assertEquals(123L, audit.decidedAt)
        val originals = JSONArray(audit.commandsJson)
        before.forEachIndexed { index, original ->
            val saved = originals.getJSONObject(index)
            assertEquals(original.payload, saved.getString("payload"))
            assertEquals(original.preconditions, saved.getString("preconditions"))
            assertEquals(original.wireDocument, saved.opt("wire_document").takeUnless { it == org.json.JSONObject.NULL })
            assertEquals(original.sequence, saved.getLong("sequence"))
            assertEquals(original.attempts, saved.getInt("attempts"))
            assertEquals(original.answeredAttempt, saved.getInt("answered_attempt"))
            assertEquals(1, saved.getJSONArray("subjects").length())
        }
        assertNull(resolver(db).preview("root"))
    }

    @Test fun protectsUncertainLegacyAcknowledgedAndIdempotencyConflictingDescendants() = runBlocking {
        val cases = listOf("uncertain", "legacy", "acknowledged", "idempotency")
        for (kind in cases) {
            val db = openDatabase()
            val root = "root-$kind"
            add(db, root)
            add(db, "child-$kind", root, attempts = 1,
                answered = if (kind == "uncertain") 0 else 1,
                wire = if (kind == "legacy") null else "frozen",
                status = if (kind == "acknowledged") "ACKNOWLEDGED" else "REJECTED",
                error = if (kind == "idempotency") "idempotency_conflict" else "precondition_failed")
            assertNull(kind, resolver(db).preview(root))
            assertTrue(db.resolutions().all().isEmpty())
            db.close()
        }
    }

    @Test fun stalePreviewCannotDiscardAnExpandedOrModifiedGroup() = runBlocking {
        val db = openDatabase()
        add(db, "root")
        val preview = resolver(db).preview("root")!!
        add(db, "new-child", "root")
        assertEquals(ConflictResolver.Result.Stale, resolver(db).discard(preview))
        val fresh = resolver(db).preview("root")!!
        db.openHelper.writableDatabase.execSQL("UPDATE commands SET payload = '{\"names\":[\"changed\"]}' WHERE id = 'new-child'")
        assertEquals(ConflictResolver.Result.Stale, resolver(db).discard(fresh))
        assertEquals(2, db.commands().count())
        assertTrue(db.resolutions().all().isEmpty())
    }

    @Test fun aNewUnansweredAttemptAfterPreviewPreventsDiscard() = runBlocking {
        val db = openDatabase()
        add(db, "root")
        val preview = resolver(db).preview("root")!!
        db.openHelper.writableDatabase.execSQL("UPDATE commands SET attempts = attempts + 1 WHERE id = 'root'")
        assertEquals(ConflictResolver.Result.NotDiscardable, resolver(db).discard(preview))
        assertEquals(1, db.commands().count())
    }

    @Test fun projectionFailureRollsBackAuditQueueAndProjectionTogether() = runBlocking {
        val db = openDatabase()
        add(db, "root")
        add(db, "independent", attempts = 0, status = "PENDING")
        ProjectionRebuilder(db).rebuild()
        val beforeCommands = db.commands().inOrder()
        val beforeItems = db.listItems().all()
        val preview = resolver(db).preview("root")!!
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_projection BEFORE INSERT ON list_items BEGIN SELECT RAISE(ABORT, 'storage failed'); END")
        try {
            resolver(db).discard(preview)
            fail("failure must propagate and roll back")
        } catch (_: android.database.SQLException) {
            assertEquals(beforeCommands, db.commands().inOrder())
            assertEquals(beforeItems, db.listItems().all())
            assertTrue(db.resolutions().all().isEmpty())
        } finally {
            db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_projection")
        }
    }

    @Test fun observesEachGroupOnceAndEmitsItsRemovalAfterTheAtomicDiscard() = runBlocking {
        val db = openDatabase()
        add(db, "root")
        add(db, "child", "root", attempts = 1, status = "REJECTED")
        add(db, "grandchild", "child")
        add(db, "independent", attempts = 0, status = "PENDING")
        val updates = Channel<List<ConflictResolver.Preview>>(Channel.UNLIMITED)
        val observer = launch { resolver(db).observe().collect { updates.send(it) } }
        try {
            val offered = withTimeout(5_000) { updates.receive() }
            assertEquals(1, offered.size)
            assertEquals(listOf("root", "child", "grandchild"), offered.single().commandIds)
            assertTrue(resolver(db).discard(offered.single()) is ConflictResolver.Result.Discarded)
            assertTrue(withTimeout(5_000) { updates.receive() }.isEmpty())
            assertEquals(listOf("independent"), db.commands().inOrder().map { it.id })
            assertEquals(1, db.resolutions().all().size)
        } finally { observer.cancelAndJoin() }
    }

}
