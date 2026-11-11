package net.ts.taild80ca0.atlas.twa.sync

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.ts.taild80ca0.atlas.twa.access.HouseAccess
import net.ts.taild80ca0.atlas.twa.data.list.AttemptResult
import net.ts.taild80ca0.atlas.twa.data.list.PreparationRefusal
import net.ts.taild80ca0.atlas.twa.data.list.PreparationResult
import net.ts.taild80ca0.atlas.twa.data.list.WireOutbox
import net.ts.taild80ca0.atlas.twa.data.list.WirePreparer
import net.ts.taild80ca0.atlas.twa.data.list.WireRefusal

/** Why a command was not sent. In every case nothing reached the network and no attempt was counted. */
enum class NotSentReason {
    /** No such command in the queue (it may have been confirmed and removed). */
    UNKNOWN_COMMAND,

    /** The command is blocked, in conflict or rejected: the user or the reconciliation owns it. */
    NOT_SENDABLE,

    /** Attempted before its document was kept; the bytes it sent then are unknown (see [WirePreparer]). */
    LEGACY_ALREADY_ATTEMPTED,

    /** The intent cannot be turned into a faithful document yet; [SendAttempt.NotSent.preparation] says why. */
    NOT_PREPARABLE,

    /** The device has no credential sync may use (not connected, revoked, storage unavailable). */
    NO_CREDENTIAL,

    /** The frozen document is larger than any command should be; it stays queued untouched. */
    DOCUMENT_TOO_LARGE,
}

sealed interface SendAttempt {
    /**
     * Nothing was sent and no attempt was counted. Preparation may have frozen the document;
     * the local intent and projection are unchanged.
     */
    data class NotSent(
        val reason: NotSentReason,
        val detail: String,
        val preparation: PreparationRefusal? = null,
    ) : SendAttempt

    /**
     * Attempt number [attempt] was recorded (durably) and one request was made.
     * [outcome] says what came back; the command is exactly as it was, apart from the counter
     * and `IN_FLIGHT` that [WireOutbox.beginAttempt] wrote before the request.
     */
    data class Sent(val commandId: String, val attempt: Int, val outcome: CommandOutcome) : SendAttempt
}

/**
 * One safe attempt to send one queued command. This is the low-level step of the
 * future sender, not the sender: it neither orders the queue nor retries, and it applies
 * nothing.
 *
 * `attempt(commandId)` does, in order:
 * 1. [WirePreparer.prepare]: reads the intent and freezes the document, or returns the one already
 *    frozen byte for byte. A refusal ends the call: nothing is sent.
 * 2. [HouseAccess.credentialForSync]. Without one, nothing is sent.
 * 3. [WireOutbox.beginAttempt]: its own Room transaction counts the attempt and marks the
 *    command in flight, and it has **committed** when it returns. Only then is the network
 *    touched. So an attempt that reached the wire is always counted, and no Room transaction
 *    is open while waiting for the server. **Never call [attempt] inside a Room transaction.**
 * 4. [CommandTransport.send] with the frozen document: one request.
 * 5. On a refused credential, [HouseAccess.reportUnauthorized] with the credential this attempt
 *    actually used, so a late answer cannot flag one the user has since reconnected.
 *
 * Whatever comes back, the queue keeps the command and its document: a receipt is returned to
 * the caller as a value, not applied. If the process dies before a receipt is applied, the next
 * attempt repeats the same bytes and the server answers `duplicate`. Attempts are serialized in
 * this object, so one process never has two requests in flight from it.
 */
class CommandSender(
    private val preparer: WirePreparer,
    private val outbox: WireOutbox,
    private val access: HouseAccess,
    private val transport: CommandTransport,
) {
    private val oneAtATime = Mutex()

    suspend fun attempt(commandId: String): SendAttempt = oneAtATime.withLock { attemptLocked(commandId) }

    private suspend fun attemptLocked(commandId: String): SendAttempt {
        val document = when (val prepared = preparer.prepare(commandId)) {
            is PreparationResult.Prepared -> prepared.document
            is PreparationResult.Refused -> return notSent(prepared.reason, prepared.detail)
        }
        if (document.toByteArray(Charsets.UTF_8).size > CommandLimits.MAX_DOCUMENT_BYTES) {
            return SendAttempt.NotSent(NotSentReason.DOCUMENT_TOO_LARGE, "document of $commandId is over the limit")
        }
        val credential = access.credentialForSync()
            ?: return SendAttempt.NotSent(NotSentReason.NO_CREDENTIAL, "no credential sync may use")

        val started = when (val begun = outbox.beginAttempt(commandId)) {
            is AttemptResult.Started -> begun
            is AttemptResult.Refused -> return notSent(begun.reason, begun.detail)
        }

        val outcome = transport.send(credential, commandId, started.document)
        if (outcome == CommandOutcome.Unauthorized) access.reportUnauthorized(credential)
        return SendAttempt.Sent(commandId, started.attempt, outcome)
    }

    private fun notSent(reason: PreparationRefusal, detail: String): SendAttempt.NotSent = SendAttempt.NotSent(
        when (reason) {
            PreparationRefusal.UNKNOWN_COMMAND -> NotSentReason.UNKNOWN_COMMAND
            PreparationRefusal.NOT_SENDABLE -> NotSentReason.NOT_SENDABLE
            PreparationRefusal.LEGACY_ALREADY_ATTEMPTED -> NotSentReason.LEGACY_ALREADY_ATTEMPTED
            else -> NotSentReason.NOT_PREPARABLE
        },
        detail,
        preparation = reason,
    )

    /** The outbox may refuse after a successful preparation when the command changed in between (confirmed, blocked…). */
    private fun notSent(reason: WireRefusal, detail: String) = SendAttempt.NotSent(
        when (reason) {
            WireRefusal.UNKNOWN_COMMAND -> NotSentReason.UNKNOWN_COMMAND
            WireRefusal.LEGACY_ALREADY_ATTEMPTED -> NotSentReason.LEGACY_ALREADY_ATTEMPTED
            WireRefusal.NOT_SENDABLE -> NotSentReason.NOT_SENDABLE
            WireRefusal.NOT_FROZEN, WireRefusal.INVALID_DOCUMENT -> NotSentReason.NOT_PREPARABLE
        },
        detail,
    )
}
