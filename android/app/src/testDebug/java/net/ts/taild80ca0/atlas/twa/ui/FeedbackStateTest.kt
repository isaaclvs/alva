package net.ts.taild80ca0.atlas.twa.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FeedbackStateTest {
    @Test fun theNewestMessageWinsAndOnlyItsOwnDismissTakesItAway() {
        val state = FeedbackState()
        state.show("Sem conexão com o servidor.")
        val first = state.current!!
        state.show("A lista mudou em outro aparelho.")
        // The auto-hide of the message that was already replaced must not take the newer one away.
        state.dismiss(first)
        assertEquals("A lista mudou em outro aparelho.", state.current?.text)
        assertNotEquals(first.id, state.current?.id)
        state.dismiss()
        assertNull(state.current)
    }

    @Test fun aMessageKeepsTheWayToRepeatTheAction() {
        val state = FeedbackState()
        var retried = 0
        state.show("Sem conexão com o servidor.") { retried++ }
        state.current?.retry?.invoke()
        assertEquals(1, retried)
        state.show("A lista mudou em outro aparelho.")
        assertNull("not every failure can be repeated", state.current?.retry)
    }
}
