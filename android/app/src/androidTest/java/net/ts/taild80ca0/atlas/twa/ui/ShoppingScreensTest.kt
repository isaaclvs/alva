package net.ts.taild80ca0.atlas.twa.ui

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
import net.ts.taild80ca0.atlas.twa.sync.CableConnector
import net.ts.taild80ca0.atlas.twa.sync.CableEvent
import net.ts.taild80ca0.atlas.twa.sync.CableSocket
import net.ts.taild80ca0.atlas.twa.sync.CommandOutcome
import net.ts.taild80ca0.atlas.twa.sync.CommandTransport
import net.ts.taild80ca0.atlas.twa.sync.ForegroundInvalidation
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
        waitForText("list-summary", "Pendentes: 3 · Comprados: 0")
        val id = house.named("Leite").single().id
        compose.onNodeWithTag("item-$id").performClick()
        assertMarked(id, true)
        waitForText("list-summary", "Pendentes: 2 · Comprados: 1")
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
        waitForText("list-summary", "Pendentes: 1 · Comprados: 1")
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
        waitForText("list-summary", "Pendentes: 1")
        house.commandSteps += listOf(Step.NetworkDown, Step.NetworkDown, Step.NetworkDown)
        compose.onNodeWithTag("item-${leite.id}").performClick()
        waitForText("feedback", "Sem conexão com o servidor")
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
        waitForText("list-summary", "Pendentes: 1")
    }

    @Test fun aFailedReloadKeepsTheListAndFlagsItAsPossiblyOutOfDate() {
        house.seed("Leite")
        launch()
        compose.onNodeWithTag("nav-list").performClick()
        waitForText("list-summary", "Pendentes: 1")
        house.snapshotSteps += Step.NetworkDown
        scenario!!.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
        scenario!!.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
        waitForText("connection-notice", "Sem conexão com o servidor")
        waitForText("list-summary", "Pendentes: 1")
        compose.onNodeWithTag("reload").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("connection-notice").fetchSemanticsNodes().isEmpty() }
        waitForText("list-summary", "Pendentes: 1")
    }

    @Test fun resumingTheAppReadsTheListAgain() {
        launch()
        compose.onNodeWithTag("nav-list").performClick()
        waitForText("list-summary", "Pendentes: 0")
        scenario!!.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
        house.seed("Leite") // another phone added it while this app was in the background
        scenario!!.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
        waitForText("list-summary", "Pendentes: 1")
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
        waitForText("list-summary", "Pendentes: 0")
        receive(CableEvent.Subscribed)
        house.seed("Leite") // the other phone adds
        receive(CableEvent.Invalidated(900, 0))
        waitForText("list-summary", "Pendentes: 1")
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
    }
}
