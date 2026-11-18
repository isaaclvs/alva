package net.ts.taild80ca0.atlas.twa.online

import net.ts.taild80ca0.atlas.twa.sync.SnapshotFixtures
import net.ts.taild80ca0.atlas.twa.sync.SnapshotParse
import net.ts.taild80ca0.atlas.twa.sync.SnapshotParser
import net.ts.taild80ca0.atlas.twa.sync.ServerSnapshot
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/** The names the add field suggests: the PWA's datalist sources, built from the snapshot the server already sends. */
class SuggestionNamesTest {
    private fun parse(root: JSONObject): ServerSnapshot =
        (SnapshotParser.parse(SnapshotFixtures.bytes(root)) as SnapshotParse.Valid).snapshot

    private fun catalog(name: String, normalized: String, index: Int) = JSONObject()
        .put("public_id", "e0000000-0000-4000-8000-0000000000%02d".format(index))
        .put("name", name).put("normalized_name", normalized)
        .put("category_public_id", SnapshotFixtures.DAIRY).put("learned", false)

    private fun snapshot(vararg catalogNames: Pair<String, String>, items: List<JSONObject> = emptyList(), archived: List<JSONObject> = emptyList()) =
        SnapshotFixtures.snapshot(items = items, archived = archived, trips = listOf(SnapshotFixtures.trip()))
            .put("catalog_items", JSONArray(catalogNames.mapIndexed { index, entry -> catalog(entry.first, entry.second, index) }))

    @Test
    fun everyCatalogAndHouseholdNameIsOfferedInTheDatalistOrder() {
        val snapshot = parse(
            snapshot("Banana" to "banana", "Detergente" to "detergente", "abacate" to "abacate",
                items = listOf(SnapshotFixtures.item("i-1", "Água"), SnapshotFixtures.item("i-2", "Zebra"))),
        )
        assertEquals(listOf("abacate", "Água", "Banana", "Detergente", "Zebra"), snapshot.suggestionNames())
    }

    @Test
    fun archivedItemsCountTooBecauseHistoryIsWhatThePwaUses() {
        val snapshot = parse(
            snapshot("Leite" to "leite",
                archived = listOf(SnapshotFixtures.item("i-1", "Arroz", status = "purchased", purchasedAt = SnapshotFixtures.EARLY, trip = SnapshotFixtures.TRIP))),
        )
        assertEquals(listOf("Arroz", "Leite"), snapshot.suggestionNames())
    }

    @Test
    fun theHouseholdsOwnSpellingWinsOverTheDictionarys() {
        val snapshot = parse(snapshot("Banana" to "banana", items = listOf(SnapshotFixtures.item("i-1", "banana"))))
        assertEquals(listOf("banana"), snapshot.suggestionNames())
    }

    @Test
    fun theLatestSpellingTheHouseUsedWins() {
        val snapshot = parse(
            snapshot(items = listOf(
                SnapshotFixtures.item("i-1", "pão", createdAt = "2026-11-10T12:00:00.000001Z"),
                SnapshotFixtures.item("i-2", "Pão", createdAt = "2026-11-11T12:00:00.000001Z"),
            )),
        )
        assertEquals(listOf("Pão"), snapshot.suggestionNames())
    }

    @Test
    fun aLiveItemWinsOverAnOlderArchivedOne() {
        val snapshot = parse(
            snapshot(
                items = listOf(SnapshotFixtures.item("i-2", "Pão", createdAt = "2026-11-11T12:00:00.000001Z")),
                archived = listOf(SnapshotFixtures.item("i-1", "pão", status = "purchased", purchasedAt = "2026-11-09T12:00:00.000001Z",
                    createdAt = "2026-11-09T12:00:00.000001Z", trip = SnapshotFixtures.TRIP)),
            ),
        )
        assertEquals(listOf("Pão"), snapshot.suggestionNames())
    }

    @Test
    fun pluralsOfTheSameEntryShowUpOnceBecauseMatchingUsesTheNormalizedName() {
        val snapshot = parse(snapshot("Maçã" to "maca", items = listOf(SnapshotFixtures.item("i-1", "Maçãs"))))
        assertEquals(listOf("Maçãs"), snapshot.suggestionNames())
    }
}
