package net.ts.taild80ca0.atlas.twa.sync

import kotlinx.coroutines.runBlocking
import net.ts.taild80ca0.atlas.twa.access.AccessCredential
import net.ts.taild80ca0.atlas.twa.access.ServerEndpoint
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The snapshot request on its own, against raw answers: what it sends and how it classifies what comes back. */
class HttpSnapshotTransportTest {
    private lateinit var server: MockWebServer
    private lateinit var transport: HttpSnapshotTransport
    private val credential = AccessCredential(7, "segredo-de-teste-123")

    @Before
    fun start() {
        server = MockWebServer().apply { start() }
        transport = HttpSnapshotTransport(ServerEndpoint.parse(server.url("/").toString()), 2_000, 1_500)
    }

    @After
    fun stop() {
        server.shutdown()
    }

    private fun answer(status: Int, body: String = "", vararg headers: Pair<String, String>) {
        server.enqueue(MockResponse().setResponseCode(status).setBody(body).also { r -> headers.forEach { (k, v) -> r.setHeader(k, v) } })
    }

    private fun fetch(): SnapshotOutcome = runBlocking { transport.fetch(credential) }

    @Test
    fun asksForTheSnapshotWithOnlyTheBearer() {
        answer(200, SnapshotFixtures.snapshot().toString())

        val outcome = fetch()

        assertTrue(outcome is SnapshotOutcome.Received)
        assertEquals(SnapshotFixtures.HOUSE, (outcome as SnapshotOutcome.Received).snapshot.householdPublicId)
        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/api/v1/snapshot", request.path)
        assertEquals("Bearer segredo-de-teste-123", request.getHeader("Authorization"))
        assertEquals(0L, request.bodySize)
    }

    @Test
    fun aRefusedCredentialIsUnauthorizedAndNothingIsRead() {
        answer(401, """{"error":"unauthorized"}""")
        assertEquals(SnapshotOutcome.Unauthorized, fetch())
        answer(403, """{"error":"forbidden_scope"}""")
        assertEquals(SnapshotOutcome.Unauthorized, fetch())
    }

    @Test
    fun failuresThatMayPassAreTransientAndCarryTheServersWait() {
        answer(500, """{"error":"internal_error"}""")
        assertEquals(SnapshotOutcome.Transient(TransientCause.SERVER_ERROR), fetch())
        answer(429, "", "Retry-After" to "7")
        assertEquals(SnapshotOutcome.Transient(TransientCause.RATE_LIMITED, 7), fetch())
        // A GET changes nothing on the server, so the HTTP stack may replay it on a dropped connection
        // (the JDK does, at least once; the command POST is protected from that by its fixed-length
        // body, see HttpCommandTransportTest). Every request that arrives is still the same read-only
        // GET, and a dropped connection ends as a lost answer, never as a snapshot.
        val before = server.requestCount
        repeat(before) { server.takeRequest() }
        repeat(3) { server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)) }
        assertEquals(SnapshotOutcome.Transient(TransientCause.NETWORK), fetch())
        val arrived = server.requestCount - before
        assertTrue("a dropped GET may be replayed a bounded number of times, saw $arrived", arrived in 1..3)
        repeat(arrived) { val r = server.takeRequest(); assertEquals("GET /api/v1/snapshot", "${r.method} ${r.path}") }
    }

    @Test
    fun aSlowServerIsATimeoutNotAHang() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        assertEquals(SnapshotOutcome.Transient(TransientCause.TIMEOUT), fetch())
    }

    @Test
    fun aBodyCutShortIsLostNotASnapshot() {
        val body = SnapshotFixtures.snapshot().toString()
        server.enqueue(MockResponse().setBody(body).setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY))
        val outcome = fetch()
        assertTrue("got $outcome", outcome is SnapshotOutcome.Transient)
    }

    @Test
    fun otherClientErrorsAreRefusedWithOnlyAWellFormedCode() {
        answer(404, """{"error":"not_found"}""")
        assertEquals(SnapshotOutcome.Refused(404, "not_found"), fetch())
        answer(400, """{"error":"Some Free Text!"}""")
        assertEquals(SnapshotOutcome.Refused(400, null), fetch())
        answer(422, "not json")
        assertEquals(SnapshotOutcome.Refused(422, null), fetch())
    }

    @Test
    fun aRedirectIsNotFollowedAndNothingElseIsAccepted() {
        answer(302, "", "Location" to "http://127.0.0.1:1/elsewhere")
        val outcome = fetch()
        assertEquals(SnapshotOutcome.Invalid(SnapshotProblem.UNEXPECTED_STATUS, "status"), outcome)
        assertEquals(1, server.requestCount)
        answer(204)
        assertTrue(fetch() is SnapshotOutcome.Invalid)
    }

    @Test
    fun a200ThatIsNotASnapshotConfirmsNothing() {
        answer(200, "<html>captive portal</html>")
        assertEquals(SnapshotOutcome.Invalid(SnapshotProblem.NOT_JSON, "body"), fetch())
        answer(200, SnapshotFixtures.snapshot().put("schema_version", 2).toString())
        assertEquals(SnapshotOutcome.Invalid(SnapshotProblem.UNSUPPORTED_SCHEMA, "schema_version"), fetch())
        answer(200, SnapshotFixtures.snapshot().put("revision", "x").toString())
        assertEquals(SnapshotOutcome.Invalid(SnapshotProblem.WRONG_SHAPE, "revision"), fetch())
    }

    @Test
    fun theOutcomeNeverCarriesTheCredential() {
        answer(200, SnapshotFixtures.snapshot().toString())
        val text = fetch().toString()
        assertTrue(!text.contains("segredo-de-teste-123"))
        assertNull(SnapshotOutcome.Refused(400, null).code)
    }
}
