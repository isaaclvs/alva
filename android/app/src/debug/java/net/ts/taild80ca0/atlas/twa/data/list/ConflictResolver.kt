package net.ts.taild80ca0.atlas.twa.data.list

import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import net.ts.taild80ca0.atlas.twa.data.db.AlvaDatabase
import net.ts.taild80ca0.atlas.twa.data.db.CommandEntity
import net.ts.taild80ca0.atlas.twa.data.db.CommandResolutionEntity
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/**
 * Preview and explicit, audited removal of a command group from the queue: either a definitively refused command
 * or a revive the device held ([Kind.REFUSED]), or a group with a member whose effect can no longer be learned
 * ([Kind.UNCERTAIN]), each with its dependency closure.
 */
class ConflictResolver(
    private val db: AlvaDatabase,
    private val ids: IdGenerator,
    private val clock: Clock,
) {
    data class Intention(val commandId: String, val description: String)

    enum class Kind {
        /** The server refused every attempt of the root: removing it undoes nothing that happened. */
        REFUSED,

        /**
         * Some member may have taken effect and resending can never say: refused only by `idempotency_conflict` (sent
         * by an older credential lineage whose secret was lost), or sent without an answer and now blocked so it is
         * never sent again. Such commands hold the snapshot forever. Removing the group lets the server's list in;
         * whatever it did is in that list, whatever it did not is gone.
         */
        UNCERTAIN,
    }

    data class Preview(
        val rootId: String,
        val kind: Kind,
        val commandIds: List<String>,
        val subjects: List<String>,
        val token: String,
        val intentions: List<Intention>,
    )
    sealed interface Result {
        data class Discarded(val resolutionId: String) : Result
        data object Stale : Result
        data object NotDiscardable : Result
    }

    /** Only eligible roots, with descendants shown once as part of their oldest root. */
    fun observe(): Flow<List<Preview>> = db.invalidationTracker.createFlow(
        "commands", "command_subjects", "sync_state", "list_items", "sync_aliases", "command_receipts",
        emitInitialState = true,
    ).map {
        db.withTransaction {
            val commands = db.commands().inOrder()
            val covered = mutableSetOf<String>()
            buildList {
                for (command in commands) {
                    if (command.id in covered || !isRootCandidate(command)) continue
                    val (kind, group) = closure(command.id, commands) ?: continue
                    val preview = makePreview(command.id, kind, group)
                    add(preview)
                    covered.addAll(preview.commandIds)
                }
            }
        }
    }.distinctUntilChanged().flowOn(Dispatchers.IO)

    suspend fun preview(rootId: String): Preview? = db.withTransaction {
        val commands = db.commands().inOrder()
        val (kind, group) = closure(rootId, commands) ?: return@withTransaction null
        makePreview(rootId, kind, group)
    }

    suspend fun discard(preview: Preview): Result = db.withTransaction {
        val commands = db.commands().inOrder()
        val (kind, group) = closure(preview.rootId, commands) ?: return@withTransaction Result.NotDiscardable
        val fresh = makePreview(preview.rootId, kind, group)
        if (fresh != preview) return@withTransaction Result.Stale
        val subjects = group.flatMap { db.commands().subjectsOf(it.id) }.map { it.subjectPublicId }.distinct().sorted()
        val records = records(group)
        val resolutionId = ids.newId()
        db.resolutions().insert(CommandResolutionEntity(resolutionId, clock.nowMillis(), records.toString(), JSONArray(subjects).toString()))
        group.forEach { db.commands().delete(it.id) }
        ProjectionRebuilder(db).rebuild()
        Result.Discarded(resolutionId)
    }

    private suspend fun closure(rootId: String, commands: List<CommandEntity>): Pair<Kind, List<CommandEntity>>? {
        val byId = commands.associateBy { it.id }
        val root = byId[rootId] ?: return null
        if (!isRootCandidate(root)) return null
        val result = linkedMapOf(root.id to root)
        var changed: Boolean
        do {
            changed = false
            commands.filter { it.dependsOn in result && it.id !in result }.forEach { command ->
                // A consumed dependent proves its dependency took effect: never take that history away.
                if (command.status == "ACKNOWLEDGED") return null
                result[command.id] = command
                changed = true
            }
        } while (changed)
        val group = result.values.sortedBy { it.sequence }
        if (group.any { db.receipts().find(it.id) != null }) return null
        // Discarding is only for what provably had no effect. One member whose effect cannot be known (sent without
        // an answer, sent before its bytes were kept, or refused only by idempotency) turns the whole group into a
        // decision to take the server's list instead: never into an assumption that nothing happened.
        val kind = if (group.any { !provenWithoutEffect(it) }) Kind.UNCERTAIN else Kind.REFUSED
        return kind to group
    }

    /**
     * What a person can be asked about: the server refused it (`CONFLICT`/`REJECTED`), or the device itself held it
     * because a revive was answered with another item (`BLOCKED`/`revive_redirected`) and nothing will ever send it.
     * A command refused on an attempt that is not its latest, or sent before its bytes were kept, is not a root.
     */
    private fun isRootCandidate(command: CommandEntity): Boolean = when {
        command.status == "BLOCKED" && command.lastErrorCode == REVIVE_REDIRECTED -> true
        command.status !in setOf("CONFLICT", "REJECTED") -> false
        command.lastErrorCode == IDEMPOTENCY_CONFLICT -> command.attempts > 0 && command.wireDocument != null
        else -> command.attempts > 0 && command.wireDocument != null && command.answeredAttempt >= command.attempts
    }

    /** Never sent, or every attempt answered by a refusal that proves it did nothing. */
    private fun provenWithoutEffect(command: CommandEntity): Boolean =
        command.attempts == 0 ||
            (command.wireDocument != null && command.answeredAttempt >= command.attempts && command.lastErrorCode != IDEMPOTENCY_CONFLICT)

    private suspend fun makePreview(rootId: String, kind: Kind, group: List<CommandEntity>): Preview {
        val subjects = group.flatMap { db.commands().subjectsOf(it.id) }.map { it.subjectPublicId }.distinct().sorted()
        // Include every original field, not just status: a new subject category,
        // local intent or server snapshot must invalidate an old confirmation.
        val state = db.syncState().get()
        val material = "$kind|" + records(group).toString() + "|${state?.householdPublicId}|${state?.confirmedRevision}"
        val token = MessageDigest.getInstance("SHA-256").digest(material.toByteArray()).joinToString("") { "%02x".format(it) }
        val intentions = group.map { command ->
            val payload = JSONObject(command.payload)
            val description = when (command.type) {
                CommandTypes.ADD_ITEMS -> {
                    val names = payload.optJSONArray("names") ?: JSONArray()
                    "Adicionar " + (0 until names.length()).joinToString(", ") { names.getString(it) }
                }
                CommandTypes.SET_ITEM_STATE -> {
                    val cited = payload.optString("public_id")
                    val canonical = db.aliases().find(cited)?.canonicalPublicId ?: cited
                    val name = db.listItems().find(canonical)?.name ?: "item"
                    if (payload.optString("desired") == "purchased") "Marcar $name como comprado" else "Marcar $name como pendente"
                }
                else -> "Alteração guardada neste aparelho"
            }
            Intention(command.id, description)
        }
        return Preview(rootId, kind, group.map { it.id }, subjects, token, intentions)
    }

    private suspend fun records(group: List<CommandEntity>): JSONArray {
        val records = JSONArray()
        for (command in group) {
            val subjects = JSONArray()
            for (subject in db.commands().subjectsOf(command.id)) {
                subjects.put(
                    JSONObject().put("id", subject.subjectPublicId).put("ordinal", subject.ordinal)
                        .put("role", subject.role).put("category_public_id", subject.categoryPublicId ?: JSONObject.NULL),
                )
            }
            records.put(
                JSONObject().put("sequence", command.sequence).put("id", command.id).put("type", command.type)
                    .put("payload", command.payload).put("preconditions", command.preconditions)
                    .put("depends_on", command.dependsOn ?: JSONObject.NULL).put("observed_revision", command.observedRevision)
                    .put("status", command.status).put("last_error_code", command.lastErrorCode ?: JSONObject.NULL)
                    .put("attempts", command.attempts).put("answered_attempt", command.answeredAttempt)
                    .put("wire_document", command.wireDocument ?: JSONObject.NULL).put("created_at", command.createdAt)
                    .put("subjects", subjects),
            )
        }
        return records
    }

    private companion object {
        const val IDEMPOTENCY_CONFLICT = "idempotency_conflict"
        const val REVIVE_REDIRECTED = "revive_redirected"
    }
}
