package net.ts.taild80ca0.atlas.twa.ui

import net.ts.taild80ca0.atlas.twa.data.list.ItemStatus
import net.ts.taild80ca0.atlas.twa.data.list.ListItem
import org.junit.Assert.assertEquals
import org.junit.Test

class ListFiltersTest {
    private val items = listOf(
        ListItem("1", "Pão de açúcar", null, ItemStatus.PENDING, null, false),
        ListItem("2", "PAO integral", null, ItemStatus.PURCHASED, 1, false),
        ListItem("3", "Leite", null, ItemStatus.PENDING, null, false),
    )

    @Test fun searchIgnoresCaseAndAccents() {
        assertEquals(listOf("Pão de açúcar", "PAO integral"), ListFilters("pAo").apply(items).map { it.name })
    }

    @Test fun pendingOnlyComposesWithSearchAndCanBeReused() {
        val filter = ListFilters("pao", pendingOnly = true)
        assertEquals(listOf("Pão de açúcar"), filter.apply(items).map { it.name })
        assertEquals(listOf("Pão de açúcar", "PAO integral"), filter.copy(pendingOnly = false).apply(items).map { it.name })
    }
}
