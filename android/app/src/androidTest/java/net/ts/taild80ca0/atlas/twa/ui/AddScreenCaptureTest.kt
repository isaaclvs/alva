package net.ts.taild80ca0.atlas.twa.ui

import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import net.ts.taild80ca0.atlas.twa.MainActivity
import net.ts.taild80ca0.atlas.twa.access.*
import net.ts.taild80ca0.atlas.twa.online.FakeHouse
import net.ts.taild80ca0.atlas.twa.online.OnlineAccess
import net.ts.taild80ca0.atlas.twa.online.OnlineRepository
import net.ts.taild80ca0.atlas.twa.sync.SnapshotFixtures
import net.ts.taild80ca0.atlas.twa.ui.theme.AlvaColor
import net.ts.taild80ca0.atlas.twa.ui.theme.AlvaPalettes
import net.ts.taild80ca0.atlas.twa.ui.theme.AlvaTheme
import net.ts.taild80ca0.atlas.twa.ui.theme.applyAlvaSystemBars
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/**
 * Screenshots of the Adicionar screen in every theme and color, to put beside the PWA's (#97, "paridade visual").
 * Not a test of behavior: it only runs when `-Pandroid.testInstrumentationRunnerArguments.captureAdd=true` is
 * given, and writes `/sdcard/Download/add-<theme>-<color>-<state>.png` (use a 412×915dp screen: `wm size 1080x2400`).
 * The list is the one of the PWA's local sample data, so the duplicate hint and the categories match.
 */
class AddScreenCaptureTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val house = FakeHouse()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var scenario: ActivityScenario<MainActivity>? = null

    @After fun cleanup() {
        scenario?.close()
        AccessGraph.install(null)
        scope.cancel()
    }

    private fun screencap(name: String) {
        compose.waitForIdle()
        Thread.sleep(400) // the focus ring and the keyboard animation settle
        val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("screencap -p /sdcard/Download/$name.png")
        android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
    }

    private fun hideKeyboard() {
        scenario!!.onActivity { activity ->
            WindowCompat.getInsetsController(activity.window, activity.window.decorView).hide(WindowInsetsCompat.Type.ime())
        }
        compose.waitForIdle()
        Thread.sleep(500)
    }

    private fun capture(dark: Boolean, color: AlvaColor) {
        val theme = "${if (dark) "dark" else "light"}-${color.name.lowercase()}"
        house.rows.clear()
        house.learned.clear()
        house.seed("Leite", category = SnapshotFixtures.DAIRY)
        house.seed("Pão", category = FakeHouse.BAKERY)
        house.seed("Banana", category = FakeHouse.PRODUCE)
        house.seed("Sabão em pó", category = FakeHouse.CLEANING)
        house.seed("Arroz", category = FakeHouse.PANTRY)
        house.seed("Detergente", category = FakeHouse.CLEANING)
        house.seed("Frango", category = FakeHouse.MEAT)
        house.seed("Refrigerante", category = FakeHouse.DRINKS)
        house.learned["café"] = FakeHouse.PANTRY
        house.learned["esponja"] = FakeHouse.CLEANING

        val vault = object : CredentialVault {
            override fun read() = VaultRead.Found(StoredAccess(AccessCredential(1, "test-only")))
            override fun write(access: StoredAccess) = error("opening must not write credentials")
        }
        val access = HouseAccess(vault, object : HouseAccessApi {
            override suspend fun pair(password: String, deviceName: String, previousToken: String?): ApiResult<AccessCredential> = error("no")
            override suspend fun identifyHousehold(credential: AccessCredential): ApiResult<HouseholdIdentity> = error("no")
        }, InMemoryHouseholdBinding())
        val repository = OnlineRepository(OnlineAccess.of(access), house, house, scope, pause = {})
        AccessGraph.install(access, online = repository)
        scenario = ActivityScenario.launch(MainActivity::class.java)
        // The activity starts dark and teal (preferences arrive with #109); show the screen in the theme to capture.
        scenario!!.onActivity { activity ->
            activity.setContent {
                AlvaTheme(dark, color) {
                    SideEffect { activity.applyAlvaSystemBars(AlvaPalettes.of(dark, color)) }
                    Surface(color = MaterialTheme.colorScheme.background) { ShoppingScreens(repository) }
                }
            }
        }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("add-input").fetchSemanticsNodes().isNotEmpty() }

        compose.onNodeWithTag("add-input").assertIsFocused()
        hideKeyboard()
        screencap("add-$theme-empty")

        compose.onNodeWithTag("add-input").performTextInput("leite, pao")
        hideKeyboard()
        screencap("add-$theme-duplicate")
        compose.onNodeWithTag("add-input").performTextReplacement("")

        compose.onNodeWithTag("add-input").performTextInput("Café, Esponja, Tapioca")
        compose.onNodeWithTag("save-add").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("recent-adds").fetchSemanticsNodes().isNotEmpty() }
        hideKeyboard()
        screencap("add-$theme-recent")
    }

    private fun requested() = assumeTrue(
        "visual captures only run on request",
        InstrumentationRegistry.getArguments().getString("captureAdd") == "true",
    )

    @Test fun darkTeal() { requested(); capture(true, AlvaColor.Teal) }
    @Test fun lightPink() { requested(); capture(false, AlvaColor.Pink) }
    @Test fun darkPink() { requested(); capture(true, AlvaColor.Pink) }
    @Test fun lightTeal() { requested(); capture(false, AlvaColor.Teal) }
}
