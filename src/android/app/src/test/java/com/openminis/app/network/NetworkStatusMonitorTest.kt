package com.openminis.app.network

import com.openminis.app.ProductionSources
import com.openminis.app.network.NetworkStatusMonitor.ProxyHealth
import com.openminis.app.network.NetworkStatusMonitor.ProxyLevel
import com.openminis.app.network.NetworkStatusMonitor.TailscaleLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [FEAT-261021-07] Port of iOS a6e6c10d1: the home screen's Tailscale / proxy
 * pills - when they show, what they say, and that nothing runs for an
 * unconfigured app.
 */
class NetworkStatusMonitorTest {

    private val a = ProxyServer(id = "A", name = "Mac Proxy", host = "10.0.0.2", port = 8080)
    private val b = ProxyServer(id = "B", host = "10.0.0.3", port = 1080)

    @Test
    fun onlyProxiesARouteUsesGetAPill() {
        assertFalse(NetworkStatusMonitor.wanted(NetworkSettings()))
        val saved = NetworkSettings(proxies = listOf(a, b))
        assertTrue("saved only", NetworkStatusMonitor.proxiesInUse(saved).isEmpty())
        assertFalse(NetworkStatusMonitor.wanted(saved))
        val used = saved.copy(scopes = mapOf("shell" to "proxy:A"), providerRoutes = mapOf("p" to "proxy:B"))
        assertEquals(listOf(a, b), NetworkStatusMonitor.proxiesInUse(used))
        assertEquals(listOf(a), NetworkStatusMonitor.proxiesInUse(saved.copy(defaultRoute = "proxy:A")))
        assertTrue("routing off", NetworkStatusMonitor.proxiesInUse(used.copy(forceDirect = true)).isEmpty())
        assertTrue(NetworkStatusMonitor.wanted(NetworkSettings(tailscaleEnabled = true)))
        assertFalse(NetworkStatusMonitor.wanted(NetworkSettings(tailscaleEnabled = true, forceDirect = true)))
    }

    @Test
    fun aProxyPillPrefersRealFailuresOverAnOldProbe() {
        val failing = setOf<NetworkAlertEvent.Subject>(NetworkAlertEvent.Subject.Proxy("A"))
        assertEquals(ProxyLevel.UNAVAILABLE, NetworkStatusMonitor.proxyLevel("A", mapOf("A" to ProxyHealth.AVAILABLE), failing))
        assertEquals(ProxyLevel.AVAILABLE, NetworkStatusMonitor.proxyLevel("A", mapOf("A" to ProxyHealth.AVAILABLE), emptySet()))
        assertEquals(ProxyLevel.CHECKING, NetworkStatusMonitor.proxyLevel("A", emptyMap(), emptySet()))
        assertEquals(ProxyLevel.UNAVAILABLE, NetworkStatusMonitor.proxyLevel("B", mapOf("B" to ProxyHealth.UNAVAILABLE), emptySet()))
        // Several proxies share one pill with the worst state.
        assertEquals(ProxyLevel.UNAVAILABLE, listOf(ProxyLevel.AVAILABLE, ProxyLevel.UNAVAILABLE, ProxyLevel.CHECKING).max())
        assertEquals(ProxyLevel.CHECKING, listOf(ProxyLevel.AVAILABLE, ProxyLevel.CHECKING).max())
    }

    @Test
    fun theTailscalePillReadsTheNodeLikeTheStatusRow() {
        fun lvl(running: Boolean, state: String, applying: Boolean = false, err: String? = null, failing: Boolean = false) =
            NetworkStatusMonitor.tailscaleLevel(TailscaleStatus(running = running, backendState = state), applying, err, failing)
        assertEquals(TailscaleLevel.CONNECTED, lvl(true, "Running"))
        assertEquals(TailscaleLevel.UNAVAILABLE, lvl(true, "Running", failing = true))
        assertEquals(TailscaleLevel.NOT_LOGGED_IN, lvl(true, "NeedsLogin"))
        assertEquals(TailscaleLevel.AWAITING_APPROVAL, lvl(true, "NeedsMachineAuth"))
        assertEquals(TailscaleLevel.CONNECTING, lvl(true, "Starting"))
        assertEquals(TailscaleLevel.CONNECTING, lvl(false, "", applying = true))
        assertEquals(TailscaleLevel.CONNECTING, lvl(false, ""))
        assertEquals(TailscaleLevel.NOT_CONNECTED, lvl(false, "", err = "Tailscale: failed"))
    }

    @Test
    fun thePillsSitUnderTheHomeTitleAndFollowTheAppLifecycle() {
        val list = ProductionSources.read("ui/sessions/SessionListScreen.kt")
        assertTrue(list.contains("NetworkStatusBadges(onOpen = onNetworkPageClick)"))
        assertTrue(ProductionSources.read("ui/navigation/ChatSplitScaffold.kt")
            .contains("onNetworkPageClick = { route -> navController.safeNavigate(route) }"))
        val badges = ProductionSources.read("ui/sessions/NetworkStatusBadges.kt")
        assertTrue("nothing for an unconfigured app",
            badges.contains("if (settings.forceDirect || !NetworkStatusMonitor.wanted(settings)) return"))
        val hub = ProductionSources.read("network/NetworkHub.kt")
        assertTrue(hub.substringAfter("fun onForeground()").substringBefore("}").contains("NetworkStatusMonitor.onForeground()"))
        assertTrue(hub.substringAfter("fun onBackground()").substringBefore("}").contains("NetworkStatusMonitor.onBackground()"))
        assertEquals(180_000L, NetworkStatusMonitor.PROBE_INTERVAL_MS)
    }
}
