package net.ts.taild80ca0.atlas.twa.sync

import org.json.JSONArray
import org.json.JSONObject

/**
 * `GET /api/v1/snapshot` documents as the server writes them (contract v1), for the JVM and the
 * instrumented tests alike. Built from plain values, never from the production parser, so a test of
 * the parser is not a test of itself.
 */
object SnapshotFixtures {
    const val HOUSE = "10000000-0000-4000-8000-000000000001"
    const val DAIRY = "c0000000-0000-4000-8000-000000000001"
    const val OTHERS = "c0000000-0000-4000-8000-000000000002"
    const val TRIP = "70000000-0000-4000-8000-000000000001"
    const val EARLY = "2026-11-10T12:00:00.123456Z"

    fun item(
        publicId: String,
        name: String,
        status: String = "pending",
        category: String? = DAIRY,
        lockVersion: Int = 0,
        createdAt: String = EARLY,
        updatedAt: String = createdAt,
        purchasedAt: String? = null,
        trip: String? = null,
        normalizedName: String = name.lowercase(),
        position: Int? = null,
    ): JSONObject = JSONObject()
        .put("public_id", publicId)
        .put("name", name)
        .put("normalized_name", normalizedName)
        .put("category_public_id", category ?: JSONObject.NULL)
        .put("status", status)
        .put("position", position ?: JSONObject.NULL)
        .put("purchased_at", purchasedAt ?: JSONObject.NULL)
        .put("created_at", createdAt)
        .put("updated_at", updatedAt)
        .put("lock_version", lockVersion)
        .apply { if (trip != null) put("shopping_trip_public_id", trip) }

    fun trip(publicId: String = TRIP, finishedAt: String = "2026-11-09T18:00:00.000001Z", createdAt: String = finishedAt): JSONObject =
        JSONObject().put("public_id", publicId).put("finished_at", finishedAt).put("created_at", createdAt)

    fun habitual(publicId: String, name: String): JSONObject =
        JSONObject().put("public_id", publicId).put("name", name).put("normalized_name", name.lowercase()).put("created_at", EARLY)

    fun tombstone(publicId: String, revision: Long, type: String = "list_item"): JSONObject =
        JSONObject().put("record_type", type).put("public_id", publicId).put("revision", revision)

    fun snapshot(
        revision: Long = 840,
        dictionaryRevision: Long = 12,
        house: String = HOUSE,
        items: List<JSONObject> = emptyList(),
        archived: List<JSONObject> = emptyList(),
        trips: List<JSONObject> = emptyList(),
        habituals: List<JSONObject> = emptyList(),
        tombstones: List<JSONObject> = emptyList(),
    ): JSONObject = JSONObject()
        .put("schema_version", 1)
        .put("revision", revision)
        .put("dictionary_revision", dictionaryRevision)
        .put("household", JSONObject().put("public_id", house).put("name", "Casa"))
        .put(
            "categories",
            JSONArray()
                .put(JSONObject().put("public_id", DAIRY).put("name", "Laticínios").put("position", 1))
                .put(JSONObject().put("public_id", OTHERS).put("name", "Outros").put("position", 99)),
        )
        .put(
            "catalog_items",
            JSONArray().put(
                JSONObject().put("public_id", "e0000000-0000-4000-8000-000000000001").put("name", "Leite")
                    .put("normalized_name", "leite").put("category_public_id", DAIRY).put("learned", false),
            ),
        )
        .put("list_items", JSONArray(items))
        .put("archived_items", JSONArray(archived))
        .put("shopping_trips", JSONArray(trips))
        .put("habitual_items", JSONArray(habituals))
        .put("tombstones", JSONArray(tombstones))

    fun bytes(snapshot: JSONObject): ByteArray = snapshot.toString().toByteArray(Charsets.UTF_8)
}
