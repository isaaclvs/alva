package net.ts.taild80ca0.atlas.twa.data.matching

import org.junit.Assert.assertEquals
import org.junit.Test

/** The add field's suggestions: the same filtering the PWA's datalist does, over the names the snapshot brings. */
class NameSuggestionsTest {
    private val known = listOf("Abacate", "água", "Banana", "Leite", "Leite condensado", "Pão", "Pão de queijo")

    @Test
    fun theSegmentIsTheNameBeingTypedAfterTheLastSeparator() {
        assertEquals("leite", NameSuggestions.segment("leite"))
        assertEquals("pao", NameSuggestions.segment("leite, pao"))
        assertEquals("pao", NameSuggestions.segment("leite;banana\npao"))
        assertEquals("", NameSuggestions.segment("leite, "))
        assertEquals("", NameSuggestions.segment(null))
    }

    @Test
    fun matchingIgnoresAccentsAndCase() {
        assertEquals(listOf("Pão", "Pão de queijo"), NameSuggestions.forDraft("pao", known))
        assertEquals(listOf("água"), NameSuggestions.forDraft("AGUA", known))
        assertEquals(listOf("Abacate"), NameSuggestions.forDraft("aBACA", known))
    }

    @Test
    fun onlyTheNameBeingTypedIsMatched() {
        assertEquals(listOf("Banana"), NameSuggestions.forDraft("leite, ban", known))
        // What was typed before the separator is not matched again: only the last name completes.
        assertEquals(listOf("Leite", "Leite condensado"), NameSuggestions.forDraft("banana, leite", known))
    }

    @Test
    fun aNameTypedExactlyIsNotOfferedAndEmptyDraftsOfferNothing() {
        assertEquals(emptyList<String>(), NameSuggestions.forDraft("Banana", known))
        assertEquals(listOf("Leite condensado"), NameSuggestions.forDraft("Pão, Leite", known))
        // The spelling the house uses is still offered when what was typed differs in accent or case: correcting
        // "pao" to "Pão" is one of the reasons the suggestion exists.
        assertEquals(listOf("Pão", "Pão de queijo"), NameSuggestions.forDraft("pao", known))
        assertEquals(listOf("Leite", "Leite condensado"), NameSuggestions.forDraft("leite", known))
        assertEquals(emptyList<String>(), NameSuggestions.forDraft("", known))
        assertEquals(emptyList<String>(), NameSuggestions.forDraft("   ", known))
    }

    @Test
    fun suggestionsComeInTheDatalistOrderLimitedToWhatFitsAboveTheField() {
        val many = (1..12).map { "Item ${'a' + it - 1}" }
        assertEquals(listOf("Item a", "Item b", "Item c", "Item d", "Item e"), NameSuggestions.forDraft("item", many))
        assertEquals(5, NameSuggestions.forDraft("item", many).size)
        // Accent- and case-blind, like the datalist: "água" sits with the "a"s, before "Banana".
        assertEquals(listOf("Abacate", "água"), NameSuggestions.forDraft("a", listOf("água", "Abacate", "Banana")))
    }

    @Test
    fun oneSuggestionPerNormalizedName() {
        assertEquals(listOf("Leite"), NameSuggestions.forDraft("lei", listOf("Leite", "leite")))
        assertEquals(listOf("Maçã"), NameSuggestions.forDraft("mac", listOf("Maçã", "maçãs")))
    }

    @Test
    fun completingKeepsTheNamesAlreadyTypedBeforeTheLastSeparator() {
        assertEquals("Pão", NameSuggestions.complete("pao", "Pão"))
        assertEquals("Leite, Pão", NameSuggestions.complete("Leite, pao", "Pão"))
        assertEquals("Leite;Banana\nPão de queijo", NameSuggestions.complete("Leite;Banana\npao", "Pão de queijo"))
        // A separator left glued to the name being typed reads like the PWA's own hint after the completion.
        assertEquals("Leite, Pão", NameSuggestions.complete("Leite,pao", "Pão"))
        assertEquals("Leite, Pão", NameSuggestions.complete("Leite,", "Pão"))
        // What was typed after the separator is replaced; the names before it keep exactly what was typed.
        assertEquals("Leite, Pão", NameSuggestions.complete("Leite,   ", "Pão"))
    }
}
