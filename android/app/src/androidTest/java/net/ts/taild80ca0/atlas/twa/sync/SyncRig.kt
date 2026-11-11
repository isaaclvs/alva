package net.ts.taild80ca0.atlas.twa.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.util.UUID
import net.ts.taild80ca0.atlas.twa.access.AccessCredential
import net.ts.taild80ca0.atlas.twa.access.ApiResult
import net.ts.taild80ca0.atlas.twa.access.CredentialVault
import net.ts.taild80ca0.atlas.twa.access.HouseAccess
import net.ts.taild80ca0.atlas.twa.access.HouseAccessApi
import net.ts.taild80ca0.atlas.twa.access.HouseholdIdentity
import net.ts.taild80ca0.atlas.twa.access.RoomHouseholdBinding
import net.ts.taild80ca0.atlas.twa.access.StoredAccess
import net.ts.taild80ca0.atlas.twa.access.VaultRead
import net.ts.taild80ca0.atlas.twa.data.db.AlvaDatabase
import net.ts.taild80ca0.atlas.twa.data.db.AlvaDatabaseFactory
import net.ts.taild80ca0.atlas.twa.data.list.IdGenerator
import net.ts.taild80ca0.atlas.twa.data.list.ListState
import net.ts.taild80ca0.atlas.twa.data.list.RoomShoppingListStore
import net.ts.taild80ca0.atlas.twa.data.list.WireOutbox
import net.ts.taild80ca0.atlas.twa.data.list.WirePreparer
import net.ts.taild80ca0.atlas.twa.data.TickingClock
import kotlinx.coroutines.flow.first

class MemoryVault(var access: StoredAccess?) : CredentialVault {
    override fun read(): VaultRead = access?.let { VaultRead.Found(it) } ?: VaultRead.Empty
    override fun write(access: StoredAccess) {
        this.access = access
    }
}

private val neverUsedApi = object : HouseAccessApi {
    override suspend fun pair(password: String, deviceName: String, previousToken: String?): ApiResult<AccessCredential> =
        error("pairing is not part of syncing")

    override suspend fun identifyHousehold(credential: AccessCredential): ApiResult<HouseholdIdentity> =
        error("identifying is not part of syncing")
}

/**
 * One device on its own real Room file: a store, a bound household, a credential and a sync cycle over the given
 * transports. Two rigs are two phones. [close] removes the file.
 */
class SyncRig(
    house: String,
    commands: CommandTransport,
    snapshots: SnapshotTransport,
    token: String = "segredo-${UUID.randomUUID()}",
    connected: Boolean = true,
    ids: IdGenerator = IdGenerator { UUID.randomUUID().toString() },
) {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val fileName = "rig-${UUID.randomUUID()}.db"
    val db: AlvaDatabase = AlvaDatabaseFactory.create(context, fileName)
    val vault = MemoryVault(if (connected) StoredAccess(active = AccessCredential(1, token)) else null)
    val access = HouseAccess(vault, neverUsedApi, RoomHouseholdBinding(db))
    val store = RoomShoppingListStore(db, ids, TickingClock())
    val cycle = SyncCycle(
        db = db,
        sender = CommandSender(WirePreparer(db), WireOutbox(db), access, commands),
        applier = ReceiptApplier(db),
        snapshots = snapshots,
        installer = SnapshotInstaller(db),
        access = access,
    )

    init {
        kotlinx.coroutines.runBlocking { RoomHouseholdBinding(db).bind(house) }
    }

    suspend fun state(): ListState = store.observe().first()

    suspend fun names(): List<Pair<String, Boolean>> =
        state().items.map { it.name to (it.status == net.ts.taild80ca0.atlas.twa.data.list.ItemStatus.PURCHASED) }

    fun close() {
        if (db.isOpen) db.close()
        context.deleteDatabase(fileName)
    }
}
