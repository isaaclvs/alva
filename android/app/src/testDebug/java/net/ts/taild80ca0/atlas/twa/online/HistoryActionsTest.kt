package net.ts.taild80ca0.atlas.twa.online

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import net.ts.taild80ca0.atlas.twa.access.AccessCredential
import net.ts.taild80ca0.atlas.twa.data.list.AddOutcome
import net.ts.taild80ca0.atlas.twa.data.list.IdGenerator
import net.ts.taild80ca0.atlas.twa.online.FakeHouse.Step
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Reading the history and repeating a purchase, over the same in-memory server as the rest of the list. */
class HistoryActionsTest {
    private val house = FakeHouse()
    private val credential = AccessCredential(1, "test-token")
    private val access = object : OnlineAccess {
        override suspend fun credential() = credential
        override suspend fun reportUnauthorized(credential: AccessCredential) {}
    }
    private var counter = 0
    private val scope = CoroutineScope(Dispatchers.Default)
    private val repository = OnlineRepository(
        access, house, house, scope,
        ids = IdGenerator { "00000000-0000-4000-8000-%012d".format(++counter) },
        pause = {},
        history = house,
    )

    /** A finished trip as the fake server keeps it: its items are archived, off the live list. */
    private fun seedTrip(vararg names: String, finishedAt: String = "2026-11-12T21:30:00.000000Z"): String {
        val id = java.util.UUID.randomUUID().toString()
        val archived = names.map { house.seed(it, status = "purchased") }
        house.rows.removeAll(archived.toSet())
        house.trips[id] = archived
        house.tripFinishedAt[id] = finishedAt
        return id
    }

    private fun page(read: HistoryRead) = (read as HistoryRead.Received).page

    @Test fun readsTheNewestMonthAndKeepsTheServersOrder() = runBlocking {
        seedTrip("Sabão", "Banana")
        val older = seedTrip("Arroz", finishedAt = "2026-10-03T12:00:00.000000Z")

        val read = page(repository.history())

        assertEquals("2026-11", read.month)
        assertEquals(listOf("2026-11", "2026-10"), read.months.map { it.key })
        assertEquals("2026-10", read.olderMonth)
        assertEquals(null, read.newerMonth)
        // The server's order: category position, then name without accents.
        assertEquals(listOf("Banana", "Sabão"), read.trips.single().names)
        assertEquals(listOf("Arroz"), page(repository.history("2026-10")).trips.single().names)
        assertEquals("2026-11", page(repository.history("2026-10")).newerMonth)
        assertEquals(listOf<String?>(null, "2026-10", "2026-10"), house.historyRequests)
        assertTrue(read.trips.none { it.publicId == older })
    }

    @Test fun anEmptyHistoryIsAnEmptyPageNotAFailure() = runBlocking {
        val read = page(repository.history())

        assertTrue(read.isEmpty)
        assertEquals(null, read.month)
        assertTrue(read.months.isEmpty())
    }

    @Test fun aFailedReadNeverTakesTheListAway() = runBlocking {
        seedTrip("Sabão")
        repository.refresh()
        house.historySteps += Step.NetworkDown

        assertTrue(repository.history() is HistoryRead.Transient)
        assertTrue(repository.state.value is OnlineState.Ready)
    }

    @Test fun aRefusedCredentialIsReportedAsUnauthorized() = runBlocking {
        house.historySteps += Step.Unauthorized

        assertEquals(HistoryRead.Unauthorized, repository.history())
    }

    @Test fun readdingATripPutsItsNamesBackWithTheExistingCommand() = runBlocking {
        val trip = seedTrip("Sabão", "Banana")
        repository.refresh()
        val page = page(repository.history())

        val readd = repository.readdHistory(page.trips.single())

        assertTrue(readd is HistoryReadd.Done)
        assertEquals(listOf("Banana", "Sabão"), (readd as HistoryReadd.Done).items.map { it.name })
        assertTrue(readd.items.all { it.outcome == AddOutcome.CREATED })
        // The same command the add field sends: no history-specific command exists.
        val document = JSONObject(house.sent.single().second)
        assertEquals("add_items", document.getString("type"))
        assertEquals(listOf("Banana", "Sabão"), house.rows.map { it.name })
        assertEquals(trip, house.trips.keys.single())
    }

    @Test fun readdingAgainCountsWhatIsAlreadyOnTheList() = runBlocking {
        seedTrip("Sabão", "Banana")
        repository.refresh()
        val trip = page(repository.history()).trips.single()

        repository.readdHistory(trip)
        val again = repository.readdHistory(trip) as HistoryReadd.Done

        assertTrue(again.items.all { it.outcome == AddOutcome.DUPLICATE })
        assertEquals(2, house.rows.size)
    }

    @Test fun readdingMoreNamesThanOneCommandTakesGoesInBatches() = runBlocking {
        seedTrip(*(1..35).map { "Produto $it" }.toTypedArray())
        repository.refresh()
        val trip = page(repository.history()).trips.single()

        val readd = repository.readdHistory(trip) as HistoryReadd.Done

        assertEquals(35, readd.items.size)
        assertEquals(listOf(30, 5), house.sent.map { JSONObject(it.second).getJSONObject("payload").getJSONArray("items").length() })
        assertEquals(35, house.rows.size)
    }

    @Test fun aBatchThatFailsStopsTheRestAndSaysWhatWentThrough() = runBlocking {
        seedTrip(*(1..35).map { "Produto $it" }.toTypedArray())
        repository.refresh()
        val trip = page(repository.history()).trips.single()
        house.commandSteps += Step.NetworkDown
        house.commandSteps += Step.NetworkDown
        house.commandSteps += Step.NetworkDown

        val readd = repository.readdHistory(trip)

        assertTrue(readd is HistoryReadd.Failed)
        assertTrue((readd as HistoryReadd.Failed).result is ActionResult.Offline)
        assertEquals(0, house.rows.size)
    }
}
