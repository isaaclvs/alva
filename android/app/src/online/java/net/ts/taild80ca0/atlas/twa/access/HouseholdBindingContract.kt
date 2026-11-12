package net.ts.taild80ca0.atlas.twa.access

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

