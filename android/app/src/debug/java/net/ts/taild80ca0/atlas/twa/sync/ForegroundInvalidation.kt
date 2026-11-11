package net.ts.taild80ca0.atlas.twa.sync

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import net.ts.taild80ca0.atlas.twa.access.AccessCredential
import net.ts.taild80ca0.atlas.twa.access.AccessState
import net.ts.taild80ca0.atlas.twa.access.HouseAccess
import net.ts.taild80ca0.atlas.twa.access.ServerEndpoint

/** One foreground subscription; stopping it cancels the socket, callbacks and reconnect delay. */
class ForegroundInvalidation(
    private val access: HouseAccess,
    private val endpoint: ServerEndpoint,
    private val sync: SyncController,
    private val scope: CoroutineScope,
    private val connector: CableConnector = OkHttpCableConnector(),
    private val pause: suspend (Long) -> Unit = { delay(it) },
) {
    private var job: Job? = null

    @Synchronized fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            access.state.collectLatest { state ->
                if (state == AccessState.Connected) runConnected()
            }
        }
    }

    /** Local commits also wake the foreground cycle, without waiting for the worker. */
    @Synchronized fun localChange() {
        if (job?.isActive == true && access.state.value == AccessState.Connected) sync.requestInvalidation()
    }

    @Synchronized fun stop() {
        job?.cancel()
        job = null
    }

    private suspend fun runConnected() {
        var backoff = 1_000L
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
                    if (session.subscribed) backoff = 1_000L
                    pause(backoff)
                    backoff = (backoff * 2).coerceAtMost(30_000L)
                }
            }
        }
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
                when (val event = events.receive()) {
                    CableEvent.Subscribed -> {
                        if (!subscribed) sync.requestInvalidation()
                        subscribed = true
                    }
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
}
