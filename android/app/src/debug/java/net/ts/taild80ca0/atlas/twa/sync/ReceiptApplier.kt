package net.ts.taild80ca0.atlas.twa.sync

import androidx.room.withTransaction
import net.ts.taild80ca0.atlas.twa.data.db.AlvaDatabase
import net.ts.taild80ca0.atlas.twa.data.db.CommandEntity
import net.ts.taild80ca0.atlas.twa.data.db.CommandReceiptEntity
import net.ts.taild80ca0.atlas.twa.data.db.SubjectRole
import net.ts.taild80ca0.atlas.twa.data.db.SyncAliasEntity
import net.ts.taild80ca0.atlas.twa.data.list.AliasClaim
import net.ts.taild80ca0.atlas.twa.data.list.Clock
import net.ts.taild80ca0.atlas.twa.data.list.CommandStatus
import net.ts.taild80ca0.atlas.twa.data.list.ItemOutcome
import net.ts.taild80ca0.atlas.twa.data.list.ProjectionRebuilder
import net.ts.taild80ca0.atlas.twa.data.list.ReceiptCheck
import net.ts.taild80ca0.atlas.twa.data.list.ReceiptItem
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
     * [held]: later commands that were about a purchase this one meant to revive and that the server answered
     * with another item (see [ReceiptApplier]); they are `BLOCKED` with their intent and document intact, and
     * so is whatever depends on them.
     */
    data class Acknowledged(
        val commandId: String,
        val aliases: List<AliasClaim>,
        val retained: Boolean,
        val snapshotRequired: Boolean,
        val held: List<String> = emptyList(),
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
 * **A revive the server answered with another item.** Typing a purchased name again sends an `add_items` that revives
 * that purchase (S). The server looks for a *pending* item of the name first, so if one appeared meanwhile (T) the
 * answer is a `duplicate` of T and S stays purchased. A later command about S (a mark the user made on the
 * "revived" row) was frozen against S and would now act on the old purchase while the user sees T, silently losing
 * what they meant. Such commands, and what depends on them, become `BLOCKED` (`revive_redirected`) and need
 * reconciliation; their payload and frozen document stay as they are. S and T are two different real items: no alias
 * S→T is ever recorded, and independent commands are untouched.
 *
 * **Answers and attempts.** Whether the server has *answered an attempt* is recorded apart from the queue's status
 * (`commands.answered_attempt`): only a refusal that proves the command had no effect closes its attempt, and only
 * for the attempt it answers. Blocking a dependent because its dependency failed is the device's decision, not an
 * answer, so a dependent that was in flight stays unresolved. A rejection `idempotency_conflict` closes nothing
 * either: the server gives it for a different content *and* for the same content from another lineage (the secret
 * of the old one was lost), so it does not tell whether the old effect happened.
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
            is CommandOutcome.Refused -> requestRefused(sent.commandId, sent.attempt, outcome)
            else -> ApplyResult.NoReceipt(outcome)
        }

    /**
     * The request as a whole was refused (a 4xx other than 401/403/429, or the local `request_too_large` that never
     * left the device). Rails answers it only before running any command (`malformed_command`), and other 4xx
     * come from in front of Rails, so the attempt had **no effect**: it is answered, the command becomes `REJECTED`
     * with the code ([REQUEST_REFUSED] when the body had none) and its dependents are blocked, exactly like a
     * rejection inside a batch. Resending the same bytes would only get the same refusal and hold the snapshot
     * forever; the intent stays queued for an explicit decision.
     */
    private suspend fun requestRefused(commandId: String, attempt: Int, outcome: CommandOutcome.Refused): ApplyResult =
        db.withTransaction {
            val command = db.commands().find(commandId) ?: return@withTransaction ApplyResult.Gone(commandId)
            if (attempt < 1 || attempt > command.attempts) {
                return@withTransaction ApplyResult.Refused(commandId, ReceiptRefusal.IMPOSSIBLE_ATTEMPT, "attempt $attempt of ${command.attempts}")
            }
            refused(command, attempt, ReceiptStatus.REJECTED, outcome.code ?: REQUEST_REFUSED, snapshotRequired = false)
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
            if (receipt.status.recordedByServer) {
                consumed(command, attempt, receipt)
            } else {
                val error = receipt.error ?: return@withTransaction ApplyResult.Refused(command.id, ReceiptRefusal.MALFORMED_DATA, "no error code")
                refused(command, attempt, receipt.status, error, receipt.snapshotRequired)
            }
        }

    /**
     * The commands that were sent and have no recorded answer, in queue order. For each of them the server may
     * have applied the command, so **a snapshot must not be installed and the queue must not be replayed over it
     * until each was resolved**: send the same document again (the answer is `duplicate`, or the real refusal) and
     * [apply] it. What counts is whether the server answered the attempt, not the command's status: a dependent
     * blocked or reopened by the queue is still listed, and an `idempotency_conflict` does not take a command out. A snapshot read after another device removed or archived the item would otherwise
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
        val held = holdWhatWasAboutARedirectedRevive(command, effect.items, subjects, known)

        val covered = revision <= (db.syncState().get()?.confirmedRevision ?: 0L)
        val needed = db.commands().dependentsOf(command.id).any { it.wireDocument == null }
        val retained = !(covered && !needed)
        if (!retained) db.commands().delete(command.id)

        rebuilder.rebuild()
        return ApplyResult.Acknowledged(command.id, effect.aliases, retained, receipt.snapshotRequired, held)
    }

    /**
     * A revive of purchase S that the server answered with a `duplicate` of another item: the commands queued after
     * it that touch S (or an id that is an alias of S) cannot be sent as they are. Returns the commands held, in
     * queue order, dependents included. No alias is recorded: S and the item the server kept are different items.
     */
    private suspend fun holdWhatWasAboutARedirectedRevive(
        command: CommandEntity,
        items: List<ReceiptItem>,
        subjects: List<net.ts.taild80ca0.atlas.twa.data.db.CommandSubjectEntity>,
        known: Map<String, String>,
    ): List<String> {
        val purchases = items.filter { it.outcome == ItemOutcome.DUPLICATE }.mapNotNull { item ->
            val subject = subjects.firstOrNull { it.ordinal == item.ordinal && it.role == SubjectRole.TARGETS } ?: return@mapNotNull null
            val purchase = subject.subjectPublicId.lowercase()
            subject.subjectPublicId.takeIf { item.canonicalPublicId != (known[purchase] ?: purchase) }
        }
        if (purchases.isEmpty()) return emptyList()

        val cited = (purchases + db.aliases().pointingAt(purchases.map { it.lowercase() }).map { it.aliasPublicId }).distinct()
        val held = ArrayList<String>()
        for (later in db.commands().touchingAfter(cited, command.sequence)) {
            if (later.status != CommandStatus.PENDING.name && later.status != CommandStatus.IN_FLIGHT.name) continue
            db.commands().setStatus(later.id, CommandStatus.BLOCKED.name, REVIVE_REDIRECTED)
            held += later.id
            held += blockDependents(later.id)
        }
        return held.distinct()
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

    private suspend fun refused(
        command: CommandEntity,
        attempt: Int,
        answer: ReceiptStatus,
        error: String,
        snapshotRequired: Boolean,
    ): ApplyResult {
        if (command.status == CommandStatus.ACKNOWLEDGED.name) return ApplyResult.Stale(command.id, StaleReason.ALREADY_ACKNOWLEDGED)
        if (attempt < command.attempts) return ApplyResult.Stale(command.id, StaleReason.ATTEMPT_SUPERSEDED)
        // The server spoke about this attempt: it had no effect, so the attempt is no longer in doubt. Except for an
        // idempotency_conflict, which says nothing about what an older lineage of this device already made it do.
        if (error != IDEMPOTENCY_CONFLICT) db.commands().recordAnswer(command.id, attempt)

        if (answer == ReceiptStatus.BLOCKED) {
            val dependency = command.dependsOn?.let { db.commands().find(it) }
            val dependencyStatus = dependency?.let { CommandStatus.valueOf(it.status) }
            if (dependency != null && (dependencyStatus == CommandStatus.PENDING || dependencyStatus == CommandStatus.IN_FLIGHT)) {
                db.commands().setStatus(command.id, CommandStatus.PENDING.name, DEPENDENCY_NOT_APPLIED)
                return ApplyResult.WaitingForDependency(command.id, dependency.id)
            }
        }
        val status = when (answer) {
            ReceiptStatus.CONFLICT -> CommandStatus.CONFLICT
            ReceiptStatus.REJECTED -> CommandStatus.REJECTED
            else -> CommandStatus.BLOCKED
        }
        db.commands().setStatus(command.id, status.name, error)
        val blocked = blockDependents(command.id)
        rebuilder.rebuild()
        return ApplyResult.Recorded(command.id, status, error, blocked, snapshotRequired)
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

    companion object {
        /** The contract's code for "the command I depend on has no record", reused for a local block. */
        const val DEPENDENCY_NOT_APPLIED = "dependency_not_applied"

        /** Local code: a command about a purchase the server did not revive; only reconciliation moves it. */
        const val REVIVE_REDIRECTED = "revive_redirected"

        /** Local code for a request refused as a whole whose body carried no contract code. */
        const val REQUEST_REFUSED = "request_refused"

        /** The server's code for the same id with other content or from another lineage. */
        const val IDEMPOTENCY_CONFLICT = "idempotency_conflict"
    }
}
