package net.ts.taild80ca0.atlas.twa.sync

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * Test-only TCP proxy that makes loopback behave like a slow path to the server (the Atlas is reached over the
 * internet, through Tailscale Funnel). It models, per connection:
 *
 * - setup: [setupRoundTrips] × [roundTripMillis] before the first byte is relayed (TCP handshake = 1, plus TLS 1.3 = 1),
 * - each request/response on it: one [roundTripMillis] (half before reaching the server, half on the way back).
 *
 * Connections are persistent, like the server's (HTTP/1.1 keep-alive): further requests on an open connection pay
 * no setup, and the connection ends when the client closes it or asks for it with `Connection: close`, as Puma and
 * the proxies in front of it do. A request therefore costs `(setupRoundTrips + 1) × roundTripMillis` on a new
 * connection and `roundTripMillis` on a reused one. Bandwidth, packet loss, idle timeouts and server time are not
 * modeled.
 */
class LatencyProxy(
    private val upstreamPort: Int,
    private val roundTripMillis: Long,
    private val setupRoundTrips: Int = 2,
) : AutoCloseable {
    private val listener = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    val port: Int get() = listener.localPort

    /** TCP connections the client opened. */
    val connections = AtomicInteger()

    /** Requests relayed, over all connections. */
    val requests = AtomicInteger()

    init {
        thread(isDaemon = true, name = "latency-proxy") {
            while (!listener.isClosed) {
                try {
                    val client = listener.accept()
                    connections.incrementAndGet()
                    thread(isDaemon = true) { serve(client) }
                } catch (_: IOException) {
                    // closed
                }
            }
        }
    }

    private fun serve(client: Socket) = client.use {
        try {
            Thread.sleep(setupRoundTrips * roundTripMillis)
            val input = client.getInputStream()
            while (true) {
                val head = readHead(input) ?: return
                val body = readBody(input, head)
                requests.incrementAndGet()
                Thread.sleep(roundTripMillis / 2)
                val response = Socket(InetAddress.getByName("127.0.0.1"), upstreamPort).use { upstream ->
                    upstream.getOutputStream().apply { write(head); write(body); flush() }
                    val answer = upstream.getInputStream()
                    // The upstream may keep the connection open: take exactly what its Content-Length says.
                    val answerHead = readHead(answer) ?: throw IOException("closed before the answer")
                    answerHead + readBody(answer, answerHead)
                }
                Thread.sleep(roundTripMillis - roundTripMillis / 2)
                client.getOutputStream().apply { write(response); flush() }
                if (Regex("(?i)\r\nconnection:\\s*close").containsMatchIn(String(head, Charsets.ISO_8859_1))) return
            }
        } catch (_: IOException) {
            // the client went away
        } catch (_: InterruptedException) {
            // test over
        }
    }

    private fun readBody(input: InputStream, head: ByteArray): ByteArray {
        val length = Regex("(?i)\r\ncontent-length:\\s*(\\d+)").find(String(head, Charsets.ISO_8859_1))
            ?.groupValues?.get(1)?.toInt() ?: 0
        val body = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(body, read, length - read)
            if (n < 0) throw IOException("closed before the end of the body")
            read += n
        }
        return body
    }

    /** The head of the next message, or null when the other side closed cleanly before sending any of it. */
    private fun readHead(input: InputStream): ByteArray? {
        val out = ByteArrayOutputStream()
        val end = byteArrayOf('\r'.code.toByte(), '\n'.code.toByte(), '\r'.code.toByte(), '\n'.code.toByte())
        var matched = 0
        while (matched < end.size) {
            val next = input.read()
            if (next < 0) { if (out.size() == 0) return null else throw IOException("closed before the end of the headers") }
            out.write(next)
            matched = if (next.toByte() == end[matched]) matched + 1 else if (next.toByte() == end[0]) 1 else 0
        }
        return out.toByteArray()
    }

    override fun close() {
        listener.close()
    }
}
