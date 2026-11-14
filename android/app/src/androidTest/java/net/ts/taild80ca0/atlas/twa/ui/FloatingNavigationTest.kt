package net.ts.taild80ca0.atlas.twa.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
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
import net.ts.taild80ca0.atlas.twa.ui.theme.AlvaColor
import net.ts.taild80ca0.atlas.twa.ui.theme.AlvaTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * The floating navigation capsule (approved prototype, variant A), alone: semantics, the pending counter, the
 * 48dp touch targets (also with an enlarged font) and a capsule that floats with canvas on its sides and below.
 */
class FloatingNavigationCapsuleTest {
    @get:Rule val compose = createComposeRule()

    private var current by mutableStateOf(AppScreen.Add)
    private var pending by mutableStateOf(3)
    private val selected = mutableListOf<AppScreen>()

    private fun show(dark: Boolean = false, color: AlvaColor = AlvaColor.Pink, fontScale: Float = 1f) {
        compose.setContent {
            AlvaTheme(dark = dark, color = color) {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                    Box(Modifier.fillMaxSize().background(AlvaTheme.colors.canvas)) {
                        BottomNavigation(current, pending, onSelect = { selected += it; current = it }, modifier = Modifier.align(Alignment.BottomCenter))
                    }
                }
            }
        }
    }

    @Test fun exposesTwoSelectableTabsAndThePendingCounterInAllFourPalettes() {
        val palettes = listOf(true to AlvaColor.Teal, false to AlvaColor.Teal, true to AlvaColor.Pink, false to AlvaColor.Pink)
        var dark by mutableStateOf(palettes[0].first)
        var color by mutableStateOf(palettes[0].second)
        compose.setContent {
            AlvaTheme(dark = dark, color = color) {
                Box(Modifier.fillMaxSize().background(AlvaTheme.colors.canvas)) {
                    BottomNavigation(current, pending, onSelect = { current = it }, modifier = Modifier.align(Alignment.BottomCenter))
                }
            }
        }
        palettes.forEach { (d, c) ->
            compose.runOnIdle { dark = d; color = c }
            compose.onNodeWithTag("nav-add").assertIsSelected().assertHasClickAction()
            compose.onNodeWithTag("nav-list").assertIsNotSelected().assertHasClickAction()
            compose.onNodeWithText("Adicionar").assertIsDisplayed()
            compose.onNodeWithText("Lista").assertIsDisplayed()
            compose.onNodeWithTag("list-counter", useUnmergedTree = true).assertContentDescriptionEquals("3 pendentes")
        }
    }

    @Test fun selectingATabReportsItAndMovesTheSelection() {
        show()
        compose.onNodeWithTag("nav-list").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.onNodeWithTag("nav-list").assertIsSelected()
        compose.onNodeWithTag("nav-add").assertIsNotSelected()
        assertEquals(listOf(AppScreen.List), selected)
    }

    @Test fun theCounterFollowsThePendingItemsAndIsSingularForOne() {
        show()
        compose.runOnIdle { pending = 1 }
        compose.onNodeWithTag("list-counter", useUnmergedTree = true).assertContentDescriptionEquals("1 pendente")
        compose.runOnIdle { pending = 0 }
        compose.onNodeWithTag("list-counter", useUnmergedTree = true).assertContentDescriptionEquals("0 pendentes")
    }

    @Test fun theCapsuleFloatsWithCanvasAtTheSidesAndBelow() {
        show()
        val root = compose.onRoot().getUnclippedBoundsInRoot()
        val capsule = compose.onNodeWithTag(FloatingNavigation.CapsuleTag).getUnclippedBoundsInRoot()
        assertTrue("left margin ${capsule.left}", capsule.left >= FloatingNavigation.SideMargin - 0.5.dp)
        assertTrue("right margin", root.right - capsule.right >= FloatingNavigation.SideMargin - 0.5.dp)
        assertTrue("canvas below", root.bottom - capsule.bottom >= FloatingNavigation.EdgeGap - 0.5.dp)
        assertTrue("narrower than the screen", capsule.width < root.width)
        // A capsule, not a bar: the two tabs sit inside it, with the inner padding around them.
        listOf("nav-add", "nav-list").forEach {
            val tab = compose.onNodeWithTag(it).getUnclippedBoundsInRoot()
            assertTrue("$it inside the capsule", tab.left >= capsule.left && tab.right <= capsule.right && tab.top >= capsule.top && tab.bottom <= capsule.bottom)
        }
    }

    @Test fun eachTabIsAtLeast48dpTall() = checkTouchTargets(fontScale = 1f)

    @Test fun eachTabStaysTouchableWithTheLargestFont() = checkTouchTargets(fontScale = 2f)

    private fun checkTouchTargets(fontScale: Float) {
        show(fontScale = fontScale)
        val capsule = compose.onNodeWithTag(FloatingNavigation.CapsuleTag).getUnclippedBoundsInRoot()
        val add = compose.onNodeWithTag("nav-add").getUnclippedBoundsInRoot()
        val list = compose.onNodeWithTag("nav-list").getUnclippedBoundsInRoot()
        listOf(add, list).forEach {
            assertTrue("height ${it.height}", it.height >= 48.dp - 0.5.dp)
            assertTrue("width ${it.width}", it.width >= 48.dp)
            assertTrue("inside the capsule at font scale $fontScale", it.left >= capsule.left && it.right <= capsule.right)
        }
        assertEquals("both pills have the same height", add.height.value, list.height.value, 0.5f)
        assertTrue("tabs do not overlap", add.right <= list.left)
        compose.onNodeWithText("Adicionar").assertIsDisplayed()
        compose.onNodeWithText("Lista").assertIsDisplayed()
        compose.onNodeWithTag("list-counter", useUnmergedTree = true).assertIsDisplayed()
    }
}

/** The capsule in the app: content and the undo toast end above it, and shopping mode has no navigation at all. */
class FloatingNavigationInAppTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val house = FakeHouse()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var scenario: ActivityScenario<MainActivity>? = null
    private val noNetwork = object : HouseAccessApi {
        override suspend fun pair(password: String, deviceName: String, previousToken: String?): ApiResult<AccessCredential> = error("UI must not pair")
        override suspend fun identifyHousehold(credential: AccessCredential): ApiResult<HouseholdIdentity> = error("UI must not use HTTP")
    }

    @org.junit.Before fun reset() {
        kotlinx.coroutines.runBlocking {
            net.ts.taild80ca0.atlas.twa.preferences.DevicePreferencesStore(context).reset()
        }
        context.getSharedPreferences(SHOPPING_PREFERENCES, 0).edit().clear().commit()
    }

    @After fun cleanup() {
        scenario?.close()
        context.getSharedPreferences(SHOPPING_PREFERENCES, 0).edit().clear().commit()
        AccessGraph.install(null)
        scope.cancel()
    }

    private fun launch() {
        val vault = object : CredentialVault {
            override fun read() = VaultRead.Found(StoredAccess(AccessCredential(1, "test-only")))
            override fun write(access: StoredAccess) = error("opening must not write credentials")
        }
        val access = HouseAccess(vault, noNetwork, InMemoryHouseholdBinding())
        val repository = OnlineRepository(OnlineAccess.of(access), house, house, scope, pause = {})
        AccessGraph.install(access, online = repository, foreground = null)
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("add-input").fetchSemanticsNodes().isNotEmpty() }
    }

    private fun press(tag: String) = compose.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.OnClick) { it() }

    @Test fun theCapsuleKeepsTheContentAndTheUndoToastAboveIt() {
        val leite = house.seed("Leite")
        launch()
        val capsule = compose.onNodeWithTag(FloatingNavigation.CapsuleTag).getUnclippedBoundsInRoot()
        val add = compose.onNodeWithTag("add-input").getUnclippedBoundsInRoot()
        assertTrue("the add field ends above the capsule", add.bottom <= capsule.top)

        press("nav-list")
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("list-summary").fetchSemanticsNodes().isNotEmpty() }
        val home = compose.onNodeWithTag("shopping-home").getUnclippedBoundsInRoot()
        assertTrue("the list ends above the capsule", home.bottom <= capsule.top + 0.5.dp)

        compose.onNodeWithTag("item-${leite.id}").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("toast-undo").fetchSemanticsNodes().isNotEmpty() }
        val undo = compose.onNodeWithTag("toast-undo").getUnclippedBoundsInRoot()
        assertTrue("Desfazer is above the capsule, not under it", undo.bottom <= capsule.top)
        compose.onNodeWithTag("toast-undo").assertIsDisplayed()
    }

    @Test fun theToastShowsAboveTheCapsuleAndKeepsTheTabSelectionWhenSwitchingTabs() {
        val leite = house.seed("Leite")
        launch()
        press("nav-list")
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("list-summary").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("item-${leite.id}").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("toast-undo").fetchSemanticsNodes().isNotEmpty() }
        press("nav-add")
        compose.onNodeWithTag("nav-add").assertIsSelected()
        compose.onNodeWithTag("nav-list").assertIsNotSelected()
        val capsule = compose.onNodeWithTag(FloatingNavigation.CapsuleTag).getUnclippedBoundsInRoot()
        compose.onNodeWithTag("toast-undo").assertIsDisplayed()
        assertTrue(compose.onNodeWithTag("toast-undo").getUnclippedBoundsInRoot().bottom <= capsule.top)
    }

    @Test fun shoppingUndoStaysAboveTheWholeFooterWhenPurchasedItemsAddTheFinishButton() {
        val leite = house.seed("Leite")
        house.seed("Café")
        launch()
        press("nav-list")
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("start-shopping").fetchSemanticsNodes().isNotEmpty() }
        press("start-shopping")
        compose.onNodeWithTag("item-${leite.id}").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("toast-undo").fetchSemanticsNodes().isNotEmpty() }
        val footer = compose.onNodeWithTag("shopping-footer").getUnclippedBoundsInRoot()
        val undo = compose.onNodeWithTag("toast-undo").getUnclippedBoundsInRoot()
        assertTrue("undo must clear the entire shopping footer", undo.bottom <= footer.top)
        compose.onNodeWithTag("finish-shopping").assertIsDisplayed()
        compose.onNodeWithTag("list-search").assertIsDisplayed()
        compose.onNodeWithTag("toast-undo").performClick()
        compose.waitUntil(10_000) { leite.status == "pending" }
    }

    @Test fun shoppingModeHidesTheCapsule() {
        house.seed("Leite")
        context.getSharedPreferences(SHOPPING_PREFERENCES, 0).edit().putLong("until", System.currentTimeMillis() + 60_000).commit()
        val vault = object : CredentialVault {
            override fun read() = VaultRead.Found(StoredAccess(AccessCredential(1, "test-only")))
            override fun write(access: StoredAccess) = error("opening must not write credentials")
        }
        val access = HouseAccess(vault, noNetwork, InMemoryHouseholdBinding())
        val repository = OnlineRepository(OnlineAccess.of(access), house, house, scope, pause = {})
        AccessGraph.install(access, online = repository, foreground = null)
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("exit-shopping").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag(FloatingNavigation.CapsuleTag).assertDoesNotExist()
        compose.onNodeWithTag("nav-add").assertDoesNotExist()
        compose.onNodeWithTag("nav-list").assertDoesNotExist()
    }
}
