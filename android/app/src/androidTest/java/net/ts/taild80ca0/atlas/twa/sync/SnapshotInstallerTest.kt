package net.ts.taild80ca0.atlas.twa.sync

import kotlinx.coroutines.runBlocking
import net.ts.taild80ca0.atlas.twa.access.RoomHouseholdBinding
import net.ts.taild80ca0.atlas.twa.data.DatabaseTest
import net.ts.taild80ca0.atlas.twa.data.ScriptedIds
import net.ts.taild80ca0.atlas.twa.data.db.AlvaDatabase
import net.ts.taild80ca0.atlas.twa.data.db.ItemStatusCodes
import net.ts.taild80ca0.atlas.twa.data.db.SyncAliasEntity
import net.ts.taild80ca0.atlas.twa.data.list.AttemptResult
import net.ts.taild80ca0.atlas.twa.data.list.CommandStatus
import net.ts.taild80ca0.atlas.twa.data.list.IntentResult
import net.ts.taild80ca0.atlas.twa.data.list.ItemStatus
import net.ts.taild80ca0.atlas.twa.data.list.ListIntent
import net.ts.taild80ca0.atlas.twa.data.list.PreparationResult
import net.ts.taild80ca0.atlas.twa.data.list.WireOutbox
import net.ts.taild80ca0.atlas.twa.data.list.WirePreparer
import net.ts.taild80ca0.atlas.twa.sync.SnapshotFixtures.DAIRY
import net.ts.taild80ca0.atlas.twa.sync.SnapshotFixtures.HOUSE
import net.ts.taild80ca0.atlas.twa.sync.SnapshotFixtures.TRIP
import net.ts.taild80ca0.atlas.twa.sync.SnapshotFixtures.item
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Installing a snapshot (issue #45, fifth cut), on a real Room file. Each refusal must leave the whole
 * database exactly as it was; each install must leave the confirmed state equal to the snapshot, keep every
 * queued intention on top of it, and keep the aliases the server revealed.
 */
class SnapshotInstallerTest : DatabaseTest() {

    private val milk = "a0000000-0000-4000-8000-0000000000a1"
    private val coffee = "a0000000-0000-4000-8000-0000000000a2"
    private val rice = "a0000000-0000-4000-8000-0000000000a3"
    private val local = "9a1d0000-0000-4000-8000-000000000001"
    private fun cmd(n: Int) = "5f0c2f0e-0000-4000-8000-00000000000$n"

    private fun parsed(json: JSONObject): ServerSnapshot =
        (SnapshotParser.parse(SnapshotFixtures.bytes(json)) as SnapshotParse.Valid).snapshot

    private fun snap(revision: Long = 840, dictionary: Long = 12, items: List<JSONObject> = emptyList(), house: String = HOUSE,
        archived: List<JSONObject> = emptyList(), trips: List<JSONObject> = emptyList(), habituals: List<JSONObject> = emptyList(),
        tombstones: List<JSONObject> = emptyList()) =
        parsed(SnapshotFixtures.snapshot(revision, dictionary, house, items, archived, trips, habituals, tombstones))

    private suspend fun bound(db: AlvaDatabase) = RoomHouseholdBinding(db).bind(HOUSE)

    /** Everything an install could touch, as comparable text. */
    private suspend fun world(db: AlvaDatabase): List<Any?> = listOf(
        db.syncState().get(), db.categories().all(), db.catalog().entries(), db.catalog().state(),
        db.confirmedItems().all(), db.listItems().all(), db.commands().inOrder(), db.receipts().all(), db.aliases().all(),
        db.history().archived(), db.history().trips(), db.history().habituals(), db.history().tombstones(),
    )

    private suspend fun refused(db: AlvaDatabase, snapshot: ServerSnapshot, reason: SnapshotRefusal): InstallResult.Refused {
        val before = world(db)
        val result = SnapshotInstaller(db).install(snapshot)
        assertTrue("expected $reason, got $result", result is InstallResult.Refused)
        assertEquals(reason, (result as InstallResult.Refused).reason)
        assertEquals("a refusal must change nothing", before, world(db))
        return result
    }

    /** Queues an add and consumes it with a receipt, the way the sender would; the command stays `ACKNOWLEDGED`. */
    private suspend fun addAndAcknowledge(db: AlvaDatabase, name: String, itemId: String, commandId: String, revision: Long) {
        val store = storeOn(db, ScriptedIds(itemId, commandId))
        assertTrue(store.execute(ListIntent.AddItems(listOf(name))) is IntentResult.Saved)
        assertTrue(WirePreparer(db).prepare(commandId) is PreparationResult.Prepared)
        val attempt = (WireOutbox(db).beginAttempt(commandId) as AttemptResult.Started).attempt
        val data = """{"items":[{"public_id":"$itemId","canonical_public_id":"$itemId","name":"$name","status":"pending","purchased_at":null,"lock_version":0,"outcome":"created"}]}"""
        val result = ReceiptApplier(db).apply(commandId, attempt, CommandReceipt(commandId, ReceiptStatus.APPLIED, null, revision, null, data, revision, false))
        assertTrue("$result", result is ApplyResult.Acknowledged)
    }

    // ---- bootstrap -----------------------------------------------------------------------

    @Test
    fun anEmptyHouseholdBootstrapsTheCategoriesAndTheDictionary() = runBlocking<Unit> {
        val db = openDatabase()
        bound(db)

        val result = SnapshotInstaller(db).install(snap(revision = 0, dictionary = 3))

        assertEquals(InstallResult.Installed(0, emptyList()), result)
        assertEquals(listOf("Laticínios", "Outros"), db.categories().all().map { it.name })
        assertEquals(listOf("leite"), db.catalog().entries().map { it.normalizedName })
        assertEquals(3L, db.catalog().state()?.dictionaryRevision)
        assertEquals("Casa", db.syncState().get()?.householdName)
        assertEquals(HOUSE, db.syncState().get()?.householdPublicId)
        assertTrue(db.listItems().all().isEmpty())
    }

    @Test
    fun theSnapshotAlsoFillsAnEmptyDeviceWithTheHouseholdsList() = runBlocking<Unit> {
        val db = openDatabase()
        bound(db)
        // No queue, no list: this is exactly the device that must not conclude "nothing to sync".
        assertEquals(0, db.commands().count())

        SnapshotInstaller(db).install(snap(items = listOf(item(milk, "Leite"), item(coffee, "Café", status = "purchased", purchasedAt = "2026-11-10T13:00:00.5Z", lockVersion = 2))))

        val shown = db.listItems().live()
        assertEquals(listOf("Leite", "Café"), shown.map { it.name })
        assertEquals(listOf(ItemStatusCodes.PENDING, ItemStatusCodes.PURCHASED), shown.map { it.status })
        assertEquals(2, shown[1].lockVersion)
        assertEquals(840L, db.syncState().get()?.confirmedRevision)
    }

    // ---- what is kept ---------------------------------------------------------------------

    @Test
    fun keepsWhatTheServerSaidWithItsPrecisionAndOrder() = runBlocking<Unit> {
        val db = openDatabase()
        bound(db)
        val first = item(coffee, "Café", createdAt = "2026-11-10T12:00:00.123456Z", updatedAt = "2026-11-10T12:30:00.000007Z", lockVersion = 1)
        val second = item(milk, "Leite", createdAt = "2026-11-10T12:00:00.123457Z")

        SnapshotInstaller(db).install(
            snap(
                items = listOf(first, second),
                archived = listOf(item(rice, "Arroz", status = "purchased", purchasedAt = "2026-11-09T17:00:00.000009Z", trip = TRIP, lockVersion = 2)),
                trips = listOf(SnapshotFixtures.trip(finishedAt = "2026-11-09T18:00:00.000001Z", createdAt = "2026-11-09T18:00:00.000002Z")),
                habituals = listOf(SnapshotFixtures.habitual("b0000000-0000-4000-8000-000000000001", "Pão")),
                tombstones = listOf(SnapshotFixtures.tombstone("d0000000-0000-4000-8000-000000000001", 838)),
            ),
        )

        val confirmed = db.confirmedItems().all()
        assertEquals(listOf(coffee, milk), confirmed.map { it.publicId })
        assertEquals(listOf(0, 1), confirmed.map { it.serverOrder })
        assertEquals("café", confirmed[0].normalizedName)
        assertEquals(ServerTime.parseMicros("2026-11-10T12:00:00.123456Z"), confirmed[0].createdAtMicros)
        assertEquals(ServerTime.parseMicros("2026-11-10T12:30:00.000007Z"), confirmed[0].updatedAtMicros)
        // Two instants of the same millisecond stay distinguishable.
        assertEquals(confirmed[0].createdAt, confirmed[1].createdAt)
        assertTrue(confirmed[0].createdAtMicros!! < confirmed[1].createdAtMicros!!)
        val archived = db.history().archived().single()
        assertEquals(TRIP, archived.tripPublicId)
        assertEquals(ServerTime.parseMicros("2026-11-09T17:00:00.000009Z"), archived.purchasedAtMicros)
        assertEquals(ServerTime.parseMicros("2026-11-09T18:00:00.000002Z"), db.history().trips().single().createdAtMicros)
        assertEquals("pão", db.history().habituals().single().normalizedName)
        assertEquals(838L, db.history().tombstones().single().revision)
        // History is not on the live list nor in the projection the screens read.
        assertEquals(listOf("Café", "Leite"), db.listItems().all().map { it.name })
    }

    @Test
    fun whatTheSnapshotDoesNotHaveIsGoneButTheQueueIsNeverTouched() = runBlocking<Unit> {
        val db = openDatabase()
        bound(db)
        SnapshotInstaller(db).install(snap(revision = 840, items = listOf(item(milk, "Leite"), item(coffee, "Café"))))
        val store = storeOn(db, ScriptedIds(local, cmd(1)))
        store.execute(ListIntent.AddItems(listOf("Arroz")))
        val queued = db.commands().inOrder()

        // Another device removed the milk; the next snapshot no longer lists it.
        SnapshotInstaller(db).install(snap(revision = 845, items = listOf(item(coffee, "Café", lockVersion = 1)), tombstones = listOf(SnapshotFixtures.tombstone(milk, 842))))

        assertEquals(listOf(coffee), db.confirmedItems().all().map { it.publicId })
        assertEquals(setOf("Café", "Arroz"), db.listItems().live().map { it.name }.toSet())
        assertEquals("the unsent add is still queued, byte for byte", queued, db.commands().inOrder())
        assertEquals(845L, db.syncState().get()?.confirmedRevision)
    }

    @Test
    fun pendingIntentionsAreReplayedOverTheNewConfirmedState() = runBlocking<Unit> {
        val db = openDatabase()
        bound(db)
        SnapshotInstaller(db).install(snap(items = listOf(item(milk, "Leite", lockVersion = 3))))
        val store = storeOn(db, ScriptedIds(cmd(1), local, cmd(2)))
        assertTrue(store.execute(ListIntent.SetItemState(milk, ItemStatus.PURCHASED)) is IntentResult.Saved)
        assertTrue(store.execute(ListIntent.AddItems(listOf("Arroz"))) is IntentResult.Saved)

        // A newer snapshot arrives with the milk still pending (the mark has not been sent) and a coffee from elsewhere.
        SnapshotInstaller(db).install(snap(revision = 850, items = listOf(item(milk, "Leite", lockVersion = 3), item(coffee, "Café"))))

        val shown = db.listItems().live().associateBy { it.name }
        assertEquals("the unsent mark still shows", ItemStatusCodes.PURCHASED, shown.getValue("Leite").status)
        assertEquals("the unsent add still shows", local, shown.getValue("Arroz").publicId)
        assertNotNull(shown["Café"])
        assertEquals(listOf(CommandStatus.PENDING.name, CommandStatus.PENDING.name), db.commands().inOrder().map { it.status })
    }

    @Test
    fun aConsumedCommandCoveredByTheSnapshotIsReleasedAndItsAliasKept() = runBlocking<Unit> {
        val db = openDatabase()
        bound(db)
        addAndAcknowledge(db, "Leite", local, cmd(1), revision = 841)
        db.aliases().insert(SyncAliasEntity("fffffff0-0000-4000-8000-000000000001", local, cmd(1), 841))
        assertEquals(listOf("Leite"), db.listItems().live().map { it.name })

        val result = SnapshotInstaller(db).install(snap(revision = 841, items = listOf(item(local, "Leite"))))

        assertEquals(InstallResult.Installed(841, listOf(cmd(1))), result)
        assertEquals(0, db.commands().count())
        assertEquals(0, db.receipts().all().size)
        assertEquals("aliases outlive the command", 1, db.aliases().all().size)
        assertEquals(listOf(local), db.listItems().live().map { it.publicId })
    }

    @Test
    fun aConsumedCommandIsKeptWhileALaterOneStillNeedsItToLendItsVersion() = runBlocking<Unit> {
        val db = openDatabase()
        bound(db)
        addAndAcknowledge(db, "Leite", local, cmd(1), revision = 841)
        // Marked right after adding, before the add was even sent: depends on cmd(1), not prepared yet.
        val store = storeOn(db, ScriptedIds(cmd(2)))
        store.execute(ListIntent.SetItemState(local, ItemStatus.PURCHASED))
        assertEquals(cmd(1), db.commands().find(cmd(2))?.dependsOn)

        val result = SnapshotInstaller(db).install(snap(revision = 842, items = listOf(item(local, "Leite"))))

        assertEquals(InstallResult.Installed(842, emptyList()), result)
        assertEquals(listOf(cmd(1), cmd(2)), db.commands().inOrder().map { it.id })
        assertEquals(CommandStatus.ACKNOWLEDGED.name, db.commands().find(cmd(1))?.status)
        assertEquals("the mark still shows over the snapshot", ItemStatusCodes.PURCHASED, db.listItems().find(local)?.status)
    }

    @Test
    fun installingTheSameSnapshotAgainChangesNothing() = runBlocking<Unit> {
        val db = openDatabase()
        bound(db)
        val snapshot = snap(items = listOf(item(milk, "Leite")), archived = listOf(item(rice, "Arroz", trip = TRIP)), trips = listOf(SnapshotFixtures.trip()))
        SnapshotInstaller(db).install(snapshot)
        val before = world(db)

        assertEquals(InstallResult.Installed(840, emptyList()), SnapshotInstaller(db).install(snapshot))

        assertEquals(before, world(db))
    }

    // ---- refusals --------------------------------------------------------------------------

    @Test
    fun aDeviceThatNeverConnectedInstallsNothing() = runBlocking<Unit> {
        val db = openDatabase()
        refused(db, snap(items = listOf(item(milk, "Leite"))), SnapshotRefusal.NOT_BOUND)
        assertNull(db.syncState().get())
    }

    @Test
    fun anotherHouseholdsSnapshotIsNeverInstalled() = runBlocking<Unit> {
        val db = openDatabase()
        bound(db)
        SnapshotInstaller(db).install(snap(items = listOf(item(milk, "Leite"))))
        storeOn(db, ScriptedIds(local, cmd(1))).execute(ListIntent.AddItems(listOf("Arroz")))

        refused(db, snap(revision = 9_000, house = "20000000-0000-4000-8000-000000000002", items = listOf(item(coffee, "Outro"))), SnapshotRefusal.OTHER_HOUSEHOLD)

        assertEquals(setOf("Leite", "Arroz"), db.listItems().live().map { it.name }.toSet())
    }

    @Test
    fun anOlderSnapshotNeverReplacesNewerKnowledge() = runBlocking<Unit> {
        val db = openDatabase()
        bound(db)
        SnapshotInstaller(db).install(snap(revision = 900, dictionary = 14, items = listOf(item(milk, "Leite", lockVersion = 4))))

        refused(db, snap(revision = 899, dictionary = 14, items = listOf(item(milk, "Leite", lockVersion = 3))), SnapshotRefusal.OLDER_REVISION)
        refused(db, snap(revision = 901, dictionary = 13, items = listOf(item(milk, "Leite", lockVersion = 5))), SnapshotRefusal.OLDER_DICTIONARY)

        assertEquals(4, db.listItems().find(milk)?.lockVersion)
    }

    @Test
    fun aSnapshotOlderThanAReceiptThisDeviceHoldsIsNotWhatTheServerHasNow() = runBlocking<Unit> {
        val db = openDatabase()
        bound(db)
        addAndAcknowledge(db, "Leite", local, cmd(1), revision = 841)

        refused(db, snap(revision = 840), SnapshotRefusal.BEHIND_RECEIPT)

        assertEquals(CommandStatus.ACKNOWLEDGED.name, db.commands().find(cmd(1))?.status)
        assertEquals(listOf("Leite"), db.listItems().live().map { it.name })
    }

    @Test
    fun anAttemptTheServerNeverAnsweredKeepsEverythingWhereItIs() = runBlocking<Unit> {
        val db = openDatabase()
        bound(db)
        SnapshotInstaller(db).install(snap(revision = 840, items = listOf(item(milk, "Leite"))))
        val store = storeOn(db, ScriptedIds(local, cmd(1)))
        store.execute(ListIntent.AddItems(listOf("Arroz")))
        assertTrue(WirePreparer(db).prepare(cmd(1)) is PreparationResult.Prepared)
        WireOutbox(db).beginAttempt(cmd(1)) // sent; the answer was lost

        // The server may have applied it and another device may have removed the milk since: the snapshot says nothing of either.
        val result = refused(db, snap(revision = 870, items = listOf(item(coffee, "Café"))), SnapshotRefusal.UNRESOLVED_ATTEMPTS)

        assertEquals(listOf(cmd(1)), result.commandIds)
        assertEquals(CommandStatus.IN_FLIGHT.name, db.commands().find(cmd(1))?.status)
        assertEquals(840L, db.syncState().get()?.confirmedRevision)
        assertEquals(setOf("Leite", "Arroz"), db.listItems().live().map { it.name }.toSet())
    }

    @Test
    fun aSnapshotThatContradictsAnAliasTheServerRevealedIsRefused() = runBlocking<Unit> {
        val db = openDatabase()
        bound(db)
        db.aliases().insert(SyncAliasEntity(milk, coffee, cmd(1), 841))

        refused(db, snap(items = listOf(item(milk, "Leite"))), SnapshotRefusal.ALIAS_CONTRADICTION)
    }

    @Test
    fun aFailingStatementRollsTheWholeInstallBackAndThePartsAreNotLeftHalfWritten() = runBlocking<Unit> {
        val db = openDatabase()
        bound(db)
        SnapshotInstaller(db).install(snap(revision = 840, dictionary = 12, items = listOf(item(milk, "Leite"))))
        storeOn(db, ScriptedIds(local, cmd(1))).execute(ListIntent.AddItems(listOf("Arroz")))
        val before = world(db)
        // The last thing the install writes: a trigger makes that insert fail after everything before it succeeded.
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER no_tombstones BEFORE INSERT ON tombstones BEGIN SELECT RAISE(ABORT, 'boom'); END",
        )

        try {
            SnapshotInstaller(db).install(
                snap(revision = 900, dictionary = 13, items = listOf(item(coffee, "Café")), tombstones = listOf(SnapshotFixtures.tombstone(milk, 880))),
            )
            fail("the failing insert must propagate")
        } catch (expected: android.database.SQLException) {
            // rolled back
        }

        assertEquals("nothing of the snapshot may remain", before, world(db))
        assertEquals(12L, db.catalog().state()?.dictionaryRevision)
        assertFalse(db.confirmedItems().all().any { it.publicId == coffee })
    }
}
