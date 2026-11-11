package net.ts.taild80ca0.atlas.twa.sync

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import net.ts.taild80ca0.atlas.twa.data.list.IntentResult
import net.ts.taild80ca0.atlas.twa.data.list.InvalidReason
import net.ts.taild80ca0.atlas.twa.data.list.ListIntent
import net.ts.taild80ca0.atlas.twa.data.list.ListState
import net.ts.taild80ca0.atlas.twa.data.list.ShoppingListStore
import org.junit.Assert.*
import org.junit.Test

class BackgroundSyncPolicyTest {
    @Test
    fun onlyNetworkTimeoutRateLimitServerFailureAndConcurrentCycleRetry() {
        TransientCause.entries.forEach { cause ->
            assertEquals(cause.name, cause != TransientCause.TLS, SyncOutcome.Offline(cause).shouldRetryInBackground())
        }
        assertTrue(SyncOutcome.AlreadyRunning.shouldRetryInBackground())
        listOf(
            SyncOutcome.Synced(3), SyncOutcome.NotConnected, SyncOutcome.SnapshotHeld(listOf("uncertain")),
            SyncOutcome.SnapshotNotInstalled(SnapshotRefusal.OLDER_REVISION),
            SyncOutcome.SnapshotUnusable(SnapshotProblem.WRONG_SHAPE), SyncOutcome.StorageFailed("SQLiteException"),
        ).forEach { assertFalse("$it", it.shouldRetryInBackground()) }
        // A synced snapshot can coexist with preserved conflicts; they must never cause automatic retries.
        assertFalse(SyncReport(SyncOutcome.Synced(3), needsAttention = 2, stalled = 1).outcome.shouldRetryInBackground())
    }

    @Test
    fun onlyASavedMutationSchedulesAndOnlyAfterTheCommitReturns() = runBlocking {
        val saved = IntentResult.Saved("command", listOf("item"))
        listOf(
            saved, IntentResult.Unchanged, IntentResult.AlreadyOnList(emptyList()),
            IntentResult.Invalid(InvalidReason.NO_NAMES), IntentResult.Failed(IllegalStateException()),
        ).forEach { result ->
            val events = mutableListOf<String>()
            val local = object : ShoppingListStore {
                override fun observe() = emptyFlow<ListState>()
                override suspend fun execute(intent: ListIntent): IntentResult {
                    events += "commit finished"
                    return result
                }
            }
            val store = SyncingShoppingListStore(local, SyncScheduler { events += "wake-up persisted" })
            assertSame(result, store.execute(ListIntent.AddText("Leite")))
            assertEquals(
                if (result is IntentResult.Saved) listOf("commit finished", "wake-up persisted") else listOf("commit finished"),
                events,
            )
        }
    }

    @Test
    fun foregroundAndBackgroundShareTheGateAndPublishTheWorkersResult() = runBlocking {
        val scope = CoroutineScope(Job() + Dispatchers.Default)
        try {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var calls = 0
            val controller = SyncController(SyncRunner {
                calls++
                entered.complete(Unit)
                release.await()
                SyncReport(SyncOutcome.Synced(9))
            }, scope)
            val worker = async(Dispatchers.Default) { controller.runInBackground() }
            entered.await()
            assertFalse(controller.request(manual = true))
            assertEquals(SyncOutcome.AlreadyRunning, controller.runInBackground().outcome)
            assertTrue(controller.status.value is SyncStatus.Running)
            release.complete(Unit)
            assertEquals(SyncOutcome.Synced(9), worker.await().outcome)
            assertEquals(1, calls)
            assertEquals(SyncStatus.Idle(SyncReport(SyncOutcome.Synced(9))), controller.status.value)
            assertTrue(controller.request(manual = true))
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun stoppingAWorkerReleasesTheControllerForItsReplacement() = runBlocking {
        val scope = CoroutineScope(Job() + Dispatchers.Default)
        val entered = CompletableDeferred<Unit>()
        var runs = 0
        try {
            val controller = SyncController(SyncRunner {
                if (++runs == 1) {
                    entered.complete(Unit)
                    CompletableDeferred<Unit>().await()
                }
                SyncReport(SyncOutcome.Synced(4))
            }, scope)
            val stopped = async(Dispatchers.Default) { controller.runInBackground() }
            entered.await()
            stopped.cancelAndJoin()
            assertTrue(controller.status.value is SyncStatus.Idle)
            assertEquals(SyncOutcome.Synced(4), controller.runInBackground().outcome)
            assertEquals(2, runs)
        } finally {
            scope.cancel()
        }
    }

}
