package net.ts.taild80ca0.atlas.twa.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ServerTimeTest {
    @Test
    fun readsTheServersInstantsInMicroseconds() {
        assertEquals(0L, ServerTime.parseMicros("1970-01-01T00:00:00Z"))
        assertEquals(1_000_000L, ServerTime.parseMicros("1970-01-01T00:00:01.000000Z"))
        // 2026-11-10T12:00:00 UTC is 1_794_312_000 s after the epoch, and every digit of the fraction counts.
        assertEquals(1_794_312_000_123_456L, ServerTime.parseMicros("2026-11-10T12:00:00.123456Z"))
        assertEquals(1_794_312_000_100_000L, ServerTime.parseMicros("2026-11-10T12:00:00.1Z"))
        assertEquals(1_794_312_000_000_001L, ServerTime.parseMicros("2026-11-10T12:00:00.000001Z"))
    }

    @Test
    fun twoInstantsInTheSameMillisecondStayApart() {
        val a = ServerTime.parseMicros("2026-11-10T12:00:00.123456Z")!!
        val b = ServerTime.parseMicros("2026-11-10T12:00:00.123457Z")!!
        assertEquals(1L, b - a)
        assertEquals(ServerTime.millis(a), ServerTime.millis(b))
    }

    @Test
    fun handlesLeapDaysAndCenturies() {
        assertEquals(951_782_400_000_000L, ServerTime.parseMicros("2000-02-29T00:00:00Z")) // leap: divisible by 400
        assertNull(ServerTime.parseMicros("1900-02-29T00:00:00Z")) // not leap: divisible by 100 only
        assertNull(ServerTime.parseMicros("2026-02-29T00:00:00Z"))
        assertEquals(1_582_934_400_000_000L, ServerTime.parseMicros("2020-02-29T00:00:00Z"))
    }

    @Test
    fun refusesWhatTheServerCouldNotHaveWritten() {
        listOf(
            "", "2026-11-10", "2026-11-10T12:00:00", "2026-11-10T12:00:00+00:00", "2026-11-10T12:00:00.1234567Z",
            "2026-13-10T12:00:00Z", "2026-11-31T12:00:00Z", "2026-11-10T24:00:00Z", "2026-11-10T12:60:00Z",
            "2026-11-10T12:00:60Z", "2026-11-10 12:00:00Z", " 2026-11-10T12:00:00Z", "2026-11-10T12:00:00Z ",
            "2026-11-10T12:00:00.Z",
        ).forEach { assertNull("'$it' is not an instant", ServerTime.parseMicros(it)) }
    }

    @Test
    fun formatRoundTripsEveryInstantWithSixDigits() {
        listOf(
            "1970-01-01T00:00:00.000000Z", "2026-11-10T12:00:00.123456Z", "2000-02-29T23:59:59.999999Z",
            "1969-12-31T23:59:59.999999Z", "2038-01-19T03:14:08.000001Z",
        ).forEach { assertEquals(it, ServerTime.format(ServerTime.parseMicros(it)!!)) }
    }

    @Test
    fun millisecondsRoundDownEvenBeforeTheEpoch() {
        assertEquals(-1L, ServerTime.millis(-1L))
        assertEquals(0L, ServerTime.millis(999L))
        assertEquals(1L, ServerTime.millis(1_000L))
    }
}
