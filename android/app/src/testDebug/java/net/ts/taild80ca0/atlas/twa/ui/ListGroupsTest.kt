package net.ts.taild80ca0.atlas.twa.ui

import net.ts.taild80ca0.atlas.twa.data.list.Category
import net.ts.taild80ca0.atlas.twa.data.list.ItemStatus
import net.ts.taild80ca0.atlas.twa.data.list.ListItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The list's layout rules, which are the PWA's (`Household#list_items_by_category`, `list_summary_text`). */
class ListGroupsTest {
    private val fruit = Category("c-fruit", "Hortifruti", 1)
    private val dairy = Category("c-dairy", "Laticínios", 2)
    private val others = Category("c-others", "Outros", 99)
    private val categories = listOf(others, dairy, fruit) // arrival order does not matter

    private fun item(id: String, name: String, category: String?, status: ItemStatus = ItemStatus.PENDING) =
        ListItem(id, name, category, status, if (status == ItemStatus.PURCHASED) 1L else null, awaitingSync = false)

    @Test fun sectionsFollowTheCategoryPositionAndSkipEmptyOnes() {
        val groups = ListGroups.of(categories, listOf(item("1", "Sabão", "c-others"), item("2", "Leite", "c-dairy"), item("3", "Maçã", "c-fruit")))
        assertEquals(listOf("Hortifruti", "Laticínios", "Outros"), groups.map { it.label })
    }

    @Test fun itemsWithoutAKnownCategoryComeLastUnderSemCategoria() {
        val groups = ListGroups.of(categories, listOf(item("1", "Antigo", null), item("2", "Perdido", "c-gone"), item("3", "Sabão", "c-others")))
        assertEquals(listOf("Outros", "Sem categoria"), groups.map { it.label })
        assertEquals(listOf("Antigo", "Perdido"), groups.last().items.map { it.name })
        assertNull(groups.last().category)
    }

    @Test fun pendingItemsComeFirstThenByNameIgnoringAccentsAndCase() {
        val groups = ListGroups.of(
            categories,
            listOf(
                item("1", "Queijo", "c-dairy", ItemStatus.PURCHASED), item("2", "Requeijão", "c-dairy"),
                item("3", "iogurte", "c-dairy"), item("4", "Ácido", "c-dairy", ItemStatus.PURCHASED), item("5", "Leite", "c-dairy"),
            ),
        )
        assertEquals(listOf("iogurte", "Leite", "Requeijão", "Ácido", "Queijo"), groups.single().items.map { it.name })
    }

    @Test fun theBoughtCountOfASectionIsXOfY() {
        val group = ListGroups.of(categories, listOf(item("1", "Leite", "c-dairy", ItemStatus.PURCHASED), item("2", "Queijo", "c-dairy"))).single()
        assertEquals(1, group.purchased)
        assertEquals(2, group.items.size)
    }

    @Test fun theSummaryNamesPendingAndPurchasedLikeThePwa() {
        assertEquals("Nada na lista", ListGroups.summary(0, 0))
        assertEquals("1 pendente", ListGroups.summary(1, 0))
        assertEquals("0 pendentes · 2 comprados", ListGroups.summary(0, 2))
        assertEquals("8 pendentes · 1 comprado", ListGroups.summary(8, 1))
    }
}
