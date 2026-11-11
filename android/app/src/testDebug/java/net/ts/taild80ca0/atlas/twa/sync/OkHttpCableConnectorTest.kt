package net.ts.taild80ca0.atlas.twa.sync

import java.util.concurrent.CompletableFuture
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import net.ts.taild80ca0.atlas.twa.access.AccessCredential
import net.ts.taild80ca0.atlas.twa.access.ServerEndpoint
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class OkHttpCableConnectorTest {
    private val token = AccessCredential(1, "test-only-secret")

    @Test fun authenticatesInHeadersAndSubscribesBeforeAcceptingStrictRevisionHints() {
        val server = MockWebServer()
        val client = OkHttpClient()
        val events = LinkedBlockingQueue<CableEvent>()
        val subscription = CompletableFuture<JSONObject>()
        server.enqueue(MockResponse().setHeader("Sec-WebSocket-Protocol", "actioncable-v1-json").withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { webSocket.send("""{"type":"welcome"}""") }
            override fun onMessage(webSocket: WebSocket, text: String) {
                subscription.complete(JSONObject(text))
                val identifier = OkHttpCableConnector.IDENTIFIER
                val confirmation = JSONObject().put("type", "confirm_subscription").put("identifier", identifier).toString()
                webSocket.send(confirmation)
                webSocket.send(confirmation)
                fun hint(house: Any, dictionary: Any) = JSONObject().put("identifier", identifier).put("message",
                    JSONObject().put("type", "invalidate").put("household_revision", house).put("dictionary_revision", dictionary)).toString()
                webSocket.send(hint("2", 0))
                webSocket.send(hint(2.5, 0))
                webSocket.send(hint(-1, 0))
                webSocket.send(hint(2, 0))
            }
        }))
        server.start()
        val endpoint = ServerEndpoint.parse(server.url("/").toString())
        val socket = OkHttpCableConnector(client).open(endpoint, token) { events.add(it) }
        try {
            assertEquals(CableEvent.Subscribed, events.poll(5, TimeUnit.SECONDS))
            assertEquals(CableEvent.Invalidated(2, 0), events.poll(5, TimeUnit.SECONDS))
            val sent = subscription.get(5, TimeUnit.SECONDS)
            assertEquals("subscribe", sent.getString("command"))
            assertEquals(OkHttpCableConnector.IDENTIFIER, sent.getString("identifier"))
            val request = server.takeRequest(5, TimeUnit.SECONDS)!!
            assertEquals("/api/v1/cable", request.path)
            assertEquals("Bearer ${token.token}", request.getHeader("Authorization"))
            assertEquals(endpoint.origin, request.getHeader("Origin"))
            assertEquals("actioncable-v1-json", request.getHeader("Sec-WebSocket-Protocol"))
            assertNull(events.poll(100, TimeUnit.MILLISECONDS))
        } finally {
            socket.close()
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
            server.shutdown()
        }
    }

    @Test fun unauthorizedHandshakeIsTerminalAndRedirectsDoNotForwardCredentials() {
        for ((code, expected) in listOf(401 to CableEnd.UNAUTHORIZED, 403 to CableEnd.UNAUTHORIZED, 302 to CableEnd.PERMANENT, 200 to CableEnd.PERMANENT, 503 to CableEnd.RETRY)) {
            val server = MockWebServer()
            val client = OkHttpClient()
            server.enqueue(MockResponse().setResponseCode(code).setHeader("Location", "http://127.0.0.1:1/leak"))
            server.start()
            val events = LinkedBlockingQueue<CableEvent>()
            val socket = OkHttpCableConnector(client).open(ServerEndpoint.parse(server.url("/").toString()), token) { events.add(it) }
            try {
                assertEquals("HTTP $code", CableEvent.Ended(expected), events.poll(5, TimeUnit.SECONDS))
                assertEquals(1, server.requestCount)
            } finally {
                socket.close()
                client.dispatcher.executorService.shutdown()
                client.connectionPool.evictAll()
                server.shutdown()
            }
        }
    }
}
