package com.openminis.app.quota

import org.junit.Assert.assertEquals
import org.junit.Test
import java.math.BigDecimal

/**
 * [T-quota-subscription-crossreview] The Claude / ChatGPT (Codex) subscription
 * path pinned to iOS, byte for byte. Neither subscription can be signed in on
 * the test device, so these fixtures stand in for real replies:
 * - Claude reply headers as sent on a 200 and on a 429 (the unified set plus
 *   the neighbours the parser must ignore: representative-claim,
 *   fallback-percentage, the overall reset, overage-*), a family window in
 *   both spellings (7d_opus, 7d-sonnet) and a percent-form value;
 * - the /api/oauth/usage body with null windows, a zero window, a
 *   seven_day_sonnet window, an unknown key and extra_usage;
 * - Codex x-codex-* headers on a 200 (with the credits / over-limit headers
 *   iOS ignores) and a 429, and wham/usage bodies: limit reached with a null
 *   secondary, credits only, nothing usable, a window without a length and an
 *   additional limit;
 * - an active result followed by fresher headers (the merge).
 *
 * EXPECTED is the output of the SAME fixtures run through the shipping iOS
 * sources (QuotaModels / QuotaRules / QuotaAdapters / QuotaPersistence
 * compiled with swiftc) and the same dump, captured on 2026-09-30. A
 * difference means the platforms read a real reply differently.
 */
class QuotaSubscriptionParityTest {

    private val now = 1_800_000_000_000L
    private val csub = QuotaSubjectID(QuotaVendor.claudeSubscription, "instance:c1")
    private val xsub = QuotaSubjectID(QuotaVendor.codexSubscription, "instance:x1")

    private fun dec(d: BigDecimal?) = d?.stripTrailingZeros()?.toPlainString() ?: "-"
    private fun secs(ms: Long?) = ms?.let { Math.floorDiv(it, 1000L).toString() } ?: "-"
    private fun target(t: QuotaPoolBinding.Target) = when (t) {
        QuotaPoolBinding.Target.Subject -> "subject"
        is QuotaPoolBinding.Target.Model -> "model(${t.key})"
        is QuotaPoolBinding.Target.ModelFamily -> "family(${t.pattern})"
        is QuotaPoolBinding.Target.Product -> "product(${t.name})"
        else -> "other"
    }
    private fun family(key: QuotaPoolKey, s: QuotaSubjectID) = key.rawValue.removePrefix("$s:")

    private fun dump(name: String, snap: UpstreamQuotaSnapshot?, s: QuotaSubjectID): String {
        if (snap == null) return "$name: nil"
        val lines = mutableListOf("plan=${snap.plan?.rawId ?: "-"} source=${snap.provenance.source.name}")
        for (p in snap.pools.sortedBy { it.id.rawValue }) {
            val ws = p.windows.sortedBy { it.id }.joinToString(",") {
                "${it.id}/${it.durationSeconds?.toString() ?: "-"}/${dec(it.reportedUsedFraction)}/${secs(it.resetsAt)}"
            }
            lines += "pool ${family(p.id, s)} kind=${p.kind.name} label=${p.vendorLabel ?: "-"} verdict=${p.verdict?.name ?: "-"} " +
                "unlimited=${p.unlimited} remaining=${dec(p.amounts?.remaining)} windows=[$ws]"
            val st = QuotaStatusRules.poolStatus(p, now + 60_000)
            lines += "  status=${st.status.name} deciding=${st.decidingWindowId ?: "-"} pct=${dec(st.utilization?.percent)} " +
                "figure=${QuotaComposerHintRules.windowsFigure(p, now + 60_000) ?: "-"}"
        }
        for (b in snap.bindings.sortedBy { it.poolKey.rawValue }) {
            lines += "bind ${family(b.poolKey, s)} -> ${target(b.target)} ${b.evidence.name}"
        }
        return "$name:\n" + lines.joinToString("\n") { "  $it" }
    }

    private fun usage(name: String, json: String, claude: Boolean): String = try {
        val snap = if (claude) ClaudeSubscriptionQuotaAdapter.parseUsage(json.toByteArray(), csub, now = now)
        else CodexSubscriptionQuotaAdapter.parseUsage(json.toByteArray(), xsub, now = now)
        dump(name, snap, if (claude) csub else xsub)
    } catch (e: QuotaFetchError) {
        "$name: error ${e.kind.name}"
    }

    private fun run(): String {
        val out = mutableListOf<String>()
        out += dump("C1", ClaudeSubscriptionQuotaAdapter.parseHeaders(mapOf(
            "anthropic-ratelimit-unified-status" to "allowed",
            "anthropic-ratelimit-unified-5h-status" to "allowed",
            "anthropic-ratelimit-unified-5h-reset" to "1800010800",
            "anthropic-ratelimit-unified-5h-utilization" to "0.0712",
            "anthropic-ratelimit-unified-7d-status" to "allowed",
            "anthropic-ratelimit-unified-7d-reset" to "1800400000",
            "anthropic-ratelimit-unified-7d-utilization" to "0.3521",
            "anthropic-ratelimit-unified-representative-claim" to "five_hour",
            "anthropic-ratelimit-unified-fallback-percentage" to "0.5",
            "anthropic-ratelimit-unified-reset" to "1800010800",
            "anthropic-ratelimit-unified-overage-status" to "rejected",
            "anthropic-ratelimit-unified-overage-disabled-reason" to "org_level_disabled",
        ), csub, now = now), csub)
        out += dump("C2", ClaudeSubscriptionQuotaAdapter.parseHeaders(mapOf(
            "Anthropic-Ratelimit-Unified-Status" to "rejected",
            "anthropic-ratelimit-unified-5h-status" to "rejected",
            "anthropic-ratelimit-unified-5h-reset" to "1800003600",
            "anthropic-ratelimit-unified-5h-utilization" to "1.0",
            "anthropic-ratelimit-unified-7d-status" to "allowed_warning",
            "anthropic-ratelimit-unified-7d-reset" to "1800400000",
            "anthropic-ratelimit-unified-7d-utilization" to "0.84",
            "anthropic-ratelimit-unified-representative-claim" to "five_hour",
            "retry-after" to "3600",
        ), csub, now = now), csub)
        out += dump("C3", ClaudeSubscriptionQuotaAdapter.parseHeaders(mapOf(
            "anthropic-ratelimit-unified-status" to "allowed_warning",
            "anthropic-ratelimit-unified-5h-utilization" to "12",
            "anthropic-ratelimit-unified-7d_opus-utilization" to "0.95",
            "anthropic-ratelimit-unified-7d_opus-reset" to "1800400000",
            "anthropic-ratelimit-unified-7d-sonnet-utilization" to "0.5",
        ), csub, now = now), csub)
        out += usage("C4", """{"five_hour":{"utilization":7.0,"resets_at":"2027-01-15T11:00:00.428917+00:00"},"seven_day":{"utilization":35,"resets_at":"2027-01-19T08:00:00Z"},"seven_day_oauth_apps":null,"seven_day_opus":{"utilization":0.0,"resets_at":null},"seven_day_sonnet":{"utilization":12.5,"resets_at":"2027-01-19T08:00:00.94+00:00"},"iguana_necktie":null,"extra_usage":{"is_enabled":true,"monthly_limit":5000,"used_credits":120,"utilization":2.4}}""", claude = true)
        out += dump("C5", CodexSubscriptionQuotaAdapter.parseHeaders(mapOf(
            "x-codex-primary-used-percent" to "12.0",
            "x-codex-primary-window-minutes" to "300",
            "x-codex-primary-reset-at" to "1800010800",
            "x-codex-secondary-used-percent" to "3",
            "x-codex-secondary-window-minutes" to "10080",
            "x-codex-secondary-reset-after-seconds" to "400000",
            "x-codex-primary-over-secondary-limit-percent" to "0",
            "x-codex-credits-has-credits" to "False",
            "x-codex-credits-unlimited" to "False",
            "x-codex-credits-balance" to "",
        ), xsub, now = now), xsub)
        out += dump("C6", CodexSubscriptionQuotaAdapter.parseHeaders(mapOf(
            "X-Codex-Primary-Used-Percent" to "100.0",
            "x-codex-primary-window-minutes" to "300",
            "x-codex-primary-reset-after-seconds" to "1800",
        ), xsub, now = now), xsub)
        out += usage("C7", """{"plan_type":"pro","rate_limit":{"allowed":false,"limit_reached":true,"primary_window":{"used_percent":100,"limit_window_seconds":18000,"reset_after_seconds":1800,"reset_at":1800001800},"secondary_window":null},"credits":{"has_credits":false,"unlimited":false,"balance":"0"},"additional_rate_limits":[]}""", claude = false)
        out += usage("C8", """{"plan_type":"team","rate_limit":null,"credits":{"has_credits":true,"unlimited":true,"balance":null}}""", claude = false)
        out += usage("C9", """{"plan_type":"free","rate_limit":{"allowed":true,"limit_reached":false,"primary_window":null,"secondary_window":null}}""", claude = false)
        out += usage("C10", """{"plan_type":"plus","rate_limit":{"allowed":true,"primary_window":{"used_percent":"55.5","reset_after_seconds":600},"secondary_window":{"used_percent":20,"limit_window_seconds":86400}},"additional_rate_limits":[{"limit_name":"GPT-5.3-Codex-Spark","rate_limit":{"allowed":true,"limit_reached":false,"primary_window":{"used_percent":90,"limit_window_seconds":18000,"reset_at":1800005000}}},{"limit_name":"","rate_limit":{"primary_window":{"used_percent":1}}}]}""", claude = false)

        // M1: an active result, then fresher headers 60 s later.
        val api = ClaudeSubscriptionQuotaAdapter.parseUsage(
            """{"five_hour":{"utilization":7.0,"resets_at":"2027-01-15T11:00:00Z"},"seven_day":{"utilization":35,"resets_at":"2027-01-19T08:00:00Z"},"seven_day_opus":{"utilization":20,"resets_at":null}}""".toByteArray(),
            csub, now = now,
        )
        val hdr = ClaudeSubscriptionQuotaAdapter.parseHeaders(mapOf(
            "anthropic-ratelimit-unified-status" to "allowed_warning",
            "anthropic-ratelimit-unified-5h-utilization" to "0.81",
            "anthropic-ratelimit-unified-5h-reset" to "1800010800",
        ), csub, now = now + 60_000)!!
        val pools = mutableMapOf<QuotaPoolKey, QuotaPool>()
        val binds = mutableListOf<QuotaPoolBinding>()
        QuotaMerge.ingest(api, authoritative = true, pools = pools, bindings = binds)
        QuotaMerge.ingest(hdr, authoritative = false, pools = pools, bindings = binds)
        out += dump("M1", api.copy(pools = pools.values.toList(), bindings = binds), csub)
        return out.joinToString("\n") + "\n"
    }

    @Test
    fun `every fixture reads exactly as it does on iOS`() {
        val actual = run().lines()
        val expected = EXPECTED.lines()
        // Line by line, so a failure names the fixture and field that differ.
        for (i in 0 until maxOf(actual.size, expected.size)) {
            assertEquals("line ${i + 1}", expected.getOrNull(i), actual.getOrNull(i))
        }
    }

    @Test
    fun `quota bookkeeping can never fail the chat request that carried the headers`() {
        val store = com.openminis.app.ProductionSources.read("quota/QuotaStore.kt")
        val sink = store.substringAfter("object QuotaObservationSink")
        // Both entry points go through the guarded helper, never the store directly.
        assertEquals(2, Regex("""\bobserve\(instanceId, QuotaVendor\.""").findAll(sink).count())
        assertEquals(1, Regex("""QuotaStore\.observeSubscriptionHeaders\(""").findAll(sink).count())
        val helper = sink.substringAfter("private fun observe(")
        assertEquals(true, helper.indexOf("try {") in 0 until helper.indexOf("QuotaStore.observeSubscriptionHeaders("))
        assertEquals(true, helper.contains("catch (e: Exception)"))
    }

    private companion object {
        /** iOS output for the fixtures above (see the class doc). */
        val EXPECTED = """
C1:
  plan=- source=officialHeaders
  pool subscription kind=usageWindow label=Claude subscription verdict=allowed unlimited=false remaining=- windows=[5h/18000/0.0712/1800010800,7d/604800/0.3521/1800400000]
    status=available deciding=7d pct=35.21 figure=5h 7.1% / 7d 35%
  bind subscription -> subject assumedSubjectWide
C2:
  plan=- source=officialHeaders
  pool subscription kind=usageWindow label=Claude subscription verdict=rejected unlimited=false remaining=- windows=[5h/18000/1/1800003600,7d/604800/0.84/1800400000]
    status=exhausted deciding=- pct=- figure=5h 100% / 7d 84%
  bind subscription -> subject assumedSubjectWide
C3:
  plan=- source=officialHeaders
  pool model:opus kind=usageWindow label=model:opus verdict=- unlimited=false remaining=- windows=[7d/604800/0.95/1800400000]
    status=low deciding=7d pct=95 figure=-
  pool model:sonnet kind=usageWindow label=model:sonnet verdict=- unlimited=false remaining=- windows=[7d/604800/0.5/-]
    status=available deciding=7d pct=50 figure=-
  pool subscription kind=usageWindow label=Claude subscription verdict=warning unlimited=false remaining=- windows=[5h/18000/0.12/-]
    status=low deciding=5h pct=12 figure=-
  bind model:opus -> family(claude-opus*) explicitUpstream
  bind model:sonnet -> family(claude-sonnet*) explicitUpstream
  bind subscription -> subject assumedSubjectWide
C4:
  plan=- source=providerDashboardAPI
  pool model:opus kind=usageWindow label=model:opus verdict=- unlimited=false remaining=- windows=[7d/604800/0/-]
    status=available deciding=7d pct=0 figure=-
  pool model:sonnet kind=usageWindow label=model:sonnet verdict=- unlimited=false remaining=- windows=[7d/604800/0.125/1800345600]
    status=available deciding=7d pct=12.5 figure=-
  pool subscription kind=usageWindow label=Claude subscription verdict=- unlimited=false remaining=- windows=[5h/18000/0.07/1800010800,7d/604800/0.35/1800345600]
    status=available deciding=7d pct=35 figure=5h 7% / 7d 35%
  bind model:opus -> family(claude-opus*) explicitUpstream
  bind model:sonnet -> family(claude-sonnet*) explicitUpstream
  bind subscription -> subject assumedSubjectWide
C5:
  plan=- source=officialHeaders
  pool codex kind=usageWindow label=ChatGPT subscription verdict=- unlimited=false remaining=- windows=[5h/18000/0.12/1800010800,7d/604800/0.03/1800400000]
    status=available deciding=5h pct=12 figure=5h 12% / 7d 3%
  bind codex -> subject assumedSubjectWide
C6:
  plan=- source=officialHeaders
  pool codex kind=usageWindow label=ChatGPT subscription verdict=- unlimited=false remaining=- windows=[5h/18000/1/1800001800]
    status=exhausted deciding=5h pct=100 figure=-
  bind codex -> subject assumedSubjectWide
C7:
  plan=pro source=providerDashboardAPI
  pool codex kind=usageWindow label=ChatGPT subscription verdict=rejected unlimited=false remaining=- windows=[5h/18000/1/1800001800]
    status=exhausted deciding=- pct=- figure=-
  bind codex -> subject assumedSubjectWide
C8:
  plan=team source=providerDashboardAPI
  pool credits kind=prepaidBalance label=Credits verdict=- unlimited=true remaining=- windows=[]
    status=unknown deciding=- pct=- figure=-
  bind credits -> subject assumedSubjectWide
C9: error unsupportedResponse
C10:
  plan=plus source=providerDashboardAPI
  pool codex kind=usageWindow label=ChatGPT subscription verdict=allowed unlimited=false remaining=- windows=[1d/86400/0.2/-,primary/-/0.555/1800000600]
    status=available deciding=primary pct=55.5 figure=1d 20% / primary 56%
  pool model:gpt-5.3-codex-spark kind=usageWindow label=model:gpt-5.3-codex-spark verdict=allowed unlimited=false remaining=- windows=[5h/18000/0.9/1800005000]
    status=low deciding=5h pct=90 figure=-
  bind codex -> subject assumedSubjectWide
  bind model:gpt-5.3-codex-spark -> model(gpt-5.3-codex-spark) explicitUpstream
M1:
  plan=- source=providerDashboardAPI
  pool model:opus kind=usageWindow label=model:opus verdict=- unlimited=false remaining=- windows=[7d/604800/0.2/-]
    status=available deciding=7d pct=20 figure=-
  pool subscription kind=usageWindow label=Claude subscription verdict=warning unlimited=false remaining=- windows=[5h/18000/0.81/1800010800,7d/604800/0.35/1800345600]
    status=low deciding=5h pct=81 figure=5h 81% / 7d 35%
  bind model:opus -> family(claude-opus*) explicitUpstream
  bind subscription -> subject assumedSubjectWide
""".trimStart('\n')
    }
}
