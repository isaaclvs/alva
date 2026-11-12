package net.ts.taild80ca0.atlas.twa

import android.graphics.drawable.GradientDrawable
import androidx.compose.ui.graphics.toArgb
import net.ts.taild80ca0.atlas.twa.preferences.DevicePreferencesStore
import net.ts.taild80ca0.atlas.twa.ui.theme.AlvaColor
import net.ts.taild80ca0.atlas.twa.ui.theme.AlvaPalettes
import androidx.test.platform.app.InstrumentationRegistry
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.*
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import kotlinx.coroutines.*
import net.ts.taild80ca0.atlas.twa.access.*
import net.ts.taild80ca0.atlas.twa.ui.InMemoryHouseholdBinding
import net.ts.taild80ca0.atlas.twa.online.*
import net.ts.taild80ca0.atlas.twa.sync.CommandOutcome
import net.ts.taild80ca0.atlas.twa.sync.CommandTransport
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class QuickPanelTest {
    private val house = FakeHouse()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var scenario: ActivityScenario<QuickAddActivity>? = null
    private var stored: StoredAccess? = StoredAccess(AccessCredential(1, "test-only"))
    private lateinit var access: HouseAccess
    private fun launch(commands: CommandTransport = house): OnlineRepository {
        val vault = object : CredentialVault {
            override fun read() = stored?.let { VaultRead.Found(it) } ?: VaultRead.Empty
            override fun write(access: StoredAccess) { stored = access }
        }
        val api = object : HouseAccessApi {
            override suspend fun pair(password: String, deviceName: String, previousToken: String?): ApiResult<AccessCredential> = error("No pairing inside widget")
            override suspend fun identifyHousehold(credential: AccessCredential): ApiResult<HouseholdIdentity> = error("No access HTTP")
        }
        access = HouseAccess(vault, api, InMemoryHouseholdBinding())
        val repository = OnlineRepository(OnlineAccess.of(access), house, commands, scope, pause = {})
        AccessGraph.install(access, online = repository)
        scenario = ActivityScenario.launch(QuickAddActivity::class.java)
        await { activity -> activity.findViewById<Button>(R.id.quick_add_submit).isEnabled }
        return repository
    }
    private fun await(condition: (QuickAddActivity) -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        do {
            var done = false
            scenario!!.onActivity { done = condition(it) }
            if (done) return
            Thread.sleep(50)
        } while (System.currentTimeMillis() < deadline)
        fail("Quick panel did not reach the expected state")
    }
    @After fun cleanup() {
        scenario?.close(); AccessGraph.install(null); scope.cancel()
        runBlocking { DevicePreferencesStore(InstrumentationRegistry.getInstrumentation().targetContext).reset() }
    }

    @Test fun panelFollowsTheDeviceThemeAndAccent() {
        val preferences = DevicePreferencesStore(InstrumentationRegistry.getInstrumentation().targetContext)
        runBlocking { preferences.dark(false); preferences.color(AlvaColor.Pink) }
        launch()
        val palette = AlvaPalettes.PinkLight
        await { it.findViewById<EditText>(R.id.quick_add_product).currentTextColor == palette.ink.toArgb() }
        scenario!!.onActivity {
            val submit = it.findViewById<Button>(R.id.quick_add_submit)
            assertEquals(palette.accentInk.toArgb(), submit.currentTextColor)
            assertEquals(palette.accent.toArgb(), (submit.background as GradientDrawable).color!!.defaultColor)
            assertEquals(palette.surface.toArgb(), (it.findViewById<android.view.View>(R.id.quick_add_panel).background as GradientDrawable).color!!.defaultColor)
        }
        runBlocking { preferences.dark(true); preferences.color(AlvaColor.Teal) }
        await { it.findViewById<EditText>(R.id.quick_add_product).currentTextColor == AlvaPalettes.TealDark.ink.toArgb() }
    }

    @Test fun noAccessOffersTheMainAppWithoutPasswordOrTyping() {
        stored = null
        launch()
        onView(withId(R.id.quick_add_password)).check(matches(withEffectiveVisibility(Visibility.GONE)))
        onView(withId(R.id.quick_add_product)).check(matches(withEffectiveVisibility(Visibility.GONE)))
        onView(withId(R.id.quick_add_submit)).check(matches(withText("Abrir app")))
    }
    @Test fun networkFailureKeepsDraftAndRecreationAllowsRetry() {
        launch()
        onView(withId(R.id.quick_add_product)).check(matches(hasFocus()))
        house.commandSteps.addAll(List(3) { FakeHouse.Step.NetworkDown })
        // Include every retry configured by the repository.
        house.commandSteps += FakeHouse.Step.NetworkDown
        onView(withId(R.id.quick_add_product)).perform(replaceText("Leite, Pão"))
        onView(withId(R.id.quick_add_product)).perform(closeSoftKeyboard())
        onView(withId(R.id.quick_add_submit)).perform(scrollTo(), click())
        await { it.findViewById<TextView>(R.id.quick_add_status).text.contains("Sem conexão") }
        onView(withId(R.id.quick_add_product)).check(matches(withText("Leite, Pão")))
        scenario!!.recreate()
        await { it.findViewById<Button>(R.id.quick_add_submit).isEnabled }
        onView(withId(R.id.quick_add_product)).check(matches(withText("Leite, Pão")))
        house.commandSteps.clear()
        onView(withId(R.id.quick_add_product)).perform(pressImeActionButton())
        await { it.findViewById<TextView>(R.id.quick_add_success_message).text == "Adicionado à lista" }
        assertEquals(listOf("Leite", "Pão"), house.rows.map { it.name })
    }
    @Test fun rotationRetainsTheInFlightSendAndRejectsAnotherImeSubmission() {
        val release = CompletableDeferred<Unit>()
        val calls = java.util.concurrent.atomic.AtomicInteger()
        val transport = object : CommandTransport {
            override suspend fun send(credential: AccessCredential, commandId: String, document: String): CommandOutcome {
                calls.incrementAndGet(); release.await(); return house.send(credential, commandId, document)
            }
        }
        launch(transport)
        onView(withId(R.id.quick_add_product)).perform(replaceText("Café; Ovos"), pressImeActionButton())
        await { calls.get() == 1 }
        scenario!!.recreate()
        scenario!!.onActivity {
            assertFalse(it.findViewById<Button>(R.id.quick_add_submit).isEnabled)
            it.findViewById<EditText>(R.id.quick_add_product).onEditorAction(android.view.inputmethod.EditorInfo.IME_ACTION_DONE)
        }
        assertEquals(1, calls.get())
        release.complete(Unit)
        await { it.findViewById<TextView>(R.id.quick_add_success_message).text == "Adicionado à lista" }
        assertEquals(2, house.rows.size)
    }
    @Test fun revokedAccessKeepsTheDraftAndReauthorizationRestoresThePanel() {
        launch()
        house.commandSteps += FakeHouse.Step.Unauthorized
        onView(withId(R.id.quick_add_product)).perform(replaceText("Leite"), pressImeActionButton())
        await { it.findViewById<Button>(R.id.quick_add_submit).text == "Abrir app" }
        onView(withId(R.id.quick_add_product)).check(matches(withEffectiveVisibility(Visibility.GONE)))
        onView(withId(R.id.quick_add_password)).check(matches(withEffectiveVisibility(Visibility.GONE)))
        stored = StoredAccess(AccessCredential(1, "reauthorized-test-only"))
        scenario!!.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
        scenario!!.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
        await { it.findViewById<EditText>(R.id.quick_add_product).isShown }
        onView(withId(R.id.quick_add_product)).check(matches(withText("Leite")))
        onView(withId(R.id.quick_add_product)).perform(closeSoftKeyboard())
        onView(withId(R.id.quick_add_submit)).perform(scrollTo(), click())
        await { it.findViewById<TextView>(R.id.quick_add_success_message).text == "Adicionado à lista" }
        assertEquals(1, house.rows.size)
    }

    @Test fun duplicateConfirmsAndClosesAfterOneAndAHalfSeconds() {
        house.seed("Leite")
        launch()
        onView(withId(R.id.quick_add_product)).perform(replaceText("Leite"), pressImeActionButton())
        await { it.findViewById<TextView>(R.id.quick_add_success_message).text == "Já estava na lista" }
        val deadline = System.currentTimeMillis() + 4_000
        while (scenario!!.state != androidx.lifecycle.Lifecycle.State.DESTROYED && System.currentTimeMillis() < deadline) Thread.sleep(50)
        assertEquals(androidx.lifecycle.Lifecycle.State.DESTROYED, scenario!!.state)
        assertEquals(1, house.rows.size)
    }
}
