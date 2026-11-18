package net.ts.taild80ca0.atlas.twa.sync

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class BuyAgainSnapshotTest {
    private fun suggestion(name: String, category: String? = SnapshotFixtures.DAIRY) = JSONObject()
        .put("name", name).put("category_public_id", category ?: JSONObject.NULL)
        .put("trips", 4).put("last_purchased_at", SnapshotFixtures.EARLY)
    private fun parse(root: JSONObject) = SnapshotParser.parse(SnapshotFixtures.bytes(root))

    @Test fun recommendationsKeepTheServersOrderAndAllowALegacyMissingCategory() {
        val root = SnapshotFixtures.snapshot().put("buy_again", JSONArray(listOf(suggestion("Leite"), suggestion("Antigo", null))))
        val snapshot = (parse(root) as SnapshotParse.Valid).snapshot
        assertEquals(listOf("Leite", "Antigo"), snapshot.buyAgain.map { it.name })
        assertNull(snapshot.buyAgain.last().categoryPublicId)
        assertEquals(4L, snapshot.buyAgain.first().trips)
        assertEquals(ServerTime.parseMicros(SnapshotFixtures.EARLY), snapshot.buyAgain.first().lastPurchasedAtMicros)
    }

    @Test fun olderServersCanOmitTheAdditiveField() {
        assertTrue((parse(SnapshotFixtures.snapshot()) as SnapshotParse.Valid).snapshot.buyAgain.isEmpty())
    }

    @Test fun invalidReferencesAndMalformedRecommendationsRejectTheWholeSnapshot() {
        val reference = parse(SnapshotFixtures.snapshot().put("buy_again", JSONArray(listOf(suggestion("Leite", "unknown")))))
        assertEquals(SnapshotProblem.BROKEN_REFERENCE, (reference as SnapshotParse.Invalid).problem)
        val malformed = parse(SnapshotFixtures.snapshot().put("buy_again", JSONArray(listOf(suggestion("Leite").put("trips", 0)))))
        assertEquals(SnapshotProblem.WRONG_SHAPE, (malformed as SnapshotParse.Invalid).problem)
    }

    // --- cadence, additive in v1 (#163) ---

    @Test fun readTheCadenceAndTheDueFlagWhenTheServerSendsThem() {
        val root = SnapshotFixtures.snapshot().put("buy_again", JSONArray(listOf(
            suggestion("Leite").put("cadence_days", 12).put("due", true),
            suggestion("Pão").put("cadence_days", 3).put("due", false),
        )))

        val snapshot = (parse(root) as SnapshotParse.Valid).snapshot

        assertEquals(12, snapshot.buyAgain.first().cadenceDays)
        assertTrue(snapshot.buyAgain.first().due)
        assertEquals(3, snapshot.buyAgain.last().cadenceDays)
        assertFalse(snapshot.buyAgain.last().due)
    }

    @Test fun anOlderServerWithoutTheCadenceStillReadsBothSuggestions() {
        val root = SnapshotFixtures.snapshot().put("buy_again", JSONArray(listOf(suggestion("Leite"), suggestion("Pão"))))

        val snapshot = (parse(root) as SnapshotParse.Valid).snapshot

        assertEquals(listOf(null, null), snapshot.buyAgain.map { it.cadenceDays })
        assertEquals(listOf(false, false), snapshot.buyAgain.map { it.due })
    }

    @Test fun aNullCadenceIsANameTheArchiveIsTooShortFor() {
        val root = SnapshotFixtures.snapshot().put("buy_again", JSONArray(listOf(suggestion("Leite")
            .put("cadence_days", JSONObject.NULL).put("due", false))))

        val suggestion = (parse(root) as SnapshotParse.Valid).snapshot.buyAgain.single()

        assertNull(suggestion.cadenceDays)
        assertFalse(suggestion.due)
    }

    @Test fun aMistypedCadenceOrDueFlagRejectsTheWholeSnapshot() {
        val cadence = parse(SnapshotFixtures.snapshot().put("buy_again", JSONArray(listOf(suggestion("Leite").put("cadence_days", "12")))))
        assertEquals(SnapshotProblem.WRONG_SHAPE, (cadence as SnapshotParse.Invalid).problem)
        val negative = parse(SnapshotFixtures.snapshot().put("buy_again", JSONArray(listOf(suggestion("Leite").put("cadence_days", -1)))))
        assertEquals(SnapshotProblem.WRONG_SHAPE, (negative as SnapshotParse.Invalid).problem)
        val due = parse(SnapshotFixtures.snapshot().put("buy_again", JSONArray(listOf(suggestion("Leite").put("due", "sim")))))
        assertEquals(SnapshotProblem.WRONG_SHAPE, (due as SnapshotParse.Invalid).problem)
    }
}
