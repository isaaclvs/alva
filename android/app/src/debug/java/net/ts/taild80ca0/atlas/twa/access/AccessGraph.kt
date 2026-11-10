package net.ts.taild80ca0.atlas.twa.access

import android.content.Context
import net.ts.taild80ca0.atlas.twa.data.list.RoomShoppingListStore
import net.ts.taild80ca0.atlas.twa.data.list.ShoppingListStore
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
    private var database: AlvaDatabase? = null
    private var listStore: ShoppingListStore? = null

    @Synchronized
    private fun database(context: Context): AlvaDatabase =
        database ?: AlvaDatabaseFactory.create(context.applicationContext).also { database = it }

    @Synchronized
    fun shoppingListStore(context: Context): ShoppingListStore =
        listStore ?: RoomShoppingListStore(database(context)).also { listStore = it }

    fun houseAccess(context: Context): HouseAccess =
        instance ?: synchronized(this) {
            instance ?: build(context.applicationContext).also { instance = it }
        }

    /** Test hook: replaces (or, with null, drops) the instance, like a new process would. */
    fun install(access: HouseAccess?, store: ShoppingListStore? = null) {
        synchronized(this) {
            instance = access
            listStore = store
        }
    }

    private fun build(context: Context): HouseAccess {
        val database = database(context)
        return HouseAccess(
            vault = KeystoreCredentialVault(KeystoreCredentialVault.defaultFile(context)),
            api = HttpHouseAccessApi(ServerEndpoint.parse(BuildConfig.SERVER_URL)),
            binding = RoomHouseholdBinding(database),
        )
    }
}
