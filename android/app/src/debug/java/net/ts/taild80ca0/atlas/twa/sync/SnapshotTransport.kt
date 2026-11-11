package net.ts.taild80ca0.atlas.twa.sync

import net.ts.taild80ca0.atlas.twa.access.AccessCredential
import net.ts.taild80ca0.atlas.twa.access.HttpExchange
import net.ts.taild80ca0.atlas.twa.access.HttpFailure
import net.ts.taild80ca0.atlas.twa.access.HttpResult
import net.ts.taild80ca0.atlas.twa.access.ServerEndpoint
import net.ts.taild80ca0.atlas.twa.access.retryAfterSeconds
import org.json.JSONObject

/**
 * The result of one request for the snapshot. Nothing here carries the credential or a body
 * that was not accepted, and none of it touches the device's state: installing a snapshot is
 * [SnapshotInstaller]'s job, after the network is done.
 */
sealed interface SnapshotOutcome {
    /** A complete, well-formed snapshot. Whether it may be installed is not decided here. */
    data class Received(val snapshot: ServerSnapshot) : SnapshotOutcome

    /** No usable answer (network, timeout, TLS, 5xx, 429). Nothing changed; ask again later. */
    data class Transient(val cause: TransientCause, val retryAfterSeconds: Int? = null) : SnapshotOutcome

    /** 401/403: the credential was refused (revoked, password rotated, or not a `sync` credential). */
    data object Unauthorized : SnapshotOutcome

    /** Another 4xx: asking again the same way will not help. */
    data class Refused(val status: Int, val code: String?) : SnapshotOutcome

    /** A 200 that is not a snapshot this client accepts. Nothing of it is kept. */
    data class Invalid(val problem: SnapshotProblem, val detail: String) : SnapshotOutcome
}

/** Reads the household's snapshot. Independent of HTTP for its callers. */
interface SnapshotTransport {
    /** `GET /api/v1/snapshot` authenticated by [credential]; one request, no retry, no redirect. */
    suspend fun fetch(credential: AccessCredential): SnapshotOutcome
}

/**
 * `GET /api/v1/snapshot` over [HttpExchange]: the rules every client of the server shares (https or
 * the device's own loopback, no redirects, timeouts, a cap on the body, no logging). The v1 snapshot is
 * complete (no pagination) and parsed whole; the cap is the one the contract already names as the point
 * to reevaluate it.
 */
class HttpSnapshotTransport(
    endpoint: ServerEndpoint,
    connectTimeoutMillis: Int = 10_000,
    readTimeoutMillis: Int = 60_000,
) : SnapshotTransport {
    private val exchange = HttpExchange(endpoint, connectTimeoutMillis, readTimeoutMillis, MAX_BODY_BYTES)

    override suspend fun fetch(credential: AccessCredential): SnapshotOutcome =
        when (val result = exchange.execute("GET", PATH, credential.token, null)) {
            is HttpResult.Failed -> when (result.failure) {
                HttpFailure.TOO_LARGE -> SnapshotOutcome.Invalid(SnapshotProblem.TOO_LARGE, "body")
                HttpFailure.TLS -> SnapshotOutcome.Transient(TransientCause.TLS)
                HttpFailure.TIMEOUT -> SnapshotOutcome.Transient(TransientCause.TIMEOUT)
                HttpFailure.NETWORK -> SnapshotOutcome.Transient(TransientCause.NETWORK)
            }
            is HttpResult.Received -> when (result.status) {
                200 -> when (val parsed = SnapshotParser.parse(result.body)) {
                    is SnapshotParse.Valid -> SnapshotOutcome.Received(parsed.snapshot)
                    is SnapshotParse.Invalid -> SnapshotOutcome.Invalid(parsed.problem, parsed.detail)
                }
                401, 403 -> SnapshotOutcome.Unauthorized
                429 -> SnapshotOutcome.Transient(TransientCause.RATE_LIMITED, retryAfterSeconds(result.retryAfterHeader))
                in 500..599 -> SnapshotOutcome.Transient(TransientCause.SERVER_ERROR)
                in 400..499 -> SnapshotOutcome.Refused(result.status, errorCode(result.body))
                else -> SnapshotOutcome.Invalid(SnapshotProblem.UNEXPECTED_STATUS, "status")
            }
        }

    /** The contract's error code, only if it has the shape of one (never free text from a body). */
    private fun errorCode(body: ByteArray): String? = try {
        (JSONObject(String(body, Charsets.UTF_8)).opt("error") as? String)?.takeIf { ERROR_CODE.matches(it) }
    } catch (_: Exception) {
        null
    }

    companion object {
        const val PATH = "/api/v1/snapshot"

        /** ~4 MB at 10k archived items, where the contract says to revisit the v1 snapshot. */
        const val MAX_BODY_BYTES = 32 * 1024 * 1024

        private val ERROR_CODE = Regex("[a-z][a-z_]{0,63}")
    }
}
