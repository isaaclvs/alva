package net.ts.taild80ca0.atlas.twa.data.matching

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The name limit shared with Rails: `ListItem::MAX_NAME_LENGTH` and `ItemNameSplitter.clean`. */
class ItemNameSplitterTest {
    private val longest = "x".repeat(ItemNameSplitter.MAX_NAME_LENGTH)

    @Test
    fun aNameIsCutAtTheLimitBeforeTheRepeatsAreDropped() {
        assertEquals(listOf(longest), ItemNameSplitter.split("x".repeat(101)))
        assertEquals(listOf(longest, "pão"), ItemNameSplitter.split("x".repeat(101) + ", pão"))
        assertEquals(listOf(longest), ItemNameSplitter.split("x".repeat(101) + ", $longest"))
    }

    @Test
    fun theLimitCountsCharactersNotBytes() {
        val accented = "á".repeat(ItemNameSplitter.MAX_NAME_LENGTH)

        assertEquals(listOf(accented), ItemNameSplitter.split(accented + "á"))
        assertEquals(accented, ItemNameSplitter.clean(accented + "á"))
    }

    @Test
    fun aCutThatLandsOnASpaceStripsIt() {
        assertEquals(listOf("x".repeat(99)), ItemNameSplitter.split("x".repeat(99) + " y"))
    }

    @Test
    fun theDraftStopsEachNameAtTheLimitAndKeepsTheSeparators() {
        val draft = ItemNameSplitter.limitDraft("x".repeat(140) + ", pão\n" + "y".repeat(101))

        assertEquals(longest + ", pão\n" + "y".repeat(ItemNameSplitter.MAX_NAME_LENGTH), draft)
        assertEquals("", ItemNameSplitter.limitDraft(null))
        assertEquals("leite, pão", ItemNameSplitter.limitDraft("leite, pão"))
    }

    @Test
    fun theCorrectionFieldTakesOneNameAndStopsAtTheLimit() {
        assertEquals(longest, ItemNameSplitter.limitName("x".repeat(140)))
        assertEquals("Leite", ItemNameSplitter.limitName("Leite"))
        assertNull(ItemNameSplitter.clean("   "))
    }
}
