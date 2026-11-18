package net.ts.taild80ca0.atlas.twa.sync

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull
import net.ts.taild80ca0.atlas.twa.access.AccessCredential
import net.ts.taild80ca0.atlas.twa.access.AccessState
import net.ts.taild80ca0.atlas.twa.access.HouseAccess
import net.ts.taild80ca0.atlas.twa.access.ServerEndpoint

/** Whoever reloads its data when the ActionCable channel says the household changed. */
fun interface InvalidationSink {
    fun requestInvalidation()
}

/** One foreground subscription; stopping it cancels the socket, callbacks and reconnect delay. */
class ForegroundInvalidation(
    private val access: HouseAccess,
    private val endpoint: ServerEndpoint,
    private val sync: InvalidationSink,
    private val scope: CoroutineScope,
    private val connector: CableConnector = OkHttpCableConnector(),
    private val pause: suspend (Long) -> Unit = { delay(it) },
    private val network: NetworkWatcher? = null,
    private val silenceTimeoutMillis: Long = OkHttpCableConnector.SILENCE_TIMEOUT_MILLIS,
) {
    private var job: Job? = null

    /** A network coming back wakes the reconnect wait instead of letting the backoff expire. */
    private val networkWakes = Channel<Unit>(Channel.CONFLATED)

    /** True while the cycle waits to reconnect: only then does a network back mean the wait can be cut short. */
    @Volatile private var recovering = false

    @Synchronized fun start() {
        if (job?.isActive == true) return
        recovering = false
        job = scope.launch {
            access.state.collectLatest { state ->
                if (state == AccessState.Connected) runConnected()
            }
        }
        // Only while the app is resumed; no background service and no polling.
        network?.start(::networkAvailable)
    }

    /** Local commits also wake the foreground cycle, without waiting for the worker. */
    @Synchronized fun localChange() {
        if (job?.isActive == true && access.state.value == AccessState.Connected) sync.requestInvalidation()
    }

    @Synchronized fun stop() {
        network?.stop()
        recovering = false
        job?.cancel()
        job = null
        // A wake from the previous foreground period must not skip or shorten the next backoff.
        while (networkWakes.tryReceive().isSuccess) Unit
    }

    /**
     * A network became available: cut the reconnect wait short and read the list again, so whatever changed while
     * the connection was down does not stay off screen. It never closes a live socket.
     */
    private fun networkAvailable() {
        if (recovering) networkWakes.trySend(Unit)
        if (job?.isActive == true && access.state.value == AccessState.Connected) sync.requestInvalidation()
    }

    private suspend fun runConnected() {
        var backoff = INITIAL_BACKOFF
        while (currentCoroutineContext().isActive) {
            val credential = access.credentialForSync() ?: return
            val session = session(credential)
            when (session.end) {
                CableEnd.UNAUTHORIZED -> {
                    access.reportUnauthorized(credential)
                    // A late refusal of an old token must not stop a replacement.
                    val current = access.credentialForSync()
                    if (current != null && current != credential) continue
                    return
                }
                CableEnd.PERMANENT -> return
                CableEnd.RETRY -> {
                    if (session.subscribed) backoff = INITIAL_BACKOFF
                    // A network that comes back before the timer expires skips the rest of the wait.
                    recovering = true
                    backoff = if (pauseUntilNetworkOrTimeout(backoff)) INITIAL_BACKOFF else (backoff * 2).coerceAtMost(MAX_BACKOFF)
                    recovering = false
                }
            }
        }
    }

    /** True when a network became available while waiting, false when the whole [backoff] elapsed. */
    private suspend fun pauseUntilNetworkOrTimeout(backoff: Long): Boolean = coroutineScope {
        val paused = async { pause(backoff) }
        val woke = select {
            paused.onAwait { false }
            networkWakes.onReceive { true }
        }
        if (woke) paused.cancel()
        woke
    }

    private data class Session(val end: CableEnd, val subscribed: Boolean)
    private suspend fun session(credential: AccessCredential): Session {
        val events = Channel<CableEvent>(Channel.UNLIMITED)
        val socket = connector.open(endpoint, credential) { events.trySend(it) }
        var subscribed = false
        var household = -1L
        var dictionary = -1L
        try {
            while (true) {
                currentCoroutineContext().ensureActive()
                // ActionCable beats every 3 s once subscribed: a longer silence is a half-open socket,
                // never a quiet list. Ending the session reconnects instead of waiting for OkHttp's own timeout.
                val event = if (subscribed) withTimeoutOrNull(silenceTimeoutMillis) { events.receive() } else events.receive()
                if (event == null) return Session(CableEnd.RETRY, subscribed)
                when (event) {
                    CableEvent.Subscribed -> {
                        if (!subscribed) sync.requestInvalidation()
                        subscribed = true
                    }
                    // A ping is the proof of life the watchdog waits for; it carries no list data.
                    CableEvent.Ping -> Unit
                    is CableEvent.Invalidated -> if (subscribed && (event.household > household || event.dictionary > dictionary)) {
                        household = maxOf(household, event.household)
                        dictionary = maxOf(dictionary, event.dictionary)
                        sync.requestInvalidation()
                    }
                    is CableEvent.Ended -> return Session(event.reason, subscribed)
                }
            }
        } finally {
            events.close()
            socket.close()
        }
    }

    companion object {
        const val INITIAL_BACKOFF = 1_000L
        const val MAX_BACKOFF = 30_000L
    }
}
