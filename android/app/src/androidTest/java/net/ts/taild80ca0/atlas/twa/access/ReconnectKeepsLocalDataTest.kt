package net.ts.taild80ca0.atlas.twa.access

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import net.ts.taild80ca0.atlas.twa.data.db.AlvaDatabase
import net.ts.taild80ca0.atlas.twa.data.db.SyncStateEntity
import net.ts.taild80ca0.atlas.twa.data.list.IntentResult
import net.ts.taild80ca0.atlas.twa.data.list.ItemStatus
import net.ts.taild80ca0.atlas.twa.data.list.ListIntent
import net.ts.taild80ca0.atlas.twa.data.list.RoomShoppingListStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Revocation and reconnection with a real queue: whatever the user did offline
 * must still be there, byte for byte, after the device lost access and got it back.
 */
class ReconnectKeepsLocalDataTest {
    private val storage = RealAccessStorage()
    private val server = FakeAlvaServer()
    private lateinit var db: AlvaDatabase
    private lateinit var store: RoomShoppingListStore

    @Before
    fun open() {
        db = storage.openDatabase()
        store = RoomShoppingListStore(db, { java.util.UUID.randomUUID().toString() }, { System.currentTimeMillis() })
    }

    @After
    fun cleanup() {
        server.close()
        storage.cleanup()
    }

    private fun access(vault: CredentialVault = storage.newVault()) = HouseAccess(
        vault,
        HttpHouseAccessApi(ServerEndpoint.parse(server.baseUrl), 3_000, 5_000),
        RoomHouseholdBinding(db),
    )

    private suspend fun queueSomeWork(): IntentResult.Saved {
        val add = store.execute(ListIntent.AddItems(listOf("Leite", "Pão"))) as IntentResult.Saved
        store.execute(ListIntent.SetItemState(add.itemIds.first(), ItemStatus.PURCHASED))
        store.execute(ListIntent.AddItems(listOf("Café")))
        return add
    }

    @Test
    fun revocationThenReconnectionKeepsTheQueueTheProjectionAndTheRevision() = runBlocking {
        val access = access()
        assertTrue(access.connect(server.password) is ConnectResult.Connected)
        db.syncState().put(SyncStateEntity(householdPublicId = server.householdPublicId, confirmedRevision = 840))
        queueSomeWork()
        val queueBefore = db.commands().inOrder()
        val subjectsBefore = queueBefore.map { db.commands().subjectsOf(it.id) }
        val stateBefore = store.observe().first()
        val credentialBefore = (storage.newVault().read() as VaultRead.Found).access.active!!

        server.revoke(credentialBefore.deviceId)
        assertEquals(AccessError.Unauthorized, ((access.checkConnection() as ConnectResult.Failed).error as ConnectError.Server).error)
        assertEquals(AccessState.NeedsReconnect, access.state.value)
        assertEquals("revocation touches nothing local", queueBefore, db.commands().inOrder())

        assertTrue(access.connect(server.password) is ConnectResult.Connected)

        assertEquals(queueBefore, db.commands().inOrder())
        assertEquals(subjectsBefore, queueBefore.map { db.commands().subjectsOf(it.id) })
        assertEquals(stateBefore, store.observe().first())
        assertEquals(
            "connecting is not a reconciliation: the revision stays where it was",
            SyncStateEntity(householdPublicId = server.householdPublicId, confirmedRevision = 840),
            db.syncState().get(),
        )
        val fresh = (storage.newVault().read() as VaultRead.Found).access.active!!
        assertEquals(server.device(credentialBefore.deviceId).lineage, server.device(fresh.deviceId).lineage)
    }

    @Test
    fun aRestoredDatabaseWithoutItsCredentialReconnectsToTheSameHouseholdOnly() = runBlocking {
        assertTrue(access().connect(server.password) is ConnectResult.Connected)
        queueSomeWork()
        val queueBefore = db.commands().inOrder()
        // A restore brings the database back but never the credential (no_backup + Keystore).
        storage.vaultFile.delete()
        storage.deleteKey()

        val restored = access()
        assertEquals(AccessState.NeedsReconnect, restored.refresh())

        // A different household's server is refused: nothing is merged, nothing is deleted.
        val other = FakeAlvaServer(householdPublicId = "casa-de-outra-pessoa", password = "outra")
        try {
            val result = HouseAccess(
                storage.newVault(),
                HttpHouseAccessApi(ServerEndpoint.parse(other.baseUrl), 3_000, 5_000),
                RoomHouseholdBinding(db),
            ).connect("outra")
            assertEquals(ConnectResult.Failed(ConnectError.OtherHousehold), result)
            assertEquals(queueBefore, db.commands().inOrder())
            assertEquals(server.householdPublicId, db.syncState().get()!!.householdPublicId)
        } finally {
            other.close()
        }

        // The right household reconnects and the queue is intact.
        assertTrue(restored.connect(server.password) is ConnectResult.Connected)
        assertEquals(queueBefore, db.commands().inOrder())
        assertEquals(AccessState.Connected, restored.state.value)
    }

    @Test
    fun settingUpWithOfflineWorkAlreadyQueuedKeepsIt() = runBlocking {
        queueSomeWork() // the user added things before ever connecting
        val queueBefore = db.commands().inOrder()
        val stateBefore = store.observe().first()

        assertTrue(access().connect(server.password) is ConnectResult.Connected)

        assertEquals(queueBefore, db.commands().inOrder())
        assertEquals(stateBefore.items, store.observe().first().items)
        assertEquals(server.householdPublicId, db.syncState().get()!!.householdPublicId)
        assertEquals(0L, db.syncState().get()!!.confirmedRevision)
    }
}
