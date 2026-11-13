package net.ts.taild80ca0.atlas.twa.widgets

import net.ts.taild80ca0.atlas.twa.sync.*
import org.junit.Assert.*
import org.junit.Test

class WidgetListRowsTest {
    @Test fun onlyPendingItemsGetRowsAndEachHasASecondaryCategory() {
        val document = SnapshotFixtures.snapshot(items = listOf(
            SnapshotFixtures.item(publicId = "queijo", name = "Queijo", status = "purchased", purchasedAt = SnapshotFixtures.EARLY),
            SnapshotFixtures.item(publicId = "leite", name = "Leite"),
            SnapshotFixtures.item(publicId = "agua", name = "Água"),
        ))
        val snapshot = (SnapshotParser.parse(SnapshotFixtures.bytes(document)) as SnapshotParse.Valid).snapshot
        val rows = WidgetListRows.of(snapshot)
        assertEquals(listOf("Água", "Leite"), rows.map { it.second.name })
        assertNotNull(rows.first().first)
        assertEquals(rows.first().first, rows.last().first)
    }
    @Test fun absentSnapshotHasNoRows() { assertTrue(WidgetListRows.of(null).isEmpty()) }
}
