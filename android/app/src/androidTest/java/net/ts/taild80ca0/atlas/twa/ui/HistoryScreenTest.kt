package net.ts.taild80ca0.atlas.twa.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import java.util.TimeZone
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
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** The read-only "Compras" screen over the online repository, with a server in memory standing in for Rails. */
class HistoryScreenTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val house = FakeHouse()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var scenario: ActivityScenario<MainActivity>? = null
    private val noNetwork = object : HouseAccessApi {
        override suspend fun pair(password: String, deviceName: String, previousToken: String?): ApiResult<AccessCredential> = error("UI must not pair")
        override suspend fun identifyHousehold(credential: AccessCredential): ApiResult<HouseholdIdentity> = error("UI must not use HTTP")
    }

    @Before fun resetDevicePreferences() = kotlinx.coroutines.runBlocking {
        net.ts.taild80ca0.atlas.twa.preferences.DevicePreferencesStore(context).reset()
    }

    @After fun stop() {
        scenario?.close()
        scope.cancel()
    }

    /**
     * A finished trip as the fake server keeps it, with its items archived off the live list. Each name
     * carries its category: "Banana" in Hortifruti, "Sabão" in Limpeza, like the house's own.
     */
    private fun seedTrip(vararg items: Pair<String, String>, finishedAt: String = "2026-11-12T21:30:00.000000Z"): String {
        val id = java.util.UUID.randomUUID().toString()
        val archived = items.map { (name, category) -> house.seed(name, status = "purchased", category = category) }
        house.rows.removeAll(archived.toSet())
        house.trips[id] = archived
        house.tripFinishedAt[id] = finishedAt
        return id
    }

    private fun press(tag: String) = compose.onNodeWithTag(tag)
        .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { it() }

    private fun launch(): OnlineRepository {
        val vault = object : CredentialVault {
            override fun read() = VaultRead.Found(StoredAccess(AccessCredential(1, "test-only")))
            override fun write(access: StoredAccess) = error("opening must not write credentials")
        }
        val access = HouseAccess(vault, noNetwork, InMemoryHouseholdBinding())
        val repository = OnlineRepository(OnlineAccess.of(access), house, house, scope, pause = {}, history = house)
        AccessGraph.install(access, online = repository)
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("shopping-home").fetchSemanticsNodes().isNotEmpty() }
        return repository
    }

    /** Opens the screen and waits for the first page, so the month navigation is there. */
    private fun openHistory(waitForMonth: Boolean = true) {
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("house-menu").fetchSemanticsNodes().isNotEmpty() }
        press("house-menu")
        press("open-history")
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("history-screen").fetchSemanticsNodes().isNotEmpty() }
        if (waitForMonth) compose.waitUntil(10_000) { compose.onAllNodesWithTag("history-month").fetchSemanticsNodes().isNotEmpty() }
    }

    private fun waitForText(tag: String, text: String) = compose.waitUntil(10_000) {
        compose.onAllNodes(hasTestTag(tag) and hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()
    }

    @Test fun theMenuOpensTheHistoryAndATripExpandsIntoItsItemsByCategory() {
        val trip = seedTrip("Banana" to FakeHouse.PRODUCE, "Sabão" to FakeHouse.CLEANING)
        launch()
        openHistory()

        compose.onNodeWithTag("history-title").assertTextEquals("Compras")
        compose.onNodeWithTag("history-month").assertTextEquals("Novembro de 2026")
        compose.onNodeWithTag("history-category-Hortifruti").assertDoesNotExist()
        press("history-trip-summary-$trip")
        compose.onNodeWithTag("history-category-Hortifruti").assertExists()
        compose.onNodeWithTag("history-category-Limpeza").assertExists()
        compose.onNodeWithText("Banana").assertExists()
        compose.onNodeWithText("Sabão").assertExists()
        compose.onNodeWithTag("history-readd").assertExists()
        // History is read-only: no edit or remove control reaches it.
        compose.onNodeWithText("Remover").assertDoesNotExist()
    }

    @Test fun readdingATripPutsItsNamesBackAndSaysWhatItDid() {
        val trip = seedTrip("Banana" to FakeHouse.PRODUCE, "Sabão" to FakeHouse.CLEANING)
        launch()
        openHistory()
        press("history-trip-summary-$trip")
        press("history-readd")

        compose.waitUntil(10_000) { house.rows.size == 2 }
        assertEquals(listOf("Banana", "Sabão"), house.rows.map { it.name })
        assertTrue(house.trips.containsKey(trip))
        press("history-back")
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("add-input").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("feedback").assertTextEquals("2 itens adicionados")
    }

    @Test fun readdingAgainReportsThatTheNamesWereAlreadyThere() {
        val trip = seedTrip("Banana" to FakeHouse.PRODUCE, "Sabão" to FakeHouse.CLEANING)
        launch()
        openHistory()
        press("history-trip-summary-$trip")
        press("history-readd")
        compose.waitUntil(10_000) { house.rows.size == 2 }
        press("history-readd")

        compose.waitUntil(10_000) { house.rows.size == 2 && house.sent.size == 2 }
        press("history-back")
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("feedback") and hasText("Nada adicionado", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("feedback").assertTextEquals("Nada adicionado · Banana e Sabão já estavam na lista")
    }

    @Test fun monthsArePagesAndAnEmptyHistorySaysSo() {
        val trip = seedTrip("Sabão" to FakeHouse.CLEANING)
        seedTrip("Arroz" to FakeHouse.PANTRY, finishedAt = "2026-10-03T12:00:00.000000Z")
        launch()
        openHistory()

        press("history-older")
        waitForText("history-month", "Outubro de 2026")
        compose.onNodeWithTag("history-trip-$trip").assertDoesNotExist()
        press("history-newer")
        waitForText("history-month", "Novembro de 2026")
        compose.onNodeWithTag("history-trip-$trip").assertExists()

        house.trips.clear()
        press("history-back")
        openHistory(waitForMonth = false)
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("history-empty").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Nenhuma compra registrada ainda.").assertExists()
    }

    @Test fun aFailedReadSaysWhyWithoutTakingTheListAway() {
        val trip = seedTrip("Sabão" to FakeHouse.CLEANING)
        house.historySteps += Step.NetworkDown
        launch()
        // The read failed, so there is no month to show: the notice says why, with a way to try again.
        openHistory(waitForMonth = false)

        waitForText("history-problem", "Sem conexão com o servidor")
        // The list is still the server's: history failing never takes it away.
        press("history-reload")
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("history-trip-$trip").fetchSemanticsNodes().isNotEmpty() }
        press("history-back")
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("add-input").fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun theNewestMonthOpensFirstWithItsNewestTripOnTop() {
        val newer = seedTrip("Sabão" to FakeHouse.CLEANING, finishedAt = "2026-11-15T10:00:00.000000Z")
        val older = seedTrip("Arroz" to FakeHouse.PANTRY, finishedAt = "2026-11-01T10:00:00.000000Z")
        launch()
        openHistory()

        val trips = compose.onAllNodesWithTag("history-trip-summary-$newer", useUnmergedTree = true).fetchSemanticsNodes()
        assertEquals(1, trips.size)
        compose.onNodeWithTag("history-trip-summary-$older").assertExists()
        compose.onNodeWithTag("history-older").assertDoesNotExist()
        compose.onNodeWithTag("history-newer").assertDoesNotExist()
    }

    @Test fun theHistoryDatesUseTheDeviceClock() {
        val zone = TimeZone.getDefault()
        val trip = seedTrip("Sabão" to FakeHouse.CLEANING, finishedAt = "2026-11-12T21:30:00.000000Z")
        launch()
        openHistory()
        press("history-trip-summary-$trip")

        // The label is the trip's instant on this device's clock, whatever the zone is.
        compose.onNodeWithTag("history-trip-$trip").assertExists()
        val expected = HistoryDate.label(net.ts.taild80ca0.atlas.twa.sync.ServerTime.parseMicros("2026-11-12T21:30:00.000000Z")!!, zone)
        compose.onNodeWithText(expected).assertExists()
    }

}
