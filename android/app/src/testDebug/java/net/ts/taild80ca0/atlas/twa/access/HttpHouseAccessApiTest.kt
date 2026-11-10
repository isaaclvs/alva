package net.ts.taild80ca0.atlas.twa.access

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The HTTP client on its own, against raw server answers (statuses the contract table lists and ones it does not). */
class HttpHouseAccessApiTest {
    private lateinit var server: MockWebServer
    private lateinit var api: HttpHouseAccessApi

    @Before
    fun start() {
        server = MockWebServer().apply { start() }
        api = HttpHouseAccessApi(ServerEndpoint.parse(server.url("/").toString()), 2_000, 3_000)
    }

    @After
    fun stop() {
        server.shutdown()
    }

    private fun answer(status: Int, body: String = "", headers: Map<String, String> = emptyMap()) {
        server.enqueue(MockResponse().setResponseCode(status).setBody(body).also { r -> headers.forEach { (k, v) -> r.setHeader(k, v) } })
    }

    private fun pairError(): AccessError = (runBlocking { api.pair("x", "n", null) } as ApiResult.Failed).error

    private fun snapshotError(): AccessError =
        (runBlocking { api.identifyHousehold(AccessCredential(1, "t")) } as ApiResult.Failed).error

    @Test
    fun pairingSendsJsonWithoutAuthorizationAndOnlyTheKnownFields() {
        answer(201, """{"device_id":5,"token":"abc"}""")

        val result = runBlocking { api.pair("senha", "Alva Android", "antigo") } as ApiResult.Ok

        assertEquals(AccessCredential(5, "abc"), result.value)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/v1/devices", request.path)
        assertNull(request.getHeader("Authorization"))
        assertTrue(request.getHeader("Content-Type")!!.startsWith("application/json"))
        val sent = JSONObject(request.body.readUtf8())
        assertEquals(setOf("password", "name", "previous_token"), sent.keys().asSequence().toSet())
    }

    @Test
    fun identifyingSendsAStrictBearerAndReadsOnlyTheHouseholdId() {
        answer(200, """{"schema_version":1,"revision":9,"household":{"public_id":"casa-1","name":"Casa"},"list_items":[]}""")

        val result = runBlocking { api.identifyHousehold(AccessCredential(2, "sec ret".replace(" ", ""))) } as ApiResult.Ok

        assertEquals(HouseholdIdentity("casa-1"), result.value)
        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/api/v1/snapshot", request.path)
        assertEquals("Bearer secret", request.getHeader("Authorization"))
    }

    @Test
    fun pairingStatusesMapToTheContractErrors() {
        answer(401, """{"error":"unauthorized"}""")
        assertEquals(AccessError.WrongPassword, pairError())
        answer(403, """{"error":"sync_unavailable"}""")
        assertEquals(AccessError.SyncUnavailable, pairError())
        answer(403, """{"error":"something_else"}""")
        assertEquals(AccessError.UnexpectedResponse, pairError())
        answer(422, """{"error":"invalid_previous_token"}""")
        assertEquals(AccessError.InvalidPreviousToken, pairError())
        answer(422, """{"error":"other"}""")
        assertEquals(AccessError.UnexpectedResponse, pairError())
        answer(429, """{"error":"rate_limited","retry_after":900}""", mapOf("Retry-After" to "900"))
        assertEquals(AccessError.RateLimited(900), pairError())
        answer(429, "", mapOf("Retry-After" to "60"))
        assertEquals(AccessError.RateLimited(60), pairError())
        answer(500, """{"error":"internal_error"}""")
        assertEquals(AccessError.ServerUnavailable, pairError())
        answer(502)
        assertEquals(AccessError.ServerUnavailable, pairError())
        answer(404)
        assertEquals(AccessError.UnexpectedResponse, pairError())
    }

    @Test
    fun snapshotStatusesMapToTheContractErrors() {
        answer(401, """{"error":"unauthorized"}""")
        assertEquals(AccessError.Unauthorized, snapshotError())
        answer(403, """{"error":"forbidden_scope"}""")
        assertEquals(AccessError.Unauthorized, snapshotError())
        answer(500, """{"error":"internal_error"}""")
        assertEquals(AccessError.ServerUnavailable, snapshotError())
        answer(200, "not json")
        assertEquals(AccessError.UnexpectedResponse, snapshotError())
        answer(200, """{"household":{"public_id":""}}""")
        assertEquals(AccessError.UnexpectedResponse, snapshotError())
        answer(200, """{"household":{"public_id":42}}""")
        assertEquals(AccessError.UnexpectedResponse, snapshotError())
    }

    @Test
    fun aPairingAnswerNeedsABlankFreeTokenAndANumericDeviceId() {
        answer(201, """{"device_id":"7","token":"abc"}""")
        assertEquals(AccessError.UnexpectedResponse, pairError())
        answer(201, """{"device_id":7,"token":" "}""")
        assertEquals(AccessError.UnexpectedResponse, pairError())
        answer(201, "[]")
        assertEquals(AccessError.UnexpectedResponse, pairError())
    }
}
