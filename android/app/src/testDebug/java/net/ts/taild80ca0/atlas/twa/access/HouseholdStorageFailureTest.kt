package net.ts.taild80ca0.atlas.twa.access

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class HouseholdStorageFailureTest {
    @Test fun failedHouseholdIdentityWriteKeepsPairingRecoverableAndReportsStorageFailure() = runBlocking {
        var stored: StoredAccess? = null
        val credential = AccessCredential(1, "test-only")
        val vault = object : CredentialVault {
            override fun read() = stored?.let { VaultRead.Found(it) } ?: VaultRead.Empty
            override fun write(access: StoredAccess) { stored = access }
        }
        val api = object : HouseAccessApi {
            override suspend fun pair(password: String, deviceName: String, previousToken: String?) = ApiResult.Ok(credential)
            override suspend fun identifyHousehold(credential: AccessCredential) = ApiResult.Ok(HouseholdIdentity("test-house"))
        }
        val binding = object : HouseholdBinding {
            override suspend fun current(): String? = null
            override suspend fun bind(householdPublicId: String): BindResult = throw java.io.IOException("disk unavailable")
        }
        val access = HouseAccess(vault, api, binding)
        assertEquals(ConnectResult.Failed(ConnectError.StorageFailed), access.connect("test-only-password"))
        assertEquals(credential, stored!!.pending)
    }
}
