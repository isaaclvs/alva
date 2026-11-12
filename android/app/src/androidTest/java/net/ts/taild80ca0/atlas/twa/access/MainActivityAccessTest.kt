package net.ts.taild80ca0.atlas.twa.access

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
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
    fun freshProcess() = startProcess()

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

    private fun connectWith(password: String) {
        compose.onNodeWithTag("password").performTextInput(password)
        // The keyboard opens with the field (autofocus) and can cover the button: scroll to it like a thumb would.
        compose.onNodeWithTag("connect").performScrollTo().performClick()
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
        compose.onNodeWithTag("connect").assertIsDisplayed()
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
}
