package net.ts.taild80ca0.atlas.twa.access

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import net.ts.taild80ca0.atlas.twa.sync.LatencyProxy
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * `HttpExchange` over the real `HttpURLConnection` and real sockets: consecutive requests to the server reuse one
 * connection (a mark is a command and a reload, and each new connection to the Atlas costs a TCP and a TLS
 * handshake), and everything that makes one call one request on the wire still holds on a reused connection.
 */
class HttpExchangeReuseTest {
    private lateinit var server: MockWebServer
    private lateinit var exchange: HttpExchange
    private val body = """{"ok":true}""".toByteArray()

    @Before fun start() {
        server = MockWebServer().apply { start() }
        exchange = HttpExchange(ServerEndpoint.parse(server.url("/").toString()), 2_000, 1_500, 4_096)
    }

    @After fun stop() {
        server.shutdown()
    }

    private fun answer(status: Int = 200, text: String = """{"ok":true}""", policy: SocketPolicy = SocketPolicy.KEEP_OPEN, vararg headers: Pair<String, String>) {
        server.enqueue(MockResponse().setResponseCode(status).setBody(text).setSocketPolicy(policy).also { r -> headers.forEach { (k, v) -> r.setHeader(k, v) } })
    }

    private fun get(path: String = "/api/v1/snapshot", bearer: String? = "tok") = runBlocking { exchange.execute("GET", path, bearer, null) }
    private fun post(bearer: String? = "tok") = runBlocking { exchange.execute("POST", "/api/v1/commands", bearer, body) }
    private fun next(): RecordedRequest = server.takeRequest(2, TimeUnit.SECONDS)!!

    @Test fun consecutiveRequestsShareOneConnection() {
        repeat(3) { answer() }

        assertTrue(post() is HttpResult.Received)
        assertTrue(get() is HttpResult.Received)
        assertTrue(post() is HttpResult.Received)

        val requests = List(3) { next() }
        // sequenceNumber: the position of the request on its connection. 0, 1, 2 is one connection used three times.
        assertEquals(listOf(0, 1, 2), requests.map { it.sequenceNumber })
        assertTrue(requests.none { it.getHeader("Connection").equals("close", true) })
    }

    @Test fun throughAServerThatHonorsConnectionCloseThreeRequestsOpenOneConnection() {
        // MockWebServer ignores `Connection: close`; Puma and the proxies in front of it do not. The proxy behaves like them.
        LatencyProxy(server.port, roundTripMillis = 0, setupRoundTrips = 0).use { proxy ->
            val viaProxy = HttpExchange(ServerEndpoint.parse("http://127.0.0.1:${proxy.port}"), 2_000, 1_500, 4_096)
            repeat(3) { answer() }

            runBlocking {
                assertTrue(viaProxy.execute("POST", "/api/v1/commands", "tok", body) is HttpResult.Received)
                assertTrue(viaProxy.execute("GET", "/api/v1/snapshot", "tok", null) is HttpResult.Received)
                assertTrue(viaProxy.execute("GET", "/api/v1/snapshot", "tok", null) is HttpResult.Received)
            }

            assertEquals(3, proxy.requests.get())
            assertEquals("every request paid its own connection", 1, proxy.connections.get())
        }
    }

    @Test fun eachRequestCarriesItsOwnCredentialAndNothingStaysWithTheConnection() {
        repeat(3) { answer() }

        get(bearer = "first")
        get(bearer = null)
        get(bearer = "third")

        val requests = List(3) { next() }
        assertEquals(listOf("Bearer first", null, "Bearer third"), requests.map { it.getHeader("Authorization") })
        assertEquals("they did share the connection", listOf(0, 1, 2), requests.map { it.sequenceNumber })
    }

    @Test fun aRedirectIsNotFollowedEvenOnAReusedConnection() {
        val elsewhere = MockWebServer().apply { start() }
        try {
            answer()
            answer(302, "", SocketPolicy.KEEP_OPEN, "Location" to elsewhere.url("/stolen").toString())

            assertTrue(get() is HttpResult.Received)
            val redirected = get() as HttpResult.Received

            assertEquals(302, redirected.status)
            assertEquals("the token never went to another host", 0, elsewhere.requestCount)
        } finally {
            elsewhere.shutdown()
        }
    }

    @Test fun anAnswerOverTheLimitIsRefusedAndTheNextAnswerIsNotMixedWithIt() {
        answer(text = "x".repeat(50_000))
        answer(text = """{"next":1}""")

        assertEquals(HttpFailure.TOO_LARGE, (get() as HttpResult.Failed).failure)
        val after = get() as HttpResult.Received

        assertEquals("""{"next":1}""", String(after.body))
    }

    @Test fun anErrorAnswerKeepsTheConnectionAndItsBodyIsNotMixedWithTheNextOne() {
        answer(503, "down", SocketPolicy.KEEP_OPEN, "Retry-After" to "3")
        answer(200, """{"ok":true}""")

        val failed = get() as HttpResult.Received
        val fine = get() as HttpResult.Received

        assertEquals(503, failed.status)
        assertEquals("down", String(failed.body))
        assertEquals("3", failed.retryAfterHeader)
        assertEquals("""{"ok":true}""", String(fine.body))
        assertNull(fine.retryAfterHeader)
        assertEquals(listOf(0, 1), listOf(next().sequenceNumber, next().sequenceNumber))
    }

    @Test fun aServerThatClosedTheIdleConnectionIsNoProblemForTheNextRequest() {
        answer(policy = SocketPolicy.DISCONNECT_AT_END) // answers, then hangs up while the client may keep it pooled
        answer()

        assertTrue(get() is HttpResult.Received)
        val second = post()

        // Either the stack noticed the dead connection and opened another, or it failed without the request ever
        // reaching the server. Never a request that arrived twice.
        if (second is HttpResult.Received) assertEquals(200, second.status) else assertEquals(HttpFailure.NETWORK, (second as HttpResult.Failed).failure)
        assertTrue("the POST reached the server at most once", server.requestCount <= 2)
    }

    @Test fun aLostAnswerOnAReusedConnectionIsNotSentAgainByTheStack() {
        answer()
        answer(policy = SocketPolicy.DISCONNECT_AFTER_REQUEST) // the server got the POST and the answer never came

        assertTrue(get() is HttpResult.Received)
        val lost = post()

        assertEquals(HttpFailure.NETWORK, (lost as HttpResult.Failed).failure)
        assertEquals("one GET and one POST on the wire; the stack did not resend the POST", 2, server.requestCount)
    }
}
