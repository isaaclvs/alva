package net.ts.taild80ca0.atlas.twa.access

import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.runBlocking
import net.ts.taild80ca0.atlas.twa.sync.LatencyProxy
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * `HttpExchange` on the Android HTTP stack (not the JDK's): do consecutive requests really share a connection,
 * and is a POST still sent exactly once when its answer is lost on a reused one? The network is loopback behind
 * [LatencyProxy], a model of the path to the server: the timings printed here are **simulated**, not a measurement
 * of a phone on a real network. Connection counts and request counts are real.
 */
class HttpExchangeReuseOnDeviceTest {
    private lateinit var server: MockWebServer
    private val body = """{"ok":true}""".toByteArray()

    @Before fun start() {
        server = MockWebServer().apply { start() }
    }

    @After fun stop() {
        server.shutdown()
    }

    private fun exchange(port: Int) = HttpExchange(ServerEndpoint.parse("http://127.0.0.1:$port"), 3_000, 3_000, 64 * 1024)

    @Test fun threeRequestsThroughAServerThatHonorsCloseShareOneConnection() = runBlocking {
        repeat(3) { server.enqueue(MockResponse().setBody("""{"ok":true}""")) }
        LatencyProxy(server.port, roundTripMillis = 100).use { proxy ->
            val exchange = exchange(proxy.port)
            val started = System.nanoTime()
            assertTrue(exchange.execute("POST", "/api/v1/commands", "tok", body) is HttpResult.Received)
            assertTrue(exchange.execute("GET", "/api/v1/snapshot", "tok", null) is HttpResult.Received)
            val markMillis = (System.nanoTime() - started) / 1_000_000
            assertTrue(exchange.execute("POST", "/api/v1/commands", "tok", body) is HttpResult.Received)
            val threeMillis = (System.nanoTime() - started) / 1_000_000

            println("LATENCY(device stack, simulated rtt=100ms) command+reload on a cold connection=${markMillis}ms, third request=${threeMillis - markMillis}ms, connections=${proxy.connections.get()}")
            assertEquals(3, proxy.requests.get())
            assertEquals(1, proxy.connections.get())
        }
    }

    /** The control: the stack of the phone does honor a request `Connection: close`, which is what the old code sent. */
    @Test fun theOldHeaderOpensOneConnectionPerRequestOnThisStack() {
        repeat(3) { server.enqueue(MockResponse().setBody("""{"ok":true}""")) }
        LatencyProxy(server.port, roundTripMillis = 0, setupRoundTrips = 0).use { proxy ->
            repeat(3) {
                val c = URL("http://127.0.0.1:${proxy.port}/api/v1/snapshot").openConnection() as HttpURLConnection
                c.setRequestProperty("Connection", "close")
                assertEquals(200, c.responseCode)
                c.inputStream.use { it.readBytes() }
                c.disconnect()
            }
            assertEquals(3, proxy.connections.get())
        }
    }

    @Test fun aLostAnswerOnAReusedConnectionIsNotResentByThePhonesStack() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"ok":true}"""))
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        val exchange = exchange(server.port)

        assertTrue(exchange.execute("GET", "/api/v1/snapshot", "tok", null) is HttpResult.Received)
        val lost = exchange.execute("POST", "/api/v1/commands", "tok", body)

        assertEquals(HttpFailure.NETWORK, (lost as HttpResult.Failed).failure)
        assertEquals("one GET and one POST reached the server", 2, server.requestCount)
    }

    @Test fun aConnectionTheServerClosedWhileIdleDoesNotResendAnything() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"ok":true}""").setSocketPolicy(SocketPolicy.DISCONNECT_AT_END))
        server.enqueue(MockResponse().setBody("""{"ok":true}"""))
        val exchange = exchange(server.port)

        assertTrue(exchange.execute("GET", "/api/v1/snapshot", "tok", null) is HttpResult.Received)
        Thread.sleep(200)
        val next = exchange.execute("POST", "/api/v1/commands", "tok", body)

        if (next is HttpResult.Received) assertEquals(200, next.status) else assertEquals(HttpFailure.NETWORK, (next as HttpResult.Failed).failure)
        assertTrue("the POST reached the server at most once", server.requestCount <= 2)
    }
}
