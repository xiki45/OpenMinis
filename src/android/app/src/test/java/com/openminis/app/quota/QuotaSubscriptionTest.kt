package com.openminis.app.quota

import com.openminis.app.ProductionSources
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

/**
 * [T-quota-claude-subscription] [T-quota-codex-subscription] Port of the iOS
 * ProviderQuotaTests "Claude subscription", "Codex subscription" and
 * "Subscription refresh window" blocks, plus the Android wiring.
 */
class QuotaSubscriptionTest {

    private val now = 1_800_000_000_000L
    private fun same(a: BigDecimal?, b: String) = a != null && a.compareTo(BigDecimal(b)) == 0
    private fun desc(kind: QuotaInstanceDescriptor.ProviderKind, oauth: Boolean = false, base: String? = null) =
        QuotaInstanceDescriptor("i", "L", kind, oauth, true, base)

    private val csub = QuotaSubjectID(QuotaVendor.claudeSubscription, "instance:abc")
    private val claudeJSON = """{"five_hour":{"utilization":6.0,"resets_at":"2026-09-27T16:59:59.943648+00:00"},"seven_day":{"utilization":35.5,"resets_at":"2026-10-01T03:59:59+00:00"},"seven_day_opus":{"utilization":80.0,"resets_at":null},"seven_day_oauth_apps":null,"extra_usage":{"is_enabled":false}}"""
    private val cu = ClaudeSubscriptionQuotaAdapter.parseUsage(claudeJSON.toByteArray(), csub, now = now)
    private val cMain = cu.pools.first { it.id.rawValue.endsWith(":subscription") }

    @Test fun `Claude usage API`() {
        assertEquals(setOf("5h", "7d"), cMain.windows.map { it.id }.toSet())
        assertTrue("percent stored as a fraction", same(cMain.windows.first { it.id == "5h" }.reportedUsedFraction, "0.06"))
        assertNotNull("fractional-second resets_at parsed", cMain.windows.first { it.id == "5h" }.resetsAt)
        val opus = cu.pools.first { it.id.rawValue.endsWith(":model:opus") }
        assertTrue(cu.bindings.any {
            it.poolKey == opus.id && it.target == QuotaPoolBinding.Target.ModelFamily("claude-opus*") &&
                it.evidence == QuotaPoolBinding.Evidence.explicitUpstream
        })
        assertEquals("null window skipped, extra_usage ignored", 2, cu.pools.size)
        assertTrue(cu.provenance.source == QuotaSource.providerDashboardAPI && cu.provenance.confidence == QuotaConfidence.observedClient)
        assertEquals(
            QuotaError.Kind.unsupportedResponse,
            runCatching { ClaudeSubscriptionQuotaAdapter.parseUsage("{}".toByteArray(), csub, now = now) }.exceptionOrNull()
                .let { it as? QuotaFetchError }?.kind,
        )
    }

    @Test fun `Claude headers write the same pools and merge per window`() {
        val ch = ClaudeSubscriptionQuotaAdapter.parseHeaders(
            mapOf(
                "Anthropic-Ratelimit-Unified-Status" to "allowed_warning",
                "anthropic-ratelimit-unified-5h-utilization" to "0.42",
                "anthropic-ratelimit-unified-5h-reset" to "1800003600",
                "anthropic-ratelimit-unified-5h-status" to "allowed",
                "anthropic-ratelimit-unified-7d-utilization" to "0.91",
                "anthropic-ratelimit-unified-7d-reset" to "1800500000",
                "anthropic-ratelimit-unified-representative-claim" to "five_hour",
                "anthropic-ratelimit-requests-remaining" to "49",
            ),
            csub, now = now + 60_000,
        )!!
        val main = ch.pools.first()
        assertEquals("same pool key as the API", cMain.id, main.id)
        val w5 = main.windows.first { it.id == "5h" }
        assertTrue(same(w5.reportedUsedFraction, "0.42") && main.windows.map { it.id }.toSet() == setOf("5h", "7d") &&
            w5.resetsAt == 1_800_003_600_000L)
        assertEquals(QuotaPool.Verdict.warning, main.verdict)
        assertEquals(QuotaSource.officialHeaders, ch.provenance.source)
        assertNull("no unified window: no fake data",
            ClaudeSubscriptionQuotaAdapter.parseHeaders(mapOf("anthropic-ratelimit-requests-remaining" to "49"), csub, now = now))
        assertTrue("a percent-form header reads as 0.35", same(ClaudeSubscriptionQuotaAdapter.fraction(BigDecimal(35), false), "0.35"))

        val cp = mutableMapOf<QuotaPoolKey, QuotaPool>(); val cb = mutableListOf<QuotaPoolBinding>()
        QuotaMerge.ingest(cu, true, cp, cb)
        QuotaMerge.ingest(ch, false, cp, cb)
        assertTrue(same(cp[cMain.id]!!.windows.first { it.id == "5h" }.reportedUsedFraction, "0.42") && cp.size == 2)
        assertEquals(QuotaStatus.low, QuotaStatusRules.poolStatus(cp[cMain.id]!!, now + 60_000).status)
    }

    private class FakeTransport(private val body: String) : QuotaHttpTransport {
        val urls = mutableListOf<String>()
        val sentHeaders = mutableListOf<Map<String, String>>()
        override suspend fun get(url: String, bearer: String, headers: Map<String, String>): QuotaHttpResponse {
            urls += url; sentHeaders += headers
            return QuotaHttpResponse(200, body.toByteArray(), emptyMap())
        }
    }

    @Test fun `Claude active query - fixed URL, oauth beta header, CLI headers`() = runBlocking {
        val fake = FakeTransport(claudeJSON)
        ClaudeSubscriptionQuotaAdapter.fetch(
            QuotaFetchContext(desc(QuotaInstanceDescriptor.ProviderKind.anthropic, oauth = true), csub, "oauth-token", fake, now,
                extraHeaders = mapOf("User-Agent" to "claude-cli/x")),
        )
        assertEquals(listOf("https://api.anthropic.com/api/oauth/usage"), fake.urls)
        assertEquals("oauth-2025-04-20", fake.sentHeaders[0]["anthropic-beta"])
        assertEquals("claude-cli/x", fake.sentHeaders[0]["User-Agent"])
        val oauth = desc(QuotaInstanceDescriptor.ProviderKind.anthropic, oauth = true)
        val key = desc(QuotaInstanceDescriptor.ProviderKind.anthropic)
        assertTrue(QuotaAdapterRegistry.adapter(oauth).capability(oauth).active)
        assertFalse("API-key Anthropic stays unsupported", QuotaAdapterRegistry.adapter(key).capability(key).active)
        assertEquals(
            QuotaCapability.activeQuery(QuotaSource.providerDashboardAPI, QuotaConfidence.observedClient),
            QuotaAdapterRegistry.adapter(oauth).capability(oauth),
        )
    }

    private val xsub = QuotaSubjectID(QuotaVendor.codexSubscription, "instance:cdx")
    private val codexJSON = """{"plan_type":"plus","rate_limit":{"allowed":true,"limit_reached":false,"primary_window":{"used_percent":42,"limit_window_seconds":18000,"reset_after_seconds":3600,"reset_at":1800003600},"secondary_window":{"used_percent":81,"limit_window_seconds":604800,"reset_at":1800500000}},"credits":{"has_credits":true,"unlimited":false,"balance":"12.5"},"additional_rate_limits":[{"limit_name":"GPT-5.3-Codex-Spark","rate_limit":{"primary_window":{"used_percent":0,"limit_window_seconds":18000}}}]}"""

    @Test fun `Codex usage API and headers`() {
        val xu = CodexSubscriptionQuotaAdapter.parseUsage(codexJSON.toByteArray(), xsub, now = now)
        val main = xu.pools.first { it.id.rawValue.endsWith(":codex") }
        assertEquals(setOf("5h", "7d"), main.windows.map { it.id }.toSet())
        val w5 = main.windows.first { it.id == "5h" }
        assertTrue(same(w5.reportedUsedFraction, "0.42") && w5.resetsAt == 1_800_003_600_000L)
        val spark = xu.pools.first { it.id.rawValue.contains(":model:") }
        assertTrue(xu.bindings.any {
            it.poolKey == spark.id && it.target == QuotaPoolBinding.Target.Model("gpt-5.3-codex-spark") &&
                it.evidence == QuotaPoolBinding.Evidence.explicitUpstream
        })
        val credits = xu.pools.first { it.id.rawValue.endsWith(":credits") }
        assertTrue(credits.unit == QuotaUnit.Credits() && same(credits.amounts?.remaining, "12.5") && credits.kind == QuotaPool.Kind.prepaidBalance)
        assertEquals("plus", xu.plan?.rawId)
        // Codex (used_percent) and Claude (utilization) are both stored as used.
        assertTrue(same(cMain.windows.first { it.id == "5h" }.reportedUsedFraction, "0.06"))

        val xh = CodexSubscriptionQuotaAdapter.parseHeaders(
            mapOf(
                "x-codex-primary-used-percent" to "55.0", "x-codex-primary-window-minutes" to "300",
                "x-codex-primary-reset-after-seconds" to "1200",
                "X-Codex-Secondary-Used-Percent" to "83", "x-codex-secondary-window-minutes" to "10080",
                "x-codex-secondary-reset-at" to "1800600000",
            ),
            xsub, now = now,
        )!!
        assertEquals(main.id, xh.pools.first().id)
        assertEquals(setOf("5h", "7d"), xh.pools.first().windows.map { it.id }.toSet())
        assertEquals(now + 1_200_000, xh.pools.first().windows.first { it.id == "5h" }.resetsAt)
        assertNull(CodexSubscriptionQuotaAdapter.parseHeaders(mapOf("x-codex-primary-window-minutes" to "300"), xsub, now = now))
        assertEquals(listOf("5h", "7d", "1d", "90m"), listOf(300, 10080, 1440, 90).map(CodexSubscriptionQuotaAdapter::windowId))
        val oauth = desc(QuotaInstanceDescriptor.ProviderKind.openAI, oauth = true)
        assertTrue(QuotaAdapterRegistry.adapter(oauth).capability(oauth).active)
    }

    @Test fun `subscription refresh window - 5 minutes across both sources`() {
        val sp = QuotaRefreshPolicy.forVendor(QuotaVendor.claudeSubscription)
        assertTrue(sp.normalIntervalMs == 300_000L &&
            QuotaRefreshPolicy.forVendor(QuotaVendor.codexSubscription).normalIntervalMs == 300_000L &&
            QuotaRefreshPolicy.forVendor(QuotaVendor.deepSeek).normalIntervalMs == 600_000L)
        assertEquals(QuotaRefreshDecision.Allowed, QuotaRefreshGate.decide(null, now, false, sp))
        val obs = QuotaRefreshGate.recordObservation(null, now, sp)
        assertEquals("unattended: a header update restarts the hour",
            QuotaRefreshDecision.TooSoon(now + 3_600_000), QuotaRefreshGate.decide(obs, now + 1_800_000, false, sp, attended = false))
        assertFalse(QuotaRefreshGate.decide(obs, now + 240_000, false, sp).isAllowed)
        assertEquals(QuotaRefreshDecision.Allowed, QuotaRefreshGate.decide(obs, now + 300_000, false, sp))
        val activeOK = QuotaRefreshGate.recordSuccess(null, now, false, sp)
        val obsLater = QuotaRefreshGate.recordObservation(activeOK, now + 200_000, sp)
        assertFalse(QuotaRefreshGate.decide(obsLater, now + 400_000, false, sp).isAllowed)
        assertEquals(QuotaRefreshDecision.Allowed, QuotaRefreshGate.decide(obsLater, now + 500_000, false, sp))
        val cooled = QuotaRefreshGate.recordFailure(
            null, QuotaError(QuotaError.Kind.rateLimited, "429", 429, now, retryAfter = now + 3_600_000), now, false, sp,
        )
        val cooledObs = QuotaRefreshGate.recordObservation(cooled, now + 60_000, sp)
        assertEquals("an observation never lifts Retry-After",
            QuotaRefreshDecision.ServerCooldown(now + 3_600_000), QuotaRefreshGate.decide(cooledObs, now + 1_000_000, true, sp))
        assertEquals(QuotaRefreshDecision.Allowed, QuotaRefreshGate.decide(obs, now + 60_000, true, sp))
        assertTrue(QuotaCapabilityRules.failureDisablesEndpoint(QuotaError.Kind.forbidden, QuotaVendor.claudeSubscription) &&
            QuotaCapabilityRules.failureDisablesEndpoint(QuotaError.Kind.unauthorized, QuotaVendor.codexSubscription))
        assertTrue(!QuotaCapabilityRules.failureDisablesEndpoint(QuotaError.Kind.unauthorized, QuotaVendor.deepSeek) &&
            QuotaCapabilityRules.failureDisablesEndpoint(QuotaError.Kind.notFound, QuotaVendor.deepSeek))
    }

    @Test fun `wiring - providers feed the sink, store keys subscriptions by instance`() {
        val anthropic = ProductionSources.read("provider/anthropic/AnthropicProvider.kt")
        assertEquals("both Claude paths feed the sink", 2,
            anthropic.split("if (isOAuth) com.openminis.app.quota.QuotaObservationSink.recordAnthropicResponse(quotaInstanceId, response.headers)").size - 1)
        val openai = ProductionSources.read("provider/openai/OpenAIProvider.kt")
        val sink = openai.indexOf("QuotaObservationSink.recordCodexResponse(quotaInstanceId, response.headers)")
        assertTrue("Codex replies feed the sink before the error branch", sink > 0 &&
            sink < openai.indexOf("[T321] ← HTTP \${response.code} error body"))
        val factory = ProductionSources.read("provider/ProviderFactory.kt")
        assertTrue(factory.contains("if (isOAuth) anthropic.quotaInstanceId = instance.id") &&
            factory.contains("it.quotaInstanceId = instance.id"))
        val store = ProductionSources.read("quota/QuotaStore.kt")
        assertTrue(store.contains("subjectId = subscriptionSubject(adapter.vendor, instanceId)"))
        assertTrue("token fetched only after the gates", store.indexOf("val token = credentialSource()") >
            store.indexOf("QuotaRefreshGate.decide(\n                _state.value.subjects[subjectId]?.lastRefresh"))
        assertTrue(store.contains("QuotaRefreshGate.recordObservation(s.lastRefresh, now, policy)") &&
            store.contains("QuotaMerge.ingest(snapshot, authoritative = false"))
        assertTrue("the sink allocates nothing without usage headers", store.contains("if (!key.startsWith(\"anthropic-ratelimit-\")) continue"))
    }
}
