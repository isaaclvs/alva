package net.ts.taild80ca0.atlas.twa.online

import kotlinx.coroutines.runBlocking
import net.ts.taild80ca0.atlas.twa.access.AccessCredential
import net.ts.taild80ca0.atlas.twa.access.ServerEndpoint
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The history request on its own: what it sends and how it classifies the answers. */
class HttpHistoryTransportTest {
    private lateinit var server: MockWebServer
    private lateinit var transport: HttpHistoryTransport
    private val credential = AccessCredential(7, "segredo-de-teste-123")

    @Before
    fun start() {
        server = MockWebServer().apply { start() }
        transport = HttpHistoryTransport(ServerEndpoint.parse(server.url("/").toString()), 2_000, 1_500)
    }

    @After
    fun stop() {
        server.shutdown()
    }

    private fun answer(status: Int, body: String = "") {
        server.enqueue(MockResponse().setResponseCode(status).setBody(body))
    }

    private fun read(month: String? = null): HistoryRead = runBlocking { transport.fetch(credential, month) }

    private fun body(month: String? = "2026-11") = """
        {"month":${month?.let { "\"$it\"" } ?: "null"},"months":[{"key":"2026-11","trip_count":1}],
         "older_month":null,"newer_month":null,
         "trips":[{"public_id":"t1","finished_at":"2026-11-12T21:30:00.000000Z","items_count":1,
                   "items":[{"public_id":"i1","name":"Sabão","category_public_id":null}]}]}
    """.trimIndent()

    @Test
    fun asksForThePageWithTheBearerAndNoBody() {
        answer(200, body())

        val read = read() as HistoryRead.Received

        assertEquals("2026-11", read.page.month)
        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/api/v1/history", request.path)
        assertEquals("Bearer segredo-de-teste-123", request.getHeader("Authorization"))
        assertEquals(0L, request.bodySize)
    }

    @Test
    fun asksForAMonthAsAQuery() {
        answer(200, body("2026-11"))

        read("2026-10")

        assertEquals("/api/v1/history?month=2026-10", server.takeRequest().path)
    }

    @Test
    fun aRefusedCredentialIsUnauthorized() {
        answer(401, """{"error":"unauthorized"}""")
        assertEquals(HistoryRead.Unauthorized, read())
        answer(403, """{"error":"forbidden_scope"}""")
        assertEquals(HistoryRead.Unauthorized, read())
    }

    @Test
    fun networkAndServerErrorsAreTransientAndChangeNothing() {
        answer(500, """{"error":"internal_error"}""")
        assertEquals(HistoryRead.Transient(offline = false), read())
        answer(429, """{"error":"rate_limited"}""")
        assertEquals(HistoryRead.Transient(offline = false), read())
    }

    @Test
    fun aBodyThatIsNotTheDocumentIsRejected() {
        answer(200, """{"month":"2026-11"}""")
        assertTrue(read() is HistoryRead.Invalid)
        answer(200, "nada")
        assertEquals(HistoryRead.Invalid(HistoryProblem.NOT_JSON), read())
    }
}
