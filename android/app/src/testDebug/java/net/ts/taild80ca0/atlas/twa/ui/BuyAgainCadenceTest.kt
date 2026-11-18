package net.ts.taild80ca0.atlas.twa.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class BuyAgainCadenceTest {
    @Test fun readsTheMedianDaysTheServerComputed() {
        assertEquals("costuma acabar a cada ~12 dias", buyAgainCadence(12))
        assertEquals("costuma acabar a cada ~2 dias", buyAgainCadence(2))
    }

    @Test fun oneDayIsEveryDay() {
        assertEquals("costuma acabar todo dia", buyAgainCadence(1))
    }
}
