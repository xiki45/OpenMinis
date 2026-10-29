package com.openminis.app.network

import java.io.File
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [FIX-261021-01] Port of iOS b1b1fe2c7 / 62f3577a3 (which proxy variables
 * the shell gets) and 1891a0258 (a logged-out Tailscale node no longer pulls
 * direct traffic through the local entry).
 */
class TailnetGateAndShellProxyTest {

    @Before fun reset() = NetworkRuntime.resetForTest()

    @After fun after() = NetworkRuntime.resetForTest()

    private fun status(running: Boolean, state: String) =
        TailscaleStatus.parse(JSONObject().put("running", running).put("backendState", state))

    @Test
    fun tailnetIsUsableOnlyForAWorkingOrSettlingNode() {
        for (s in listOf("Running", "Starting", "NoState", "")) assertTrue(s, status(true, s).tailnetUsable)
        for (s in listOf("NeedsLogin", "NeedsMachineAuth", "Stopped")) assertFalse(s, status(true, s).tailnetUsable)
        assertFalse("not running", status(false, "Running").tailnetUsable)
        assertFalse("no status", TailscaleStatus().tailnetUsable)
    }

    @Test
    fun aLoggedOutNodeLeavesDirectRoutesDirect() {
        // The 10-19 iOS case: Tailscale switched on, never logged in.
        NetworkRuntime.publish(NetworkRuntime.Snapshot(port = 4000, httpPort = 4001, password = "pw",
            tailscaleEnabled = true && status(true, "NeedsLogin").tailnetUsable,
            tagRoutes = mapOf("mcp" to "proxy:a")))
        assertNull("direct provider", NetworkRuntime.endpoint("prov:x"))
        assertNull("direct shell", NetworkRuntime.endpoint("shell"))
        assertNotNull("a proxy route still uses the entry", NetworkRuntime.endpoint("mcp"))
        // Logged in: direct routes split tailnet hosts off through the entry again.
        NetworkRuntime.publish(NetworkRuntime.snapshot.copy(tailscaleEnabled = status(true, "Running").tailnetUsable))
        assertNotNull(NetworkRuntime.endpoint("prov:x"))
    }

    @Test
    fun shellVariablesFollowTheRouteAndTheHttpFront() {
        val socks = "socks5h://shell:pw@127.0.0.1:4000"
        val http = "http://shell:pw@127.0.0.1:4001"
        assertTrue(ShellProxyVariables.make(socks, http, routeIsDirect = true).isEmpty())
        assertTrue(ShellProxyVariables.make(null, http, routeIsDirect = false).isEmpty())
        assertTrue(ShellProxyVariables.make("", http, routeIsDirect = false).isEmpty())
        val routed = ShellProxyVariables.make(socks, http, routeIsDirect = false)
        assertEquals(socks, routed["ALL_PROXY"])
        assertEquals(socks, routed["all_proxy"])
        for (k in listOf("HTTP_PROXY", "http_proxy", "HTTPS_PROXY", "https_proxy")) assertEquals(k, http, routed[k])
        assertEquals(ShellProxyVariables.NO_PROXY, routed["NO_PROXY"])
        // No HTTP front: HTTP(S)_PROXY are left unset, never pointed at SOCKS.
        val noFront = ShellProxyVariables.make(socks, null, routeIsDirect = false)
        assertEquals(setOf("ALL_PROXY", "all_proxy", "NO_PROXY", "no_proxy"), noFront.keys)
        assertFalse(noFront.values.any { it.startsWith("socks") && it == noFront["HTTP_PROXY"] })
    }

    @Test
    fun theHttpUrlEncodesCredentialsAndNeedsTheFront() {
        assertNull(SocksEndpoint(4000, "shell", "pw", null).httpUrl)
        assertEquals("http://shell:p%40ss%20w@127.0.0.1:4001", SocksEndpoint(4000, "shell", "p@ss w", 4001).httpUrl)
    }

    @Test
    fun theHubGatesTheSplitOnTheTailnetAndEveryStatusGoesThroughIt() {
        val src = File("src/main/java/com/openminis/app/network/NetworkHub.kt").readText()
        assertTrue(src.contains("tailscaleEnabled = nodeUp && status.tailnetUsable"))
        // Only applyNow and publishStatus write the status flow directly.
        assertEquals(2, src.split("_tailscale.value = ").size - 1)
        assertTrue(src.contains("publish = ::publishStatus"))
    }

    @Test
    fun theEntryIsCheckedEveryThirtySecondsOnlyWhileInFront() {
        assertEquals(30_000L, NetworkHub.HEALTH_INTERVAL_MS)
        val app = File("src/main/java/com/openminis/app/MinisApp.kt").readText()
        assertTrue(app.contains("NetworkHub.onForeground()"))
        assertTrue(app.contains("NetworkHub.onBackground()"))
        val hub = File("src/main/java/com/openminis/app/network/NetworkHub.kt").readText()
        val fg = hub.substringAfter("fun onForeground()").substringBefore("fun onBackground()")
        assertTrue(fg.contains("delay(HEALTH_INTERVAL_MS)") && fg.contains("verifyEntry()"))
        // Status is read only from a node this process started.
        assertTrue(hub.contains("if (nodeUp) scope.launch { refreshStatusNow() }"))
    }
}
