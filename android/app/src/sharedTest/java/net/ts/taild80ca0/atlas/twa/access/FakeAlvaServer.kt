package net.ts.taild80ca0.atlas.twa.access

import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONObject

/**
 * A real HTTP server on loopback that behaves like the Rails endpoints the
 * client uses, following `docs/plans/android-sync-contract-v1.md` and the Rails
 * tests (`test/controllers/api/v1/devices_controller_test.rb`):
 *
 * - `POST /api/v1/devices`: password → `201 {device_id, token}`; a `previous_token`
 *   (even revoked, even after a rotation) continues the lineage; unknown → 422
 *   `invalid_previous_token`; wrong password → 401; no password configured → 403
 *   `sync_unavailable`; over the budget → 429 `rate_limited` + `Retry-After`.
 * - `GET /api/v1/snapshot`: strict `Bearer <secret>`; revoked, unknown or
 *   pre-rotation credentials → 401.
 *
 * Failure injection (a pairing that commits but whose answer is lost, a failing
 * snapshot) is explicit so each test reads as a scenario.
 */
class FakeAlvaServer(
    val householdPublicId: String = UUID.randomUUID().toString(),
    @Volatile var password: String = "senha-da-casa",
) : AutoCloseable {
    class Device(val id: Long, val token: String, val lineage: String, val passwordVersion: Int) {
        @Volatile var revoked = false
    }

    class Recorded(val method: String, val path: String, val authorization: String?, val body: String)

    private val lock = Any()
    private var nextId = 1L
    private var passwordVersion = 1
    val devices = CopyOnWriteArrayList<Device>()
    val requests = CopyOnWriteArrayList<Recorded>()

    @Volatile var syncAvailable = true
    @Volatile var pairingBudget: Int? = null // remaining pairings before 429
    /** While set, pairings commit but their answer is never sent (some HTTP stacks resend such a POST once). */
    @Volatile var dropPairingResponses = false
    @Volatile var redirectPairingTo: String? = null
    @Volatile var malformedPairingBody: String? = null
    private val snapshotStatuses = java.util.ArrayDeque<Int>() // statuses to answer before the real one
    var snapshotRevision = 840

    private val server = MockWebServer().apply {
        dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = synchronized(lock) { handle(request) }
        }
        start()
    }

    val baseUrl: String get() = server.url("/").toString().trimEnd('/')

    fun pairingRequests() = requests.filter { it.path == "/api/v1/devices" }

    fun snapshotRequests() = requests.filter { it.path == "/api/v1/snapshot" }

    fun device(id: Long): Device = devices.first { it.id == id }

    fun deviceByToken(token: String): Device? = devices.firstOrNull { it.token == token }

    fun revoke(deviceId: Long) {
        device(deviceId).revoked = true
    }

    /** Rotating the shared password invalidates every credential issued before it. */
    fun rotatePassword(newPassword: String) {
        synchronized(lock) {
            password = newPassword
            passwordVersion += 1
        }
    }

    fun failSnapshotsWith(vararg statuses: Int) {
        synchronized(lock) { statuses.forEach { snapshotStatuses.add(it) } }
    }

    private var stopped = false

    override fun close() = stop()

    @Synchronized
    fun stop() {
        if (!stopped) {
            stopped = true
            server.shutdown()
        }
    }

    private fun handle(request: RecordedRequest): MockResponse {
        val body = request.body.readUtf8()
        requests += Recorded(request.method.orEmpty(), request.path.orEmpty().substringBefore('?'), request.getHeader("Authorization"), body)
        return when {
            request.method == "POST" && request.path == "/api/v1/devices" -> pair(body)
            request.method == "GET" && request.path == "/api/v1/snapshot" -> snapshot(request.getHeader("Authorization"))
            else -> json(404, """{"error":"not_found"}""")
        }
    }

    private fun pair(body: String): MockResponse {
        redirectPairingTo?.let { target ->
            return MockResponse().setResponseCode(302).setHeader("Location", target)
        }
        if (!syncAvailable) return json(403, """{"error":"sync_unavailable"}""")
        pairingBudget?.let { left ->
            if (left <= 0) {
                return json(429, """{"error":"rate_limited","retry_after":900}""").setHeader("Retry-After", "900")
            }
            pairingBudget = left - 1
        }
        val params = JSONObject(body)
        if (params.optString("password") != password) return json(401, """{"error":"unauthorized"}""")

        var lineage: String? = null
        if (params.has("previous_token")) {
            val previous = deviceByToken(params.optString("previous_token"))
                ?: return json(422, """{"error":"invalid_previous_token"}""")
            lineage = previous.lineage
        }
        val device = Device(nextId++, UUID.randomUUID().toString().replace("-", "") + "x", lineage ?: UUID.randomUUID().toString(), passwordVersion)
        devices += device
        if (dropPairingResponses) {
            // The device exists on the server, but the phone never hears about it.
            return MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)
        }
        malformedPairingBody?.let { return json(201, it) }
        return json(201, JSONObject().put("device_id", device.id).put("token", device.token).toString())
            .setHeader("Cache-Control", "no-store")
    }

    private fun snapshot(authorization: String?): MockResponse {
        val secret = authorization?.let { Regex("^Bearer (\\S+)$").matchEntire(it)?.groupValues?.get(1) }
        val device = secret?.let { deviceByToken(it) }
        if (device == null || device.revoked || device.passwordVersion != passwordVersion) {
            return json(401, """{"error":"unauthorized"}""")
        }
        snapshotStatuses.poll()?.let { status -> return json(status, """{"error":"internal_error"}""") }
        val document = JSONObject()
            .put("schema_version", 1)
            .put("revision", snapshotRevision)
            .put("dictionary_revision", 12)
            .put("household", JSONObject().put("public_id", householdPublicId).put("name", "Casa"))
        return json(200, document.toString())
    }

    private fun json(status: Int, body: String) = MockResponse()
        .setResponseCode(status)
        .setHeader("Content-Type", "application/json; charset=utf-8")
        .setHeader("Cache-Control", "no-store")
        .setBody(body)
}
