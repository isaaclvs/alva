package net.ts.taild80ca0.atlas.twa.sync

import androidx.room.withTransaction
import net.ts.taild80ca0.atlas.twa.data.catalog.LocalCatalog
import net.ts.taild80ca0.atlas.twa.data.db.AlvaDatabase
import net.ts.taild80ca0.atlas.twa.data.db.ArchivedItemEntity
import net.ts.taild80ca0.atlas.twa.data.db.ConfirmedItemEntity
import net.ts.taild80ca0.atlas.twa.data.db.HabitualItemEntity
import net.ts.taild80ca0.atlas.twa.data.db.ShoppingTripEntity
import net.ts.taild80ca0.atlas.twa.data.db.SyncStateEntity
import net.ts.taild80ca0.atlas.twa.data.db.TombstoneEntity
import net.ts.taild80ca0.atlas.twa.data.list.CommandStatus
import net.ts.taild80ca0.atlas.twa.data.list.ProjectionRebuilder

/** Why a well-formed snapshot was not installed. In every case nothing was written. */
enum class SnapshotRefusal {
    /** This device is not tied to a household yet (it never connected), so no snapshot can be its own. */
    NOT_BOUND,

    /** The snapshot is another household's. Never merged, never replaced. */
    OTHER_HOUSEHOLD,

    /** Commands were sent and the server never answered: it may have applied them. See [InstallResult.Refused.commandIds]. */
    UNRESOLVED_ATTEMPTS,

    /** `revision` is lower than the confirmed one: an old read must never replace newer knowledge. */
    OLDER_REVISION,

    /** `dictionary_revision` is lower than the stored one. */
    OLDER_DICTIONARY,

    /** The snapshot is older than the server's own receipt for a command this device sent. It cannot be what the server holds now. */
    BEHIND_RECEIPT,

    /** The snapshot lists as a live or archived item an id the server told this device is an alias. The two cannot both be true. */
    ALIAS_CONTRADICTION,
}

sealed interface InstallResult {
    /**
     * The confirmed state is now this snapshot's, and the projection is the confirmed state with the queue
     * replayed over it. [released] are the commands the server had consumed whose effect the snapshot now carries,
     * removed with their receipts; commands still in the queue were not touched.
     */
    data class Installed(val revision: Long, val released: List<String>) : InstallResult

    /** Nothing changed. [commandIds] are the unanswered attempts, for [SnapshotRefusal.UNRESOLVED_ATTEMPTS]. */
    data class Refused(val reason: SnapshotRefusal, val commandIds: List<String> = emptyList()) : InstallResult
}

/**
 * Makes a snapshot the device's confirmed state, in one Room transaction, and rebuilds the projection from it
 * with the queue on top. It is the only writer of `confirmed_items` and the history tables and the only mover of
 * `sync_state.confirmed_revision`. The network is over by the time it runs.
 *
 * It installs only when installing cannot lose anything:
 *
 * - the snapshot belongs to the household this device is bound to (never bound by this class);
 * - **no sent command is without an answer** (`unresolvedAttempts`): a snapshot read after another device removed
 *   or archived an item does not show it, and replaying the old command over that would bring it back. The sender
 *   resolves those first; here they only refuse;
 * - the house revision and the dictionary revision do not go backwards, and the snapshot is not older than the
 *   receipt of a command this device sent;
 * - it does not contradict an alias the server revealed.
 *
 * Then, still in the same transaction: the catalog is replaced ([LocalCatalog.replace]), the live items and the
 * history are replaced as a whole (what the snapshot does not have is gone; the queue is never touched by a read),
 * `confirmed_revision` moves, the commands the server consumed whose revision the snapshot covers are released, and
 * `list_items` is rebuilt ([ProjectionRebuilder]) from the confirmed state plus every command still queued, with the
 * aliases in force. A failing statement rolls all of it back and the exception propagates: a half-installed
 * snapshot does not exist.
 */
class SnapshotInstaller(
    private val db: AlvaDatabase,
    private val rebuilder: ProjectionRebuilder = ProjectionRebuilder(db),
) {
    suspend fun install(snapshot: ServerSnapshot): InstallResult =
        db.withTransaction {
            val state = db.syncState().get()
            val bound = state?.householdPublicId ?: return@withTransaction InstallResult.Refused(SnapshotRefusal.NOT_BOUND)
            if (bound != snapshot.householdPublicId) return@withTransaction InstallResult.Refused(SnapshotRefusal.OTHER_HOUSEHOLD)

            val unresolved = db.commands().unresolvedAttemptIds()
            if (unresolved.isNotEmpty()) return@withTransaction InstallResult.Refused(SnapshotRefusal.UNRESOLVED_ATTEMPTS, unresolved)

            if (snapshot.revision < state.confirmedRevision) return@withTransaction InstallResult.Refused(SnapshotRefusal.OLDER_REVISION)
            val storedDictionary = db.catalog().state()?.dictionaryRevision
            if (storedDictionary != null && snapshot.dictionaryRevision < storedDictionary) {
                return@withTransaction InstallResult.Refused(SnapshotRefusal.OLDER_DICTIONARY)
            }
            val newestReceipt = db.receipts().all().maxOfOrNull { it.revision }
            if (newestReceipt != null && snapshot.revision < newestReceipt) {
                return@withTransaction InstallResult.Refused(SnapshotRefusal.BEHIND_RECEIPT)
            }
            val aliases = db.aliases().all().mapTo(HashSet()) { it.aliasPublicId }
            if ((snapshot.listItems + snapshot.archivedItems).any { it.publicId.lowercase() in aliases }) {
                return@withTransaction InstallResult.Refused(SnapshotRefusal.ALIAS_CONTRADICTION)
            }

            LocalCatalog(db).replace(snapshot.catalog())
            replaceConfirmed(snapshot)
            db.syncState().put(SyncStateEntity(householdPublicId = bound, confirmedRevision = snapshot.revision, householdName = snapshot.householdName))
            val released = releaseCovered(snapshot.revision)
            rebuilder.rebuild()
            InstallResult.Installed(snapshot.revision, released)
        }

    private suspend fun replaceConfirmed(snapshot: ServerSnapshot) {
        db.confirmedItems().clear()
        db.confirmedItems().upsert(
            snapshot.listItems.mapIndexed { index, item ->
                ConfirmedItemEntity(
                    publicId = item.publicId,
                    name = item.name,
                    categoryPublicId = item.categoryPublicId,
                    status = item.status,
                    position = item.position,
                    purchasedAt = item.purchasedAtMicros?.let(ServerTime::millis),
                    createdAt = ServerTime.millis(item.createdAtMicros),
                    lockVersion = item.lockVersion,
                    tripPublicId = null,
                    normalizedName = item.normalizedName,
                    createdAtMicros = item.createdAtMicros,
                    updatedAtMicros = item.updatedAtMicros,
                    purchasedAtMicros = item.purchasedAtMicros,
                    serverOrder = index,
                )
            },
        )
        val history = db.history()
        history.clearArchived()
        history.clearTrips()
        history.clearHabituals()
        history.clearTombstones()
        history.insertTrips(snapshot.shoppingTrips.mapIndexed { i, t -> ShoppingTripEntity(t.publicId, t.finishedAtMicros, t.createdAtMicros, i) })
        history.insertArchived(
            snapshot.archivedItems.mapIndexed { i, a ->
                ArchivedItemEntity(
                    publicId = a.publicId, name = a.name, normalizedName = a.normalizedName, categoryPublicId = a.categoryPublicId,
                    status = a.status, position = a.position, purchasedAtMicros = a.purchasedAtMicros,
                    createdAtMicros = a.createdAtMicros, updatedAtMicros = a.updatedAtMicros, lockVersion = a.lockVersion,
                    tripPublicId = requireNotNull(a.tripPublicId), serverOrder = i,
                )
            },
        )
        history.insertHabituals(snapshot.habitualItems.mapIndexed { i, h -> HabitualItemEntity(h.publicId, h.name, h.normalizedName, h.createdAtMicros, i) })
        history.insertTombstones(snapshot.tombstones.mapIndexed { i, t -> TombstoneEntity(t.recordType, t.publicId, t.revision, i) })
    }

    /**
     * Deletes the consumed commands whose receipt the snapshot covers (`receipt.revision <= revision`), unless a
     * command prepared later still needs one to lend its version (a dependent with no frozen document yet). The same
     * rule as [net.ts.taild80ca0.atlas.twa.sync.ReceiptApplier]; aliases are never released.
     */
    private suspend fun releaseCovered(revision: Long): List<String> {
        val released = ArrayList<String>()
        for (command in db.commands().inOrder()) {
            if (command.status != CommandStatus.ACKNOWLEDGED.name) continue
            val receipt = db.receipts().find(command.id) ?: continue
            if (receipt.revision > revision) continue
            if (db.commands().dependentsOf(command.id).any { it.wireDocument == null }) continue
            db.commands().delete(command.id)
            released += command.id
        }
        return released
    }
}
