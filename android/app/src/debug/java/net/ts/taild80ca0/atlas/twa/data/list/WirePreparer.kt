package net.ts.taild80ca0.atlas.twa.data.list

import androidx.room.withTransaction
import java.util.UUID
import net.ts.taild80ca0.atlas.twa.data.db.AlvaDatabase

sealed interface PreparationResult {
    /**
     * [document] is the command's frozen request. [created] is true when this call froze it,
     * false when it was already frozen (by an earlier call or a racing one) and is returned
     * exactly as stored.
     */
    data class Prepared(val commandId: String, val document: String, val created: Boolean) : PreparationResult

    /** Nothing was written. The command and everything around it are untouched. */
    data class Refused(val reason: PreparationRefusal, val detail: String) : PreparationResult
}

/**
 * Prepares the request of a queued command: reads the stored intent, builds the contract
 * v1 document ([WireEnvelopeBuilder]) and freezes it ([WireOutbox.freeze]) in one Room
 * transaction that joins the caller's, if any. There is no network.
 *
 * - A command that already has a document gets exactly that document back, byte for byte,
 *   whatever its status or attempts: nothing is rebuilt, so no UUID is generated twice.
 * - Otherwise the document is built once from the *intent* (never from the projection or a
 *   snapshot) and stored; two racing callers are serialized by the transaction and the SQL
 *   guard, so both get the same bytes and only the winner's claim UUIDs ever exist.
 * - Anything that cannot be sent faithfully is a [PreparationResult.Refused] value with a
 *   reason, and writes nothing. SQL errors propagate and undo the transaction.
 *
 * Call it before the first attempt (it is cheap to call again). It does not count attempts;
 * [WireOutbox.beginAttempt] does, outside any transaction owned by the sender.
 */
class WirePreparer(
    private val db: AlvaDatabase,
    private val outbox: WireOutbox = WireOutbox(db),
    private val claimIds: IdGenerator = IdGenerator { UUID.randomUUID().toString() },
) {

    suspend fun prepare(commandId: String): PreparationResult =
        db.withTransaction {
            val command = db.commands().find(commandId)
                ?: return@withTransaction refused(PreparationRefusal.UNKNOWN_COMMAND, "no command $commandId")
            command.wireDocument?.let { return@withTransaction PreparationResult.Prepared(commandId, it, created = false) }
            if (command.attempts > 0) {
                return@withTransaction refused(
                    PreparationRefusal.LEGACY_ALREADY_ATTEMPTED,
                    "command $commandId was attempted ${command.attempts} time(s) before its document was kept",
                )
            }
            if (CommandStatus.valueOf(command.status) !in SENDABLE) {
                return@withTransaction refused(PreparationRefusal.NOT_SENDABLE, "command $commandId is ${command.status}")
            }

            val dependency = command.dependsOn?.let { db.commands().find(it) }
            val source = WireSource(
                id = command.id,
                type = command.type,
                dependsOn = command.dependsOn,
                observedRevision = command.observedRevision,
                payload = command.payload,
                preconditions = command.preconditions,
                subjects = db.commands().subjectsOf(command.id).map { WireSubject(it.subjectPublicId, it.role, it.ordinal) },
                dependencyTouches = dependency?.let { d -> db.commands().subjectsOf(d.id).mapTo(HashSet()) { it.subjectPublicId } },
            )
            when (val built = WireEnvelopeBuilder.build(source, claimIds)) {
                is WireBuild.Refused -> refused(built.reason, built.detail)
                is WireBuild.Built -> when (val frozen = outbox.freeze(commandId, built.document)) {
                    is FreezeResult.Frozen -> PreparationResult.Prepared(commandId, frozen.document, frozen.created)
                    is FreezeResult.Refused -> refused(
                        when (frozen.reason) {
                            WireRefusal.UNKNOWN_COMMAND -> PreparationRefusal.UNKNOWN_COMMAND
                            WireRefusal.LEGACY_ALREADY_ATTEMPTED -> PreparationRefusal.LEGACY_ALREADY_ATTEMPTED
                            else -> PreparationRefusal.INVALID_DOCUMENT
                        },
                        frozen.detail,
                    )
                }
            }
        }

    private fun refused(reason: PreparationRefusal, detail: String) = PreparationResult.Refused(reason, detail)

    private companion object {
        val SENDABLE = setOf(CommandStatus.PENDING, CommandStatus.IN_FLIGHT)
    }
}
