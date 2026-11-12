package net.ts.taild80ca0.atlas.twa.access

import java.net.URI
import java.net.URISyntaxException

/**
 * The server the device talks to. TLS is never relaxed for a real server: the
 * only plain-HTTP origin accepted is the device's own loopback (`localhost`,
 * `127.0.0.1`, `::1`), which is how tests and a local Rails reach the emulator
 * (`adb reverse`). Anything else must be `https`, and the URL must be a bare
 * origin: no credentials, path or query.
 */
class ServerEndpoint private constructor(val origin: String) {
    fun url(path: String): String = origin + path

    override fun toString(): String = origin

    companion object {
        private val LOOPBACK_HOSTS = setOf("localhost", "127.0.0.1", "[::1]")

        /** Null when [raw] is not an acceptable server address. */
        fun parseOrNull(raw: String): ServerEndpoint? {
            val uri = try {
                URI(raw.trim())
            } catch (_: URISyntaxException) {
                return null
            }
            val scheme = uri.scheme?.lowercase() ?: return null
            val host = uri.host?.lowercase() ?: return null
            val hasExtras = uri.userInfo != null || uri.rawQuery != null || uri.rawFragment != null ||
                !(uri.rawPath.isNullOrEmpty() || uri.rawPath == "/")
            if (hasExtras) return null
            val plainHost = if (host.contains(':') && !host.startsWith("[")) "[$host]" else host
            val allowed = scheme == "https" || (scheme == "http" && plainHost in LOOPBACK_HOSTS)
            if (!allowed) return null
            val port = if (uri.port == -1) "" else ":${uri.port}"
            return ServerEndpoint("$scheme://$plainHost$port")
        }

        fun parse(raw: String): ServerEndpoint =
            parseOrNull(raw) ?: throw IllegalArgumentException("Server address must be https (or loopback)")
    }
}
