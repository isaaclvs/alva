package net.ts.taild80ca0.atlas.twa.sync

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncControllerTest {
    private val scope = CoroutineScope(Job() + Dispatchers.Default)
    private var now = 1_000_000L

    private class Runner(val gate: CompletableDeferred<Unit>? = null) : SyncRunner {
        val runs = AtomicInteger()
        override suspend fun run(): SyncReport {
            runs.incrementAndGet()
            gate?.await()
            return SyncReport(SyncOutcome.Synced(runs.get().toLong()))
        }
    }

    private fun controller(runner: SyncRunner) = SyncController(runner, scope, autoIntervalMillis = 15_000, clock = { now })

    private fun settle(c: SyncController) = runBlocking {
        withTimeout(5_000) { while (c.status.value is SyncStatus.Running) kotlinx.coroutines.delay(5) }
    }

    @Test
    fun resumingAsksForOneCycleAndReportsItsResult() {
        val runner = Runner()
        val c = controller(runner)

        assertTrue(c.request(manual = false))
        settle(c)

        assertEquals(1, runner.runs.get())
        assertEquals(SyncStatus.Idle(SyncReport(SyncOutcome.Synced(1))), c.status.value)
    }

    @Test
    fun comingBackQuicklyDoesNotAskAgainButTheButtonAlwaysCan() {
        val runner = Runner()
        val c = controller(runner)
        c.request(manual = false)
        settle(c)

        now += 5_000
        assertFalse("a resume within the interval is ignored", c.request(manual = false))
        assertTrue("the button is never throttled", c.request(manual = true))
        settle(c)
        now += 20_000
        assertTrue("a later resume is welcome", c.request(manual = false))
        settle(c)

        assertEquals(3, runner.runs.get())
    }

    @Test
    fun nothingStartsWhileACycleRunsAndTheLastReportStaysVisible() {
        val gate = CompletableDeferred<Unit>()
        val runner = Runner(gate)
        val c = controller(runner)
        val first = SyncReport(SyncOutcome.Offline(TransientCause.NETWORK))
        // A previous result is shown while the next cycle runs.
        val seeded = controller(SyncRunner { first })
        seeded.request(manual = true)
        settle(seeded)
        assertEquals(SyncStatus.Idle(first), seeded.status.value)

        assertTrue(c.request(manual = true))
        assertFalse(c.request(manual = true))
        assertFalse(c.request(manual = false))
        assertTrue(c.status.value is SyncStatus.Running)
        gate.complete(Unit)
        settle(c)

        assertEquals(1, runner.runs.get())
    }

    @Test
    fun aCycleThatFindsAnotherRunningKeepsThePreviousReport() {
        val runner = SyncRunner { SyncReport(SyncOutcome.AlreadyRunning) }
        val c = controller(runner)
        c.request(manual = true)
        settle(c)
        assertEquals(SyncStatus.Idle(null), c.status.value)
    }

    @Test
    fun committedChangesBypassResumeThrottle() {
        val runner = Runner()
        val c = controller(runner)
        c.request(manual = false)
        settle(c)
        assertTrue(c.changed())
        settle(c)
        assertEquals(2, runner.runs.get())
    }

    @Test
    fun editsDuringACycleCoalesceIntoOneFollowUpWithoutOverlap() = runBlocking {
        val first = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        val runs = AtomicInteger()
        val c = controller(SyncRunner {
            if (runs.incrementAndGet() == 1) { entered.complete(Unit); first.await() }
            SyncReport(SyncOutcome.Synced(runs.get().toLong()))
        })
        c.request(manual = false)
        withTimeout(5_000) { entered.await() }
        repeat(20) { c.changed() }
        assertEquals(1, runs.get())
        first.complete(Unit)
        settle(c)
        assertEquals(2, runs.get())
        assertEquals(SyncStatus.Idle(SyncReport(SyncOutcome.Synced(2))), c.status.value)
    }

    @Test
    fun failedCycleDoesNotRetryItselfButConnectivityRestorationRetriesImmediately() {
        val runs = AtomicInteger()
        val c = controller(SyncRunner {
            runs.incrementAndGet()
            SyncReport(SyncOutcome.Offline(TransientCause.NETWORK))
        })
        val network = NetworkSyncTrigger(c)
        c.request(manual = false)
        settle(c)
        network.connected(true) // initial callback is throttled along with resume
        settle(c)
        assertEquals(1, runs.get())
        network.connected(false)
        network.connected(true)
        settle(c)
        repeat(5) { network.connected(true) }
        settle(c)
        assertEquals(2, runs.get())
    }

    @Test
    fun messagesAreInPortugueseAndNeverCarryCodesOrIds() {
        val reports = listOf(
            SyncReport(SyncOutcome.Synced(5)),
            SyncReport(SyncOutcome.NotConnected),
            SyncReport(SyncOutcome.Offline(TransientCause.TIMEOUT)),
            SyncReport(SyncOutcome.SnapshotHeld(listOf("5f0c2f0e-0000-4000-8000-000000000001"))),
            SyncReport(SyncOutcome.SnapshotNotInstalled(SnapshotRefusal.OLDER_REVISION)),
            SyncReport(SyncOutcome.SnapshotUnusable(SnapshotProblem.WRONG_SHAPE)),
            SyncReport(SyncOutcome.StorageFailed("SQLiteException")),
        )
        reports.forEach { report ->
            val text = SyncMessages.describe(report)
            assertTrue(text, text.isNotBlank() && !text.contains("5f0c2f0e") && !text.contains("SQLite") && !text.contains("WRONG_SHAPE") && !text.contains("TIMEOUT"))
        }
        assertEquals("Sincronizado.", SyncMessages.describe(reports[0]))
        assertTrue(SyncMessages.describe(reports[3]).contains("1 alteração enviada"))
    }

    @Test
    fun attentionAndStalledWorkAreSaidEvenWhenSynced() {
        val text = SyncMessages.describe(SyncReport(SyncOutcome.Synced(5), needsAttention = 2, stalled = 1))
        assertTrue(text, text.contains("2 alterações precisam de atenção"))
        assertTrue(text, text.contains("1 alteração ainda não pôde ser enviada"))
        assertFalse(SyncMessages.isTrouble(SyncReport(SyncOutcome.Synced(5))))
        assertTrue(SyncMessages.isTrouble(SyncReport(SyncOutcome.NotConnected)))
        assertFalse(SyncMessages.isTrouble(null))
    }
}
