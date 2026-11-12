package net.ts.taild80ca0.atlas.twa.ui

import org.junit.Assert.*
import org.junit.Test

class ShoppingModeTest {
    @Test fun validForTwentyFourHoursAndExpiresAtTheDeadline() {
        val now = 10_000L
        val until = now + SHOPPING_DURATION_MILLIS
        assertTrue(shoppingModeActive(until, now))
        assertTrue(shoppingModeActive(until, until - 1))
        assertFalse(shoppingModeActive(until, until))
        assertFalse(shoppingModeActive(until, until + 1))
    }
    @Test fun missingPreferenceAndClockRollbackCannotKeepShoppingForever() {
        assertFalse(shoppingModeActive(0, 10_000))
        assertFalse(shoppingModeActive(10_000 + SHOPPING_DURATION_MILLIS, 9_999))
    }
}
