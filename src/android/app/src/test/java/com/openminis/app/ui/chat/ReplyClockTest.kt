package com.openminis.app.ui.chat

import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** [UI-261025-11] The time under a reply gains its date on any other day. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ReplyClockTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val now = ms(2026, 10, 25, 15, 0)

    private fun ms(y: Int, mo: Int, d: Int, h: Int, mi: Int) =
        ZonedDateTime.of(y, mo, d, h, mi, 0, 0, zone).toInstant().toEpochMilli()

    @Test
    fun todayIsTheTimeAlone() {
        assertEquals("09:05", formatReplyClock(ms(2026, 10, 25, 9, 5), now, Locale.US, zone))
        assertEquals("00:00", formatReplyClock(ms(2026, 10, 25, 0, 0), now, Locale.US, zone))
    }

    @Test
    fun anotherDayThisYearHasMonthAndDay() {
        assertEquals("Oct 24 23:59", formatReplyClock(ms(2026, 10, 24, 23, 59), now, Locale.US, zone))
        assertEquals("10月24日 23:59", formatReplyClock(ms(2026, 10, 24, 23, 59), now, Locale.SIMPLIFIED_CHINESE, zone))
    }

    @Test
    fun anEarlierYearHasTheYear() {
        val text = formatReplyClock(ms(2025, 12, 31, 18, 30), now, Locale.US, zone)
        assertEquals("Dec 31, 2025 18:30", text)
        assertTrue(formatReplyClock(ms(2025, 12, 31, 18, 30), now, Locale.SIMPLIFIED_CHINESE, zone).startsWith("2025年12月31日"))
    }

    @Test
    fun theTimeStaysTwentyFourHourInEveryLocale() {
        assertEquals("18:30", formatReplyClock(ms(2026, 10, 25, 18, 30), now, Locale.US, zone))
        assertTrue(formatReplyClock(ms(2026, 3, 1, 18, 30), now, Locale("ar"), zone).endsWith(" 18:30"))
    }
}
