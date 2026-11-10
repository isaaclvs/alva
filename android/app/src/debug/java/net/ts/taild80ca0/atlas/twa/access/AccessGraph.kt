package net.ts.taild80ca0.atlas.twa.access

import android.content.Context
import net.ts.taild80ca0.atlas.twa.BuildConfig
import net.ts.taild80ca0.atlas.twa.data.db.AlvaDatabase
import net.ts.taild80ca0.atlas.twa.data.db.AlvaDatabaseFactory

/**
 * Wires access for the debug entry point. No dependency-injection framework (see
 * docs/plans/android-offline.md): one lazily built instance per process.
 */
object AccessGraph {
    @Volatile
    private var instance: HouseAccess? = null

    fun houseAccess(context: Context): HouseAccess =
        instance ?: synchronized(this) {
            instance ?: build(context.applicationContext).also { instance = it }
        }

    private fun build(context: Context): HouseAccess {
        val database: AlvaDatabase = AlvaDatabaseFactory.create(context)
        return HouseAccess(
            vault = KeystoreCredentialVault(KeystoreCredentialVault.defaultFile(context)),
            api = HttpHouseAccessApi(ServerEndpoint.parse(BuildConfig.SERVER_URL)),
            binding = RoomHouseholdBinding(database),
        )
    }
}
