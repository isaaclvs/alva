package net.ts.taild80ca0.atlas.twa.data

import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject

/**
 * test/fixtures/files/android_wire_documents.json, packaged as a test asset: the
 * documents the Rails suite posts to the real endpoint, which the stored intents must
 * produce here.
 */
object WireFixtures {
    private val cases: JSONArray by lazy {
        InstrumentationRegistry.getInstrumentation().context.assets.open("android_wire_documents.json")
            .bufferedReader(Charsets.UTF_8).use { JSONObject(it.readText()).getJSONArray("cases") }
    }

    fun documents(name: String): List<JSONObject> {
        val case = (0 until cases.length()).map { cases.getJSONObject(it) }.first { it.getString("name") == name }
        val documents = case.getJSONArray("documents")
        return (0 until documents.length()).map { documents.getJSONObject(it) }
    }

    /** Key order and spacing are not part of the contract (the server digests sorted keys), so compare trees. */
    fun canonical(value: Any?): String = when (value) {
        is JSONObject -> value.keys().asSequence().sorted().joinToString(",", "{", "}") { "\"$it\":" + canonical(value.get(it)) }
        is JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { canonical(value.get(it)) }
        JSONObject.NULL, null -> "null"
        is String -> JSONObject.quote(value)
        else -> value.toString()
    }
}
