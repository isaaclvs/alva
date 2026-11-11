package net.ts.taild80ca0.atlas.twa.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import net.ts.taild80ca0.atlas.twa.access.AccessGraph
import net.ts.taild80ca0.atlas.twa.data.list.IntentResult
import net.ts.taild80ca0.atlas.twa.data.list.ListIntent
import net.ts.taild80ca0.atlas.twa.sync.SnapshotFixtures.HOUSE
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class BackgroundSyncTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val server = FakeHouseServer()
    private lateinit var rig: SyncRig
    private lateinit var manager: WorkManager
    private lateinit var scheduler: BackgroundSync

    @Before
    fun setup() {
        rig = SyncRig(HOUSE, server, server)
        use(rig.cycle)
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context, Configuration.Builder().setExecutor(SynchronousExecutor()).build(),
        )
        manager = WorkManager.getInstance(context)
        scheduler = BackgroundSync(manager)
    }

    private fun use(runner: SyncRunner) {
        AccessGraph.install(rig.access, rig.store, SyncController(runner, scope))
    }

    @After
    fun close() {
        manager.cancelAllWork().result.get()
        scope.cancel()
        AccessGraph.install(null)
        rig.close()
        WorkManagerTestInitHelper.closeWorkDatabase()
    }

    private fun infos() = manager.getWorkInfosForUniqueWork(BackgroundSync.NAME).get()

    private suspend fun awaitState(id: UUID, predicate: (WorkInfo) -> Boolean): WorkInfo = withTimeout(10_000) {
        while (true) {
            val info = manager.getWorkInfoById(id).get()!!
            if (predicate(info)) return@withTimeout info
            delay(10)
        }
        error("unreachable")
    }

    private fun allowNetwork(id: UUID) {
        WorkManagerTestInitHelper.getTestDriver(context)!!.setAllConstraintsMet(id)
    }

    @Test
    fun openingRepairsUnscheduledRoomCommandsAndKeepsOneExistingWakeUp() = runBlocking<Unit> {
        // The Room commit outlives the producer; the next graph only needs the file and the credentials.
        rig.store.execute(ListIntent.AddText("Leite"))
        scheduler.recover()
        val id = infos().single().id
        scheduler.recover()
        assertEquals(listOf(id), infos().map { it.id })
        assertTrue(server.received.isEmpty())
        assertEquals(WorkInfo.State.ENQUEUED, infos().single().state)

        allowNetwork(id)
        awaitState(id) { it.state == WorkInfo.State.SUCCEEDED }
        assertEquals(listOf("Leite" to false), rig.names())
        assertEquals(1, server.received.size)
        assertEquals(0, rig.db.commands().count())
        assertTrue(AccessGraph.syncController(context)!!.status.value is SyncStatus.Idle)
    }

    @Test
    fun aSaveDuringTheLastSnapshotGetsASuccessorInsteadOfBeingLostToKeep() = runBlocking<Unit> {
        val entered = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        var runs = 0
        use(SyncRunner {
            runs++
            val report = rig.cycle.run()
            if (runs == 1) {
                // Pause after installing the old snapshot; a later local commit needs another cycle.
                entered.complete(Unit)
                finish.await()
            }
            report
        })
        scheduler.request()
        val first = infos().single().id
        allowNetwork(first)
        withTimeout(5_000) { entered.await() }
        val store = SyncingShoppingListStore(rig.store, scheduler)
        assertTrue(store.execute(ListIntent.AddText("Café")) is IntentResult.Saved)
        val successor = infos().single { it.id != first }.id
        assertEquals(WorkInfo.State.BLOCKED, manager.getWorkInfoById(successor).get()!!.state)
        finish.complete(Unit)
        awaitState(first) { it.state == WorkInfo.State.SUCCEEDED }
        assertTrue(server.received.isEmpty())
        assertEquals(1, rig.db.commands().count())
        allowNetwork(successor)
        awaitState(successor) { it.state == WorkInfo.State.SUCCEEDED }
        assertEquals(2, runs)
        assertEquals(1, server.received.size)
    }

    @Test
    fun transientFailureIsEnqueuedForBackoffInsteadOfBeingConsumed() = runBlocking<Unit> {
        use(SyncRunner { SyncReport(SyncOutcome.Offline(TransientCause.NETWORK)) })
        rig.store.execute(ListIntent.AddText("Pão"))
        scheduler.request()
        val id = infos().single().id
        allowNetwork(id)
        val info = awaitState(id) { it.state == WorkInfo.State.ENQUEUED && it.runAttemptCount == 1 }
        assertFalse(info.state.isFinished)
        assertEquals(1, rig.db.commands().count())
        assertTrue(server.received.isEmpty())
    }

    @Test
    fun permanentFailureFinishesAndANewSaveCanRunAfterIt() = runBlocking<Unit> {
        use(SyncRunner { SyncReport(SyncOutcome.SnapshotHeld(listOf("uncertain")), needsAttention = 1) })
        scheduler.request()
        val first = infos().single().id
        allowNetwork(first)
        val finished = awaitState(first) { it.state == WorkInfo.State.SUCCEEDED }
        assertEquals(1, finished.runAttemptCount)
        assertTrue(infos().all { it.state.isFinished })

        use(rig.cycle)
        SyncingShoppingListStore(rig.store, scheduler).execute(ListIntent.AddText("Ovos"))
        val next = infos().first { !it.state.isFinished }.id
        allowNetwork(next)
        awaitState(next) { it.state == WorkInfo.State.SUCCEEDED }
        assertEquals(listOf("Ovos" to false), rig.names())
    }

    @Test
    fun cancellationDoesNotPoisonFutureSavesAndWorkerRetriesBehindForeground() = runBlocking<Unit> {
        scheduler.request()
        val cancelled = infos().single().id
        manager.cancelUniqueWork(BackgroundSync.NAME).result.get()
        scheduler.request()
        val next = infos().first { it.id != cancelled }.id
        assertEquals(WorkInfo.State.ENQUEUED, manager.getWorkInfoById(next).get()!!.state)

        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        use(SyncRunner { entered.complete(Unit); release.await(); SyncReport(SyncOutcome.Synced(1)) })
        val controller = AccessGraph.syncController(context)!!
        assertTrue(controller.request(manual = true))
        withTimeout(5_000) { entered.await() }
        val worker = TestListenableWorkerBuilder<SyncWorker>(context).build()
        assertEquals(androidx.work.ListenableWorker.Result.retry(), worker.doWork())
        release.complete(Unit)
        withTimeout(5_000) { while (controller.status.value is SyncStatus.Running) delay(10) }
    }
}
