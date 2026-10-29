package com.openminis.app.quota

import com.openminis.app.ProductionSources
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.util.Locale

/**
 * [T-provider-quota] Port of iOS ProviderQuotaTests.swift (part A: model,
 * rules, adapters, persistence; part B: the wiring that has an Android
 * counterpart).
 */
class ProviderQuotaTest {

    private fun dec(s: String) = BigDecimal(s)
    private fun same(a: BigDecimal?, b: String) = a != null && a.compareTo(BigDecimal(b)) == 0
    private val now = 1_800_000_000_000L
    private val sub = QuotaSubjectID(QuotaVendor.deepSeek, "abcd1234abcd1234")
    private val prov = QuotaProvenance(QuotaSource.officialQuery, QuotaConfidence.documented, "test")

    private fun pool(
        family: String,
        kind: QuotaPool.Kind,
        unit: QuotaUnit = QuotaUnit.Currency("USD"),
        amounts: QuotaAmounts? = null,
        windows: List<QuotaWindow> = emptyList(),
        verdict: QuotaPool.Verdict? = null,
        subject: QuotaSubjectID = sub,
        at: Long = now,
        adapterId: String = "test",
        unlimited: Boolean = false,
    ) = QuotaPool(
        id = QuotaPoolKey.of(subject, family), subjectId = subject, kind = kind, unit = unit,
        amounts = amounts, windows = windows, verdict = verdict, unlimited = unlimited,
        provenance = prov.copy(adapterId = adapterId), observedAt = at,
    )

    private fun window(id: String, used: String? = null, remaining: String? = null, resets: Long? = null, at: Long = now) =
        QuotaWindow(
            id = id, isRolling = true, reportedUsedFraction = used?.let(QuotaDecimalParser::parseString),
            reportedRemainingFraction = remaining?.let(QuotaDecimalParser::parseString), resetsAt = resets, observedAt = at, provenance = prov,
        )

    private fun http(status: Int, body: String, headers: Map<String, String> = emptyMap()) =
        QuotaHttpResponse(status, body.toByteArray(), headers)

    private inline fun <reified T : Throwable> thrown(block: () -> Unit): T? =
        try { block(); null } catch (e: Throwable) { e as? T }

    private val dsJSON = """{"is_available":true,"balance_infos":[{"currency":"CNY","total_balance":"110.00","granted_balance":"10.00","topped_up_balance":"100.00"},{"currency":"USD","total_balance":"3.50","granted_balance":"0.00","topped_up_balance":"3.50"}]}"""
    private val ds by lazy { DeepSeekQuotaAdapter.parse(dsJSON.toByteArray(), sub, now = now) }
    private val cny by lazy { ds.pools.first { it.unit == QuotaUnit.Currency("CNY") } }
    private val usd by lazy { ds.pools.first { it.unit == QuotaUnit.Currency("USD") } }

    // ── Decimal parsing ────────────────────────────────────────────────────

    @Test fun `decimal parsing never turns junk into zero`() {
        assertTrue(same(QuotaDecimalParser.parseString("110.00"), "110"))
        assertTrue("JSON number 0.1 stays exactly 0.1", same(QuotaDecimalParser.parse(JsonPrimitive(0.1)), "0.1"))
        assertNull("\"12abc\" is not its numeric prefix", QuotaDecimalParser.parseString("12abc"))
        assertNull(QuotaDecimalParser.parseString(""))
        assertNull("no digit", QuotaDecimalParser.parseString("-"))
        assertNull("a boolean is never an amount", QuotaDecimalParser.parse(JsonPrimitive(true)))
        assertNull("null / missing is null, never 0", QuotaDecimalParser.parse(null))
        assertNull(QuotaDecimalParser.parse(kotlinx.serialization.json.JsonNull))
    }

    // ── DeepSeek ────────────────────────────────────────────────────────────

    @Test fun `DeepSeek keeps one pool per currency, never summed`() {
        assertEquals(2, ds.pools.size)
        assertTrue(cny.id != usd.id && cny.id.rawValue.endsWith(":balance:CNY") && usd.id.rawValue.endsWith(":balance:USD"))
        assertTrue(same(cny.amounts?.remaining, "110"))
        assertEquals(
            listOf(QuotaAmounts.Role.Granted, QuotaAmounts.Role.ToppedUp),
            cny.amounts?.breakdown?.map { it.role },
        )
        assertTrue(same(cny.amounts!!.breakdown[0].amount, "10") && same(cny.amounts!!.breakdown[1].amount, "100"))
        assertTrue(same(usd.amounts?.remaining, "3.5"))
        assertTrue(cny.kind == QuotaPool.Kind.prepaidBalance && cny.amounts?.total == null)
        assertTrue(cny.verdict == QuotaPool.Verdict.allowed && ds.accountAvailable == true)
        assertTrue(ds.bindings.size == 2 && ds.bindings.all {
            it.target == QuotaPoolBinding.Target.Subject && it.evidence == QuotaPoolBinding.Evidence.vendorDocumented
        })
        assertTrue(ds.provenance.source == QuotaSource.officialQuery && ds.provenance.confidence == QuotaConfidence.documented)
    }

    @Test fun `DeepSeek unavailable and malformed answers`() {
        val off = DeepSeekQuotaAdapter.parse(
            """{"is_available":false,"balance_infos":[{"currency":"CNY","total_balance":"0.00","granted_balance":"0.00","topped_up_balance":"0.00"}]}""".toByteArray(),
            sub, now = now,
        )
        assertEquals(QuotaPool.Verdict.rejected, off.pools[0].verdict)
        assertEquals(QuotaStatus.exhausted, QuotaStatusRules.poolStatus(off.pools[0], now).status)
        assertEquals(
            QuotaError.Kind.unsupportedResponse,
            thrown<QuotaFetchError> { DeepSeekQuotaAdapter.parse("""{"foo":1}""".toByteArray(), sub, now = now) }?.kind,
        )
        assertEquals(
            QuotaError.Kind.unsupportedResponse,
            thrown<QuotaFetchError> { DeepSeekQuotaAdapter.parse("<html>".toByteArray(), sub, now = now) }?.kind,
        )
        assertNotNull(
            "an unparseable amount is an error, not a CN¥0 balance",
            thrown<QuotaFetchError> {
                DeepSeekQuotaAdapter.parse("""{"is_available":true,"balance_infos":[{"currency":"CNY","total_balance":"n/a"}]}""".toByteArray(), sub, now = now)
            },
        )
    }

    // ── Moonshot / SiliconFlow / OpenRouter ─────────────────────────────────

    @Test fun `Moonshot and SiliconFlow`() {
        val ms = MoonshotQuotaAdapter.parse(
            """{"code":0,"data":{"available_balance":49.58894,"voucher_balance":46.58893,"cash_balance":3.00001},"status":true}""".toByteArray(),
            "api.moonshot.cn", sub, now = now,
        )
        assertEquals(QuotaUnit.Currency("CNY"), ms.pools[0].unit)
        assertTrue(same(ms.pools[0].amounts?.remaining, "49.58894"))
        assertEquals("USD", MoonshotQuotaAdapter.currencyForHost("api.moonshot.ai"))
        val sf = SiliconFlowQuotaAdapter.parse(
            """{"code":20000,"status":true,"data":{"id":"u1","name":"Alice","email":"a@b.c","balance":"0.88","chargeBalance":"88.00","totalBalance":"88.88"}}""".toByteArray(),
            "api.siliconflow.cn", sub, now = now,
        )
        assertTrue(same(sf.pools[0].amounts?.remaining, "88.88"))
        val dump = kotlinx.serialization.json.Json.encodeToString(UpstreamQuotaSnapshot.serializer(), sf)
        assertFalse("no PII kept", dump.contains("Alice") || dump.contains("a@b.c"))
    }

    @Test fun `OpenRouter key limit, credits and partial failure`() {
        val orSub = QuotaSubjectID(QuotaVendor.openRouter, "k")
        val noLimit = OpenRouterQuotaAdapter.combine(
            Result.success(http(200, """{"data":{"label":"x","limit":null,"usage":3.2,"limit_remaining":null}}""")),
            Result.success(http(200, """{"data":{"total_credits":50,"total_usage":8.5}}""")), orSub, now = now,
        )
        assertFalse(noLimit.snapshot.pools.any { it.id.rawValue.endsWith(":key-limit") })
        val credits = noLimit.snapshot.pools.first { it.id.rawValue.endsWith(":account-credits") }
        assertTrue(same(credits.amounts?.remaining, "41.5") && credits.kind == QuotaPool.Kind.prepaidBalance)
        assertTrue(noLimit.isComplete)

        val capped = OpenRouterQuotaAdapter.combine(
            Result.success(http(200, """{"data":{"limit":10,"usage":3.2,"limit_remaining":6.8}}""")),
            Result.failure(QuotaFetchError(QuotaError.Kind.forbidden, "403", 403)), orSub, now = now,
        )
        val cap = capped.snapshot.pools.first { it.id.rawValue.endsWith(":key-limit") }
        assertTrue(cap.kind == QuotaPool.Kind.spendCap && same(cap.amounts?.total, "10") && same(cap.amounts?.remaining, "6.8"))
        assertTrue(!capped.isComplete && capped.partialErrors.first().kind == QuotaError.Kind.forbidden)
        val u = cap.amounts!!.computedUtilization(QuotaPool.Kind.spendCap, false)!!
        assertTrue(same(u.percent, "32") && u.basis == QuotaUtilization.Basis.computed)
        assertEquals(
            "both fail: the auth error is thrown",
            QuotaError.Kind.unauthorized,
            thrown<QuotaFetchError> {
                OpenRouterQuotaAdapter.combine(
                    Result.failure(QuotaFetchError(QuotaError.Kind.server, "500")),
                    Result.failure(QuotaFetchError(QuotaError.Kind.unauthorized, "401")), orSub, now = now,
                )
            }?.kind,
        )
    }

    // ── Percent derivation ─────────────────────────────────────────────────

    @Test fun `percent only with a reliable denominator`() {
        assertNull(QuotaAmounts(total = dec("100"), remaining = dec("40")).computedUtilization(QuotaPool.Kind.prepaidBalance, false))
        assertNull(QuotaStatusRules.poolStatus(cny, now).utilization)
        assertTrue(same(QuotaAmounts(total = dec("10"), used = dec("3.2")).computedUtilization(QuotaPool.Kind.spendCap, false)?.percent, "32"))
        assertNull(QuotaAmounts(total = dec("0"), used = dec("0")).computedUtilization(QuotaPool.Kind.allowance, false))
        assertNull(QuotaAmounts(used = dec("5"), remaining = dec("5")).computedUtilization(QuotaPool.Kind.allowance, false))
        assertNull(QuotaAmounts(total = dec("10")).computedUtilization(QuotaPool.Kind.allowance, false))
        assertNull(QuotaAmounts(total = dec("10"), used = dec("9")).computedUtilization(QuotaPool.Kind.spendCap, true))
        assertNull(QuotaAmounts(total = dec("50"), used = dec("48")).computedUtilization(QuotaPool.Kind.rateLimit, false))
        val r = window("7d", used = "0.81").utilization(QuotaPool.Kind.usageWindow, false)!!
        assertTrue(same(r.percent, "81") && r.basis == QuotaUtilization.Basis.reported)
        val c = window("5h", remaining = "0.3").utilization(QuotaPool.Kind.usageWindow, false)!!
        assertTrue(same(c.percent, "70") && c.basis == QuotaUtilization.Basis.reportedComplement)
    }

    // ── Windows ────────────────────────────────────────────────────────────

    private val windowed by lazy {
        pool(
            "primary", QuotaPool.Kind.usageWindow, QuotaUnit.PercentOnly,
            windows = listOf(window("5h", used = "0.30", resets = now + 3_600_000), window("7d", used = "0.81", resets = now + 86_400_000)),
        )
    }

    @Test fun `windows side by side, worst wins, never summed`() {
        val ws = QuotaStatusRules.poolStatus(windowed, now)
        assertTrue(ws.status == QuotaStatus.low && ws.decidingWindowId == "7d" && same(ws.utilization?.percent, "81"))
        val passed = pool("primary", QuotaPool.Kind.usageWindow, QuotaUnit.PercentOnly, windows = listOf(window("5h", used = "1.0", resets = now - 1)))
        assertEquals("past its reset: unknown, not exhausted", QuotaStatus.unknown, QuotaStatusRules.poolStatus(passed, now).status)
        assertEquals(
            QuotaStatus.exhausted,
            QuotaStatusRules.poolStatus(pool("w", QuotaPool.Kind.usageWindow, QuotaUnit.PercentOnly, windows = listOf(window("5h", used = "1"))), now).status,
        )
    }

    // ── Freshness ──────────────────────────────────────────────────────────

    @Test fun `freshness of balances`() {
        val fp = QuotaFreshnessPolicy()
        fun b(age: Long) = pool("b", QuotaPool.Kind.prepaidBalance, amounts = QuotaAmounts(remaining = BigDecimal.ONE), at = now - age)
        assertEquals(QuotaFreshness.fresh, fp.freshnessOfAmounts(b(600_000), now))
        assertEquals(QuotaFreshness.stale, fp.freshnessOfAmounts(b(7_200_000), now))
        assertEquals(QuotaFreshness.expired, fp.freshnessOfAmounts(b(25_200_000), now))
    }

    // ── Refresh gate ───────────────────────────────────────────────────────

    @Test fun `refresh gate - interval, forced, Retry-After, backoff, attention`() {
        val pol = QuotaRefreshPolicy()
        assertEquals(QuotaRefreshDecision.Allowed, QuotaRefreshGate.decide(null, now, false))
        val ok = QuotaRefreshGate.recordSuccess(null, now, false)
        assertEquals(QuotaRefreshDecision.TooSoon(now + 600_000), QuotaRefreshGate.decide(ok, now + 300_000, false))
        assertEquals(QuotaRefreshDecision.Allowed, QuotaRefreshGate.decide(ok, now + 600_000, false))
        assertEquals(QuotaRefreshDecision.Allowed, QuotaRefreshGate.decide(ok, now + 300_000, true))
        assertEquals("manual 10 s after an AUTOMATIC request", QuotaRefreshDecision.Allowed, QuotaRefreshGate.decide(ok, now + 10_000, true))
        val okForced = QuotaRefreshGate.recordSuccess(null, now, true)
        assertEquals(
            QuotaRefreshDecision.ForcedDebounce(now + pol.forcedMinIntervalMs),
            QuotaRefreshGate.decide(okForced, now + 10_000, true),
        )
        val err500 = QuotaError(QuotaError.Kind.server, "500", 500, now)
        assertEquals(
            "manual bypasses backoff and the unattended hour",
            QuotaRefreshDecision.Allowed,
            QuotaRefreshGate.decide(QuotaRefreshGate.recordFailure(null, err500, now, false), now + 5_000, true, attended = false),
        )
        val limited = QuotaRefreshGate.recordFailure(
            ok, QuotaError(QuotaError.Kind.rateLimited, "429", 429, now, retryAfter = now + 1_800_000), now, false,
        )
        assertEquals(QuotaRefreshDecision.ServerCooldown(now + 1_800_000), QuotaRefreshGate.decide(limited, now + 600_000, true))
        assertEquals(QuotaRefreshDecision.Allowed, QuotaRefreshGate.decide(limited, now + 1_801_000, false))
        val f1 = QuotaRefreshGate.recordFailure(null, err500, now, false)
        val f2 = QuotaRefreshGate.recordFailure(f1, err500, now, false)
        assertTrue(f1.nextAllowedAt == now + 300_000 && f2.nextAllowedAt == now + 600_000)
        assertEquals(QuotaRefreshDecision.Backoff(now + 300_000), QuotaRefreshGate.decide(f1, now + 30_000, false))
        // [T-quota-attention-refresh]
        assertEquals(QuotaRefreshDecision.TooSoon(now + 3_600_000), QuotaRefreshGate.decide(ok, now + 600_000, false, attended = false))
        assertEquals(QuotaRefreshDecision.Allowed, QuotaRefreshGate.decide(ok, now + 3_600_000, false, attended = false))
        assertEquals(QuotaRefreshDecision.Allowed, QuotaRefreshGate.decide(ok, now + 600_000, false, attended = true))
        assertEquals(QuotaRefreshDecision.Allowed, QuotaRefreshGate.decide(null, now, false, attended = false))
        assertTrue(
            "no automatic interval under 5 min for any vendor",
            QuotaVendor.entries.all { v ->
                val p = QuotaRefreshPolicy.forVendor(v)
                p.normalIntervalMs >= 300_000 && p.backoffMs.all { it >= 300_000 }
            } && pol.unattendedIntervalMs == 3_600_000L,
        )
        val bad = QuotaRefreshGate.recordFailure(null, QuotaError(QuotaError.Kind.unauthorized, "401", 401, now), now, false)
        assertEquals(QuotaRefreshDecision.CredentialRejected, QuotaRefreshGate.decide(bad, now + 7_200_000, false))
        assertEquals(QuotaRefreshDecision.Allowed, QuotaRefreshGate.decide(bad, now + 60_000, true))
        val nf = QuotaRefreshGate.recordFailure(null, QuotaError(QuotaError.Kind.notFound, "404", 404, now), now, false)
        assertEquals("404 never retried sooner than 10 min", now + 600_000, nf.nextAllowedAt)
    }

    @Test fun `Retry-After and HTTP status mapping`() {
        assertEquals(now + 120_000, QuotaHttp.retryAfter("120", now))
        assertEquals(QuotaHttp.httpDate("Fri, 15 Jan 2027 08:00:00 GMT"), QuotaHttp.retryAfter("Fri, 15 Jan 2027 08:00:00 GMT", now))
        assertEquals("capped at a day", now + 86_400_000, QuotaHttp.retryAfter("999999", now))
        assertNull(QuotaHttp.retryAfter("soon", now))
        assertEquals(now + 60_000, QuotaHttp.error(http(429, "", mapOf("retry-after" to "60")), "e", now)?.retryAfter)
        assertEquals(
            listOf(QuotaError.Kind.unauthorized, QuotaError.Kind.forbidden, QuotaError.Kind.notFound, QuotaError.Kind.server),
            listOf(401, 403, 404, 503).map { QuotaHttp.error(http(it, ""), "e", now)?.kind },
        )
    }

    // ── Capability gate ────────────────────────────────────────────────────

    @Test fun `capability gate - 24 hours, manual re-check`() {
        val key = QuotaCapabilityKey("openAI", QuotaCapabilityKey.normalize("https://api.deepseek.com/v1/"))
        assertEquals("https://api.deepseek.com", key.baseURL)
        val failed = QuotaCapabilityRecord(key, "x", QuotaCapabilityRecord.Status.failed, probedAt = now, detail = "HTTP 404")
        assertTrue(QuotaCapabilityGate.shouldProbe(null, now, false))
        assertFalse(QuotaCapabilityGate.shouldProbe(failed, now + 23 * 3_600_000L, false))
        assertTrue(QuotaCapabilityGate.shouldProbe(failed, now + 24 * 3_600_000L, false))
        assertTrue(QuotaCapabilityGate.shouldProbe(failed, now + 1_000, true))
    }

    // ── Shared pools and merging ───────────────────────────────────────────

    @Test fun `shared pools de-duplicate across instances and models`() {
        val keyA = QuotaAccountKey.derive("sk-test-same-key-000001")
        assertTrue(keyA == QuotaAccountKey.derive("sk-test-same-key-000001") && keyA.length == 16)
        assertTrue(QuotaAccountKey.derive("sk-test-other-key-00002") != keyA)
        assertEquals("sk-…0001", QuotaAccountKey.mask("sk-test-same-key-000001"))

        val pools = mutableMapOf<QuotaPoolKey, QuotaPool>()
        val bindings = mutableListOf<QuotaPoolBinding>()
        QuotaMerge.ingest(ds, true, pools, bindings)
        QuotaMerge.ingest(ds, true, pools, bindings)
        assertTrue(pools.size == 2 && bindings.size == 2)
        val a = QuotaModelResolver.view("i1", "deepseek-chat", sub, pools, bindings, now = now)
        val b = QuotaModelResolver.view("i1", "deepseek-reasoner", sub, pools, bindings, now = now)
        assertTrue(a.bound.map { it.poolKey }.toSet() == b.bound.map { it.poolKey }.toSet() && a.bound.size == 2)
        assertTrue(a.bound.all { it.isShared } && !a.hasModelSpecificPool)
        assertNotNull(QuotaHeadline.pick(pools.values.toList(), now))

        val onlyCny = DeepSeekQuotaAdapter.parse(
            """{"is_available":true,"balance_infos":[{"currency":"CNY","total_balance":"99.00"}]}""".toByteArray(), sub, now = now + 700_000,
        )
        val p2 = pools.toMutableMap(); val b2 = bindings.toMutableList()
        QuotaMerge.ingest(onlyCny, false, p2, b2)
        assertTrue("partial fetch keeps USD", p2.size == 2 && same(p2[cny.id]?.amounts?.remaining, "99"))
        QuotaMerge.ingest(onlyCny, true, pools, bindings)
        assertTrue("complete fetch retires USD", pools.size == 1 && pools[usd.id] == null && bindings.none { it.poolKey == usd.id })

        val wp = mutableMapOf<QuotaPoolKey, QuotaPool>(); val wb = mutableListOf<QuotaPoolBinding>()
        val older = UpstreamQuotaSnapshot(sub, prov, now, pools = listOf(
            pool("p", QuotaPool.Kind.usageWindow, QuotaUnit.PercentOnly, windows = listOf(window("5h", used = "0.2"), window("7d", used = "0.5"))),
        ), bindings = emptyList())
        val newer = UpstreamQuotaSnapshot(sub, prov, now, pools = listOf(
            pool("p", QuotaPool.Kind.usageWindow, QuotaUnit.PercentOnly, windows = listOf(window("5h", used = "0.4", at = now + 60_000)), at = now + 60_000),
        ), bindings = emptyList())
        QuotaMerge.ingest(older, false, wp, wb)
        QuotaMerge.ingest(newer, false, wp, wb)
        val merged = wp.values.first()
        assertTrue(merged.windows.size == 2 &&
            same(merged.windows.first { it.id == "5h" }.reportedUsedFraction, "0.4") &&
            same(merged.windows.first { it.id == "7d" }.reportedUsedFraction, "0.5"))

        val manual = QuotaPoolBinding(cny.id, QuotaPoolBinding.Target.Model("x"), QuotaPoolBinding.Evidence.userManualMapping)
        val bb = bindings.toMutableList()
        QuotaMerge.ingest(UpstreamQuotaSnapshot(sub, prov, now, pools = emptyList(), bindings = listOf(manual)), false, pools, bb)
        assertEquals("an adapter can never inject a manual binding", bindings, bb)
    }

    // ── Persistence ────────────────────────────────────────────────────────

    @Test fun `store file round trip keeps decimals as strings and no credential`() {
        val ok = QuotaRefreshGate.recordSuccess(null, now, false)
        val key = QuotaCapabilityKey("openAI", "https://api.deepseek.com")
        val file = QuotaStoreFile(
            subjects = listOf(QuotaSubject(sub, instanceIds = setOf("i1", "i2"), accountLabel = "sk-…0001", lastRefresh = ok)),
            pools = ds.pools + windowed,
            bindings = ds.bindings,
            capabilities = listOf(QuotaCapabilityRecord(key, "x", QuotaCapabilityRecord.Status.failed, probedAt = now, detail = "HTTP 404")),
            instanceSubjects = mapOf("i1" to sub, "i2" to sub),
        )
        val text = QuotaStoreFile.encode(file)
        assertEquals(file, QuotaStoreFile.decode(text))
        assertTrue("decimals as strings: $text", text.contains("\"remaining\":\"110\""))
        assertFalse(text.contains("sk-test-same-key"))
        assertNull("future version ignored", QuotaStoreFile.decode(QuotaStoreFile.encode(file.copy(version = 99))))
        assertNull("garbage", QuotaStoreFile.decode("{"))
    }

    // ── Single flight ──────────────────────────────────────────────────────

    @Test fun `single flight shares one execution`() = runBlocking {
        var calls = 0
        val sf = QuotaSingleFlight<String, Int>(CoroutineScope(Dispatchers.Default))
        val a = async { sf.run("subject") { calls++; delay(50); 7 } }
        delay(5)
        val b = async { sf.run("subject") { calls++; 8 } }
        assertEquals(listOf(7, 7), listOf(a.await(), b.await()))
        assertEquals(9, sf.run("subject") { calls++; 9 })
        assertEquals(2, calls)
    }

    // ── Vendor detection / fixed host ──────────────────────────────────────

    private fun desc(kind: QuotaInstanceDescriptor.ProviderKind, oauth: Boolean = false, base: String? = null) =
        QuotaInstanceDescriptor("i", "L", kind, oauth, true, base)

    @Test fun `vendor detection`() {
        val openAI = QuotaInstanceDescriptor.ProviderKind.openAI
        val anthropic = QuotaInstanceDescriptor.ProviderKind.anthropic
        assertEquals(QuotaVendor.deepSeek, QuotaVendorDetector.vendor(desc(openAI, base = "https://api.deepseek.com/v1")))
        assertEquals(QuotaVendor.deepSeek, QuotaVendorDetector.vendor(desc(anthropic, base = "https://api.deepseek.com/anthropic")))
        assertEquals(QuotaVendor.customRelay, QuotaVendorDetector.vendor(desc(openAI, base = "https://relay.example.com/v1")))
        assertEquals(QuotaVendor.codexSubscription, QuotaVendorDetector.vendor(desc(openAI, oauth = true)))
        assertEquals(QuotaVendor.claudeSubscription, QuotaVendorDetector.vendor(desc(anthropic, oauth = true)))
        val or = desc(QuotaInstanceDescriptor.ProviderKind.openRouter)
        assertTrue(QuotaAdapterRegistry.adapter(or).capability(or).active)
        val x = desc(QuotaInstanceDescriptor.ProviderKind.xAI)
        assertEquals(
            QuotaCapability.unsupported(QuotaSubject.UnsupportedReason.vendorHasNoQuotaAPI),
            QuotaAdapterRegistry.adapter(x).capability(x),
        )
    }

    private class FakeTransport(private val body: String) : QuotaHttpTransport {
        val urls = mutableListOf<String>()
        val bearers = mutableListOf<String>()
        val sentHeaders = mutableListOf<Map<String, String>>()
        override suspend fun get(url: String, bearer: String, headers: Map<String, String>): QuotaHttpResponse {
            urls += url; bearers += bearer; sentHeaders += headers
            return QuotaHttpResponse(200, body.toByteArray(), emptyMap())
        }
    }

    @Test fun `DeepSeek request goes to the fixed official URL`() = runBlocking {
        val fake = FakeTransport(dsJSON)
        val out = DeepSeekQuotaAdapter.fetch(
            QuotaFetchContext(
                desc(QuotaInstanceDescriptor.ProviderKind.openAI, base = "https://api.deepseek.com/some/custom/path"),
                sub, "sk-secret", fake, now,
            ),
        )
        assertEquals(listOf("https://api.deepseek.com/user/balance"), fake.urls)
        assertEquals(2, out.snapshot.pools.size)
        assertEquals(listOf("sk-secret"), fake.bearers)
    }

    // ── Console links ──────────────────────────────────────────────────────

    @Test fun `console links are fixed per vendor`() {
        fun link(d: QuotaInstanceDescriptor) = QuotaConsoleLink.url(d)
        val openAI = QuotaInstanceDescriptor.ProviderKind.openAI
        val anthropic = QuotaInstanceDescriptor.ProviderKind.anthropic
        assertEquals("https://chatgpt.com/codex/cloud/settings/analytics#usage", link(desc(openAI, oauth = true)))
        assertEquals("https://claude.ai/settings/usage", link(desc(anthropic, oauth = true)))
        assertEquals("https://console.anthropic.com/settings/billing", link(desc(anthropic)))
        assertEquals("https://platform.openai.com/usage", link(desc(openAI)))
        assertEquals("https://platform.deepseek.com/usage", link(desc(anthropic, base = "https://api.deepseek.com/anthropic")))
        assertEquals("https://platform.moonshot.cn/console/account", link(desc(openAI, base = "https://api.moonshot.cn/v1")))
        assertEquals("https://platform.moonshot.ai/console/account", link(desc(openAI, base = "https://api.moonshot.ai/v1")))
        assertEquals("https://www.kimi.com/code", link(desc(QuotaInstanceDescriptor.ProviderKind.kimiCode)))
        assertEquals("https://openrouter.ai/settings/credits", link(desc(QuotaInstanceDescriptor.ProviderKind.openRouter)))
        val mimo = desc(openAI, base = "https://api.xiaomimimo.com/v1")
        assertEquals(QuotaVendor.xiaomiMiMo, QuotaVendorDetector.vendor(mimo))
        assertEquals(
            QuotaCapability.unsupported(QuotaSubject.UnsupportedReason.vendorHasNoQuotaAPI),
            QuotaAdapterRegistry.adapter(QuotaVendor.xiaomiMiMo).capability(desc(openAI)),
        )
        assertEquals("https://platform.xiaomimimo.com/console/plan-manage", link(mimo))
        assertNull("never built from a user URL", link(desc(openAI, base = "https://relay.example.com/v1")))
    }

    // ── Quota low / levels ─────────────────────────────────────────────────

    private fun lowView(ps: List<QuotaPool>): Pair<ModelQuotaView, Map<QuotaPoolKey, QuotaPool>> {
        val dict = ps.associateBy { it.id }
        val binds = ps.map { QuotaPoolBinding(it.id, QuotaPoolBinding.Target.Subject, QuotaPoolBinding.Evidence.vendorDocumented) }
        return QuotaModelResolver.view("i", "m", sub, dict, binds, now = now) to dict
    }

    private fun isLow(ps: List<QuotaPool>): Boolean = lowView(ps).let { QuotaLowRules.isLow(it.first, it.second) }
    private fun lvl(ps: List<QuotaPool>): QuotaLevel = QuotaLevelRules.level(ps.map { QuotaStatusRules.poolStatus(it, now) to it })
    private fun bal(code: String, v: String, family: String = "b", at: Long = now) =
        pool(family, QuotaPool.Kind.prepaidBalance, QuotaUnit.Currency(code), QuotaAmounts(remaining = dec(v)), at = at)

    @Test fun `quota low - yellow dot rules`() {
        assertTrue(isLow(listOf(pool("w", QuotaPool.Kind.usageWindow, QuotaUnit.PercentOnly, windows = listOf(window("5h", used = "0.91"))))))
        assertFalse(isLow(listOf(pool("w", QuotaPool.Kind.usageWindow, QuotaUnit.PercentOnly, windows = listOf(window("5h", used = "0.89"))))))
        assertTrue(isLow(listOf(bal("USD", "0.99"))) && !isLow(listOf(bal("USD", "1"))))
        assertTrue("fixed 7.2 CNY/USD", isLow(listOf(bal("CNY", "7"))) && !isLow(listOf(bal("CNY", "7.5"))))
        assertFalse("CN¥36 + $0 judged together", isLow(listOf(bal("CNY", "36", "b:cny"), bal("USD", "0", "b:usd"))))
        assertTrue(isLow(listOf(pool("c", QuotaPool.Kind.spendCap, amounts = QuotaAmounts(total = dec("10"), remaining = dec("0.5"))))))
        assertTrue(isLow(listOf(pool("b", QuotaPool.Kind.prepaidBalance, QuotaUnit.Currency("CNY"), QuotaAmounts(remaining = dec("100")), verdict = QuotaPool.Verdict.rejected))))
        assertFalse(isLow(listOf(pool("b", QuotaPool.Kind.prepaidBalance, QuotaUnit.Credits(), QuotaAmounts(remaining = dec("0.1"))))))
        assertFalse(QuotaLowRules.isLow(ModelQuotaView.empty("i", "m"), emptyMap()))
        assertFalse("expired data is not trusted", isLow(listOf(bal("USD", "0.1", at = now - 86_400_000))))
        assertFalse(isLow(listOf(pool("u", QuotaPool.Kind.spendCap, amounts = QuotaAmounts(remaining = BigDecimal.ZERO), unlimited = true))))
    }

    @Test fun `usage wording and tag levels`() {
        assertEquals("44% used", QuotaFormat.percentUsedText(dec("44")))
        assertEquals("4.3% used", QuotaFormat.percentUsedText(dec("4.25")))
        assertEquals(QuotaLevel.low, lvl(listOf(pool("x", QuotaPool.Kind.usageWindow, QuotaUnit.PercentOnly, windows = listOf(window("5h", used = "0.95"))))))
        assertEquals(QuotaLevel.low, lvl(listOf(pool("r", QuotaPool.Kind.usageWindow, QuotaUnit.PercentOnly, windows = listOf(window("5h", remaining = "0.05"))))))
        assertEquals(QuotaLevel.normal, lvl(listOf(pool("w", QuotaPool.Kind.usageWindow, QuotaUnit.PercentOnly, windows = listOf(window("5h", used = "0.5"))))))
        assertEquals(QuotaLevel.depleted, lvl(listOf(pool("w", QuotaPool.Kind.usageWindow, QuotaUnit.PercentOnly, windows = listOf(window("5h", used = "1"))))))
        assertEquals(QuotaLevel.low, lvl(listOf(bal("USD", "0.5"))))
        assertEquals(QuotaLevel.depleted, lvl(listOf(bal("USD", "0"))))
        assertEquals(QuotaLevel.depleted, lvl(listOf(bal("USD", "-2"))))
        assertEquals(QuotaLevel.normal, lvl(listOf(bal("CNY", "36", "b:cny"), bal("USD", "0", "b:usd"))))
        assertEquals(
            QuotaLevel.depleted,
            lvl(listOf(pool("q", QuotaPool.Kind.allowance, QuotaUnit.Custom("quota"), QuotaAmounts(total = dec("100"), used = dec("100"), remaining = dec("0"))))),
        )
        assertEquals(QuotaLevel.normal, lvl(listOf(pool("c", QuotaPool.Kind.prepaidBalance, QuotaUnit.Credits(), QuotaAmounts(remaining = dec("5"))))))
    }

    // ── Tone and bars ──────────────────────────────────────────────────────

    private fun ps(st: QuotaStatus, f: QuotaFreshness = QuotaFreshness.fresh, pct: String? = null) =
        PoolStatus(st, null, pct?.let { QuotaUtilization(dec(it), QuotaUtilization.Basis.computed) }, f)

    @Test fun `tone and bars`() {
        assertEquals(QuotaTone.critical, QuotaToneRules.tone(ps(QuotaStatus.exhausted)))
        assertEquals(QuotaTone.caution, QuotaToneRules.tone(ps(QuotaStatus.low, pct = "85")))
        assertEquals(QuotaTone.caution, QuotaToneRules.tone(ps(QuotaStatus.rateLimited)))
        assertEquals(QuotaTone.healthy, QuotaToneRules.tone(ps(QuotaStatus.available, pct = "30")))
        assertEquals(QuotaTone.normal, QuotaToneRules.tone(ps(QuotaStatus.available)))
        assertEquals(QuotaTone.muted, QuotaToneRules.tone(ps(QuotaStatus.unknown)))
        assertEquals(QuotaTone.muted, QuotaToneRules.tone(ps(QuotaStatus.exhausted, QuotaFreshness.expired)))
        assertEquals(QuotaTone.caution, QuotaToneRules.tone(ps(QuotaStatus.low, QuotaFreshness.stale, "90")))
        assertTrue(QuotaToneRules.pillTone(ps(QuotaStatus.available, pct = "10")) == QuotaTone.normal &&
            QuotaToneRules.pillTone(ps(QuotaStatus.exhausted)) == QuotaTone.critical)
        assertEquals(QuotaTone.normal, QuotaToneRules.tone(QuotaStatusRules.poolStatus(cny, now)))
        assertNull(QuotaBarRules.poolFraction(cny))
        val cap = pool("cap", QuotaPool.Kind.spendCap, amounts = QuotaAmounts(total = dec("10"), used = dec("3.2")))
        assertEquals(0.32, QuotaBarRules.poolFraction(cap)!!, 1e-9)
        assertNull(QuotaBarRules.poolFraction(cap.copy(unlimited = true)))
        assertNull(QuotaBarRules.poolFraction(pool("x", QuotaPool.Kind.allowance, amounts = QuotaAmounts(used = dec("3")))))
        assertEquals(1.0, QuotaBarRules.fraction(QuotaUtilization(dec("150"), QuotaUtilization.Basis.reported))!!, 0.0)
        assertEquals(0.81, QuotaBarRules.windowFraction(window("7d", used = "0.81"), windowed)!!, 1e-9)
        assertNull(QuotaBarRules.fraction(null))
    }

    // ── Formatting ─────────────────────────────────────────────────────────

    @Test fun `formatting`() {
        val en = Locale.US
        val short = QuotaFormat.shortText(cny, QuotaStatusRules.poolStatus(cny, now), en)!!
        assertTrue(short, short.startsWith("Balance ") && short.contains("110") && !short.contains("%"))
        assertEquals("4.3%", QuotaFormat.percent(dec("4.25")))
        assertEquals("81%", QuotaFormat.percent(dec("81")))
        assertEquals("0%", QuotaFormat.percent(dec("0")))
        assertEquals("10%", QuotaFormat.percent(dec("9.96")))
        assertEquals("CN¥36.51", QuotaFormat.amount(dec("36.51"), QuotaUnit.Currency("CNY"), en))
        assertEquals("$1.12", QuotaFormat.amount(dec("1.12"), QuotaUnit.Currency("USD"), en))
        assertEquals("Granted CN¥10.00 · Topped up CN¥100.00", QuotaFormat.breakdown(cny.amounts!!, cny.unit, en))
    }

    // ── Wiring (source guards) ─────────────────────────────────────────────

    @Test fun `wiring - store, section placement, credentials`() {
        val store = ProductionSources.read("quota/QuotaStore.kt")
        assertTrue("persists under noBackupFilesDir (outside Android backup)", store.contains("File(File(it.noBackupFilesDir, \"quota\"), \"store.json\")"))
        assertFalse(
            "never logs a credential",
            store.lines().any { it.contains("AppLogger.") && (it.contains("\$credential") || it.contains("\$token") || it.contains("\${credential")) },
        )
        assertTrue("foreground only", store.contains("ProcessLifecycleOwner.get().lifecycle.addObserver"))
        assertTrue(store.contains("QuotaRefreshGate.decide(") && store.contains("singleFlight.run(subjectId)"))
        assertTrue(store.contains("QuotaCapabilityRules.failureDisablesEndpoint(fe.kind, subjectId.vendor)"))
        assertTrue(store.contains("attended = isWatched(instance.id)"))

        val detail = ProductionSources.read("ui/settings/ProviderDetailScreen.kt")
        val statusIdx = detail.indexOf("// ─── Status ─")
        val quotaIdx = detail.indexOf("ui.quota.ProviderQuotaSection(instance = instance)")
        val thinkIdx = detail.indexOf("ThinkingRulesSection(instance = instance")
        assertTrue("Quota between Status and Thinking Rules", statusIdx in 0 until quotaIdx && quotaIdx < thinkIdx)

        assertTrue("store starts at launch", ProductionSources.read("MinisApp.kt").contains("QuotaStore.start(this, providerRepository)"))
        val repo = ProductionSources.read("data/repository/ProviderRepository.kt")
        assertEquals("key saved / deleted re-arms quota", 2, repo.split("QuotaStore.credentialsChanged()").size - 1)

        val section = ProductionSources.read("ui/quota/ProviderQuotaSection.kt")
        assertTrue(section.contains("QuotaWatch(instance.id, \"detailAppeared\")") && section.contains("QuotaWatch(instanceId, \"detailSheet\")"))
        assertTrue(section.contains("stringResource(R.string.quota_shared_by_all)"))
        val ui = ProductionSources.read("ui/quota/QuotaUi.kt")
        assertTrue("bars only from QuotaBarRules", ui.contains("QuotaBarRules.poolFraction(pool)") && ui.contains("QuotaBarRules.windowFraction(window, pool)"))
        assertTrue("window rows show usage", ui.contains("QuotaFormat.percentUsedText(util.percent, strings)"))
        assertTrue(ui.contains("R.string.quota_prepaid_no_percent"))
        assertFalse("sheet shows no key, not even masked", section.contains("accountLabel"))
    }

    @Test fun `review - no credential read, cache load or file write on the main thread`() {
        val store = ProductionSources.read("quota/QuotaStore.kt")
        assertTrue("refresh always runs off the caller's (possibly main) thread",
            store.contains("withContext(Dispatchers.Default) { refreshOffMain(instanceId, forced, reason, attended) }"))
        assertTrue("launch-time cache load off main", store.contains("scope.launch(Dispatchers.IO) {\n            ensureLoaded()"))
        assertTrue("onStop persist off main", store.contains("scope.launch(Dispatchers.IO) { flushPersist() }"))
        val refreshIfDue = store.substring(store.indexOf("fun refreshIfDue("), store.indexOf("// MARK: - Refresh"))
        assertFalse("refreshIfDue (composition thread) never loads the file", refreshIfDue.lines().any { it.trim() == "ensureLoaded()" })
        assertTrue("no detach before the provider config has loaded", store.contains("if (repository?.configLoaded?.value != true)"))
        val ui = ProductionSources.read("ui/quota/QuotaUi.kt")
        assertTrue("relative times are lower-case mid-sentence (\"Resets in 3 hr.\")",
            ui.contains("DisplayContext.CAPITALIZATION_NONE") && !ui.contains("DateUtils.getRelativeTimeSpanString"))
    }

    @Test fun `review - every iOS locale of a ported key exists on Android`() {
        val res = java.io.File(ProductionSources.mainRoot()!!, "../../../../res")
        // Keys iOS translates beyond en / zh-Hans / zh-Hant (checked against Localizable.xcstrings).
        val wide = listOf("quota_source", "quota_done", "quota_status_unknown", "quota_status_low", "quota_updated")
        for (folder in listOf("values-de", "values-es", "values-fil", "values-fr", "values-hr", "values-in", "values-ja",
            "values-ko", "values-ms", "values-pl", "values-pt-rBR", "values-ro", "values-ru", "values-th", "values-tr")) {
            val text = java.io.File(res, "$folder/strings.xml").readText()
            for (name in wide) assertTrue("$folder lacks $name", text.contains("name=\"$name\""))
        }
        for (folder in listOf("values-hr", "values-ja")) {
            assertTrue(java.io.File(res, "$folder/strings.xml").readText().contains("name=\"quota_status_rate_limited\""))
        }
    }
}
