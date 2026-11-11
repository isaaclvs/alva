package net.ts.taild80ca0.atlas.twa.sync

import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import kotlinx.coroutines.runBlocking
import net.ts.taild80ca0.atlas.twa.access.AccessCredential
import net.ts.taild80ca0.atlas.twa.access.ServerEndpoint
import net.ts.taild80ca0.atlas.twa.data.list.CommandStatus
import net.ts.taild80ca0.atlas.twa.data.list.IntentResult
import net.ts.taild80ca0.atlas.twa.data.list.ItemStatus
import net.ts.taild80ca0.atlas.twa.data.list.ListIntent
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * Two devices, each with its own real Room file and the real HTTP stack of the emulator, syncing through a REAL Rails
 * (RAILS_ENV=test, a throwaway SQLite database, fixtures only): A adds offline, syncs, B receives, B marks, syncs, A
 * converges; and an answer lost after the server committed gives no second effect. It runs only under
 * `script/android_rails_sync_check`, which starts Rails, puts it on the emulator's loopback with `adb reverse` and passes
 * `railsUrl`, `syncTokenA`, `syncTokenB` and `householdId` as instrumentation arguments; anywhere else it is skipped (and
 * the script refuses a skipped run).
 *
 * What it does not prove: the Compose screens (their own tests), a physical device, a real network path (`adb reverse` is
 * loopback) or a long-lived server with other traffic.
 */
class RailsSyncConvergenceTest {
    private val args = InstrumentationRegistry.getArguments()
    private val rigs = mutableListOf<SyncRig>()
    private val closeables = mutableListOf<AutoCloseable>()
    private lateinit var url: String
    private lateinit var house: String
    private lateinit var tokenA: String
    private lateinit var tokenB: String

    @Before
    fun connect() {
        assumeTrue("needs script/android_rails_sync_check", args.getString("railsUrl") != null)
        url = args.getString("railsUrl")!!
        assertTrue(url, url.startsWith("http://127.0.0.1:"))
        house = args.getString("householdId")!!
        tokenA = args.getString("syncTokenA")!!
        tokenB = args.getString("syncTokenB")!!
    }

    @After
    fun stop() {
        rigs.forEach { it.close() }
        closeables.forEach { it.close() }
    }

    private fun endpoint(through: Int? = null) = ServerEndpoint.parse(through?.let { "http://127.0.0.1:$it" } ?: url)

    private fun device(token: String, through: Int? = null): SyncRig = SyncRig(
        house, HttpCommandTransport(endpoint(through), 5_000, 20_000), HttpSnapshotTransport(endpoint(), 5_000, 30_000), token,
    ).also { rigs += it }

    private fun newName() = "Teste " + UUID.randomUUID().toString().filter { it.isLetter() }.take(12)

    /** What Rails holds, read with the same client, as device A's credential. */
    private fun server(): ServerSnapshot {
        val outcome = runBlocking { HttpSnapshotTransport(endpoint(), 5_000, 30_000).fetch(AccessCredential(9, tokenA)) }
        assertTrue("$outcome", outcome is SnapshotOutcome.Received)
        return (outcome as SnapshotOutcome.Received).snapshot
    }

    private suspend fun SyncRig.add(vararg names: String) {
        assertTrue(store.execute(ListIntent.AddItems(names.toList())) is IntentResult.Saved)
    }

    private suspend fun SyncRig.mark(name: String, desired: ItemStatus) {
        val item = state().items.first { it.name == name }
        assertTrue(store.execute(ListIntent.SetItemState(item.publicId, desired)) is IntentResult.Saved)
    }

    private suspend fun SyncRig.sync(): SyncReport = cycle.run().also { assertTrue("$it", it.outcome is SyncOutcome.Synced) }

    private suspend fun SyncRig.has(name: String): Boolean? = state().items.firstOrNull { it.name == name }?.let { it.status == ItemStatus.PURCHASED }

    @Test
    fun aAddsOfflineBReceivesBMarksAAndBConverge() = runBlocking<Unit> {
        val a = device(tokenA)
        val b = device(tokenB)
        val name = newName()
        // Both devices start from what the fixture household has: the first sync is the bootstrap.
        a.sync()
        b.sync()
        assertEquals(a.state().confirmedRevision, b.state().confirmedRevision)

        a.add(name) // "offline": nothing has reached the server yet
        assertEquals(null, b.has(name))
        assertTrue(server().listItems.none { it.name == name })

        a.sync()
        b.sync()
        assertEquals("B received what A added", false, b.has(name))

        b.mark(name, ItemStatus.PURCHASED)
        b.sync()
        a.sync()

        assertEquals("A converged on B's mark", true, a.has(name))
        assertEquals(true, b.has(name))
        val onServer = server().listItems.filter { it.name == name }
        assertEquals("one item, once", 1, onServer.size)
        assertEquals(1, onServer.single().status)
        assertEquals(server().revision, a.state().confirmedRevision)
        assertEquals(server().revision, b.state().confirmedRevision)
        assertEquals("nothing left to send on either device", 0, a.db.commands().count() + b.db.commands().count())
    }

    @Test
    fun anAddAndAMarkMadeTogetherOfflineArriveInOrderAndReachTheOtherDevice() = runBlocking<Unit> {
        val a = device(tokenA)
        val b = device(tokenB)
        val name = newName()
        a.add(name)
        a.mark(name, ItemStatus.PURCHASED)

        val report = a.sync()
        b.sync()

        assertEquals(2, report.acknowledged)
        assertEquals(true, a.has(name))
        assertEquals("the dependent mark was applied on top of the add it depends on", true, b.has(name))
    }

    @Test
    fun theSameNameOnBothPhonesIsOneItemOnTheServerAndOnBoth() = runBlocking<Unit> {
        val a = device(tokenA)
        val b = device(tokenB)
        val name = newName()
        a.add(name)
        b.add(name)

        a.sync()
        b.sync() // Rails keeps A's item; B's claimed UUID becomes an alias
        b.mark(name, ItemStatus.PURCHASED)
        b.sync()
        a.sync()

        assertEquals(1, server().listItems.count { it.name == name })
        assertEquals(true, a.has(name))
        assertEquals(true, b.has(name))
        assertEquals(1, b.db.aliases().all().size)
    }

    @Test
    fun anAnswerLostAfterTheServerCommittedHasNoSecondEffect() = runBlocking<Unit> {
        val proxy = ResponseDroppingProxy(Regex(":(\\d+)$").find(url)!!.groupValues[1].toInt()).also { closeables += it }
        val a = device(tokenA, through = proxy.port)
        val b = device(tokenB)
        val name = newName()
        a.sync()
        a.add(name)
        val revisionBefore = server().revision

        proxy.dropNextResponses(1)
        val lost = a.cycle.run()

        assertEquals(SyncOutcome.Offline(TransientCause.NETWORK), lost.outcome)
        assertEquals("Rails committed it", 1, server().listItems.count { it.name == name })
        assertEquals(revisionBefore + 1, server().revision)
        val command = a.db.commands().inOrder().single()
        assertEquals(CommandStatus.IN_FLIGHT.name, command.status)
        assertEquals("the device is unsure and says so: no snapshot was installed", revisionBefore, a.state().confirmedRevision)

        val retried = a.sync()

        assertEquals(1, retried.acknowledged)
        assertEquals(2, proxy.requestBodies.size)
        assertArrayEquals("the very same bytes", proxy.requestBodies[0], proxy.requestBodies[1])
        assertEquals("no second effect", 1, server().listItems.count { it.name == name })
        assertEquals(revisionBefore + 1, server().revision)
        assertEquals(false, a.has(name))
        assertEquals(0, a.db.commands().count())
        b.sync()
        assertEquals("B sees it exactly once", 1, b.state().items.count { it.name == name })
    }
}
