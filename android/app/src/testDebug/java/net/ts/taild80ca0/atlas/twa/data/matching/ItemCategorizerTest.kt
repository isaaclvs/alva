package net.ts.taild80ca0.atlas.twa.data.matching

import net.ts.taild80ca0.atlas.twa.data.list.Category
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ItemCategorizerTest {
    private val dairy = Category("11111111-1111-4111-8111-111111111111", "Laticínios", 2)
    private val other = Category("22222222-2222-4222-8222-222222222222", "Outros", 99)
    private val milk = DictionaryEntry(TextNormalizer.key("Leite"), dairy.publicId)

    @Test
    fun noMatchFallsBackToTheServersOutrosCategoryNeverToAMadeUpId() {
        val categorizer = ItemCategorizer(listOf(milk), listOf(dairy, other))

        assertEquals(other.publicId, categorizer.categoryFor("Coisa desconhecida"))
        assertEquals(dairy.publicId, categorizer.categoryFor("leites"))
    }

    @Test
    fun beforeTheCategoriesWereSyncedNothingIsMadeUp() {
        val categorizer = ItemCategorizer(emptyList(), emptyList())

        assertNull(categorizer.categoryFor("Leite"))
        assertNull(categorizer.categoryFor("Coisa desconhecida"))
        assertNull(categorizer.categoryFor(null))
    }

    @Test
    fun withoutOutrosAnUnknownNameStaysUncategorizedButAKnownOneStillMatches() {
        val categorizer = ItemCategorizer(listOf(milk), listOf(dairy))

        assertNull(categorizer.categoryFor("Coisa desconhecida"))
        assertEquals(dairy.publicId, categorizer.categoryFor("LEITE"))
    }

    @Test
    fun anEntryPointingAtAnUnknownCategoryIsIgnoredInsteadOfLeavingADanglingReference() {
        val dangling = DictionaryEntry(TextNormalizer.key("Pão"), "33333333-3333-4333-8333-333333333333")
        val categorizer = ItemCategorizer(listOf(dangling, milk), listOf(dairy, other))

        assertEquals(other.publicId, categorizer.categoryFor("Pão"))
    }

    @Test
    fun theLookupIsExactOnTheNormalizedNameWithNoPrefixOrFuzzyMatching() {
        val categorizer = ItemCategorizer(listOf(milk), listOf(dairy, other))

        assertEquals(other.publicId, categorizer.categoryFor("Leite integral"))
        assertEquals(other.publicId, categorizer.categoryFor("Leit"))
    }
}
