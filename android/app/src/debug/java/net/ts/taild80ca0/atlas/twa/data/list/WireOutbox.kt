package net.ts.taild80ca0.atlas.twa.data.list

import androidx.room.withTransaction
import net.ts.taild80ca0.atlas.twa.data.db.AlvaDatabase

/** Why [WireOutbox] said no. The queue and the local intent are untouched in every case. */
enum class WireRefusal {
    /** No such command in the queue (it may have been confirmed and removed). */
    UNKNOWN_COMMAND,

    /** The candidate document is not a valid wire form of this command. */
    INVALID_DOCUMENT,

    /**
     * The command was already attempted before its document was frozen (a row from before
     * this feature). The bytes it sent then were not kept, so no replacement can be
     * trusted to be the same request; it is not frozen and not attempted until a later
     * task decides how to reconcile it.
     */
    LEGACY_ALREADY_ATTEMPTED,

    /** Nothing was frozen for this command yet, so there is nothing to send. */
    NOT_FROZEN,

    /** The command's status (blocked, conflict, rejected) does not allow another attempt. */
    NOT_SENDABLE,
}

sealed interface FreezeResult {
    /** [document] is now (or already was) the command's frozen document; [created] tells which. */
    data class Frozen(val commandId: String, val document: String, val created: Boolean) : FreezeResult

    data class Refused(val reason: WireRefusal, val detail: String) : FreezeResult
}

sealed interface AttemptResult {
    /** The attempt is recorded. [document] is the exact frozen request; send it and nothing else. */
    data class Started(val commandId: String, val document: String, val attempt: Int) : AttemptResult

    data class Refused(val reason: WireRefusal, val detail: String) : AttemptResult
}

/**
 * Durable outgoing request of each queued command, apart from the local intent.
 *
 * `commands.payload`/`preconditions` are what the user meant; the projection replays
 * them and they may be revisited when the app learns more. `commands.wire_document` is
 * what the server is asked: prepared once, kept byte for byte until the server confirms
 * the command (which deletes the row), and handed back unchanged on every retry. A retry
 * after a lost response must repeat the same bytes, because the server's idempotency check
 * compares the command's content, not only its UUID.
 *
 * Both operations are single Room transactions and join the caller's transaction if there
 * is one. SQL errors propagate (the transaction is rolled back); refusals are values.
 * There is no network here: a future sender calls [beginAttempt], and only when it returns
 * [AttemptResult.Started] puts the returned document on the wire.
 */
class WireOutbox(private val db: AlvaDatabase) {

    /**
     * Freezes [candidate] as the wire document of [commandId] if none exists yet. Once
     * frozen, later candidates are ignored (valid or not) and the stored document is
     * returned, so the caller can always rebuild-and-freeze without risk of rewriting.
     */
    suspend fun freeze(commandId: String, candidate: String): FreezeResult =
        db.withTransaction {
            val command = db.commands().find(commandId)
                ?: return@withTransaction FreezeResult.Refused(WireRefusal.UNKNOWN_COMMAND, "no command $commandId")
            command.wireDocument?.let { return@withTransaction FreezeResult.Frozen(commandId, it, created = false) }
            if (command.attempts > 0) {
                return@withTransaction FreezeResult.Refused(
                    WireRefusal.LEGACY_ALREADY_ATTEMPTED,
                    "command $commandId was attempted ${command.attempts} time(s) before its document was kept",
                )
            }
            WireDocument.problemWith(candidate, command.id, command.type, command.dependsOn)?.let {
                return@withTransaction FreezeResult.Refused(WireRefusal.INVALID_DOCUMENT, it)
            }
            if (db.commands().freezeWireDocument(commandId, candidate) == 1) {
                return@withTransaction FreezeResult.Frozen(commandId, candidate, created = true)
            }
            // Lost a race inside the guard: whoever won decides.
            val winner = db.commands().find(commandId)?.wireDocument
                ?: return@withTransaction FreezeResult.Refused(WireRefusal.UNKNOWN_COMMAND, "no command $commandId")
            FreezeResult.Frozen(commandId, winner, created = false)
        }

    /**
     * Records one attempt (`attempts + 1`, status [CommandStatus.IN_FLIGHT]) and returns the
     * frozen document, in the same transaction, so a network call can never be made for an
     * attempt that was not counted. A command left in flight by a dead process may be
     * attempted again; blocked, conflicting and rejected ones may not.
     */
    suspend fun beginAttempt(commandId: String): AttemptResult =
        db.withTransaction {
            val command = db.commands().find(commandId)
                ?: return@withTransaction AttemptResult.Refused(WireRefusal.UNKNOWN_COMMAND, "no command $commandId")
            val document = command.wireDocument
                ?: return@withTransaction AttemptResult.Refused(
                    if (command.attempts > 0) WireRefusal.LEGACY_ALREADY_ATTEMPTED else WireRefusal.NOT_FROZEN,
                    if (command.attempts > 0) {
                        "command $commandId was attempted ${command.attempts} time(s) before its document was kept"
                    } else {
                        "command $commandId has no frozen document"
                    },
                )
            val sendable = SENDABLE.map { it.name }
            if (db.commands().recordAttempt(commandId, CommandStatus.IN_FLIGHT.name, sendable) != 1) {
                return@withTransaction AttemptResult.Refused(WireRefusal.NOT_SENDABLE, "command $commandId is ${command.status}")
            }
            AttemptResult.Started(commandId, document, db.commands().find(commandId)!!.attempts)
        }

    /** The frozen document of [commandId], or null if none was frozen (or it does not exist). */
    suspend fun frozenDocument(commandId: String): String? = db.commands().find(commandId)?.wireDocument

    private companion object {
        val SENDABLE = listOf(CommandStatus.PENDING, CommandStatus.IN_FLIGHT)
    }
}
