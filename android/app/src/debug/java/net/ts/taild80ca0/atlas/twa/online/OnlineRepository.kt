package net.ts.taild80ca0.atlas.twa.online

import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.ts.taild80ca0.atlas.twa.data.list.IdGenerator
import net.ts.taild80ca0.atlas.twa.sync.CommandOutcome
import net.ts.taild80ca0.atlas.twa.sync.CommandReceipt
import net.ts.taild80ca0.atlas.twa.sync.CommandTransport
import net.ts.taild80ca0.atlas.twa.sync.InvalidationSink
import net.ts.taild80ca0.atlas.twa.sync.ReceiptStatus
import net.ts.taild80ca0.atlas.twa.sync.SnapshotOutcome
import net.ts.taild80ca0.atlas.twa.sync.SnapshotTransport
import net.ts.taild80ca0.atlas.twa.sync.TransientCause

/**
 * The native client of the server, as the PWA is: the server is the source of truth, so there is no local copy
 * to reconcile, no queue and nothing kept for later.
 *
 * - [state] is the last snapshot the server sent ([refresh] reads `GET /api/v1/snapshot`). It is read when the
 *   app opens or resumes, when the ActionCable channel says the household changed ([requestInvalidation]) and
 *   after every answer to an action.
 * - [execute] gives an [OnlineAction] a new command UUID, builds its one document from the last snapshot and
 *   sends it with `POST /api/v1/commands`. Only a failure to reach the server (no network, timeout) is
 *   retried, a few times, with **the same document**, so a lost answer can never apply the action twice: the
 *   server answers `duplicate` to a UUID it already holds. Whatever the server answers, the list is read again.
 * - Nothing is stored when the server cannot be reached: the result tells the screen, which keeps what was typed.
 */
class OnlineRepository(
    private val access: OnlineAccess,
    private val snapshots: SnapshotTransport,
    private val commands: CommandTransport,
    private val scope: CoroutineScope,
    private val ids: IdGenerator = IdGenerator { UUID.randomUUID().toString() },
    /** Pauses between the attempts of one action; its size is the number of retries. */
    private val retryDelaysMillis: List<Long> = listOf(400L, 1_200L),
    private val pause: suspend (Long) -> Unit = { delay(it) },
) : InvalidationSink {
    private val _state = MutableStateFlow<OnlineState>(OnlineState.Loading)
    val state: StateFlow<OnlineState> = _state.asStateFlow()

    private val loadLock = Mutex()
    private val tickets = AtomicLong()
    private var coveredTickets = 0L

    /**
     * Reads the snapshot again. Calls that arrive while a read runs share the next one: it starts after
     * they asked, so it is never older than their request, and one burst of signals is one read.
     */
    suspend fun refresh() {
        val mine = tickets.incrementAndGet()
        loadLock.withLock {
            if (coveredTickets >= mine) return
            val coveredByThisRead = tickets.get()
            if (_state.value is OnlineState.Failed) _state.value = OnlineState.Loading
            load()
            coveredTickets = coveredByThisRead
        }
    }

    /** The ActionCable signal: something changed on the server. */
    override fun requestInvalidation() {
        scope.launch { refresh() }
    }

    /**
     * Runs [action] on the server. See the class comment for the retry and reload rules. When no list has
     * been read yet, it is read first: preconditions come from the snapshot.
     */
    suspend fun execute(action: OnlineAction): ActionResult {
        var ready = state.value as? OnlineState.Ready
        if (ready == null) {
            refresh()
            ready = state.value as? OnlineState.Ready
                ?: return (state.value as? OnlineState.Failed)?.problem.asResult()
        }
        val commandId = ids.newId()
        val document = when (val plan = action.plan(PlanContext(commandId, ready.snapshot) { ids.newId() })) {
            is ActionPlan.Invalid -> return ActionResult.Invalid(plan.reason)
            is ActionPlan.Send -> plan.document
        }
        return send(commandId, document)
    }

    private suspend fun send(commandId: String, document: String): ActionResult {
        var attempt = 0
        while (true) {
            val credential = access.credential() ?: return ActionResult.NoAccess
            when (val outcome = commands.send(credential, commandId, document)) {
                is CommandOutcome.Receipt -> return answered(interpret(outcome.receipt))
                is CommandOutcome.Transient -> {
                    if (outcome.cause.isNetworkFailure && attempt < retryDelaysMillis.size) {
                        pause(retryDelaysMillis[attempt++])
                        continue
                    }
                    // Nothing came back from the network: reloading would only wait for the same failure.
                    if (outcome.cause.isNetworkFailure) return ActionResult.Offline
                    return answered(ActionResult.ServerUnavailable)
                }
                CommandOutcome.Unauthorized -> {
                    access.reportUnauthorized(credential)
                    return ActionResult.NoAccess
                }
                is CommandOutcome.Refused -> return answered(ActionResult.Refused(outcome.code ?: UNKNOWN_CODE))
                is CommandOutcome.Invalid -> return answered(ActionResult.ServerUnavailable)
            }
        }
    }

    /** Any answer from the server, good or bad, is followed by a fresh read of the list. */
    private suspend fun answered(result: ActionResult): ActionResult {
        refresh()
        return result
    }

    private fun interpret(receipt: CommandReceipt): ActionResult = when (receipt.status) {
        ReceiptStatus.APPLIED, ReceiptStatus.NOOP, ReceiptStatus.DUPLICATE -> ActionResult.Success(receipt)
        ReceiptStatus.CONFLICT, ReceiptStatus.REJECTED, ReceiptStatus.BLOCKED -> ActionResult.Refused(receipt.error ?: UNKNOWN_CODE)
    }

    private suspend fun load() {
        val credential = access.credential() ?: return fail(OnlineProblem.NO_ACCESS)
        when (val outcome = snapshots.fetch(credential)) {
            is SnapshotOutcome.Received -> _state.value = OnlineState.Ready(outcome.snapshot)
            is SnapshotOutcome.Transient ->
                fail(if (outcome.cause.isNetworkFailure) OnlineProblem.OFFLINE else OnlineProblem.SERVER)
            SnapshotOutcome.Unauthorized -> {
                access.reportUnauthorized(credential)
                fail(OnlineProblem.NO_ACCESS)
            }
            is SnapshotOutcome.Refused, is SnapshotOutcome.Invalid -> fail(OnlineProblem.UNEXPECTED)
        }
    }

    /** Keeps the list already on screen (flagged as possibly stale); without one, the read has failed. */
    private fun fail(problem: OnlineProblem) {
        val current = _state.value
        _state.value = if (current is OnlineState.Ready) current.copy(problem = problem) else OnlineState.Failed(problem)
    }

    private fun OnlineProblem?.asResult(): ActionResult = when (this) {
        OnlineProblem.OFFLINE -> ActionResult.Offline
        OnlineProblem.NO_ACCESS -> ActionResult.NoAccess
        else -> ActionResult.ServerUnavailable
    }

    private val TransientCause.isNetworkFailure: Boolean get() = this == TransientCause.NETWORK || this == TransientCause.TIMEOUT

    companion object {
        /** Used when the server refuses without a code the contract knows. */
        const val UNKNOWN_CODE = "unknown"
    }
}
