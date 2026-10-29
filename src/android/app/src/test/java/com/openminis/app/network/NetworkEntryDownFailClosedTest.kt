package com.openminis.app.network

import com.openminis.app.ProductionSources
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [FIX-261025-11] With the local entry down, a proxy / Tailscale route used to
 * go direct - out from the device's own address, around the proxy the user
 * chose - until the entry was back (up to ~30 s, or for good if it could not
 * be configured). It now fails closed; direct routes stay direct.
 */
class NetworkEntryDownFailClosedTest {

    @Before fun reset() = NetworkRuntime.resetForTest()
    @After fun tearDown() = NetworkRuntime.resetForTest()

    @Test fun `a proxy or tailscale route is blocked while the entry is down, a direct one stays direct`() {
        NetworkRuntime.publish(NetworkRuntime.Snapshot(port = null, entryDown = true, defaultRoute = "direct",
            tagRoutes = mapOf("shell" to "proxy:a", "browser" to "tailscale", "backup" to "default")))
        for (tag in listOf("shell", "browser")) {
            val ep = NetworkRuntime.endpoint(tag)!!
            assertEquals(tag, NetworkRuntime.BLOCKED_PORT, ep.port)
            assertEquals(tag, NetworkRuntime.BLOCKED_PORT, ep.httpPort)
        }
        assertNull("direct default", NetworkRuntime.endpoint("backup"))
        assertNull(NetworkRuntime.endpoint("prov:x"))
    }

    @Test fun `an unconfigured app is never marked down`() {
        assertNull(NetworkRuntime.endpoint("shell"))
        val src = ProductionSources.read("network/NetworkHub.kt")
        assertTrue("only when the entry is wanted", src.contains("entryDown = s.needsEntry && port == null,"))
        assertTrue("a dead entry is published down, not direct",
            src.contains("NetworkRuntime.snapshot.copy(port = null, httpPort = null, password = \"\", entryDown = true)"))
    }

    @Test fun `minis-mcp-cli is pointed at the blocked endpoint on a routed MCP scope`() {
        NetworkRuntime.publish(NetworkRuntime.Snapshot(port = null, entryDown = true, defaultRoute = "proxy:a"))
        val vars = NetworkShellEnvironment.proxyVariables()
        assertTrue(vars[ShellProxyVariables.MCP_PROXY_KEY]!!.contains("127.0.0.1:${NetworkRuntime.BLOCKED_PORT}"))
        NetworkRuntime.publish(NetworkRuntime.Snapshot(port = null, entryDown = true, defaultRoute = "direct"))
        assertNull(NetworkShellEnvironment.proxyVariables()[ShellProxyVariables.MCP_PROXY_KEY])
    }
}
