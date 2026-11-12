package net.ts.taild80ca0.atlas.twa.access

import org.json.JSONException
import org.json.JSONObject

/** The household a credential belongs to, as the snapshot names it. */
data class HouseholdIdentity(val publicId: String)

/** The two server calls access needs. */
interface HouseAccessApi {
    /**
     * `POST /api/v1/devices`: trades the shared password for this device's own
     * credential. [previousToken] is the secret the device held before, the only
     * proof that lets the server keep the same lineage.
     */
    suspend fun pair(password: String, deviceName: String, previousToken: String?): ApiResult<AccessCredential>

    /**
     * `GET /api/v1/snapshot` with [credential], read only for `household.public_id`.
     * It proves the credential works and says which household it belongs to. It
     * does **not** apply the snapshot: nothing here claims the list was synced.
     */
    suspend fun identifyHousehold(credential: AccessCredential): ApiResult<HouseholdIdentity>
}

/**
 * `HttpURLConnection` implementation. Never follows redirects (a redirect could
 * carry the password or token to another host), never logs, and never puts a
 * secret in a URL, an exception message or [AccessError].
 */
class HttpHouseAccessApi(
    private val endpoint: ServerEndpoint,
    private val connectTimeoutMillis: Int = 10_000,
    private val readTimeoutMillis: Int = 30_000,
) : HouseAccessApi {

    override suspend fun pair(
        password: String,
        deviceName: String,
        previousToken: String?,
    ): ApiResult<AccessCredential> {
        val body = JSONObject().put("password", password).put("name", deviceName)
        if (previousToken != null) body.put("previous_token", previousToken)
        return when (val response = call("POST", "/api/v1/devices", null, body.toString())) {
            is ApiResult.Failed -> response
            is ApiResult.Ok -> interpretPairing(response.value)
        }
    }

    override suspend fun identifyHousehold(credential: AccessCredential): ApiResult<HouseholdIdentity> =
        when (val response = call("GET", "/api/v1/snapshot", credential.token, null)) {
            is ApiResult.Failed -> response
            is ApiResult.Ok -> interpretSnapshot(response.value)
        }

    private fun interpretPairing(response: Response): ApiResult<AccessCredential> {
        val code = errorCode(response)
        return when (response.status) {
            201 -> {
                val json = parseObject(response.body) ?: return unexpected()
                val deviceId = json.opt("device_id") as? Number
                val token = json.opt("token") as? String
                if (deviceId == null || token.isNullOrBlank()) return unexpected()
                ApiResult.Ok(AccessCredential(deviceId.toLong(), token))
            }
            401 -> failed(AccessError.WrongPassword)
            403 -> if (code == "sync_unavailable") failed(AccessError.SyncUnavailable) else unexpected()
            422 -> if (code == "invalid_previous_token") failed(AccessError.InvalidPreviousToken) else unexpected()
            429 -> failed(AccessError.RateLimited(retryAfter(response)))
            in 500..599 -> failed(AccessError.ServerUnavailable)
            else -> unexpected()
        }
    }

    private fun interpretSnapshot(response: Response): ApiResult<HouseholdIdentity> = when (response.status) {
        200 -> {
            val household = parseObject(response.body)?.optJSONObject("household")
            val publicId = household?.opt("public_id") as? String
            if (publicId.isNullOrBlank() || publicId.length > MAX_PUBLIC_ID) unexpected()
            else ApiResult.Ok(HouseholdIdentity(publicId))
        }
        401, 403 -> failed(AccessError.Unauthorized)
        429 -> failed(AccessError.RateLimited(retryAfter(response)))
        in 500..599 -> failed(AccessError.ServerUnavailable)
        else -> unexpected()
    }

    private class Response(val status: Int, val body: ByteArray, val retryAfterHeader: String?)

    private val exchange = HttpExchange(endpoint, connectTimeoutMillis, readTimeoutMillis, MAX_BODY_BYTES)

    private suspend fun call(
        method: String,
        path: String,
        bearer: String?,
        jsonBody: String?,
    ): ApiResult<Response> =
        when (val result = exchange.execute(method, path, bearer, jsonBody?.toByteArray(Charsets.UTF_8))) {
            is HttpResult.Received -> ApiResult.Ok(Response(result.status, result.body, result.retryAfterHeader))
            is HttpResult.Failed -> ApiResult.Failed(
                when (result.failure) {
                    HttpFailure.TOO_LARGE -> AccessError.UnexpectedResponse
                    HttpFailure.TLS -> AccessError.Tls
                    HttpFailure.TIMEOUT, HttpFailure.NETWORK -> AccessError.Network
                },
            )
        }

    private fun parseObject(body: ByteArray): JSONObject? = try {
        JSONObject(String(body, Charsets.UTF_8))
    } catch (_: JSONException) {
        null
    }

    private fun errorCode(response: Response): String? = parseObject(response.body)?.optString("error")

    private fun retryAfter(response: Response): Int? {
        val fromBody = parseObject(response.body)?.opt("retry_after") as? Number
        return fromBody?.toInt() ?: retryAfterSeconds(response.retryAfterHeader)
    }

    private fun failed(error: AccessError): ApiResult<Nothing> = ApiResult.Failed(error)

    private fun unexpected(): ApiResult<Nothing> = ApiResult.Failed(AccessError.UnexpectedResponse)

    private companion object {
        /**
         * The v1 snapshot is complete (no pagination) and is parsed whole: ~4 MB at 10k
         * archived items, the point where the contract says to revisit it. Anything
         * past this is refused instead of exhausting the heap.
         */
        const val MAX_BODY_BYTES = 32 * 1024 * 1024
        const val MAX_PUBLIC_ID = 64
    }
}
