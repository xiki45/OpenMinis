package com.openminis.app.network

import com.openminis.app.ProductionSources
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [FEAT-261021-05] Port of iOS e720112cf: the "[Net][Alert]" / "[Net][Recovered]"
 * lines from nethub/exitalert.go become a banner for a proxy or Tailscale that
 * keeps failing, with the same rules as iOS NetworkAlertCenter.
 */
class NetworkAlertCenterTest {

    @Before fun reset() = NetworkAlertCenter.resetForTest()

    @After fun after() = NetworkAlertCenter.resetForTest()

    private val office = ProxyServer(id = "6F1C-22", name = "Office", host = "10.0.0.2", port = 8080)
    private val settings = NetworkSettings(tailscaleEnabled = true, proxies = listOf(office), defaultRoute = "proxy:6F1C-22")

    private val proxyAlert = "[Net][Alert] exit=proxy id=6F1C-22 failures=3 stage=proxy-connect class=refused " +
        "name=Office_proxy err=dial tcp 10.0.0.2:8080: connect: connection refused"

    @Test
    fun parsesTheExactGoLines() {
        assertEquals(
            NetworkAlertEvent(NetworkAlertEvent.Subject.Proxy("6F1C-22"), false, 3, "proxy-connect", "refused",
                "dial tcp 10.0.0.2:8080: connect: connection refused"),
            NetworkAlertEvent.parse(proxyAlert),
        )
        // err= is the rest of the line, even with "=" inside it.
        assertEquals("proxy CONNECT a=b:443: 407 x=y", NetworkAlertEvent.parse(
            "[Net][Alert] exit=proxy id=p failures=4 stage=proxy-handshake class=proxy-auth err=proxy CONNECT a=b:443: 407 x=y")?.detail)
        assertEquals(NetworkAlertEvent(NetworkAlertEvent.Subject.Proxy("6F1C-22"), recovered = true),
            NetworkAlertEvent.parse("[Net][Recovered] exit=proxy id=6F1C-22"))
        assertEquals(NetworkAlertEvent.Subject.Tailscale, NetworkAlertEvent.parse("[Net][Recovered] exit=tailscale")?.subject)
        assertNull(NetworkAlertEvent.parse("[Net][Proxy] c0001 FAIL stage=proxy-connect via=proxy:x"))
        assertNull(NetworkAlertEvent.parse("[Net][Alert] exit=direct failures=3"))
        assertNull(NetworkAlertEvent.parse("[Net][Alert] exit=proxy failures=3 err=x"))
        // The Go side still writes these shapes.
        val go = generateSequence(ProductionSources.mainRoot()) { it.parentFile }
            .map { File(it, "deps/rclone-mobile/nethub/exitalert.go") }.firstOrNull { it.exists() }
        val src = go!!.readText()
        assertTrue(src.contains("\"[Net][Alert] %s failures=%d stage=%s class=%s%s err=%s\""))
        assertTrue(src.contains("\"[Net][Recovered] %s\""))
    }

    @Test
    fun aFailingProxyInUseShowsTheBannerUntilItRecovers() {
        val e = NetworkAlertEvent.parse(proxyAlert)!!
        NetworkAlertCenter.handle(e, settings)
        assertEquals(e, NetworkAlertCenter.banner.value)
        assertEquals(e, NetworkAlertCenter.failing.value[e.subject])
        NetworkAlertCenter.handle(NetworkAlertEvent.parse("[Net][Recovered] exit=proxy id=6F1C-22")!!, settings)
        assertNull(NetworkAlertCenter.banner.value)
        assertTrue(NetworkAlertCenter.failing.value.isEmpty())
    }

    @Test
    fun noBannerOfflineForAnUnusedSubjectOrWhileSnoozed() {
        val e = NetworkAlertEvent.parse(proxyAlert)!!
        NetworkAlertCenter.isOnline = { false }
        NetworkAlertCenter.handle(e, settings)
        assertNull("offline: the network is the problem", NetworkAlertCenter.banner.value)
        assertEquals("but the page still knows", e, NetworkAlertCenter.failing.value[e.subject])

        NetworkAlertCenter.resetForTest()
        NetworkAlertCenter.handle(e, settings.copy(proxies = emptyList()))
        assertNull("a deleted proxy", NetworkAlertCenter.banner.value)
        NetworkAlertCenter.handle(e, settings.copy(forceDirect = true))
        assertNull("routing off", NetworkAlertCenter.banner.value)
        val ts = NetworkAlertEvent.parse("[Net][Alert] exit=tailscale failures=3 stage=tailnet class=timeout err=x")!!
        NetworkAlertCenter.handle(ts, settings.copy(tailscaleEnabled = false))
        assertNull("Tailscale off", NetworkAlertCenter.banner.value)

        var t = 1_000L
        NetworkAlertCenter.now = { t }
        NetworkAlertCenter.handle(e, settings)
        NetworkAlertCenter.dismissBanner()
        NetworkAlertCenter.handle(e, settings)
        assertNull("snoozed", NetworkAlertCenter.banner.value)
        t += NetworkAlertCenter.SNOOZE_MS + 1
        NetworkAlertCenter.handle(e, settings)
        assertEquals("snooze over", e, NetworkAlertCenter.banner.value)
        // A tap takes the banner away; the caller opens the setting.
        assertEquals(e, NetworkAlertCenter.takeBanner())
        assertNull(NetworkAlertCenter.banner.value)
    }

    @Test
    fun aSettingsChangeThatDropsTheSubjectClearsIt() {
        val e = NetworkAlertEvent.parse(proxyAlert)!!
        NetworkAlertCenter.handle(e, settings)
        NetworkAlertCenter.prune(settings.withoutProxy("6F1C-22"))
        assertNull(NetworkAlertCenter.banner.value)
        assertTrue(NetworkAlertCenter.failing.value.isEmpty())
    }

    @Test
    fun stopUsingSendsEveryRouteThatNamedTheProxyBackButKeepsIt() {
        val s = settings.copy(
            scopes = mapOf("shell" to "proxy:6F1C-22", "mcp" to "tailscale"),
            providerRoutes = mapOf("p1" to "proxy:6F1C-22", "p2" to "direct"),
        ).stopUsing("6F1C-22")
        assertEquals("direct", s.defaultRoute)
        assertEquals(mapOf("shell" to "default", "mcp" to "tailscale"), s.scopes)
        assertEquals(mapOf("p2" to "direct"), s.providerRoutes)
        assertEquals(listOf(office), s.proxies)
    }

    @Test
    fun theBannerIsWiredFromTheGoLogToEveryScreen() {
        val node = ProductionSources.read("network/TailscaleNode.kt")
        assertTrue(node.contains("NetworkAlertCenter.ingest(line)"))
        // The sink is installed for the entry too, not only for the node.
        assertTrue(node.substringAfter("fun configureEntry(").substringBefore("entryTouched = true").contains("installLogger()"))
        assertTrue(ProductionSources.read("MainActivity.kt").contains("NetworkAlertBannerHost(navController)"))
        val screen = ProductionSources.read("ui/settings/NetworkSettingsScreen.kt")
        assertTrue(screen.contains("NetworkHub.stopUsingProxy(proxy.id)"))
        assertTrue(screen.contains("R.string.network_alert_keeps_failing"))
        assertTrue(ProductionSources.read("deeplink/DeepLinkHandler.kt").contains("NetworkHub.pendingProxyEditor.value = it"))
    }
}
