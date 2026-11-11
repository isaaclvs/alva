package net.ts.taild80ca0.atlas.twa.data.list

import androidx.room.withTransaction
import net.ts.taild80ca0.atlas.twa.data.db.AlvaDatabase
import net.ts.taild80ca0.atlas.twa.data.db.CommandEntity
import net.ts.taild80ca0.atlas.twa.data.db.CommandReceiptEntity
import net.ts.taild80ca0.atlas.twa.data.db.ConfirmedItemEntity
import net.ts.taild80ca0.atlas.twa.data.db.ItemStatusCodes
import net.ts.taild80ca0.atlas.twa.data.db.ListItemEntity
import net.ts.taild80ca0.atlas.twa.data.db.SubjectRole
import org.json.JSONObject

/**
 * Recomputes `list_items` from what the device knows, in one transaction:
 *
 * 1. the confirmed items (what the last snapshot said);
 * 2. every command in device order. A command the server **consumed** and whose receipt is
 *    newer than the confirmed revision (`ACKNOWLEDGED`) contributes what its receipt says,
 *    the server's name, state, `purchased_at` and `lock_version` for the canonical item,
 *    with the category the device chose (the receipt has none). One whose receipt is at or
 *    before the confirmed revision contributes nothing: the confirmed state already
 *    includes its effect, and what it did later may have been undone by someone else, so
 *    replaying it could bring an item back that no longer exists. Every other command
 *    replays its intent.
 * 3. throughout, ids resolve through the aliases the server revealed, so a command queued
 *    against a UUID the device made up acts on the item the server kept.
 *
 * Nothing here writes the queue, the aliases or the confirmed state.
 */
class ProjectionRebuilder(private val db: AlvaDatabase) {

    suspend fun rebuild() {
        db.withTransaction {
            val confirmedRevision = db.syncState().get()?.confirmedRevision ?: 0L
            val aliases = db.aliases().all().associate { it.aliasPublicId to it.canonicalPublicId }
            val sameAs = HashMap<String, String>()
            val resolve: (String) -> String = { id ->
                val key = id.lowercase()
                sameAs[id] ?: sameAs[key] ?: aliases[key] ?: id
            }
            val projection = db.confirmedItems().all().associate { it.publicId to it.toProjection() }.toMutableMap()

            for (command in db.commands().inOrder()) {
                val subjects = db.commands().subjectsOf(command.id)
                if (command.status == CommandStatus.ACKNOWLEDGED.name) {
                    val receipt = db.receipts().find(command.id)
                    if (receipt != null && receipt.revision > confirmedRevision) {
                        applyReceipt(projection, command, receipt, subjects, aliases)
                    }
                    continue
                }
                val replay = ProjectionReducer.replay(
                    projection,
                    ProjectedCommand(
                        command.type,
                        JSONObject(command.payload),
                        subjects.map { ProjectedSubject(it.subjectPublicId, it.role, it.ordinal, it.categoryPublicId) },
                        command.createdAt,
                    ),
                    resolve,
                )
                replay.changed.forEach { projection[it.publicId] = it }
                sameAs += replay.sameAs
            }
            db.listItems().clear()
            db.listItems().upsert(projection.values.toList())
        }
    }

    /** The receipt's effect on the projection. Receipts were validated when they were applied; one that no longer validates contributes nothing. */
    private fun applyReceipt(
        projection: MutableMap<String, ListItemEntity>,
        command: CommandEntity,
        receipt: CommandReceiptEntity,
        subjects: List<net.ts.taild80ca0.atlas.twa.data.db.CommandSubjectEntity>,
        aliases: Map<String, String>,
    ) {
        val document = command.wireDocument ?: return
        val check = ReceiptValidator.check(
            command.type,
            document,
            subjects.map { WireSubject(it.subjectPublicId, it.role, it.ordinal) },
            applied = receipt.outcome == CommandReceiptEntity.APPLIED,
            dataJson = receipt.dataJson,
            knownAlias = { aliases[it] },
        ) as? ReceiptCheck.Valid ?: return

        for (item in check.effect.items) {
            val existing = projection[item.canonicalPublicId]
            if (existing?.tripPublicId != null) continue
            // The receipt has no category. What the device chose shows until a snapshot says better,
            // but never over a category the item already has, except a revive, which re-categorizes.
            val local = subjects.firstOrNull { it.ordinal == item.ordinal }?.categoryPublicId
            val category = if (item.outcome == ItemOutcome.REVIVED) local ?: existing?.categoryPublicId else existing?.categoryPublicId ?: local
            projection[item.canonicalPublicId] = (existing ?: ListItemEntity(
                publicId = item.canonicalPublicId,
                name = item.name,
                categoryPublicId = null,
                status = item.status,
                position = null,
                purchasedAt = item.purchasedAt,
                createdAt = command.createdAt,
                lockVersion = item.lockVersion,
                tripPublicId = null,
            )).copy(
                name = item.name,
                status = item.status,
                purchasedAt = item.purchasedAt,
                lockVersion = item.lockVersion,
                categoryPublicId = category,
            )
        }
    }
}

internal fun ConfirmedItemEntity.toProjection() = ListItemEntity(
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
