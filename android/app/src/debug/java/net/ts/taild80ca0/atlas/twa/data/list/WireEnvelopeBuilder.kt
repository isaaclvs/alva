package net.ts.taild80ca0.atlas.twa.data.list

import net.ts.taild80ca0.atlas.twa.data.db.SubjectRole
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Why a queued command could not be turned into a request. In every case the command, its
 * intent, its subjects and the projection are exactly as they were; nothing is dropped,
 * replaced or invented. The "exit" column is what a later task must provide for the row to
 * move on (see `docs/plans/android-local-persistence.md`, "Preparação do envio").
 */
enum class PreparationRefusal {
    /** No such command (it may have been confirmed and removed). */
    UNKNOWN_COMMAND,

    /** BLOCKED / CONFLICT / REJECTED: the user or reconciliation owns it, not the sender. */
    NOT_SENDABLE,

    /** Attempted before its document was kept; the bytes sent then are unknown. Exit: snapshot, then re-issue with new UUIDs. */
    LEGACY_ALREADY_ATTEMPTED,

    /** A command type with no wire form yet. Exit: the task that introduces the type. */
    UNSUPPORTED_TYPE,

    /** Payload, preconditions and subjects disagree; the row is damaged. Exit: manual or reconciliation repair. */
    INCONSISTENT_INTENT,

    /** `add_items` with more names than the contract takes (30); legacy rows from `AddItems`. Exit: a task that splits it explicitly (#53), never silently. */
    ENVELOPE_TOO_LARGE,

    /** A revive from before observations were kept and nothing in its dependency supplies the version. Exit: snapshot shows what is on the server, then the user's intent is re-evaluated. */
    REVIVE_OBSERVATION_LOST,

    /** The device never saw a version of the item and there is no earlier command to inherit one from. Exit: a snapshot with the item (reconciliation). */
    VERSION_UNKNOWN,

    /** There is an earlier command, but it does not (provably) touch this item, so it cannot lend a version. Exit: a snapshot with the item (reconciliation). */
    DEPENDENCY_INSUFFICIENT,

    /** The document built was not accepted for freezing (a bug here, or a mismatch with the command). */
    INVALID_DOCUMENT,
}

/** A record the local command touches (a row of `command_subjects`). */
data class WireSubject(val publicId: String, val role: String, val ordinal: Int)

/**
 * Everything the builder may use: the stored intent and queue facts. Deliberately no
 * projection and no snapshot, so it cannot answer "what is the item like now".
 */
class WireSource(
    val id: String,
    val type: String,
    val dependsOn: String?,
    val observedRevision: Long,
    /** The local intent JSON (`commands.payload`). */
    val payload: String,
    /** The local precondition JSON (`commands.preconditions`). */
    val preconditions: String,
    val subjects: List<WireSubject>,
    /**
     * `public_id`s the command in [dependsOn] still touches in the queue, or null when that
     * command is not in the queue (no dependency, or it was confirmed and removed).
     */
    val dependencyTouches: Set<String>?,
)

sealed interface WireBuild {
    data class Built(val document: String) : WireBuild
    data class Refused(val reason: PreparationRefusal, val detail: String) : WireBuild
}

/**
 * Turns the stored local intent of one command into the JSON document contract v1 sends
 * (`docs/plans/android-sync-contract-v1.md`, "Comandos v1"). Pure: no database, no clock, no
 * network; the only outside input is [claimIds], used for the one new UUID a revive needs.
 *
 * - `add_items`: `payload.names` + `command_subjects` become `payload.items`. A name that
 *   creates an item travels with the UUID the device already gave it. A name that revives a
 *   purchase travels with a **new claim UUID** and `revives: { public_id: <the purchase> }`:
 *   the server treats a UUID it already knows as a duplicate, so the purchase's own UUID can
 *   never be the entry's `public_id`. The claim becomes an alias of the purchase when the
 *   server answers; item `i` of the document is the subject with `ordinal == i`.
 * - `set_item_state`: the desired state, the `lock_version` the user saw (if any) and the
 *   command's own `depends_on`.
 * - Versions are only what the intent recorded or what `depends_on` can lend. A version is
 *   never read from the current projection or a newer snapshot.
 *
 * The result is deterministic for a given [WireSource] and claim UUIDs, which is why the
 * caller freezes it: it is built once and never again.
 */
object WireEnvelopeBuilder {
    const val MAX_ADD_ITEMS = 30

    fun build(source: WireSource, claimIds: IdGenerator): WireBuild =
        when (source.type) {
            CommandTypes.ADD_ITEMS -> addItems(source, claimIds)
            CommandTypes.SET_ITEM_STATE -> setItemState(source)
            else -> refused(PreparationRefusal.UNSUPPORTED_TYPE, "type ${source.type} has no wire form yet")
        }

    private fun addItems(source: WireSource, claimIds: IdGenerator): WireBuild {
        val payload = parse(source.payload) ?: return refused(PreparationRefusal.INCONSISTENT_INTENT, "payload is not a JSON object")
        val preconditions = parse(source.preconditions) ?: return refused(PreparationRefusal.INCONSISTENT_INTENT, "preconditions is not a JSON object")
        val names = payload.opt("names") as? JSONArray
            ?: return refused(PreparationRefusal.INCONSISTENT_INTENT, "payload.names must be an array")
        if (names.length() == 0) return refused(PreparationRefusal.INCONSISTENT_INTENT, "payload.names is empty")
        if (names.length() > MAX_ADD_ITEMS) {
            return refused(
                PreparationRefusal.ENVELOPE_TOO_LARGE,
                "add_items carries ${names.length()} names; the contract takes at most $MAX_ADD_ITEMS and none was dropped",
            )
        }
        val typed = (0 until names.length()).map { names.opt(it) as? String ?: return refused(PreparationRefusal.INCONSISTENT_INTENT, "payload.names[$it] must be a string") }

        val byOrdinal = source.subjects.groupBy { it.ordinal }
        if (byOrdinal.keys != (0 until typed.size).toSet() || byOrdinal.values.any { it.size != 1 }) {
            return refused(PreparationRefusal.INCONSISTENT_INTENT, "subjects do not match the ${typed.size} names one to one")
        }
        val subjects = typed.indices.map { byOrdinal.getValue(it).single() }
        subjects.firstOrNull { it.role != SubjectRole.CREATES && it.role != SubjectRole.TARGETS }?.let {
            return refused(PreparationRefusal.INCONSISTENT_INTENT, "subject ${it.ordinal} has role ${it.role}")
        }

        val recorded = try {
            ReviveObservation.fromPreconditions(preconditions)
        } catch (e: IllegalArgumentException) {
            return refused(PreparationRefusal.INCONSISTENT_INTENT, e.message ?: "revive observations are damaged")
        }
        val targets = subjects.filter { it.role == SubjectRole.TARGETS }
        if (recorded != null) {
            val consistent = recorded.size == targets.size &&
                recorded.all { seen -> targets.any { it.ordinal == seen.ordinal && it.publicId == seen.publicId } }
            if (!consistent) return refused(PreparationRefusal.INCONSISTENT_INTENT, "revive observations do not match the revived subjects")
        }

        // Decide every revive before any UUID is generated: a refusal must not consume one.
        val revives = HashMap<Int, Revive>()
        for (target in targets) {
            val seen = recorded?.firstOrNull { it.ordinal == target.ordinal }
            val covered = source.dependsOn != null &&
                (seen?.covered == true || source.dependencyTouches?.contains(target.publicId) == true)
            val version = seen?.lockVersion
            if (version == null && !covered) {
                return when {
                    recorded == null -> refused(
                        PreparationRefusal.REVIVE_OBSERVATION_LOST,
                        "the purchase of ${target.publicId} was seen before observations were kept, and no earlier command lends its version",
                    )
                    source.dependsOn == null -> refused(
                        PreparationRefusal.VERSION_UNKNOWN,
                        "the device never saw a version of ${target.publicId} and nothing earlier touched it",
                    )
                    else -> refused(
                        PreparationRefusal.DEPENDENCY_INSUFFICIENT,
                        "the earlier command ${source.dependsOn} does not touch ${target.publicId}, so it cannot lend its version",
                    )
                }
            }
            revives[target.ordinal] = Revive(target.publicId, version)
        }

        val taken = HashSet<String>().apply { subjects.forEach { add(it.publicId.lowercase()) } }
        val items = typed.indices.map { ordinal ->
            val revive = revives[ordinal]
            if (revive == null) {
                Item(subjects[ordinal].publicId, typed[ordinal], null)
            } else {
                var claim = claimIds.newId()
                while (!taken.add(claim.lowercase())) claim = claimIds.newId()
                Item(claim, typed[ordinal], revive)
            }
        }
        return WireBuild.Built(
            envelope(source, "{}") {
                append("""{"items":[""")
                items.forEachIndexed { index, item ->
                    if (index > 0) append(',')
                    append("""{"public_id":""").append(quote(item.publicId)).append(""","name":""").append(quote(item.name))
                    item.revives?.let { revive ->
                        append(""","revives":{"public_id":""").append(quote(revive.publicId))
                        revive.lockVersion?.let { append(""","lock_version":""").append(it) }
                        append('}')
                    }
                    append('}')
                }
                append("]}")
            },
        )
    }

    private fun setItemState(source: WireSource): WireBuild {
        val payload = parse(source.payload) ?: return refused(PreparationRefusal.INCONSISTENT_INTENT, "payload is not a JSON object")
        val preconditions = parse(source.preconditions) ?: return refused(PreparationRefusal.INCONSISTENT_INTENT, "preconditions is not a JSON object")
        val publicId = (payload.opt("public_id") as? String)?.takeIf { it.isNotBlank() }
            ?: return refused(PreparationRefusal.INCONSISTENT_INTENT, "payload.public_id must be a string")
        val desired = (payload.opt("desired") as? String)?.takeIf { it == ProjectionReducer.DESIRED_PENDING || it == ProjectionReducer.DESIRED_PURCHASED }
            ?: return refused(PreparationRefusal.INCONSISTENT_INTENT, "payload.desired must be pending or purchased")
        val subject = source.subjects.singleOrNull()
        if (subject == null || subject.publicId != publicId || subject.role != SubjectRole.TARGETS) {
            return refused(PreparationRefusal.INCONSISTENT_INTENT, "the subject is not the item in the payload")
        }

        val version = when (val value = preconditions.opt("lock_version")) {
            null, JSONObject.NULL -> null
            is Int -> value.takeIf { it >= 0 } ?: return refused(PreparationRefusal.INCONSISTENT_INTENT, "preconditions.lock_version must be >= 0")
            else -> return refused(PreparationRefusal.INCONSISTENT_INTENT, "preconditions.lock_version must be an integer")
        }
        if (version == null) {
            if (source.dependsOn == null) {
                return refused(PreparationRefusal.VERSION_UNKNOWN, "the device never saw a version of $publicId and nothing earlier touched it")
            }
            if (source.dependencyTouches != null && publicId !in source.dependencyTouches) {
                return refused(
                    PreparationRefusal.DEPENDENCY_INSUFFICIENT,
                    "the earlier command ${source.dependsOn} does not touch $publicId, so it cannot lend its version",
                )
            }
        }
        val wirePreconditions = if (version == null) "{}" else """{"lock_version":$version}"""
        return WireBuild.Built(
            envelope(source, wirePreconditions) {
                append("""{"public_id":""").append(quote(publicId)).append(""","desired":""").append(quote(desired)).append('}')
            },
        )
    }

    /** Fixed key order, so the same inputs always give the same bytes. */
    private fun envelope(source: WireSource, preconditions: String, payload: StringBuilder.() -> Unit): String =
        buildString {
            append("""{"id":""").append(quote(source.id))
            append(""","type":""").append(quote(source.type))
            append(""","depends_on":""").append(source.dependsOn?.let { quote(it) } ?: "null")
            // 0 is "nothing observed yet" in the queue; the server does not use it for these commands.
            if (source.observedRevision > 0) append(""","observed_revision":""").append(source.observedRevision)
            append(""","preconditions":""").append(preconditions)
            append(""","payload":""")
            payload()
            append('}')
        }

    private class Revive(val publicId: String, val lockVersion: Int?)
    private class Item(val publicId: String, val name: String, val revives: Revive?)

    private fun quote(text: String): String = JSONObject.quote(text)

    private fun parse(text: String): JSONObject? =
        try {
            JSONObject(text)
        } catch (e: JSONException) {
            null
        }

    private fun refused(reason: PreparationRefusal, detail: String) = WireBuild.Refused(reason, detail)
}
