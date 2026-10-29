package com.openminis.app.network

import com.openminis.app.ProductionSources
import java.io.IOException
import okhttp3.Credentials
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * [FEAT-261021-16] G1: every HTTP client the app builds follows Settings >
 * Network under the category the user confirmed (2026-10-21):
 *  - a provider's model list, usage and sign-in: prov:<id>;
 *  - the app's own requests (updates, skills, models.dev, voice, weather,
 *    images): default;
 *  - browser downloads: browser; the shell's reachability probe: shell;
 *  - mirror speed tests, loopback and debug tooling: always direct.
 * And an unconfigured app sends exactly what it sent before.
 */
class NetworkCoverageTest {

    private lateinit var server: MockWebServer

    @Before fun setUp() {
        NetworkRuntime.resetForTest()
        NetworkHttp.clearCacheForTest()
        server = MockWebServer().also { it.start() }
    }

    @After fun tearDown() {
        server.shutdown()
        NetworkRuntime.resetForTest()
        NetworkHttp.clearCacheForTest()
    }

    // ── HttpURLConnection call sites ────────────────────────────────────

    @Test
    fun unconfiguredUrlRequestsAreThePlainHttpUrlConnection() {
        assertNull("no route: no OkHttp client at all", NetworkUrlRequest.routedClient(NetworkHttp.DEFAULT_TAG))
        server.enqueue(MockResponse().setBody("hello"))
        NetworkUrlRequest.open(
            NetworkHttp.DEFAULT_TAG, server.url("/a").toString(), "POST",
            headers = mapOf("Authorization" to "Bearer t", "Content-Type" to "application/json"),
            body = "{}".toByteArray(), connectTimeoutMs = 15_000, readTimeoutMs = 15_000,
        ).use { r ->
            assertEquals(200, r.code)
            assertEquals("hello", r.text())
        }
        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/a", req.path)
        assertEquals("Bearer t", req.getHeader("Authorization"))
        assertEquals("{}", req.body.readUtf8())
        // HttpURLConnection's own User-Agent (the JVM's "Java/<version>"), not OkHttp's.
        assertTrue(req.getHeader("User-Agent").orEmpty(), req.getHeader("User-Agent").orEmpty().startsWith("Java"))
        assertNull(req.getHeader("Proxy-Authorization"))
    }

    @Test
    fun anHttpErrorThrowsFromTheBodyOnBothPaths() {
        server.enqueue(MockResponse().setResponseCode(404))
        NetworkUrlRequest.open(NetworkHttp.DEFAULT_TAG, server.url("/x").toString()).use { r ->
            assertEquals(404, r.code)
            try { r.body(); fail("direct 404 must throw like getInputStream") } catch (_: IOException) {}
        }
        routeDefaultThroughEntry(server.port)
        server.enqueue(MockResponse().setResponseCode(404))
        NetworkUrlRequest.open(NetworkHttp.DEFAULT_TAG, "http://example.invalid/x").use { r ->
            assertEquals(404, r.code)
            try { r.body(); fail("routed 404 must throw like getInputStream") } catch (_: IOException) {}
        }
    }

    @Test
    fun aRoutedUrlRequestGoesThroughTheEntryWithItsTag() {
        // The mock plays the entry's HTTP front: it demands the entry
        // credentials once, then answers as the origin.
        routeDefaultThroughEntry(server.port)
        assertNotNull(NetworkUrlRequest.routedClient(NetworkHttp.DEFAULT_TAG))
        server.enqueue(MockResponse().setResponseCode(407).setHeader("Proxy-Authenticate", "Basic realm=\"Minis\""))
        server.enqueue(MockResponse().setBody("via entry"))
        NetworkUrlRequest.open(
            NetworkHttp.DEFAULT_TAG, "http://example.invalid/skill.md",
            headers = mapOf("Cookie" to "a=b"), connectTimeoutMs = 5_000, readTimeoutMs = 5_000,
        ).use { r ->
            assertEquals(200, r.code)
            assertEquals("via entry", r.text())
        }
        server.takeRequest() // the 407 round
        val req = server.takeRequest()
        assertEquals("GET http://example.invalid/skill.md HTTP/1.1", req.requestLine)
        assertEquals(Credentials.basic(NetworkHttp.DEFAULT_TAG, "pw"), req.getHeader("Proxy-Authorization"))
        assertEquals("a=b", req.getHeader("Cookie"))
        // Another tag without a route of its own: still plain HttpURLConnection.
        NetworkRuntime.publish(NetworkRuntime.snapshot.copy(defaultRoute = "direct", tagRoutes = mapOf("browser" to "proxy:p")))
        assertNull(NetworkUrlRequest.routedClient(NetworkHttp.DEFAULT_TAG))
        assertNotNull(NetworkUrlRequest.routedClient(NetworkScope.BROWSER.tag))
    }

    private fun routeDefaultThroughEntry(httpPort: Int) {
        NetworkRuntime.publish(NetworkRuntime.Snapshot(port = 1, httpPort = httpPort, password = "pw", defaultRoute = "proxy:p"))
    }

    // ── Source guards: each call site keeps its category ─────────────────

    private fun src(path: String) = ProductionSources.read(path)

    private fun assertHas(path: String, vararg needles: String) {
        val s = src(path)
        for (n in needles) assertTrue("$path must contain: $n", s.contains(n))
    }

    @Test
    fun providerSideRequestsFollowTheProviderRoute() {
        for (f in listOf(
            "provider/openai/OpenAIModelsApi.kt", "provider/anthropic/AnthropicModelsApi.kt",
            "provider/gemini/GeminiModelsApi.kt", "provider/antigravity/AntigravityModelsApi.kt",
            "provider/openrouter/OpenRouterModelsApi.kt", "provider/openai/CodexModelsApi.kt",
        )) assertHas(f, "NetworkHttp.client(netTag, client)")
        val repo = src("data/repository/ProviderRepository.kt")
        assertTrue(repo.contains("val netTag = com.openminis.app.network.NetworkRuntime.providerTag(instance.id)"))
        // anthropic, gemini, openai, openRouter, xAI, kimi + the Codex discovery
        assertEquals(6, repo.split("netTag = netTag").size - 1)
        assertTrue(repo.contains("netTag = com.openminis.app.network.NetworkRuntime.providerTag(instance.id),"))
        assertHas("quota/QuotaAdapters.kt", "NetworkHttp.client(netTag, baseClient)")
        assertHas("quota/QuotaStore.kt", "QuotaOkHttpTransport(com.openminis.app.network.NetworkRuntime.providerTag(d.instanceId))")
        assertHas("auth/OAuthManager.kt", "NetworkRuntime.providerTag(instanceId)", "NetworkHttp.client(netTag, baseHttpClient)")
        assertHas("auth/OpenAIOAuthManager.kt", "NetworkHttp.client(netTag, systemAwareHttpClient)")
        assertHas("auth/OpenRouterOAuthManager.kt", "NetworkHttp.client(netTag, client)", "providerTag(instanceId)")
        for (f in listOf("auth/GeminiOAuthManager.kt", "auth/AntigravityOAuthManager.kt")) {
            assertHas(f, "NetworkUrlRequest.open(netTag,")
            assertFalse("$f: no HttpURLConnection left", src(f).contains("HttpURLConnection\n") || src(f).contains("as HttpURLConnection"))
        }
    }

    @Test
    fun appRequestsFollowTheDefaultRoute() {
        assertHas("data/UpdateChecker.kt", "NetworkHttp.DEFAULT_TAG, baseClient)")
        assertHas("data/repository/SkillRepository.kt", "NetworkHttp.DEFAULT_TAG, baseHttpClient)")
        assertHas("offload/WeatherManager.kt", "NetworkHttp.DEFAULT_TAG, baseClient)")
        assertHas("provider/ModelsDevApi.kt", "NetworkHttp.DEFAULT_TAG, client)")
        assertHas("provider/voice/VoiceProvider.kt", "NetworkHttp.DEFAULT_TAG, baseHttpClient)")
        assertHas("MinisApp.kt", "NetworkHttp.DEFAULT_TAG, base).newCall(request)", "val base = okhttp3.OkHttpClient()")
        for (f in listOf("ui/settings/MinisSkillsBrowserScreen.kt", "ui/settings/SkillsManagementScreen.kt", "ui/components/FullscreenImageViewer.kt")) {
            assertHas(f, "NetworkUrlRequest.open(", "NetworkHttp.DEFAULT_TAG")
        }
        assertHas("browser/BrowserTabPool.kt", "NetworkScope.BROWSER.tag, url,")
        assertHas("agent/shell/OnDemandBash.kt", "NetworkScope.SHELL.tag, APK_PROBE_URL, \"HEAD\"")
    }

    /** [FEAT-261021-18] G8 / G4b / G5: what the diagnostics now check and say. */
    @Test
    fun diagnosticsCheckTheHttpFrontAndNameWhatIsNotRouted() {
        val d = src("network/NetworkDiagnostics.kt")
        assertTrue(d.contains("require(anonymous.contains(\" 407\"))"))
        assertTrue(d.contains("require(ok.contains(\" 200\"))"))
        assertTrue("the tunnel targets a throwaway loopback listener, not the entry's own ports",
            d.contains("java.net.ServerSocket(0, 1, InetAddress.getByName(\"127.0.0.1\"))"))
        // [FEAT-261021-23] Shell UDP / DNS are real checks now (SKIP only while forwarding is off).
        assertTrue(d.contains("checks += shellUdpDnsChecks(listening, magicName)"))
        assertTrue(d.contains("TailscaleNode.shellProbe(resolver, names, TIMEOUT_MS)"))
        assertTrue(d.contains("Check(\"Inbound / ICMP\", Result.SKIP"))
        assertTrue(d.contains("!WebViewNetworkProxy.isSupported()"))
        // All of it after the unconfigured early return: no Go, no sockets.
        val early = d.indexOf("if (!s.needsEntry && !s.nodeWanted)")
        assertTrue(early in 0 until d.indexOf("httpFrontSelfTest(httpPort"))
        assertTrue(early < d.indexOf("checks += shellUdpDnsChecks("))
    }

    /**
     * Whole-tree census: a file that builds an HTTP client must be listed
     * here with its route, so a new client cannot silently stay direct.
     */
    @Test
    fun everyHttpClientInTheAppIsAccountedFor() {
        val pattern = Regex("""OkHttpClient\.Builder\(\)|OkHttpClient\(\)|\.openConnection\(|\.openStream\(\)|ImageLoader\.Builder\(""")
        val root = ProductionSources.mainRoot()!!
        val found = ProductionSources.allKotlinFiles()
            .filter { pattern.containsMatchIn(it.readText()) }
            .map { it.relativeTo(root).path.replace('\\', '/') }
            .toSortedSet()
        val routed = setOf(
            "MinisApp.kt", "auth/OAuthManager.kt", "auth/OpenAIOAuthManager.kt", "auth/OpenRouterOAuthManager.kt",
            "data/UpdateChecker.kt", "data/repository/SkillRepository.kt", "offload/WeatherManager.kt",
            "provider/ModelsDevApi.kt", "provider/anthropic/AnthropicModelsApi.kt", "provider/anthropic/AnthropicProvider.kt",
            "provider/antigravity/AntigravityModelsApi.kt", "provider/gemini/GeminiModelsApi.kt", "provider/gemini/GeminiProvider.kt",
            "provider/openai/CodexModelsApi.kt", "provider/openai/OpenAIModelsApi.kt", "provider/openai/OpenAIProvider.kt",
            "provider/openrouter/OpenRouterModelsApi.kt", "provider/voice/VoiceProvider.kt", "quota/QuotaAdapters.kt",
            "mcp/oauth/MCPOAuthDiscovery.kt",
            // MCPOAuthController's own default client is unused in the app: the
            // screen injects mcpOAuthHttp (NetworkHttp.following, NetworkFollowingClientTest).
            "mcp/oauth/MCPOAuthController.kt", "ui/settings/MCPIntegrationsScreen.kt",
            // The module itself.
            "network/NetworkHttp.kt", "network/NetworkUrlRequest.kt",
        )
        val directByDecision = setOf(
            "ui/sandbox/MirrorSettingsScreen.kt", // mirror speed test measures this network itself
            "auth/OAuthRedirectActivity.kt", // loopback callback
            "ui/MinisImageFetcher.kt", // minis:// local files (Coil mention in a comment)
            "debug/DebugRPCHandler.kt", "debug/NetDebugMethods.kt", "debug/ProviderMutationMethods.kt", // debug tooling
        )
        val unknown = found - routed - directByDecision
        assertTrue("HTTP clients without a Settings > Network decision: $unknown", unknown.isEmpty())
    }
}
