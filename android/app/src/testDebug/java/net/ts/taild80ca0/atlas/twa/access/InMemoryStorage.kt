package net.ts.taild80ca0.atlas.twa.access

/** JVM-only stand-ins for the Keystore vault and the Room binding (the instrumented tests use the real ones). */
class InMemoryVault : CredentialVault {
    private var stored: StoredAccess? = null

    override fun read(): VaultRead = stored?.let { VaultRead.Found(it) } ?: VaultRead.Empty

    override fun write(access: StoredAccess) {
        stored = access
    }
}

class InMemoryBinding : HouseholdBinding {
    private var household: String? = null

    override suspend fun current(): String? = household

    override suspend fun bind(householdPublicId: String): BindResult = when (household) {
        householdPublicId -> BindResult.Bound
        null -> {
            household = householdPublicId
            BindResult.Bound
        }
        else -> BindResult.OtherHousehold
    }
}
