package net.ts.taild80ca0.atlas.twa.access

import android.content.Context
import androidx.work.WorkManager
import kotlinx.coroutines.launch
import net.ts.taild80ca0.atlas.twa.sync.BackgroundSync
import net.ts.taild80ca0.atlas.twa.sync.ForegroundInvalidation
import net.ts.taild80ca0.atlas.twa.sync.SyncingShoppingListStore
import net.ts.taild80ca0.atlas.twa.data.list.RoomShoppingListStore
import net.ts.taild80ca0.atlas.twa.data.list.ConflictResolver
import net.ts.taild80ca0.atlas.twa.data.list.IdGenerator
import net.ts.taild80ca0.atlas.twa.data.list.Clock
import net.ts.taild80ca0.atlas.twa.data.list.ShoppingListStore
import net.ts.taild80ca0.atlas.twa.BuildConfig
import net.ts.taild80ca0.atlas.twa.data.db.AlvaDatabase
import net.ts.taild80ca0.atlas.twa.data.db.AlvaDatabaseFactory
import net.ts.taild80ca0.atlas.twa.data.list.WireOutbox
import net.ts.taild80ca0.atlas.twa.data.list.WirePreparer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import net.ts.taild80ca0.atlas.twa.online.OnlineAccess
import net.ts.taild80ca0.atlas.twa.online.OnlineRepository
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
    private var conflictResolver: ConflictResolver? = null
    @Volatile private var foregroundInvalidation: ForegroundInvalidation? = null
    private var onlineRepository: OnlineRepository? = null

    /** True while a test installed its own graph: then nothing is built lazily (a test must never reach the real server). */
    private var overridden = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var background: BackgroundSync? = null
    private var recoveryStarted = false

    @Synchronized
    private fun background(context: Context): BackgroundSync =
        background ?: BackgroundSync(WorkManager.getInstance(context.applicationContext)).also { background = it }

    @Synchronized
    private fun database(context: Context): AlvaDatabase =
        database ?: AlvaDatabaseFactory.create(context.applicationContext).also { database = it }

    @Synchronized
    fun shoppingListStore(context: Context): ShoppingListStore =
        listStore ?: RoomShoppingListStore(database(context)).let { local ->
            if (overridden) local else SyncingShoppingListStore(local, background(context)) { foregroundInvalidation?.localChange() }
        }.also { listStore = it }

    fun houseAccess(context: Context): HouseAccess =
        instance ?: synchronized(this) {
            instance ?: build(context.applicationContext).also { instance = it }
        }

    /** The one controller of this process, shared by the screens and the persistent worker. */
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
            SyncController(cycle, scope).also { syncController = it }
        }

    /**
     * The online client the screens use: the server's list and actions that wait for its answer. Holds nothing on
     * disk. Null only while a test installed a graph without one.
     */
    @Synchronized
    fun onlineRepository(context: Context): OnlineRepository? {
        if (overridden) return onlineRepository
        return onlineRepository ?: run {
            val endpoint = ServerEndpoint.parse(BuildConfig.SERVER_URL)
            OnlineRepository(
                OnlineAccess.of(houseAccess(context)), HttpSnapshotTransport(endpoint), HttpCommandTransport(endpoint), scope,
            )
        }.also { onlineRepository = it }
    }

    /** The ActionCable signal, which asks the online repository to read the list again. */
    @Synchronized
    fun foregroundInvalidation(context: Context): ForegroundInvalidation? {
        if (overridden) return foregroundInvalidation
        return foregroundInvalidation ?: ForegroundInvalidation(
            houseAccess(context), ServerEndpoint.parse(BuildConfig.SERVER_URL), onlineRepository(context)!!, scope,
        ).also { foregroundInvalidation = it }
    }

    @Synchronized
    fun conflictResolver(context: Context): ConflictResolver? {
        if (overridden) return conflictResolver
        return conflictResolver ?: ConflictResolver(
            database(context), IdGenerator { java.util.UUID.randomUUID().toString() }, Clock { System.currentTimeMillis() },
        ).also { conflictResolver = it }
    }

    /** Called by the app entry, never by a worker: worker startup must not schedule another worker. */
    @Synchronized
    fun recoverBackgroundSync(context: Context) {
        if (overridden || recoveryStarted) return
        recoveryStarted = true
        val scheduler = background(context)
        scope.launch { scheduler.recover() }
    }

    /** Test hook: replaces (or, with null, drops) the instance, like a new process would. */
    fun install(
        access: HouseAccess?,
        store: ShoppingListStore? = null,
        sync: SyncController? = null,
        foreground: ForegroundInvalidation? = null,
        conflicts: ConflictResolver? = null,
        online: OnlineRepository? = null,
    ) {
        synchronized(this) {
            foregroundInvalidation?.stop()
            foregroundInvalidation = foreground
            instance = access
            listStore = store
            syncController = sync
            conflictResolver = conflicts
            onlineRepository = online
            overridden = access != null
            recoveryStarted = false
            background = null
        }
    }

    private fun build(context: Context): HouseAccess {
        val database = database(context)
        return HouseAccess(
            vault = KeystoreCredentialVault(KeystoreCredentialVault.defaultFile(context)),
            api = HttpHouseAccessApi(ServerEndpoint.parse(BuildConfig.SERVER_URL)),
            binding = RoomHouseholdBinding(database),
            // The online client reads the list when the screen shows it; nothing is scheduled for later.
            onConnected = {},
            onSignedOut = { onlineRepository?.forget() },
        )
    }
}
