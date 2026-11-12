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
}
