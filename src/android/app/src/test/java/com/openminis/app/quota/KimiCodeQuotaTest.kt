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
 * [ISS-031] Kimi Code subscription usage (GET {base}/usages, from the official
 * MoonshotAI/kimi-cli `/usage` command), plus the MiniMax / OpenCode console
 * links. Port of iOS MinisTests/Standalone/KimiCodeQuotaTests.swift
 * (d7fa94168): the same 52 checks, in the same order, each assertion message
 * naming the iOS check it mirrors.
 *
 *   A. The adapter driven with the response shapes the CLI's parser accepts
 *      (NOT captured replies - the real response is unverified).
 *   B. Vendor routing and console links.
 *   C. Store wiring (source), Android-specific where the platform differs.
 */
class KimiCodeQuotaTest {

    private val now = 1_800_000_000_000L
    private val sid = QuotaSubjectID(QuotaVendor.kimiCode, "instance:k1")

    private fun same(a: BigDecimal?, b: String) = a != null && a.compareTo(BigDecimal(b)) == 0
    private fun parse(json: String): Result<UpstreamQuotaSnapshot> =
        runCatching { KimiCodeQuotaAdapter.parse(json.toByteArray(), subjectId = sid, now = now) }
    private fun windows(json: String): List<QuotaWindow> = parse(json).getOrNull()?.pools?.firstOrNull()?.windows.orEmpty()
    private fun pct(w: QuotaWindow?): BigDecimal? = w?.utilization(QuotaPool.Kind.usageWindow, unlimited = false)?.percent
    private fun failKind(json: String): QuotaError.Kind? = (parse(json).exceptionOrNull() as? QuotaFetchError)?.kind

    private val full = """
        {"usage":{"limit":1000,"used":300,"resetTime":"2027-01-15T08:00:00.123456789Z"},
         "limits":[{"detail":{"limit":200,"used":150,"reset_in":3600},"window":{"duration":300,"timeUnit":"TIME_UNIT_MINUTE"}}]}
    """.trimIndent()

    // ── A. Response shapes the official CLI parses ──────────────────────────

    @Test fun `full shape - pool, provenance, ids, counts, basis, resets, deciding window`() {
        val snap = parse(full).getOrThrow()
        val pool = snap.pools.first()
        assertTrue(
            "1 one account-wide usage-window pool",
            snap.pools.size == 1 && pool.kind == QuotaPool.Kind.usageWindow &&
                snap.bindings.first().target == QuotaPoolBinding.Target.Subject &&
                snap.bindings.first().evidence == QuotaPoolBinding.Evidence.assumedSubjectWide,
        )
        assertTrue(
            "2 labelled as observed-client dashboard API, not 'official API'",
            snap.provenance.source == QuotaSource.providerDashboardAPI &&
                snap.provenance.confidence == QuotaConfidence.observedClient,
        )
        val ws = pool.windows
        assertTrue(
            "3 usage -> Weekly (the CLI's default label), limits[300 MINUTE] -> 5h",
            ws.map { it.id } == listOf("weekly", "5h") &&
                ws.first().calendarPeriod == QuotaWindow.CalendarPeriod.week && ws.last().durationSeconds == 18000L,
        )
        assertTrue(
            "4 counts kept as given (total = limit, used), percent computed 30% / 75%",
            same(ws.first().amounts?.total, "1000") && same(ws.first().amounts?.used, "300") &&
                ws.first().amounts?.remaining == null &&
                same(pct(ws.first()), "30") && same(pct(ws.last()), "75"),
        )
        assertTrue(
            "5 percent basis is computed, never 'reported'",
            ws.first().utilization(QuotaPool.Kind.usageWindow, false)?.basis == QuotaUtilization.Basis.computed &&
                ws.all { it.reportedUsedFraction == null },
        )
        assertEquals("6 ISO reset with nanoseconds parsed (to the millisecond)", 1_800_000_000_123L, ws.first().resetsAt)
        assertEquals("7 reset_in seconds -> now + 3600", now + 3_600_000L, ws.last().resetsAt)
        assertEquals("8 worst window decides (75% = 5h)", "5h", QuotaStatusRules.poolStatus(pool, now).decidingWindowId)
    }

    @Test fun `remaining, epoch and relative resets, usage only`() {
        val remaining = """{"usage":{"limit":"1000","remaining":"700","reset_at":1800003600}}"""
        val w = windows(remaining).first()
        assertTrue(
            "9 remaining + limit (string numbers): used derived -> 30%, remaining kept",
            same(pct(w), "30") && same(w.amounts?.remaining, "700") && w.amounts?.used == null,
        )
        assertEquals("10 epoch-seconds reset_at", 1_800_003_600_000L, w.resetsAt)
        assertEquals(
            "11 epoch-milliseconds resetAt", 1_800_003_600_000L,
            windows("""{"usage":{"limit":10,"used":1,"resetAt":1800003600000}}""").first().resetsAt,
        )
        assertTrue(
            "12 ttl / window seconds as relative reset",
            windows("""{"usage":{"limit":10,"used":1,"ttl":60}}""").first().resetsAt == now + 60_000L &&
                windows("""{"usage":{"limit":10,"used":1,"window":120}}""").first().resetsAt == now + 120_000L,
        )
        assertEquals("13 usage only (no limits) -> one window", 1, windows("""{"usage":{"limit":10,"used":5}}""").size)
    }

    @Test fun `limits only - units, item-level counts, names, numbering, duplicate ids`() {
        val limitsOnly = """
            {"limits":[
             {"detail":{"limit":10,"used":1},"window":{"duration":5,"timeUnit":"HOUR"}},
             {"detail":{"limit":10,"used":2},"window":{"duration":7,"timeUnit":"DAY"}},
             {"detail":{"limit":10,"used":3},"window":{"duration":90}},
             {"limit":10,"used":4,"duration":1440,"timeUnit":"MINUTE"},
             {"detail":{"limit":10,"used":5,"name":"Opus"}},
             {"detail":{"limit":10,"used":6}}]}
        """.trimIndent()
        val lw = windows(limitsOnly)
        assertEquals(
            "14 limits only: HOUR -> 5h, DAY -> 7d, unit-less -> seconds, item-level counts, upstream name, numbered fallback",
            listOf("5h", "7d", "1m", "1d", "Opus", "limit 6"), lw.map { it.id },
        )
        assertEquals("15 durations: 5h / 7d / 90 s / 1 day", listOf(18000L, 604800L, 90L, 86400L), lw.take(4).map { it.durationSeconds })
        assertEquals(
            "16 same id twice stays two windows", listOf("5h", "5h #2"),
            windows(
                """{"limits":[{"detail":{"limit":5,"used":1},"window":{"duration":300,"timeUnit":"MINUTE"}},""" +
                    """{"detail":{"limit":9,"used":1},"window":{"duration":300,"timeUnit":"MINUTE"}}]}""",
            ).map { it.id },
        )
    }

    // ── A. Never an invented number ─────────────────────────────────────────

    @Test fun `never an invented number`() {
        assertEquals(
            "17 limit without used/remaining -> no window (the CLI would show '0 used')",
            QuotaError.Kind.unsupportedResponse, failKind("""{"usage":{"limit":1000}}"""),
        )
        assertEquals("18 used without limit -> no window", QuotaError.Kind.unsupportedResponse, failKind("""{"usage":{"used":5}}"""))
        assertTrue(
            "19 limit 0 / negative / junk -> skipped",
            failKind("""{"usage":{"limit":0,"used":0}}""") == QuotaError.Kind.unsupportedResponse &&
                failKind("""{"usage":{"limit":10,"used":-1}}""") == QuotaError.Kind.unsupportedResponse &&
                failKind("""{"usage":{"limit":"lots","used":"some"}}""") == QuotaError.Kind.unsupportedResponse &&
                failKind("""{"usage":{"limit":true,"used":1}}""") == QuotaError.Kind.unsupportedResponse,
        )
        assertEquals(
            "20 a bad entry is skipped, the good one kept", listOf("5h"),
            windows("""{"usage":{"limit":10},"limits":[{"detail":{"limit":10,"used":2},"window":{"duration":300,"timeUnit":"MINUTE"}}]}""")
                .map { it.id },
        )
        assertEquals("21 {} -> unsupportedResponse", QuotaError.Kind.unsupportedResponse, failKind("{}"))
        assertTrue(
            "22 non-JSON / array -> unsupportedResponse",
            failKind("<html>nope</html>") == QuotaError.Kind.unsupportedResponse && failKind("[1,2]") == QuotaError.Kind.unsupportedResponse,
        )
        assertNull(
            "23 unreadable reset -> no reset, window still shown",
            windows("""{"usage":{"limit":10,"used":1,"reset_at":"soon"}}""").single().resetsAt,
        )
        val e = parse("""{"quota":{"left":42,"owner":"alice@example.com"}}""").exceptionOrNull() as QuotaFetchError
        assertTrue(
            "24 failure detail carries the shape (names + types) without any value: ${e.detail}",
            e.detail.contains("\"quota\":{\"left\":int,\"owner\":string}") && !e.detail.contains("42") && !e.detail.contains("alice"),
        )
    }

    // ── A. Request ──────────────────────────────────────────────────────────

    private class FakeTransport(private val status: Int, private val body: String) : QuotaHttpTransport {
        val urls = mutableListOf<String>()
        val bearers = mutableListOf<String>()
        val sentHeaders = mutableListOf<Map<String, String>>()
        override suspend fun get(url: String, bearer: String, headers: Map<String, String>): QuotaHttpResponse {
            urls += url; bearers += bearer; sentHeaders += headers
            return QuotaHttpResponse(status, body.toByteArray(), emptyMap())
        }
    }

    private fun desc(
        kind: QuotaInstanceDescriptor.ProviderKind,
        oauth: Boolean = false,
        base: String? = null,
        openCode: Boolean = false,
    ) = QuotaInstanceDescriptor("k1", "L", kind, oauth, true, base, isOpenCodeChannel = openCode)

    private val minisUA = "Minis/9.9 (Android 13; Pixel 4a)"
    private val secret = "kimi-oauth-SECRET-token"

    private fun fetch(d: QuotaInstanceDescriptor, fake: FakeTransport, override: String? = null): Result<QuotaFetchOutcome> {
        val saved = KimiCodeQuotaAdapter.userAgent
        KimiCodeQuotaAdapter.userAgent = { minisUA }
        try {
            return runCatching {
                runBlocking {
                    KimiCodeQuotaAdapter.fetch(QuotaFetchContext(d, sid, secret, fake, now, endpointOverride = override))
                }
            }
        } finally {
            KimiCodeQuotaAdapter.userAgent = saved
        }
    }

    @Test fun `request - fixed URL, bearer only, own UA, override, shape`() {
        val okFake = FakeTransport(200, full)
        val ok = fetch(desc(QuotaInstanceDescriptor.ProviderKind.kimiCode, oauth = true), okFake)
        assertEquals("25 default host: GET https://api.kimi.com/coding/v1/usages", listOf("https://api.kimi.com/coding/v1/usages"), okFake.urls)
        assertEquals("26 credential only as the bearer", listOf(secret), okFake.bearers)

        val ovFake = FakeTransport(200, full)
        fetch(desc(QuotaInstanceDescriptor.ProviderKind.kimiCode, oauth = true), ovFake, "http://10.0.0.5:18931/coding/v1/usages")
        assertTrue(
            "27 a (DEBUG-only) endpoint override replaces the URL and nothing else",
            ovFake.urls == listOf("http://10.0.0.5:18931/coding/v1/usages") && ovFake.sentHeaders == okFake.sentHeaders,
        )
        // Android differs from iOS here: OkHttp would otherwise send its own
        // `okhttp/<version>` UA, so the adapter sets Minis's own explicitly -
        // and that is the ONLY header (no borrowed client identity).
        assertEquals(
            "28 no extra headers: only Minis's own User-Agent, no borrowed client identity",
            listOf(mapOf("User-Agent" to minisUA)), okFake.sentHeaders,
        )
        val shape = ok.getOrThrow().responseShape.orEmpty()
        assertTrue(
            "29 shape returned for the once-per-launch log, with no values and no token: $shape",
            shape.contains("\"limits\":[{\"detail\":{\"limit\":int,\"reset_in\":int,\"used\":int}") &&
                !shape.contains("1000") && !shape.contains("SECRET") && shape.contains("iso-date"),
        )
        val aiFake = FakeTransport(200, full)
        fetch(desc(QuotaInstanceDescriptor.ProviderKind.openAI, base = "https://api.kimi.ai/coding/v1/some/path"), aiFake)
        assertEquals(
            "30 overseas base on api.kimi.ai -> same fixed path on that host (user path ignored)",
            listOf("https://api.kimi.ai/coding/v1/usages"), aiFake.urls,
        )
        fun failure(status: Int) =
            (fetch(desc(QuotaInstanceDescriptor.ProviderKind.kimiCode, oauth = true), FakeTransport(status, "{}")).exceptionOrNull()
                as? QuotaFetchError)?.kind
        assertTrue(
            "31 401 -> unauthorized, 404 -> notFound, 500 -> server",
            failure(401) == QuotaError.Kind.unauthorized && failure(404) == QuotaError.Kind.notFound &&
                failure(500) == QuotaError.Kind.server,
        )
        val e401 = fetch(desc(QuotaInstanceDescriptor.ProviderKind.kimiCode, oauth = true), FakeTransport(401, "{}"))
            .exceptionOrNull() as QuotaFetchError
        assertFalse("32 error detail never contains the token", e401.detail.contains("SECRET"))
    }

    @Test fun `the real UA is Minis's own, never a Kimi or other client identity`() {
        // Android-only check (iOS relies on URLSession's default UA): the
        // production provider is the app's branded MinisUserAgent.
        val src = ProductionSources.read("quota/QuotaAdapters.kt")
        val adapter = src.substringAfter("object KimiCodeQuotaAdapter").substringBefore("object QuotaResponseShape")
        assertTrue(adapter.contains("internal var userAgent: () -> String = { com.openminis.app.provider.MinisUserAgent.DEFAULT }"))
        assertTrue(adapter.contains("ctx.transport.get(url, ctx.credential, mapOf(\"User-Agent\" to userAgent()))"))
        for (spoof in listOf("KimiCLI", "kimi-cli", "claude-cli", "codex_cli", "X-Msh")) {
            assertFalse("no $spoof identity", adapter.contains(spoof, ignoreCase = false) && !adapter.contains("MoonshotAI/kimi-cli"))
        }
        val ua = ProductionSources.read("provider/UserAgentOverride.kt")
        assertTrue(ua.contains("\"Minis/\${BuildConfig.VERSION_NAME} (Android \$release; \$model)\""))
    }

    // ── B. Vendor routing ───────────────────────────────────────────────────

    private fun cap(d: QuotaInstanceDescriptor) = QuotaAdapterRegistry.adapter(d).capability(d)

    @Test fun `vendor routing`() {
        val oauth = desc(QuotaInstanceDescriptor.ProviderKind.kimiCode, oauth = true)
        assertTrue(
            "33 Kimi Code OAuth -> Kimi Code adapter (active, dashboard API / observed client)",
            QuotaVendorDetector.vendor(oauth) == QuotaVendor.kimiCode &&
                cap(oauth) == QuotaCapability.activeQuery(QuotaSource.providerDashboardAPI, QuotaConfidence.observedClient),
        )
        assertTrue(
            "34 Kimi Code API key on api.kimi.com / api.kimi.ai -> Kimi Code adapter",
            QuotaVendorDetector.vendor(desc(QuotaInstanceDescriptor.ProviderKind.openAI, base = "https://api.kimi.com/coding/v1")) == QuotaVendor.kimiCode &&
                QuotaVendorDetector.vendor(desc(QuotaInstanceDescriptor.ProviderKind.openAI, base = "https://api.kimi.ai/coding/v1")) == QuotaVendor.kimiCode,
        )
        assertEquals(
            "35 Moonshot open platform unchanged", QuotaVendor.moonshot,
            QuotaVendorDetector.vendor(desc(QuotaInstanceDescriptor.ProviderKind.openAI, base = "https://api.moonshot.cn/v1")),
        )
        val mmIntl = desc(QuotaInstanceDescriptor.ProviderKind.anthropic, base = "https://api.minimax.io/anthropic/v1")
        val mmCn = desc(QuotaInstanceDescriptor.ProviderKind.anthropic, base = "https://api.minimaxi.com/anthropic")
        assertTrue(
            "36 MiniMax (international / China) -> no query (not supported yet), not a relay probe",
            QuotaVendorDetector.vendor(mmIntl) == QuotaVendor.miniMax && QuotaVendorDetector.vendor(mmCn) == QuotaVendor.miniMax &&
                cap(mmIntl) == QuotaCapability.unsupported(QuotaSubject.UnsupportedReason.providerTypeUnsupported) &&
                QuotaAdapterRegistry.adapter(mmIntl) !is RelayQuotaAdapter,
        )
        val oc = desc(QuotaInstanceDescriptor.ProviderKind.openAI, base = "https://opencode.ai/zen/go/v1", openCode = true)
        assertTrue(
            "37 OpenCode channel -> no lookup API (no relay probe against opencode.ai)",
            QuotaVendorDetector.vendor(oc) == QuotaVendor.openCode &&
                cap(oc) == QuotaCapability.unsupported(QuotaSubject.UnsupportedReason.vendorHasNoQuotaAPI) &&
                QuotaAdapterRegistry.adapter(oc) !is RelayQuotaAdapter,
        )
        assertEquals(
            "38 without the channel flag the host alone does not decide OpenCode (the app's detection is the source)",
            QuotaVendor.customRelay,
            QuotaVendorDetector.vendor(desc(QuotaInstanceDescriptor.ProviderKind.openAI, base = "https://opencode.ai/zen/go/v1")),
        )
        assertTrue(
            "39 other unsupported vendors unchanged",
            cap(desc(QuotaInstanceDescriptor.ProviderKind.xAI)) == QuotaCapability.unsupported(QuotaSubject.UnsupportedReason.vendorHasNoQuotaAPI) &&
                cap(desc(QuotaInstanceDescriptor.ProviderKind.gemini, oauth = true)) == QuotaCapability.unsupported(QuotaSubject.UnsupportedReason.providerTypeUnsupported) &&
                cap(desc(QuotaInstanceDescriptor.ProviderKind.githubCopilot, oauth = true)) == QuotaCapability.unsupported(QuotaSubject.UnsupportedReason.providerTypeUnsupported) &&
                QuotaVendorDetector.vendor(desc(QuotaInstanceDescriptor.ProviderKind.openAI, base = "https://api.xiaomimimo.com/v1")) == QuotaVendor.xiaomiMiMo,
        )
    }

    // ── B. Console links ────────────────────────────────────────────────────

    @Test fun `console links`() {
        fun link(d: QuotaInstanceDescriptor) = QuotaConsoleLink.url(d)
        assertEquals(
            "40 Kimi Code keeps kimi.com/code (top-up balance is only on the site)",
            "https://www.kimi.com/code", link(desc(QuotaInstanceDescriptor.ProviderKind.kimiCode, oauth = true)),
        )
        assertEquals(
            "41 MiniMax international -> Billing > Token Plan",
            "https://platform.minimax.io/user-center/payment/token-plan",
            link(desc(QuotaInstanceDescriptor.ProviderKind.anthropic, base = "https://api.minimax.io/anthropic/v1")),
        )
        assertEquals(
            "42 MiniMax China -> plan usage console", "https://platform.minimaxi.com/console/usage",
            link(desc(QuotaInstanceDescriptor.ProviderKind.anthropic, base = "https://api.minimaxi.com/anthropic")),
        )
        assertEquals(
            "43 OpenCode -> opencode.ai/auth (the docs' console link)", "https://opencode.ai/auth",
            link(desc(QuotaInstanceDescriptor.ProviderKind.openAI, base = "https://opencode.ai/zen/go/v1", openCode = true)),
        )
    }

    // ── C. Store wiring (source) ────────────────────────────────────────────

    private val store = ProductionSources.read("quota/QuotaStore.kt")

    @Test fun `store wiring`() {
        val kimi = store.substringAfter("adapter.vendor == QuotaVendor.kimiCode && d.isOAuth && ctx != null -> {").take(900)
        assertTrue(
            "44 Kimi Code OAuth: pasted token first, else the device-flow token (refreshed when due)",
            kimi.contains("OAuthManager.forInstance(ctx, instance)?.loadManualBearerToken()?.trim()?.takeIf { it.isNotEmpty() }") &&
                kimi.contains("?: runCatching { com.openminis.app.auth.KimiOAuthManager(ctx, instanceId).validAccessToken() }.getOrNull()"),
        )
        assertTrue(
            "45 Kimi Code OAuth subject keyed by instance (rotating token never hashed)",
            kimi.contains("subjectId = subscriptionSubject(adapter.vendor, instanceId)"),
        )
        assertTrue(
            "46 response shape logged once per adapter per launch, never the body",
            store.contains("if (loggedResponseShapes.add(adapter.adapterId)) {") &&
                store.contains("AppLogger.info(TAG, \"[Quota] 🧩 \${adapter.adapterId} response shape: \$shape\")"),
        )
        val headers = store.substringAfter("private fun extraHeaders(vendor: QuotaVendor, d: QuotaInstanceDescriptor)").take(900)
        assertFalse("47 no client-identity headers are added for Kimi Code", headers.contains("kimiCode"))
        assertTrue(
            "48 endpoint override: DEV_TOOLS builds only, local file, keyed by instance (never a whole vendor)",
            store.contains("val endpointOverride = if (com.openminis.app.BuildConfig.DEV_TOOLS) debugEndpointOverride(d.instanceId) else null") &&
                store.contains("val raw = (map[instanceId] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content ?: return null") &&
                store.contains("File(dir, \"quota-debug-endpoints.json\")"),
        )
        assertTrue(
            "49 OpenCode flag comes from the app's own detection",
            store.contains("isOpenCodeChannel = isOpenCodeChannel(instance),") &&
                store.contains("com.openminis.app.provider.OpenCodeSessionHeader.matches(instance.customBaseURL)"),
        )
    }

    // ── Android-only additions ──────────────────────────────────────────────

    @Test fun `DEV_TOOLS is a compile-time false in release, so the override cannot run there`() {
        val gradle = java.io.File(ProductionSources.mainRoot()!!.path.substringBefore("/src/main/") + "/build.gradle.kts").readText()
        assertTrue("50 defaultConfig: DEV_TOOLS false", gradle.contains("buildConfigField(\"boolean\", \"DEV_TOOLS\", \"false\")"))
        val release = gradle.substringAfter("        release {").substringBefore("\n        }\n")
        assertFalse("51 release does not turn DEV_TOOLS on", release.contains("DEV_TOOLS"))
    }

    @Test fun `OpenCode detection is limited to OpenAI-protocol types, like iOS isOpenCodeChannel`() {
        val fn = store.substringAfter("fun isOpenCodeChannel(instance: ProviderInstance): Boolean").take(700)
        assertTrue(
            "52 OpenAI-family types consult the header gate; Anthropic / Gemini / Antigravity never do",
            fn.contains("ProviderType.anthropic, ProviderType.gemini, ProviderType.antigravity, ProviderType.unsupported -> false"),
        )
        assertNotNull(QuotaVendor.valueOf("miniMax")); assertNotNull(QuotaVendor.valueOf("openCode"))
    }
}
