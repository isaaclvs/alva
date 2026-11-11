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

/** Preview and explicit, audited discard of a definitively refused command and its dependency closure. */
class ConflictResolver(
    private val db: AlvaDatabase,
    private val ids: IdGenerator,
    private val clock: Clock,
) {
    data class Intention(val commandId: String, val description: String)
    data class Preview(
        val rootId: String,
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
                    if (command.id in covered || command.status !in setOf("CONFLICT", "REJECTED")) continue
                    val group = closure(command.id, commands) ?: continue
                    val preview = makePreview(command.id, group)
                    add(preview)
                    covered.addAll(preview.commandIds)
                }
            }
        }
    }.distinctUntilChanged().flowOn(Dispatchers.IO)

    suspend fun preview(rootId: String): Preview? = db.withTransaction {
        val commands = db.commands().inOrder()
        val group = closure(rootId, commands) ?: return@withTransaction null
        makePreview(rootId, group)
    }

    suspend fun discard(preview: Preview): Result = db.withTransaction {
        val commands = db.commands().inOrder()
        val group = closure(preview.rootId, commands) ?: return@withTransaction Result.NotDiscardable
        val fresh = makePreview(preview.rootId, group)
        if (fresh != preview) return@withTransaction Result.Stale
        val subjects = group.flatMap { db.commands().subjectsOf(it.id) }.map { it.subjectPublicId }.distinct().sorted()
        val records = records(group)
        val resolutionId = ids.newId()
        db.resolutions().insert(CommandResolutionEntity(resolutionId, clock.nowMillis(), records.toString(), JSONArray(subjects).toString()))
        group.forEach { db.commands().delete(it.id) }
        ProjectionRebuilder(db).rebuild()
        Result.Discarded(resolutionId)
    }

    private suspend fun closure(rootId: String, commands: List<CommandEntity>): List<CommandEntity>? {
        val byId = commands.associateBy { it.id }
        val root = byId[rootId] ?: return null
        if (root.status !in setOf("CONFLICT", "REJECTED") || root.attempts <= 0 || root.answeredAttempt < root.attempts ||
            root.wireDocument == null || root.lastErrorCode == "idempotency_conflict") return null
        val result = linkedMapOf(root.id to root)
        var changed: Boolean
        do {
            changed = false
            commands.filter { it.dependsOn in result && it.id !in result }.forEach { command ->
                if (command.status == "ACKNOWLEDGED" || command.attempts > command.answeredAttempt ||
                    (command.attempts > 0 && command.wireDocument == null) ||
                    (command.status in setOf("CONFLICT", "REJECTED") && (command.answeredAttempt < command.attempts || command.wireDocument == null))) return null
                result[command.id] = command
                changed = true
            }
        } while (changed)
        val group = result.values.sortedBy { it.sequence }
        if (group.any { it.lastErrorCode == "idempotency_conflict" || db.receipts().find(it.id) != null }) return null
        return group
    }

    private suspend fun makePreview(rootId: String, group: List<CommandEntity>): Preview {
        val subjects = group.flatMap { db.commands().subjectsOf(it.id) }.map { it.subjectPublicId }.distinct().sorted()
        // Include every original field, not just status: a new subject category,
        // local intent or server snapshot must invalidate an old confirmation.
        val state = db.syncState().get()
        val material = records(group).toString() + "|${state?.householdPublicId}|${state?.confirmedRevision}"
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
        return Preview(rootId, group.map { it.id }, subjects, token, intentions)
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
}
