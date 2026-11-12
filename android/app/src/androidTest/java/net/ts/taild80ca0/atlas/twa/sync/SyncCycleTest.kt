package net.ts.taild80ca0.atlas.twa.sync

import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import net.ts.taild80ca0.atlas.twa.data.db.CommandReceiptEntity
import net.ts.taild80ca0.atlas.twa.data.list.CommandStatus
import net.ts.taild80ca0.atlas.twa.data.list.IntentResult
import net.ts.taild80ca0.atlas.twa.data.list.ItemStatus
import net.ts.taild80ca0.atlas.twa.data.list.ListIntent
import net.ts.taild80ca0.atlas.twa.sync.SnapshotFixtures.HOUSE
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The sync cycle (issue #45, sixth cut) on real Room files and a scripted server: order, retries with the same
 * bytes, an answer lost after the server committed, refusals that must not loop, and the rule that a snapshot is
 * never installed over an attempt nobody answered. The same scenarios against real Rails are in
 * `RailsSyncConvergenceTest` (`script/android_rails_sync_check`).
 */
class SyncCycleTest {
    private val server = FakeHouseServer()
    private val rigs = mutableListOf<SyncRig>()

    private fun device(connected: Boolean = true) = SyncRig(HOUSE, server, server, connected = connected).also { rigs += it }

    @After
    fun closeAll() {
        rigs.forEach { it.close() }
    }

    private suspend fun SyncRig.add(vararg names: String) {
        val result = store.execute(ListIntent.AddItems(names.toList()))
        assertTrue("$result", result is IntentResult.Saved)
    }

    private suspend fun SyncRig.mark(name: String, desired: ItemStatus) {
        val item = state().items.first { it.name == name }
        assertTrue(store.execute(ListIntent.SetItemState(item.publicId, desired)) is IntentResult.Saved)
    }

    @Test
    fun anItemAddedOfflineIsSentThenReplacedByTheServersSnapshot() = runBlocking<Unit> {
        val a = device()
        a.add("Leite")
        assertTrue(a.state().items.single().awaitingSync)

        val report = a.cycle.run()

        assertEquals(SyncOutcome.Synced(server.revision), report.outcome)
        assertEquals(1, report.acknowledged)
        assertEquals(listOf("Leite"), server.items.values.map { it.name })
        assertEquals("the consumed command is released once the snapshot carries it", 0, a.db.commands().count())
        assertFalse(a.state().items.single().awaitingSync)
        assertEquals(server.revision, a.state().confirmedRevision)
    }

    @Test
    fun anAddAndAMarkMadeOfflineGoInOrderAndConverge() = runBlocking<Unit> {
        val a = device()
        a.add("Leite")
        a.mark("Leite", ItemStatus.PURCHASED)

        val report = a.cycle.run()

        assertTrue("${report.outcome}", report.outcome is SyncOutcome.Synced)
        assertEquals(2, report.acknowledged)
        assertEquals("the dependent never goes before its dependency", 2, server.received.size)
        assertTrue(server.received[0].second.contains("add_items") && server.received[1].second.contains("set_item_state"))
        assertTrue(server.items.values.single().purchased)
        assertEquals(listOf("Leite" to true), a.names())
        assertEquals(0, a.db.commands().count())
    }

    @Test
    fun twoDevicesConverge() = runBlocking<Unit> {
        val a = device()
        val b = device()
        a.add("Leite")
        assertEquals(emptyList<Pair<String, Boolean>>(), b.names())

        assertTrue(a.cycle.run().outcome is SyncOutcome.Synced)
        // B has nothing of its own to send, and must still learn the list: an empty queue never skips the snapshot.
        val first = b.cycle.run()
        assertTrue("${first.outcome}", first.outcome is SyncOutcome.Synced)
        assertEquals(listOf("Leite" to false), b.names())

        b.mark("Leite", ItemStatus.PURCHASED)
        assertTrue(b.cycle.run().outcome is SyncOutcome.Synced)
        assertTrue(a.cycle.run().outcome is SyncOutcome.Synced)

        assertEquals(listOf("Leite" to true), a.names())
        assertEquals(listOf("Leite" to true), b.names())
        assertEquals(a.state().confirmedRevision, b.state().confirmedRevision)
        assertEquals(1, server.items.size)
    }

    @Test
    fun theSameNameAddedOnTwoPhonesEndsAsOneItemOnBoth() = runBlocking<Unit> {
        val a = device()
        val b = device()
        a.add("Leite")
        b.add("Leite")

        assertTrue(a.cycle.run().outcome is SyncOutcome.Synced)
        assertTrue(b.cycle.run().outcome is SyncOutcome.Synced)
        b.mark("Leite", ItemStatus.PURCHASED)
        assertTrue(b.cycle.run().outcome is SyncOutcome.Synced)
        assertTrue(a.cycle.run().outcome is SyncOutcome.Synced)

        assertEquals(1, server.items.size)
        assertEquals(listOf("Leite" to true), a.names())
        assertEquals(listOf("Leite" to true), b.names())
        assertEquals("the alias the server revealed is kept", 1, b.db.aliases().all().size)
    }

    @Test
    fun anAnswerLostAfterTheServerCommittedIsRetriedWithTheSameBytesAndHasNoSecondEffect() = runBlocking<Unit> {
        val a = device()
        a.add("Leite")
        server.applyThenLoseTheAnswer = 1

        val lost = a.cycle.run()

        assertEquals(SyncOutcome.Offline(TransientCause.NETWORK), lost.outcome)
        assertEquals("the server did commit", 1, server.items.size)
        val revisionAfterTheFirstEffect = server.revision
        val command = a.db.commands().inOrder().single()
        assertEquals(CommandStatus.IN_FLIGHT.name, command.status)
        assertEquals(1, command.attempts)
        assertEquals("no snapshot is read while an attempt is unanswered", 0, server.snapshotRequests)
        assertEquals("nothing confirmed yet", 0L, a.state().confirmedRevision)

        val retried = a.cycle.run()

        assertTrue("${retried.outcome}", retried.outcome is SyncOutcome.Synced)
        assertEquals(2, server.received.size)
        assertEquals("the very same document", server.received[0], server.received[1])
        assertEquals("no second effect", 1, server.items.size)
        assertEquals(revisionAfterTheFirstEffect, server.revision)
        assertEquals(listOf("Leite" to false), a.names())
        assertEquals(0, a.db.commands().count())
    }

    @Test
    fun aLostAnswerDoesNotLetAnotherDevicesRemovalBeUndoneBySnapshotReplay() = runBlocking<Unit> {
        val a = device()
        val b = device()
        a.add("Leite")
        server.applyThenLoseTheAnswer = 1
        assertTrue(a.cycle.run().outcome is SyncOutcome.Offline)
        // B removes it while A is unsure; a snapshot read now would not show the item at all.
        server.removeElsewhere(server.items.keys.single())
        assertTrue(b.cycle.run().outcome is SyncOutcome.Synced)

        val retry = a.cycle.run()

        assertTrue("${retry.outcome}", retry.outcome is SyncOutcome.Synced)
        assertEquals("A does not bring it back", emptyList<Pair<String, Boolean>>(), a.names())
        assertEquals(emptyList<Pair<String, Boolean>>(), b.names())
    }

    @Test
    fun withoutANetworkNothingIsLostOrConfirmed() = runBlocking<Unit> {
        val a = device()
        a.add("Leite")
        server.unreachable = true

        val report = a.cycle.run()

        assertEquals(SyncOutcome.Offline(TransientCause.NETWORK), report.outcome)
        assertEquals(0, server.snapshotRequests)
        assertEquals(listOf("Leite" to false), a.names())
        assertTrue(a.state().items.single().awaitingSync)
        // The attempt that reached the wire is counted, and stays unresolved.
        assertEquals(1, a.db.commands().inOrder().single().attempts)

        server.unreachable = false
        assertTrue(a.cycle.run().outcome is SyncOutcome.Synced)
        assertEquals(1, server.items.size)
    }

    @Test
    fun aDeviceWithoutAccessSendsNothing() = runBlocking<Unit> {
        val a = device(connected = false)
        a.add("Leite")

        val report = a.cycle.run()

        assertEquals(SyncOutcome.NotConnected, report.outcome)
        assertTrue(server.received.isEmpty())
        assertEquals(0, server.snapshotRequests)
        assertEquals(0, a.db.commands().inOrder().single().attempts)
    }

    @Test
    fun aConflictIsNotRetriedInALoopAndDoesNotStopIndependentWork() = runBlocking<Unit> {
        val a = device()
        server.addElsewhere("a0000000-0000-4000-8000-0000000000a1", "Café")
        assertTrue(a.cycle.run().outcome is SyncOutcome.Synced)
        a.mark("Café", ItemStatus.PURCHASED)
        a.mark("Café", ItemStatus.PENDING) // depends on the previous mark
        a.add("Arroz") // independent
        val marks = a.db.commands().inOrder()
        server.refuse(marks[0].id, ReceiptStatus.CONFLICT, "precondition_failed")

        val report = a.cycle.run()

        assertTrue("${report.outcome}", report.outcome is SyncOutcome.Synced)
        assertEquals(1, report.acknowledged)
        assertEquals("the conflict and its dependent wait for a person", 2, report.needsAttention)
        assertEquals(listOf("Café", "Arroz"), server.items.values.map { it.name })
        val queue = a.db.commands().inOrder().associateBy { it.id }
        assertEquals(CommandStatus.CONFLICT.name, queue.getValue(marks[0].id).status)
        assertEquals(CommandStatus.BLOCKED.name, queue.getValue(marks[1].id).status)
        assertEquals("every command was tried once", 1, server.received.count { it.first == marks[0].id })
        assertEquals(0, server.received.count { it.first == marks[1].id })

        // Another cycle does not send them again either, and still syncs.
        val again = a.cycle.run()
        assertTrue(again.outcome is SyncOutcome.Synced)
        assertEquals(1, server.received.count { it.first == marks[0].id })
        assertEquals("the intention is kept", marks.map { it.payload }.take(2), a.db.commands().inOrder().take(2).map { it.payload })
        assertEquals(2, again.needsAttention)
    }

    @Test
    fun anIdempotencyConflictKeepsTheSnapshotAwayInsteadOfGuessing() = runBlocking<Unit> {
        val a = device()
        a.add("Leite")
        val id = a.db.commands().inOrder().single().id
        server.refuse(id, ReceiptStatus.REJECTED, "idempotency_conflict")

        val report = a.cycle.run()

        assertEquals(SyncOutcome.SnapshotHeld(listOf(id)), report.outcome)
        assertEquals(0, server.snapshotRequests)
        assertEquals("nothing is deleted or re-created", listOf(id), a.db.commands().inOrder().map { it.id })
        assertEquals(listOf("Leite" to false), a.names())
    }

    @Test
    fun aRequestRefusedAsAWholeIsNotResentAndDoesNotHoldTheSnapshot() = runBlocking<Unit> {
        val a = device()
        a.add("Leite")
        a.add("Pão")
        val (refused, independent) = a.db.commands().inOrder().map { it.id }
        server.refuseRequest(refused, CommandOutcome.Refused(422, "malformed_command"))

        val report = a.cycle.run()

        assertTrue("$report", report.outcome is SyncOutcome.Synced)
        assertEquals(1, report.needsAttention)
        assertEquals(listOf(refused, independent), server.received.map { it.first })
        val kept = a.db.commands().inOrder().single()
        assertEquals(refused, kept.id)
        assertEquals("REJECTED", kept.status)
        assertEquals("malformed_command", kept.lastErrorCode)
        assertTrue("the intent is still shown", a.names().any { it.first == "Leite" })
        val resolver = net.ts.taild80ca0.atlas.twa.data.list.ConflictResolver(
            a.db, net.ts.taild80ca0.atlas.twa.data.list.IdGenerator { "decision" }, net.ts.taild80ca0.atlas.twa.data.list.Clock { 0L },
        )
        assertEquals("offered for an explicit discard", net.ts.taild80ca0.atlas.twa.data.list.ConflictResolver.Kind.REFUSED, resolver.preview(refused)!!.kind)

        assertTrue(a.cycle.run().outcome is SyncOutcome.Synced)
        assertEquals("never resent in a loop", 2, server.received.size)
    }

    @Test
    fun aMarkHeldByARedirectedReviveIsOfferedForDiscardAndTheQueueThenEmpties() = runBlocking<Unit> {
        val a = device()
        val purchase = "9a1d0000-0000-4000-8000-0000000000e1"
        val elsewhere = "9a1d0000-0000-4000-8000-0000000000e2"
        server.addElsewhere(purchase, "Leite")
        server.markElsewhere(purchase, purchased = true)
        assertTrue(a.cycle.run().outcome is SyncOutcome.Synced)
        server.addElsewhere(elsewhere, "Leite") // another phone added a new pending "Leite" meanwhile

        a.store.execute(ListIntent.AddText("Leite")) // revives the purchase this phone saw
        a.mark("Leite", ItemStatus.PURCHASED)
        val report = a.cycle.run()

        assertTrue("$report", report.outcome is SyncOutcome.Synced)
        assertEquals(1, report.needsAttention)
        // The consumed revive stays until nothing unprepared depends on it; the mark is the one held.
        val held = a.db.commands().inOrder().single { it.status != CommandStatus.ACKNOWLEDGED.name }
        assertEquals(ReceiptApplier.REVIVE_REDIRECTED, held.lastErrorCode)
        val resolver = net.ts.taild80ca0.atlas.twa.data.list.ConflictResolver(
            a.db, net.ts.taild80ca0.atlas.twa.data.list.IdGenerator { "decision" }, net.ts.taild80ca0.atlas.twa.data.list.Clock { 0L },
        )
        val group = resolver.observe().first().single()
        assertEquals(held.id, group.rootId)
        assertEquals(net.ts.taild80ca0.atlas.twa.data.list.ConflictResolver.Kind.REFUSED, group.kind)
        assertTrue(resolver.discard(group) is net.ts.taild80ca0.atlas.twa.data.list.ConflictResolver.Result.Discarded)

        val sent = server.received.size
        assertTrue(a.cycle.run().outcome is SyncOutcome.Synced)
        assertEquals("the held mark was never sent", sent, server.received.size)
        assertEquals(0, a.db.commands().count())
        assertEquals("the server's two items, untouched", listOf("Leite" to true, "Leite" to false).sortedBy { it.second }, a.names().sortedBy { it.second })
    }

    @Test
    fun aFailureOnTheSecondCommandKeepsTheFirstConsumedAndTheSecondUnresolved() = runBlocking<Unit> {
        // The first request goes through; the second times out before the server applies it.
        val seen = java.util.concurrent.atomic.AtomicInteger()
        val flaky = object : CommandTransport by server {
            override suspend fun send(credential: net.ts.taild80ca0.atlas.twa.access.AccessCredential, commandId: String, document: String): CommandOutcome =
                if (seen.incrementAndGet() == 2) CommandOutcome.Transient(TransientCause.TIMEOUT) else server.send(credential, commandId, document)
        }
        val a = SyncRig(HOUSE, flaky, server).also { rigs += it }
        a.add("Leite")
        a.add("Arroz")
        val ids = a.db.commands().inOrder().map { it.id }

        val report = a.cycle.run()

        assertEquals(SyncOutcome.Offline(TransientCause.TIMEOUT), report.outcome)
        assertEquals(1, report.acknowledged)
        assertEquals(CommandStatus.ACKNOWLEDGED.name, a.db.commands().find(ids[0])?.status)
        assertEquals(CommandStatus.IN_FLIGHT.name, a.db.commands().find(ids[1])?.status)
        assertEquals(1, a.db.commands().find(ids[1])?.attempts)
        assertEquals("the second was not applied by the server", listOf("Leite"), server.items.values.map { it.name })
        assertEquals("no snapshot while an attempt is unanswered", 0, server.snapshotRequests)

        // The next cycle finishes everything, with the second command's same bytes.
        assertTrue(a.cycle.run().outcome is SyncOutcome.Synced)
        assertEquals(setOf("Leite", "Arroz"), server.items.values.map { it.name }.toSet())
        assertEquals(0, a.db.commands().count())
    }

    @Test
    fun aSnapshotTheServerRefusesToGiveChangesNothing() = runBlocking<Unit> {
        val a = device()
        a.add("Leite")
        server.snapshotOutcome = { SnapshotOutcome.Invalid(SnapshotProblem.WRONG_SHAPE, "revision") }

        val report = a.cycle.run()

        assertEquals(SyncOutcome.SnapshotUnusable(SnapshotProblem.WRONG_SHAPE), report.outcome)
        assertEquals("the command was consumed, its receipt kept until a snapshot covers it", CommandStatus.ACKNOWLEDGED.name, a.db.commands().inOrder().single().status)
        assertEquals(1L, a.db.receipts().all().size.toLong())
        assertEquals(listOf("Leite" to false), a.names())
        assertEquals(0L, a.state().confirmedRevision)
    }

    @Test
    fun aRefusedCredentialOnTheSnapshotFlagsTheDeviceForReconnecting() = runBlocking<Unit> {
        val a = device()
        server.snapshotOutcome = { SnapshotOutcome.Unauthorized }

        val report = a.cycle.run()

        assertEquals(SyncOutcome.NotConnected, report.outcome)
        assertNull(a.access.credentialForSync())
    }

    @Test
    fun aSecondCycleWhileOneRunsDoesNothing() = runBlocking<Unit> {
        val a = device()
        a.add("Leite")
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val slow = object : CommandTransport by server {
            override suspend fun send(credential: net.ts.taild80ca0.atlas.twa.access.AccessCredential, commandId: String, document: String): CommandOutcome {
                gate.await()
                return server.send(credential, commandId, document)
            }
        }
        val b = SyncRig(HOUSE, slow, server).also { rigs += it }
        b.add("Arroz")
        val first = async(kotlinx.coroutines.Dispatchers.Default) { b.cycle.run() }
        while (b.db.commands().inOrder().single().attempts == 0) kotlinx.coroutines.delay(10)

        assertEquals(SyncOutcome.AlreadyRunning, b.cycle.run().outcome)
        gate.complete(Unit)

        assertTrue(first.await().outcome is SyncOutcome.Synced)
        assertEquals(1, server.received.size)
    }

    @Test
    fun theReceiptKeptAfterAnAnswerIsReleasedOnlyBySnapshotCoverage() = runBlocking<Unit> {
        val a = device()
        a.add("Leite")
        server.snapshotOutcome = { SnapshotOutcome.Transient(TransientCause.NETWORK) }

        assertEquals(SyncOutcome.Offline(TransientCause.NETWORK), a.cycle.run().outcome)
        val retained = a.db.receipts().all().single()
        assertEquals(CommandReceiptEntity.APPLIED, retained.outcome)
        assertEquals("not awaiting a server answer any more", 0, a.state().queuedCommands)

        server.snapshotOutcome = null
        assertTrue(a.cycle.run().outcome is SyncOutcome.Synced)
        assertEquals(0, a.db.receipts().all().size)
    }
}
