package com.openminis.app.usage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UsageDisplayTest {

    @Test fun `token counts are compact and never overflow`() {
        assertEquals("0", UsageDisplay.tokens(0))
        assertEquals("999", UsageDisplay.tokens(999))
        assertEquals("1k", UsageDisplay.tokens(1_000))
        assertEquals("1.2k", UsageDisplay.tokens(1_234))
        assertEquals("12k", UsageDisplay.tokens(12_000))
        assertEquals("999.9k", UsageDisplay.tokens(999_900))
        assertEquals("1M", UsageDisplay.tokens(999_950))
        assertEquals("3.1M", UsageDisplay.tokens(3_100_000))
        assertEquals("12.4M", UsageDisplay.tokens(12_400_000))
        assertEquals("1B", UsageDisplay.tokens(999_950_000))
        assertEquals("15.6B", UsageDisplay.tokens(15_589_934_588))
        assertEquals("9007199.3B", UsageDisplay.tokens(9_007_199_254_740_991))
    }

    @Test fun `percent shows the em dash for null and a sliver as under 1`() {
        assertEquals("—", UsageDisplay.percent(null))
        assertEquals("0%", UsageDisplay.percent(0.0))
        assertEquals("<1%", UsageDisplay.percent(0.004))
        assertEquals("82%", UsageDisplay.percent(0.818))
        assertEquals("100%", UsageDisplay.percent(1.0))
        assertNull(UsageDisplay.share(5, 0))
    }

    private fun g(key: String, total: Long) = UsageGroup(key, key, Tokens(input = total), 0, 0)

    @Test fun `top 8 fold keeps unknown apart and last`() {
        val groups = (1..12).map { g("p$it", 1000L - it) } + g(UsageReportBuilder.UNKNOWN_PROVIDER, 5)
        val f = UsageDisplay.fold(groups)
        assertEquals((1..8).map { "p$it" }, f.top.map { it.key })
        assertEquals((9..12).map { "p$it" }, f.rest.map { it.key })
        assertEquals((9..12).sumOf { 1000L - it }, f.restTotal)
        assertEquals(UsageReportBuilder.UNKNOWN_PROVIDER, f.unknown?.key)
    }

    @Test fun `a single overflow group is shown rather than folded`() {
        val f = UsageDisplay.fold((1..9).map { g("p$it", 100L - it) })
        assertEquals(9, f.top.size)
        assertTrue(f.rest.isEmpty())
        assertNull(f.unknown)
    }

    @Test fun `unknown model key is the empty string`() {
        val f = UsageDisplay.fold(listOf(g("a", 3), g(UsageReportBuilder.UNKNOWN_MODEL, 9)))
        assertEquals(listOf("a"), f.top.map { it.key })
        assertEquals("", f.unknown?.key)
    }
}
