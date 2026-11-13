package net.ts.taild80ca0.atlas.twa.online

import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import net.ts.taild80ca0.atlas.twa.access.AccessCredential
import net.ts.taild80ca0.atlas.twa.access.ServerEndpoint
import net.ts.taild80ca0.atlas.twa.data.list.ItemStatus
import net.ts.taild80ca0.atlas.twa.sync.HttpCommandTransport
import net.ts.taild80ca0.atlas.twa.sync.HttpSnapshotTransport
import net.ts.taild80ca0.atlas.twa.sync.LatencyProxy
import net.ts.taild80ca0.atlas.twa.sync.SnapshotFixtures
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Measures, with the real HTTP transports, how long one mark takes to reach the server and to come back on the
 * screen when the server is [RTT] away. Reproduce with:
 *
 *     ./gradlew :app:testDebugUnitTest --tests '*MarkLatencyTest' -i | grep LATENCY
 *
 * The proxy charges 2 round trips per new connection (TCP + TLS 1.3) and 1 per request. `HttpExchange` opens a new
 * connection for every request (`Connection: close`), so a mark that needs the command *and* a reload pays that
 * price twice. Server time is not modeled (production answers a command in ~7 ms and a snapshot in ~11 ms).
 */
class MarkLatencyTest {
    private val id = "20000000-0000-4000-8000-000000000001"
    private lateinit var upstream: MockWebServer
    private lateinit var proxy: LatencyProxy
    private val status = AtomicReference("pending")
    private val order = java.util.Collections.synchronizedList(ArrayList<String>())

    /** Called when the server has committed a command, like the ActionCable broadcast that follows a commit. */
    @Volatile private var afterCommit: () -> Unit = {}

    @Before fun start() {
        upstream = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    order += "${request.method} ${request.path}"
                    return when (request.path) {
                        "/api/v1/snapshot" -> MockResponse().setBody(snapshotJson())
                        else -> {
                            status.set("purchased")
                            afterCommit()
                            val commandId = JSONObject(request.body.readUtf8()).getJSONArray("commands").getJSONObject(0).getString("id")
                            MockResponse().setBody(receiptJson(commandId))
                        }
                    }
                }
            }
            start()
        }
        proxy = LatencyProxy(upstream.port, RTT)
    }

    @After fun stop() {
        proxy.close()
        upstream.shutdown()
    }

    private fun snapshotJson() = SnapshotFixtures.snapshot(
        items = listOf(SnapshotFixtures.item(id, "Leite", status = status.get(), lockVersion = if (status.get() == "pending") 0 else 1,
            purchasedAt = if (status.get() == "purchased") "2026-11-13T12:00:00.000000Z" else null)),
    ).toString()

    private fun receiptJson(commandId: String) =
        """{"revision":841,"snapshot_required":false,"results":[{"id":"$commandId","status":"applied","revision":841,"data":{"item":""" +
            """{"public_id":"$id","canonical_public_id":"$id","name":"Leite","status":"purchased","lock_version":1}}}]}"""

    @Test fun aMarkPaysTwoConnectionsAndTwoSequentialRequestsBeforeTheListChanges() = runBlocking {
        val endpoint = ServerEndpoint.parse("http://127.0.0.1:${proxy.port}")
        val access = object : OnlineAccess {
            override suspend fun credential() = AccessCredential(1, "test-only")
            override suspend fun reportUnauthorized(credential: AccessCredential) = Unit
        }
        val repository = OnlineRepository(access, HttpSnapshotTransport(endpoint), HttpCommandTransport(endpoint), CoroutineScope(Dispatchers.Default))
        repository.refresh()
        order.clear()
        val connectionsBefore = proxy.connections.get()

        val started = System.nanoTime()
        val result = repository.execute(SetItemState(id, ItemStatus.PURCHASED))
        val tapToConfirmedList = (System.nanoTime() - started) / 1_000_000

        assertTrue("result=$result order=$order", result is ActionResult.Success)
        val shown = (repository.state.value as OnlineState.Ready).snapshot.liveItems().single()
        assertEquals(ItemStatus.PURCHASED, shown.status)
        println("LATENCY rtt=${RTT}ms setup=2rtt/connection: tap->confirmed-on-screen=${tapToConfirmedList}ms " +
            "requests=$order connections=${proxy.connections.get() - connectionsBefore}")

        // The command and the reload are sequential, each on its own connection.
        assertEquals(listOf("POST /api/v1/commands", "GET /api/v1/snapshot"), order)
        assertEquals(2, proxy.connections.get() - connectionsBefore)
        // 2 requests × (2 setup + 1 request) round trips.
        assertTrue("took only ${tapToConfirmedList}ms", tapToConfirmedList >= 6 * RTT)
    }

    @Test fun aLiveSignalThatLandsDuringTheMarkAddsAThirdSequentialRequest() = runBlocking {
        val endpoint = ServerEndpoint.parse("http://127.0.0.1:${proxy.port}")
        val access = object : OnlineAccess {
            override suspend fun credential() = AccessCredential(1, "test-only")
            override suspend fun reportUnauthorized(credential: AccessCredential) = Unit
        }
        val scope = CoroutineScope(Dispatchers.Default)
        val repository = OnlineRepository(access, HttpSnapshotTransport(endpoint), HttpCommandTransport(endpoint), scope)
        repository.refresh()
        order.clear()
        // The broadcast of the commit reaches the open cable a little before the answer of the POST reaches the app.
        afterCommit = { scope.launch { delay(RTT / 4); repository.requestInvalidation() } }

        val started = System.nanoTime()
        assertTrue(repository.execute(SetItemState(id, ItemStatus.PURCHASED)) is ActionResult.Success)
        val tapToConfirmedList = (System.nanoTime() - started) / 1_000_000
        println("LATENCY rtt=${RTT}ms with a live signal during the mark: tap->confirmed-on-screen=${tapToConfirmedList}ms requests=$order")

        assertEquals(listOf("POST /api/v1/commands", "GET /api/v1/snapshot", "GET /api/v1/snapshot"), order)
    }

    private companion object {
        const val RTT = 100L
    }
}
