package net.ts.taild80ca0.atlas.twa.widgets

import android.appwidget.AppWidgetManager
import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import org.junit.Rule
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
    @get:Rule val compose = createEmptyComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var scenario: ActivityScenario<HabitualWidgetConfigureActivity>? = null
    private val widgetId = 867530
    @After fun cleanup() {
        scenario?.close(); WidgetRuntime.resetForTests(context); AccessGraph.install(null); scope.cancel()
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
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("habit-$milk").fetchSemanticsNodes().size == 1 }
        compose.onNodeWithText("2 × 2").performClick()
        compose.onNodeWithTag("widget-preview").assertExists()
        compose.onNodeWithTag("widget-name").performTextReplacement("Cozinha")
        compose.onNodeWithTag("habit-$milk").performScrollTo().performClick()
        compose.onNodeWithTag("habit-$coffee").performScrollTo().performClick()
        compose.onNodeWithText("Reordenar atalhos", substring = true).performScrollTo().performClick()
        compose.onNodeWithContentDescription("Mover Café para cima").performScrollTo().performClick()
        scenario!!.recreate()
        compose.onNodeWithTag("widget-name").assertTextContains("Cozinha")
        compose.onNodeWithTag("habit-$milk").assertIsSelected()
        compose.onNodeWithTag("habit-$coffee").assertIsSelected()
        compose.onNodeWithText("Salvar widget").performClick()
        assertEquals(HabitualWidgetSettings("Cozinha", listOf(coffee, milk)), HabitualWidgetSettings.read(context, widgetId))
        assertEquals(HabitualWidgetSettings("Outro", listOf(coffee)), HabitualWidgetSettings.read(context, widgetId + 1))
        assertTrue(house.rows.isEmpty())
    }
}
