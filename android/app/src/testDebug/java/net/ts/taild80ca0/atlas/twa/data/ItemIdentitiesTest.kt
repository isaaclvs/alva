package net.ts.taild80ca0.atlas.twa.data

import net.ts.taild80ca0.atlas.twa.data.list.ItemIdentities
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ItemIdentitiesTest {
    private val a = "9a1d0000-0000-4000-8000-000000000001"
    private val b = "9a1d0000-0000-4000-8000-0000000000aa"
    private val c = "9a1d0000-0000-4000-8000-0000000000cc"

    @Test
    fun anIdWithNoTwinIsItsOwnRow() {
        val identities = ItemIdentities(emptyMap())
        assertEquals(a, identities.resolve(a))
        assertNull(identities.rowOf(a))
        assertEquals(listOf(a), identities.citedAs(listOf(a)))
    }

    @Test
    fun durableAliasesAndProvisionalMergesBothFindTheRowAndBothAreCitedAsIt() {
        val identities = ItemIdentities(mapOf(a to b))
        identities.merge(mapOf(c to b))

        assertEquals(b, identities.resolve(a))
        assertEquals(b, identities.resolve(c))
        assertEquals(b, identities.resolve(c.uppercase()))
        assertEquals(setOf(b, a, c), identities.citedAs(listOf(b)).toSet())
        assertFalse("what the server said is not a guess", identities.isProvisional(a))
        assertTrue(identities.isProvisional(c))
    }

    @Test
    fun theProvisionalMergeIsOnlyAsGoodAsTheReplayThatMadeIt() {
        // A fresh instance (the next replay, after the server answered) has no memory of the old guess.
        val before = ItemIdentities(emptyMap()).apply { merge(mapOf(a to b)) }
        val after = ItemIdentities(mapOf(a to b))
        assertEquals(before.resolve(a), after.resolve(a))
        assertTrue(before.isProvisional(a))
        assertFalse(after.isProvisional(a))
    }
}
