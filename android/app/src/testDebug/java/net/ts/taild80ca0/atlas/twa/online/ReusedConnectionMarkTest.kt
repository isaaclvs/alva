package net.ts.taild80ca0.atlas.twa.online

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import net.ts.taild80ca0.atlas.twa.access.AccessCredential
import net.ts.taild80ca0.atlas.twa.access.ServerEndpoint
import net.ts.taild80ca0.atlas.twa.data.list.ItemStatus
import net.ts.taild80ca0.atlas.twa.sync.HttpCommandTransport
import net.ts.taild80ca0.atlas.twa.sync.HttpSnapshotTransport
import net.ts.taild80ca0.atlas.twa.sync.SnapshotFixtures
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Marks over the real transports and a real socket, now that connections are reused: a connection that the server
 * dropped, or an answer lost on one that was in use, must end with the item marked **once** and the screen telling
 * the truth, because every retry carries the same command UUID and the server keeps a ledger of them.
 */
class ReusedConnectionMarkTest {
    private val id = "20000000-0000-4000-8000-000000000001"
    private lateinit var server: MockWebServer
    private val effects = AtomicInteger()
    private val ledger = HashMap<String, Int>()
    private val commandIds = java.util.Collections.synchronizedList(ArrayList<String>())
    private val status = java.util.concurrent.atomic.AtomicReference("pending")

    /** What the next command does on the wire: nothing special, answer lost after applying, or hang up after answering. */
    private val commandPolicies = java.util.concurrent.ConcurrentLinkedQueue<SocketPolicy>()

    @Before fun start() {
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                    "/api/v1/snapshot" -> MockResponse().setBody(snapshotJson())
                    else -> command(request)
                }
            }
            start()
        }
    }

    @After fun stop() {
        server.shutdown()
    }

    private fun snapshotJson() = SnapshotFixtures.snapshot(
        items = listOf(SnapshotFixtures.item(id, "Leite", status = status.get(), lockVersion = if (status.get() == "pending") 0 else 1,
            purchasedAt = if (status.get() == "purchased") "2026-11-13T12:00:00.000000Z" else null)),
    ).toString()

    @Synchronized private fun command(request: RecordedRequest): MockResponse {
        val commandId = JSONObject(request.body.readUtf8()).getJSONArray("commands").getJSONObject(0).getString("id")
        commandIds += commandId
        val duplicate = commandId in ledger
        if (!duplicate) { ledger[commandId] = 1; effects.incrementAndGet(); status.set("purchased") }
        val item = """{"item":{"public_id":"$id","canonical_public_id":"$id","name":"Leite","status":"purchased","purchased_at":"2026-11-13T12:00:00.000000Z","lock_version":1}}"""
        val result = if (duplicate) """{"id":"$commandId","status":"duplicate","original_status":"applied","revision":841,"data":$item}"""
        else """{"id":"$commandId","status":"applied","revision":841,"data":$item}"""
        val policy = commandPolicies.poll() ?: SocketPolicy.KEEP_OPEN
        return MockResponse().setSocketPolicy(policy)
            .setBody("""{"revision":841,"snapshot_required":false,"results":[$result]}""")
            .let { if (policy == SocketPolicy.DISCONNECT_AFTER_REQUEST) MockResponse().setSocketPolicy(policy) else it }
    }

    private fun repository(): OnlineRepository {
        val endpoint = ServerEndpoint.parse(server.url("/").toString())
        val access = object : OnlineAccess {
            override suspend fun credential() = AccessCredential(1, "test-only")
            override suspend fun reportUnauthorized(credential: AccessCredential) = Unit
        }
        return OnlineRepository(access, HttpSnapshotTransport(endpoint), HttpCommandTransport(endpoint), CoroutineScope(Dispatchers.Default), pause = { })
    }

    private fun shown(repository: OnlineRepository) = (repository.state.value as OnlineState.Ready).snapshot.listItems.single()

    @Test fun aLostAnswerOnAReusedConnectionIsRetriedWithTheSameUuidAndMarksOnce() = runBlocking {
        val repository = repository()
        repository.refresh() // the connection every later request reuses
        commandPolicies += SocketPolicy.DISCONNECT_AFTER_REQUEST // applied on the server, the answer never comes

        val result = repository.execute(SetItemState(id, ItemStatus.PURCHASED))

        assertTrue("$result", result is ActionResult.Success)
        assertEquals("the retry names the same command", 2, commandIds.size)
        assertEquals(1, commandIds.distinct().size)
        assertEquals("marked once", 1, effects.get())
        assertEquals(1, shown(repository).status)
    }

    @Test fun aConnectionTheServerClosedWhileIdleNeverResendsTheMarkAndTheScreenKeepsWhatWasConfirmed() = runBlocking {
        val repository = repository()
        // The answer arrives, then the server hangs up: the reload finds a dead connection in the pool. Whether the
        // stack opens another one by itself or the read fails, the command is not sent again and the mark stays.
        commandPolicies += SocketPolicy.DISCONNECT_AT_END

        val result = repository.execute(SetItemState(id, ItemStatus.PURCHASED))

        assertTrue("$result", result is ActionResult.Success)
        assertEquals("the POST was not resent", 1, commandIds.size)
        assertEquals(1, effects.get())
        assertEquals(1, shown(repository).status)

        repository.refresh() // a later read is the server's list again, with no warning left
        assertEquals(1, shown(repository).status)
        assertEquals(null, (repository.state.value as OnlineState.Ready).problem)
    }
}
