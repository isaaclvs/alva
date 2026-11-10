package net.ts.taild80ca0.atlas.twa.access

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import net.ts.taild80ca0.atlas.twa.MainActivity
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
    private var scenario: ActivityScenario<MainActivity>? = null

    @Before
    fun freshProcess() = startProcess()

    @After
    fun tearDown() {
        scenario?.close()
        AccessGraph.install(null)
        server.close()
        storage.cleanup()
    }

    /** A new process: new objects over the same Keystore vault and database file. */
    private fun startProcess() {
        scenario?.close()
        AccessGraph.install(
            HouseAccess(
                storage.newVault(),
                HttpHouseAccessApi(ServerEndpoint.parse(server.baseUrl), 3_000, 5_000),
                RoomHouseholdBinding(storage.openDatabase()),
            ),
        )
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
        compose.onNodeWithTag("connect").performClick()
    }

    @Test
    fun firstLaunchAsksForThePasswordOnceAndLaterLaunchesOpenDirectly() {
        waitForText(AccessMessages.SETUP_TITLE)
        compose.onNodeWithTag("password").assertIsDisplayed()

        connectWith(server.password)
        waitForText(AccessMessages.CONNECTED_TITLE)
        assertEquals(1, server.pairingRequests().size)
        server.requests.clear()

        startProcess()

        waitForText(AccessMessages.CONNECTED_TITLE)
        compose.onAllNodes(androidx.compose.ui.test.hasTestTag("password")).fetchSemanticsNodes().let {
            assertTrue("no password field once connected", it.isEmpty())
        }
        assertTrue("opening again must not touch the server", server.requests.isEmpty())
    }

    @Test
    fun aWrongPasswordShowsAHelpfulMessageAndKeepsAskingWithoutKeepingTheText() {
        waitForText(AccessMessages.SETUP_TITLE)

        connectWith("errada")

        waitForText("Senha incorreta. Confira e tente de novo.")
        compose.onNodeWithText("Senha incorreta. Confira e tente de novo.").assertIsDisplayed()
        compose.onNodeWithTag("connect").assertIsDisplayed()
    }

    @Test
    fun aRevokedDeviceOffersToReconnectAndGoesBackToConnected() {
        waitForText(AccessMessages.SETUP_TITLE)
        connectWith(server.password)
        waitForText(AccessMessages.CONNECTED_TITLE)

        server.revoke(server.devices.single().id)
        compose.onNodeWithTag("check").performClick()
        waitForText(AccessMessages.RECONNECT_TITLE)
        compose.onNodeWithText(AccessMessages.RECONNECT_HELP).assertIsDisplayed()

        connectWith(server.password)
        waitForText(AccessMessages.CONNECTED_TITLE)
        assertEquals(2, server.pairingRequests().size)
    }

    @Test
    fun noInternalDetailReachesTheScreenWhenTheServerIsDown() {
        waitForText(AccessMessages.SETUP_TITLE)
        server.stop()

        connectWith(server.password)

        waitForText("Sem conexão com a casa. Confira a internet e tente de novo.")
    }
}
