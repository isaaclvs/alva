package net.ts.taild80ca0.atlas.twa.ui

import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import net.ts.taild80ca0.atlas.twa.MainActivity
import net.ts.taild80ca0.atlas.twa.access.*
import net.ts.taild80ca0.atlas.twa.online.FakeHouse
import net.ts.taild80ca0.atlas.twa.online.FakeHouse.Step
import net.ts.taild80ca0.atlas.twa.online.OnlineAccess
import net.ts.taild80ca0.atlas.twa.online.OnlineRepository
import net.ts.taild80ca0.atlas.twa.online.OnlineState
import net.ts.taild80ca0.atlas.twa.ui.theme.AlvaTheme
import net.ts.taild80ca0.atlas.twa.sync.CableConnector
import net.ts.taild80ca0.atlas.twa.sync.CableEvent
import net.ts.taild80ca0.atlas.twa.sync.CableSocket
import net.ts.taild80ca0.atlas.twa.sync.CommandOutcome
import net.ts.taild80ca0.atlas.twa.sync.CommandTransport
import net.ts.taild80ca0.atlas.twa.sync.ForegroundInvalidation
import net.ts.taild80ca0.atlas.twa.sync.SnapshotFixtures
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** The installed Compose app over the online repository, with a server in memory standing in for Rails. */
class ShoppingScreensTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val house = FakeHouse()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var scenario: ActivityScenario<MainActivity>? = null
    private val noNetwork = object : HouseAccessApi {
        override suspend fun pair(password: String, deviceName: String, previousToken: String?): ApiResult<AccessCredential> = error("UI must not pair")
        override suspend fun identifyHousehold(credential: AccessCredential): ApiResult<HouseholdIdentity> = error("UI must not use HTTP")
    }

    private fun launch(
        commands: CommandTransport = house,
        foreground: ((HouseAccess, OnlineRepository) -> ForegroundInvalidation)? = null,
    ): OnlineRepository {
        val vault = object : CredentialVault {
            override fun read() = VaultRead.Found(StoredAccess(AccessCredential(1, "test-only")))
            override fun write(access: StoredAccess) = error("opening must not write credentials")
        }
        val access = HouseAccess(vault, noNetwork, InMemoryHouseholdBinding())
        val repository = OnlineRepository(OnlineAccess.of(access), house, commands, scope, pause = {})
        AccessGraph.install(access, online = repository, foreground = foreground?.invoke(access, repository))
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("shopping-home").fetchSemanticsNodes().isNotEmpty() }
        return repository
    }

    private fun launchLoaded() {
        launch()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("add-input").fetchSemanticsNodes().isNotEmpty() }
    }

    @After fun cleanup() {
        scenario?.close()
        AccessGraph.install(null)
        scope.cancel()
    }

    private fun waitForText(tag: String, text: String) {
        compose.waitUntil(10_000) {
            compose.onAllNodes(hasTestTag(tag) and hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun assertMarked(id: String, checked: Boolean) {
        val value = if (checked) androidx.compose.ui.state.ToggleableState.On else androidx.compose.ui.state.ToggleableState.Off
        compose.waitUntil(10_000) {
            compose.onAllNodes(hasTestTag("item-$id") and SemanticsMatcher.expectValue(
                androidx.compose.ui.semantics.SemanticsProperties.ToggleableState, value,
            )).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private val emptyInput = SemanticsMatcher("editable text is empty") {
        it.config[androidx.compose.ui.semantics.SemanticsProperties.EditableText].text.isEmpty()
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        // Public emulator output survives the runner uninstalling the test APK.
        val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand("screencap -p /sdcard/Download/$name")
        android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
    }

    private fun submit(text: String) {
        compose.onNodeWithTag("add-input").performTextInput(text)
        compose.onNodeWithTag("save-add").assertIsDisplayed()
        // Exercise the real Done action without racing IME/layout animation coordinates.
        compose.onNodeWithTag("add-input").performImeAction()
    }

    @Test fun addsMultipleNamesThroughTheServerThenMarksAndUnmarks() {
        launchLoaded()
        compose.onNodeWithTag("add-input").assertIsFocused()
        submit("Leite, Pão; Ovos\nLeite")
        waitForText("feedback", "3 itens adicionados")
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("add-input") and emptyInput).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(listOf("Leite", "Pão", "Ovos"), house.rows.map { it.name })
        compose.onNodeWithText("Alva").assertIsDisplayed()
        capture("shopping-add.png")

        compose.onNodeWithTag("nav-list").performClick()
        waitForText("list-summary", "3 pendentes")
        val id = house.named("Leite").single().id
        compose.onNodeWithTag("item-$id").performClick()
        assertMarked(id, true)
        waitForText("list-summary", "2 pendentes · 1 comprado")
        assertEquals("purchased", house.named("Leite").single().status)
        capture("shopping-list.png")

        compose.onNodeWithTag("item-$id").performClick()
        assertMarked(id, false)
        assertEquals("pending", house.named("Leite").single().status)
    }

    @Test fun openingReadsTheServersListGroupedByCategory() {
        house.seed("Leite")
        house.seed("Pão", status = "purchased")
        launch()
        compose.onNodeWithTag("nav-list").performClick()
        waitForText("list-summary", "1 pendente · 1 comprado")
        compose.onNodeWithText("Laticínios", substring = true).assertIsDisplayed()
        compose.onNodeWithTag("item-${house.named("Pão").single().id}").assertIsOn()
        assertEquals("the list was read from the server, not from a copy", 1, house.fetches)
    }

    @Test fun warnsAboutDuplicatesWhileTypingAndReportsWhatTheServerSaid() {
        house.seed("Leite")
        launch()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("add-input").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("add-input").performTextInput("leite")
        waitForText("duplicate-hint", "Já na lista: Leite")
        compose.onNodeWithTag("save-add").performClick()
        waitForText("feedback", "Nada adicionado · Leite já estava na lista")
        assertEquals(1, house.rows.size)
    }

    @Test fun withoutNetworkTheTextStaysAndNothingIsStoredForLater() {
        launchLoaded()
        house.commandSteps += listOf(Step.NetworkDown, Step.NetworkDown, Step.NetworkDown)
        compose.onNodeWithTag("add-input").performTextInput("Café")
        compose.onNodeWithTag("save-add").performClick()
        waitForText("feedback", "Sem conexão com o servidor")
        compose.onNodeWithTag("feedback").assertTextContains("Seu texto foi mantido", substring = true)
        compose.onNodeWithTag("add-input").assertTextContains("Café")
        assertTrue(house.rows.isEmpty())

        // Nothing was queued: only repeating the action adds it, once.
        compose.onNodeWithTag("save-add").performClick()
        waitForText("feedback", "1 item adicionado")
        assertEquals(listOf("Café"), house.rows.map { it.name })
        assertEquals(4, house.sent.size)
    }

    @Test fun aServerErrorKeepsTheTextToo() {
        launchLoaded()
        house.commandSteps += Step.ServerError
        compose.onNodeWithTag("add-input").performTextInput("Café")
        compose.onNodeWithTag("save-add").performClick()
        waitForText("feedback", "O servidor não está disponível agora")
        compose.onNodeWithTag("add-input").assertTextContains("Café")
        assertTrue(house.rows.isEmpty())
    }

    @Test fun aMarkThatCannotBeSentSaysSoAndTheItemStaysAsItWas() {
        val leite = house.seed("Leite")
        launch()
        compose.onNodeWithTag("nav-list").performClick()
        waitForText("list-summary", "1 pendente")
        house.commandSteps += listOf(Step.NetworkDown, Step.NetworkDown, Step.NetworkDown)
        compose.onNodeWithTag("item-${leite.id}").performClick()
        waitForText("feedback", "Sem conexão com o servidor")
        compose.onNodeWithTag("toast-undo").assertDoesNotExist()
        assertMarked(leite.id, false)
        assertEquals("pending", leite.status)
    }

    @Test fun whenTheListCannotBeReadItSaysWhyAndReadsAgainOnRequest() {
        house.seed("Leite")
        house.snapshotSteps += Step.NetworkDown
        launch()
        waitForText("load-failed", "Sem conexão com o servidor")
        compose.onNodeWithTag("reload").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("add-input").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("nav-list").performClick()
        waitForText("list-summary", "1 pendente")
    }

    @Test fun aFailedReloadKeepsTheListAndFlagsItAsPossiblyOutOfDate() {
        house.seed("Leite")
        launch()
        compose.onNodeWithTag("nav-list").performClick()
        waitForText("list-summary", "1 pendente")
        house.snapshotSteps += Step.NetworkDown
        scenario!!.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
        scenario!!.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
        waitForText("connection-notice", "Sem conexão com o servidor")
        waitForText("list-summary", "1 pendente")
        compose.onNodeWithTag("reload").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("connection-notice").fetchSemanticsNodes().isEmpty() }
        waitForText("list-summary", "1 pendente")
    }

    @Test fun resumingTheAppReadsTheListAgain() {
        launch()
        compose.onNodeWithTag("nav-list").performClick()
        waitForText("list-summary", "Nada na lista")
        scenario!!.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
        house.seed("Leite") // another phone added it while this app was in the background
        scenario!!.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
        waitForText("list-summary", "1 pendente")
    }

    @Test fun theLiveSignalReadsTheListAgain() {
        val connections = java.util.concurrent.LinkedBlockingQueue<(CableEvent) -> Unit>()
        launch(foreground = { access, repository ->
            ForegroundInvalidation(
                access, ServerEndpoint.parse("http://127.0.0.1:3010"), repository, scope,
                CableConnector { _, _, receive -> connections.add(receive); CableSocket { } },
            )
        })
        val receive = connections.poll(10, java.util.concurrent.TimeUnit.SECONDS)!!
        compose.onNodeWithTag("nav-list").performClick()
        waitForText("list-summary", "Nada na lista")
        receive(CableEvent.Subscribed)
        house.seed("Leite") // the other phone adds
        receive(CableEvent.Invalidated(900, 0))
        waitForText("list-summary", "1 pendente")
    }

    @Test fun liveSubscriptionClosesWhenPausedAndReopensOnResume() {
        val opened = java.util.concurrent.atomic.AtomicInteger()
        val closed = java.util.concurrent.atomic.AtomicInteger()
        launch(foreground = { access, repository ->
            ForegroundInvalidation(
                access, ServerEndpoint.parse("http://127.0.0.1:3010"), repository, scope,
                CableConnector { _, _, _ -> opened.incrementAndGet(); CableSocket { closed.incrementAndGet() } },
            )
        })
        compose.waitUntil(10_000) { opened.get() == 1 }
        scenario!!.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
        compose.waitUntil(10_000) { closed.get() == 1 }
        scenario!!.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
        compose.waitUntil(10_000) { opened.get() == 2 }
        assertEquals(1, closed.get())
    }

    @Test fun typingDuringTheRequestIsPreservedAndRotationKeepsDraftAndScreen() {
        val release = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        val slow = object : CommandTransport {
            override suspend fun send(credential: AccessCredential, commandId: String, document: String): CommandOutcome {
                entered.complete(Unit)
                release.await()
                return house.send(credential, commandId, document)
            }
        }
        launch(commands = slow)
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("add-input").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("add-input").performTextInput("Leite")
        compose.onNodeWithTag("save-add").performClick()
        compose.waitUntil(10_000) { entered.isCompleted }
        compose.onNodeWithTag("add-input").performTextReplacement("Pão")
        release.complete(Unit)
        waitForText("feedback", "1 item adicionado")
        compose.onNodeWithTag("add-input").assertTextContains("Pão")
        scenario!!.recreate()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("add-input").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("add-input").assertTextContains("Pão")
        compose.onNodeWithTag("nav-list").performClick()
        scenario!!.recreate()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("nav-list").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("nav-list").assertIsSelected()
        compose.onNodeWithText("Leite").assertIsDisplayed()
        compose.onNodeWithTag("add-input").assertDoesNotExist()
    }

    @Test fun repeatedNavigationOnlyFocusesTheAttachedAddField() {
        launchLoaded()
        repeat(10) {
            compose.onNodeWithTag("nav-list").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { it() }
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("add-input").fetchSemanticsNodes().isEmpty() }
            compose.onNodeWithTag("nav-list").assertIsSelected()
            compose.onNodeWithTag("nav-add").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { it() }
            compose.waitUntil(10_000) {
                compose.onAllNodes(hasTestTag("add-input") and SemanticsMatcher.expectValue(
                    androidx.compose.ui.semantics.SemanticsProperties.Focused, true,
                )).fetchSemanticsNodes().isNotEmpty()
            }
        }
        assertTrue(house.sent.isEmpty())
    }

    @Test fun noOfflineMachineryIsOnScreen() {
        launchLoaded()
        compose.onNodeWithTag("nav-list").performClick()
        listOf("sync-status", "sync-now", "conflicts-expand").forEach { tag ->
            assertTrue(tag, compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isEmpty())
        }
        compose.onNodeWithText("Aguardando", substring = true).assertDoesNotExist()
        compose.onNodeWithText("sem internet", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Pronto para adicionar", substring = true).assertDoesNotExist()
    }

    // ---- Lista (#98) -------------------------------------------------------------------------------------

    private val fruit = "c0000000-0000-4000-8000-000000000003"

    private fun seedCategories() {
        house.categories = listOf(
            SnapshotFixtures.category(SnapshotFixtures.OTHERS, "Outros", 99),
            SnapshotFixtures.category(SnapshotFixtures.DAIRY, "Laticínios", 2),
            SnapshotFixtures.category(fruit, "Hortifruti", 1),
        )
    }

    private fun top(tag: String) = compose.onNodeWithTag(tag).getBoundsInRoot().top

    private fun openList() {
        launch()
        // The list is read before the first tap, so the tap is never lost to the screen still loading.
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("add-input").fetchSemanticsNodes().isNotEmpty() }
        // The semantics action, not a tap at the pill's coordinates: the keyboard of the Add screen is still
        // sliding in and moves the bar under a tap.
        compose.onNodeWithTag("nav-list").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { it() }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("list-summary").fetchSemanticsNodes().isNotEmpty() }
    }

    private fun commandTypes() = house.sent.map { JSONObject(it.second).getString("type") }

    @Test fun theInlineRemoveButtonUsesTheSameUndoAsSwipe() {
        val item = house.seed("Leite", status = "purchased")
        openList()
        compose.onNodeWithTag("edit-${item.id}").performClick()
        compose.onNodeWithTag("remove-item").performScrollTo().performClick()
        waitForText("toast-message", "Leite removido")
        compose.onNodeWithTag("edit-name").assertDoesNotExist()
        compose.onNodeWithTag("toast-undo").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("item-${item.id}").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("item-${item.id}").assertIsOn()
        assertEquals(listOf("remove_item", "undo_remove"), commandTypes())
    }

    @Test fun editingRenamesInPlaceAndCancelKeepsTheOriginal() {
        val item = house.seed("Leite")
        openList()
        compose.onNodeWithTag("edit-${item.id}").performClick()
        compose.onNodeWithTag("edit-name").performTextReplacement("Café")
        compose.onNodeWithTag("edit-cancel").performScrollTo().performClick()
        compose.onNodeWithText("Leite").assertIsDisplayed()
        assertTrue(house.sent.isEmpty())
        compose.onNodeWithTag("edit-${item.id}").performClick()
        compose.onNodeWithTag("edit-name").performTextReplacement("Café")
        compose.onNodeWithTag("edit-category").performClick()
        compose.onNodeWithTag("edit-category-Mercearia").performClick()
        compose.onNodeWithTag("edit-save").performScrollTo().performClick()
        compose.waitUntil(10_000) { house.rows.single().name == "Café" }
        assertEquals(FakeHouse.PANTRY, house.learned["café"])
        compose.onNodeWithTag("edit-name").assertDoesNotExist()
        assertEquals(listOf("correct_item"), commandTypes())
    }

    @Test fun aConflictKeepsTheDraftAndTheNextSaveUsesTheReviewedVersion() {
        val item = house.seed("Leite")
        val repository = launch()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("add-input").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("nav-list").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { it() }
        compose.onNodeWithTag("edit-${item.id}").performClick()
        compose.onNodeWithTag("edit-name").performTextReplacement("Café")
        item.lock++
        kotlinx.coroutines.runBlocking { repository.refresh() }
        compose.onNodeWithTag("edit-save").performScrollTo().performClick()
        waitForText("edit-error", "A lista mudou")
        compose.onNodeWithTag("edit-name").assertTextContains("Café")
        assertEquals("Leite", item.name)
        scenario!!.recreate()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("edit-name").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("edit-name").assertTextContains("Café")
        compose.onNodeWithTag("edit-save").performScrollTo().performClick()
        compose.waitUntil(10_000) { item.name == "Café" }
    }

    @Test fun blankEditAndNetworkFailureKeepWhatWasTyped() {
        val item = house.seed("Leite")
        openList()
        compose.onNodeWithTag("edit-${item.id}").performClick()
        compose.onNodeWithTag("edit-name").performTextReplacement("")
        compose.onNodeWithTag("edit-save").performScrollTo().performClick()
        waitForText("edit-error", "Digite um nome")
        assertTrue(house.sent.isEmpty())
        compose.onNodeWithTag("edit-name").performTextReplacement("Café")
        house.commandSteps += Step.ServerError
        compose.onNodeWithTag("edit-save").performScrollTo().performClick()
        waitForText("edit-error", "Seu texto foi mantido")
        compose.onNodeWithTag("edit-name").assertTextContains("Café")
        assertEquals("Leite", item.name)
    }

    @Test fun swipingLeftRemovesAndUndoRestoresOnlyOnThisDevice() {
        val item = house.seed("Leite", status = "purchased")
        openList()
        compose.onNodeWithTag("item-${item.id}").performTouchInput { swipeLeft() }
        waitForText("toast-message", "Leite removido")
        compose.onNodeWithTag("item-${item.id}").assertDoesNotExist()
        compose.onNodeWithTag("toast-undo").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("item-${item.id}").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("item-${item.id}").assertIsOn()
        assertEquals(listOf("remove_item", "undo_remove"), commandTypes())
    }

    @Test fun swipingRightDoesNotRemoveOrMark() {
        val item = house.seed("Leite")
        openList()
        compose.onNodeWithTag("item-${item.id}").performTouchInput { swipeRight() }
        compose.onNodeWithTag("item-${item.id}").assertIsOff()
        assertTrue(house.sent.isEmpty())
    }

    @Test fun shoppingModeDisablesSwipeRemoval() {
        val item = house.seed("Leite")
        val repository = launch()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("add-input").fetchSemanticsNodes().isNotEmpty() }
        val snapshot = (repository.state.value as OnlineState.Ready).snapshot
        scenario!!.onActivity { activity -> activity.setContent {
            AlvaTheme {
                ListScreen(repository, snapshot, onAddItems = {}, onFailure = { error(it) }, showToast = { error("unexpected removal") }, shoppingMode = true)
            }
        } }
        compose.onNodeWithTag("item-${item.id}").performTouchInput { swipeLeft() }
        compose.onNodeWithTag("item-${item.id}").assertIsOff()
        assertTrue(house.sent.isEmpty())
    }

    @Test fun theListShowsSectionsInCategoryOrderWithXOfYCountsAndThePendingCounter() {
        seedCategories()
        val queijo = house.seed("Queijo")
        val leite = house.seed("Leite", status = "purchased")
        house.seed("Maçã", status = "purchased", category = fruit)
        house.seed("Sabão", category = SnapshotFixtures.OTHERS)
        house.seed("Antigo", category = null)
        openList()
        waitForText("list-summary", "3 pendentes · 2 comprados")

        assertTrue("pending first inside a section", top("item-${queijo.id}") < top("item-${leite.id}"))
        compose.onNodeWithTag("category-count-Hortifruti").assertTextEquals("1/1")
        compose.onNodeWithTag("category-count-Laticínios").assertTextEquals("1/2")
        assertTrue("no count until something is bought", compose.onAllNodesWithTag("category-count-Outros").fetchSemanticsNodes().isEmpty())
        compose.onNodeWithTag("list-counter", useUnmergedTree = true).assertContentDescriptionEquals("3 pendentes")
        compose.onNodeWithTag("nav-add").assertIsNotSelected()
        compose.onNodeWithTag("nav-list").assertIsSelected()
        compose.onNodeWithText("Alva").assertIsDisplayed()
        compose.onNodeWithText("Nossa casa").assertIsDisplayed()
        compose.onNodeWithText("Lista de compras").assertIsDisplayed()
        // LazyColumn does not compose every section on a small viewport. Section ordering is
        // covered by ListGroupsTest; here verify that every heading can actually be reached.
        val list = compose.onNode(hasScrollToNodeAction())
        listOf("Hortifruti", "Laticínios", "Outros", "Sem categoria").forEach { label ->
            list.performScrollToNode(hasTestTag("category-$label"))
            compose.onNodeWithTag("category-$label").assertIsDisplayed()
        }
    }

    @Test fun anEmptyListSaysSoAndInvitesToAdd() {
        openList()
        waitForText("list-summary", "Nada na lista")
        compose.onNodeWithText("Nada na lista por enquanto.").assertIsDisplayed()
        compose.onNodeWithTag("empty-add").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("add-input").fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun markingShowsTheUndoToastAndUndoPutsTheItemBackOnTheServer() {
        val leite = house.seed("Leite")
        openList()
        compose.onNodeWithTag("item-${leite.id}").performClick()
        assertMarked(leite.id, true)
        waitForText("toast-message", "Leite marcado")
        compose.onNodeWithTag("toast-undo").assertTextEquals("Desfazer")

        compose.onNodeWithTag("toast-undo").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("toast-undo").fetchSemanticsNodes().isEmpty() }
        assertMarked(leite.id, false)
        assertEquals("pending", leite.status)
        assertEquals(listOf("set_item_state", "undo_item_state"), commandTypes())
        waitForText("list-summary", "1 pendente")
    }

    @Test fun unmarkingAnItemCanBeUndoneToTheOriginalPurchase() {
        val leite = house.seed("Leite", status = "purchased")
        val bought = leite.purchasedAt
        openList()
        compose.onNodeWithTag("item-${leite.id}").performClick()
        assertMarked(leite.id, false)
        waitForText("toast-message", "Leite desmarcado")

        compose.onNodeWithTag("toast-undo").performClick()
        assertMarked(leite.id, true)
        assertEquals("purchased", leite.status)
        assertEquals(bought, leite.purchasedAt)
    }

    @Test fun theUndoToastGoesAwayByItselfAfterAboutFiveSeconds() {
        val leite = house.seed("Leite")
        openList()
        compose.onNodeWithTag("item-${leite.id}").performClick()
        waitForText("toast-message", "Leite marcado")
        compose.waitUntil(UNDO_TOAST_MILLIS + 4_000) { compose.onAllNodesWithTag("toast-undo").fetchSemanticsNodes().isEmpty() }
        assertEquals("marked", "purchased", leite.status)
    }

    @Test fun aMarkMadeOnAnotherPhoneShowsNoToastHere() {
        val leite = house.seed("Leite")
        openList()
        waitForText("list-summary", "1 pendente")
        leite.status = "purchased"; leite.lock++ // the other phone marked it
        scenario!!.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
        scenario!!.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
        assertMarked(leite.id, true)
        compose.onNodeWithTag("toast-undo").assertDoesNotExist()
    }

    @Test fun anUndoRefusedBecauseTheItemChangedElsewhereSaysSoAndKeepsTheServersState() {
        val leite = house.seed("Leite")
        openList()
        compose.onNodeWithTag("item-${leite.id}").performClick()
        waitForText("toast-message", "Leite marcado")
        leite.lock += 2 // the other phone unmarked and marked it again before this one undid

        compose.onNodeWithTag("toast-undo").performClick()

        waitForText("feedback", "A lista mudou em outro aparelho")
        assertEquals("the other phone's state stands", "purchased", leite.status)
    }

    @Test fun theBottomNavigationHasPlusAndListPillsAndSwitchesScreens() {
        launchLoaded()
        compose.onNodeWithTag("nav-add").assertIsSelected()
        compose.onNodeWithText("Adicionar").assertIsDisplayed()
        compose.onNodeWithText("Lista").assertIsDisplayed()
        compose.onNodeWithTag("list-counter", useUnmergedTree = true).assertContentDescriptionEquals("0 pendentes")
        compose.onNodeWithTag("nav-list").performClick()
        compose.onNodeWithTag("nav-list").assertIsSelected()
        compose.onNodeWithTag("nav-add").performClick()
        compose.onNodeWithTag("nav-add").assertIsSelected()
    }
}
