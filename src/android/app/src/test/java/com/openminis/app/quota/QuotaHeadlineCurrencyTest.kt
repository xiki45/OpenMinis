package com.openminis.app.quota

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.util.Locale

/**
 * [ISS-035] Port of the iOS QuotaHeadlineCurrencyTests: the one-line quota
 * choice for multi-currency prepaid balances.
 *
 * A DeepSeek account holding CN¥7.62 and US$0.00 showed "Balance US$0.00" in
 * red on the picker header (and the Model Group pill / composer line), because
 * both one-line choices took the worst status first. An emptied money balance
 * no longer headlines while another currency still has funds; every other pool
 * kind keeps "worst first".
 */
class QuotaHeadlineCurrencyTest {

    private val now = 1_800_000_000_000L
    private val prov = QuotaProvenance(QuotaSource.officialQuery, QuotaConfidence.documented, "test")
    private val sub = QuotaSubjectID(QuotaVendor.deepSeek, "abcd1234abcd1234")

    private fun dec(s: String) = BigDecimal(s)

    private fun balance(cur: String, amount: String, verdict: QuotaPool.Verdict? = QuotaPool.Verdict.allowed) = QuotaPool(
        id = QuotaPoolKey.of(sub, "balance", cur), subjectId = sub, kind = QuotaPool.Kind.prepaidBalance,
        unit = QuotaUnit.Currency(cur), vendorLabel = "$cur balance", amounts = QuotaAmounts(remaining = dec(amount)),
        verdict = verdict, provenance = prov, observedAt = now,
    )

    private fun credits(amount: String) = QuotaPool(
        id = QuotaPoolKey.of(sub, "credits"), subjectId = sub, kind = QuotaPool.Kind.prepaidBalance,
        unit = QuotaUnit.Credits("points"), amounts = QuotaAmounts(remaining = dec(amount)),
        provenance = prov, observedAt = now,
    )

    private fun usageWindow(used: String) = QuotaPool(
        id = QuotaPoolKey.of(sub, "subscription"), subjectId = sub, kind = QuotaPool.Kind.usageWindow,
        unit = QuotaUnit.PercentOnly,
        windows = listOf(QuotaWindow(id = "5h", isRolling = true, reportedUsedFraction = dec(used), observedAt = now, provenance = prov)),
        provenance = prov, observedAt = now,
    )

    private val usdLow = QuotaThresholds(lowBalance = mapOf(sub to mapOf("USD" to dec("1"))))

    /** The headline's pool qualifier ("USD" / "CNY" / family) and status. */
    private fun head(pools: List<QuotaPool>, thresholds: QuotaThresholds = QuotaThresholds()): Pair<String, QuotaStatus>? =
        QuotaHeadline.pick(pools, now, thresholds = thresholds)?.let {
            it.first.id.rawValue.substringAfterLast(':') to it.second.status
        }

    private val reported = DeepSeekQuotaAdapter.parse(
        ("""{"is_available":true,"balance_infos":[""" +
            """{"currency":"CNY","total_balance":"7.62","granted_balance":"0.00","topped_up_balance":"7.62"},""" +
            """{"currency":"USD","total_balance":"0.00","granted_balance":"0.00","topped_up_balance":"0.00"}]}""").toByteArray(),
        sub, now = now,
    )

    @Test fun `reported account - USD 0 plus CNY 7_62 headlines the CNY balance`() {
        assertEquals(2, reported.pools.size)
        assertEquals("CNY" to QuotaStatus.available, head(reported.pools))
        assertEquals("pool order does not matter", "CNY" to QuotaStatus.available, head(reported.pools.reversed()))
    }

    @Test fun `mirror - funded USD plus empty CNY headlines USD`() {
        assertEquals("USD" to QuotaStatus.available, head(listOf(balance("USD", "3.5"), balance("CNY", "0"))))
    }

    @Test fun `all money balances exhausted - nothing is hidden`() {
        assertEquals("CNY" to QuotaStatus.exhausted, head(listOf(balance("USD", "0"), balance("CNY", "0"))))
        assertEquals("USD" to QuotaStatus.exhausted, head(listOf(balance("USD", "0"))))
    }

    @Test fun `several funded currencies - unchanged choice, no exchange rate`() {
        assertEquals("CNY" to QuotaStatus.available, head(listOf(balance("USD", "3.5"), balance("CNY", "7.62"))))
        assertEquals("USD" to QuotaStatus.available, head(listOf(balance("USD", "3.5"))))
    }

    @Test fun `a low balance is still money and is never dropped`() {
        assertEquals("USD" to QuotaStatus.low, head(listOf(balance("USD", "0.5"), balance("CNY", "7.62")), usdLow))
        assertEquals("USD" to QuotaStatus.low, head(listOf(balance("USD", "0.5"), balance("CNY", "0")), usdLow))
    }

    @Test fun `other pool kinds keep worst first`() {
        assertEquals(
            "an exhausted window still headlines",
            "subscription" to QuotaStatus.exhausted,
            head(listOf(balance("USD", "0"), balance("CNY", "7.62"), usageWindow("1"))),
        )
        assertEquals(
            "a funded window beats the dropped USD pool by the usual window-first rule",
            "subscription" to QuotaStatus.available,
            head(listOf(balance("USD", "0"), balance("CNY", "7.62"), usageWindow("0.5"))),
        )
        assertEquals(
            "non-currency prepaid (credits) at 0 is not a money balance",
            "credits" to QuotaStatus.exhausted,
            head(listOf(credits("0"), balance("CNY", "7.62"))),
        )
        assertTrue(QuotaHeadline.isCurrencyBalance(balance("USD", "1")))
        assertFalse(QuotaHeadline.isCurrencyBalance(credits("1")))
        assertFalse(QuotaHeadline.isCurrencyBalance(usageWindow("0.1")))
    }

    @Test fun `a vendor-rejected account is never hidden`() {
        assertEquals(
            QuotaStatus.exhausted,
            head(listOf(balance("USD", "0", QuotaPool.Verdict.rejected), balance("CNY", "7.62", QuotaPool.Verdict.rejected)))?.second,
        )
        assertEquals(
            "USD" to QuotaStatus.exhausted,
            head(listOf(balance("USD", "0", QuotaPool.Verdict.rejected), balance("CNY", "7.62"))),
        )
    }

    @Test fun `header, member pill and composer show the CNY balance and the account dot is unchanged`() {
        val pools = reported.pools.associateBy { it.id }
        val picked = QuotaHeadline.pick(reported.pools, now)!!
        val header = QuotaPickerHintRules.headerText(picked.first, picked.second, Locale.US)
        assertNotNull(header)
        assertTrue("header: $header", header!!.contains("7.62") && !header.contains("0.00"))

        val view = QuotaModelResolver.view("i1", "deepseek-chat", sub, pools, reported.bindings, now = now)
        val pill = QuotaPickerHintRules.memberPill(view, pools, Locale.US)
        assertNotNull(pill)
        assertTrue("pill: ${pill!!.text}", pill.text.contains("7.62"))
        assertTrue(pill.poolKey.rawValue.endsWith(":CNY"))
        val seg = QuotaComposerHintRules.segment(view, pools, now = now, locale = Locale.US)
        assertTrue("composer: ${seg?.text}", seg?.text?.contains("7.62") == true)
        assertEquals("¥7.62 + $0 is not low", QuotaLevel.normal, QuotaLevelRules.level(view, pools))
    }

    @Test fun `both balances empty - member pill still shows the exhausted amount`() {
        val spent = listOf(balance("USD", "0"), balance("CNY", "0")).associateBy { it.id }
        val view = QuotaModelResolver.view("i1", "deepseek-chat", sub, spent, reported.bindings, now = now)
        val pill = QuotaPickerHintRules.memberPill(view, spent, Locale.US)
        assertTrue("pill: ${pill?.text}", pill?.text?.contains("0.00") == true)
        assertEquals(QuotaLevel.depleted, QuotaLevelRules.level(view, spent))
    }
}
