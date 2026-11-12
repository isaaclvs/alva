package net.ts.taild80ca0.atlas.twa.sync

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import net.ts.taild80ca0.atlas.twa.data.catalog.CatalogEntry
import net.ts.taild80ca0.atlas.twa.data.catalog.CatalogSnapshot
import net.ts.taild80ca0.atlas.twa.data.db.ItemStatusCodes
import net.ts.taild80ca0.atlas.twa.data.list.Category
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener

/**
 * A live or archived item of the snapshot, exactly as the server wrote it. Instants are epoch
 * **microseconds** ([ServerTime]); the order of the lists they come in is the server's id order and
 * is kept (see [ServerSnapshot]).
 */
data class SnapshotItem(
    val publicId: String,
    val name: String,
    val normalizedName: String,
    val categoryPublicId: String?,
    /** [ItemStatusCodes]: 0 pending, 1 purchased. */
    val status: Int,
    val position: Int?,
    val purchasedAtMicros: Long?,
    val createdAtMicros: Long,
    val updatedAtMicros: Long,
    val lockVersion: Int,
    /** Present on `archived_items` only. */
    val tripPublicId: String?,
)

data class SnapshotTrip(val publicId: String, val finishedAtMicros: Long, val createdAtMicros: Long)

data class SnapshotHabitual(val publicId: String, val name: String, val normalizedName: String, val createdAtMicros: Long)

data class SnapshotSuggestion(val name: String, val categoryPublicId: String?, val trips: Long, val lastPurchasedAtMicros: Long)

data class SnapshotTombstone(val recordType: String, val publicId: String, val revision: Long)

/**
 * The complete synchronized state of one household (`GET /api/v1/snapshot`, contract v1), already
 * checked for shape and for references between its parts, but not yet compared with anything on this
 * device: the household, the revisions and the queue are [SnapshotInstaller]'s business.
 *
 * Every list keeps the order the server sent. That order is part of the contract (ascending internal
 * id: the Rails tie-break for the latest trip and the latest spelling), and nothing else can reproduce
 * it. Nothing here interprets history: archived items, trips, habituals and tombstones are carried so
 * that a screen added later has what the server said, with its precision.
 */
data class ServerSnapshot(
    val schemaVersion: Int,
    val revision: Long,
    val dictionaryRevision: Long,
    val householdPublicId: String,
    val householdName: String,
    val categories: List<Category>,
    val catalogItems: List<CatalogEntry>,
    val listItems: List<SnapshotItem>,
    val archivedItems: List<SnapshotItem>,
    val shoppingTrips: List<SnapshotTrip>,
    val habitualItems: List<SnapshotHabitual>,
    val tombstones: List<SnapshotTombstone>,
    val buyAgain: List<SnapshotSuggestion> = emptyList(),
) {
    /** The part [net.ts.taild80ca0.atlas.twa.data.catalog.LocalCatalog] stores. */
    fun catalog() = CatalogSnapshot(categories, catalogItems, dictionaryRevision)

    companion object {
        const val SUPPORTED_SCHEMA = 1
    }
}

/** Why a 200 body was not accepted as a snapshot. The detail names a field, never a value. */
enum class SnapshotProblem {
    /** Not one complete JSON object (truncated, empty, trailing text, not UTF-8). */
    NOT_JSON,

    /** A field the contract guarantees is missing or has the wrong type. */
    WRONG_SHAPE,

    /** `schema_version` is not one this client reads. Nothing is guessed from an unknown version. */
    UNSUPPORTED_SCHEMA,

    /** A record points at a category or trip that is not in the snapshot, or an id appears twice. */
    BROKEN_REFERENCE,

    /** The body was larger than the limit; it was not read to the end. */
    TOO_LARGE,

    /** A status line this call cannot mean (a redirect, an unexpected 2xx...). */
    UNEXPECTED_STATUS,
}

sealed interface SnapshotParse {
    data class Valid(val snapshot: ServerSnapshot) : SnapshotParse

    data class Invalid(val problem: SnapshotProblem, val detail: String) : SnapshotParse
}

/**
 * Strict reading of the snapshot document. A body that is not exactly the contract is
 * [SnapshotParse.Invalid] as a whole: nothing partial is ever handed on, so an installer never
 * has to wonder whether half a snapshot is a snapshot. Unknown extra fields are ignored (the contract
 * adds fields only additively); a missing or mistyped known field is not.
 */
object SnapshotParser {
    private const val MAX_ID = 64

    fun parse(body: ByteArray): SnapshotParse {
        val text = try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(body)).toString()
        } catch (_: CharacterCodingException) {
            return invalid(SnapshotProblem.NOT_JSON, "body")
        }
        val root = try {
            val tokener = JSONTokener(text)
            val value = tokener.nextValue()
            if (value !is JSONObject || tokener.nextClean() != 0.toChar()) return invalid(SnapshotProblem.NOT_JSON, "body")
            value
        } catch (_: JSONException) {
            return invalid(SnapshotProblem.NOT_JSON, "body")
        }
        return try {
            SnapshotParse.Valid(read(root))
        } catch (problem: Rejected) {
            SnapshotParse.Invalid(problem.problem, problem.detail)
        }
    }

    private class Rejected(val problem: SnapshotProblem, val detail: String) : Exception()

    private fun invalid(problem: SnapshotProblem, detail: String) = SnapshotParse.Invalid(problem, detail)

    private fun shape(detail: String): Nothing = throw Rejected(SnapshotProblem.WRONG_SHAPE, detail)

    private fun broken(detail: String): Nothing = throw Rejected(SnapshotProblem.BROKEN_REFERENCE, detail)

    private fun read(root: JSONObject): ServerSnapshot {
        val schema = whole(root, "schema_version", "schema_version")
        if (schema != ServerSnapshot.SUPPORTED_SCHEMA.toLong()) throw Rejected(SnapshotProblem.UNSUPPORTED_SCHEMA, "schema_version")
        val revision = whole(root, "revision", "revision")
        val dictionaryRevision = whole(root, "dictionary_revision", "dictionary_revision")
        val household = obj(root, "household", "household")
        val householdId = id(household, "public_id", "household.public_id")
        val householdName = string(household, "name", "household.name")

        val categories = array(root, "categories").map { (i, c) ->
            Category(id(c, "public_id", "categories[$i].public_id"), string(c, "name", "categories[$i].name"), smallWhole(c, "position", "categories[$i].position"))
        }
        val categoryIds = unique(categories.map { it.publicId }, "categories")

        val catalog = array(root, "catalog_items").map { (i, e) ->
            val at = "catalog_items[$i]"
            CatalogEntry(
                publicId = id(e, "public_id", "$at.public_id"),
                name = string(e, "name", "$at.name"),
                normalizedName = string(e, "normalized_name", "$at.normalized_name"),
                categoryPublicId = id(e, "category_public_id", "$at.category_public_id").also {
                    if (it !in categoryIds) broken("$at.category_public_id")
                },
                learned = bool(e, "learned", "$at.learned"),
            )
        }
        unique(catalog.map { it.publicId }, "catalog_items")
        unique(catalog.map { it.normalizedName }, "catalog_items.normalized_name")

        val trips = array(root, "shopping_trips").map { (i, t) ->
            val at = "shopping_trips[$i]"
            SnapshotTrip(id(t, "public_id", "$at.public_id"), time(t, "finished_at", "$at.finished_at"), time(t, "created_at", "$at.created_at"))
        }
        val tripIds = unique(trips.map { it.publicId }, "shopping_trips")

        val live = array(root, "list_items").map { (i, o) -> item(o, "list_items[$i]", archived = false, categoryIds, tripIds) }
        val archived = array(root, "archived_items").map { (i, o) -> item(o, "archived_items[$i]", archived = true, categoryIds, tripIds) }
        unique((live + archived).map { it.publicId }, "list_items/archived_items")

        val habituals = array(root, "habitual_items").map { (i, h) ->
            val at = "habitual_items[$i]"
            SnapshotHabitual(id(h, "public_id", "$at.public_id"), string(h, "name", "$at.name"), string(h, "normalized_name", "$at.normalized_name"), time(h, "created_at", "$at.created_at"))
        }
        unique(habituals.map { it.publicId }, "habitual_items")

        val tombstones = array(root, "tombstones").map { (i, t) ->
            val at = "tombstones[$i]"
            SnapshotTombstone(string(t, "record_type", "$at.record_type").also { if (it.isBlank() || it.length > MAX_ID) shape("$at.record_type") }, id(t, "public_id", "$at.public_id"), whole(t, "revision", "$at.revision"))
        }
        unique(tombstones.map { Triple(it.recordType, it.publicId, it.revision) }, "tombstones")

        // Additive in v1: older servers can omit recommendations. The server owns their order and ranking.
        val buyAgain = if (!root.has("buy_again")) emptyList() else array(root, "buy_again").map { (i, entry) ->
            val at = "buy_again[$i]"
            val category = if (entry.has("category_public_id") && entry.isNull("category_public_id")) null
                else id(entry, "category_public_id", "$at.category_public_id").also { if (it !in categoryIds) broken("$at.category_public_id") }
            SnapshotSuggestion(string(entry, "name", "$at.name"), category,
                whole(entry, "trips", "$at.trips").also { if (it < 1) shape("$at.trips") },
                time(entry, "last_purchased_at", "$at.last_purchased_at"))
        }

        return ServerSnapshot(
            schema.toInt(), revision, dictionaryRevision, householdId, householdName, categories, catalog,
            live, archived, trips, habituals, tombstones, buyAgain,
        )
    }

    private fun item(o: JSONObject, at: String, archived: Boolean, categoryIds: Set<String>, tripIds: Set<String>): SnapshotItem {
        val status = when (string(o, "status", "$at.status")) {
            "pending" -> ItemStatusCodes.PENDING
            "purchased" -> ItemStatusCodes.PURCHASED
            else -> shape("$at.status")
        }
        val category = if (o.isNull("category_public_id") && o.has("category_public_id")) null else id(o, "category_public_id", "$at.category_public_id")
        if (category != null && category !in categoryIds) broken("$at.category_public_id")
        val trip = if (archived) {
            id(o, "shopping_trip_public_id", "$at.shopping_trip_public_id").also { if (it !in tripIds) broken("$at.shopping_trip_public_id") }
        } else {
            null
        }
        return SnapshotItem(
            publicId = id(o, "public_id", "$at.public_id"),
            name = string(o, "name", "$at.name"),
            normalizedName = string(o, "normalized_name", "$at.normalized_name"),
            categoryPublicId = category,
            status = status,
            position = if (o.isNull("position") && o.has("position")) null else smallWhole(o, "position", "$at.position"),
            purchasedAtMicros = if (o.isNull("purchased_at") && o.has("purchased_at")) null else time(o, "purchased_at", "$at.purchased_at"),
            createdAtMicros = time(o, "created_at", "$at.created_at"),
            updatedAtMicros = time(o, "updated_at", "$at.updated_at"),
            lockVersion = whole(o, "lock_version", "$at.lock_version").also { if (it > Int.MAX_VALUE) shape("$at.lock_version") }.toInt(),
            tripPublicId = trip,
        )
    }

    // ---- typed reads: a wrong type is a rejected snapshot, never a coerced one -----------------

    private fun obj(o: JSONObject, key: String, at: String): JSONObject = o.opt(key) as? JSONObject ?: shape(at)

    private fun array(o: JSONObject, key: String): List<Pair<Int, JSONObject>> {
        val list = o.opt(key) as? JSONArray ?: shape(key)
        return (0 until list.length()).map { it to (list.opt(it) as? JSONObject ?: shape("$key[$it]")) }
    }

    private fun string(o: JSONObject, key: String, at: String): String = o.opt(key) as? String ?: shape(at)

    private fun id(o: JSONObject, key: String, at: String): String =
        string(o, key, at).also { if (it.isBlank() || it.length > MAX_ID) shape(at) }

    private fun bool(o: JSONObject, key: String, at: String): Boolean = o.opt(key) as? Boolean ?: shape(at)

    /** A JSON integer >= 0 (a float or a string is not one). */
    private fun whole(o: JSONObject, key: String, at: String): Long =
        when (val v = o.opt(key)) {
            is Int -> v.toLong()
            is Long -> v
            else -> shape(at)
        }.also { if (it < 0) shape(at) }

    private fun smallWhole(o: JSONObject, key: String, at: String): Int =
        whole(o, key, at).also { if (it > Int.MAX_VALUE) shape(at) }.toInt()

    private fun time(o: JSONObject, key: String, at: String): Long =
        ServerTime.parseMicros(string(o, key, at)) ?: shape(at)

    private fun <T> unique(values: List<T>, at: String): Set<T> {
        val set = values.toSet()
        if (set.size != values.size) broken(at)
        return set
    }
}
