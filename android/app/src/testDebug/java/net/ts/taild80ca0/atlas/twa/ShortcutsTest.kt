package net.ts.taild80ca0.atlas.twa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The URI of each launcher shortcut maps to the screen that already does that job (#158). */
class ShortcutsTest {
    @Test fun everyShortcutUriHasItsDestination() {
        assertEquals(ShortcutDestination.ADD_ITEM, Shortcuts.destination("alva-shortcut://add"))
        assertEquals(ShortcutDestination.LIST, Shortcuts.destination("alva-shortcut://list"))
        assertEquals(ShortcutDestination.SHOPPING_MODE, Shortcuts.destination("alva-shortcut://shopping"))
    }

    @Test fun aUriOfAnotherSchemeOrHostIsNotOurs() {
        assertNull(Shortcuts.destination(null))
        assertNull(Shortcuts.destination(""))
        assertNull(Shortcuts.destination("add"))
        assertNull(Shortcuts.destination("https://add"))
        assertNull(Shortcuts.destination("alva-shortcut://"))
        assertNull(Shortcuts.destination("alva-shortcut://delete"))
        assertNull(Shortcuts.destination("alva-dev://widget-design"))
    }

    @Test fun queryParametersAndAPathStillNameTheSameScreen() {
        assertEquals(ShortcutDestination.LIST, Shortcuts.destination("alva-shortcut://list/"))
        assertEquals(ShortcutDestination.LIST, Shortcuts.destination("alva-shortcut://list?from=launcher"))
        assertEquals(ShortcutDestination.SHOPPING_MODE, Shortcuts.destination("alva-shortcut://shopping/#top"))
    }
}
