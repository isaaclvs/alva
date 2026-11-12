package net.ts.taild80ca0.atlas.twa.widgets

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import net.ts.taild80ca0.atlas.twa.access.*
import net.ts.taild80ca0.atlas.twa.online.*
import net.ts.taild80ca0.atlas.twa.ui.InMemoryHouseholdBinding
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class HabitualWidgetConfigurationTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var scenario: ActivityScenario<HabitualWidgetConfigureActivity>? = null
    private val widgetId = 867530
    @After fun cleanup() {
        scenario?.close(); AccessGraph.install(null); scope.cancel()
        HabitualWidgetSettings.delete(context, widgetId)
        HabitualWidgetSettings.delete(context, widgetId + 1)
    }
    @Test fun selectionOrderAndDraftSurviveRecreationAndSaveOnlyThisInstance() {
        val house = FakeHouse()
        var stored = StoredAccess(AccessCredential(1, "widget-test"))
        val access = HouseAccess(object : CredentialVault {
            override fun read() = VaultRead.Found(stored)
            override fun write(access: StoredAccess) { stored = access }
        }, object : HouseAccessApi {
            override suspend fun pair(password: String, deviceName: String, previousToken: String?): ApiResult<AccessCredential> = error("No pairing")
            override suspend fun identifyHousehold(credential: AccessCredential): ApiResult<HouseholdIdentity> = error("No HTTP")
        }, InMemoryHouseholdBinding())
        val repository = OnlineRepository(OnlineAccess.of(access), house, house, scope, pause = {})
        AccessGraph.install(access, online = repository)
        runBlocking {
            repository.refresh()
            repository.execute(CreateHabitual("Café"))
            repository.execute(CreateHabitual("Leite"))
        }
        val snapshot = (repository.state.value as OnlineState.Ready).snapshot
        val coffee = snapshot.habitualItems.first { it.name == "Café" }.publicId
        val milk = snapshot.habitualItems.first { it.name == "Leite" }.publicId
        HabitualWidgetSettings.save(context, widgetId + 1, HabitualWidgetSettings("Outro", listOf(coffee)))
        scenario = ActivityScenario.launch(Intent(context, HabitualWidgetConfigureActivity::class.java)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
        await { views(it.window.decorView).filterIsInstance<CheckBox>().size == 2 }
        scenario!!.onActivity { activity ->
            val views = views(activity.window.decorView)
            views.filterIsInstance<EditText>().single().setText("Cozinha")
            views.filterIsInstance<CheckBox>().first { it.text == "Leite" }.performClick()
        }
        scenario!!.onActivity { activity -> views(activity.window.decorView).filterIsInstance<CheckBox>().first { it.text == "Café" }.performClick() }
        // First selected is Leite; moving Café up gives an explicit order unrelated to selection order.
        scenario!!.onActivity { activity -> views(activity.window.decorView).first { it.contentDescription == "Mover Café para cima" }.performClick() }
        scenario!!.recreate()
        await { views(it.window.decorView).filterIsInstance<CheckBox>().count { it.isChecked } == 2 }
        scenario!!.onActivity { activity ->
            assertEquals("Cozinha", views(activity.window.decorView).filterIsInstance<EditText>().single().text.toString())
            assertTrue(views(activity.window.decorView).filterIsInstance<TextView>().any { it.text.toString() == "Prévia · Café  + Leite" })
            views(activity.window.decorView).filterIsInstance<Button>().first { it.text == "Salvar widget" }.performClick()
        }
        assertEquals(HabitualWidgetSettings("Cozinha", listOf(coffee, milk)), HabitualWidgetSettings.read(context, widgetId))
        assertEquals(HabitualWidgetSettings("Outro", listOf(coffee)), HabitualWidgetSettings.read(context, widgetId + 1))
        assertTrue(house.rows.isEmpty())
    }
    private fun views(root: View): List<View> = listOf(root) + if (root is ViewGroup) (0 until root.childCount).flatMap { views(root.getChildAt(it)) } else emptyList()
    private fun await(condition: (HabitualWidgetConfigureActivity) -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            var done = false
            scenario!!.onActivity { done = condition(it) }
            if (done) return
            Thread.sleep(50)
        }
        fail("Configuração não terminou de carregar")
    }
}
