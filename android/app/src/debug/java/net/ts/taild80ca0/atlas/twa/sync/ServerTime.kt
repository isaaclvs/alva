package net.ts.taild80ca0.atlas.twa.sync

/**
 * The server's instants: UTC ISO 8601 with up to six fractional digits (`2026-11-10T12:00:00.123456Z`).
 * Kept as **epoch microseconds**, the precision the server keeps: two items created in the same
 * millisecond stay apart, which the "buy again" tie-breaks (a later task) depend on. Parsed by hand
 * because the app supports API 24 (`java.time` starts at 26) and never rounds: a value with more
 * than microsecond precision, a zone other than `Z` or an impossible date is not an instant.
 */
object ServerTime {
    private val FORMAT = Regex("""(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})(?:\.(\d{1,6}))?Z""")

    /** Epoch microseconds, or null when [text] is not an instant the server could have written. */
    fun parseMicros(text: String): Long? {
        val m = FORMAT.matchEntire(text) ?: return null
        val (year, month, day, hour, minute, second) = m.groupValues.drop(1).take(6).map { it.toInt() }
        if (month !in 1..12 || day !in 1..daysIn(year, month) || hour > 23 || minute > 59 || second > 59) return null
        val fraction = m.groupValues[7].padEnd(6, '0').toInt()
        val seconds = daysFromCivil(year, month, day) * 86_400L + hour * 3_600L + minute * 60L + second
        return seconds * 1_000_000L + fraction
    }

    /** The text form of [micros], always with six digits, like the server's own. */
    fun format(micros: Long): String {
        val seconds = Math.floorDiv(micros, 1_000_000L)
        val fraction = Math.floorMod(micros, 1_000_000L)
        val days = Math.floorDiv(seconds, 86_400L)
        val inDay = Math.floorMod(seconds, 86_400L)
        val (year, month, day) = civilFromDays(days)
        return "%04d-%02d-%02dT%02d:%02d:%02d.%06dZ".format(year, month, day, inDay / 3_600, inDay % 3_600 / 60, inDay % 60, fraction)
    }

    /** The same instant in milliseconds (rounded down), for the columns the screens read. */
    fun millis(micros: Long): Long = Math.floorDiv(micros, 1_000L)

    private operator fun <T> List<T>.component6(): T = this[5]

    private fun daysIn(year: Int, month: Int): Int = when (month) {
        2 -> if (year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)) 29 else 28
        4, 6, 9, 11 -> 30
        else -> 31
    }

    /** Days since 1970-01-01 (Howard Hinnant's civil calendar algorithm). */
    private fun daysFromCivil(year: Int, month: Int, day: Int): Long {
        val y = (if (month <= 2) year - 1 else year).toLong()
        val era = Math.floorDiv(y, 400L)
        val yearOfEra = y - era * 400
        val dayOfYear = (153 * (if (month > 2) month - 3 else month + 9) + 2) / 5 + day - 1
        val dayOfEra = yearOfEra * 365 + yearOfEra / 4 - yearOfEra / 100 + dayOfYear
        return era * 146_097 + dayOfEra - 719_468
    }

    private fun civilFromDays(days: Long): Triple<Int, Int, Int> {
        val z = days + 719_468
        val era = Math.floorDiv(z, 146_097L)
        val dayOfEra = z - era * 146_097
        val yearOfEra = (dayOfEra - dayOfEra / 1_460 + dayOfEra / 36_524 - dayOfEra / 146_096) / 365
        val dayOfYear = dayOfEra - (365 * yearOfEra + yearOfEra / 4 - yearOfEra / 100)
        val mp = (5 * dayOfYear + 2) / 153
        val day = (dayOfYear - (153 * mp + 2) / 5 + 1).toInt()
        val month = (if (mp < 10) mp + 3 else mp - 9).toInt()
        val year = (yearOfEra + era * 400 + if (month <= 2) 1 else 0).toInt()
        return Triple(year, month, day)
    }
}
