package net.ts.taild80ca0.atlas.twa.data

import net.ts.taild80ca0.atlas.twa.data.db.ItemStatusCodes
import net.ts.taild80ca0.atlas.twa.data.db.ListItemEntity
import net.ts.taild80ca0.atlas.twa.data.list.CommandTypes
import net.ts.taild80ca0.atlas.twa.data.list.ProjectedCommand
import net.ts.taild80ca0.atlas.twa.data.list.ProjectionReducer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectionReducerTest {
    private fun item(id: String, status: Int = ItemStatusCodes.PENDING, trip: String? = null) =
        ListItemEntity(id, id, null, status, null, null, 1, 3, trip)

    private fun setState(id: String, desired: String, at: Long = 50) = ProjectedCommand(
        CommandTypes.SET_ITEM_STATE, JSONObject().put("public_id", id).put("desired", desired), emptyList(), at,
    )

    @Test
    fun addItemsCreatesOnePendingRowPerNameWithTheGivenIds() {
        val command = ProjectedCommand(
            CommandTypes.ADD_ITEMS, JSONObject().put("names", JSONArray(listOf("Leite", "Pão"))), listOf("a", "b"), 9,
        )

        val rows = ProjectionReducer.reduce(emptyMap(), command)

        assertEquals(listOf("a" to "Leite", "b" to "Pão"), rows.map { it.publicId to it.name })
        assertTrue(rows.all { it.status == ItemStatusCodes.PENDING && it.lockVersion == null && it.createdAt == 9L })
    }

    @Test
    fun purchasingSetsPurchasedAtAndPendingClearsIt() {
        val bought = ProjectionReducer.reduce(mapOf("a" to item("a")), setState("a", "purchased")).single()
        assertEquals(ItemStatusCodes.PURCHASED, bought.status)
        assertEquals(50L, bought.purchasedAt)

        val back = ProjectionReducer.reduce(mapOf("a" to bought), setState("a", "pending")).single()
        assertEquals(ItemStatusCodes.PENDING, back.status)
        assertEquals(null, back.purchasedAt)
    }

    @Test
    fun alreadyDesiredMissingAndArchivedItemsChangeNothing() {
        val current = mapOf("a" to item("a"), "z" to item("z", trip = "trip-1"))

        assertTrue(ProjectionReducer.reduce(current, setState("a", "pending")).isEmpty())
        assertTrue(ProjectionReducer.reduce(current, setState("missing", "purchased")).isEmpty())
        assertTrue(ProjectionReducer.reduce(current, setState("z", "purchased")).isEmpty())
    }

    @Test
    fun unknownCommandTypesAreLeftForTheirOwnTask() {
        val command = ProjectedCommand("finish_shopping", JSONObject(), emptyList(), 1)

        assertTrue(ProjectionReducer.reduce(mapOf("a" to item("a")), command).isEmpty())
    }
}
