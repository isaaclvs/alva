package net.ts.taild80ca0.atlas.twa

import android.widget.FrameLayout
import android.widget.TextView
import android.view.View
import android.view.ViewGroup
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import net.ts.taild80ca0.atlas.twa.access.*
import net.ts.taild80ca0.atlas.twa.online.*
import net.ts.taild80ca0.atlas.twa.sync.SnapshotFixtures
import net.ts.taild80ca0.atlas.twa.ui.InMemoryHouseholdBinding
import net.ts.taild80ca0.atlas.twa.widgets.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

/** Applies real RemoteViews and follows the PendingIntent through the installed receiver. */
class WidgetInteractionTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val house = FakeHouse()
    private val widgetId = 9201

    private fun connect(): OnlineRepository {
        WidgetRuntime.resetForTests(context)
        val vault = object : CredentialVault {
            override fun read() = VaultRead.Found(StoredAccess(AccessCredential(1, "widget-test-only")))
            override fun write(access: StoredAccess) = Unit
        }
        val api = object : HouseAccessApi {
            override suspend fun pair(password: String, deviceName: String, previousToken: String?): ApiResult<AccessCredential> = error("No pairing")
            override suspend fun identifyHousehold(credential: AccessCredential): ApiResult<HouseholdIdentity> = error("No access HTTP")
        }
        val access = HouseAccess(vault, api, InMemoryHouseholdBinding())
        val repository = OnlineRepository(OnlineAccess.of(access), house, house, scope, pause = {})
        AccessGraph.install(access, online = repository)
        WidgetRuntime.bind(context, repository)
        runBlocking { access.refresh(); repository.refresh() }
        await { WidgetRuntime.snapshot(context)?.habitualItems?.size == house.habituals.size }
        return repository
    }

    private fun await(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(40)
        }
        fail("Widget did not reach expected state")
    }

    private fun texts(view: View): List<TextView> = buildList {
        if (view is TextView) add(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) addAll(texts(view.getChildAt(index)))
    }

    @After fun cleanup() {
        HabitualWidgetSettings.delete(context, widgetId)
        WidgetRuntime.resetForTests(context)
        AccessGraph.install(null)
        scope.cancel()
    }

    @Test fun shortcutAddsThroughItsPendingIntentAndRemainsInTheSameSlot() {
        val milkId = "11111111-2222-4333-8444-555555555555"
        house.habituals += SnapshotFixtures.habitual(milkId, "Leite")
        connect()
        HabitualWidgetSettings.save(context, widgetId, HabitualWidgetSettings("Cozinha", listOf(milkId)))
        instrumentation.runOnMainSync {
            val root = HabitualWidgetProvider.render(context, widgetId, 300, 200).apply(context, FrameLayout(context))
            assertTrue(texts(root).single { it.contentDescription == "Adicionar Leite" }.performClick())
        }
        await { house.rows.singleOrNull()?.name == "Leite" && !WidgetRuntime.busyHabitual(context, milkId) }
        await { WidgetRuntime.snapshot(context)?.listItems?.singleOrNull()?.name == "Leite" }
        instrumentation.runOnMainSync {
            val root = HabitualWidgetProvider.render(context, widgetId, 300, 200).apply(context, FrameLayout(context))
            assertEquals(1, texts(root).count { it.contentDescription == "Leite já está na lista" })
        }
        assertEquals(listOf(milkId), HabitualWidgetSettings.read(context, widgetId).ids)
        assertEquals(1, house.sent.size)
    }

    @Test fun listMarksTheObservedVersionAndCanUndoTheReceipt() {
        val milk = house.seed("Leite", lock = 4)
        connect()
        runBlocking { WidgetRuntime.refreshNow(context); WidgetRuntime.refreshNow(context) }
        assertNull("An unchanged snapshot must finish the refresh indicator", WidgetRuntime.status(context))
        val snapshot = WidgetRuntime.snapshot(context)!!
        instrumentation.runOnMainSync {
            val root = ShoppingListWidgetProvider.render(context, widgetId).apply(context, FrameLayout(context))
            assertTrue(texts(root).any { it.text == "1 pendente" })
            val factory = ListFactory(context)
            factory.onDataSetChanged()
            assertEquals(1, factory.count)
            val row = factory.getViewAt(0)!!.apply(context, FrameLayout(context))
            assertTrue(texts(row).any { it.contentDescription == "Marcar Leite como comprado" })
        }
        runBlocking { WidgetRuntime.mark(context, milk.id, 4, snapshot.householdPublicId) }
        assertEquals("purchased", milk.status)
        val undo = WidgetRuntime.undo
        assertNotNull(undo)
        runBlocking { WidgetRuntime.undoMark(context, undo!!.token) }
        assertEquals("pending", milk.status)
        assertNull(WidgetRuntime.undo)
    }

    @Test fun listDoesNotSilentlyReplaceTheVersionShownInAnOldWidget() {
        val milk = house.seed("Leite", lock = 4)
        val repository = connect()
        val household = WidgetRuntime.snapshot(context)!!.householdPublicId
        milk.lock++ // Another device corrected this item after the widget was drawn.
        runBlocking {
            repository.refresh()
            WidgetRuntime.mark(context, milk.id, 4, household)
        }
        assertEquals("pending", milk.status)
        assertNull(WidgetRuntime.undo)
        assertTrue(WidgetRuntime.status(context).orEmpty().contains("mudou"))
    }

    @Test fun failedMarkKeepsThePendingItemAndRevokedAccessClearsWidgetData() {
        val milk = house.seed("Leite")
        val repository = connect()
        val household = WidgetRuntime.snapshot(context)!!.householdPublicId
        house.commandSteps.addAll(List(3) { FakeHouse.Step.NetworkDown })
        runBlocking { WidgetRuntime.mark(context, milk.id, milk.lock, household) }
        assertEquals("pending", milk.status)
        assertNull(WidgetRuntime.undo)
        assertTrue(WidgetRuntime.status(context).orEmpty().contains("Sem conexão"))
        house.snapshotSteps += FakeHouse.Step.Unauthorized
        runBlocking { repository.refresh() }
        await { WidgetRuntime.snapshot(context) == null }
        instrumentation.runOnMainSync {
            val root = ShoppingListWidgetProvider.render(context, widgetId).apply(context, FrameLayout(context))
            assertFalse(texts(root).any { it.text == "Leite" })
        }
    }
}
