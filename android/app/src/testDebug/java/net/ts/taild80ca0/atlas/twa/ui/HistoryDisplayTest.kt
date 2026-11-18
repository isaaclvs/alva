package net.ts.taild80ca0.atlas.twa.ui

import java.util.TimeZone
import net.ts.taild80ca0.atlas.twa.online.HistoryItem
import net.ts.taild80ca0.atlas.twa.online.HistoryTrip
import net.ts.taild80ca0.atlas.twa.sync.ServerTime
import org.junit.Assert.assertEquals
import org.junit.Test

class HistoryDisplayTest {
    private val categories = mapOf("c1" to "Hortifruti", "c2" to "Limpeza")

    private fun trip(vararg items: HistoryItem) = HistoryTrip("t1", 0, items.size, items.toList())

    @Test fun groupsKeepTheServersOrderAndNameTheCategories() {
        val groups = HistoryGroups.of(categories, trip(
            HistoryItem("i1", "Banana", "c1"),
            HistoryItem("i2", "Sabão", "c2"),
            HistoryItem("i3", "Alface", "c1"),
        ))

        assertEquals(listOf("Hortifruti", "Limpeza"), groups.map { it.label })
        assertEquals(listOf("Banana", "Alface"), groups.first().items.map { it.name })
    }

    @Test fun anItemWithoutACategoryOrWithOneTheSnapshotLostIsSemCategoria() {
        val groups = HistoryGroups.of(categories, trip(
            HistoryItem("i1", "Coisa", null),
            HistoryItem("i2", "Outra", "desconhecida"),
        ))

        assertEquals(listOf("Sem categoria"), groups.map { it.label })
        assertEquals(2, groups.single().items.size)
    }

    @Test fun theTripDateIsTheServerInstantOnTheDevicesClock() {
        val instant = ServerTime.parseMicros("2026-11-12T21:30:00.000000Z")!!

        assertEquals("Quinta, 12 de novembro · 18:30", HistoryDate.label(instant, TimeZone.getTimeZone("America/Sao_Paulo")))
        assertEquals("Sexta, 13 de novembro · 06:30", HistoryDate.label(instant, TimeZone.getTimeZone("Asia/Tokyo")))
    }

    @Test fun theMonthAndTheCountAreInThePwasWords() {
        assertEquals("Novembro de 2026", HistoryDate.monthLabel("2026-11"))
        assertEquals("2026-13", HistoryDate.monthLabel("2026-13"))
        assertEquals("1 item", HistoryDate.itemsLabel(1))
        assertEquals("3 itens", HistoryDate.itemsLabel(3))
    }
}
