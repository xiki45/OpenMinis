package com.openminis.app.network

import java.io.File
import java.net.Proxy
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [T-net-android-phase3] Clients follow Settings > Network, and an
 * unconfigured app's clients are exactly the ones it built before:
 *  - OkHttp: NetworkHttp.client returns the SAME OkHttpClient object;
 *  - shell: no proxy variable is added;
 *  - every place that builds one of these clients goes through the module.
 */
class NetworkClientsTest {

    private val base = OkHttpClient.Builder().build()

    @Before fun reset() {
        NetworkRuntime.resetForTest()
        NetworkHttp.clearCacheForTest()
    }

    @After fun after() = reset()

    @Test
    fun okHttpIsUntouchedWithoutARoute() {
        for (tag in listOf(null, "default", "mcp", "prov:x")) assertSame(base, NetworkHttp.client(tag, base))
        // Entry up but every route direct and Tailscale off: still the same object.
        NetworkRuntime.publish(NetworkRuntime.Snapshot(port = 1, httpPort = 2, password = "pw"))
        assertSame(base, NetworkHttp.client("mcp", base))
        // A route, but the HTTP front is not listening: direct, untouched.
        NetworkRuntime.publish(NetworkRuntime.Snapshot(port = 1, httpPort = null, password = "pw", defaultRoute = "tailscale"))
        assertSame(base, NetworkHttp.client("mcp", base))
    }

    @Test
    fun okHttpGoesThroughTheEntryOnlyForRoutedTagsAndRebuildsOnChange() {
        NetworkRuntime.publish(NetworkRuntime.Snapshot(port = 1, httpPort = 4100, password = "pw",
            tagRoutes = mapOf("mcp" to "proxy:a")))
        val c = NetworkHttp.client("mcp", base)
        assertNotSame(base, c)
        assertSame("cached per generation", c, NetworkHttp.client("mcp", base))
        assertSame(base, NetworkHttp.client("shell", base))
        // Everything else about the client is the base's.
        assertSame(base.connectionPool, c.connectionPool)
        assertSame(base.dispatcher, c.dispatcher)
        assertEquals(base.readTimeoutMillis, c.readTimeoutMillis)
        // A proxy route never falls back to direct around the proxy...
        val sel = c.proxySelector as NetworkHttp.EntrySelector
        assertTrue(sel.select(null).none { it == Proxy.NO_PROXY } || sel.select(null).size == 1)
        // ...a new generation rebuilds.
        NetworkRuntime.publish(NetworkRuntime.snapshot.copy(httpPort = 4200))
        assertNotSame(c, NetworkHttp.client("mcp", base))
        // Back to direct: the base itself again.
        NetworkRuntime.publish(NetworkRuntime.Snapshot())
        assertSame(base, NetworkHttp.client("mcp", base))
    }

    @Test
    fun aDirectRouteThroughTheEntryKeepsADirectFallback() {
        // Port 1 on loopback: nothing listens, so the liveness check fails.
        val dead = NetworkHttp.EntrySelector(1, fallbackDirect = true)
        assertEquals(listOf(Proxy.NO_PROXY), dead.select(null))
        val deadProxyRoute = NetworkHttp.EntrySelector(1, fallbackDirect = false)
        assertEquals(1, deadProxyRoute.select(null).size)
        assertEquals(Proxy.Type.HTTP, deadProxyRoute.select(null).single().type())
    }

    @Test
    fun shellGetsNoVariablesUnlessRouted() {
        assertTrue(NetworkShellEnvironment.proxyVariables().isEmpty())
        // [FIX-261021-01] Tailscale on with a direct shell: the entry exists
        // for OkHttp's tailnet split, but the shell gets nothing.
        // [FEAT-261021-19] Only minis-mcp-cli's MINIS_MCP_PROXY (the MCP scope), never the shell's own.
        NetworkRuntime.publish(NetworkRuntime.Snapshot(port = 4000, httpPort = 4001, password = "pw", tailscaleEnabled = true))
        assertEquals(mapOf("MINIS_MCP_PROXY" to "http://mcp:pw@127.0.0.1:4001"), NetworkShellEnvironment.proxyVariables())
        // A routed shell: SOCKS for ALL_PROXY, the HTTP front for HTTP(S)_PROXY.
        NetworkRuntime.publish(NetworkRuntime.snapshot.copy(tagRoutes = mapOf("shell" to "tailscale")))
        val v = NetworkShellEnvironment.proxyVariables()
        assertEquals(setOf("ALL_PROXY", "all_proxy", "HTTP_PROXY", "http_proxy", "HTTPS_PROXY", "https_proxy", "NO_PROXY", "no_proxy", "MINIS_MCP_PROXY"), v.keys)
        assertEquals("socks5h://shell:pw@127.0.0.1:4000", v["ALL_PROXY"])
        assertEquals("http://shell:pw@127.0.0.1:4001", v["HTTPS_PROXY"])
        assertEquals("localhost,127.0.0.1,::1", v["no_proxy"])
        NetworkRuntime.publish(NetworkRuntime.Snapshot(port = null, tailscaleEnabled = true, tagRoutes = mapOf("shell" to "tailscale")))
        assertTrue(NetworkShellEnvironment.proxyVariables().isEmpty())
        // The entry without its HTTP front, or without a password: no MCP variable either.
        NetworkRuntime.publish(NetworkRuntime.Snapshot(port = 4000, httpPort = null, password = "pw", tailscaleEnabled = true))
        assertTrue(NetworkShellEnvironment.proxyVariables().isEmpty())
        assertTrue(ShellProxyVariables.mcp(4000, 4001, "").isEmpty())
    }

    /**
     * [FEAT-261021-19, iOS d72a2b261 G2] SFTP / FTP backups take the Backup
     * route through rclone's socks_proxy; an unconfigured app never calls it
     * (the first value is "" and equals the initial state).
     */
    @Test
    fun sftpAndFtpFollowTheBackupRouteThroughSocksProxy() {
        val b = File("src/main/java/com/openminis/app/backup/remote/RcloneBridge.kt").readText()
        assertTrue(b.contains("applySocksProxy(ep?.let { \"${'$'}{it.tag}:${'$'}{it.password}@${'$'}{it.host}:${'$'}{it.port}\" } ?: \"\")"))
        assertTrue(b.contains("if (socks == lastAppliedSocks) return"))
        assertTrue(b.contains("private var lastAppliedSocks = \"\""))
        assertTrue(b.contains("Gomobile.rcloneSetSocks(socks)"))
    }

    /** [FEAT-261021-19] Android's minis-mcp-cli reads MINIS_MCP_PROXY exactly as iOS's (59362a754). */
    @Test
    fun mcpCliRoutingMatchesIos() {
        fun block(p: String): String {
            val s = File(p).readText()
            return s.substring(s.indexOf("MCP_PROXY_ENV = "), s.indexOf("class MCPError"))
        }
        val rel = "usr/local/lib/minis-mcp-cli/transport/http.py"
        val android = block("src/main/assets/default_mount/$rel")
        assertEquals(block("../../ios/default_mount/$rel"), android)
        val s = File("src/main/assets/default_mount/$rel").readText()
        assertTrue(s.contains("resp = httpx.post(token_endpoint, data=form, timeout=30,\n                              **proxy_kwargs(token_endpoint))"))
        assertTrue(s.contains("**proxy_kwargs(self.url)"))
    }

    @Test
    fun everyClientSiteGoesThroughTheModule() {
        fun src(p: String) = File("src/main/java/com/openminis/app/$p").readText()
        for (p in listOf("provider/openai/OpenAIProvider.kt", "provider/anthropic/AnthropicProvider.kt", "provider/gemini/GeminiProvider.kt")) {
            val s = src(p)
            assertTrue(p, s.contains("private val client: OkHttpClient get() = com.openminis.app.network.NetworkHttp.client(netTag, baseClient)"))
            assertTrue(p, s.contains("NetworkTagged"))
        }
        assertTrue(src("provider/ProviderFactory.kt").contains("NetworkRuntime.providerTag(instance.id)"))
        assertTrue(src("mcp/oauth/MCPOAuthDiscovery.kt").contains("NetworkHttp.client(com.openminis.app.network.NetworkScope.MCP.tag, http)"))
        for (p in listOf("sandbox/ShellExecutor.kt", "sandbox/PersistentShell.kt", "sandbox/FreshProcessShell.kt", "sandbox/TerminalSession.kt", "sandbox/ExecutionCoordinator.kt")) {
            assertTrue(p, src(p).contains("NetworkShellEnvironment.proxyVariables()"))
        }
        // Every WebViewClient answers the entry's proxy challenge (the
        // override is process-wide, so one that did not would fail to load
        // any remote page while a Browser route is on).
        for (p in listOf("browser/BrowserUseManager.kt", "ui/preview/WebViewHolder.kt", "webapp/WebAppActivity.kt",
                "ui/settings/MinisSkillsBrowserScreen.kt", "ui/sandbox/FilePreviewScreen.kt", "ui/chat/KatexWebViewPool.kt", "ui/markdown/KaTeXView.kt")) {
            val s = src(p)
            assertEquals(p, s.split("object : WebViewClient() {").size - 1, s.split("WebViewNetworkProxy.answerAuth(").size - 1)
        }
        assertTrue(src("backup/remote/RcloneBridge.kt").contains("applyNetworkProxy()"))
    }

    @Test
    fun theUserProxyNeverLeaksIntoLogs() {
        assertNull(NetworkRuntime.endpoint("mcp"))
        val ep = SocksEndpoint(1, "mcp", "s3cret", 2)
        assertTrue(!ep.toString().contains("s3cret"))
    }
}
