package net.ts.taild80ca0.atlas.twa.data.matching

import net.ts.taild80ca0.atlas.twa.data.list.ItemStatus
import net.ts.taild80ca0.atlas.twa.data.list.ListItem
import org.junit.Assert.assertEquals
import org.junit.Test

class DuplicateHintTest {
    private fun item(name: String, status: ItemStatus = ItemStatus.PENDING) =
        ListItem(name, name, null, status, null, false)

    private val list = listOf(item("Leite"), item("Pão de queijo"), item("Café", ItemStatus.PURCHASED))

    @Test
    fun namesAlreadyPendingAreReportedSpelledAsOnTheList() {
        assertEquals(listOf("Leite"), DuplicateHint.alreadyPending("LEITE, ovos", list))
        assertEquals(listOf("Pão de queijo", "Leite"), DuplicateHint.alreadyPending("pao  de queijo;leite", list))
    }

    @Test
    fun purchasedItemsAreNotDuplicatesAndNeitherArePluralsWhichOnlyTheFullRuleCatches() {
        assertEquals(emptyList<String>(), DuplicateHint.alreadyPending("café", list))
        assertEquals(emptyList<String>(), DuplicateHint.alreadyPending("leites", list))
    }

    @Test
    fun repeatsAndBlanksInTheTextCountOnce() {
        assertEquals(listOf("Leite"), DuplicateHint.alreadyPending("leite,, Leite ;\n  ", list))
        assertEquals(emptyList<String>(), DuplicateHint.alreadyPending(null, list))
    }
}
