package net.ts.taild80ca0.atlas.twa.access

/** The shared scenarios with in-memory storage: fast feedback on the access logic over real HTTP. */
class HouseAccessJvmTest : HouseAccessScenarios() {
    override fun createVault(): CredentialVault = InMemoryVault()

    override fun createBinding(): HouseholdBinding = InMemoryBinding()
}
