package net.ts.taild80ca0.atlas.twa.online

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import net.ts.taild80ca0.atlas.twa.access.AccessCredential
import net.ts.taild80ca0.atlas.twa.access.HttpExchange
import net.ts.taild80ca0.atlas.twa.access.HttpFailure
import net.ts.taild80ca0.atlas.twa.access.HttpResult
import net.ts.taild80ca0.atlas.twa.access.ServerEndpoint
import net.ts.taild80ca0.atlas.twa.sync.ServerTime
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener

/** One trip of the history: when it was finished and what it took off the list. */
data class HistoryTrip(
    val publicId: String,
    val finishedAtMicros: Long,
    val itemsCount: Int,
    /** In the server's display order: category position, then name without accents. */
    val items: List<HistoryItem>,
) {
    /** The names "Adicionar tudo de novo" puts back on the list, in the order shown. */
    val names: List<String> get() = items.map { it.name }
}

data class HistoryItem(val publicId: String, val name: String, val categoryPublicId: String?)

data class HistoryMonth(val key: String, val tripCount: Int)

/**
 * One page of the read-only history (`GET /api/v1/history`, additive in v1): the
 * month it shows, the trips of that month (newest first) and the months beside
 * it. `month` is null when the house has no finished trip yet.
 */
data class HistoryPage(
    val month: String?,
    val months: List<HistoryMonth>,
    val olderMonth: String?,
    val newerMonth: String?,
    val trips: List<HistoryTrip>,
) {
    val isEmpty: Boolean get() = trips.isEmpty()

    companion object {
        val EMPTY = HistoryPage(null, emptyList(), null, null, emptyList())
    }
}

/** Why a history body was not accepted. The detail names a field, never a value. */
enum class HistoryProblem {
    NOT_JSON,
    WRONG_SHAPE,
    UNEXPECTED_STATUS,
}

sealed interface HistoryRead {
    data class Received(val page: HistoryPage) : HistoryRead

    /** No usable answer: network, timeout, TLS, 5xx or 429. Nothing changed. */
    data class Transient(val offline: Boolean) : HistoryRead

    /** 401/403: the credential was refused. */
    data object Unauthorized : HistoryRead

    /** A body this client does not accept. */
    data class Invalid(val problem: HistoryProblem) : HistoryRead
}

/**
 * Reads the shopping history. Like the snapshot transport, it is one request with no retry and
 * no redirect, authenticated by the device's `sync` credential; the household comes from it.
 */
interface HistoryTransport {
    suspend fun fetch(credential: AccessCredential, month: String? = null): HistoryRead
}

/** `GET /api/v1/history` over [HttpExchange]. */
class HttpHistoryTransport(
    private val endpoint: ServerEndpoint,
    connectTimeoutMillis: Int = 10_000,
    readTimeoutMillis: Int = 30_000,
) : HistoryTransport {
    private val exchange = HttpExchange(endpoint, connectTimeoutMillis, readTimeoutMillis, MAX_BODY_BYTES)

    override suspend fun fetch(credential: AccessCredential, month: String?): HistoryRead {
        val path = if (month.isNullOrBlank()) PATH else PATH + "?month=" + month
        return when (val result = exchange.execute("GET", path, credential.token, null)) {
            is HttpResult.Failed -> when (result.failure) {
                HttpFailure.TOO_LARGE -> HistoryRead.Invalid(HistoryProblem.UNEXPECTED_STATUS)
                HttpFailure.TLS -> HistoryRead.Transient(offline = true)
                HttpFailure.TIMEOUT -> HistoryRead.Transient(offline = true)
                HttpFailure.NETWORK -> HistoryRead.Transient(offline = true)
            }
            is HttpResult.Received -> when (result.status) {
                200 -> when (val parsed = HistoryParser.parse(result.body)) {
                    is HistoryParse.Valid -> HistoryRead.Received(parsed.page)
                    is HistoryParse.Invalid -> HistoryRead.Invalid(parsed.problem)
                }
                401, 403 -> HistoryRead.Unauthorized
                429 -> HistoryRead.Transient(offline = false)
                in 500..599 -> HistoryRead.Transient(offline = false)
                else -> HistoryRead.Invalid(HistoryProblem.UNEXPECTED_STATUS)
            }
        }
    }

    companion object {
        const val PATH = "/api/v1/history"

        /** A page of one month: a few dozen trips of a few names. */
        const val MAX_BODY_BYTES = 4 * 1024 * 1024
    }
}

sealed interface HistoryParse {
    data class Valid(val page: HistoryPage) : HistoryParse
    data class Invalid(val problem: HistoryProblem) : HistoryParse
}

/**
 * Strict reading of the history document. A body that is not exactly the shape the server
 * writes is rejected as a whole, so a screen never shows half a page. Unknown extra fields are
 * ignored: the contract adds fields only additively.
 */
object HistoryParser {
    private const val MAX_ID = 64
    private val MONTH = Regex("""\d{4}-(?:0[1-9]|1[0-2])""")

    fun parse(body: ByteArray): HistoryParse {
        val text = try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(body)).toString()
        } catch (_: CharacterCodingException) {
            return HistoryParse.Invalid(HistoryProblem.NOT_JSON)
        }
        val root = try {
            val tokener = JSONTokener(text)
            val value = tokener.nextValue()
            if (value !is JSONObject || tokener.nextClean() != 0.toChar()) return HistoryParse.Invalid(HistoryProblem.NOT_JSON)
            value
        } catch (_: JSONException) {
            return HistoryParse.Invalid(HistoryProblem.NOT_JSON)
        }
        return try {
            HistoryParse.Valid(read(root))
        } catch (_: Rejected) {
            HistoryParse.Invalid(HistoryProblem.WRONG_SHAPE)
        }
    }

    private class Rejected : Exception()

    private fun shape(): Nothing = throw Rejected()

    private fun monthOrNull(o: JSONObject, key: String): String? = when (val value = o.opt(key)) {
        null, JSONObject.NULL -> null
        is String -> value.takeIf { MONTH.matches(it) } ?: shape()
        else -> shape()
    }

    private fun read(root: JSONObject): HistoryPage {
        val month = monthOrNull(root, "month")
        val months = array(root, "months").map { (index, entry) ->
            val at = "months[$index]"
            val key = entry.opt("key") as? String ?: shape()
            HistoryMonth(key.takeIf { MONTH.matches(it) } ?: shape(), whole(entry, "trip_count", at))
        }
        val older = monthOrNull(root, "older_month")
        val newer = monthOrNull(root, "newer_month")
        val trips = array(root, "trips").map { (index, entry) -> trip(entry, "trips[$index]") }
        return HistoryPage(month, months, older, newer, trips)
    }

    private fun trip(o: JSONObject, at: String): HistoryTrip {
        val publicId = id(o, "public_id", "$at.public_id")
        val finished = o.opt("finished_at") as? String ?: shape()
        val items = array(o, "items").map { (index, item) ->
            val itemAt = "$at.items[$index]"
            val category = when (val value = item.opt("category_public_id")) {
                null, JSONObject.NULL -> null
                is String -> value.takeIf { it.isNotBlank() && it.length <= MAX_ID } ?: shape()
                else -> shape()
            }
            HistoryItem(id(item, "public_id", "$itemAt.public_id"), item.opt("name") as? String ?: shape(), category)
        }
        val count = whole(o, "items_count", at)
        // The count is what the trip looks like on screen; a body that disagrees with its own
        // items is not the server's document.
        if (count != items.size) shape()
        return HistoryTrip(publicId, ServerTime.parseMicros(finished) ?: shape(), count, items)
    }

    private fun array(o: JSONObject, key: String): List<Pair<Int, JSONObject>> {
        val list = o.opt(key) as? org.json.JSONArray ?: shape()
        return (0 until list.length()).map { it to (list.opt(it) as? JSONObject ?: shape()) }
    }

    private fun id(o: JSONObject, key: String, at: String): String {
        val value = o.opt(key) as? String ?: shape()
        return value.takeIf { it.isNotBlank() && it.length <= MAX_ID } ?: shape()
    }

    private fun whole(o: JSONObject, key: String, at: String): Int = when (val value = o.opt(key)) {
        is Int -> value
        is Long -> value.takeIf { it in 0..Int.MAX_VALUE }?.toInt() ?: shape()
        else -> shape()
    }.also { if (it < 0) shape() }
}
