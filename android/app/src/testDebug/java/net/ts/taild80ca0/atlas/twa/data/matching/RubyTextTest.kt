package net.ts.taild80ca0.atlas.twa.data.matching

import org.junit.Assert.assertEquals
import org.junit.Test

class RubyTextTest {
    @Test
    fun squishCollapsesEveryUnicodeSpaceRunAndStripsTheEdges() {
        assertEquals("Pão francês", RubyText.squish("Pão francês"))
        assertEquals("a b c", RubyText.squish("　a \t b  \nc \u0085"))
        assertEquals("", RubyText.squish(" 　 "))
        assertEquals("ab", RubyText.squish("ab"))
        // NUL is not white space for the squish, only for the strip at the edges.
        assertEquals("a\u0000b", RubyText.squish("a\u0000b"))
        assertEquals("a", RubyText.squish("\u0000 a \u0000"))
    }
}
