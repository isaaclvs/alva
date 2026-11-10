package net.ts.taild80ca0.atlas.twa.access

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import net.ts.taild80ca0.atlas.twa.data.db.SyncStateEntity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Binding on the real Room database and the existing `sync_state` table. */
class RoomHouseholdBindingTest {
    private val storage = RealAccessStorage()
    private val db = storage.openDatabase()
    private val binding = RoomHouseholdBinding(db)

    @After
    fun cleanup() = storage.cleanup()

    @Test
    fun firstBindingRecordsTheHouseholdAndClaimsNoRevision() = runBlocking {
        assertNull(binding.current())

        assertEquals(BindResult.Bound, binding.bind("casa-a"))

        val row = db.syncState().get()!!
        assertEquals("casa-a", row.householdPublicId)
        assertEquals("nothing was reconciled, so no revision is claimed", 0L, row.confirmedRevision)
    }

    @Test
    fun bindingKeepsTheRevisionOfAnAlreadyReconciledCopy() = runBlocking {
        db.syncState().put(SyncStateEntity(householdPublicId = null, confirmedRevision = 840))

        assertEquals(BindResult.Bound, binding.bind("casa-a"))

        assertEquals(SyncStateEntity(householdPublicId = "casa-a", confirmedRevision = 840), db.syncState().get())
    }

    @Test
    fun theSameHouseholdAgainChangesNothing() = runBlocking {
        db.syncState().put(SyncStateEntity(householdPublicId = "casa-a", confirmedRevision = 840))

        assertEquals(BindResult.Bound, binding.bind("casa-a"))

        assertEquals(840L, db.syncState().get()!!.confirmedRevision)
    }

    @Test
    fun anotherHouseholdIsRefusedAndTheRowIsUntouched() = runBlocking {
        db.syncState().put(SyncStateEntity(householdPublicId = "casa-a", confirmedRevision = 840))

        assertEquals(BindResult.OtherHousehold, binding.bind("casa-b"))

        assertEquals(SyncStateEntity(householdPublicId = "casa-a", confirmedRevision = 840), db.syncState().get())
    }

    @Test
    fun racingBindsOfDifferentHouseholdsLetExactlyOneWin() = runBlocking {
        val results = (1..8).map { n -> async(Dispatchers.IO) { binding.bind("casa-$n") } }.awaitAll()

        assertEquals(1, results.count { it == BindResult.Bound })
        assertTrue(binding.current()!!.startsWith("casa-"))
    }
}
