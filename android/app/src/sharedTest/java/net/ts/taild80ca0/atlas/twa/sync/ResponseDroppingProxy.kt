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
 * Connections are persistent like the transport's: it relays request after request on one client connection
 * (a request that asks for `Connection: close` ends it), and frames the server's answer by its `Content-Length`
 * (or its chunks) instead of waiting for the server to hang up, because the server keeps its connection open. An
 * answer with neither is read until the server closes.
 */
class ResponseDroppingProxy(private val upstreamPort: Int) : AutoCloseable {
    private val listener = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    private val toDrop = AtomicInteger(0)

    /** The proxy's port; point the client here. */
    val port: Int get() = listener.localPort

    /** Requests the real server received and answered (whether or not the answer was passed on). */
    val answeredByServer = AtomicInteger()

    /** TCP connections the client opened to this proxy. */
    val connections = AtomicInteger()

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
                    connections.incrementAndGet()
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
                while (true) {
                    val head = readHead(input) ?: return
                    val body = readExactly(input, contentLength(head) ?: 0)

                    val response = Socket(InetAddress.getByName("127.0.0.1"), upstreamPort).use { upstream ->
                        upstream.soTimeout = TIMEOUT_MILLIS
                        upstream.getOutputStream().apply {
                            write(head)
                            write(body)
                            flush()
                        }
                        val answer = upstream.getInputStream()
                        val answerHead = readHead(answer) ?: throw IOException("closed before the answer")
                        val length = contentLength(answerHead)
                        val chunked = Regex("(?i)\\r\\ntransfer-encoding:\\s*chunked").containsMatchIn(String(answerHead, Charsets.ISO_8859_1))
                        answerHead + when {
                            length != null -> readExactly(answer, length)
                            chunked -> readChunked(answer)
                            else -> answer.readBytes()
                        }
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
                    if (Regex("(?i)\r\nconnection:\\s*close").containsMatchIn(String(head, Charsets.ISO_8859_1))) return
                }
            } catch (_: IOException) {
                // the client or the server went away; nothing to relay
            }
        }
    }

    /** The raw chunked body, size lines and trailers included, up to the last chunk. */
    private fun readChunked(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        fun line(): ByteArray {
            val buffer = ByteArrayOutputStream()
            while (true) {
                val next = input.read()
                if (next < 0) throw IOException("closed inside a chunked body")
                buffer.write(next)
                if (next == '\n'.code) return buffer.toByteArray()
            }
        }
        while (true) {
            val sizeLine = line()
            out.write(sizeLine)
            val size = String(sizeLine, Charsets.ISO_8859_1).substringBefore(';').trim().toInt(16)
            if (size == 0) {
                while (true) { val trailer = line(); out.write(trailer); if (trailer.size <= 2) return out.toByteArray() }
            }
            out.write(readExactly(input, size))
            out.write(line()) // the CRLF after the chunk
        }
    }

    private fun contentLength(head: ByteArray): Int? =
        Regex("(?i)\r\ncontent-length:\\s*(\\d+)").find(String(head, Charsets.ISO_8859_1))?.groupValues?.get(1)?.toInt()

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
