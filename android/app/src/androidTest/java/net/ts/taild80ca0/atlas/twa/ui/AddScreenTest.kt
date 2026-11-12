package net.ts.taild80ca0.atlas.twa.ui

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
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

/** The Adicionar screen over the online repository, with a server in memory standing in for Rails. */
class AddScreenTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val house = FakeHouse()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var scenario: ActivityScenario<MainActivity>? = null
    private val noNetwork = object : HouseAccessApi {
        override suspend fun pair(password: String, deviceName: String, previousToken: String?): ApiResult<AccessCredential> = error("UI must not pair")
        override suspend fun identifyHousehold(credential: AccessCredential): ApiResult<HouseholdIdentity> = error("UI must not use HTTP")
    }

    private fun launch() {
        val vault = object : CredentialVault {
            override fun read() = VaultRead.Found(StoredAccess(AccessCredential(1, "test-only")))
            override fun write(access: StoredAccess) = error("opening must not write credentials")
        }
        val access = HouseAccess(vault, noNetwork, InMemoryHouseholdBinding())
        val repository = OnlineRepository(OnlineAccess.of(access), house, house, scope, pause = {})
        AccessGraph.install(access, online = repository)
        scenario = ActivityScenario.launch(MainActivity::class.java)
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

    @Test fun pickingACategoryCorrectsTheItemAndTeachesTheDictionary() {
        launch()
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

    @Test fun theDraftSurvivesTheListScreenAndTheFieldIsFocusedAgain() {
        launch()
        compose.onNodeWithTag("add-input").performTextInput("Café")
        compose.onNodeWithTag("nav-list").performClick()
        waitUntilGone("add-input")
        compose.onNodeWithTag("nav-add").performClick()
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
