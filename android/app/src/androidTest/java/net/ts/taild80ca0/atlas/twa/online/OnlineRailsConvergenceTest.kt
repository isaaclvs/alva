package net.ts.taild80ca0.atlas.twa.online

import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import net.ts.taild80ca0.atlas.twa.access.AccessCredential
import net.ts.taild80ca0.atlas.twa.access.ServerEndpoint
import net.ts.taild80ca0.atlas.twa.data.list.AddOutcome
import net.ts.taild80ca0.atlas.twa.data.list.ItemStatus
import net.ts.taild80ca0.atlas.twa.sync.HttpCommandTransport
import net.ts.taild80ca0.atlas.twa.sync.HttpSnapshotTransport
import net.ts.taild80ca0.atlas.twa.sync.ReceiptStatus
import net.ts.taild80ca0.atlas.twa.sync.ResponseDroppingProxy
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * Two phones — two [OnlineRepository] objects with their own credentials and no shared memory — talking to a
 * REAL Rails (RAILS_ENV=test, a throwaway SQLite database, fixtures only) through the emulator's HTTP stack:
 * A adds and marks and B sees it; the same name on both phones is one item; a stale mark is refused and the list
 * reloaded; an answer lost after Rails committed is retried with the same bytes and has no second effect. It runs
 * only under `script/android_rails_online_check`, which starts Rails, puts it on the emulator's loopback with
 * `adb reverse` and passes `railsUrl`, `syncTokenA`, `syncTokenB` as instrumentation arguments; anywhere else it
 * is skipped (and the script refuses a skipped run).
 *
 * What it does not prove: the Compose screens (ShoppingScreensTest), a physical device, or a network that is not
 * loopback.
 */
class OnlineRailsConvergenceTest {
    private val args = InstrumentationRegistry.getArguments()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val closeables = mutableListOf<AutoCloseable>()
    private lateinit var url: String
    private lateinit var tokenA: String
    private lateinit var tokenB: String

    @Before fun connect() {
        assumeTrue("needs script/android_rails_online_check", args.getString("railsUrl") != null)
        url = args.getString("railsUrl")!!
        assertTrue(url, url.startsWith("http://127.0.0.1:"))
        tokenA = args.getString("syncTokenA")!!
        tokenB = args.getString("syncTokenB")!!
    }

    @After fun stop() {
        closeables.forEach { it.close() }
        scope.cancel()
    }

    private class Phone(val repository: OnlineRepository, val unauthorized: MutableList<AccessCredential>) {
        val ready get() = repository.state.value as OnlineState.Ready
        val items get() = ready.snapshot.listItems
        fun item(name: String) = items.firstOrNull { it.name == name }
    }

    private fun endpoint(port: Int? = null) = ServerEndpoint.parse(port?.let { "http://127.0.0.1:$it" } ?: url)

    private fun phone(token: String, commandsThrough: Int? = null, endpointOverride: ServerEndpoint? = null): Phone {
        val unauthorized = mutableListOf<AccessCredential>()
        val credential = AccessCredential(1, token)
        val access = object : OnlineAccess {
            override suspend fun credential() = credential
            override suspend fun reportUnauthorized(credential: AccessCredential) { unauthorized += credential }
        }
        val repository = OnlineRepository(
            access,
            HttpSnapshotTransport(endpointOverride ?: endpoint(), 5_000, 30_000),
            HttpCommandTransport(endpointOverride ?: endpoint(commandsThrough), 5_000, 20_000),
            scope,
            pause = { delay(100) },
        )
        return Phone(repository, unauthorized)
    }

    private fun newName() = "Teste " + UUID.randomUUID().toString().filter { it.isLetter() }.take(12)

    private suspend fun Phone.add(name: String) = repository.execute(AddItems.fromText(name))

    private suspend fun Phone.mark(name: String, desired: ItemStatus) = repository.execute(SetItemState(item(name)!!.publicId, desired))

    @Test fun correctingARecentAddsCategoryTeachesRailsAndTheOtherPhoneSeesIt() = runBlocking<Unit> {
        val a = phone(tokenA)
        val b = phone(tokenB)
        val name = newName()
        a.repository.refresh()
        assertTrue(a.add(name) is ActionResult.Success)
        val original = a.item(name)!!
        val category = a.ready.snapshot.categories.first { it.publicId != original.categoryPublicId }

        val corrected = a.repository.execute(CorrectItem(original.publicId, categoryPublicId = category.publicId))

        assertTrue("$corrected", corrected is ActionResult.Success)
        assertEquals(category.publicId, a.item(name)!!.categoryPublicId)
        b.repository.refresh()
        assertEquals(category.publicId, b.item(name)!!.categoryPublicId)
        assertTrue(b.ready.snapshot.dictionaryRevision > 0)
        assertTrue(b.ready.snapshot.catalogItems.any { it.name == name && it.categoryPublicId == category.publicId && it.learned })
        assertTrue(a.mark(name, ItemStatus.PURCHASED) is ActionResult.Success)
        b.repository.refresh()
        val revived = b.add(name) as ActionResult.Success
        assertEquals(listOf(AddOutcome.REVIVED), revived.receipt.addedItems().map { it.outcome })
        assertEquals(category.publicId, b.item(name)!!.categoryPublicId)
    }

    @Test fun aAddsAndMarksAndBSeesEachStepAndMarksBack() = runBlocking<Unit> {
        val a = phone(tokenA)
        val b = phone(tokenB)
        val name = newName()
        a.repository.refresh()
        b.repository.refresh()
        assertEquals(a.ready.snapshot.revision, b.ready.snapshot.revision)

        assertTrue(a.add(name) is ActionResult.Success)
        assertEquals("B has not looked yet", null, b.item(name))
        b.repository.refresh()
        assertEquals("B sees what A added", 0, b.item(name)!!.status)

        assertTrue(a.mark(name, ItemStatus.PURCHASED) is ActionResult.Success)
        b.repository.refresh()
        assertEquals("B sees A's mark", 1, b.item(name)!!.status)

        assertTrue(b.mark(name, ItemStatus.PENDING) is ActionResult.Success)
        a.repository.refresh()
        assertEquals("A sees B's unmark", 0, a.item(name)!!.status)
        assertEquals(1, a.items.count { it.name == name })
        assertEquals("both phones show the server's list", a.item(name)!!.lockVersion, b.item(name)!!.lockVersion)
    }

    @Test fun undoingAMarkRestoresTheItemOnRailsAndBSeesIt() = runBlocking<Unit> {
        val a = phone(tokenA)
        val b = phone(tokenB)
        val name = newName()
        a.add(name)

        // Mark, then undo with the version the mark left: the item is pending again, with no purchase instant.
        val marked = (a.mark(name, ItemStatus.PURCHASED) as ActionResult.Success).receipt.markedItem()!!
        val undone = a.repository.execute(UndoItemState(marked.publicId, ItemStatus.PENDING, null, marked.lockVersion))
        assertTrue("$undone", undone is ActionResult.Success)
        assertEquals(0, a.item(name)!!.status)
        assertEquals(null, a.item(name)!!.purchasedAtMicros)
        b.repository.refresh()
        assertEquals("B sees the item as it was", 0, b.item(name)!!.status)

        // Unmark a purchased item, then undo: the original purchase instant comes back, to the microsecond.
        a.mark(name, ItemStatus.PURCHASED)
        val before = a.item(name)!!
        val unmarked = (a.mark(name, ItemStatus.PENDING) as ActionResult.Success).receipt.markedItem()!!
        val restored = a.repository.execute(UndoItemState(before.publicId, ItemStatus.PURCHASED, before.purchasedAtMicros, unmarked.lockVersion))
        assertTrue("$restored", restored is ActionResult.Success)
        assertEquals(1, a.item(name)!!.status)
        assertEquals(before.purchasedAtMicros, a.item(name)!!.purchasedAtMicros)
    }

    @Test fun anUndoAfterTheOtherPhoneChangedTheItemIsRefusedAndNothingIsOverwritten() = runBlocking<Unit> {
        val a = phone(tokenA)
        val b = phone(tokenB)
        val name = newName()
        a.add(name)
        val marked = (a.mark(name, ItemStatus.PURCHASED) as ActionResult.Success).receipt.markedItem()!!
        b.repository.refresh()
        assertTrue(b.mark(name, ItemStatus.PENDING) is ActionResult.Success) // B unmarked it meanwhile
        b.mark(name, ItemStatus.PURCHASED)

        val refused = a.repository.execute(UndoItemState(marked.publicId, ItemStatus.PENDING, null, marked.lockVersion))

        assertEquals(ActionResult.Refused("precondition_failed"), refused)
        assertEquals("B's later state stands", 1, a.item(name)!!.status)
    }

    @Test fun theSameNameOnBothPhonesIsOneItem() = runBlocking<Unit> {
        val a = phone(tokenA)
        val b = phone(tokenB)
        val name = newName()
        a.repository.refresh()
        b.repository.refresh() // B has not seen A's add when it adds the same name

        assertTrue(a.add(name) is ActionResult.Success)
        val second = b.add(name) as ActionResult.Success

        assertEquals(listOf(AddOutcome.DUPLICATE), second.receipt.addedItems().map { it.outcome })
        assertEquals("Nada adicionado · $name já estava na lista", OnlineMessages.added(second.receipt.addedItems()))
        assertEquals(1, b.items.count { it.name == name })
        a.repository.refresh()
        assertEquals(a.item(name)!!.publicId, b.item(name)!!.publicId)
    }

    @Test fun aMarkMadeOnAStaleListIsRefusedAndTheListIsReloaded() = runBlocking<Unit> {
        val a = phone(tokenA)
        val b = phone(tokenB)
        val name = newName()
        a.add(name)
        b.repository.refresh() // B sees the item at version 0
        a.mark(name, ItemStatus.PURCHASED)
        a.mark(name, ItemStatus.PENDING) // version 2 on the server

        val refused = b.mark(name, ItemStatus.PURCHASED)

        assertEquals(ActionResult.Refused("precondition_failed"), refused)
        assertEquals("nothing was overwritten", 0, b.item(name)!!.status)
        assertEquals("B now holds the server's version", 2, b.item(name)!!.lockVersion)
        assertTrue(b.mark(name, ItemStatus.PURCHASED) is ActionResult.Success)
    }

    @Test fun anAnswerLostAfterRailsCommittedIsRetriedWithTheSameBytesAndHasNoSecondEffect() = runBlocking<Unit> {
        val proxy = ResponseDroppingProxy(Regex(":(\\d+)$").find(url)!!.groupValues[1].toInt()).also { closeables += it }
        val a = phone(tokenA, commandsThrough = proxy.port)
        val b = phone(tokenB)
        val name = newName()
        a.repository.refresh()
        b.repository.refresh()
        val revisionBefore = a.ready.snapshot.revision

        proxy.dropNextResponses(1)
        val result = a.add(name) as ActionResult.Success

        assertEquals("Rails answered the retry with the first result", ReceiptStatus.DUPLICATE, result.receipt.status)
        assertEquals(2, proxy.requestBodies.size)
        assertArrayEquals("the very same bytes", proxy.requestBodies[0], proxy.requestBodies[1])
        assertEquals(1, a.items.count { it.name == name })
        assertEquals("one revision, once", revisionBefore + 1, a.ready.snapshot.revision)
        b.repository.refresh()
        assertEquals("B sees it exactly once", 1, b.items.count { it.name == name })
    }

    @Test fun aRefusedCredentialIsNoAccessAndIsReported() = runBlocking<Unit> {
        val intruder = phone("not-a-credential-" + UUID.randomUUID())
        intruder.repository.refresh()
        assertEquals(OnlineState.Failed(OnlineProblem.NO_ACCESS), intruder.repository.state.value)
        assertEquals(1, intruder.unauthorized.size)
    }

    @Test fun aServerThatCannotBeReachedIsOfflineAndNothingIsStored() = runBlocking<Unit> {
        val nobody = phone(tokenA, endpointOverride = endpoint(1)) // nothing listens on port 1
        nobody.repository.refresh()
        assertEquals(OnlineState.Failed(OnlineProblem.OFFLINE), nobody.repository.state.value)
        assertEquals(ActionResult.Offline, nobody.repository.execute(AddItems.fromText(newName())))
    }
}
