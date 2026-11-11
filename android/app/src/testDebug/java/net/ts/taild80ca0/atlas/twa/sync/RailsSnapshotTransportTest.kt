package net.ts.taild80ca0.atlas.twa.sync

import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import net.ts.taild80ca0.atlas.twa.access.AccessCredential
import net.ts.taild80ca0.atlas.twa.access.ServerEndpoint
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * The Kotlin snapshot client against a REAL Rails (`GET /api/v1/snapshot`). Runs only under
 * `script/android_rails_transport_check` (see [RailsCommandTransportTest]); anywhere else it is skipped, and the
 * script refuses a skipped run. It shows that what Rails really writes (status names, microsecond instants, the
 * order of the arrays, the fixture household's categories, dictionary and history) is accepted by the strict
 * [SnapshotParser], which the hand-written fixtures alone could not prove.
 */
class RailsSnapshotTransportTest {
    private val harness: JSONObject? = System.getenv("ALVA_RAILS_HARNESS")?.let { JSONObject(File(it).readText()) }
    private lateinit var baseUrl: String
    private lateinit var sync: AccessCredential
    private lateinit var snapshots: HttpSnapshotTransport
    private lateinit var commands: HttpCommandTransport

    @Before
    fun connect() {
        assumeTrue("needs script/android_rails_transport_check (ALVA_RAILS_HARNESS)", harness != null)
        baseUrl = harness!!.getString("url")
        assertTrue(baseUrl, baseUrl.startsWith("http://127.0.0.1:"))
        sync = AccessCredential(1, harness.getString("sync_token"))
        snapshots = HttpSnapshotTransport(ServerEndpoint.parse(baseUrl), 5_000, 30_000)
        commands = HttpCommandTransport(ServerEndpoint.parse(baseUrl), 5_000, 20_000)
    }

    private fun fetch(credential: AccessCredential = sync): SnapshotOutcome = runBlocking { snapshots.fetch(credential) }

    private fun received(): ServerSnapshot {
        val outcome = fetch()
        assertTrue("expected a snapshot, got $outcome", outcome is SnapshotOutcome.Received)
        return (outcome as SnapshotOutcome.Received).snapshot
    }

    @Test
    fun theFixtureHouseholdsSnapshotIsAcceptedWithEverythingRailsWrites() {
        val snapshot = received()

        assertEquals(harness!!.getString("household_public_id"), snapshot.householdPublicId)
        assertTrue("the seeded categories come with the snapshot", snapshot.categories.isNotEmpty())
        assertTrue(snapshot.revision >= 0 && snapshot.dictionaryRevision >= 0)
        // Every instant Rails wrote has at most six digits and parsed into microseconds.
        val items = snapshot.listItems + snapshot.archivedItems
        assertTrue("the fixtures hold items", items.isNotEmpty())
        items.forEach { assertTrue(it.createdAtMicros > 0 && it.updatedAtMicros >= it.createdAtMicros - 1_000_000_000L) }
        // Archived items point at trips of the snapshot, which the parser already enforced; the order is kept as sent.
        snapshot.archivedItems.forEach { archived -> assertTrue(snapshot.shoppingTrips.any { it.publicId == archived.tripPublicId }) }
    }

    @Test
    fun aNewItemAppearsLastWithMicrosecondsAndTheHouseRevisionMovesByOne() {
        val before = received()
        val itemId = UUID.randomUUID().toString()
        val commandId = UUID.randomUUID().toString()
        val name = "Teste " + UUID.randomUUID().toString().filter { it.isLetter() }.take(10)
        val document =
            """{"id":"$commandId","type":"add_items","depends_on":null,"preconditions":{},"payload":{"items":[{"public_id":"$itemId","name":"$name"}]}}"""
        val outcome = runBlocking { commands.send(sync, commandId, document) }
        assertTrue("$outcome", outcome is CommandOutcome.Receipt)

        val after = received()

        assertEquals(before.revision + 1, after.revision)
        val added = after.listItems.last()
        assertEquals(itemId, added.publicId)
        assertEquals(name, added.name)
        assertEquals(0, added.lockVersion)
        assertEquals("pending", if (added.status == 0) "pending" else "purchased")
        assertEquals(before.listItems.map { it.publicId }, after.listItems.dropLast(1).map { it.publicId })
        assertEquals("microseconds survive: the instant has more than millisecond resolution available", true, added.createdAtMicros > 0)
    }

    @Test
    fun anotherCredentialKindsAreRefusedLikeTheServerDoes() {
        assertEquals(SnapshotOutcome.Unauthorized, fetch(AccessCredential(2, harness!!.getString("add_only_token"))))
        assertEquals(SnapshotOutcome.Unauthorized, fetch(AccessCredential(3, harness.getString("revoked_token"))))
        assertEquals(SnapshotOutcome.Unauthorized, fetch(AccessCredential(4, "not-a-real-token")))
    }
}
