package net.ts.taild80ca0.atlas.twa.ui

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
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
import net.ts.taild80ca0.atlas.twa.sync.SnapshotFixtures
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.json.JSONObject

/** The Adicionar screen over the online repository, with a server in memory standing in for Rails. */
class AddScreenTest {
    @org.junit.Before fun resetDevicePreferences() = kotlinx.coroutines.runBlocking {
        net.ts.taild80ca0.atlas.twa.preferences.DevicePreferencesStore(
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext).reset()
    }
    @get:Rule val compose = createEmptyComposeRule()
    private val house = FakeHouse()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var scenario: ActivityScenario<MainActivity>? = null
    private val noNetwork = object : HouseAccessApi {
        override suspend fun pair(password: String, deviceName: String, previousToken: String?): ApiResult<AccessCredential> = error("UI must not pair")
        override suspend fun identifyHousehold(credential: AccessCredential): ApiResult<HouseholdIdentity> = error("UI must not use HTTP")
    }

    private fun launch(): OnlineRepository {
        val vault = object : CredentialVault {
            override fun read() = VaultRead.Found(StoredAccess(AccessCredential(1, "test-only")))
            override fun write(access: StoredAccess) = error("opening must not write credentials")
        }
        val access = HouseAccess(vault, noNetwork, InMemoryHouseholdBinding())
        val repository = OnlineRepository(OnlineAccess.of(access), house, house, scope, pause = {})
        AccessGraph.install(access, online = repository)
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("add-input").fetchSemanticsNodes().isNotEmpty() }
        return repository
    }

    private fun clickHabitual(tag: String) {
        compose.onNodeWithTag(tag).performScrollTo().performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { it() }
    }

    @Test fun habitualPanelCreatesSelectsAddsAndRemovesWithoutChangingRecentAdds() {
        launch()
        clickHabitual("habitual-toggle")
        compose.onNodeWithText("Ainda não há itens habituais salvos.").assertExists()
        compose.onNodeWithTag("habitual-name").performScrollTo().performTextInput("Café")
        clickHabitual("habitual-save")
        waitForText("habitual-feedback", "Café salvo nos habituais")
        compose.onNodeWithTag("habitual-name").assert(SemanticsMatcher("habitual input is empty") { it.config[SemanticsProperties.EditableText].text.isEmpty() })
        clickHabitual("habitual-select-Café")
        clickHabitual("habitual-add-selected")
        waitForText("habitual-feedback", "1 adicionados à lista")
        compose.onNodeWithTag("recent-adds").assertDoesNotExist()
        clickHabitual("habitual-add-all")
        waitForText("habitual-feedback", "0 adicionados, 1 já estavam na lista")
        compose.onNodeWithContentDescription("Remover item habitual Café").performScrollTo()
            .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { it() }
        waitForText("habitual-feedback", "Café removido dos habituais")
        assertEquals(1, house.rows.size)
        assertTrue(house.habituals.isEmpty())
    }

    @Test fun habitualDraftSelectionAndPanelSurviveRefreshAndNavigationAndFailuresKeepText() {
        house.habituals += SnapshotFixtures.habitual(java.util.UUID.randomUUID().toString(), "Café")
        val repository = launch()
        clickHabitual("habitual-toggle")
        clickHabitual("habitual-select-Café")
        compose.onNodeWithTag("habitual-name").performScrollTo().performTextInput("Arroz")
        kotlinx.coroutines.runBlocking { repository.refresh() }
        compose.onNodeWithTag("habitual-select-Café").assertIsOn()
        compose.onNodeWithTag("nav-list").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { it() }
        compose.onNodeWithTag("nav-add").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { it() }
        compose.onNodeWithTag("habitual-name").assertTextEquals("Arroz")
        house.commandSteps += Step.ServerError
        clickHabitual("habitual-save")
        waitForText("habitual-feedback", "O servidor não está disponível agora")
        compose.onNodeWithTag("habitual-name").assertTextEquals("Arroz")
        compose.onNodeWithTag("habitual-select-Café").assertIsOn()
    }

    @Test fun habitualValidationDuplicateAndRecreationKeepTheForm() {
        house.habituals += SnapshotFixtures.habitual(java.util.UUID.randomUUID().toString(), "Café")
        launch()
        clickHabitual("habitual-toggle")
        clickHabitual("habitual-save")
        waitForText("habitual-feedback", "Digite o nome do item habitual")
        compose.onNodeWithTag("habitual-name").performTextInput("x".repeat(101))
        clickHabitual("habitual-save")
        waitForText("habitual-feedback", "O item habitual deve ter até 100 caracteres")
        compose.onNodeWithTag("habitual-name").performTextReplacement("Café")
        clickHabitual("habitual-save")
        waitForText("habitual-feedback", "Esse item habitual já está salvo")
        compose.onNodeWithTag("habitual-name").assertTextEquals("Café")
        clickHabitual("habitual-select-Café")
        scenario!!.recreate()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("habitual-name").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("habitual-name").assertTextEquals("Café")
        compose.onNodeWithTag("habitual-select-Café").assertIsOn()
    }

    private fun recommendation(name: String) = JSONObject().put("name", name)
        .put("category_public_id", SnapshotFixtures.DAIRY).put("trips", 2).put("last_purchased_at", SnapshotFixtures.EARLY)

    @Test fun buyAgainShowsAtMostFiveShortcutsAndAddsWithoutErasingTheDraft() {
        house.buyAgain = listOf("Leite", "Pão", "Café", "Ovos", "Arroz", "Feijão").map(::recommendation)
        launch()
        compose.onNodeWithText("Comprar de novo").assertExists()
        compose.onNodeWithTag("buy-again-Arroz").assertExists()
        compose.onNodeWithTag("buy-again-Feijão").assertDoesNotExist()
        compose.onNodeWithTag("add-input").performTextInput("Farinha")
        compose.onNodeWithTag("buy-again-Leite").performScrollTo()
            .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { it() }
        waitForText("feedback", "Leite adicionado")
        compose.onNodeWithTag("add-input").assertTextContains("Farinha")
        compose.onNodeWithTag("buy-again-Leite").assertDoesNotExist()
        compose.onNodeWithTag("recent-adds").assertExists()
        assertEquals(1, house.rows.count { it.name == "Leite" })
    }

    @Test fun theFrequentTouchesAreAtLeast48DpHigh() {
        house.buyAgain = listOf(recommendation("Leite"))
        house.habituals += SnapshotFixtures.habitual(java.util.UUID.randomUUID().toString(), "Café")
        launch()
        compose.onNodeWithTag("buy-again-Leite").assertHeightIsAtLeast(48.dp)

        typeAndAdd("Café")
        waitForText("feedback", "Café adicionado em Outros")
        val itemId = house.named("Café").single().id
        compose.onNodeWithTag("recent-category-$itemId").assertHeightIsAtLeast(48.dp)

        clickHabitual("habitual-toggle")
        compose.onNodeWithTag("habitual-select-Café").assertHeightIsAtLeast(48.dp)
        compose.onNodeWithContentDescription("Remover item habitual Café")
            .assertHeightIsAtLeast(48.dp)
    }

    @Test fun failedShortcutKeepsTheDraftAndRecommendationsFollowTheNextSnapshot() {
        house.buyAgain = listOf(recommendation("Leite"))
        val repository = launch()
        compose.onNodeWithTag("add-input").performTextInput("Farinha")
        house.commandSteps += Step.ServerError
        compose.onNodeWithTag("buy-again-Leite").performScrollTo()
            .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { it() }
        waitForText("feedback", "O servidor não está disponível agora")
        compose.onNodeWithTag("add-input").assertTextContains("Farinha")
        compose.onNodeWithTag("buy-again-Leite").assertExists()
        assertTrue(house.rows.isEmpty())
        house.buyAgain = listOf(recommendation("Pão"))
        kotlinx.coroutines.runBlocking { repository.refresh() }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("buy-again-Pão").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("buy-again-Leite").assertDoesNotExist()
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

    private fun waitUntilGone(tag: String) = compose.waitUntil(10_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isEmpty() }

    private val emptyInput = SemanticsMatcher("editable text is empty") {
        it.config[SemanticsProperties.EditableText].text.isEmpty()
    }

    private fun waitForEmptyField() = compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("add-input") and emptyInput).fetchSemanticsNodes().isNotEmpty() }

    private fun typeAndAdd(text: String) {
        // A tap while the previous add is still being answered is ignored, so wait for the button to be idle.
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("save-add") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("add-input").performTextInput(text)
        compose.onNodeWithTag("save-add").performClick()
    }

    private fun pick(itemId: String, category: String) {
        // With the keyboard up the screen scrolls: bring the picker into view first (and open it by its action,
        // as the injected touch lands on the keyboard's edge of the shrunken window).
        compose.onNodeWithTag("recent-category-$itemId").performScrollTo().performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { it() }
        compose.onNodeWithTag("category-option-$category").performClick()
    }

    @Test fun opensWithTheFieldFocusedAndTheEmptyStateOfThePwa() {
        launch()
        compose.onNodeWithTag("add-input").assertIsFocused()
        compose.onNodeWithText("O que está faltando em casa?").assertIsDisplayed()
        compose.onNodeWithText("Lembrou, adicionou.").assertIsDisplayed()
        compose.onNodeWithText("Ex.: leite, pão, café").assertIsDisplayed()
        compose.onNodeWithText("Separe por vírgula para adicionar vários.").assertIsDisplayed()
        compose.onNodeWithTag("recent-adds").assertDoesNotExist()
        compose.onNodeWithTag("duplicate-hint").assertDoesNotExist()
        compose.onNodeWithText("sem internet", substring = true).assertDoesNotExist()
    }

    @Test fun commaSemicolonAndLinesAddManyNamesOnceEachAndSayHowManyWereAdded() {
        house.seed("Leite")
        launch()
        typeAndAdd("Pão, ovos; café\npão\n  \nLeite")
        waitForText("feedback", "3 itens adicionados · Leite já estava na lista")
        waitForEmptyField()
        assertEquals(listOf("Leite", "Pão", "ovos", "café"), house.rows.map { it.name })
        assertEquals(1, house.sent.size)
    }

    @Test fun onlyTheFirstThirtyNamesAreAddedLikeInThePwa() {
        launch()
        compose.onNodeWithTag("add-input").performTextInput((1..31).joinToString(", ") { "item $it" })
        compose.onNodeWithTag("save-add").performClick()
        waitForText("feedback", "30 itens adicionados")
        waitForEmptyField()
        assertEquals((1..30).map { "item $it" }, house.rows.map { it.name })
        assertEquals(1, house.sent.size)
    }

    @Test fun nothingTypedAsksForAnItem() {
        launch()
        compose.onNodeWithTag("save-add").performClick()
        waitForText("feedback", "Digite pelo menos um item.")
        assertTrue(house.sent.isEmpty())
    }

    @Test fun oneNameGetsItsOwnSentenceAndShowsUpInJustAdded() {
        launch()
        typeAndAdd("Café")
        waitForText("feedback", "Café adicionado em Outros")
        val id = house.named("Café").single().id
        compose.onNodeWithText("Acabou de adicionar").assertExists()
        compose.onNodeWithTag("recent-$id").assertExists()
        // "Outros" means the dictionary did not know it: the picker says so.
        compose.onNodeWithTag("recent-category-$id").assert(hasContentDescription("Outros", substring = true))
    }

    @Test fun aPurchasedNameComesBackAndAnAlreadyPendingOneIsOnlyReported() {
        house.seed("Leite", status = "purchased")
        house.seed("Pão", category = FakeHouse.BAKERY)
        launch()
        typeAndAdd("leite")
        waitForText("feedback", "Leite voltou para a lista")
        assertEquals("pending", house.named("Leite").single().status)
        compose.onNodeWithTag("recent-${house.named("Leite").single().id}").assertExists()

        typeAndAdd("pão")
        waitForText("feedback", "Pão já está na lista")
        compose.onNodeWithTag("recent-${house.named("Pão").single().id}").assertDoesNotExist()
    }

    @Test fun aNameThisAddCreatedCanBeTakenBackFromJustAddedWithTheToast() {
        launch()
        typeAndAdd("Café")
        waitForText("feedback", "Café adicionado em Outros")
        val id = house.named("Café").single().id
        compose.onNodeWithTag("recent-remove-$id").performScrollTo()
            .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { it() }
        waitForText("toast-message", "Café removido")
        compose.onNodeWithTag("recent-$id").assertDoesNotExist()
        assertTrue(house.rows.isEmpty())
        assertEquals(listOf("add_items", "remove_item"), commandTypes())

        compose.onNodeWithTag("toast-undo").performClick()
        compose.waitUntil(10_000) { house.rows.isNotEmpty() }
        compose.onNodeWithTag("recent-$id").assertExists()
        assertEquals(listOf("add_items", "remove_item", "undo_remove"), commandTypes())
    }

    @Test fun aRevivedNameOffersNoRemovalInJustAdded() {
        house.seed("Leite", status = "purchased")
        launch()
        typeAndAdd("leite")
        waitForText("feedback", "Leite voltou para a lista")
        val id = house.named("Leite").single().id
        compose.onNodeWithTag("recent-$id").assertExists()
        // Removing it would delete a bought item's history, not take back this add.
        compose.onNodeWithTag("recent-remove-$id").assertDoesNotExist()
        assertEquals(listOf("add_items"), commandTypes())
    }

    private fun commandTypes() = house.sent.map { JSONObject(it.second).getString("type") }

    @Test fun pickingACategoryCorrectsTheItemAndTeachesTheDictionary() {        launch()
        typeAndAdd("Café")
        waitForText("feedback", "Café adicionado em Outros")
        val row = house.named("Café").single()
        assertEquals(SnapshotFixtures.OTHERS, row.category)

        pick(row.id, "Mercearia")
        compose.waitUntil(10_000) { house.named("Café").single().category == FakeHouse.PANTRY }
        assertEquals("the correction teaches the dictionary", FakeHouse.PANTRY, house.learned["café"])
        compose.waitUntil(10_000) {
            compose.onAllNodes(hasTestTag("recent-category-${row.id}") and hasContentDescription("Mercearia", substring = true))
                .fetchSemanticsNodes().isNotEmpty()
        }
        val correction = house.sent.last().second
        assertTrue(correction, correction.contains(""""type":"correct_item"""") && correction.contains(""""category_id":"${FakeHouse.PANTRY}""""))
    }

    @Test fun aCorrectionThatCannotBeSentSaysSoAndTheItemStaysWhereItWas() {
        launch()
        typeAndAdd("Café")
        waitForText("feedback", "Café adicionado em Outros")
        val row = house.named("Café").single()
        house.commandSteps += listOf(Step.NetworkDown, Step.NetworkDown, Step.NetworkDown)
        pick(row.id, "Mercearia")
        waitForText("feedback", "Sem conexão com o servidor")
        assertEquals(SnapshotFixtures.OTHERS, row.category)
        compose.onNodeWithTag("recent-category-${row.id}").assert(hasContentDescription("Outros", substring = true))
    }

    @Test fun justAddedKeepsTheNewestThreeWithTheLastTypedFirst() {
        launch()
        typeAndAdd("Arroz, Feijão")
        waitForText("feedback", "2 itens adicionados") // the feedback comes right before the add is over
        waitForEmptyField()
        typeAndAdd("Sal, Açúcar")
        compose.waitUntil(10_000) { house.rows.size == 4 }
        waitForEmptyField()
        val ids = listOf("Açúcar", "Sal", "Feijão").map { house.named(it).single().id }
        ids.forEach { compose.onNodeWithTag("recent-$it").assertExists() }
        compose.onNodeWithTag("recent-${house.named("Arroz").single().id}").assertDoesNotExist()
        val tops = ids.map { compose.onNodeWithTag("recent-$it").fetchSemanticsNode().boundsInRoot.top }
        assertEquals(tops.sorted(), tops)
    }

    @Test fun theHintNamesWhatIsAlreadyPendingLikeThePwa() {
        house.seed("Leite")
        house.seed("Pão")
        house.seed("Café", status = "purchased")
        launch()
        compose.onNodeWithTag("add-input").performTextInput("leite")
        waitForText("duplicate-hint", "Leite já está na lista")
        compose.onNodeWithTag("add-input").performTextReplacement("pao; LEITE\nCafé, ovos")
        waitForText("duplicate-hint", "Pão e Leite já estão na lista")
        compose.onNodeWithTag("add-input").performTextReplacement("café")
        waitUntilGone("duplicate-hint")
    }

    @Test fun suggestionsCompleteTheNameBeingTypedWithoutAddingIt() {
        house.seed("Leite")
        house.seed("Pão")
        house.seed("Sabão em pó")
        launch()
        // The dictionary (Leite) and the history (Pão, Sabão em pó) both count; accents and case do not.
        compose.onNodeWithTag("add-input").performTextInput("pao")
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("add-suggestion-Pão").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("add-suggestion-Pão").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { it() }
        compose.onNodeWithTag("add-input").assertTextEquals("Pão")
        // Completing does not add: the server only hears about what was sent.
        assertTrue(house.sent.isEmpty())

        // The last comma segment is the one completed; the names before it stay as typed.
        compose.onNodeWithTag("add-input").performTextReplacement("Sabão em pó, lei")
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("add-suggestion-Leite").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("add-suggestion-Leite").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { it() }
        compose.onNodeWithTag("add-input").assertTextEquals("Sabão em pó, Leite")

        // What was completed is added like typed text, with the duplicate the server finds.
        compose.onNodeWithTag("add-input").performTextReplacement("Leite, Pão")
        compose.onNodeWithTag("save-add").performClick()
        waitForText("feedback", "Nada adicionado · Leite e Pão já estavam na lista")
        assertEquals(listOf("Leite", "Pão", "Sabão em pó"), house.rows.map { it.name }.sorted())
    }

    @Test fun aNameTypedExactlyOffersNoSuggestionAndTheRowsAre48DpTall() {
        house.seed("Leite")
        house.seed("Leite condensado")
        launch()
        compose.onNodeWithTag("add-suggestion-Leite condensado").assertDoesNotExist()
        compose.onNodeWithTag("add-input").performTextInput("Leite")
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("add-suggestion-Leite condensado").fetchSemanticsNodes().isNotEmpty() }
        // The name typed in full is not offered again.
        compose.onNodeWithTag("add-suggestion-Leite").assertDoesNotExist()
        compose.onNodeWithTag("add-suggestion-Leite condensado").assertHeightIsAtLeast(48.dp)
    }

    @Test fun theDraftSurvivesTheListScreenAndTheFieldIsFocusedAgain() {
        launch()
        compose.onNodeWithTag("add-input").performTextInput("Café")
        compose.onNodeWithTag("nav-list").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { it() }
        waitUntilGone("add-input")
        compose.onNodeWithTag("nav-add").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { it() }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("add-input").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("add-input").assertTextContains("Café")
        compose.onNodeWithTag("add-input").assertIsFocused()
    }

    @Test fun theFeedbackLeavesAfterAFewSecondsButAProblemStays() {
        launch()
        typeAndAdd("Café")
        waitForText("feedback", "Café adicionado")
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("feedback") and hasText("Café adicionado", substring = true)).fetchSemanticsNodes().isEmpty() }

        house.commandSteps += Step.ServerError
        typeAndAdd("Pão")
        waitForText("feedback", "O servidor não está disponível agora")
        Thread.sleep(AddScreenState.FEEDBACK_MILLIS + 500)
        compose.onNodeWithTag("feedback").assertTextContains("O servidor não está disponível agora", substring = true)
        compose.onNodeWithTag("add-input").assertTextContains("Pão")
    }
}
