package net.ts.taild80ca0.atlas.twa.sync

import kotlinx.coroutines.runBlocking
import net.ts.taild80ca0.atlas.twa.access.AccessCredential
import net.ts.taild80ca0.atlas.twa.access.ServerEndpoint
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The command transport on its own, against raw answers: what it sends, and how it classifies what comes back. */
class HttpCommandTransportTest {
    private lateinit var server: MockWebServer
    private lateinit var transport: HttpCommandTransport

    private val id = "5f0c2f0e-0000-4000-8000-000000000001"
    private val credential = AccessCredential(7, "segredo-de-teste-123")

    // Awkward on purpose: key order, spacing, a newline, non-ASCII and an escaped quote.
    private val document =
        "{ \"payload\" : {\"items\":[ {\"name\":\"Pão de açúcar \\\"quente\\\"\" , \"public_id\":\"9a1d0000-0000-4000-8000-000000000001\"} ]},\n" +
            "  \"id\":\"$id\",\"depends_on\":null,\"type\":\"add_items\",\"preconditions\":{} }"

    @Before
    fun start() {
        server = MockWebServer().apply { start() }
        transport = HttpCommandTransport(ServerEndpoint.parse(server.url("/").toString()), 2_000, 1_500)
    }

    @After
    fun stop() {
        server.shutdown()
    }

    private fun answer(status: Int, body: String = "", headers: Map<String, String> = emptyMap()) {
        server.enqueue(MockResponse().setResponseCode(status).setBody(body).also { r -> headers.forEach { (k, v) -> r.setHeader(k, v) } })
    }

    private fun send(doc: String = document, commandId: String = id): CommandOutcome =
        runBlocking { transport.send(credential, commandId, doc) }

    private val appliedBody =
        """{"revision":841,"results":[{"id":"$id","status":"applied","revision":841,"data":{"items":[]}}],"snapshot_required":false}"""

    @Test
    fun sendsTheFrozenDocumentByteForByteAsTheOnlyCommandWithAStrictBearer() {
        answer(200, appliedBody)

        val outcome = send()

        assertTrue(outcome is CommandOutcome.Receipt)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/v1/commands", request.path)
        assertEquals("Bearer segredo-de-teste-123", request.getHeader("Authorization"))
        assertTrue(request.getHeader("Content-Type")!!.startsWith("application/json"))
        assertNull(request.getHeader("Transfer-Encoding")) // fixed length, so the stack cannot replay it silently
        val sent = request.body.readByteArray()
        assertArrayEquals("{\"commands\":[$document]}".toByteArray(Charsets.UTF_8), sent)
        // Still valid JSON for the server: one command, and nothing but the commands key at the top.
        val parsed = JSONObject(String(sent, Charsets.UTF_8))
        assertEquals(setOf("commands"), parsed.keys().asSequence().toSet())
        assertEquals(id, parsed.getJSONArray("commands").getJSONObject(0).getString("id"))
    }

    @Test
    fun aRepeatedSendPutsTheSameBytesOnTheWire() {
        answer(500)
        answer(200, appliedBody)

        send()
        send()

        val first = server.takeRequest().body.readByteArray()
        val second = server.takeRequest().body.readByteArray()
        assertArrayEquals(first, second)
    }

    @Test
    fun aValidReceiptIsReturnedForEveryBusinessStatus() {
        answer(200, appliedBody)
        val receipt = (send() as CommandOutcome.Receipt).receipt
        assertEquals(ReceiptStatus.APPLIED, receipt.status)

        answer(200, """{"revision":9,"results":[{"id":"$id","status":"conflict","error":"precondition_failed","data":{"reason":"version_changed"}}],"snapshot_required":true}""")
        val conflict = (send() as CommandOutcome.Receipt).receipt
        assertEquals(ReceiptStatus.CONFLICT, conflict.status)
        assertTrue(conflict.snapshotRequired)
    }

    @Test
    fun httpOkWithAnotherCommandOrAnUnknownStatusOrATruncatedBodyIsInvalidNotSuccess() {
        answer(200, appliedBody.replace(id, "5f0c2f0e-0000-4000-8000-0000000000ff"))
        assertEquals(CommandOutcome.Invalid(InvalidResponse.WRONG_COMMAND), send())

        answer(200, appliedBody.replace("applied", "maybe"))
        assertEquals(CommandOutcome.Invalid(InvalidResponse.UNKNOWN_STATUS), send())

        answer(200, appliedBody.dropLast(12))
        assertEquals(CommandOutcome.Invalid(InvalidResponse.NOT_JSON), send())

        answer(200, "")
        assertEquals(CommandOutcome.Invalid(InvalidResponse.NOT_JSON), send())
    }

    @Test
    fun aBodyCutShortByTheConnectionIsATransientLossNotAnAnswer() {
        server.enqueue(MockResponse().setResponseCode(200).setBody(appliedBody).setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY))

        assertEquals(CommandOutcome.Transient(TransientCause.NETWORK), send())
    }

    @Test
    fun unauthorizedAnswersAreReportedAsSuchWithoutLeakingAnything() {
        answer(401, """{"error":"unauthorized"}""")
        answer(403, """{"error":"forbidden_scope"}""")

        assertEquals(CommandOutcome.Unauthorized, send())
        assertEquals(CommandOutcome.Unauthorized, send())
        assertFalse(CommandOutcome.Unauthorized.toString().contains(credential.token))
    }

    @Test
    fun rateLimitingExposesRetryAfterFromTheHeaderOrTheBodyAndDoesNotRetry() {
        answer(429, """{"error":"rate_limited"}""", mapOf("Retry-After" to "17"))
        assertEquals(CommandOutcome.Transient(TransientCause.RATE_LIMITED, 17), send())
        answer(429, """{"error":"rate_limited","retry_after":900}""")
        assertEquals(CommandOutcome.Transient(TransientCause.RATE_LIMITED, 900), send())
        answer(429, "")
        assertEquals(CommandOutcome.Transient(TransientCause.RATE_LIMITED, null), send())
        answer(429, "", mapOf("Retry-After" to "Wed, 21 Oct 2026 07:28:00 GMT"))
        assertEquals(CommandOutcome.Transient(TransientCause.RATE_LIMITED, null), send())

        assertEquals(4, server.requestCount)
    }

    @Test
    fun serverErrorsAreTransientAndRequestWideRefusalsArePermanent() {
        answer(500, """{"error":"internal_error"}""")
        answer(503)
        assertEquals(CommandOutcome.Transient(TransientCause.SERVER_ERROR), send())
        assertEquals(CommandOutcome.Transient(TransientCause.SERVER_ERROR), send())

        answer(422, """{"error":"malformed_command"}""")
        assertEquals(CommandOutcome.Refused(422, "malformed_command"), send())
        answer(422, """{"error":"Some <b>free text</b> from a proxy"}""")
        assertEquals(CommandOutcome.Refused(422, null), send())
        answer(404, "<html>not alva</html>")
        assertEquals(CommandOutcome.Refused(404, null), send())
    }

    @Test
    fun aRedirectIsNeverFollowedAndNothingElseIsContacted() {
        MockWebServer().use { elsewhere ->
            elsewhere.start()
            answer(307, "", mapOf("Location" to elsewhere.url("/api/v1/commands").toString()))

            val outcome = send()

            assertEquals(CommandOutcome.Invalid(InvalidResponse.UNEXPECTED_STATUS), outcome)
            assertEquals(1, server.requestCount)
            assertEquals(0, elsewhere.requestCount)
        }
    }

    @Test
    fun aSilentServerTimesOutAsTransientAfterExactlyOneRequest() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))

        assertEquals(CommandOutcome.Transient(TransientCause.TIMEOUT), send())
        assertEquals(1, server.requestCount)
    }

    @Test
    fun aConnectionDroppedAfterTheRequestIsTransientAndIsNotSentAgain() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))

        assertEquals(CommandOutcome.Transient(TransientCause.NETWORK), send())
        assertEquals("a lost answer must not make the stack resend the POST", 1, server.requestCount)
    }

    @Test
    fun aServerThatIsGoneIsTransient() {
        val dead = HttpCommandTransport(ServerEndpoint.parse("http://127.0.0.1:1"), 500, 500)

        val outcome = runBlocking { dead.send(credential, id, document) }

        assertTrue(outcome is CommandOutcome.Transient)
    }

    @Test
    fun anOversizedAnswerIsRefusedWithoutBeingRead() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("x".repeat(HttpCommandTransport.MAX_RESPONSE_BYTES + 10)))

        assertEquals(CommandOutcome.Invalid(InvalidResponse.TOO_LARGE), send())
    }

    @Test
    fun aDocumentOverTheLimitIsRefusedBeforeAnyRequest() {
        val huge = document.replace("Pão", "P".repeat(CommandLimits.MAX_DOCUMENT_BYTES))

        assertEquals(CommandOutcome.Refused(413, "request_too_large"), send(huge))
        assertEquals(0, server.requestCount)
    }
}
