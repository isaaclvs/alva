package net.ts.taild80ca0.atlas.twa.access

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpRetryException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import javax.net.ssl.SSLException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Why an exchange produced no HTTP response to interpret. None of them carries a secret or a body. */
internal enum class HttpFailure {
    /** The body was larger than the limit given to [HttpExchange]; it was not read to the end. */
    TOO_LARGE,

    /** The TLS handshake or certificate was refused. Never retried insecurely. */
    TLS,

    /** Connecting, or waiting for the server, took longer than the timeouts. */
    TIMEOUT,

    /** No connectivity, DNS, reset, or a response cut short. */
    NETWORK,
}

internal sealed interface HttpResult {
    class Received(val status: Int, val body: ByteArray, val retryAfterHeader: String?) : HttpResult

    class Failed(val failure: HttpFailure) : HttpResult
}

/** Seconds from a `Retry-After` header (the integer form only); null if absent, negative or a date. */
internal fun retryAfterSeconds(header: String?): Int? =
    header?.trim()?.toIntOrNull()?.takeIf { it >= 0 }

/**
 * One request over `HttpURLConnection`, with the rules every client of the server shares:
 * the endpoint's own scheme rules ([ServerEndpoint]: https, or loopback in debug), no
 * redirects (a redirect could carry a password or token to another host), no caches,
 * timeouts, a cap on the response body, and no logging. A secret is never put in a URL or
 * in anything this returns, and the bearer travels with each request, never with a connection.
 *
 * Connections are kept alive and reused, as the platform stack does by itself: every new one
 * to the server costs a TCP and a TLS handshake, and a mark is two requests in a row (the
 * command, then the reload). A connection goes back to the pool only after its answer was read
 * to the end and matched its declared length; after anything else it is closed here.
 *
 * A request body is sent with a fixed length. That also stops the JDK from silently
 * resending a POST whose answer was lost: one call here is one request on the wire. A pooled
 * connection the server closed meanwhile, or an answer lost on a reused one, is a
 * [HttpFailure.NETWORK] like any other: the caller may send the same document again, and its
 * command UUID makes the server answer `duplicate` if the first one had been applied.
 */
internal class HttpExchange(
    private val endpoint: ServerEndpoint,
    private val connectTimeoutMillis: Int,
    private val readTimeoutMillis: Int,
    private val maxBodyBytes: Int,
) {
    suspend fun execute(method: String, path: String, bearer: String?, body: ByteArray?, accept: String = "application/json"): HttpResult =
        withContext(Dispatchers.IO) {
            var connection: HttpURLConnection? = null
            // True only when the whole answer was read and checked: then the connection may stay in the pool.
            var reusable = false
            try {
                connection = (URL(endpoint.url(path)).openConnection() as HttpURLConnection).apply {
                    requestMethod = method
                    connectTimeout = connectTimeoutMillis
                    readTimeout = readTimeoutMillis
                    instanceFollowRedirects = false
                    useCaches = false
                    setRequestProperty("Accept", accept)
                    if (bearer != null) setRequestProperty("Authorization", "Bearer $bearer")
                    if (body != null) {
                        doOutput = true
                        setFixedLengthStreamingMode(body.size)
                        setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    }
                }
                if (body != null) connection.outputStream.use { it.write(body) }
                val status = connection.responseCode
                val stream = if (status < 400) connection.inputStream else connection.errorStream
                val received = stream?.use { readCapped(it) } ?: ByteArray(0)
                // The JDK hands back a short body without complaint when the connection dies
                // mid-answer; an answer that is not the length the server declared was lost.
                val declared = connection.contentLengthLong
                if (stream != null && declared >= 0 && declared != received.size.toLong()) return@withContext HttpResult.Failed(HttpFailure.NETWORK)
                reusable = stream != null
                HttpResult.Received(status, received, connection.getHeaderField("Retry-After"))
            } catch (e: HttpRetryException) {
                // The JDK (not Android) refuses to read a 401 or a redirect after a streamed body,
                // because it could not replay it. The status is all that matters, and none of it
                // is followed or retried here.
                HttpResult.Received(e.responseCode(), ByteArray(0), null)
            } catch (_: TooLarge) {
                HttpResult.Failed(HttpFailure.TOO_LARGE)
            } catch (_: SSLException) {
                HttpResult.Failed(HttpFailure.TLS)
            } catch (_: SocketTimeoutException) {
                HttpResult.Failed(HttpFailure.TIMEOUT)
            } catch (_: IOException) {
                HttpResult.Failed(HttpFailure.NETWORK)
            } finally {
                // `disconnect()` after a complete answer is harmless to the pool on Android but closes it on other
                // stacks; after a failure or an answer left unread it is exactly what is wanted.
                if (!reusable) connection?.disconnect()
            }
        }

    private class TooLarge : Exception()

    private fun readCapped(stream: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val read = stream.read(buffer)
            if (read < 0) break
            out.write(buffer, 0, read)
            if (out.size() > maxBodyBytes) throw TooLarge()
        }
        return out.toByteArray()
    }
}
