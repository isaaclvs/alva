package net.ts.taild80ca0.atlas.twa.data.list

import androidx.room.withTransaction
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import net.ts.taild80ca0.atlas.twa.data.catalog.LocalCatalog
import net.ts.taild80ca0.atlas.twa.data.db.AlvaDatabase
import net.ts.taild80ca0.atlas.twa.data.db.CommandEntity
import net.ts.taild80ca0.atlas.twa.data.db.CommandSubjectEntity
import net.ts.taild80ca0.atlas.twa.data.db.ListItemEntity
import net.ts.taild80ca0.atlas.twa.data.db.SubjectRole
import net.ts.taild80ca0.atlas.twa.data.matching.ItemNameSplitter
import org.json.JSONArray
import org.json.JSONObject

/**
 * [ShoppingListStore] on Room.
 *
 * Every successful [execute] is one SQLite transaction that (1) updates the
 * local projection and (2) appends the command, with its subjects and its
 * dependency, to the queue. If either step fails, both are rolled back and the
 * caller gets [IntentResult.Failed]; it never gets `Saved` for a change that is
 * not durably queued.
 */
class RoomShoppingListStore(
    private val db: AlvaDatabase,
    private val ids: IdGenerator = IdGenerator { UUID.randomUUID().toString() },
    private val clock: Clock = Clock { System.currentTimeMillis() },
) : ShoppingListStore {

    override fun observe(): Flow<ListState> =
        db.invalidationTracker
            .createFlow("list_items", "categories", "commands", "command_subjects", "sync_state")
            // One transaction per emission: items, queue and revision always belong together.
            .map { db.withTransaction { readState() } }
            .distinctUntilChanged()

    override suspend fun execute(intent: ListIntent): IntentResult =
        try {
            db.withTransaction { run(intent) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            IntentResult.Failed(failure)
        }

    private suspend fun run(intent: ListIntent): IntentResult =
        when (intent) {
            is ListIntent.AddText -> addItems(ItemNameSplitter.split(intent.text))
            is ListIntent.AddItems -> addItems(intent.names)
            is ListIntent.SetItemState -> setItemState(intent)
        }

    /**
     * The local decision for each name (see [AddPlanner]): duplicates are dropped, a
     * purchased name is revived, the rest are created already categorized. Only the
     * names that change the list go into the `add_items` command, so its payload
     * keeps the contract shape (`names`); what the device decided per name travels
     * as local metadata in the command subjects, not on the wire.
     */
    private suspend fun addItems(names: List<String>): IntentResult {
        val steps = AddPlanner(LocalCatalog.categorizerOn(db), ids).plan(names, db.listItems().live())
        if (steps.singleOrNull() == AddStep.Invalid) return IntentResult.Invalid(InvalidReason.NO_NAMES)

        val additions = steps.map { it.toAddition() }
        val changing = steps.filter { it is AddStep.Create || it is AddStep.Revive }
        if (changing.isEmpty()) return IntentResult.AlreadyOnList(additions)

        val subjects = changing.mapIndexed { index, step ->
            when (step) {
                is AddStep.Create -> Subject(step.publicId, SubjectRole.CREATES, index, step.categoryPublicId)
                is AddStep.Revive -> Subject(step.item.publicId, SubjectRole.TARGETS, index, step.categoryPublicId)
                else -> error("only steps that change the list are queued")
            }
        }
        val payload = JSONObject().put("names", JSONArray(changing.map { it.typedName() }))
        // What the user saw of each purchase being revived is fixed now, not when the request is
        // prepared: a snapshot applied in between must not pose as the version they saw.
        val seen = changing.mapIndexedNotNull { index, step ->
            (step as? AddStep.Revive)?.let { ReviveObservation(index, it.item.publicId, it.item.lockVersion, covered = false) }
        }
        val commandId = projectAndEnqueue(CommandTypes.ADD_ITEMS, payload, lockVersion = null, subjects, seen)
        return IntentResult.Saved(commandId, subjects.map { it.id }, additions)
    }

    private fun AddStep.typedName(): String = when (this) {
        is AddStep.Create -> name
        is AddStep.Revive -> name
        is AddStep.Duplicate -> name
        AddStep.Invalid -> ""
    }

    private fun AddStep.toAddition(): ItemAddition = when (this) {
        is AddStep.Create -> ItemAddition(name, AddOutcome.CREATED, publicId)
        is AddStep.Revive -> ItemAddition(name, AddOutcome.REVIVED, item.publicId)
        is AddStep.Duplicate -> ItemAddition(name, AddOutcome.DUPLICATE, itemPublicId)
        AddStep.Invalid -> error("invalid is answered before additions are built")
    }

    private suspend fun setItemState(intent: ListIntent.SetItemState): IntentResult {
        val item = db.listItems().find(intent.publicId)
        if (item == null || item.tripPublicId != null) {
            return IntentResult.Invalid(InvalidReason.ITEM_NOT_FOUND)
        }
        if (item.status == intent.desired.code) return IntentResult.Unchanged

        val payload = JSONObject()
            .put("public_id", item.publicId)
            .put("desired", if (intent.desired == ItemStatus.PURCHASED) {
                ProjectionReducer.DESIRED_PURCHASED
            } else {
                ProjectionReducer.DESIRED_PENDING
            })
        val subjects = listOf(Subject(item.publicId, SubjectRole.TARGETS, 0))
        val commandId = projectAndEnqueue(CommandTypes.SET_ITEM_STATE, payload, item.lockVersion, subjects)
        return IntentResult.Saved(commandId, listOf(item.publicId))
    }

    /** Projection first, queue second: a failing enqueue must undo the visible change. */
    private suspend fun projectAndEnqueue(
        type: String,
        payload: JSONObject,
        lockVersion: Int?,
        subjects: List<Subject>,
        revives: List<ReviveObservation> = emptyList(),
    ): String {
        val now = clock.nowMillis()
        val projected = ProjectedCommand(
            type, payload, subjects.map { ProjectedSubject(it.id, it.role, it.ordinal, it.categoryPublicId) }, now,
        )
        val touched = subjects.filter { it.role == SubjectRole.TARGETS }.map { it.id }
        val current = db.listItems().findAll(touched).associateBy { it.publicId }
        db.listItems().upsert(ProjectionReducer.reduce(current, projected))

        val commandId = ids.newId()
        val ancestor = if (touched.isEmpty()) null else db.commands().latestTouching(touched)
        val status = if (ancestor != null && CommandStatus.valueOf(ancestor.status).stopsDependents) {
            CommandStatus.BLOCKED
        } else {
            CommandStatus.PENDING
        }
        val preconditions = if (revives.isEmpty()) {
            JSONObject().apply { if (lockVersion != null) put("lock_version", lockVersion) }
        } else {
            // `covered`: the command this one depends on is the last to have touched that item,
            // so the server can hand its resulting version on. Decided here, with the queue as it is.
            ReviveObservation.toPreconditions(
                revives.map { it.copy(covered = ancestor != null && db.commands().latestTouching(listOf(it.publicId))?.id == ancestor.id) },
            )
        }
        db.commands().insert(
            CommandEntity(
                id = commandId,
                type = type,
                payload = payload.toString(),
                preconditions = preconditions.toString(),
                dependsOn = ancestor?.id,
                observedRevision = db.syncState().get()?.confirmedRevision ?: 0L,
                status = status.name,
                createdAt = now,
            ),
        )
        db.commands().insertSubjects(
            subjects.map { CommandSubjectEntity(commandId, it.id, it.ordinal, it.role, it.categoryPublicId) },
        )
        return commandId
    }

    /**
     * Recomputes the projection as confirmed state + every queued command, in
     * device order. Reconciliation (a later task) calls this after writing a new
     * confirmed state, so a remote read never erases a local action.
     */
    suspend fun rebuildProjection() {
        db.withTransaction {
            val projection = db.confirmedItems().all()
                .associate { it.publicId to it.toProjection() }
                .toMutableMap()
            for (command in db.commands().inOrder()) {
                val subjects = db.commands().subjectsOf(command.id)
                    .map { ProjectedSubject(it.subjectPublicId, it.role, it.ordinal, it.categoryPublicId) }
                val changed = ProjectionReducer.reduce(
                    projection,
                    ProjectedCommand(command.type, JSONObject(command.payload), subjects, command.createdAt),
                )
                changed.forEach { projection[it.publicId] = it }
            }
            db.listItems().clear()
            db.listItems().upsert(projection.values.toList())
        }
    }

    private suspend fun readState(): ListState {
        val awaiting = db.commands().subjectIdsInQueue().toSet()
        val needsAttention = CommandStatus.entries.filter { it.stopsDependents }
            .sumOf { db.commands().countByStatus(it.name) }
        return ListState(
            items = db.listItems().live().map { it.toDomain(it.publicId in awaiting) },
            categories = db.categories().all().map { Category(it.publicId, it.name, it.position) },
            queuedCommands = db.commands().count(),
            needsAttention = needsAttention,
            confirmedRevision = db.syncState().get()?.confirmedRevision ?: 0L,
        )
    }

    private class Subject(val id: String, val role: String, val ordinal: Int, val categoryPublicId: String? = null)
}

private fun ListItemEntity.toDomain(awaitingSync: Boolean) = ListItem(
    publicId = publicId,
    name = name,
    categoryPublicId = categoryPublicId,
    status = ItemStatus.fromCode(status),
    purchasedAt = purchasedAt,
    awaitingSync = awaitingSync,
)

private fun net.ts.taild80ca0.atlas.twa.data.db.ConfirmedItemEntity.toProjection() = ListItemEntity(
    publicId = publicId,
    name = name,
    categoryPublicId = categoryPublicId,
    status = status,
    position = position,
    purchasedAt = purchasedAt,
    createdAt = createdAt,
    lockVersion = lockVersion,
    tripPublicId = tripPublicId,
)
