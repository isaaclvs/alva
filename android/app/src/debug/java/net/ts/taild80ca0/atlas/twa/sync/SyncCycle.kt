package net.ts.taild80ca0.atlas.twa.sync

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import net.ts.taild80ca0.atlas.twa.access.HouseAccess
import net.ts.taild80ca0.atlas.twa.data.db.AlvaDatabase
import net.ts.taild80ca0.atlas.twa.data.db.CommandEntity
import net.ts.taild80ca0.atlas.twa.data.list.CommandStatus

/** How a sync cycle ended. Exactly one; the report next to it counts what happened on the way. */
sealed interface SyncOutcome {
    /** Every command that could go was sent and answered, and the server's snapshot is now the confirmed state. */
    data class Synced(val revision: Long) : SyncOutcome

    /** Another cycle is running; this call did nothing. */
    data object AlreadyRunning : SyncOutcome

    /** No credential sync may use (never connected, revoked, storage unavailable). The queue is untouched. */
    data object NotConnected : SyncOutcome

    /** The network did not answer. Nothing was lost; the commands already sent are asked again next time with the same bytes. */
    data class Offline(val cause: TransientCause) : SyncOutcome

    /**
     * Commands were sent and the server never answered ([commandIds]): it may or may not have applied them, so the
     * snapshot is **not** fetched or installed (it could not show what another device did to those items, and
     * replaying the queue over it could bring them back). Nothing is deleted or re-created. A later cycle sends the
     * same bytes again; a command that can never be answered again stays here, visibly.
     */
    data class SnapshotHeld(val commandIds: List<String>) : SyncOutcome

    /** The snapshot was read but must not be installed ([reason]); the device keeps what it had. */
    data class SnapshotNotInstalled(val reason: SnapshotRefusal) : SyncOutcome

    /** The server's answer to the snapshot request was refused or not a snapshot. Nothing of it was kept. */
    data class SnapshotUnusable(val problem: SnapshotProblem?) : SyncOutcome

    /** Local storage failed. The transaction in progress was rolled back. */
    data class StorageFailed(val error: String) : SyncOutcome
}

/**
 * What a cycle did. [acknowledged] commands were consumed by the server in this cycle; [stalled] were left alone
 * because they cannot be sent as they are (not preparable, answered unusably, or behind one that is); [needsAttention]
 * is the number of queued commands the server refused or the queue blocked (`CONFLICT`, `REJECTED`, `BLOCKED`) and
 * that wait for a person: their intent is kept and shown, nothing was discarded.
 */
data class SyncReport(val outcome: SyncOutcome, val acknowledged: Int = 0, val stalled: Int = 0, val needsAttention: Int = 0)

/** Something that runs one sync cycle; [SyncCycle] is the real one. */
fun interface SyncRunner {
    suspend fun run(): SyncReport
}

/**
 * One manual-or-triggered synchronization of this device, in order:
 *
 * 1. **Send the queue**, oldest first, one command at a time ([CommandSender.attempt]) and apply every answer
 *    ([ReceiptApplier.apply]). A command is sent only when what it depends on is consumed (or no longer queued): a
 *    dependent never goes before its dependency. A command the server refused or the queue blocked is not sent
 *    again and does not hold back commands that do not depend on it. Each command is tried **at most once per
 *    cycle**, so a refusal can never loop. The same frozen bytes and the same UUID are used on every retry; a
 *    command whose answer was lost is retried here before anything else is concluded.
 * 2. **Stop if the network or the credential failed.** Offline means nothing more is attempted; a refused
 *    credential also stops (the access layer flags it for reconnecting).
 * 3. **Only if every sent command has an answer** (`unresolvedAttempts` empty), read the snapshot and install it
 *    ([SnapshotInstaller]). Otherwise [SyncOutcome.SnapshotHeld]: the uncertain commands are named and kept. An empty
 *    local list or queue never skips this step: a device that has nothing of its own still has to learn the
 *    household's list from the snapshot.
 *
 * The network is never inside a Room transaction: each attempt commits before its request, each answer is applied
 * in its own transaction afterwards, and the snapshot is fully read before the installer opens its one.
 * Cycles do not overlap (a second call returns [SyncOutcome.AlreadyRunning]). Callers include opening/resuming,
 * the explicit button and the persistent worker; scheduling stays outside this cycle.
 */
class SyncCycle(
    private val db: AlvaDatabase,
    private val sender: CommandSender,
    private val applier: ReceiptApplier,
    private val snapshots: SnapshotTransport,
    private val installer: SnapshotInstaller,
    private val access: HouseAccess,
) : SyncRunner {
    private val running = Mutex()

    override suspend fun run(): SyncReport {
        if (!running.tryLock()) return SyncReport(SyncOutcome.AlreadyRunning)
        try {
            return cycle()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            // Room rolled the transaction back; say what kind of failure, never its message (it may carry data).
            return SyncReport(SyncOutcome.StorageFailed(failure.javaClass.simpleName))
        } finally {
            running.unlock()
        }
    }

    private suspend fun cycle(): SyncReport {
        var acknowledged = 0
        var stalled = 0
        val tried = HashSet<String>()
        while (true) {
            val command = nextToSend(tried) ?: break
            tried += command.id
            when (val attempt = sender.attempt(command.id)) {
                is SendAttempt.NotSent -> when (attempt.reason) {
                    NotSentReason.NO_CREDENTIAL -> return report(SyncOutcome.NotConnected, acknowledged, stalled)
                    NotSentReason.UNKNOWN_COMMAND -> Unit
                    else -> stalled++
                }
                is SendAttempt.Sent -> when (val outcome = attempt.outcome) {
                    is CommandOutcome.Receipt -> when (applier.apply(attempt)) {
                        is ApplyResult.Acknowledged, is ApplyResult.AlreadyAcknowledged -> acknowledged++
                        is ApplyResult.Refused -> stalled++
                        else -> Unit
                    }
                    is CommandOutcome.Transient -> return report(SyncOutcome.Offline(outcome.cause), acknowledged, stalled)
                    CommandOutcome.Unauthorized -> return report(SyncOutcome.NotConnected, acknowledged, stalled)
                    is CommandOutcome.Refused, is CommandOutcome.Invalid -> stalled++
                }
            }
        }

        val unresolved = applier.unresolvedAttempts()
        if (unresolved.isNotEmpty()) return report(SyncOutcome.SnapshotHeld(unresolved), acknowledged, stalled)

        val credential = access.credentialForSync() ?: return report(SyncOutcome.NotConnected, acknowledged, stalled)
        val snapshot = when (val fetched = snapshots.fetch(credential)) {
            is SnapshotOutcome.Received -> fetched.snapshot
            is SnapshotOutcome.Transient -> return report(SyncOutcome.Offline(fetched.cause), acknowledged, stalled)
            SnapshotOutcome.Unauthorized -> {
                access.reportUnauthorized(credential)
                return report(SyncOutcome.NotConnected, acknowledged, stalled)
            }
            is SnapshotOutcome.Refused -> return report(SyncOutcome.SnapshotUnusable(null), acknowledged, stalled)
            is SnapshotOutcome.Invalid -> return report(SyncOutcome.SnapshotUnusable(fetched.problem), acknowledged, stalled)
        }
        return when (val installed = installer.install(snapshot)) {
            is InstallResult.Installed -> report(SyncOutcome.Synced(installed.revision), acknowledged, stalled)
            is InstallResult.Refused -> report(
                if (installed.reason == SnapshotRefusal.UNRESOLVED_ATTEMPTS) SyncOutcome.SnapshotHeld(installed.commandIds)
                else SyncOutcome.SnapshotNotInstalled(installed.reason),
                acknowledged, stalled,
            )
        }
    }

    /**
     * The oldest command that may be sent now and was not tried in this cycle: `PENDING` or `IN_FLIGHT` (a lost
     * answer), whose dependency is consumed or gone from the queue. The queue is read again every time, because
     * applying an answer blocks dependents, reopens them or consumes the command in front.
     */
    private suspend fun nextToSend(tried: Set<String>): CommandEntity? {
        val queue = db.commands().inOrder()
        val byId = queue.associateBy { it.id }
        return queue.firstOrNull { command ->
            command.id !in tried &&
                (command.status == CommandStatus.PENDING.name || command.status == CommandStatus.IN_FLIGHT.name) &&
                command.dependsOn.let { dependency ->
                    dependency == null || byId[dependency]?.status.let { it == null || it == CommandStatus.ACKNOWLEDGED.name }
                }
        }
    }

    private suspend fun report(outcome: SyncOutcome, acknowledged: Int = 0, stalled: Int = 0): SyncReport {
        val attention = CommandStatus.entries.filter { it.stopsDependents }.sumOf { db.commands().countByStatus(it.name) }
        return SyncReport(outcome, acknowledged, stalled, attention)
    }
}
