package net.ts.taild80ca0.atlas.twa.access

import androidx.room.withTransaction
import net.ts.taild80ca0.atlas.twa.data.db.AlvaDatabase
import net.ts.taild80ca0.atlas.twa.data.db.SyncStateEntity

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
