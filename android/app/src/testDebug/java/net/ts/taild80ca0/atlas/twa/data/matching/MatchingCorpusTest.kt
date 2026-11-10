package net.ts.taild80ca0.atlas.twa.data.matching

import net.ts.taild80ca0.atlas.twa.data.db.ItemStatusCodes
import net.ts.taild80ca0.atlas.twa.data.db.ListItemEntity
import net.ts.taild80ca0.atlas.twa.data.list.AddPlanner
import net.ts.taild80ca0.atlas.twa.data.list.AddStep
import net.ts.taild80ca0.atlas.twa.data.list.IdGenerator
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Runs the examples of matching_corpus.json against the Kotlin port. The Rails suite
 * (test/services/matching_corpus_test.rb) runs the same file against the originals,
 * so any difference in behavior between the runtimes fails one of the two.
 * [net.ts.taild80ca0.atlas.twa.data.MatchingCorpusStoreTest] (instrumented) runs the
 * `add` examples again through the real Room store.
 */
class MatchingCorpusTest {
    @Test
    fun theTransliterationTableIsExactlyTheOneRailsApplies() {
        val expected = MatchingCorpus.json.getJSONObject("transliteration")
        val actual = Transliteration.APPROXIMATIONS.mapKeys { String(Character.toChars(it.key)) }

        assertEquals(expected.length(), actual.size)
        expected.keys().forEach { char -> assertEquals("approximation of $char", expected.getString(char), actual[char]) }
    }

    @Test
    fun normalizerProducesThePlainFormAndTheKeyOfEveryExample() {
        val cases = MatchingCorpus.section("normalize")
        for (index in 0 until cases.length()) {
            val case = cases.getJSONObject(index)
            val input = case.getString("input")
            assertEquals("plain(${case.getString("input")})", case.getString("plain"), TextNormalizer.plain(input))
            assertEquals("key($input)", case.getString("key"), TextNormalizer.key(input))
        }
    }

    @Test
    fun categorizerPicksTheCategoryOfEveryExampleFromTheSharedDictionary() {
        val categorizer = MatchingCorpus.categorizer()
        val cases = MatchingCorpus.section("categorize")
        for (index in 0 until cases.length()) {
            val case = cases.getJSONObject(index)
            val input = case.getString("input")
            assertEquals("categorize($input)", case.getString("category"), MatchingCorpus.categoryName(categorizer.categoryFor(input)))
        }
    }

    @Test
    fun splitterSplitsEveryExampleTheSameWay() {
        val cases = MatchingCorpus.section("split")
        for (index in 0 until cases.length()) {
            val case = cases.getJSONObject(index)
            val input = case.getString("input")
            assertEquals("split($input)", MatchingCorpus.strings(case.getJSONArray("names")), ItemNameSplitter.split(input))
        }
    }

    @Test
    fun plannerDecidesEveryAddExampleLikeTheServerRules() {
        val cases = MatchingCorpus.section("add")
        for (index in 0 until cases.length()) {
            val case = cases.getJSONObject(index)
            val title = case.getString("title")
            var next = 0
            val planner = AddPlanner(MatchingCorpus.categorizer(), IdGenerator { "new-${next++}" })

            val names = if (case.has("names")) {
                MatchingCorpus.strings(case.getJSONArray("names"))
            } else {
                ItemNameSplitter.split(case.getString("input"))
            }
            val steps = planner.plan(names, existing(case))

            assertEquals(title, MatchingCorpus.strings(case.getJSONArray("outcomes")), steps.map { outcome(it) })
        }
    }

    private fun outcome(step: AddStep) = when (step) {
        is AddStep.Create -> "created"
        is AddStep.Revive -> "revived"
        is AddStep.Duplicate -> "duplicate"
        AddStep.Invalid -> "invalid"
    }

    private fun existing(case: org.json.JSONObject): List<ListItemEntity> {
        val rows = case.getJSONArray("existing")
        return (0 until rows.length()).map { i ->
            val row = rows.getJSONObject(i)
            val purchased = row.getString("status") == "purchased"
            ListItemEntity(
                publicId = "old-$i",
                name = row.getString("name"),
                categoryPublicId = if (row.isNull("category")) null else MatchingCorpus.categoryId(row.getString("category")),
                status = if (purchased) ItemStatusCodes.PURCHASED else ItemStatusCodes.PENDING,
                position = null,
                purchasedAt = if (purchased) 1_000L + i else null,
                createdAt = i.toLong(),
                lockVersion = 1,
                tripPublicId = if (row.optBoolean("archived")) "trip" else null,
            )
        }
    }
}
