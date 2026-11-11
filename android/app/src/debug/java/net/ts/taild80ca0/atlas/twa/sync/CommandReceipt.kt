package net.ts.taild80ca0.atlas.twa.sync

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener

/** The `status` of a command's result in `POST /api/v1/commands` (contract v1, "Envelope de comando"). */
enum class ReceiptStatus(val wire: String) {
    APPLIED("applied"),

    /** Already in the desired state; consumed by the server like [APPLIED]. */
    NOOP("noop"),

    /** The server had already processed this UUID; it answers with the original result. */
    DUPLICATE("duplicate"),

    /** A precondition failed. The server consumed nothing; the user's intent must be kept. */
    CONFLICT("conflict"),

    /** Definitive error for the command as sent. Nothing was consumed. */
    REJECTED("rejected"),

    /** `depends_on` has no record on the server yet. No effect; sending the dependent again later may apply it. */
    BLOCKED("blocked"),
    ;

    /** True when the server holds a record of the command (applied, noop or duplicate of those). */
    val recordedByServer: Boolean get() = this == APPLIED || this == NOOP || this == DUPLICATE

    companion object {
        fun fromWire(value: Any?): ReceiptStatus? = entries.firstOrNull { it.wire == value }
    }
}

/**
 * What the server said about one command, checked against the command that was sent.
 * It is a value for the future applier: nothing here touches the queue, the projection or
 * the aliases, and holding one removes nothing. If it is lost, repeating the command
 * returns [ReceiptStatus.DUPLICATE] with the same revision and data.
 *
 * - [revision]: house revision after the command's effect (present for applied/noop/duplicate).
 * - [originalStatus]: for [ReceiptStatus.DUPLICATE], whether the first run was applied or noop.
 * - [error]: the contract `error` code of conflict/rejected/blocked.
 * - [dataJson]: the result's `data` object as JSON, untouched, for the applier to interpret.
 * - [houseRevision]: the house revision at the end of the batch; [snapshotRequired]: the
 *   server says the device's picture of the list is stale.
 */
data class CommandReceipt(
    val commandId: String,
    val status: ReceiptStatus,
    val originalStatus: ReceiptStatus?,
    val revision: Long?,
    val error: String?,
    val dataJson: String?,
    val houseRevision: Long,
    val snapshotRequired: Boolean,
)

/** Why a 200 answer was not accepted as the receipt of the command that was sent. */
enum class InvalidResponse {
    /** The status line is not one this call can mean (a redirect, an unexpected 2xx/4xx/…). */
    UNEXPECTED_STATUS,

    /** Not one complete JSON object: truncated, empty, trailing text or not JSON. */
    NOT_JSON,

    /** The envelope or the result lacks a field the contract guarantees, or has one of the wrong type. */
    WRONG_SHAPE,

    /** Zero or several results, or a result for a different command. */
    WRONG_COMMAND,

    /** A `status` this client does not know. Confirms nothing. */
    UNKNOWN_STATUS,

    /** Revisions or fields that contradict each other (for example a result newer than the batch). */
    INCOHERENT,

    /** The response body was larger than the limit. */
    TOO_LARGE,
}

sealed interface ReceiptParse {
    data class Valid(val receipt: CommandReceipt) : ReceiptParse

    data class Invalid(val reason: InvalidResponse) : ReceiptParse
}

/**
 * Strict reading of the answer to a one-command request. Anything that does not match
 * the contract is [ReceiptParse.Invalid]; nothing is guessed, so an answer can never be
 * mistaken for the receipt of another command.
 */
object CommandReceiptParser {
    private const val MAX_ERROR_CODE = 64

    fun parse(body: ByteArray, expectedCommandId: String): ReceiptParse {
        val root = try {
            val tokener = JSONTokener(String(body, Charsets.UTF_8))
            val value = tokener.nextValue()
            if (value !is JSONObject || tokener.nextClean() != 0.toChar()) return invalid(InvalidResponse.NOT_JSON)
            value
        } catch (_: JSONException) {
            return invalid(InvalidResponse.NOT_JSON)
        }

        val houseRevision = whole(root.opt("revision")) ?: return invalid(InvalidResponse.WRONG_SHAPE)
        val snapshotRequired = root.opt("snapshot_required") as? Boolean ?: return invalid(InvalidResponse.WRONG_SHAPE)
        val results = root.opt("results") as? JSONArray ?: return invalid(InvalidResponse.WRONG_SHAPE)
        if (results.length() != 1) return invalid(InvalidResponse.WRONG_COMMAND)
        val result = results.opt(0) as? JSONObject ?: return invalid(InvalidResponse.WRONG_SHAPE)

        val id = result.opt("id") as? String ?: return invalid(InvalidResponse.WRONG_SHAPE)
        // The server compares UUIDs in lower case and answers with what it normalized.
        if (!id.equals(expectedCommandId, ignoreCase = true)) return invalid(InvalidResponse.WRONG_COMMAND)
        val status = result.opt("status").let { raw ->
            if (raw !is String) return invalid(InvalidResponse.WRONG_SHAPE)
            ReceiptStatus.fromWire(raw) ?: return invalid(InvalidResponse.UNKNOWN_STATUS)
        }

        val data = when {
            !result.has("data") -> null
            else -> result.opt("data") as? JSONObject ?: return invalid(InvalidResponse.WRONG_SHAPE)
        }

        var revision: Long? = null
        var originalStatus: ReceiptStatus? = null
        var error: String? = null
        if (status.recordedByServer) {
            revision = whole(result.opt("revision")) ?: return invalid(InvalidResponse.WRONG_SHAPE)
            // The house revision only grows: a recorded result can never be newer than the batch's end.
            if (revision > houseRevision) return invalid(InvalidResponse.INCOHERENT)
            if (data == null) return invalid(InvalidResponse.WRONG_SHAPE)
            if (status == ReceiptStatus.DUPLICATE) {
                originalStatus = ReceiptStatus.fromWire(result.opt("original_status"))
                if (originalStatus != ReceiptStatus.APPLIED && originalStatus != ReceiptStatus.NOOP) {
                    return invalid(InvalidResponse.INCOHERENT)
                }
            }
        } else {
            error = result.opt("error") as? String
            if (error.isNullOrBlank() || error.length > MAX_ERROR_CODE) return invalid(InvalidResponse.WRONG_SHAPE)
        }

        return ReceiptParse.Valid(
            CommandReceipt(
                commandId = expectedCommandId,
                status = status,
                originalStatus = originalStatus,
                revision = revision,
                error = error,
                dataJson = data?.toString(),
                houseRevision = houseRevision,
                snapshotRequired = snapshotRequired,
            ),
        )
    }

    private fun invalid(reason: InvalidResponse) = ReceiptParse.Invalid(reason)

    /** A JSON integer >= 0 (a float or a string is not one). */
    private fun whole(value: Any?): Long? = when (value) {
        is Int -> value.toLong()
        is Long -> value
        else -> null
    }?.takeIf { it >= 0 }
}
