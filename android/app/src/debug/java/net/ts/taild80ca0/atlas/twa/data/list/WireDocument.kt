package net.ts.taild80ca0.atlas.twa.data.list

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener

/**
 * Shape check for the JSON document of one command as contract v1 sends it
 * (`docs/plans/android-sync-contract-v1.md`, "Envelope de comando"):
 *
 * ```json
 * { "id": "…", "type": "add_items", "depends_on": null, "observed_revision": 840,
 *   "preconditions": {}, "payload": { "items": [ { "public_id": "…", "name": "Leite" } ] } }
 * ```
 *
 * It only decides whether a candidate may be *frozen* for a local command. It never
 * produces a document: building one from the local intent (including turning the legacy
 * `payload.names` into `payload.items`) is a later cut, so that nothing legacy can be
 * frozen by accident. The check is deliberately structural; the server stays the
 * authority on every business rule.
 */
object WireDocument {
    private val ALLOWED_KEYS = setOf("id", "type", "depends_on", "observed_revision", "preconditions", "payload")
    private val REQUIRED_KEYS = listOf("id", "type", "depends_on", "preconditions", "payload")
    private val DESIRED = setOf("pending", "purchased")
    private const val MAX_ADD_ITEMS = 30

    /** Null when [candidate] is acceptable for the local command; otherwise why it is not. */
    fun problemWith(candidate: String, commandId: String, type: String, dependsOn: String?): String? {
        val document = try {
            parseStrict(candidate)
        } catch (e: JSONException) {
            return "not a single JSON object"
        }

        document.keys().asSequence().firstOrNull { it !in ALLOWED_KEYS }?.let { return "unknown field $it" }
        REQUIRED_KEYS.firstOrNull { !document.has(it) }?.let { return "missing $it" }
        if (document.opt("id") != commandId) return "id does not match the local command"
        if (document.opt("type") != type) return "type does not match the local command"
        if (document.opt("depends_on") != (dependsOn ?: JSONObject.NULL)) {
            return "depends_on does not match the local command"
        }
        if (document.has("observed_revision")) {
            val revision = document.opt("observed_revision")
            val whole = revision is Int || revision is Long
            if (!whole || (revision as Number).toLong() < 0) return "observed_revision must be an integer >= 0"
        }
        val preconditions = document.opt("preconditions") as? JSONObject ?: return "preconditions must be an object"
        val payload = document.opt("payload") as? JSONObject ?: return "payload must be an object"
        return when (type) {
            CommandTypes.ADD_ITEMS -> addItemsProblem(payload, preconditions)
            CommandTypes.SET_ITEM_STATE -> setItemStateProblem(payload, preconditions)
            else -> "type $type has no wire form yet"
        }
    }

    /** One JSON object and nothing after it (Android's own parser would ignore trailing text). */
    private fun parseStrict(text: String): JSONObject {
        val tokener = JSONTokener(text)
        val value = tokener.nextValue()
        if (value !is JSONObject) throw JSONException("not an object")
        if (tokener.nextClean() != 0.toChar()) throw JSONException("trailing text")
        return value
    }

    private fun addItemsProblem(payload: JSONObject, preconditions: JSONObject): String? {
        if (payload.has("names")) return "payload.names is the legacy local form, not a wire payload"
        if (payload.length() != 1) return "add_items payload takes only items"
        if (preconditions.length() != 0) return "add_items takes no preconditions"
        val items = payload.opt("items") as? JSONArray ?: return "payload.items must be an array"
        if (items.length() < 1 || items.length() > MAX_ADD_ITEMS) return "payload.items takes 1 to $MAX_ADD_ITEMS entries"
        val seen = HashSet<String>()
        for (index in 0 until items.length()) {
            val item = items.opt(index) as? JSONObject ?: return "items[$index] must be an object"
            val publicId = item.opt("public_id") as? String
            if (publicId.isNullOrBlank()) return "items[$index].public_id must be a string"
            if (!seen.add(publicId.lowercase())) return "items[$index].public_id repeats"
            if (item.opt("name") !is String) return "items[$index].name must be a string"
            if (item.has("revives")) {
                val revives = item.opt("revives") as? JSONObject
                val revived = revives?.opt("public_id") as? String
                if (revived.isNullOrBlank()) return "items[$index].revives must carry a public_id"
                // The server treats a UUID it already knows as a duplicate and revives nothing.
                if (revived.lowercase() == publicId.lowercase()) {
                    return "items[$index].public_id must be a new claim, not the purchase it revives"
                }
                if (revives.has("lock_version")) {
                    val version = revives.opt("lock_version")
                    if (!(version is Int || version is Long) || (version as Number).toLong() < 0) {
                        return "items[$index].revives.lock_version must be an integer >= 0"
                    }
                }
            }
        }
        return null
    }

    private fun setItemStateProblem(payload: JSONObject, preconditions: JSONObject): String? {
        preconditions.keys().asSequence().firstOrNull { it != "lock_version" }?.let { return "set_item_state takes only lock_version as a precondition, not $it" }
        if (preconditions.has("lock_version")) {
            val version = preconditions.opt("lock_version")
            if (!(version is Int || version is Long) || (version as Number).toLong() < 0) {
                return "preconditions.lock_version must be an integer >= 0"
            }
        }
        if ((payload.opt("public_id") as? String).isNullOrBlank()) return "payload.public_id must be a string"
        if (payload.opt("desired") !in DESIRED) return "payload.desired must be pending or purchased"
        return null
    }
}
