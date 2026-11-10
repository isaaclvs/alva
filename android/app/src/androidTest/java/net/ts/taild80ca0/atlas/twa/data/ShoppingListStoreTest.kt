package net.ts.taild80ca0.atlas.twa.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import net.ts.taild80ca0.atlas.twa.data.db.ConfirmedItemEntity
import net.ts.taild80ca0.atlas.twa.data.db.ItemStatusCodes
import net.ts.taild80ca0.atlas.twa.data.db.SubjectRole
import net.ts.taild80ca0.atlas.twa.data.db.SyncStateEntity
import net.ts.taild80ca0.atlas.twa.data.list.CommandStatus
import net.ts.taild80ca0.atlas.twa.data.list.CommandTypes
import net.ts.taild80ca0.atlas.twa.data.list.IntentResult
import net.ts.taild80ca0.atlas.twa.data.list.InvalidReason
import net.ts.taild80ca0.atlas.twa.data.list.ItemStatus
import net.ts.taild80ca0.atlas.twa.data.list.ListIntent
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShoppingListStoreTest : DatabaseTest() {

    @Test
    fun addingItemsSavesProjectionAndCommandTogether() = runBlocking {
        val db = openDatabase()
        val store = storeOn(db, ScriptedIds("leite-id", "pao-id", "cmd-1"))

        val result = store.execute(ListIntent.AddItems(listOf("  Leite ", "Pão", "   ")))

        assertEquals(IntentResult.Saved("cmd-1", listOf("leite-id", "pao-id")), result)
        val state = store.observe().first()
        assertEquals(listOf("Leite", "Pão"), state.items.map { it.name })
        assertEquals(listOf("leite-id", "pao-id"), state.items.map { it.publicId })
        assertTrue(state.items.all { it.awaitingSync && it.status == ItemStatus.PENDING })
        assertEquals(1, state.queuedCommands)

        val command = db.commands().find("cmd-1")!!
        assertEquals(CommandTypes.ADD_ITEMS, command.type)
        assertEquals(CommandStatus.PENDING.name, command.status)
        assertNull(command.dependsOn)
        assertEquals(listOf("Leite", "Pão"), JSONObject(command.payload).getJSONArray("names").let { a ->
            List(a.length()) { a.getString(it) }
        })
        // Item ids generated on this device are kept, in the order of the names.
        assertEquals(
            listOf("leite-id" to 0, "pao-id" to 1),
            db.commands().subjectsOf("cmd-1").map { it.subjectPublicId to it.ordinal },
        )
        assertTrue(db.commands().subjectsOf("cmd-1").all { it.role == SubjectRole.CREATES })
    }

    @Test
    fun blankNamesAreRejectedWithoutWritingAnything() = runBlocking {
        val db = openDatabase()
        val store = storeOn(db)

        assertEquals(IntentResult.Invalid(InvalidReason.NO_NAMES), store.execute(ListIntent.AddItems(listOf(" ", ""))))
        assertEquals(IntentResult.Invalid(InvalidReason.NO_NAMES), store.execute(ListIntent.AddItems(emptyList())))

        assertEquals(0, db.commands().count())
        assertTrue(db.listItems().all().isEmpty())
    }

    @Test
    fun failureWhenQueuingRollsBackTheProjection() = runBlocking {
        val db = openDatabase()
        // The second action reuses the first command's UUID, so the INSERT into the queue
        // violates the unique index after the projection was already written.
        val store = storeOn(db, ScriptedIds("a-id", "cmd-1", "b-id", "cmd-1"))
        assertTrue(store.execute(ListIntent.AddItems(listOf("Arroz"))) is IntentResult.Saved)

        val result = store.execute(ListIntent.AddItems(listOf("Feijão")))

        assertTrue("expected Failed, got $result", result is IntentResult.Failed)
        val state = store.observe().first()
        assertEquals("the failed action must leave no trace", listOf("Arroz"), state.items.map { it.name })
        assertEquals(1, state.queuedCommands)
        assertNull(db.listItems().find("b-id"))
    }

    @Test
    fun failureInsideTheQueueTableRollsBackTheProjection() = runBlocking {
        val db = openDatabase()
        val store = storeOn(db)
        assertTrue(store.execute(ListIntent.AddItems(listOf("Arroz"))) is IntentResult.Saved)
        val arrozId = db.listItems().all().single().publicId

        // A trigger makes the database itself refuse the command, whatever the code does.
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER refuse_commands BEFORE INSERT ON commands " +
                "BEGIN SELECT RAISE(ABORT, 'disk refused the command'); END",
        )

        val add = store.execute(ListIntent.AddItems(listOf("Feijão")))
        val mark = store.execute(ListIntent.SetItemState(arrozId, ItemStatus.PURCHASED))

        assertTrue(add is IntentResult.Failed)
        assertTrue(mark is IntentResult.Failed)
        val state = store.observe().first()
        assertEquals(listOf("Arroz"), state.items.map { it.name })
        assertEquals("the mark must be undone with the failed command", ItemStatus.PENDING, state.items.single().status)
        assertNull(state.items.single().purchasedAt)
        assertEquals(1, state.queuedCommands)
    }

    @Test
    fun settingStateUpdatesProjectionAndQueuesDesiredStateWithConfirmedLockVersion() = runBlocking {
        val db = openDatabase()
        db.syncState().put(SyncStateEntity(householdPublicId = "house-1", confirmedRevision = 840))
        db.confirmedItems().upsert(listOf(confirmed("leite-id", "Leite", lockVersion = 7)))
        val store = storeOn(db, ScriptedIds("cmd-1"), TickingClock(5_000))
        store.rebuildProjection()

        val result = store.execute(ListIntent.SetItemState("leite-id", ItemStatus.PURCHASED))

        assertEquals(IntentResult.Saved("cmd-1", listOf("leite-id")), result)
        val item = store.observe().first().items.single()
        assertEquals(ItemStatus.PURCHASED, item.status)
        assertEquals(5_000L, item.purchasedAt)
        assertTrue(item.awaitingSync)
        val command = db.commands().find("cmd-1")!!
        assertEquals(CommandTypes.SET_ITEM_STATE, command.type)
        assertEquals(840L, command.observedRevision)
        assertEquals(7, JSONObject(command.preconditions).getInt("lock_version"))
        JSONObject(command.payload).let {
            assertEquals("leite-id", it.getString("public_id"))
            assertEquals("purchased", it.getString("desired"))
        }
    }

    @Test
    fun alreadyInDesiredStateQueuesNothingAndUnknownItemIsInvalid() = runBlocking {
        val db = openDatabase()
        db.confirmedItems().upsert(listOf(confirmed("leite-id", "Leite", lockVersion = 1)))
        val store = storeOn(db)
        store.rebuildProjection()

        assertEquals(IntentResult.Unchanged, store.execute(ListIntent.SetItemState("leite-id", ItemStatus.PENDING)))
        assertEquals(
            IntentResult.Invalid(InvalidReason.ITEM_NOT_FOUND),
            store.execute(ListIntent.SetItemState("nope", ItemStatus.PURCHASED)),
        )
        assertEquals(0, db.commands().count())

        // Repeating the same desired state never queues a second command.
        assertTrue(store.execute(ListIntent.SetItemState("leite-id", ItemStatus.PURCHASED)) is IntentResult.Saved)
        assertEquals(IntentResult.Unchanged, store.execute(ListIntent.SetItemState("leite-id", ItemStatus.PURCHASED)))
        assertEquals(1, db.commands().count())
    }

    @Test
    fun commandsDependOnTheLatestCommandOnTheSameItemOnly() = runBlocking {
        val db = openDatabase()
        val store = storeOn(db, ScriptedIds("a", "b", "add", "c-other"))
        store.execute(ListIntent.AddItems(listOf("Leite", "Pão")))
        store.execute(ListIntent.AddItems(listOf("Café")))

        val mark = store.execute(ListIntent.SetItemState("a", ItemStatus.PURCHASED)) as IntentResult.Saved
        val unmark = store.execute(ListIntent.SetItemState("a", ItemStatus.PENDING)) as IntentResult.Saved
        val other = store.execute(ListIntent.SetItemState("b", ItemStatus.PURCHASED)) as IntentResult.Saved

        val queue = db.commands().inOrder()
        assertEquals(5, queue.size)
        assertNull(queue[0].dependsOn)
        assertNull(queue[1].dependsOn)
        assertEquals("marking depends on the add that created the item", queue[0].id, queue[2].dependsOn)
        assertEquals("unmarking depends on the marking", mark.commandId, queue[3].dependsOn)
        assertEquals(unmark.commandId, queue[3].id)
        assertEquals("another item of the same add depends on that add", queue[0].id, queue[4].dependsOn)
        assertEquals(other.commandId, queue[4].id)
    }

    @Test
    fun commandsBehindAConflictAreBlockedWhileIndependentOnesStayPending() = runBlocking {
        val db = openDatabase()
        val store = storeOn(db)
        val add = store.execute(ListIntent.AddItems(listOf("Leite", "Pão"))) as IntentResult.Saved
        val leite = add.itemIds.first()
        db.commands().setStatus(add.commandId, CommandStatus.CONFLICT.name, "precondition_failed")

        val dependent = store.execute(ListIntent.SetItemState(leite, ItemStatus.PURCHASED)) as IntentResult.Saved
        val independent = store.execute(ListIntent.AddItems(listOf("Café"))) as IntentResult.Saved

        // Both Leite and Pão come from the conflicting add, so touching either is blocked...
        assertEquals(CommandStatus.BLOCKED.name, db.commands().find(dependent.commandId)!!.status)
        assertEquals(add.commandId, db.commands().find(dependent.commandId)!!.dependsOn)
        // ...but a new, unrelated add is not.
        assertEquals(CommandStatus.PENDING.name, db.commands().find(independent.commandId)!!.status)
        assertNull(db.commands().find(independent.commandId)!!.dependsOn)
        val state = store.observe().first()
        assertEquals("conflict + blocked need the user", 2, state.needsAttention)
        assertEquals("the intent stays visible locally", 3, state.items.size)
    }

    @Test
    fun queueKeepsDeviceOrderAndSequencesNeverRepeat() = runBlocking {
        val db = openDatabase()
        val store = storeOn(db)
        val ids = (1..25).map { n ->
            (store.execute(ListIntent.AddItems(listOf("item $n"))) as IntentResult.Saved).commandId
        }

        val queue = db.commands().inOrder()
        assertEquals(ids, queue.map { it.id })
        assertEquals(queue.map { it.sequence }.sorted(), queue.map { it.sequence })
        assertEquals(25, queue.map { it.sequence }.toSet().size)

        // Confirming (deleting) the newest command must not let its sequence be reused.
        val lastSequence = queue.last().sequence
        db.commands().delete(queue.last().id)
        val next = store.execute(ListIntent.AddItems(listOf("depois"))) as IntentResult.Saved
        assertTrue(db.commands().find(next.commandId)!!.sequence > lastSequence)
    }

    @Test
    fun concurrentActionsAreAllSavedWithDistinctOrderedSequences() = runBlocking {
        val db = openDatabase()
        val store = storeOn(db)

        val results = (1..40).map { n ->
            async(Dispatchers.Default) { store.execute(ListIntent.AddItems(listOf("item $n"))) }
        }.awaitAll()

        assertTrue(results.all { it is IntentResult.Saved })
        val queue = db.commands().inOrder()
        assertEquals(40, queue.size)
        assertEquals(40, queue.map { it.sequence }.toSet().size)
        assertEquals(40, store.observe().first().items.size)
    }

    @Test
    fun observeEmitsAConsistentStateAfterEachChange() = runBlocking {
        val db = openDatabase()
        val store = storeOn(db)
        val seen = mutableListOf<Pair<Int, Int>>() // items, queued commands

        val initial = store.observe().first()
        seen += initial.items.size to initial.queuedCommands
        store.execute(ListIntent.AddItems(listOf("Leite")))
        val afterAdd = withTimeout(5_000) { store.observe().first { it.items.size == 1 } }
        seen += afterAdd.items.size to afterAdd.queuedCommands

        assertEquals(listOf(0 to 0, 1 to 1), seen)
        assertTrue(afterAdd.items.single().awaitingSync)
    }

    @Test
    fun rebuildKeepsLocalActionsOverANewerConfirmedState() = runBlocking {
        val db = openDatabase()
        db.confirmedItems().upsert(
            listOf(confirmed("leite-id", "Leite", lockVersion = 1), confirmed("pao-id", "Pão", lockVersion = 1)),
        )
        val store = storeOn(db)
        store.rebuildProjection()
        store.execute(ListIntent.SetItemState("leite-id", ItemStatus.PURCHASED))
        store.execute(ListIntent.AddItems(listOf("Café")))

        // A newer confirmed state arrives: Pão was bought elsewhere, Leite was renamed, a new item exists.
        db.confirmedItems().upsert(
            listOf(
                confirmed("leite-id", "Leite integral", lockVersion = 2),
                confirmed("pao-id", "Pão", lockVersion = 2, status = ItemStatusCodes.PURCHASED, purchasedAt = 77),
                confirmed("ovos-id", "Ovos", lockVersion = 0),
            ),
        )
        store.rebuildProjection()

        val items = store.observe().first().items.associateBy { it.name }
        assertEquals("the queued purchase still shows", ItemStatus.PURCHASED, items.getValue("Leite integral").status)
        assertEquals(ItemStatus.PURCHASED, items.getValue("Pão").status)
        assertEquals(ItemStatus.PENDING, items.getValue("Ovos").status)
        assertTrue("the queued add is not erased by the remote read", "Café" in items)
        assertFalse(items.getValue("Ovos").awaitingSync)
        assertEquals(2, db.commands().count())
    }

    private fun confirmed(
        id: String,
        name: String,
        lockVersion: Int,
        status: Int = ItemStatusCodes.PENDING,
        purchasedAt: Long? = null,
    ) = ConfirmedItemEntity(id, name, null, status, null, purchasedAt, createdAt = 100, lockVersion = lockVersion, tripPublicId = null)
}
