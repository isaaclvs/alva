package net.ts.taild80ca0.atlas.twa.sync

import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLException
import net.ts.taild80ca0.atlas.twa.access.AccessCredential
import net.ts.taild80ca0.atlas.twa.access.ServerEndpoint
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject

sealed interface CableEvent {
    data object Subscribed : CableEvent
    data class Invalidated(val household: Long, val dictionary: Long) : CableEvent
    data class Ended(val reason: CableEnd) : CableEvent
}
enum class CableEnd { RETRY, UNAUTHORIZED, PERMANENT }
fun interface CableSocket { fun close() }
fun interface CableConnector {
    fun open(endpoint: ServerEndpoint, credential: AccessCredential, receive: (CableEvent) -> Unit): CableSocket
}

/** Only the ActionCable framing lives here. Messages are hints, never list data. */
class OkHttpCableConnector(
    client: OkHttpClient = OkHttpClient(),
) : CableConnector {
    private val client = client.newBuilder().followRedirects(false).followSslRedirects(false).build()
    override fun open(endpoint: ServerEndpoint, credential: AccessCredential, receive: (CableEvent) -> Unit): CableSocket {
        val ended = AtomicBoolean(false)
        val subscriptionRequested = AtomicBoolean(false)
        val subscribed = AtomicBoolean(false)
        fun end(reason: CableEnd) {
            if (ended.compareAndSet(false, true)) receive(CableEvent.Ended(reason))
        }
        val request = Request.Builder().url(endpoint.url("/api/v1/cable"))
            .header("Authorization", "Bearer ${credential.token}")
            .header("Origin", endpoint.origin)
            .header("Sec-WebSocket-Protocol", "actioncable-v1-json").build()
        val socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                if (ended.get()) return
                if (text.length > MAX_FRAME_LENGTH) {
                    end(CableEnd.PERMANENT)
                    webSocket.close(1008, "Invalid frame")
                    return
                }
                val frame = try { JSONObject(text) } catch (_: org.json.JSONException) { return }
                when (frame.optString("type")) {
                    "welcome" -> if (subscriptionRequested.compareAndSet(false, true)) {
                        webSocket.send(JSONObject().put("command", "subscribe").put("identifier", IDENTIFIER).toString())
                    }
                    "confirm_subscription" -> if (subscriptionRequested.get() && frame.optString("identifier") == IDENTIFIER && subscribed.compareAndSet(false, true)) {
                        receive(CableEvent.Subscribed)
                    }
                    "reject_subscription" -> {
                        end(CableEnd.PERMANENT)
                        webSocket.close(1008, "Subscription rejected")
                    }
                    "disconnect" -> {
                        val reason = when {
                            frame.optString("reason") == "unauthorized" -> CableEnd.UNAUTHORIZED
                            frame.opt("reconnect") == false -> CableEnd.PERMANENT
                            else -> CableEnd.RETRY
                        }
                        end(reason)
                        webSocket.close(1000, "Disconnected")
                    }
                    else -> if (subscribed.get() && frame.optString("identifier") == IDENTIFIER) {
                        val message = frame.optJSONObject("message") ?: return
                        if (message.optString("type") != "invalidate") return
                        val household = revision(message.opt("household_revision")) ?: return
                        val dictionary = revision(message.opt("dictionary_revision")) ?: return
                        receive(CableEvent.Invalidated(household, dictionary))
                    }
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                end(when {
                    response?.code == 401 || response?.code == 403 -> CableEnd.UNAUTHORIZED
                    t is SSLException -> CableEnd.PERMANENT
                    response != null && response.code !in 500..599 && response.code !in setOf(408, 425, 429) -> CableEnd.PERMANENT
                    t is java.net.ProtocolException && response == null -> CableEnd.PERMANENT
                    else -> CableEnd.RETRY
                })
                response?.body?.close()
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                end(CableEnd.RETRY)
                webSocket.close(code, reason)
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { end(CableEnd.RETRY) }
        })
        return CableSocket {
            ended.set(true)
            socket.cancel()
        }
    }

    private fun revision(value: Any?): Long? = when (value) {
        is Int -> value.toLong().takeIf { it >= 0 }
        is Long -> value.takeIf { it >= 0 }
        else -> null
    }

    companion object {
        const val IDENTIFIER = "{\"channel\":\"SyncCable::Channel\"}"
        private const val MAX_FRAME_LENGTH = 4096
    }
}
