package com.openminis.app.quota

import com.openminis.app.ProductionSources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.util.Locale

/**
 * [T-provider-quota P2] Port of the iOS ProviderQuotaTests "Picker hints"
 * block ([T-quota-group-member-pill], [T-quota-composer-hint],
 * [T-quota-composer-windows], [T-quota-tag-level]) and the Android wiring of
 * the picker, Model Group page, nav bar dot and composer line.
 */
class QuotaHintsTest {

    private val now = 1_800_000_000_000L
    private val prov = QuotaProvenance(QuotaSource.officialQuery, QuotaConfidence.documented, "test")
    private val sub = QuotaSubjectID(QuotaVendor.deepSeek, "abcd1234abcd1234")
    private val hsub = QuotaSubjectID(QuotaVendor.codexSubscription, "h")

    private fun window(id: String, used: String) = QuotaWindow(
        id = id, isRolling = true, reportedUsedFraction = QuotaDecimalParser.parseString(used),
        observedAt = now, provenance = prov,
    )

    private fun pool(family: String, windows: List<QuotaWindow>, subject: QuotaSubjectID = hsub) = QuotaPool(
        id = QuotaPoolKey.of(subject, family), subjectId = subject, kind = QuotaPool.Kind.usageWindow,
        unit = QuotaUnit.PercentOnly, windows = windows, provenance = prov, observedAt = now,
    )

    private val shared5h = pool("primary", listOf(window("7d", "0.85")))
    private val spark = pool("model:spark", listOf(window("5h", "0.92")))
    private val hp = mapOf(shared5h.id to shared5h, spark.id to spark)
    private val hb = listOf(
        QuotaPoolBinding(shared5h.id, QuotaPoolBinding.Target.Subject, QuotaPoolBinding.Evidence.assumedSubjectWide),
        QuotaPoolBinding(spark.id, QuotaPoolBinding.Target.Model("gpt-5.3-codex-spark"), QuotaPoolBinding.Evidence.explicitUpstream),
    )
    private val sparkView = QuotaModelResolver.view("c", "GPT-5.3-Codex-Spark", hsub, hp, hb, now = now)
    private val otherView = QuotaModelResolver.view("c", "gpt-5.5", hsub, hp, hb, now = now)

    private val ds = DeepSeekQuotaAdapter.parse(
        """{"is_available":true,"balance_infos":[{"currency":"CNY","total_balance":"99.00"}]}""".toByteArray(), sub, now = now,
    )
    private val dsPools = ds.pools.associateBy { it.id }
    private val okDS = QuotaModelResolver.view("i1", "deepseek-chat", sub, dsPools, ds.bindings, now = now)

    @Test fun `model row hint only for a tight model-specific pool`() {
        assertNull("healthy shared balance: no hint (no green)", QuotaPickerHintRules.modelRow(okDS))
        assertNull("no data", QuotaPickerHintRules.modelRow(ModelQuotaView.empty("i", "m")))
        assertEquals(
            QuotaPickerHintRules.ModelSpecificHint(QuotaStatus.low, "5h 92%", false),
            QuotaPickerHintRules.modelRow(sparkView),
        )
        assertNull("only the shared pool is tight: the header shows it", QuotaPickerHintRules.modelRow(otherView))
    }

    @Test fun `member pill - own pool first, else the account, percent or amount`() {
        val sparkPill = QuotaPickerHintRules.memberPill(sparkView, hp)!!
        assertEquals("92% used", sparkPill.text)
        assertTrue(sparkPill.isPercent && sparkPill.isModelSpecific && sparkPill.tone == QuotaTone.caution && sparkPill.poolKey == spark.id)
        val otherPill = QuotaPickerHintRules.memberPill(otherView, hp)!!
        assertTrue(otherPill.text == "85% used" && !otherPill.isModelSpecific && otherPill.poolKey == shared5h.id)
        val dsPill = QuotaPickerHintRules.memberPill(okDS, dsPools, Locale.US)!!
        assertTrue(dsPill.toString(), !dsPill.isPercent && dsPill.text.contains("99") && !dsPill.text.contains("%") && dsPill.tone == QuotaTone.normal)
        assertNull(QuotaPickerHintRules.memberPill(ModelQuotaView.empty("i", "m"), emptyMap()))

        val old = QuotaPool(
            id = QuotaPoolKey.of(sub, "old"), subjectId = sub, kind = QuotaPool.Kind.prepaidBalance,
            unit = QuotaUnit.Currency("CNY"), amounts = QuotaAmounts(remaining = BigDecimal(5)), provenance = prov,
            observedAt = now - 86_400_000,
        )
        val expired = mapOf(old.id to old)
        val expiredView = QuotaModelResolver.view(
            "i", "m", sub, expired,
            listOf(QuotaPoolBinding(old.id, QuotaPoolBinding.Target.Subject, QuotaPoolBinding.Evidence.vendorDocumented)), now = now,
        )
        assertNull("expired data: no pill", QuotaPickerHintRules.memberPill(expiredView, expired))

        val over = pool("over", listOf(window("5h", "1.2")))
        val overView = QuotaModelResolver.view(
            "c", "x", hsub, mapOf(over.id to over),
            listOf(QuotaPoolBinding(over.id, QuotaPoolBinding.Target.Subject, QuotaPoolBinding.Evidence.assumedSubjectWide)), now = now,
        )
        val overPill = QuotaPickerHintRules.memberPill(overView, mapOf(over.id to over))!!
        assertTrue("overage shows the real usage with a red tag", overPill.text == "120% used" && overPill.level == QuotaLevel.depleted)
    }

    @Test fun `composer segment follows the member pill`() {
        assertEquals(
            QuotaComposerHintRules.Segment("Quota 92% used", "92% used", QuotaPickerHintRules.memberPill(sparkView, hp)!!.level),
            QuotaComposerHintRules.segment(sparkView, hp, now = now),
        )
        val segDS = QuotaComposerHintRules.segment(okDS, dsPools, now = now, locale = Locale.US)!!
        assertTrue(segDS.toString(), segDS.text == "Balance ${segDS.highlight}" && segDS.highlight.contains("99") && segDS.level == QuotaLevel.normal)
        assertEquals("Balance CN¥99.00", segDS.text)
        assertNull(QuotaComposerHintRules.segment(ModelQuotaView.empty("i", "m"), emptyMap(), now = now))

        val over = pool("over", listOf(window("5h", "1.2")))
        val overPools = mapOf(over.id to over)
        val overView = QuotaModelResolver.view(
            "c", "x", hsub, overPools,
            listOf(QuotaPoolBinding(over.id, QuotaPoolBinding.Target.Subject, QuotaPoolBinding.Evidence.assumedSubjectWide)), now = now,
        )
        assertEquals(QuotaLevel.depleted, QuotaComposerHintRules.segment(overView, overPools, now = now)?.level)
        assertEquals("a single window keeps the plain wording", "Quota 120% used", QuotaComposerHintRules.segment(overView, overPools, now = now)?.text)

        // [T-quota-composer-windows] 5h + 7d on one subscription pool: both, shorter first.
        val twoWin = pool("subscription", listOf(window("7d", "0.32"), window("5h", "0.14")))
        val twoPools = mapOf(twoWin.id to twoWin)
        val twoView = QuotaModelResolver.view(
            "c", "x", hsub, twoPools,
            listOf(QuotaPoolBinding(twoWin.id, QuotaPoolBinding.Target.Subject, QuotaPoolBinding.Evidence.assumedSubjectWide)), now = now,
        )
        val twoSeg = QuotaComposerHintRules.segment(twoView, twoPools, now = now)!!
        assertEquals("Quota 5h 14% / 7d 32% used", twoSeg.text)
        assertEquals("5h 14% / 7d 32%", twoSeg.highlight)
    }

    @Test fun `header text - balance with currency, never a percent, nothing when expired`() {
        val cny = ds.pools[0]
        val head = QuotaPickerHintRules.headerText(cny, QuotaStatusRules.poolStatus(cny, now), Locale.US)!!
        assertTrue(head, head.startsWith("Balance ") && head.contains("99") && !head.contains("%"))
        val old = cny.copy(observedAt = now - 86_400_000)
        assertNull(QuotaPickerHintRules.headerText(old, QuotaStatusRules.poolStatus(old, now)))
        val empty = cny.copy(amounts = null)
        assertNull("nothing to show", QuotaPickerHintRules.headerText(empty, QuotaStatusRules.poolStatus(empty, now)))
    }

    @Test fun `wiring - picker, group page, nav dot, composer`() {
        val picker = ProductionSources.read("ui/chat/ChatModelPickerSheet.kt")
        val header = picker.indexOf("QuotaSectionHeaderBadge(instance.id)")
        assertTrue("header pill after the provider name, before Edit", header > picker.indexOf("providerName,") &&
            header < picker.indexOf("R.string.model_picker_edit_provider"))
        assertEquals("member pill only on group rows", 1, picker.split("ModelQuotaMemberPill(").size - 1)
        assertTrue("provider rows get the model-specific hint on the second line", picker.contains("ModelQuotaHintView(\n"))
        assertTrue("picker hosts the quota sheet", picker.contains("QuotaDetailSheet(instanceId = id, onDismiss = { quotaSheetInstanceId = null })"))
        val nameBadge = picker.substring(picker.indexOf("private fun ModelNameWithActiveBadge("))
        assertTrue("pill sits between the name and the Active badge",
            nameBadge.indexOf("quotaPill?.invoke()") in 0 until nameBadge.indexOf("ModelActiveBadge()"))

        val group = ProductionSources.read("ui/settings/ModelGroupDetailScreen.kt")
        assertTrue(group.contains("ModelQuotaMemberPill(") && group.contains("if (instance != null && !providerDisabled)") &&
            group.contains("QuotaDetailSheet(instanceId = id"))

        val hints = ProductionSources.read("ui/quota/QuotaPickerHints.kt")
        assertTrue("attention: header, member pill and nav dot watch while on screen",
            hints.contains("QuotaWatch(instanceId, \"pickerAppeared\")") && hints.contains("QuotaWatch(instanceId, \"groupMemberAppeared\")") &&
                hints.contains("QuotaWatch(instanceId, \"chatNavBar\")"))
        assertTrue("tags: yellow / red by level", hints.contains("QuotaLevel.low -> if (dark) Color(0xFFFFD60A) else Color(0xFFFFCC00)"))
        assertTrue("pill tone never green", hints.contains("QuotaToneRules.pillTone(head.second)"))

        val screen = ProductionSources.read("ui/chat/ChatScreen.kt")
        assertTrue("nav dot is quota-aware", screen.contains("ui.quota.ModelQuotaStatusDot(") &&
            !screen.contains("if (modelName.isNotEmpty()) Color(0xFF34C759) else Color(0xFFFF9500)"))

        val vm = ProductionSources.read("ui/chat/ChatViewModel.kt")
        // [T-port-composer-hint-debug] The two real publish paths each attach
        // the quota part; the DEV_TOOLS-only debug.chat.usageHint path attaches
        // it only when asked for the live quota, and is counted on its own.
        fun body(signature: String) = vm.substringAfter(signature).substringBefore("\n    }\n")
        val withQuota = ".withQuota(composerQuotaPart())"
        assertTrue("loop-end / crossing hint carries the quota part",
            body("private fun publishContextUsage(crossingOnly: Boolean) {").contains(withQuota))
        assertTrue("post-compaction hint carries the quota part",
            body("private fun announceContextUsageAfterCompaction() {").contains(withQuota))
        val debug = body("internal fun debugShowContextUsageHint(")
        assertTrue("debug hint uses the live quota on request", debug.contains("if (useLiveQuota) base$withQuota else base"))
        assertEquals("every context hint carries the quota part (2 real paths + the debug path)",
            3, vm.split(withQuota).size - 1)
        assertEquals("no other ContextUsageHint is built in the view model",
            2, vm.split("_contextUsageHint.value = ContextUsageHint(").size - 1)
        val ui = ProductionSources.read("ui/chat/ContextUsageHintUi.kt")
        assertTrue("quota appended with ' · ' and tinted by its own tier",
            ui.contains("hint.quotaText?.let { \"\$context · \$it\" }") && ui.contains("textHighlight(hint.quotaTier)"))
        assertFalse(ui.contains("full.indexOf(segment)"))
    }
}
