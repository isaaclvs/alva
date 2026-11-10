package net.ts.taild80ca0.atlas.twa.access

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import javax.net.ssl.SSLException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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

    private suspend fun call(
        method: String,
        path: String,
        bearer: String?,
        jsonBody: String?,
    ): ApiResult<Response> = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            connection = (URL(endpoint.url(path)).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = connectTimeoutMillis
                readTimeout = readTimeoutMillis
                instanceFollowRedirects = false
                useCaches = false
                // One request per connection: a pooled connection that the server already
                // closed could make the stack silently resend a pairing POST.
                setRequestProperty("Connection", "close")
                setRequestProperty("Accept", "application/json")
                if (bearer != null) setRequestProperty("Authorization", "Bearer $bearer")
                if (jsonBody != null) {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                }
            }
            if (jsonBody != null) connection.outputStream.use { it.write(jsonBody.toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            val stream = if (status < 400) connection.inputStream else connection.errorStream
            val body = stream?.use { readCapped(it) } ?: ByteArray(0)
            ApiResult.Ok(Response(status, body, connection.getHeaderField("Retry-After")))
        } catch (_: TooLarge) {
            ApiResult.Failed(AccessError.UnexpectedResponse)
        } catch (_: SSLException) {
            ApiResult.Failed(AccessError.Tls)
        } catch (_: IOException) {
            ApiResult.Failed(AccessError.Network)
        } finally {
            connection?.disconnect()
        }
    }

    private class TooLarge : Exception()

    private fun readCapped(stream: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val read = stream.read(buffer)
            if (read < 0) break
            out.write(buffer, 0, read)
            if (out.size() > MAX_BODY_BYTES) throw TooLarge()
        }
        return out.toByteArray()
    }

    private fun parseObject(body: ByteArray): JSONObject? = try {
        JSONObject(String(body, Charsets.UTF_8))
    } catch (_: JSONException) {
        null
    }

    private fun errorCode(response: Response): String? = parseObject(response.body)?.optString("error")

    private fun retryAfter(response: Response): Int? {
        val fromBody = parseObject(response.body)?.opt("retry_after") as? Number
        return fromBody?.toInt() ?: response.retryAfterHeader?.trim()?.toIntOrNull()
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
