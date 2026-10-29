package com.openminis.app.network

import com.openminis.app.ProductionSources
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.URI
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [FEAT-261021-08] NetworkHttp.following: one long-lived client that asks
 * the current route on every connection - used for MCP sign-in's token
 * exchange and refresh, as on iOS.
 */
class NetworkFollowingClientTest {

    @Before fun reset() {
        NetworkRuntime.resetForTest()
        NetworkHttp.clearCacheForTest()
    }

    @After fun after() = reset()

    private val systemProxy = Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved("corp-proxy", 3128))
    private val baseSelector = object : ProxySelector() {
        override fun select(uri: URI?) = listOf(systemProxy)
        override fun connectFailed(uri: URI?, sa: java.net.SocketAddress?, ioe: java.io.IOException?) {}
    }
    private val base = OkHttpClient.Builder().proxySelector(baseSelector).build()
    private val uri = URI("https://auth.example.com/token")

    @Test
    fun withoutARouteItAnswersExactlyAsTheBase() {
        val c = NetworkHttp.following("mcp", base)
        assertEquals("the system proxy still applies", listOf(systemProxy), c.proxySelector.select(uri))
        // Entry up, MCP direct, Tailscale off: still the base.
        NetworkRuntime.publish(NetworkRuntime.Snapshot(port = 1, httpPort = 2, password = "pw"))
        assertEquals(listOf(systemProxy), c.proxySelector.select(uri))
    }

    @Test
    fun theSameClientFollowsARouteSetLater() {
        val c = NetworkHttp.following("mcp", base)
        assertEquals(listOf(systemProxy), c.proxySelector.select(uri))
        // Port 1 on loopback: nothing listens, a proxy route still names the entry (it fails, never goes around it).
        NetworkRuntime.publish(NetworkRuntime.Snapshot(port = 1, httpPort = 1, password = "pw", tagRoutes = mapOf("mcp" to "proxy:a")))
        val routed = c.proxySelector.select(uri)
        assertEquals(1, routed.size)
        assertEquals(1, (routed.single().address() as InetSocketAddress).port)
        // Back to direct: the base again, on the same client object.
        NetworkRuntime.publish(NetworkRuntime.Snapshot())
        assertEquals(listOf(systemProxy), c.proxySelector.select(uri))
    }

    @Test
    fun mcpSignInUsesIt() {
        val screen = ProductionSources.read("ui/settings/MCPIntegrationsScreen.kt")
        assertEquals(2, screen.split("MCPOAuthController(").size - 1)
        assertEquals(2, screen.split("http = mcpOAuthHttp)").size - 1)
        assertTrue(screen.contains("NetworkHttp.following(\n        com.openminis.app.network.NetworkScope.MCP.tag,"))
    }
}
