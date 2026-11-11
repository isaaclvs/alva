package net.ts.taild80ca0.atlas.twa.access

import android.content.Context
import net.ts.taild80ca0.atlas.twa.data.list.RoomShoppingListStore
import net.ts.taild80ca0.atlas.twa.data.list.ShoppingListStore
import net.ts.taild80ca0.atlas.twa.BuildConfig
import net.ts.taild80ca0.atlas.twa.data.db.AlvaDatabase
import net.ts.taild80ca0.atlas.twa.data.db.AlvaDatabaseFactory
import net.ts.taild80ca0.atlas.twa.data.list.WireOutbox
import net.ts.taild80ca0.atlas.twa.data.list.WirePreparer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import net.ts.taild80ca0.atlas.twa.sync.CommandSender
import net.ts.taild80ca0.atlas.twa.sync.HttpCommandTransport
import net.ts.taild80ca0.atlas.twa.sync.HttpSnapshotTransport
import net.ts.taild80ca0.atlas.twa.sync.ReceiptApplier
import net.ts.taild80ca0.atlas.twa.sync.SnapshotInstaller
import net.ts.taild80ca0.atlas.twa.sync.SyncController
import net.ts.taild80ca0.atlas.twa.sync.SyncCycle

/**
 * Wires access for the debug entry point. No dependency-injection framework (see
 * docs/plans/android-offline.md): one lazily built instance per process.
 */
object AccessGraph {
    @Volatile
    private var instance: HouseAccess? = null
    private var database: AlvaDatabase? = null
    private var listStore: ShoppingListStore? = null
    private var syncController: SyncController? = null

    /** True while a test installed its own graph: then nothing is built lazily (a test must never reach the real server). */
    private var overridden = false

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

    /** The one controller of this process: opening/resuming and the button ask it for a sync cycle. */
    @Synchronized
    fun syncController(context: Context): SyncController? =
        syncController ?: if (overridden) null else run {
            val db = database(context)
            val endpoint = ServerEndpoint.parse(BuildConfig.SERVER_URL)
            val access = houseAccess(context)
            val cycle = SyncCycle(
                db = db,
                sender = CommandSender(WirePreparer(db), WireOutbox(db), access, HttpCommandTransport(endpoint)),
                applier = ReceiptApplier(db),
                snapshots = HttpSnapshotTransport(endpoint),
                installer = SnapshotInstaller(db),
                access = access,
            )
            SyncController(cycle, CoroutineScope(SupervisorJob() + Dispatchers.Default)).also { syncController = it }
        }

    /** Test hook: replaces (or, with null, drops) the instance, like a new process would. */
    fun install(access: HouseAccess?, store: ShoppingListStore? = null, sync: SyncController? = null) {
        synchronized(this) {
            instance = access
            listStore = store
            syncController = sync
            overridden = access != null
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
