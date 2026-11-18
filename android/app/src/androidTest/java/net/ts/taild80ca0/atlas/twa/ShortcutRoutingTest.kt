package net.ts.taild80ca0.atlas.twa

import android.content.Intent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import net.ts.taild80ca0.atlas.twa.access.AccessCredential
import net.ts.taild80ca0.atlas.twa.access.AccessGraph
import net.ts.taild80ca0.atlas.twa.access.AccessMessages
import net.ts.taild80ca0.atlas.twa.access.ApiResult
import net.ts.taild80ca0.atlas.twa.access.CredentialVault
import net.ts.taild80ca0.atlas.twa.access.HouseAccess
import net.ts.taild80ca0.atlas.twa.access.HouseAccessApi
import net.ts.taild80ca0.atlas.twa.access.HouseholdIdentity
import net.ts.taild80ca0.atlas.twa.access.StoredAccess
import net.ts.taild80ca0.atlas.twa.access.VaultRead
import net.ts.taild80ca0.atlas.twa.online.FakeHouse
import net.ts.taild80ca0.atlas.twa.online.OnlineAccess
import net.ts.taild80ca0.atlas.twa.online.OnlineRepository
import net.ts.taild80ca0.atlas.twa.preferences.DevicePreferencesStore
import net.ts.taild80ca0.atlas.twa.ui.InMemoryHouseholdBinding
import net.ts.taild80ca0.atlas.twa.ui.SHOPPING_PREFERENCES
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * The three launcher shortcuts end to end (#158): following each intent of [Shortcuts] opens the screen that
 * already does its job, and a device without the house lands on the entry screen instead of breaking.
 */
class ShortcutRoutingTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val house = FakeHouse()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var scenario: ActivityScenario<*>? = null
    private var stored: StoredAccess? = StoredAccess(AccessCredential(1, "shortcut-test-only"))
    private val noNetwork = object : HouseAccessApi {
        override suspend fun pair(password: String, deviceName: String, previousToken: String?): ApiResult<AccessCredential> =
            error("A shortcut must not pair")
        override suspend fun identifyHousehold(credential: AccessCredential): ApiResult<HouseholdIdentity> =
            error("A shortcut must not use HTTP")
    }

    private fun install() {
        val vault = object : CredentialVault {
            override fun read() = stored?.let { VaultRead.Found(it) } ?: VaultRead.Empty
            override fun write(access: StoredAccess) { stored = access }
        }
        val access = HouseAccess(vault, noNetwork, InMemoryHouseholdBinding())
        AccessGraph.install(access, online = OnlineRepository(OnlineAccess.of(access), house, house, scope, pause = {}))
    }

    private fun follow(destination: String): OnlineRepository {
        install()
        val repository = AccessGraph.onlineRepository(context)!!
        val intent = Shortcuts.intent(context, "alva-shortcut://$destination")!!
        ActivityScenario.launch<android.app.Activity>(intent).also { scenario = it }
        return repository
    }

    private fun waitFor(tag: String) = compose.waitUntil(10_000) {
        compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    }

    private fun waitForText(text: String) = compose.waitUntil(10_000) {
        compose.onAllNodes(androidx.compose.ui.test.hasText(text)).fetchSemanticsNodes().isNotEmpty()
    }

    @Test fun theListShortcutOpensTheListaEvenWhenTheSavedScreenIsAdicionar() {
        house.seed("Leite")
        runBlocking { DevicePreferencesStore(context).screen("add") }
        follow(Shortcuts.LIST)
        waitFor("list-title")
        compose.onNodeWithTag("add-input").assertDoesNotExist()
        compose.onNodeWithTag("item-${house.named("Leite").single().id}").assertExists()
    }

    @Test fun theShoppingShortcutOpensTheListaAlreadyInShoppingMode() {
        house.seed("Leite")
        follow(Shortcuts.SHOPPING_MODE)
        waitFor("exit-shopping")
        compose.onNodeWithTag("list-title").assertExists()
        compose.onNodeWithTag("nav-add").assertDoesNotExist()
        compose.onNodeWithTag("start-shopping").assertDoesNotExist()
        assertEquals(true, context.getSharedPreferences(SHOPPING_PREFERENCES, 0).getLong("until", 0) > System.currentTimeMillis())
    }

    @Test fun theShoppingShortcutWithAnEmptyListLeavesItAsAList() {
        follow(Shortcuts.SHOPPING_MODE)
        waitFor("list-title")
        compose.onNodeWithTag("exit-shopping").assertDoesNotExist()
        compose.onNodeWithTag("start-shopping").assertDoesNotExist()
        assertEquals(0L, context.getSharedPreferences(SHOPPING_PREFERENCES, 0).getLong("until", 0))
    }

    @Test fun leavingTheShoppingModeTheShortcutStartedIsNotUndoneByTheNextRefresh() {
        house.seed("Leite")
        val repository = follow(Shortcuts.SHOPPING_MODE)
        waitFor("exit-shopping")
        compose.onNodeWithTag("exit-shopping").performClick()
        compose.onNodeWithTag("start-shopping").assertExists()
        runBlocking { repository.refresh() }
        compose.waitForIdle()
        compose.onNodeWithTag("exit-shopping").assertDoesNotExist()
        compose.onNodeWithTag("start-shopping").assertExists()
    }

    @Test fun theAddShortcutOpensTheQuickPanelWithTheFieldReady() {
        follow(Shortcuts.ADD_ITEM)
        compose.waitUntil(10_000) {
            scenario!!.let { launched ->
                var ready = false
                launched.onActivity { activity ->
                    ready = activity is QuickAddActivity &&
                        activity.findViewById<android.widget.EditText>(R.id.quick_add_product)?.isShown == true
                }
                ready
            }
        }
        val launched = mutableListOf<Class<*>>()
        scenario!!.onActivity { launched += it.javaClass }
        assertEquals(listOf<Class<*>>(QuickAddActivity::class.java), launched)
    }

    @Test fun anUnpairedDeviceLandsOnTheEntryScreenFromTheAddShortcut() {
        stored = null
        follow(Shortcuts.ADD_ITEM)
        waitForText(AccessMessages.TITLE)
        compose.onNodeWithTag("password").assertIsDisplayed()
    }

    @Test fun anUnpairedDeviceLandsOnTheEntryScreenFromTheListShortcut() {
        stored = null
        follow(Shortcuts.LIST)
        waitForText(AccessMessages.TITLE)
        compose.onNodeWithTag("password").assertIsDisplayed()
    }

    @After fun cleanup() {
        scenario?.close()
        context.getSharedPreferences(SHOPPING_PREFERENCES, 0).edit().clear().commit()
        runBlocking { DevicePreferencesStore(context).reset() }
        AccessGraph.install(null)
        scope.cancel()
    }
}
