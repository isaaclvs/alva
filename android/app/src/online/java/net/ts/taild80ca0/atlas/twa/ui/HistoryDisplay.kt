package net.ts.taild80ca0.atlas.twa.ui

import java.util.GregorianCalendar
import java.util.TimeZone
import net.ts.taild80ca0.atlas.twa.online.HistoryItem
import net.ts.taild80ca0.atlas.twa.online.HistoryTrip
import net.ts.taild80ca0.atlas.twa.sync.ServerTime

/** The category names of a trip, in the order the server sent them (category position, then name). */
data class HistoryGroup(val categoryName: String?, val items: List<HistoryItem>) {
    val label: String get() = categoryName ?: NO_CATEGORY

    companion object {
        const val NO_CATEGORY = "Sem categoria"
    }
}

/**
 * A trip as the screen lays it out: the items already come in display order from the server, so this
 * only splits them by category, keeping the first appearance (`categoryPublicId` → the snapshot's name).
 * A category the snapshot no longer has falls back to "Sem categoria", like an item without one.
 */
object HistoryGroups {
    fun of(categories: Map<String, String>, trip: HistoryTrip): List<HistoryGroup> =
        trip.items.groupBy { item -> item.categoryPublicId?.let { categories[it] } }
            .map { (name, items) -> HistoryGroup(name, items) }
}

/**
 * The dates of the history in the PWA's words: "Quinta, 12 de novembro · 18:30" for a trip and
 * "Novembro de 2026" for a month. The trip instant comes from the server in UTC; it is shown on the
 * device's clock, which is the house's, so the evening of a "Compra feita" stays that evening.
 */
object HistoryDate {
    private val WEEKDAYS = listOf("domingo", "segunda", "terça", "quarta", "quinta", "sexta", "sábado")
    private val MONTHS = listOf(
        "janeiro", "fevereiro", "março", "abril", "maio", "junho",
        "julho", "agosto", "setembro", "outubro", "novembro", "dezembro",
    )

    fun label(micros: Long, zone: TimeZone = TimeZone.getDefault()): String {
        val calendar = GregorianCalendar(zone).apply { timeInMillis = ServerTime.millis(micros) }
        val weekday = WEEKDAYS[calendar.get(GregorianCalendar.DAY_OF_WEEK) - 1].replaceFirstChar { it.uppercase() }
        val month = MONTHS[calendar.get(GregorianCalendar.MONTH)]
        val hour = calendar.get(GregorianCalendar.HOUR_OF_DAY)
        val minute = calendar.get(GregorianCalendar.MINUTE)
        return "$weekday, ${calendar.get(GregorianCalendar.DAY_OF_MONTH)} de $month · ${"%02d:%02d".format(hour, minute)}"
    }

    /** "2026-11" → "Novembro de 2026". A key this screen cannot read is shown as it came. */
    fun monthLabel(key: String): String {
        val parts = key.split("-")
        val month = parts.getOrNull(1)?.toIntOrNull() ?: return key
        val name = MONTHS.getOrNull(month - 1) ?: return key
        return "${name.replaceFirstChar { it.uppercase() }} de ${parts.first()}"
    }

    /** "1 item" / "3 itens". */
    fun itemsLabel(count: Int): String = "$count ${if (count == 1) "item" else "itens"}"
}
