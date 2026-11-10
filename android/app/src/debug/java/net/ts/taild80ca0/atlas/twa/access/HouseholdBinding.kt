package net.ts.taild80ca0.atlas.twa.access

import androidx.room.withTransaction
import net.ts.taild80ca0.atlas.twa.data.db.AlvaDatabase
import net.ts.taild80ca0.atlas.twa.data.db.SyncStateEntity

/** Result of tying this device's local data to a household. */
sealed interface BindResult {
    /** The data now belongs to the household (first time) or already did. */
    data object Bound : BindResult

    /** The local data belongs to another household. Nothing was changed. */
    data object OtherHousehold : BindResult
}

/**
 * Which household the local database belongs to. A copy of the list, and the
 * queue that goes with it, belong to exactly one household; connecting to a
 * different one must neither merge into it nor wipe it.
 */
interface HouseholdBinding {
    suspend fun current(): String?

    /** Compare-and-set: binds when unbound, accepts the same household, refuses another. */
    suspend fun bind(householdPublicId: String): BindResult
}

/**
 * Keeps the binding in the existing `sync_state.household_public_id` column.
 *
 * It writes **only** that column's value. `confirmed_revision` is left exactly
 * as it was (0 when the row is first created): the revision is a claim that the
 * whole list was reconciled, and identifying the household proves no such thing.
 * Only the reconciliation (#45) may move it.
 */
class RoomHouseholdBinding(private val db: AlvaDatabase) : HouseholdBinding {
    override suspend fun current(): String? = db.syncState().get()?.householdPublicId

    override suspend fun bind(householdPublicId: String): BindResult = db.withTransaction {
        val state = db.syncState().get()
        when (state?.householdPublicId) {
            householdPublicId -> BindResult.Bound
            null -> {
                db.syncState().put(
                    SyncStateEntity(
                        householdPublicId = householdPublicId,
                        confirmedRevision = state?.confirmedRevision ?: 0L,
                    ),
                )
                BindResult.Bound
            }
            else -> BindResult.OtherHousehold
        }
    }
}
