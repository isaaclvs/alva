package net.ts.taild80ca0.atlas.twa.widgets

import org.junit.Assert.assertEquals
import org.junit.Test

class HabitualWidgetSettingsTest {
    @Test fun `selection preserves explicit order and ignores deleted habituals without replacements`() {
        val settings = HabitualWidgetSettings("Cozinha", listOf("coffee", "milk", "coffee", "deleted"))
        assertEquals(listOf("coffee", "milk"), settings.selected(setOf("milk", "coffee", "eggs")))
    }
    @Test fun `different widget selections remain independent`() {
        val kitchen = HabitualWidgetSettings("Cozinha", listOf("coffee", "milk"))
        val cleaning = HabitualWidgetSettings("Limpeza", listOf("soap"))
        val available = setOf("coffee", "milk", "soap")
        assertEquals(listOf("coffee", "milk"), kitchen.selected(available))
        assertEquals(listOf("soap"), cleaning.selected(available))
    }
}
