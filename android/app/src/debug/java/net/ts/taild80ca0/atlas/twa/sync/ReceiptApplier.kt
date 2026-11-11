package net.ts.taild80ca0.atlas.twa.sync

import androidx.room.withTransaction
import net.ts.taild80ca0.atlas.twa.data.db.AlvaDatabase
import net.ts.taild80ca0.atlas.twa.data.db.CommandEntity
import net.ts.taild80ca0.atlas.twa.data.db.CommandReceiptEntity
import net.ts.taild80ca0.atlas.twa.data.db.SyncAliasEntity
import net.ts.taild80ca0.atlas.twa.data.list.AliasClaim
import net.ts.taild80ca0.atlas.twa.data.list.Clock
import net.ts.taild80ca0.atlas.twa.data.list.CommandStatus
import net.ts.taild80ca0.atlas.twa.data.list.ProjectionRebuilder
import net.ts.taild80ca0.atlas.twa.data.list.ReceiptCheck
import net.ts.taild80ca0.atlas.twa.data.list.ReceiptRefusal
import net.ts.taild80ca0.atlas.twa.data.list.ReceiptValidator
import net.ts.taild80ca0.atlas.twa.data.list.WireSubject

/** Why an answer changed nothing although it was well formed. */
enum class StaleReason {
    /** The command was already consumed; a late refusal cannot undo that. */
    ALREADY_ACKNOWLEDGED,

    /** A later attempt exists, so a refusal for an earlier one says nothing about the command now. */
    ATTEMPT_SUPERSEDED,
}

sealed interface ApplyResult {
    /** The sender had no valid receipt to give (nothing came back, or only a failure). Nothing was changed. */
    data class NoReceipt(val outcome: CommandOutcome) : ApplyResult

    /** The command is not in the queue (never was, or already consumed and released). Nothing was changed. */
    data class Gone(val commandId: String) : ApplyResult

    /**
     * The server consumed the command; this is the first time the device recognized it. Its aliases were
     * recorded ([aliases]) and its effect is in the projection. [retained]: the command stays queued as
     * `ACKNOWLEDGED` with its receipt, because no snapshot at or after the receipt's revision has been installed
     * (or a later command still needs it); `confirmed_revision` and the confirmed items did not move.
     */
    data class Acknowledged(
        val commandId: String,
        val aliases: List<AliasClaim>,
        val retained: Boolean,
        val snapshotRequired: Boolean,
    ) : ApplyResult

    /** The command had already been consumed; applying the same answer again changed nothing. */
    data class AlreadyAcknowledged(val commandId: String) : ApplyResult

    /** `conflict`/`rejected`/`blocked`: the intent is kept and [status] recorded; [blockedDependents] (transitive, in queue order) now wait. */
    data class Recorded(
        val commandId: String,
        val status: CommandStatus,
        val error: String,
        val blockedDependents: List<String>,
        val snapshotRequired: Boolean,
    ) : ApplyResult

    /**
     * The server has not recorded the command this one depends on, and that command is still unsent or in flight.
     * Not a conflict: this command stays sendable, and must go after [dependsOn].
     */
    data class WaitingForDependency(val commandId: String, val dependsOn: String) : ApplyResult

    /** A well-formed answer that no longer describes the command. Nothing was changed. */
    data class Stale(val commandId: String, val reason: StaleReason) : ApplyResult

    /** The answer cannot be the answer to this command, or contradicts what the device holds. Nothing was changed. */
    data class Refused(val commandId: String, val reason: ReceiptRefusal, val detail: String) : ApplyResult
}

/**
 * Turns the receipt of one attempt (`SendAttempt.Sent`) into durable local state, in a single Room
 * transaction. It is the only writer of `sync_aliases` and `command_receipts`, and it never touches the
 * confirmed state: the receipt tells name, state, `purchased_at` and `lock_version` of an item but not
 * its category, creation time or normalized name, so it cannot stand for a snapshot.
 *
 * **Consumed** (`applied`, `noop`, `duplicate` of those). The data is validated against the frozen
 * document and the stored subjects ([ReceiptValidator]); only then are the aliases recorded, the command marked
 * `ACKNOWLEDGED` with its receipt, and the projection rebuilt. The command is *kept*: deleting it now
 * would drop the one record of what the server did until a snapshot can say it, and the projection of a created
 * item would lose it. It is released (deleted, receipt with it) when the receipt's revision is not newer than the
 * confirmed revision and no unprepared command still depends on it. A second delivery of the same answer is
 * [ApplyResult.AlreadyAcknowledged], so effects and aliases happen once.
 *
 * **Refused by the server** (`conflict`, `rejected`, `blocked`). Intent, payload and frozen document stay. The status
 * is recorded and the dependents (transitively, by `depends_on`) become `BLOCKED`; commands that do not depend
 * on it are untouched. A `blocked` answer whose dependency is still unsent or in flight only means the order was wrong: the command
 * stays `PENDING` ([ApplyResult.WaitingForDependency]); if the dependency is not in a state that can ever satisfy
 * it, the command is `BLOCKED` and needs reconciliation. Refusals are correlated by attempt number: one for an
 * attempt older than the latest, or for a command already consumed, is [ApplyResult.Stale] and changes nothing.
 *
 * Anything else that does not hold is [ApplyResult.Refused] with nothing written. There is no network here;
 * `CommandSender`'s `Sent` is applied after it returns, never inside its transaction.
 */
class ReceiptApplier(
    private val db: AlvaDatabase,
    private val clock: Clock = Clock { System.currentTimeMillis() },
    private val rebuilder: ProjectionRebuilder = ProjectionRebuilder(db),
) {

    suspend fun apply(sent: SendAttempt.Sent): ApplyResult =
        when (val outcome = sent.outcome) {
            is CommandOutcome.Receipt -> apply(sent.commandId, sent.attempt, outcome.receipt)
            else -> ApplyResult.NoReceipt(outcome)
        }

    suspend fun apply(commandId: String, attempt: Int, receipt: CommandReceipt): ApplyResult =
        db.withTransaction {
            val command = db.commands().find(commandId) ?: return@withTransaction ApplyResult.Gone(commandId)
            if (!receipt.commandId.equals(commandId, ignoreCase = true)) {
                return@withTransaction ApplyResult.Refused(commandId, ReceiptRefusal.ITEM_MISMATCH, "receipt of another command")
            }
            if (attempt < 1 || attempt > command.attempts) {
                return@withTransaction ApplyResult.Refused(commandId, ReceiptRefusal.IMPOSSIBLE_ATTEMPT, "attempt $attempt of ${command.attempts}")
            }
            if (receipt.status.recordedByServer) consumed(command, attempt, receipt) else refused(command, attempt, receipt)
        }

    /**
     * The commands that were sent and have no recorded answer, in queue order. For each of them the server may
     * have applied the command, so **a snapshot must not be installed and the queue must not be replayed over it
     * until each was resolved**: send the same document again (the answer is `duplicate`, or the real refusal) and
     * [apply] it. A snapshot read after another device removed or archived the item would otherwise
     * not show it, and replaying the old command over it would bring it back. An empty list is the precondition
     * of installing a snapshot; nothing is guessed from a name that happens to match.
     */
    suspend fun unresolvedAttempts(): List<String> = db.commands().unresolvedAttemptIds()

    // ---- consumed ----------------------------------------------------------------------------

    private suspend fun consumed(command: CommandEntity, attempt: Int, receipt: CommandReceipt): ApplyResult {
        if (command.status == CommandStatus.ACKNOWLEDGED.name) return ApplyResult.AlreadyAcknowledged(command.id)
        fun refuse(reason: ReceiptRefusal, detail: String) = ApplyResult.Refused(command.id, reason, detail)

        val revision = receipt.revision ?: return refuse(ReceiptRefusal.IMPOSSIBLE_REVISION, "no revision")
        val applied = receipt.status == ReceiptStatus.APPLIED ||
            (receipt.status == ReceiptStatus.DUPLICATE && receipt.originalStatus == ReceiptStatus.APPLIED)
        // A command that changed the list moved the house revision, which starts at 0.
        if (applied && revision < 1) return refuse(ReceiptRefusal.IMPOSSIBLE_REVISION, "an applied command at revision $revision")
        // What a command depends on happened first on the server, so its revision cannot be higher.
        command.dependsOn?.let { dependencyId ->
            val dependencyReceipt = db.receipts().find(dependencyId)
            if (dependencyReceipt != null && dependencyReceipt.revision > revision) {
                return refuse(ReceiptRefusal.IMPOSSIBLE_REVISION, "revision $revision is before its dependency's ${dependencyReceipt.revision}")
            }
        }
        val document = command.wireDocument ?: return refuse(ReceiptRefusal.NOT_FROZEN, "no frozen document to check against")
        val data = receipt.dataJson ?: return refuse(ReceiptRefusal.MALFORMED_DATA, "no data")
        val subjects = db.commands().subjectsOf(command.id)
        val known = db.aliases().all().associate { it.aliasPublicId to it.canonicalPublicId }

        val effect = when (
            val check = ReceiptValidator.check(
                command.type, document, subjects.map { WireSubject(it.subjectPublicId, it.role, it.ordinal) }, applied, data,
            ) { known[it] }
        ) {
            is ReceiptCheck.Invalid -> return refuse(check.reason, check.detail)
            is ReceiptCheck.Valid -> check.effect
        }

        // Against the rest of the device's state: an id is either a live server item or an alias, never both,
        // and nothing may already point at an id that is about to become an alias.
        val confirmedIds = db.confirmedItems().all().mapTo(HashSet()) { it.publicId }
        for (claim in effect.aliases) {
            if (claim.alias in confirmedIds) return refuse(ReceiptRefusal.ALIAS_CONFLICT, "the alias is a confirmed item")
            if (db.aliases().pointingAt(listOf(claim.alias)).isNotEmpty()) return refuse(ReceiptRefusal.ALIAS_CONFLICT, "other aliases point at the alias")
        }

        effect.aliases.filter { it.alias !in known }.forEach { claim ->
            db.aliases().insert(SyncAliasEntity(claim.alias, claim.canonical, command.id, revision))
        }
        val previous = CommandStatus.valueOf(command.status)
        db.commands().setStatus(command.id, CommandStatus.ACKNOWLEDGED.name, null)
        db.receipts().insert(
            CommandReceiptEntity(
                commandId = command.id,
                outcome = if (applied) CommandReceiptEntity.APPLIED else CommandReceiptEntity.NOOP,
                viaDuplicate = receipt.status == ReceiptStatus.DUPLICATE,
                revision = revision,
                houseRevision = receipt.houseRevision,
                attempt = attempt,
                dataJson = data,
                receivedAt = clock.nowMillis(),
            ),
        )
        if (previous.stopsDependents) reopenDependents(command.id)

        val covered = revision <= (db.syncState().get()?.confirmedRevision ?: 0L)
        val needed = db.commands().dependentsOf(command.id).any { it.wireDocument == null }
        val retained = !(covered && !needed)
        if (!retained) db.commands().delete(command.id)

        rebuilder.rebuild()
        return ApplyResult.Acknowledged(command.id, effect.aliases, retained, receipt.snapshotRequired)
    }

    /** Dependents blocked because this chain stopped, once the chain moves again: back to `PENDING`. */
    private suspend fun reopenDependents(rootId: String) {
        val queue = ArrayDeque(listOf(rootId))
        while (queue.isNotEmpty()) {
            val parent = db.commands().find(queue.removeFirst()) ?: continue
            if (CommandStatus.valueOf(parent.status).stopsDependents) continue
            for (dependent in db.commands().dependentsOf(parent.id)) {
                if (dependent.status == CommandStatus.BLOCKED.name && dependent.lastErrorCode == DEPENDENCY_NOT_APPLIED) {
                    db.commands().setStatus(dependent.id, CommandStatus.PENDING.name, null)
                    queue.addLast(dependent.id)
                }
            }
        }
    }

    // ---- refused by the server ------------------------------------------------------------------

    private suspend fun refused(command: CommandEntity, attempt: Int, receipt: CommandReceipt): ApplyResult {
        if (command.status == CommandStatus.ACKNOWLEDGED.name) return ApplyResult.Stale(command.id, StaleReason.ALREADY_ACKNOWLEDGED)
        if (attempt < command.attempts) return ApplyResult.Stale(command.id, StaleReason.ATTEMPT_SUPERSEDED)
        val error = receipt.error ?: return ApplyResult.Refused(command.id, ReceiptRefusal.MALFORMED_DATA, "no error code")

        if (receipt.status == ReceiptStatus.BLOCKED) {
            val dependency = command.dependsOn?.let { db.commands().find(it) }
            val dependencyStatus = dependency?.let { CommandStatus.valueOf(it.status) }
            if (dependency != null && (dependencyStatus == CommandStatus.PENDING || dependencyStatus == CommandStatus.IN_FLIGHT)) {
                db.commands().setStatus(command.id, CommandStatus.PENDING.name, DEPENDENCY_NOT_APPLIED)
                return ApplyResult.WaitingForDependency(command.id, dependency.id)
            }
        }
        val status = when (receipt.status) {
            ReceiptStatus.CONFLICT -> CommandStatus.CONFLICT
            ReceiptStatus.REJECTED -> CommandStatus.REJECTED
            else -> CommandStatus.BLOCKED
        }
        db.commands().setStatus(command.id, status.name, error)
        val blocked = blockDependents(command.id)
        rebuilder.rebuild()
        return ApplyResult.Recorded(command.id, status, error, blocked, receipt.snapshotRequired)
    }

    /** Every command that depends on [rootId], directly or through others, that could still be sent. Independent commands are left alone. */
    private suspend fun blockDependents(rootId: String): List<String> {
        val blocked = ArrayList<String>()
        val seen = HashSet<String>().apply { add(rootId) }
        val queue = ArrayDeque(listOf(rootId))
        while (queue.isNotEmpty()) {
            for (dependent in db.commands().dependentsOf(queue.removeFirst())) {
                if (!seen.add(dependent.id)) continue
                // A consumed dependent cannot exist (the server needs its dependency recorded first); leave it be.
                if (dependent.status == CommandStatus.PENDING.name || dependent.status == CommandStatus.IN_FLIGHT.name) {
                    db.commands().setStatus(dependent.id, CommandStatus.BLOCKED.name, DEPENDENCY_NOT_APPLIED)
                    blocked += dependent.id
                }
                queue.addLast(dependent.id)
            }
        }
        return blocked
    }

    private companion object {
        /** The contract's code for "the command I depend on has no record", reused for a local block. */
        const val DEPENDENCY_NOT_APPLIED = "dependency_not_applied"
    }
}
