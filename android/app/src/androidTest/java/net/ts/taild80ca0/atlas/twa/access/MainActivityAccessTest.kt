package net.ts.taild80ca0.atlas.twa.access

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ActivityScenario
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import net.ts.taild80ca0.atlas.twa.MainActivity
import net.ts.taild80ca0.atlas.twa.online.FakeHouse
import net.ts.taild80ca0.atlas.twa.online.OnlineAccess
import net.ts.taild80ca0.atlas.twa.online.OnlineRepository
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * The installed debug app end to end: first launch asks for the password once,
 * later launches open straight past it, and a revoked device reconnects.
 */
class MainActivityAccessTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val storage = RealAccessStorage()
    private val server = FakeAlvaServer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var scenario: ActivityScenario<MainActivity>? = null
    private lateinit var access: HouseAccess

    @Before
    fun freshProcess() {
        runBlocking { net.ts.taild80ca0.atlas.twa.preferences.DevicePreferencesStore(
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext).reset() }
        startProcess()
    }

    @After
    fun tearDown() {
        scenario?.close()
        AccessGraph.install(null)
        scope.cancel()
        server.close()
        storage.cleanup()
    }

    /** A new process: new objects over the same Keystore vault and database file. */
    private fun startProcess() {
        scenario?.close()
        access = HouseAccess(
            storage.newVault(),
            HttpHouseAccessApi(ServerEndpoint.parse(server.baseUrl), 3_000, 5_000),
            RoomHouseholdBinding(storage.openDatabase()),
        )
        // The list is the online repository's business (ShoppingScreensTest); here it reads an empty house in memory.
        val house = FakeHouse()
        AccessGraph.install(access, online = OnlineRepository(OnlineAccess.of(access), house, house, scope))
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    private fun waitForText(text: String) {
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTextCompat(text)
        }
    }

    private fun androidx.compose.ui.test.junit4.ComposeTestRule.onAllNodesWithTextCompat(text: String) =
        onAllNodes(androidx.compose.ui.test.hasText(text)).fetchSemanticsNodes().isNotEmpty()

    /**
     * Types the password and sends it.
     *
     * Neither step may depend on the device's IME: on a loaded machine the keyboard takes seconds to bind, and a
     * keystroke or a tap injected while the window is still moving goes nowhere (the test then waits 10 s for a
     * screen that will never come). The text goes straight into the field's own `SetText` action and the send
     * through the button's `OnClick`, and each step waits for the previous one to be on screen.
     */
    private fun connectWith(password: String) {
        compose.onNodeWithTag("password").performTextReplacement(password)
        // The keyboard opens over the button and moves the layout; the button is tapped once it settled there.
        compose.onNodeWithTag("connect").performScrollTo()
            .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { it() }
    }

    @Test
    fun firstLaunchAsksForThePasswordOnceAndLaterLaunchesOpenDirectly() {
        waitForText(AccessMessages.TITLE)
        compose.onNodeWithTag("password").assertIsDisplayed()

        connectWith(server.password)
        waitForText("Nossa casa")
        assertEquals(1, server.pairingRequests().size)
        server.requests.clear()

        startProcess()

        waitForText("Nossa casa")
        compose.onAllNodes(androidx.compose.ui.test.hasTestTag("password")).fetchSemanticsNodes().let {
            assertTrue("no password field once connected", it.isEmpty())
        }
        assertTrue("opening again must not touch the server", server.requests.isEmpty())
    }

    @Test
    fun aWrongPasswordShowsAHelpfulMessageAndKeepsAskingWithoutKeepingTheText() {
        waitForText(AccessMessages.TITLE)

        connectWith("errada")

        waitForText("Senha incorreta. Tente novamente.")
        compose.onNodeWithText("Senha incorreta. Tente novamente.").assertIsDisplayed()
        compose.onNodeWithTag("connect").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun aRevokedDeviceOffersToReconnectAndGoesBackToConnected() {
        waitForText(AccessMessages.TITLE)
        connectWith(server.password)
        waitForText("Nossa casa")

        server.revoke(server.devices.single().id)
        compose.onNodeWithTag("house-menu").performClick()
        compose.onNodeWithTag("check").performClick()
        waitForText(AccessMessages.TITLE)

        connectWith(server.password)
        waitForText("Nossa casa")
        assertEquals(2, server.pairingRequests().size)
    }

    @Test
    fun noInternalDetailReachesTheScreenWhenTheServerIsDown() {
        waitForText(AccessMessages.TITLE)
        server.stop()

        connectWith(server.password)

        waitForText("Sem conexão com a casa. Confira a internet e tente de novo.")
    }

    @Test
    fun signingOutGoesBackToTheEntryScreenAndStaysThereAfterReopening() {
        waitForText(AccessMessages.TITLE)
        connectWith(server.password)
        waitForText("Nossa casa")

        runBlocking { assertTrue(access.signOut()) }

        waitForText(AccessMessages.TITLE)
        compose.onNodeWithTag("password").assertIsDisplayed()
        startProcess()
        waitForText(AccessMessages.TITLE)
        compose.onNodeWithTag("password").assertIsDisplayed()

        connectWith(server.password)
        waitForText("Nossa casa")
    }
    @Test fun menuSignOutReturnsToEntryAndKeepsDevicePreferences() {
        waitForText(AccessMessages.TITLE)
        connectWith(server.password)
        waitForText("Nossa casa")
        val preferences = net.ts.taild80ca0.atlas.twa.preferences.DevicePreferencesStore(
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext)
        runBlocking { preferences.dark(false); preferences.color(net.ts.taild80ca0.atlas.twa.ui.theme.AlvaColor.Pink) }
        // The theme just changed, so the menu is opened only once the header settled with it, and "Sair" is pressed
        // only once the menu is open: a click injected while Compose is still recomposing lands on nothing.
        waitUntilTag("house-menu")
        compose.onNodeWithTag("house-menu").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { it() }
        waitUntilTag("sign-out")
        compose.onNodeWithTag("sign-out").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { it() }
        waitForText(AccessMessages.TITLE)
        val saved = runBlocking { preferences.values.first() }
        assertTrue(!saved.dark)
        assertEquals(net.ts.taild80ca0.atlas.twa.ui.theme.AlvaColor.Pink, saved.color)
    }

    private fun waitUntilTag(tag: String) = compose.waitUntil(10_000) {
        compose.onAllNodes(androidx.compose.ui.test.hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()
    }

}
