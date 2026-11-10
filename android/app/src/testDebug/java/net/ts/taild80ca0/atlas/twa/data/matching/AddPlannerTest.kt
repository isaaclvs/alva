package net.ts.taild80ca0.atlas.twa.data.matching

import net.ts.taild80ca0.atlas.twa.data.db.ItemStatusCodes
import net.ts.taild80ca0.atlas.twa.data.db.ListItemEntity
import net.ts.taild80ca0.atlas.twa.data.list.AddPlanner
import net.ts.taild80ca0.atlas.twa.data.list.AddStep
import net.ts.taild80ca0.atlas.twa.data.list.Category
import net.ts.taild80ca0.atlas.twa.data.list.IdGenerator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AddPlannerTest {
    private val dairy = Category("cat-dairy", "Laticínios", 2)
    private val other = Category("cat-other", "Outros", 99)
    private val categorizer = ItemCategorizer(listOf(DictionaryEntry("leite", dairy.publicId)), listOf(dairy, other))
    private val issued = mutableListOf<String>()
    private val planner = AddPlanner(categorizer, IdGenerator { "id-${issued.size}".also { issued += it } })

    private fun row(name: String, status: Int = ItemStatusCodes.PENDING, at: Long? = null, category: String? = null, trip: String? = null, id: String = name) =
        ListItemEntity(id, name, category, status, null, at, 0, 0, trip)

    @Test
    fun idsAreOnlyIssuedForNamesThatBecomeNewItems() {
        val steps = planner.plan(listOf("Leite", "leite", "Pão"), listOf(row("Leite")))

        assertEquals(listOf("id-0"), issued)
        assertEquals(
            listOf(AddStep.Duplicate("Leite", "Leite"), AddStep.Duplicate("leite", "Leite"), AddStep.Create("Pão", "id-0", other.publicId)),
            steps,
        )
    }

    @Test
    fun aNameSeesTheItemsAddedBeforeItInTheSameBatch() {
        val steps = planner.plan(listOf("Café", "CAFÉS"), emptyList())

        assertEquals(listOf(AddStep.Create("Café", "id-0", other.publicId), AddStep.Duplicate("CAFÉS", "id-0")), steps)
    }

    @Test
    fun aRevivedItemKeepsItsCategoryWhenTheDictionaryAgreesAndMovesWhenItDoesNot() {
        val agrees = row("Leite", ItemStatusCodes.PURCHASED, at = 5, category = dairy.publicId)
        val moves = row("Leite", ItemStatusCodes.PURCHASED, at = 5, category = other.publicId)

        assertEquals(null, (planner.plan(listOf("leite"), listOf(agrees)).single() as AddStep.Revive).categoryPublicId)
        assertEquals(dairy.publicId, (planner.plan(listOf("leite"), listOf(moves)).single() as AddStep.Revive).categoryPublicId)
        assertTrue(issued.isEmpty())
    }

    @Test
    fun theMostRecentlyPurchasedIsRevivedAndTiesGoToTheLowerRow() {
        val older = row("Leite", ItemStatusCodes.PURCHASED, at = 5, id = "older")
        val newer = row("leite", ItemStatusCodes.PURCHASED, at = 9, id = "newer")
        val tie = row("leites", ItemStatusCodes.PURCHASED, at = 9, id = "tie")

        assertEquals("newer", (planner.plan(listOf("Leite"), listOf(older, newer)).single() as AddStep.Revive).item.publicId)
        assertEquals("tie", (planner.plan(listOf("Leite"), listOf(older, newer, tie)).single() as AddStep.Revive).item.publicId)
    }

    @Test
    fun archivedItemsAreHistoryAndNothingUsableIsInvalid() {
        val archived = row("Leite", ItemStatusCodes.PURCHASED, at = 5, trip = "trip-1")

        assertEquals(listOf(AddStep.Create("Leite", "id-0", dairy.publicId)), planner.plan(listOf("Leite"), listOf(archived)))
        assertEquals(listOf(AddStep.Invalid), planner.plan(listOf("  ", " "), emptyList()))
        assertEquals(listOf(AddStep.Invalid), planner.plan(emptyList(), emptyList()))
    }

    @Test
    fun thereIsNoLimitOfNamesHere() {
        val names = (1..40).map { "hábito $it" }

        assertEquals(40, planner.plan(names, emptyList()).size)
    }
}
