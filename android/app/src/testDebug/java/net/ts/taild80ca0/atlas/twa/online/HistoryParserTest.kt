package net.ts.taild80ca0.atlas.twa.online

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryParserTest {
    private fun item(id: String, name: String, category: String? = "c1") = JSONObject()
        .put("public_id", id).put("name", name).put("category_public_id", category ?: JSONObject.NULL)

    private fun trip(id: String, finished: String, items: List<JSONObject>) = JSONObject()
        .put("public_id", id).put("finished_at", finished).put("items_count", items.size)
        .put("items", JSONArray(items))

    private fun page(
        month: String? = "2026-11",
        months: List<Pair<String, Int>> = listOf("2026-11" to 1),
        older: String? = null,
        newer: String? = null,
        trips: List<JSONObject> = listOf(trip("t1", "2026-11-12T21:30:00.000000Z", listOf(item("i1", "Banana"), item("i2", "Sabão", null)))),
    ): JSONObject = JSONObject()
        .put("month", month ?: JSONObject.NULL)
        .put("months", JSONArray(months.map { (key, count) -> JSONObject().put("key", key).put("trip_count", count) }))
        .put("older_month", older ?: JSONObject.NULL)
        .put("newer_month", newer ?: JSONObject.NULL)
        .put("trips", JSONArray(trips))

    private fun bytes(root: JSONObject) = root.toString().toByteArray(Charsets.UTF_8)
    private fun valid(root: JSONObject) = (HistoryParser.parse(bytes(root)) as HistoryParse.Valid).page
    private fun problem(root: JSONObject) = (HistoryParser.parse(bytes(root)) as HistoryParse.Invalid).problem

    @Test fun readsTheMonthItsTripsAndTheirItemsInTheServersOrder() {
        val page = valid(page(months = listOf("2026-11" to 2, "2026-10" to 1), older = "2026-10"))

        assertEquals("2026-11", page.month)
        assertEquals(listOf(HistoryMonth("2026-11", 2), HistoryMonth("2026-10", 1)), page.months)
        assertEquals("2026-10", page.olderMonth)
        assertNull(page.newerMonth)
        val trip = page.trips.single()
        assertEquals("t1", trip.publicId)
        assertEquals(2, trip.itemsCount)
        assertEquals(listOf("Banana", "Sabão"), trip.items.map { it.name })
        assertEquals(listOf("c1", null), trip.items.map { it.categoryPublicId })
        assertEquals(listOf("Banana", "Sabão"), trip.names)
        assertEquals(net.ts.taild80ca0.atlas.twa.sync.ServerTime.parseMicros("2026-11-12T21:30:00.000000Z"), trip.finishedAtMicros)
    }

    @Test fun anEmptyHistoryHasNoMonthAndNoTrips() {
        val page = valid(page(month = null, months = emptyList(), trips = emptyList()))

        assertNull(page.month)
        assertTrue(page.isEmpty)
        assertEquals(HistoryPage.EMPTY, page)
    }

    @Test fun aBodyThatIsNotTheDocumentIsRejectedWhole() {
        assertEquals(HistoryProblem.NOT_JSON, HistoryParser.parse("nada".toByteArray()).let { (it as HistoryParse.Invalid).problem })
        assertEquals(HistoryProblem.NOT_JSON, HistoryParser.parse("{} extra".toByteArray()).let { (it as HistoryParse.Invalid).problem })
        assertEquals(HistoryProblem.WRONG_SHAPE, problem(page().put("month", "2026-13")))
        assertEquals(HistoryProblem.WRONG_SHAPE, problem(page().put("trips", "nenhuma")))
        assertEquals(HistoryProblem.WRONG_SHAPE, problem(page().put("months", JSONArray(listOf(JSONObject().put("key", "2026-11"))))))
    }

    @Test fun aTripThatDisagreesWithItsOwnCountIsRejected() {
        val items = JSONArray(listOf(item("i1", "Banana")))
        assertEquals(HistoryProblem.WRONG_SHAPE, problem(page(trips = listOf(
            trip("t1", "2026-11-12T21:30:00.000000Z", listOf(item("i1", "Banana"), item("i2", "Sabão"))).put("items", items),
        ))))
    }

    @Test fun unknownExtraFieldsAreIgnored() {
        val page = valid(page().put("extra", JSONObject().put("anything", 1)))

        assertEquals("t1", page.trips.single().publicId)
    }
}
