package net.ts.taild80ca0.atlas.twa.data.matching

import net.ts.taild80ca0.atlas.twa.data.list.Category
import org.json.JSONArray
import org.json.JSONObject

/**
 * The examples shared with the Rails suite (test/fixtures/files/matching_corpus.json,
 * exposed to the unit tests as a resource by app/build.gradle).
 */
object MatchingCorpus {
    val json: JSONObject by lazy {
        val stream = checkNotNull(MatchingCorpus::class.java.classLoader?.getResourceAsStream("matching_corpus.json")) {
            "matching_corpus.json is not on the test classpath"
        }
        JSONObject(stream.bufferedReader(Charsets.UTF_8).use { it.readText() })
    }

    fun section(name: String): JSONArray = json.getJSONArray(name)

    /** Stable fake ids: the corpus names categories, the device works with UUIDs. */
    fun categoryId(name: String) = "cat-$name"

    val categories: List<Category> by lazy {
        val array = json.getJSONObject("dictionary").getJSONArray("categories")
        (0 until array.length()).map { array.getJSONObject(it).let { c -> Category(categoryId(c.getString("name")), c.getString("name"), c.getInt("position")) } }
    }

    val entries: List<DictionaryEntry> by lazy {
        val array = json.getJSONObject("dictionary").getJSONArray("entries")
        (0 until array.length()).map {
            array.getJSONObject(it).let { e -> DictionaryEntry(TextNormalizer.key(e.getString("name")), categoryId(e.getString("category"))) }
        }
    }

    fun categorizer() = ItemCategorizer(entries, categories)

    fun categoryName(publicId: String?): String? = categories.firstOrNull { it.publicId == publicId }?.name

    fun strings(array: JSONArray): List<String> = (0 until array.length()).map { array.getString(it) }
}
