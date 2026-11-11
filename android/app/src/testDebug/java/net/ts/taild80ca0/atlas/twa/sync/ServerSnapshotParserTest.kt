package net.ts.taild80ca0.atlas.twa.sync

import net.ts.taild80ca0.atlas.twa.data.db.ItemStatusCodes
import net.ts.taild80ca0.atlas.twa.sync.SnapshotFixtures.DAIRY
import net.ts.taild80ca0.atlas.twa.sync.SnapshotFixtures.HOUSE
import net.ts.taild80ca0.atlas.twa.sync.SnapshotFixtures.TRIP
import net.ts.taild80ca0.atlas.twa.sync.SnapshotFixtures.item
import net.ts.taild80ca0.atlas.twa.sync.SnapshotFixtures.snapshot
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The snapshot reader: everything the contract says is kept, and a body that is not exactly it is refused whole. */
class ServerSnapshotParserTest {
    private fun parse(json: JSONObject) = SnapshotParser.parse(SnapshotFixtures.bytes(json))

    private fun valid(json: JSONObject): ServerSnapshot = (parse(json) as SnapshotParse.Valid).snapshot

    private fun invalid(json: JSONObject, problem: SnapshotProblem): String {
        val result = parse(json)
        assertTrue("expected $problem, got $result", result is SnapshotParse.Invalid)
        assertEquals(problem, (result as SnapshotParse.Invalid).problem)
        return result.detail
    }

    private val a = "a0000000-0000-4000-8000-00000000000a"
    private val b = "a0000000-0000-4000-8000-00000000000b"
    private val c = "a0000000-0000-4000-8000-00000000000c"

    @Test
    fun anEmptyHouseholdIsAValidSnapshot() {
        val snapshot = valid(snapshot(revision = 0, dictionaryRevision = 0))
        assertEquals(0L, snapshot.revision)
        assertEquals(HOUSE, snapshot.householdPublicId)
        assertTrue(snapshot.listItems.isEmpty() && snapshot.archivedItems.isEmpty() && snapshot.tombstones.isEmpty())
        // The categories and the dictionary the server already has are not "empty": they come with it.
        assertEquals(2, snapshot.categories.size)
        assertEquals(listOf("leite"), snapshot.catalogItems.map { it.normalizedName })
    }

    @Test
    fun keepsEveryFieldItsPrecisionAndTheServersOrder() {
        val json = snapshot(
            revision = 901,
            dictionaryRevision = 14,
            items = listOf(
                item(b, "Leite", createdAt = "2026-11-10T12:00:00.123457Z", updatedAt = "2026-11-10T12:05:00.000001Z", lockVersion = 3, normalizedName = "leite", position = 4),
                item(a, "Café", status = "purchased", purchasedAt = "2026-11-10T13:00:00.5Z", category = null, createdAt = "2026-11-10T12:00:00.123456Z"),
            ),
            archived = listOf(
                item(c, "Arroz", status = "purchased", purchasedAt = "2026-11-09T17:00:00.000009Z", trip = TRIP, lockVersion = 2),
            ),
            trips = listOf(SnapshotFixtures.trip(finishedAt = "2026-11-09T18:00:00.000001Z", createdAt = "2026-11-09T18:00:00.000002Z")),
            habituals = listOf(SnapshotFixtures.habitual("b0000000-0000-4000-8000-000000000001", "Pão")),
            tombstones = listOf(SnapshotFixtures.tombstone("d0000000-0000-4000-8000-000000000001", 838), SnapshotFixtures.tombstone("d0000000-0000-4000-8000-000000000002", 838, "habitual_item")),
        )

        val snapshot = valid(json)

        assertEquals(901L, snapshot.revision)
        assertEquals(14L, snapshot.dictionaryRevision)
        // The order is the server's, not sorted by anything the device knows.
        assertEquals(listOf(b, a), snapshot.listItems.map { it.publicId })
        val milk = snapshot.listItems[0]
        assertEquals(ServerTime.parseMicros("2026-11-10T12:00:00.123457Z"), milk.createdAtMicros)
        assertEquals(ServerTime.parseMicros("2026-11-10T12:05:00.000001Z"), milk.updatedAtMicros)
        assertEquals(3, milk.lockVersion)
        assertEquals(4, milk.position)
        assertEquals(DAIRY, milk.categoryPublicId)
        assertNull(milk.purchasedAtMicros)
        assertNull(milk.tripPublicId)
        val coffee = snapshot.listItems[1]
        assertEquals(ItemStatusCodes.PURCHASED, coffee.status)
        assertEquals(ServerTime.parseMicros("2026-11-10T13:00:00.5Z"), coffee.purchasedAtMicros)
        assertNull(coffee.categoryPublicId)
        assertNull(coffee.position)
        val rice = snapshot.archivedItems.single()
        assertEquals(TRIP, rice.tripPublicId)
        assertEquals(ServerTime.parseMicros("2026-11-09T17:00:00.000009Z"), rice.purchasedAtMicros)
        assertEquals(ServerTime.parseMicros("2026-11-09T18:00:00.000002Z"), snapshot.shoppingTrips.single().createdAtMicros)
        assertEquals("pão", snapshot.habitualItems.single().normalizedName)
        assertEquals(listOf("list_item", "habitual_item"), snapshot.tombstones.map { it.recordType })
        assertEquals(838L, snapshot.tombstones[0].revision)
    }

    @Test
    fun oversizedIntegersCannotWrapIntoSupportedSchemaOrPositions() {
        invalid(snapshot().put("schema_version", 4_294_967_297L), SnapshotProblem.UNSUPPORTED_SCHEMA)
        val categories = snapshot()
        categories.getJSONArray("categories").getJSONObject(0).put("position", 4_294_967_296L)
        invalid(categories, SnapshotProblem.WRONG_SHAPE)
        invalid(snapshot(items = listOf(item(a, "Leite").put("position", 4_294_967_296L))), SnapshotProblem.WRONG_SHAPE)
    }

    @Test
    fun unknownExtraFieldsAreIgnoredBecauseTheContractOnlyGrowsAdditively() {
        val json = snapshot(items = listOf(item(a, "Leite").put("future_field", 1)))
        json.put("another", JSONObject().put("x", 1))
        assertEquals(listOf(a), valid(json).listItems.map { it.publicId })
    }

    @Test
    fun aSchemaThisClientDoesNotReadIsNotGuessedAt() {
        assertEquals("schema_version", invalid(snapshot().put("schema_version", 2), SnapshotProblem.UNSUPPORTED_SCHEMA))
        assertEquals("schema_version", invalid(snapshot().put("schema_version", 0), SnapshotProblem.UNSUPPORTED_SCHEMA))
    }

    @Test
    fun aBodyThatIsNotOneCompleteJsonObjectIsRefused() {
        fun raw(text: String) = SnapshotParser.parse(text.toByteArray(Charsets.UTF_8))
        val good = snapshot().toString()
        listOf("", "[]", "null", "\"x\"", good.dropLast(1), "$good extra", "$good{}", "{").forEach {
            val result = raw(it)
            assertTrue("'${it.take(20)}' must be refused", result is SnapshotParse.Invalid)
            assertEquals(SnapshotProblem.NOT_JSON, (result as SnapshotParse.Invalid).problem)
        }
        // Bytes that are not UTF-8.
        val broken = good.toByteArray(Charsets.UTF_8).also { it[10] = 0xFF.toByte() }
        assertEquals(SnapshotProblem.NOT_JSON, (SnapshotParser.parse(broken) as SnapshotParse.Invalid).problem)
    }

    @Test
    fun aMissingOrMistypedFieldRefusesTheWholeSnapshot() {
        invalid(snapshot().apply { remove("revision") }, SnapshotProblem.WRONG_SHAPE)
        invalid(snapshot().put("revision", "840"), SnapshotProblem.WRONG_SHAPE)
        // org.json writes 840.0 as 840, so the fraction is put in the text itself.
        val fractional = SnapshotParser.parse(snapshot().toString().replace("\"revision\":840", "\"revision\":840.5").toByteArray())
        assertEquals(SnapshotProblem.WRONG_SHAPE, (fractional as SnapshotParse.Invalid).problem)
        invalid(snapshot().put("revision", -1), SnapshotProblem.WRONG_SHAPE)
        invalid(snapshot().put("dictionary_revision", JSONObject.NULL), SnapshotProblem.WRONG_SHAPE)
        invalid(snapshot().apply { remove("list_items") }, SnapshotProblem.WRONG_SHAPE)
        invalid(snapshot().put("list_items", JSONObject()), SnapshotProblem.WRONG_SHAPE)
        invalid(snapshot().put("household", JSONObject().put("name", "Casa")), SnapshotProblem.WRONG_SHAPE)
        invalid(snapshot().put("list_items", JSONArray().put("x")), SnapshotProblem.WRONG_SHAPE)

        fun withItem(change: JSONObject.() -> Unit) = snapshot(items = listOf(item(a, "Leite").apply(change)))
        assertEquals("list_items[0].status", invalid(withItem { put("status", "bought") }, SnapshotProblem.WRONG_SHAPE))
        invalid(withItem { put("status", 0) }, SnapshotProblem.WRONG_SHAPE)
        invalid(withItem { remove("name") }, SnapshotProblem.WRONG_SHAPE)
        invalid(withItem { put("name", JSONObject.NULL) }, SnapshotProblem.WRONG_SHAPE)
        invalid(withItem { remove("category_public_id") }, SnapshotProblem.WRONG_SHAPE)
        invalid(withItem { put("created_at", "2026-11-10 12:00:00") }, SnapshotProblem.WRONG_SHAPE)
        invalid(withItem { put("updated_at", "2026-11-10T12:00:00.1234567Z") }, SnapshotProblem.WRONG_SHAPE)
        invalid(withItem { put("purchased_at", 12) }, SnapshotProblem.WRONG_SHAPE)
        invalid(withItem { put("lock_version", "3") }, SnapshotProblem.WRONG_SHAPE)
        invalid(withItem { put("lock_version", -1) }, SnapshotProblem.WRONG_SHAPE)
        invalid(withItem { put("public_id", "") }, SnapshotProblem.WRONG_SHAPE)
        invalid(snapshot(archived = listOf(item(a, "Arroz"))), SnapshotProblem.WRONG_SHAPE) // archived needs its trip
    }

    @Test
    fun referencesToAnythingNotInTheSnapshotAreRefused() {
        assertEquals(
            "list_items[0].category_public_id",
            invalid(snapshot(items = listOf(item(a, "Leite", category = "c0000000-0000-4000-8000-0000000000ff"))), SnapshotProblem.BROKEN_REFERENCE),
        )
        assertEquals(
            "archived_items[0].shopping_trip_public_id",
            invalid(snapshot(archived = listOf(item(a, "Arroz", trip = TRIP))), SnapshotProblem.BROKEN_REFERENCE),
        )
        val dictionary = snapshot().apply {
            getJSONArray("catalog_items").getJSONObject(0).put("category_public_id", "c0000000-0000-4000-8000-0000000000ff")
        }
        assertEquals("catalog_items[0].category_public_id", invalid(dictionary, SnapshotProblem.BROKEN_REFERENCE))
    }

    @Test
    fun anIdOrNormalizedNameThatAppearsTwiceIsRefused() {
        invalid(snapshot(items = listOf(item(a, "Leite"), item(a, "Café"))), SnapshotProblem.BROKEN_REFERENCE)
        // The same item live and archived.
        invalid(snapshot(items = listOf(item(a, "Leite")), archived = listOf(item(a, "Leite", trip = TRIP)), trips = listOf(SnapshotFixtures.trip())), SnapshotProblem.BROKEN_REFERENCE)
        invalid(snapshot(trips = listOf(SnapshotFixtures.trip(), SnapshotFixtures.trip())), SnapshotProblem.BROKEN_REFERENCE)
        invalid(snapshot(habituals = listOf(SnapshotFixtures.habitual(a, "Pão"), SnapshotFixtures.habitual(a, "Café"))), SnapshotProblem.BROKEN_REFERENCE)
        val twice = snapshot().apply {
            getJSONArray("categories").put(getJSONArray("categories").getJSONObject(0))
        }
        invalid(twice, SnapshotProblem.BROKEN_REFERENCE)
        val entries = snapshot().apply {
            val e = getJSONArray("catalog_items")
            e.put(JSONObject(e.getJSONObject(0).toString()).put("public_id", "e0000000-0000-4000-8000-0000000000aa"))
        }
        assertEquals("catalog_items.normalized_name", invalid(entries, SnapshotProblem.BROKEN_REFERENCE))
    }
}
