package net.ts.taild80ca0.atlas.twa.sync

import net.ts.taild80ca0.atlas.twa.access.AccessCredential
import net.ts.taild80ca0.atlas.twa.access.ServerEndpoint
import net.ts.taild80ca0.atlas.twa.access.HttpExchange
import net.ts.taild80ca0.atlas.twa.access.HttpFailure
import net.ts.taild80ca0.atlas.twa.access.HttpResult
import net.ts.taild80ca0.atlas.twa.access.retryAfterSeconds
import org.json.JSONObject
import org.json.JSONTokener

/** Why one request did not give an answer about the command. The command may or may not have been applied. */
enum class TransientCause {
    /** No connectivity, DNS, reset, or the answer was cut off. The server may have committed the command. */
    NETWORK,

    /** Connecting, or waiting for the server, timed out. The server may have committed the command. */
    TIMEOUT,

    /** The TLS handshake or certificate was refused. Never retried insecurely. */
    TLS,

    /** The server answered 5xx (`internal_error`, restarting…). */
    SERVER_ERROR,

    /** 429. [CommandOutcome.Transient.retryAfterSeconds] is what the server asked, when it said. */
    RATE_LIMITED,
}

/**
 * The result of exactly one request for one command. It carries no HTTP detail, no body,
 * no secret and no credential, so it is safe to log and to show. None of the cases removes,
 * confirms or edits anything: the queue is the caller's, and applying a [Receipt] is the
 * job of the reconciliation that comes after this transport.
 */
sealed interface CommandOutcome {
    /** The server answered about this command, and the answer is valid. [CommandReceipt.status] may still be a refusal of the business rule. */
    data class Receipt(val receipt: CommandReceipt) : CommandOutcome

    /** No usable answer. Repeat the same document later; the idempotency of the command id makes that safe. */
    data class Transient(val cause: TransientCause, val retryAfterSeconds: Int? = null) : CommandOutcome

    /** 401/403: the credential was refused (revoked, password rotated, or not a `sync` credential). */
    data object Unauthorized : CommandOutcome

    /**
     * The request as a whole was refused (422 `malformed_command`/`unsupported_command`, or
     * another 4xx): repeating the same bytes will not help. [code] is the contract error code
     * when the body had a well-formed one. A document over [CommandLimits.MAX_DOCUMENT_BYTES]
     * is refused the same way (413, `request_too_large`) before anything is sent.
     */
    data class Refused(val status: Int, val code: String?) : CommandOutcome

    /** The answer does not match the contract or this command. It confirms nothing. */
    data class Invalid(val reason: InvalidResponse) : CommandOutcome
}

object CommandLimits {
    /** A command's document is a few KB (at most 30 item names); anything near this is not one. */
    const val MAX_DOCUMENT_BYTES = 256 * 1024
}

/** Sends one frozen command document and reads the answer. Independent of HTTP for its callers. */
interface CommandTransport {
    /**
     * `POST /api/v1/commands` with [document] — the command's frozen JSON, byte for byte —
     * as the only entry of `commands`, authenticated by [credential]. At most one request;
     * never retries, never follows a redirect. [commandId] is the command the answer must be about.
     */
    suspend fun send(credential: AccessCredential, commandId: String, document: String): CommandOutcome
}

/**
 * `POST /api/v1/commands` over [HttpExchange]. The request body is
 * `{"commands":[` + the document + `]}` built by concatenation: the document is never parsed or
 * re-serialized here, so a retry puts the same bytes on the wire. Debug-only like the rest of
 * the native client; [ServerEndpoint] allows only https (or the device's own loopback).
 */
class HttpCommandTransport(
    endpoint: ServerEndpoint,
    connectTimeoutMillis: Int = 10_000,
    readTimeoutMillis: Int = 30_000,
) : CommandTransport {
    private val exchange = HttpExchange(endpoint, connectTimeoutMillis, readTimeoutMillis, MAX_RESPONSE_BYTES)

    override suspend fun send(credential: AccessCredential, commandId: String, document: String): CommandOutcome {
        val body = requestBody(document)
        if (body.size > CommandLimits.MAX_DOCUMENT_BYTES + ENVELOPE_BYTES) {
            return CommandOutcome.Refused(413, "request_too_large")
        }
        return when (val result = exchange.execute("POST", PATH, credential.token, body)) {
            is HttpResult.Failed -> when (result.failure) {
                HttpFailure.TOO_LARGE -> CommandOutcome.Invalid(InvalidResponse.TOO_LARGE)
                HttpFailure.TLS -> CommandOutcome.Transient(TransientCause.TLS)
                HttpFailure.TIMEOUT -> CommandOutcome.Transient(TransientCause.TIMEOUT)
                HttpFailure.NETWORK -> CommandOutcome.Transient(TransientCause.NETWORK)
            }
            is HttpResult.Received -> interpret(result, commandId)
        }
    }

    private fun interpret(response: HttpResult.Received, commandId: String): CommandOutcome = when (response.status) {
        200 -> when (val parsed = CommandReceiptParser.parse(response.body, commandId)) {
            is ReceiptParse.Valid -> CommandOutcome.Receipt(parsed.receipt)
            is ReceiptParse.Invalid -> CommandOutcome.Invalid(parsed.reason)
        }
        401, 403 -> CommandOutcome.Unauthorized
        429 -> CommandOutcome.Transient(
            TransientCause.RATE_LIMITED,
            retryAfterSeconds(response.retryAfterHeader) ?: bodyRetryAfter(response.body),
        )
        in 500..599 -> CommandOutcome.Transient(TransientCause.SERVER_ERROR)
        in 400..499 -> CommandOutcome.Refused(response.status, errorCode(response.body))
        else -> CommandOutcome.Invalid(InvalidResponse.UNEXPECTED_STATUS)
    }

    private fun parseObject(body: ByteArray): JSONObject? = try {
        val tokener = JSONTokener(String(body, Charsets.UTF_8))
        (tokener.nextValue() as? JSONObject)?.takeIf { tokener.nextClean() == 0.toChar() }
    } catch (_: Exception) {
        null
    }

    private fun bodyRetryAfter(body: ByteArray): Int? =
        ((parseObject(body)?.opt("retry_after")) as? Int)?.takeIf { it >= 0 }

    /** The contract's error code, only if it has the shape of one (never free text from a body). */
    private fun errorCode(body: ByteArray): String? =
        (parseObject(body)?.opt("error") as? String)?.takeIf { ERROR_CODE.matches(it) }

    companion object {
        const val PATH = "/api/v1/commands"

        private const val ENVELOPE_BYTES = 16 // {"commands":[ and ]}

        /** One result with a `data` object is a few KB. */
        const val MAX_RESPONSE_BYTES = 1024 * 1024

        private val ERROR_CODE = Regex("[a-z][a-z_]{0,63}")

        /** `{"commands":[<document>]}` as UTF-8. The document is not parsed or altered. */
        fun requestBody(document: String): ByteArray =
            ("{\"commands\":[" + document + "]}").toByteArray(Charsets.UTF_8)
    }
}
