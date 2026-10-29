package com.openminis.app.quota

import com.openminis.app.ProductionSources
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.net.URI

/**
 * [T-quota-relay-probe] Port of the iOS ProviderQuotaTests "Relay probe"
 * block plus the Android wiring (store hint / capability record / UI).
 */
class QuotaRelayTest {

    private val now = 1_800_000_000_000L
    private val rsub = QuotaSubjectID(QuotaVendor.customRelay, "k")
    private fun same(a: BigDecimal?, b: String) = a != null && a.compareTo(BigDecimal(b)) == 0
    private fun desc(base: String?) =
        QuotaInstanceDescriptor("i", "L", QuotaInstanceDescriptor.ProviderKind.openAI, false, true, base)

    private class RouteTransport(private val routes: Map<String, Pair<Int, String>>) : QuotaHttpTransport {
        val hits = mutableListOf<String>()
        override suspend fun get(url: String, bearer: String, headers: Map<String, String>): QuotaHttpResponse {
            hits += url
            val (code, body) = routes[URI(url).path] ?: (404 to "404 page not found")
            return QuotaHttpResponse(code, body.toByteArray(), emptyMap())
        }
    }

    private fun run(t: RouteTransport, base: String = "https://relay.example.com/v1", hint: RelayKind? = null): Result<QuotaFetchOutcome> =
        runBlocking {
            runCatching {
                RelayQuotaAdapter.fetch(QuotaFetchContext(desc(base), rsub, "sk-relay", t, now, relayKindHint = hint))
            }
        }

    private val sub2Wallet = """{"isValid":true,"balance":12.34,"usage":{"total":{"actual_cost":3.2}}}"""
    private val newAPI = """{"code":true,"message":"ok","data":{"object":"token_usage","name":"Default Token","total_granted":1000000,"total_used":250000,"total_available":750000,"unlimited_quota":false,"expires_at":0}}"""
    private val billingUse = """{"object":"list","total_usage":1234}"""

    @Test fun `Sub2API detected by isValid in one request`() {
        val t = RouteTransport(mapOf("/v1/usage" to (200 to sub2Wallet)))
        val o = run(t).getOrThrow()
        assertEquals(RelayKind.sub2api, o.relayKind)
        assertEquals(listOf("https://relay.example.com/v1/usage"), t.hits)
        val p = o.snapshot.pools.first()
        assertTrue(same(p.amounts?.remaining, "12.34") && p.unit == QuotaUnit.Currency("USD"))
        assertTrue(o.snapshot.provenance.source == QuotaSource.relayAccountLedger &&
            o.snapshot.provenance.confidence == QuotaConfidence.thirdParty &&
            o.snapshot.provenance.relayHost == "relay.example.com")
    }

    @Test fun `New API detected on the second endpoint, quota units kept`() {
        val t = RouteTransport(mapOf("/api/usage/token" to (200 to newAPI)))
        val o = run(t).getOrThrow()
        assertEquals(RelayKind.newAPI, o.relayKind)
        assertEquals(2, t.hits.size)
        val p = o.snapshot.pools.first()
        assertTrue(p.unit == QuotaUnit.Custom("quota") && same(p.amounts?.total, "1000000"))
        assertEquals(0.25, QuotaBarRules.poolFraction(p)!!, 1e-9)
    }

    @Test fun `SPA HTML is not mistaken for a relay, billing detected`() {
        val html = "<!doctype html><html><body>SPA</body></html>"
        val t = RouteTransport(mapOf(
            "/v1/usage" to (200 to html), "/api/usage/token" to (200 to html),
            "/v1/dashboard/billing/subscription" to (200 to """{"object":"billing_subscription","hard_limit_usd":50,"has_payment_method":true}"""),
            "/v1/dashboard/billing/usage" to (200 to billingUse),
        ))
        val o = run(t).getOrThrow()
        assertEquals(RelayKind.openAIBilling, o.relayKind)
        val p = o.snapshot.pools.first()
        assertTrue(p.kind == QuotaPool.Kind.spendCap && same(p.amounts?.total, "50") &&
            same(p.amounts?.used, "12.34") && same(p.amounts?.remaining, "37.66"))

        val t3b = RouteTransport(mapOf(
            "/v1/dashboard/billing/subscription" to (200 to """{"hard_limit_usd":100000000}"""),
            "/v1/dashboard/billing/usage" to (200 to billingUse),
        ))
        val u = run(t3b).getOrThrow().snapshot.pools.first()
        assertTrue("1e8 = unlimited, no fake cap", u.unlimited && u.amounts?.total == null)
    }

    @Test fun `nothing answers, every family refuses, hints and path prefixes`() {
        val none = run(RouteTransport(emptyMap())).exceptionOrNull() as QuotaFetchError
        assertTrue(none.kind == QuotaError.Kind.notFound && none.detail.contains("/v1/usage") &&
            none.detail.contains("/api/usage/token") && none.detail.contains("/v1/dashboard/billing/subscription"))
        val all401 = run(RouteTransport(mapOf(
            "/v1/usage" to (401 to "{}"), "/api/usage/token" to (401 to "{}"), "/v1/dashboard/billing/subscription" to (401 to "{}"),
        ))).exceptionOrNull() as QuotaFetchError
        assertEquals("the key, not a missing endpoint", QuotaError.Kind.unauthorized, all401.kind)

        val t6 = RouteTransport(mapOf("/api/usage/token" to (200 to newAPI), "/v1/usage" to (200 to sub2Wallet)))
        val o6 = run(t6, hint = RelayKind.newAPI).getOrThrow()
        assertTrue("a known type skips the probe", o6.relayKind == RelayKind.newAPI && t6.hits == listOf("https://relay.example.com/api/usage/token"))
        val t7 = RouteTransport(mapOf("/api/usage/token" to (401 to "{}")))
        val e7 = run(t7, hint = RelayKind.newAPI).exceptionOrNull() as QuotaFetchError
        assertTrue("no re-probe storm", e7.kind == QuotaError.Kind.unauthorized && t7.hits.size == 1)
        val t8 = RouteTransport(mapOf("/gw/v1/usage" to (200 to sub2Wallet)))
        val o8 = run(t8, base = "https://relay.example.com/gw/v1/").getOrThrow()
        assertTrue("root keeps a path prefix, drops the trailing /v1",
            o8.relayKind == RelayKind.sub2api && t8.hits == listOf("https://relay.example.com/gw/v1/usage"))
    }

    @Test fun `Sub2API modes and capability`() {
        val ql = RelayQuotaAdapter.parseSub2API(
            """{"isValid":true,"mode":"quota_limited","quota":{"limit":20,"remaining":5,"used":15}}""".toByteArray(), rsub, "r", now,
        ).pools.first()
        assertTrue(ql.kind == QuotaPool.Kind.spendCap)
        assertEquals(0.75, QuotaBarRules.poolFraction(ql)!!, 1e-9)
        val unl = RelayQuotaAdapter.parseSub2API(
            """{"isValid":true,"remaining":-1,"usage":{"total":{"actual_cost":2}}}""".toByteArray(), rsub, "r", now,
        ).pools.first()
        assertTrue(unl.unlimited)
        assertTrue(RelayQuotaAdapter.capability(desc("https://r.example.com/v1")).active)
        assertFalse(RelayQuotaAdapter.capability(desc(null)).active)
        assertEquals(
            QuotaCapability.activeQuery(QuotaSource.relayAccountLedger, QuotaConfidence.thirdParty),
            QuotaAdapterRegistry.adapter(desc("https://relay.example.com/v1")).capability(desc("https://relay.example.com/v1")),
        )
        assertTrue(RelayKind.entries.all { RelayKind.fromAdapterId(it.adapterId) == it })
        assertEquals(null, RelayKind.fromAdapterId("deepseek.balance"))
    }

    @Test fun `relay detection text from the capability record`() {
        val d = desc("https://relay.example.com/v1")
        val key = d.capabilityKey
        val found = QuotaState(capabilities = mapOf(key to QuotaCapabilityRecord(
            key, RelayKind.sub2api.adapterId, QuotaCapabilityRecord.Status.supported, probedAt = now, detail = "Sub2API · /v1/usage",
        )))
        assertEquals(QuotaState.RelayDetection("Sub2API · /v1/usage", true, now), found.relayDetection(d))
        val failed = QuotaState(capabilities = mapOf(key to QuotaCapabilityRecord(
            key, "relay.probe", QuotaCapabilityRecord.Status.failed, probedAt = now, detail = "no balance endpoint",
        )))
        assertEquals(QuotaState.RelayDetection("no balance endpoint", false, now), failed.relayDetection(d))
        assertEquals("not a relay", null, found.relayDetection(desc("https://api.deepseek.com/v1")))
    }

    @Test fun `wiring - hint reuse, detected type recorded, shown in footer and sheet`() {
        val store = ProductionSources.read("quota/QuotaStore.kt")
        assertTrue(store.contains("RelayKind.fromAdapterId(it.adapterId)") && store.contains("relayHint,"))
        assertTrue(store.contains("adapterId = relayKind?.adapterId ?: adapter.adapterId") &&
            store.contains("detail = relayKind?.let { \"\${it.displayName} · \${it.path}\" }"))
        val section = ProductionSources.read("ui/quota/ProviderQuotaSection.kt")
        assertTrue(section.contains("R.string.quota_relay_type_detected") && section.contains("R.string.quota_relay_none_found"))
        assertTrue(section.contains("stringResource(R.string.quota_relay_type)"))
    }
}
