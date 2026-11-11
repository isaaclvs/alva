package net.ts.taild80ca0.atlas.twa.sync

import android.database.SQLException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import net.ts.taild80ca0.atlas.twa.data.DatabaseTest
import net.ts.taild80ca0.atlas.twa.data.ScriptedIds
import net.ts.taild80ca0.atlas.twa.data.db.AlvaDatabase
import net.ts.taild80ca0.atlas.twa.data.db.CommandEntity
import net.ts.taild80ca0.atlas.twa.data.db.ConfirmedItemEntity
import net.ts.taild80ca0.atlas.twa.data.db.ItemStatusCodes
import net.ts.taild80ca0.atlas.twa.data.db.SyncAliasEntity
import net.ts.taild80ca0.atlas.twa.data.db.SyncStateEntity
import net.ts.taild80ca0.atlas.twa.data.list.AttemptResult
import net.ts.taild80ca0.atlas.twa.data.list.CommandStatus
import net.ts.taild80ca0.atlas.twa.data.list.IdGenerator
import net.ts.taild80ca0.atlas.twa.data.list.IntentResult
import net.ts.taild80ca0.atlas.twa.data.list.ItemStatus
import net.ts.taild80ca0.atlas.twa.data.list.ListIntent
import net.ts.taild80ca0.atlas.twa.data.list.PreparationResult
import net.ts.taild80ca0.atlas.twa.data.list.ReceiptRefusal
import net.ts.taild80ca0.atlas.twa.data.list.RoomShoppingListStore
import net.ts.taild80ca0.atlas.twa.data.list.WireOutbox
import net.ts.taild80ca0.atlas.twa.data.list.WirePreparer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Applying receipts and aliases (issue #45, fourth cut), on a real Room file and the real store. There is
 * no server: each receipt is written by hand in the shape Rails produces (the Rails harness checks the
 * shapes against the real thing), so every failure the server can cause can be provoked exactly.
 *
 * What matters here is what is *not* done: a receipt never moves `confirmed_revision`, never writes the
 * confirmed items and never invents what it does not carry; invalid answers change nothing at all.
 */
class ReceiptApplierTest : DatabaseTest() {

    private val local = "9a1d0000-0000-4000-8000-000000000001" // an item this device made up
    private val kept = "9a1d0000-0000-4000-8000-0000000000aa" // the item the server kept for the same name
    private val purchase = "40000000-0000-4000-8000-000000000002"
    private val other = "9a1d0000-0000-4000-8000-0000000000bb"
    private fun cmd(suffix: String) = "5f0c2f0e-0000-4000-8000-0000000000$suffix"
    private fun claim(suffix: String) = "9a1d0000-0000-4000-8000-0000000000$suffix"
    private val bought = "2026-11-10T12:00:00.000000Z"

    // ---- receipts, as Rails writes them ---------------------------------------------------

    private fun entry(
        cited: String,
        canonical: String = cited,
        name: String = "Leite",
        status: String = "pending",
        at: String? = null,
        lock: Int = 0,
        outcome: String? = "created",
    ) = """{"public_id":"$cited","canonical_public_id":"$canonical","name":"$name","status":"$status",""" +
        """"purchased_at":${at?.let { "\"$it\"" } ?: "null"},"lock_version":$lock${outcome?.let { ""","outcome":"$it"""" } ?: ""}}"""

    private fun items(vararg entries: String) = """{"items":[${entries.joinToString(",")}]}"""

    private fun itemData(entry: String) = """{"item":$entry}"""

    private fun consumed(
        commandId: String,
        data: String,
        status: ReceiptStatus = ReceiptStatus.APPLIED,
        revision: Long = 841,
        house: Long = revision,
        original: ReceiptStatus? = if (status == ReceiptStatus.DUPLICATE) ReceiptStatus.APPLIED else null,
    ) = CommandReceipt(commandId, status, original, revision, null, data, house, snapshotRequired = false)

    private fun refusal(commandId: String, status: ReceiptStatus, error: String, snapshotRequired: Boolean = false) =
        CommandReceipt(commandId, status, null, null, error, """{}""", 900, snapshotRequired)

    // ---- the queue, through the real store -------------------------------------------------

    private fun applier(db: AlvaDatabase) = ReceiptApplier(db)

    private suspend fun prepareAndBegin(db: AlvaDatabase, commandId: String, claims: IdGenerator = IdGenerator { claim("c9") }): Int {
        val prepared = WirePreparer(db, claimIds = claims).prepare(commandId)
        assertTrue("$commandId: $prepared", prepared is PreparationResult.Prepared)
        return (WireOutbox(db).beginAttempt(commandId) as AttemptResult.Started).attempt
    }

    private suspend fun statusOf(db: AlvaDatabase, id: String) = db.commands().find(id)?.status

    private suspend fun seedConfirmed(db: AlvaDatabase, revision: Long, vararg items: ConfirmedItemEntity) {
        db.syncState().put(SyncStateEntity(householdPublicId = "house-1", confirmedRevision = revision))
        db.confirmedItems().upsert(items.toList())
        RoomShoppingListStore(db).rebuildProjection()
    }

    private fun confirmed(id: String, name: String, lock: Int, purchased: Boolean = false) = ConfirmedItemEntity(
        id, name, "cat-1", if (purchased) ItemStatusCodes.PURCHASED else ItemStatusCodes.PENDING, null,
        if (purchased) 50L else null, createdAt = 100, lockVersion = lock, tripPublicId = null,
    )

    /** Everything a receipt could touch, to prove that a refusal changes none of it. */
    private data class World(
        val commands: List<CommandEntity>,
        val receipts: List<Any>,
        val aliases: List<SyncAliasEntity>,
        val confirmed: List<ConfirmedItemEntity>,
        val projection: List<Any>,
        val state: SyncStateEntity?,
    )

    private suspend fun world(db: AlvaDatabase) = World(
        db.commands().inOrder(), db.receipts().all(), db.aliases().all(), db.confirmedItems().all(), db.listItems().all(), db.syncState().get(),
    )

    private fun ApplyResult.refusedFor(): ReceiptRefusal = (this as ApplyResult.Refused).reason

    // ---- consumed answers ------------------------------------------------------------------

    @Test
    fun aCreatedItemIsRecognizedOnceAndKeptWithItsReceiptWithoutPromotingAnything() = runBlocking {
        val db = openDatabase()
        val store = storeOn(db, ScriptedIds(local, cmd("a1")))
        assertTrue(store.execute(ListIntent.AddItems(listOf("Leite"))) is IntentResult.Saved)
        val attempt = prepareAndBegin(db, cmd("a1"))
        val frozen = db.commands().find(cmd("a1"))!!.wireDocument

        val result = applier(db).apply(cmd("a1"), attempt, consumed(cmd("a1"), items(entry(local))))

        assertEquals(ApplyResult.Acknowledged(cmd("a1"), emptyList(), retained = true, snapshotRequired = false), result)
        // Recognized, not deleted: the receipt is the only record of what the server did until a snapshot says it.
        val command = db.commands().find(cmd("a1"))!!
        assertEquals(CommandStatus.ACKNOWLEDGED.name, command.status)
        assertEquals(frozen, command.wireDocument)
        val receipt = db.receipts().find(cmd("a1"))!!
        assertEquals("applied", receipt.outcome)
        assertEquals(841L, receipt.revision)
        // The confirmed state is exactly what it was: a receipt has no category, creation time or normalized name.
        assertTrue(db.confirmedItems().all().isEmpty())
        assertNull(db.syncState().get())
        // The screen shows the item once, with the server's version, and it no longer waits for the server.
        val shown = store.observe().first()
        assertEquals(listOf("Leite"), shown.items.map { it.name })
        assertFalse(shown.items.single().awaitingSync)
        assertEquals(0, shown.queuedCommands)
        assertEquals(0, db.listItems().find(local)!!.lockVersion)

        // The same answer again: no second effect.
        val before = world(db)
        assertEquals(ApplyResult.AlreadyAcknowledged(cmd("a1")), applier(db).apply(cmd("a1"), attempt, consumed(cmd("a1"), items(entry(local)))))
        assertEquals(before, world(db))
        // And the sender will not pick it up again.
        assertTrue(WireOutbox(db).beginAttempt(cmd("a1")) is AttemptResult.Refused)
    }

    @Test
    fun aDuplicateRecordsTheAliasAndTheDependentsKeepTheirBytesAndFindTheKeptItem() = runBlocking {
        val db = openDatabase()
        val store = storeOn(db, ScriptedIds(local, cmd("a1"), cmd("a2")))
        store.execute(ListIntent.AddItems(listOf("Leite")))
        store.execute(ListIntent.SetItemState(local, ItemStatus.PURCHASED))
        val first = prepareAndBegin(db, cmd("a1"))
        prepareAndBegin(db, cmd("a2"))
        val dependent = db.commands().find(cmd("a2"))!!

        // Another phone had "Leite": the server kept its item and made ours an alias.
        val noop = consumed(cmd("a1"), items(entry(local, kept, outcome = "duplicate", lock = 4)), ReceiptStatus.NOOP)
        val result = applier(db).apply(cmd("a1"), first, noop) as ApplyResult.Acknowledged

        assertEquals(listOf(net.ts.taild80ca0.atlas.twa.data.list.AliasClaim(local, kept)), result.aliases)
        assertEquals(kept, db.aliases().find(local)!!.canonicalPublicId)
        // The dependent was prepared and attempted before: nothing about it moved, not a byte.
        assertEquals(dependent, db.commands().find(cmd("a2")))
        assertEquals(local, org.json.JSONObject(db.commands().find(cmd("a2"))!!.payload).getString("public_id"))
        // The list has the kept item, once, and the queued mark acts on it (the server will do the same through the alias).
        val rows = db.listItems().all()
        assertEquals(listOf(kept), rows.map { it.publicId })
        assertEquals(ItemStatusCodes.PURCHASED, rows.single().status)
        assertEquals(4, rows.single().lockVersion)
        assertTrue(store.observe().first().items.single().awaitingSync)

        // The mark's answer cites the alias; it resolves to the same item and records nothing new.
        val mark = consumed(cmd("a2"), itemData(entry(local, kept, status = "purchased", at = bought, lock = 5, outcome = null)), revision = 842)
        assertTrue(applier(db).apply(cmd("a2"), 1, mark) is ApplyResult.Acknowledged)
        assertEquals(1, db.aliases().all().size)
        assertEquals(5, db.listItems().find(kept)!!.lockVersion)
        assertEquals(listOf(kept), db.listItems().all().map { it.publicId })
    }

    @Test
    fun aMarkAnswerBeforeTheReceiptThatRevealsTheAliasChangesNothing() = runBlocking {
        val db = openDatabase()
        val store = storeOn(db, ScriptedIds(local, cmd("a1"), cmd("a2")))
        store.execute(ListIntent.AddItems(listOf("Leite")))
        store.execute(ListIntent.SetItemState(local, ItemStatus.PURCHASED))
        prepareAndBegin(db, cmd("a1"))
        val attempt = prepareAndBegin(db, cmd("a2"))
        val before = world(db)

        val early = consumed(cmd("a2"), itemData(entry(local, kept, status = "purchased", at = bought, lock = 5, outcome = null)), revision = 842)

        assertEquals(ReceiptRefusal.ALIAS_UNKNOWN, applier(db).apply(cmd("a2"), attempt, early).refusedFor())
        assertEquals(before, world(db))
    }

    @Test
    fun aReviveRecordsTheClaimAsAnAliasOfThePurchaseAndShowsItPending() = runBlocking {
        val db = openDatabase()
        val store = storeOn(db, ScriptedIds(cmd("a1")))
        seedConfirmed(db, 800, confirmed(purchase, "Detergente", lock = 3, purchased = true))
        assertTrue(store.execute(ListIntent.AddText("Detergente")) is IntentResult.Saved)
        val attempt = prepareAndBegin(db, cmd("a1"), claims = { claim("c1") })

        val answer = consumed(cmd("a1"), items(entry(claim("c1"), purchase, "Detergente", lock = 4, outcome = "revived")), revision = 801)
        val result = applier(db).apply(cmd("a1"), attempt, answer) as ApplyResult.Acknowledged

        assertEquals(purchase, db.aliases().find(claim("c1"))!!.canonicalPublicId)
        assertTrue(result.retained)
        val row = db.listItems().find(purchase)!!
        assertEquals(ItemStatusCodes.PENDING, row.status)
        assertEquals(4, row.lockVersion)
        assertEquals("the confirmed purchase is not rewritten", ItemStatusCodes.PURCHASED, db.confirmedItems().all().single().status)
        assertEquals(800L, db.syncState().get()!!.confirmedRevision)
    }

    @Test
    fun answersThatCannotBeThisCommandsChangeNothingAndKeepTheQueue() = runBlocking {
        val db = openDatabase()
        val store = storeOn(db, ScriptedIds(local, cmd("a1"), cmd("a2")))
        store.execute(ListIntent.AddItems(listOf("Leite")))
        store.execute(ListIntent.SetItemState(local, ItemStatus.PURCHASED))
        prepareAndBegin(db, cmd("a1"))
        val attempt = prepareAndBegin(db, cmd("a1"))
        db.aliases().insert(SyncAliasEntity(local, other, cmd("f1"), 700))
        val before = world(db)
        val applier = applier(db)

        val tries = mapOf(
            "the answer of a mark" to consumed(cmd("a1"), itemData(entry(local, outcome = null))),
            "another UUID" to consumed(cmd("a1"), items(entry(other))),
            "the right UUID under another name" to consumed(cmd("a1"), items(entry(local, name = "Café"))),
            "an item that is not a UUID" to consumed(cmd("a1"), items(entry(local, "not-a-uuid"))),
            "a created item at a version it cannot have" to consumed(cmd("a1"), items(entry(local, lock = 3))),
            "no revision where one is required" to consumed(cmd("a1"), items(entry(local)), revision = 0, house = 0),
            "a revived item for a create" to consumed(cmd("a1"), items(entry(local, purchase, lock = 4, outcome = "revived"))),
            "an empty result" to consumed(cmd("a1"), items()),
            "two results" to consumed(cmd("a1"), items(entry(local), entry(other))),
            "a duplicate whose alias contradicts the one recorded" to
                consumed(cmd("a1"), items(entry(local, kept, outcome = "duplicate")), ReceiptStatus.NOOP),
        )
        for ((label, receipt) in tries) {
            val result = applier.apply(cmd("a1"), attempt, receipt)
            assertTrue("$label: $result", result is ApplyResult.Refused)
            assertEquals(label, before, world(db))
        }
        // The same answer for a command it was never sent for, and for one that never existed.
        assertTrue(applier.apply(cmd("a2"), 1, consumed(cmd("a1"), items(entry(local)))) is ApplyResult.Refused)
        assertEquals(ApplyResult.Gone(cmd("ff")), applier.apply(cmd("ff"), 1, consumed(cmd("ff"), items(entry(local)))))
        assertEquals(before, world(db))
    }

    @Test
    fun aLateAttemptNumberThatNeverHappenedIsRefused() = runBlocking {
        val db = openDatabase()
        val store = storeOn(db, ScriptedIds(local, cmd("a1")))
        store.execute(ListIntent.AddItems(listOf("Leite")))
        val attempt = prepareAndBegin(db, cmd("a1"))
        val before = world(db)

        val result = applier(db).apply(cmd("a1"), attempt + 1, consumed(cmd("a1"), items(entry(local))))

        assertEquals(ReceiptRefusal.IMPOSSIBLE_ATTEMPT, result.refusedFor())
        assertEquals(before, world(db))
    }

    @Test
    fun aDependentCannotHaveHappenedBeforeItsDependency() = runBlocking {
        val db = openDatabase()
        val store = storeOn(db, ScriptedIds(local, cmd("a1"), cmd("a2")))
        store.execute(ListIntent.AddItems(listOf("Leite")))
        store.execute(ListIntent.SetItemState(local, ItemStatus.PURCHASED))
        val first = prepareAndBegin(db, cmd("a1"))
        val second = prepareAndBegin(db, cmd("a2"))
        applier(db).apply(cmd("a1"), first, consumed(cmd("a1"), items(entry(local)), revision = 850))
        val before = world(db)

        val impossible = consumed(cmd("a2"), itemData(entry(local, status = "purchased", at = bought, lock = 1, outcome = null)), revision = 849, house = 851)

        assertEquals(ReceiptRefusal.IMPOSSIBLE_REVISION, applier(db).apply(cmd("a2"), second, impossible).refusedFor())
        assertEquals(before, world(db))
        // At the right revision it goes through.
        val possible = consumed(cmd("a2"), itemData(entry(local, status = "purchased", at = bought, lock = 1, outcome = null)), revision = 851)
        assertTrue(applier(db).apply(cmd("a2"), second, possible) is ApplyResult.Acknowledged)
    }

    // ---- what the confirmed state already says -----------------------------------------------

    @Test
    fun anAnswerLostAfterASnapshotAlreadyCoveredItDoesNotBringBackWhatSomeoneElseRemoved() = runBlocking {
        val db = openDatabase()
        val store = storeOn(db, ScriptedIds(local, cmd("a1"), cmd("a2")))
        store.execute(ListIntent.AddItems(listOf("Leite")))
        store.execute(ListIntent.SetItemState(local, ItemStatus.PURCHASED))
        val first = prepareAndBegin(db, cmd("a1"))
        val second = prepareAndBegin(db, cmd("a2"))
        // The server applied both (revisions 841, 842), the answers were lost, the other phone removed the
        // item, and a snapshot at revision 900 (installed by a later cut) no longer has it.
        seedConfirmed(db, 900)
        assertTrue("until the answers are resolved the queue still replays", db.listItems().all().isNotEmpty())

        val a = applier(db).apply(cmd("a1"), first, consumed(cmd("a1"), items(entry(local)), ReceiptStatus.DUPLICATE, revision = 841, house = 900))
        val b = applier(db).apply(
            cmd("a2"), second,
            consumed(cmd("a2"), itemData(entry(local, status = "purchased", at = bought, lock = 1, outcome = null)), ReceiptStatus.DUPLICATE, revision = 842, house = 900),
        )

        // Covered by the snapshot: consumed and released, with nothing replayed over the snapshot.
        assertEquals(false, (a as ApplyResult.Acknowledged).retained)
        assertEquals(false, (b as ApplyResult.Acknowledged).retained)
        assertTrue(db.commands().inOrder().isEmpty())
        assertTrue("the item is not reintroduced", db.listItems().all().isEmpty())
        assertEquals(900L, db.syncState().get()!!.confirmedRevision)
    }

    @Test
    fun aReceiptNewerThanTheSnapshotIsShownAndKeptUntilASnapshotCoversIt() = runBlocking {
        val db = openDatabase()
        val store = storeOn(db, ScriptedIds(local, cmd("a1")))
        store.execute(ListIntent.AddItems(listOf("Leite")))
        val attempt = prepareAndBegin(db, cmd("a1"))
        seedConfirmed(db, 800)

        val result = applier(db).apply(cmd("a1"), attempt, consumed(cmd("a1"), items(entry(local)), revision = 801))

        assertTrue((result as ApplyResult.Acknowledged).retained)
        assertEquals(listOf(local), db.listItems().all().map { it.publicId })

        // Once a snapshot at or after 801 is the confirmed state, the same row is inert: it replays nothing.
        db.syncState().put(SyncStateEntity(householdPublicId = "house-1", confirmedRevision = 801))
        RoomShoppingListStore(db).rebuildProjection()
        assertTrue(db.listItems().all().isEmpty())
        assertEquals(CommandStatus.ACKNOWLEDGED.name, statusOf(db, cmd("a1")))
    }

    @Test
    fun aConsumedCommandIsKeptWhileACommandNotYetPreparedStillNeedsItToLendItsVersion() = runBlocking {
        val db = openDatabase()
        val store = storeOn(db, ScriptedIds(local, cmd("a1"), cmd("a2")))
        store.execute(ListIntent.AddItems(listOf("Leite")))
        store.execute(ListIntent.SetItemState(local, ItemStatus.PURCHASED))
        val attempt = prepareAndBegin(db, cmd("a1")) // cmd("a2") has no document yet
        seedConfirmed(db, 900)

        val result = applier(db).apply(cmd("a1"), attempt, consumed(cmd("a1"), items(entry(local)), ReceiptStatus.DUPLICATE, revision = 841, house = 900))

        assertTrue("the dependent has to be prepared against it", (result as ApplyResult.Acknowledged).retained)
        assertEquals(listOf(cmd("a1"), cmd("a2")), db.commands().inOrder().map { it.id })
    }

    @Test
    fun aliasesOutliveTheCommandThatRevealedThemAndQueuedCommandsStillFindTheItem() = runBlocking {
        val db = openDatabase()
        val store = storeOn(db, ScriptedIds(local, cmd("a1"), cmd("a2")))
        store.execute(ListIntent.AddItems(listOf("Leite")))
        store.execute(ListIntent.SetItemState(local, ItemStatus.PURCHASED))
        val first = prepareAndBegin(db, cmd("a1"))
        prepareAndBegin(db, cmd("a2")) // frozen: it no longer needs a1 to be prepared
        val frozen = db.commands().find(cmd("a2"))!!.wireDocument
        seedConfirmed(db, 900, confirmed(kept, "Leite", lock = 4))

        applier(db).apply(cmd("a1"), first, consumed(cmd("a1"), items(entry(local, kept, outcome = "duplicate", lock = 4)), ReceiptStatus.DUPLICATE, revision = 841, house = 900, original = ReceiptStatus.NOOP))

        assertNull("released", db.commands().find(cmd("a1")))
        assertEquals(kept, db.aliases().find(local)!!.canonicalPublicId)
        // The mark that is still queued cites the old UUID, byte for byte, and acts on the kept item.
        assertEquals(frozen, db.commands().find(cmd("a2"))!!.wireDocument)
        val row = db.listItems().all().single { it.publicId == kept }
        assertEquals(ItemStatusCodes.PURCHASED, row.status)
        assertEquals(1, db.listItems().all().size)
    }

    // ---- refusals from the server ------------------------------------------------------------------

    @Test
    fun aConflictKeepsTheIntentAndBlocksOnlyTheDependentsEvenThroughOthers() = runBlocking {
        val db = openDatabase()
        val store = storeOn(db, ScriptedIds(local, cmd("a1"), cmd("a2"), cmd("a3"), "9a1d0000-0000-4000-8000-0000000000d1", cmd("a4")))
        store.execute(ListIntent.AddItems(listOf("Leite")))
        store.execute(ListIntent.SetItemState(local, ItemStatus.PURCHASED))
        store.execute(ListIntent.SetItemState(local, ItemStatus.PENDING))
        store.execute(ListIntent.AddItems(listOf("Pão"))) // independent
        val attempt = prepareAndBegin(db, cmd("a1"))
        val intent = db.commands().inOrder().map { it.payload to it.preconditions }
        val projection = db.listItems().all()

        val result = applier(db).apply(cmd("a1"), attempt, refusal(cmd("a1"), ReceiptStatus.CONFLICT, "precondition_failed", snapshotRequired = true))

        assertEquals(
            ApplyResult.Recorded(cmd("a1"), CommandStatus.CONFLICT, "precondition_failed", listOf(cmd("a2"), cmd("a3")), snapshotRequired = true),
            result,
        )
        assertEquals(CommandStatus.CONFLICT.name, statusOf(db, cmd("a1")))
        assertEquals(CommandStatus.BLOCKED.name, statusOf(db, cmd("a2")))
        assertEquals(CommandStatus.BLOCKED.name, statusOf(db, cmd("a3")))
        assertEquals("independent work stays sendable", CommandStatus.PENDING.name, statusOf(db, cmd("a4")))
        assertEquals(intent, db.commands().inOrder().map { it.payload to it.preconditions })
        assertEquals("the draft is still on screen", projection, db.listItems().all())
        assertEquals(3, store.observe().first().needsAttention)
        // A conflicting command cannot be sent again by the sender, and neither can its dependents.
        assertTrue(WireOutbox(db).beginAttempt(cmd("a1")) is AttemptResult.Refused)
    }

    @Test
    fun aConsumedAnswerBeatsAStaleConflictAndAConflictForAnOlderAttemptIsIgnored() = runBlocking {
        val db = openDatabase()
        val store = storeOn(db, ScriptedIds(local, cmd("a1"), cmd("a2")))
        store.execute(ListIntent.AddItems(listOf("Leite")))
        store.execute(ListIntent.SetItemState(local, ItemStatus.PURCHASED))
        val first = prepareAndBegin(db, cmd("a1"))
        val second = WireOutbox(db).beginAttempt(cmd("a1")) as AttemptResult.Started
        val applier = applier(db)

        // Attempt 1's refusal arrives after attempt 2 started: it says nothing about the command now.
        val old = applier.apply(cmd("a1"), first, refusal(cmd("a1"), ReceiptStatus.CONFLICT, "precondition_failed"))
        assertEquals(ApplyResult.Stale(cmd("a1"), StaleReason.ATTEMPT_SUPERSEDED), old)
        assertEquals(CommandStatus.IN_FLIGHT.name, statusOf(db, cmd("a1")))

        assertTrue(applier.apply(cmd("a1"), second.attempt, consumed(cmd("a1"), items(entry(local)), ReceiptStatus.DUPLICATE)) is ApplyResult.Acknowledged)
        // ...and a refusal that shows up after the command was consumed cannot undo it.
        val late = applier.apply(cmd("a1"), second.attempt, refusal(cmd("a1"), ReceiptStatus.BLOCKED, "dependency_not_applied"))
        assertEquals(ApplyResult.Stale(cmd("a1"), StaleReason.ALREADY_ACKNOWLEDGED), late)
        assertEquals(CommandStatus.ACKNOWLEDGED.name, statusOf(db, cmd("a1")))
        assertEquals(CommandStatus.PENDING.name, statusOf(db, cmd("a2")))
    }

    @Test
    fun aCommandConsumedAfterItsConflictWasRecordedReopensItsDependents() = runBlocking {
        val db = openDatabase()
        val store = storeOn(db, ScriptedIds(local, cmd("a1"), cmd("a2"), cmd("a3")))
        store.execute(ListIntent.AddItems(listOf("Leite")))
        store.execute(ListIntent.SetItemState(local, ItemStatus.PURCHASED))
        store.execute(ListIntent.SetItemState(local, ItemStatus.PENDING))
        val attempt = prepareAndBegin(db, cmd("a1"))
        val applier = applier(db)
        applier.apply(cmd("a1"), attempt, refusal(cmd("a1"), ReceiptStatus.CONFLICT, "precondition_failed"))
        assertEquals(CommandStatus.BLOCKED.name, statusOf(db, cmd("a3")))

        // Another attempt, already in flight when the conflict came, had in fact been applied.
        val result = applier.apply(cmd("a1"), attempt, consumed(cmd("a1"), items(entry(local)), ReceiptStatus.DUPLICATE))

        assertTrue(result is ApplyResult.Acknowledged)
        assertEquals(CommandStatus.PENDING.name, statusOf(db, cmd("a2")))
        assertEquals(CommandStatus.PENDING.name, statusOf(db, cmd("a3")))
        assertNull(db.commands().find(cmd("a2"))!!.lastErrorCode)
    }

    @Test
    fun aRejectionIsRecordedLikeAConflictAndPreservesTheIntent() = runBlocking {
        val db = openDatabase()
        val store = storeOn(db, ScriptedIds(local, cmd("a1"), cmd("a2")))
        store.execute(ListIntent.AddItems(listOf("Leite")))
        store.execute(ListIntent.SetItemState(local, ItemStatus.PURCHASED))
        val attempt = prepareAndBegin(db, cmd("a1"))

        val result = applier(db).apply(cmd("a1"), attempt, refusal(cmd("a1"), ReceiptStatus.REJECTED, "public_id_conflict")) as ApplyResult.Recorded

        assertEquals(CommandStatus.REJECTED, result.status)
        assertEquals(listOf(cmd("a2")), result.blockedDependents)
        assertEquals("public_id_conflict", db.commands().find(cmd("a1"))!!.lastErrorCode)
        assertEquals("""{"names":["Leite"]}""", db.commands().find(cmd("a1"))!!.payload)
        assertTrue(db.receipts().all().isEmpty())
    }

    @Test
    fun aBlockedAnswerForAnUnsentDependencyIsAWaitNotAConflictAndDoesNotLoop() = runBlocking {
        val db = openDatabase()
        val store = storeOn(db, ScriptedIds(local, cmd("a1"), cmd("a2")))
        store.execute(ListIntent.AddItems(listOf("Leite")))
        store.execute(ListIntent.SetItemState(local, ItemStatus.PURCHASED))
        prepareAndBegin(db, cmd("a1")).also { /* a1 left in flight, no answer yet */ }
        val attempt = prepareAndBegin(db, cmd("a2"))
        val blocked = refusal(cmd("a2"), ReceiptStatus.BLOCKED, "dependency_not_applied")

        val first = applier(db).apply(cmd("a2"), attempt, blocked)
        val again = applier(db).apply(cmd("a2"), attempt, blocked)

        assertEquals(ApplyResult.WaitingForDependency(cmd("a2"), cmd("a1")), first)
        assertEquals(first, again)
        assertEquals("still sendable, after its dependency", CommandStatus.PENDING.name, statusOf(db, cmd("a2")))
        assertEquals(1, db.commands().find(cmd("a2"))!!.attempts)
    }

    @Test
    fun aBlockedAnswerWhoseDependencyCanNeverSatisfyItNeedsReconciliation() = runBlocking {
        val db = openDatabase()
        val store = storeOn(db, ScriptedIds(local, cmd("a1"), cmd("a2")))
        store.execute(ListIntent.AddItems(listOf("Leite")))
        store.execute(ListIntent.SetItemState(local, ItemStatus.PURCHASED))
        val first = prepareAndBegin(db, cmd("a1"))
        val second = prepareAndBegin(db, cmd("a2"))
        applier(db).apply(cmd("a1"), first, refusal(cmd("a1"), ReceiptStatus.CONFLICT, "precondition_failed"))

        // a2 was already BLOCKED by the conflict; the server's own "blocked" does not make it sendable.
        val result = applier(db).apply(cmd("a2"), second, refusal(cmd("a2"), ReceiptStatus.BLOCKED, "dependency_not_applied"))

        assertTrue(result is ApplyResult.Recorded && result.status == CommandStatus.BLOCKED)
        assertEquals(CommandStatus.BLOCKED.name, statusOf(db, cmd("a2")))
    }

    // ---- failures and what is still unresolved ----------------------------------------------------

    @Test
    fun aSqlFailureHalfwayThroughUndoesTheWholeApplication() = runBlocking {
        val db = openDatabase()
        val store = storeOn(db, ScriptedIds(local, cmd("a1")))
        store.execute(ListIntent.AddItems(listOf("Leite")))
        val attempt = prepareAndBegin(db, cmd("a1"))
        val before = world(db)
        // The status and receipt are written after the alias: make the last of them fail.
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER refuse_receipts BEFORE INSERT ON command_receipts BEGIN SELECT RAISE(ABORT, 'disk refused the receipt'); END",
        )

        try {
            applier(db).apply(cmd("a1"), attempt, consumed(cmd("a1"), items(entry(local, kept, outcome = "duplicate")), ReceiptStatus.NOOP))
            fail("the failure must reach the caller")
        } catch (expected: SQLException) {
            // the caller decides when to retry; the answer will come again as a duplicate
        }

        assertEquals("no alias, no status change, no receipt, same projection", before, world(db))
    }

    @Test
    fun attemptsWithoutAnAnswerAreTheOnesASnapshotMustWaitFor() = runBlocking {
        val db = openDatabase()
        val store = storeOn(db, ScriptedIds(local, cmd("a1"), cmd("a2"), "9a1d0000-0000-4000-8000-0000000000d1", cmd("a3")))
        store.execute(ListIntent.AddItems(listOf("Leite")))
        store.execute(ListIntent.SetItemState(local, ItemStatus.PURCHASED))
        store.execute(ListIntent.AddItems(listOf("Pão")))
        val applier = applier(db)
        assertTrue("nothing was sent yet", applier.unresolvedAttempts().isEmpty())

        val first = prepareAndBegin(db, cmd("a1"))
        prepareAndBegin(db, cmd("a3"))
        assertEquals("sent, no answer: the server may have applied them", listOf(cmd("a1"), cmd("a3")), applier.unresolvedAttempts())

        applier.apply(cmd("a1"), first, consumed(cmd("a1"), items(entry(local))))
        assertEquals(listOf(cmd("a3")), applier.unresolvedAttempts())
        applier.apply(cmd("a3"), 1, refusal(cmd("a3"), ReceiptStatus.REJECTED, "invalid_name"))
        assertTrue("a definitive answer, either way, resolves it", applier.unresolvedAttempts().isEmpty())
    }

    @Test
    fun aSentOutcomeThatIsNotAReceiptAppliesNothing() = runBlocking {
        val db = openDatabase()
        val store = storeOn(db, ScriptedIds(local, cmd("a1")))
        store.execute(ListIntent.AddItems(listOf("Leite")))
        val attempt = prepareAndBegin(db, cmd("a1"))
        val before = world(db)

        for (outcome in listOf(
            CommandOutcome.Transient(TransientCause.NETWORK),
            CommandOutcome.Unauthorized,
            CommandOutcome.Refused(422, "malformed_command"),
            CommandOutcome.Invalid(InvalidResponse.WRONG_SHAPE),
        )) {
            assertEquals(ApplyResult.NoReceipt(outcome), applier(db).apply(SendAttempt.Sent(cmd("a1"), attempt, outcome)))
        }
        assertEquals(before, world(db))
    }

    @Test
    fun anOtherDevicesNewItemWithTheSameNameIsNotShownTwiceAndAPurchaseIsNotRevivedBehindTheUsersBack() = runBlocking {
        val db = openDatabase()
        val store = storeOn(db, ScriptedIds(local, "9a1d0000-0000-4000-8000-0000000000d1", cmd("a1"), cmd("a2")))
        store.execute(ListIntent.AddItems(listOf("Leite", "Café")))
        store.execute(ListIntent.SetItemState(local, ItemStatus.PURCHASED))
        // A snapshot-like confirmed state arrives: someone else already has "Leite" pending, and bought "Café" a minute ago.
        seedConfirmed(
            db, 900,
            confirmed(kept, "Leite", lock = 2),
            confirmed(purchase, "Café", lock = 7, purchased = true),
        )

        val rows = db.listItems().all().associateBy { it.name }

        assertEquals("one Leite, the one that exists, and the queued mark acts on it", ItemStatusCodes.PURCHASED, rows.getValue("Leite").status)
        assertEquals(kept, rows.getValue("Leite").publicId)
        assertEquals(1, db.listItems().all().count { it.name == "Leite" })
        // "Café" typed on this phone is a new item; the purchase made elsewhere stays purchased.
        assertEquals(ItemStatusCodes.PURCHASED, db.listItems().find(purchase)!!.status)
        assertEquals(2, db.listItems().all().count { it.name == "Café" })
        assertEquals("the queue is untouched", 2, db.commands().inOrder().size)
    }
    @Test
    fun blockingAnInFlightDependentDoesNotResolveItsMissingAnswer() = runBlocking {
        val db = openDatabase()
        val store = storeOn(db, ScriptedIds(local, cmd("a1"), cmd("a2")))
        store.execute(ListIntent.AddText("Leite"))
        store.execute(ListIntent.SetItemState(local, ItemStatus.PURCHASED))
        val rootAttempt = prepareAndBegin(db, cmd("a1"))
        prepareAndBegin(db, cmd("a2"))

        applier(db).apply(cmd("a1"), rootAttempt, refusal(cmd("a1"), ReceiptStatus.CONFLICT, "precondition_failed"))

        assertEquals(CommandStatus.BLOCKED.name, statusOf(db, cmd("a2")))
        assertEquals("inherited blocking is not the dependent's own server answer", listOf(cmd("a2")), applier(db).unresolvedAttempts())
    }

    @Test
    fun aProvisionalMergeKeepsTheNextActionCausallyLinkedAndVisiblyPending() = runBlocking {
        val db = openDatabase()
        val store = storeOn(db, ScriptedIds(local, cmd("a1"), cmd("a2"), cmd("a3")))
        store.execute(ListIntent.AddText("Leite"))
        store.execute(ListIntent.SetItemState(local, ItemStatus.PURCHASED))
        seedConfirmed(db, 800, confirmed(kept, "Leite", lock = 0))
        val wasWaiting = store.observe().first().items.single().awaitingSync

        store.execute(ListIntent.SetItemState(kept, ItemStatus.PENDING))

        assertEquals("the displayed canonical row still has the pending mark as predecessor", cmd("a2"), db.commands().find(cmd("a3"))!!.dependsOn)
        assertTrue("provisionally merged rows must show their pending local effects", wasWaiting)
    }

    @Test
    fun aReviveRedirectedToAnotherPendingItemCannotSilentlySendADependentToTheOldPurchase() = runBlocking {
        val db = openDatabase()
        val store = storeOn(db, ScriptedIds(cmd("a1"), cmd("a2"), other, cmd("a3")))
        seedConfirmed(db, 800, confirmed(purchase, "Leite", lock = 3, purchased = true))
        store.execute(ListIntent.AddText("Leite"))
        store.execute(ListIntent.SetItemState(purchase, ItemStatus.PURCHASED))
        store.execute(ListIntent.AddText("Pão")) // independent
        val attempt = prepareAndBegin(db, cmd("a1"), claims = { claim("c1") })
        assertTrue(WirePreparer(db).prepare(cmd("a2")) is PreparationResult.Prepared)
        val dependent = db.commands().find(cmd("a2"))!!

        val reply = consumed(cmd("a1"), items(entry(claim("c1"), kept, "Leite", lock = 4, outcome = "duplicate")), ReceiptStatus.NOOP)
        assertTrue(applier(db).apply(cmd("a1"), attempt, reply) is ApplyResult.Acknowledged)

        val after = db.commands().find(cmd("a2"))!!
        assertTrue("do not silently apply a mark to the old purchase instead of the redirected item", CommandStatus.valueOf(after.status).stopsDependents)
        assertEquals(dependent.payload, after.payload)
        assertEquals(dependent.wireDocument, after.wireDocument)
        assertEquals(CommandStatus.PENDING.name, statusOf(db, cmd("a3")))
    }

}
