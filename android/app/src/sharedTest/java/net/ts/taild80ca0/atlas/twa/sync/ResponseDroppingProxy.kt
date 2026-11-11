package net.ts.taild80ca0.atlas.twa.sync

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * Test-only TCP proxy in front of a real server that loses answers *after* the server has
 * committed: it forwards the whole request, waits for the server's complete response, and
 * then, for the next [dropNextResponses] responses, closes the client's connection without
 * writing a byte of it. To the client that is exactly "the response was lost", while the
 * effect is already in the server's database. No failure endpoint exists in Rails for this.
 *
 * One request per connection (the transport sends `Connection: close`).
 */
class ResponseDroppingProxy(private val upstreamPort: Int) : AutoCloseable {
    private val listener = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    private val toDrop = AtomicInteger(0)

    /** The proxy's port; point the client here. */
    val port: Int get() = listener.localPort

    /** Requests the real server received and answered (whether or not the answer was passed on). */
    val answeredByServer = AtomicInteger()

    /** Answers the server gave that were swallowed. */
    val dropped = AtomicInteger()

    /** The request bodies the server received, in order. */
    val requestBodies = CopyOnWriteArrayList<ByteArray>()

    fun dropNextResponses(count: Int) {
        toDrop.set(count)
    }

    init {
        thread(isDaemon = true, name = "response-dropping-proxy") {
            while (!listener.isClosed) {
                try {
                    val client = listener.accept()
                    thread(isDaemon = true) { serve(client) }
                } catch (_: IOException) {
                    // closed
                }
            }
        }
    }

    private fun serve(client: Socket) {
        client.use {
            try {
                client.soTimeout = TIMEOUT_MILLIS
                val input = client.getInputStream()
                val head = readHead(input)
                val length = Regex("(?i)\r\ncontent-length:\\s*(\\d+)").find(String(head, Charsets.ISO_8859_1))
                    ?.groupValues?.get(1)?.toInt() ?: 0
                val body = readExactly(input, length)

                val response = Socket(InetAddress.getByName("127.0.0.1"), upstreamPort).use { upstream ->
                    upstream.soTimeout = TIMEOUT_MILLIS
                    upstream.getOutputStream().apply {
                        write(head)
                        write(body)
                        flush()
                    }
                    upstream.getInputStream().readBytes()
                }
                requestBodies += body
                answeredByServer.incrementAndGet()

                if (toDrop.getAndUpdate { if (it > 0) it - 1 else 0 } > 0) {
                    dropped.incrementAndGet()
                    return // closes the connection with no answer
                }
                client.getOutputStream().apply {
                    write(response)
                    flush()
                }
            } catch (_: IOException) {
                // the client or the server went away; nothing to relay
            }
        }
    }

    private fun readHead(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val end = byteArrayOf('\r'.code.toByte(), '\n'.code.toByte(), '\r'.code.toByte(), '\n'.code.toByte())
        var matched = 0
        while (matched < end.size) {
            val next = input.read()
            if (next < 0) throw IOException("closed before the end of the headers")
            out.write(next)
            matched = if (next.toByte() == end[matched]) matched + 1 else if (next.toByte() == end[0]) 1 else 0
        }
        return out.toByteArray()
    }

    private fun readExactly(input: InputStream, length: Int): ByteArray {
        val body = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(body, read, length - read)
            if (n < 0) throw IOException("closed before the end of the body")
            read += n
        }
        return body
    }

    override fun close() {
        listener.close()
    }

    private companion object {
        const val TIMEOUT_MILLIS = 20_000
    }
}
