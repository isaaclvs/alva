package net.ts.taild80ca0.atlas.twa.ui

import net.ts.taild80ca0.atlas.twa.access.BindResult
import net.ts.taild80ca0.atlas.twa.access.HouseholdBinding

/** Remembers the household in memory: the screens under test never need a database. */
class InMemoryHouseholdBinding : HouseholdBinding {
    private var household: String? = null

    override suspend fun current(): String? = household

    override suspend fun bind(householdPublicId: String): BindResult = when (household) {
        null, householdPublicId -> { household = householdPublicId; BindResult.Bound }
        else -> BindResult.OtherHousehold
    }
}
