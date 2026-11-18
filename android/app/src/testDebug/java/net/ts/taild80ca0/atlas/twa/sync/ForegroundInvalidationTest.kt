package net.ts.taild80ca0.atlas.twa.sync

import kotlinx.coroutines.async
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import net.ts.taild80ca0.atlas.twa.access.*
import org.junit.Assert.*
import org.junit.Test

class ForegroundInvalidationTest {
    private val endpoint = ServerEndpoint.parse("http://127.0.0.1:3010")
    private val credential = AccessCredential(1, "test-token")
    private val vault = InMemoryVault().apply { write(StoredAccess(credential)) }
    private val access = HouseAccess(vault, object : HouseAccessApi {
        override suspend fun pair(password: String, deviceName: String, previousToken: String?): ApiResult<AccessCredential> = error("no pairing")
        override suspend fun identifyHousehold(credential: AccessCredential): ApiResult<HouseholdIdentity> = error("no HTTP")
    }, InMemoryBinding())

    private class Connector : CableConnector {
        data class Opened(val token: AccessCredential, val receive: (CableEvent) -> Unit, var closed: Boolean = false, val closure: CompletableDeferred<Unit> = CompletableDeferred())
        val opened = mutableListOf<Opened>()
        private val openings = Channel<Opened>(Channel.UNLIMITED)
        suspend fun nextOpen(): Opened = withTimeout(2_000) { openings.receive() }
        override fun open(endpoint: ServerEndpoint, credential: AccessCredential, receive: (CableEvent) -> Unit): CableSocket {
            val item = Opened(credential, receive)
            opened += item
            openings.trySend(item)
            return CableSocket { item.closed = true; item.closure.complete(Unit) }
        }
        fun emit(event: CableEvent) = opened.last().receive(event)
    }

    @Test fun subscriptionAndNewRevisionsSyncButDuplicatesAndLateCallbacksDoNot() = runBlocking {
        access.refresh()
        val connector = Connector()
        val runs = Channel<Unit>(Channel.UNLIMITED)
        val controller = SyncController(SyncRunner { runs.send(Unit); SyncReport(SyncOutcome.Synced(1)) }, this)
        val manager = ForegroundInvalidation(access, endpoint, controller, this, connector)
        try {
            manager.start()
            connector.nextOpen()
            assertEquals(1, connector.opened.size)
            connector.emit(CableEvent.Subscribed)
            withTimeout(2_000) { runs.receive() }
            yield()
            connector.emit(CableEvent.Invalidated(2, 0))
            withTimeout(2_000) { runs.receive() }
            yield()
            connector.emit(CableEvent.Invalidated(2, 0))
            connector.emit(CableEvent.Invalidated(1, 0))
            yield()
            assertTrue(runs.tryReceive().isFailure)
            manager.stop()
            withTimeout(2_000) { connector.opened.first().closure.await() }
            assertTrue(connector.opened.first().closed)
            connector.emit(CableEvent.Invalidated(100, 100))
            yield()
            assertTrue(runs.tryReceive().isFailure)
        } finally { manager.stop() }
    }

    @Test fun signalsDuringAWorkerCycleHaveOneSuccessorWithoutResumeThrottle() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val runs = Channel<Int>(Channel.UNLIMITED)
        var count = 0
        val controller = SyncController(SyncRunner {
            count++
            runs.send(count)
            if (count == 1) gate.await()
            SyncReport(SyncOutcome.Synced(count.toLong()))
        }, this)
        val worker = async { controller.runInBackground() }
        withTimeout(2_000) { assertEquals(1, runs.receive()) }
        repeat(20) { controller.requestInvalidation() }
        gate.complete(Unit)
        worker.await()
        withTimeout(2_000) { assertEquals(2, runs.receive()) }
        yield()
        assertTrue(runs.tryReceive().isFailure)
    }

    @Test fun reconnectChecksSnapshotAndStopCancelsBackoff() = runBlocking {
        access.refresh()
        val connector = Connector()
        val runs = Channel<Unit>(Channel.UNLIMITED)
        val pauses = Channel<Long>(Channel.UNLIMITED)
        val resume = Channel<Unit>()
        val controller = SyncController(SyncRunner { runs.send(Unit); SyncReport(SyncOutcome.Synced(1)) }, this)
        val manager = ForegroundInvalidation(access, endpoint, controller, this, connector, pause = { pauses.send(it); resume.receive() })
        try {
            manager.start(); connector.nextOpen()
            connector.emit(CableEvent.Subscribed)
            withTimeout(2_000) { runs.receive() }
            connector.emit(CableEvent.Ended(CableEnd.RETRY))
            withTimeout(2_000) { assertEquals(1_000L, pauses.receive()) }
            resume.send(Unit); connector.nextOpen()
            assertEquals(2, connector.opened.size)
            connector.emit(CableEvent.Subscribed)
            withTimeout(2_000) { runs.receive() }
            connector.emit(CableEvent.Ended(CableEnd.RETRY))
            withTimeout(2_000) { pauses.receive() }
            manager.stop()
            withTimeout(2_000) { connector.opened.last().closure.await() }
            assertTrue(connector.opened.last().closed)
            assertEquals(2, connector.opened.size)
        } finally { manager.stop() }
    }

    @Test fun unauthorizedPreservesTheCredentialForReauthorizationAndDoesNotLoop() = runBlocking {
        access.refresh()
        val connector = Connector()
        val controller = SyncController(SyncRunner { error("no snapshot before subscription") }, this)
        val manager = ForegroundInvalidation(access, endpoint, controller, this, connector, pause = { error("must not retry authorization") })
        try {
            manager.start(); connector.nextOpen()
            connector.emit(CableEvent.Ended(CableEnd.UNAUTHORIZED))
            withTimeout(2_000) { access.state.first { it == AccessState.NeedsReconnect } }
            withTimeout(2_000) { connector.opened.single().closure.await() }
            assertEquals(AccessState.NeedsReconnect, access.state.value)
            assertEquals(credential, (vault.read() as VaultRead.Found).access.active)
            assertEquals(1, connector.opened.size)
            assertTrue(connector.opened.single().closed)
        } finally { manager.stop() }
    }

    @Test fun localCommitRequestsOnlyWhileForegroundAndLateAuthorizationCannotRevokeANewToken() = runBlocking {
        access.refresh()
        val connector = Connector()
        val runs = Channel<Unit>(Channel.UNLIMITED)
        val controller = SyncController(SyncRunner { runs.send(Unit); SyncReport(SyncOutcome.Synced(1)) }, this)
        val manager = ForegroundInvalidation(access, endpoint, controller, this, connector)
        try {
            manager.localChange()
            yield()
            assertTrue(runs.tryReceive().isFailure)
            manager.start(); connector.nextOpen()
            manager.localChange()
            withTimeout(2_000) { runs.receive() }
            val replacement = AccessCredential(2, "replacement-test-token")
            vault.write(StoredAccess(replacement))
            access.refresh()
            connector.emit(CableEvent.Ended(CableEnd.UNAUTHORIZED))
            assertEquals(replacement, connector.nextOpen().token)
            assertEquals(AccessState.Connected, access.state.value)
            manager.stop()
            withTimeout(2_000) { connector.opened.last().closure.await() }
            manager.localChange()
            yield()
            assertTrue(runs.tryReceive().isFailure)
        } finally { manager.stop() }
    }

    /** Test watcher: registering on a real phone delivers the network already in place, so this fake does the same. */
    private class FakeNetwork : NetworkWatcher {
        var startCalls = 0
        var stopCalls = 0
        private var available: (() -> Unit)? = null
        override fun start(onAvailable: () -> Unit) { startCalls++; available = onAvailable; onAvailable() }
        override fun stop() { stopCalls++; available = null }
        fun reconnect() { available?.invoke() ?: error("the watcher is not started") }
    }

    @Test fun silenceAfterSubscriptionDropsTheHalfOpenSocketAndReconnects() = runBlocking {
        access.refresh()
        val connector = Connector()
        val network = FakeNetwork()
        val runs = Channel<Unit>(Channel.UNLIMITED)
        val pauses = Channel<Long>(Channel.UNLIMITED)
        val resume = Channel<Unit>()
        val controller = SyncController(SyncRunner { runs.send(Unit); SyncReport(SyncOutcome.Synced(1)) }, this)
        val manager = ForegroundInvalidation(
            access, endpoint, controller, this, connector,
            pause = { pauses.send(it); resume.receive() }, network = network, silenceTimeoutMillis = 300,
        )
        try {
            manager.start(); connector.nextOpen()
            connector.emit(CableEvent.Subscribed)
            withTimeout(2_000) { runs.receive() }
            // No ping and no frame at all: the watchdog ends the session itself, without an Ended event.
            withTimeout(2_000) { connector.opened.single().closure.await() }
            assertTrue(connector.opened.single().closed)
            withTimeout(2_000) { assertEquals(1_000L, pauses.receive()) }
            resume.send(Unit); connector.nextOpen()
            assertEquals(2, connector.opened.size)
            // A live socket whose pings keep arriving is never dropped, and a network change does not cut it.
            connector.emit(CableEvent.Subscribed)
            withTimeout(2_000) { runs.receive() }
            repeat(3) {
                delay(100)
                connector.emit(CableEvent.Ping)
            }
            network.reconnect()
            withTimeout(2_000) { runs.receive() }
            assertFalse(connector.opened.last().closed)
            assertTrue(pauses.tryReceive().isFailure)
        } finally { manager.stop() }
    }

    @Test fun networkBackResetsTheBackoffAndReadsTheListAgain() = runBlocking {
        access.refresh()
        val connector = Connector()
        val runs = Channel<Unit>(Channel.UNLIMITED)
        val pauses = Channel<Long>(Channel.UNLIMITED)
        val resume = Channel<Unit>()
        val network = FakeNetwork()
        val controller = SyncController(SyncRunner { runs.send(Unit); SyncReport(SyncOutcome.Synced(1)) }, this)
        val manager = ForegroundInvalidation(
            access, endpoint, controller, this, connector,
            pause = { pauses.send(it); resume.receive() }, network = network,
        )
        try {
            manager.start(); connector.nextOpen()
            assertEquals(1, network.startCalls)
            connector.emit(CableEvent.Subscribed)
            withTimeout(2_000) { runs.receive() }
            connector.emit(CableEvent.Ended(CableEnd.RETRY))
            withTimeout(2_000) { assertEquals(1_000L, pauses.receive()) }
            // The network returns while the first backoff waits: the wait ends and the list is read again.
            network.reconnect()
            connector.nextOpen()
            withTimeout(2_000) { runs.receive() }
            connector.emit(CableEvent.Subscribed)
            withTimeout(2_000) { runs.receive() }
            connector.emit(CableEvent.Ended(CableEnd.RETRY))
            // The next wait is the initial backoff again, not the doubled one.
            withTimeout(2_000) { assertEquals(1_000L, pauses.receive()) }
            // Background: the watcher is unregistered and nothing keeps watching.
            manager.stop()
            assertEquals(1, network.stopCalls)
            assertTrue(connector.opened.last().closure.isCompleted)
        } finally { manager.stop() }
    }

}