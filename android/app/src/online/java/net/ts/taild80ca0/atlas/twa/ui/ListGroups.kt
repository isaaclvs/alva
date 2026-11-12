package net.ts.taild80ca0.atlas.twa.ui

import net.ts.taild80ca0.atlas.twa.data.list.Category
import net.ts.taild80ca0.atlas.twa.data.list.ItemStatus
import net.ts.taild80ca0.atlas.twa.data.list.ListItem
import net.ts.taild80ca0.atlas.twa.data.matching.TextNormalizer

/** One section of the list: a category (null for items without one) and its rows, in the PWA's order. */
data class ListGroup(val category: Category?, val items: List<ListItem>) {
    /** `Category.label_for`: the category's name, or "Sem categoria". */
    val label: String get() = category?.name ?: NO_CATEGORY
    val purchased: Int get() = items.count { it.status == ItemStatus.PURCHASED }

    companion object {
        const val NO_CATEGORY = "Sem categoria"
    }
}

/** The list as the PWA lays it out (`Household#list_items_by_category`, `list_summary_text`). */
object ListGroups {
    /**
     * Sections ordered by category position, items without a known category last; only sections with items.
     * Inside a section pending items come first, then by name without accents or case.
     */
    fun of(categories: List<Category>, items: List<ListItem>): List<ListGroup> {
        val byId = categories.associateBy { it.publicId }
        return items
            .sortedWith(
                compareBy<ListItem> { if (it.status == ItemStatus.PURCHASED) 1 else 0 }
                    .thenBy { TextNormalizer.plain(it.name) }
                    .thenBy { it.publicId },
            )
            .groupBy { byId[it.categoryPublicId] }
            .map { (category, rows) -> ListGroup(category, rows) }
            .sortedBy { it.category?.position ?: Int.MAX_VALUE }
    }

    /** "6 pendentes · 2 comprados"; the purchased part only once something was bought; "Nada na lista" when empty. */
    fun summary(pending: Int, purchased: Int): String {
        if (pending == 0 && purchased == 0) return "Nada na lista"
        return buildList {
            add(count(pending, "pendente"))
            if (purchased > 0) add(count(purchased, "comprado"))
        }.joinToString(" · ")
    }

    private fun count(n: Int, word: String) = if (n == 1) "1 $word" else "$n ${word}s"
}
