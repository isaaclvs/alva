package net.ts.taild80ca0.atlas.twa.sync

import java.io.File
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import net.ts.taild80ca0.atlas.twa.access.*
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/** Real Cable + PWA HTTP + snapshot, isolated by android_rails_transport_check. */
class RailsCableTransportTest {
    private val harness = System.getenv("ALVA_RAILS_HARNESS")?.let { JSONObject(File(it).readText()) }
    private lateinit var endpoint: ServerEndpoint

    @Before fun prepare() {
        assumeTrue("needs ALVA_RAILS_HARNESS", harness != null)
        endpoint = ServerEndpoint.parse(harness!!.getString("url"))
        assertTrue(endpoint.origin.startsWith("http://127.0.0.1:"))
    }

    @Test fun foregroundReceivesPwaChangesAndReconnectAlwaysReadsTheMissingSnapshot() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val received = Channel<ServerSnapshot>(Channel.UNLIMITED)
        val credential = AccessCredential(1, harness!!.getString("sync_token"))
        val vault = InMemoryVault().apply { write(StoredAccess(credential)) }
        val access = HouseAccess(vault, HttpHouseAccessApi(endpoint), InMemoryBinding())
        access.refresh()
        val controller = SyncController(SyncRunner {
            val result = HttpSnapshotTransport(endpoint, 5_000, 10_000).fetch(credential)
            assertTrue("real snapshot must parse", result is SnapshotOutcome.Received)
            val snapshot = (result as SnapshotOutcome.Received).snapshot
            received.send(snapshot)
            SyncReport(SyncOutcome.Synced(snapshot.revision))
        }, scope)
        val websocket = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).build()
        val manager = ForegroundInvalidation(access, endpoint, controller, scope, OkHttpCableConnector(websocket))
        val web = pwaClient()
        try {
            manager.start()
            withTimeout(10_000) { received.receive() }
            val first = "PWA " + UUID.randomUUID().toString()
            addThroughPwa(web, first)
            withTimeout(10_000) {
                while (received.receive().listItems.none { it.name == first }) { /* read until this revision is visible */ }
            }
            manager.stop()
            val second = "PWA offline " + UUID.randomUUID().toString()
            addThroughPwa(web, second)
            manager.start()
            withTimeout(10_000) {
                while (received.receive().listItems.none { it.name == second }) { /* subscription confirmation forces a snapshot */ }
            }
        } finally {
            manager.stop()
            scope.cancel()
            websocket.dispatcher.executorService.shutdown()
            websocket.connectionPool.evictAll()
            web.dispatcher.executorService.shutdown()
            web.connectionPool.evictAll()
        }
    }

    @Test fun widgetAndRevokedCredentialsCannotSubscribeToTheRealNativeChannel() {
        for (key in listOf("add_only_token", "revoked_token")) {
            val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).build()
            val events = LinkedBlockingQueue<CableEvent>()
            val socket = OkHttpCableConnector(client).open(endpoint, AccessCredential(1, harness!!.getString(key))) { events.add(it) }
            try {
                assertEquals(CableEvent.Ended(CableEnd.UNAUTHORIZED), events.poll(10, TimeUnit.SECONDS))
            } finally {
                socket.close()
                client.dispatcher.executorService.shutdown()
                client.connectionPool.evictAll()
            }
        }
    }

    private fun pwaClient(): OkHttpClient {
        val cookies = mutableMapOf<String, Cookie>()
        return OkHttpClient.Builder().followRedirects(false).cookieJar(object : CookieJar {
            override fun saveFromResponse(url: HttpUrl, received: List<Cookie>) { received.forEach { cookies[it.name] = it } }
            override fun loadForRequest(url: HttpUrl): List<Cookie> = cookies.values.filter { it.matches(url) }
        }).build()
    }

    private fun csrf(html: String): String? = Regex("name=\"csrf-token\" content=\"([^\"]+)\"").find(html)?.groupValues?.get(1)

    private fun addThroughPwa(web: OkHttpClient, name: String) {
        val login = web.newCall(Request.Builder().url(endpoint.url("/entrar")).build()).execute().use { it.body!!.string() }
        val loginRequest = Request.Builder().url(endpoint.url("/entrar"))
            .post(FormBody.Builder().add("password", "harness-only").build())
        csrf(login)?.let { loginRequest.header("X-CSRF-Token", it) }
        web.newCall(loginRequest.build()).execute().use { assertTrue("test PWA login", it.code in 300..399) }
        val home = web.newCall(Request.Builder().url(endpoint.url("/")).build()).execute().use {
            assertEquals(200, it.code)
            it.body!!.string()
        }
        val add = Request.Builder().url(endpoint.url("/list_items"))
            .header("Accept", "text/vnd.turbo-stream.html")
            .post(FormBody.Builder().add("list_item[name]", name).build())
        csrf(home)?.let { add.header("X-CSRF-Token", it) }
        web.newCall(add.build()).execute().use { assertEquals("real PWA add", 200, it.code) }
    }
}
