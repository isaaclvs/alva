package net.ts.taild80ca0.atlas.twa.data.list

import androidx.room.withTransaction
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import net.ts.taild80ca0.atlas.twa.data.db.AlvaDatabase
import net.ts.taild80ca0.atlas.twa.data.db.CommandEntity
import net.ts.taild80ca0.atlas.twa.data.db.CommandSubjectEntity
import net.ts.taild80ca0.atlas.twa.data.db.ListItemEntity
import net.ts.taild80ca0.atlas.twa.data.db.SubjectRole
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
            is ListIntent.AddItems -> addItems(intent)
            is ListIntent.SetItemState -> setItemState(intent)
        }

    private suspend fun addItems(intent: ListIntent.AddItems): IntentResult {
        val names = intent.names.map { it.trim() }.filter { it.isNotEmpty() }
        if (names.isEmpty()) return IntentResult.Invalid(InvalidReason.NO_NAMES)

        val itemIds = names.map { ids.newId() }
        val payload = JSONObject().put("names", JSONArray(names))
        val subjects = itemIds.mapIndexed { index, id -> Subject(id, SubjectRole.CREATES, index) }
        val commandId = projectAndEnqueue(CommandTypes.ADD_ITEMS, payload, lockVersion = null, subjects, itemIds)
        return IntentResult.Saved(commandId, itemIds)
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
        val commandId = projectAndEnqueue(
            CommandTypes.SET_ITEM_STATE, payload, item.lockVersion, subjects, createdIds = emptyList(),
        )
        return IntentResult.Saved(commandId, listOf(item.publicId))
    }

    /** Projection first, queue second: a failing enqueue must undo the visible change. */
    private suspend fun projectAndEnqueue(
        type: String,
        payload: JSONObject,
        lockVersion: Int?,
        subjects: List<Subject>,
        createdIds: List<String>,
    ): String {
        val now = clock.nowMillis()
        val projected = ProjectedCommand(type, payload, createdIds, now)
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
        val preconditions = JSONObject().apply { if (lockVersion != null) put("lock_version", lockVersion) }
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
            subjects.map { CommandSubjectEntity(commandId, it.id, it.ordinal, it.role) },
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
                val created = db.commands().subjectsOf(command.id)
                    .filter { it.role == SubjectRole.CREATES }.map { it.subjectPublicId }
                val changed = ProjectionReducer.reduce(
                    projection,
                    ProjectedCommand(command.type, JSONObject(command.payload), created, command.createdAt),
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

    private class Subject(val id: String, val role: String, val ordinal: Int)
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
