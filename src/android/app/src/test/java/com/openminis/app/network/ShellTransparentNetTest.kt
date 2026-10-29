package com.openminis.app.network

import com.openminis.app.ProductionSources
import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [FEAT-261021-11] The Android side of shell transparent forwarding: when
 * the routes file must not exist (every connection direct), what it says
 * when it does, and the wiring that keeps it in step.
 */
class ShellTransparentNetTest {

    @Test
    fun forwardingIsOffWheneverItHasNothingToDo() {
        val t = NetworkRoute.Tailscale
        val d = NetworkRoute.Direct
        val p = NetworkRoute.Proxy("a")
        assertEquals("Restore Direct Connections", ShellTransparentNet.wanted(true, true, t, true))
        assertEquals("no local entry", ShellTransparentNet.wanted(false, false, t, true))
        // A direct shell with Tailscale off or logged out: nothing to hook.
        assertEquals("shell direct and no usable tailnet", ShellTransparentNet.wanted(false, true, d, false))
        // Otherwise Go decides the prefixes (an empty hook list also turns it off).
        assertNull(ShellTransparentNet.wanted(false, true, d, true))
        assertNull(ShellTransparentNet.wanted(false, true, t, false))
        assertNull(ShellTransparentNet.wanted(false, true, p, false))
    }

    @Test
    fun theRoutesFileIsWhatProotParses() {
        val text = ShellTransparentNet.routesText(41000, 41001, "/data/net/xnet-map",
            listOf("100.64.0.0/10", "fd7a:115c:a1e0::/48"), listOf("10.1.1.0/24"))
        assertEquals(
            "# MinisApp Settings > Network -> proot xnet (FEAT-261021-11)\n" +
                "port4 41000\nport6 41001\nmap /data/net/xnet-map\n" +
                "hook 100.64.0.0/10\nhook fd7a:115c:a1e0::/48\nexempt 10.1.1.0/24\n",
            text,
        )
        assertTrue("no port6 line without a v6 listener",
            !ShellTransparentNet.routesText(1, 0, "/m", listOf("0.0.0.0/0"), emptyList()).contains("port6"))
    }

    /** [FEAT-261021-23] Stage 2: exactly what proot's xnet_parse_routes reads (tests/xnet/test-xnet.c). */
    @Test
    fun theStage2RoutesFileCarriesUdpAndDns() {
        val s2 = ShellTransparentNet.Stage2(udpPort = 41002, socksPort = 41003, user = "shell", password = "pw0",
            udpHook = listOf("100.64.0.0/10"), resolvers = listOf("198.18.0.4"), dns = true, failClosed = false)
        val text = ShellTransparentNet.routesText(41000, 0, "/m", listOf("0.0.0.0/0"), emptyList(), s2)
        assertEquals(
            "# MinisApp Settings > Network -> proot xnet (FEAT-261021-11)\n" +
                "port4 41000\nmap /m\nhook 0.0.0.0/0\n" +
                "udpport 41002\nsocks 41003\nuser shell\npass pw0\nudphook 100.64.0.0/10\nresolver 198.18.0.4\ndns 1\n",
            text,
        )
        assertTrue(ShellTransparentNet.routesText(1, 0, "/m", listOf("0.0.0.0/0"), emptyList(),
            s2.copy(dns = false, failClosed = true)).endsWith("dns 0\nfailclosed 1\n"))
        // Without stage 2 (an older Go library): the stage 1 file, unchanged.
        assertTrue(!ShellTransparentNet.routesText(1, 0, "/m", listOf("0.0.0.0/0"), emptyList()).contains("udpport"))
    }

    @Test
    fun theShellsNameserversComeFromItsResolvConf() {
        assertEquals(listOf("198.18.0.4", "fe80::1", "8.8.8.8"), ShellTransparentNet.parseResolvers(
            "# written by Minis\nnameserver 198.18.0.4\n  nameserver   fe80::1%wlan0 \nsearch lan\nnameserver 8.8.8.8 # fallback\nnameserver 198.18.0.4\n"))
        assertEquals(emptyList<String>(), ShellTransparentNet.parseResolvers("options ndots:1\n"))
    }

    @Test
    fun localNetworksAreMasked() {
        assertEquals("10.1.1.0/24", ShellTransparentNet.maskedPrefix(InetAddress.getByName("10.1.1.37"), 24))
        assertEquals("100.64.0.0/10", ShellTransparentNet.maskedPrefix(InetAddress.getByName("100.104.204.22"), 10))
        // Java writes IPv6 uncompressed; Go and proot (inet_pton) both accept it.
        assertEquals("fda1:4cdc:be6e:0:0:0:0:0/64", ShellTransparentNet.maskedPrefix(InetAddress.getByName("fda1:4cdc:be6e::1234"), 64))
        assertNull(ShellTransparentNet.maskedPrefix(InetAddress.getByName("10.1.1.37"), 0))
        assertNull(ShellTransparentNet.maskedPrefix(InetAddress.getByName("10.1.1.37"), 33))
    }

    @Test
    fun theFileFollowsTheNetworkSettingsAndProotGetsItOnlyIfItKnowsTheOption() {
        val hub = ProductionSources.read("network/NetworkHub.kt")
        assertTrue(hub.contains("ShellTransparentNet.init(appContext)"))
        assertTrue(hub.contains("ShellTransparentNet.sync(\"apply\")"))
        assertTrue(hub.contains("ShellTransparentNet.sync(\"health\")"))
        val net = ProductionSources.read("network/ShellTransparentNet.kt")
        // Off deletes the file BEFORE stopping the listener, and init drops a stale one.
        val off = net.substringAfter("private fun off(").substringBefore("\n    }\n")
        assertTrue(off.indexOf("routesFile.delete()") < off.indexOf("TailscaleNode.xparentStop()"))
        assertTrue(net.substringAfter("fun init(").substringBefore("NetworkRuntime.addListener").contains("routesFile.delete()"))
        // Every shell builder passes it, and only to a proot that knows it.
        for (f in listOf("sandbox/PRootKernel.kt", "sandbox/FreshProcessShell.kt", "sandbox/TerminalSession.kt", "sandbox/PersistentShell.kt")) {
            assertTrue(f, ProductionSources.read(f).contains("cmd.addAll(com.openminis.app.network.ShellTransparentNet.prootArgs(rootfsManager.prootBinary))"))
        }
        val args = net.substringAfter("fun prootArgs(").substringBefore("\n    }\n")
        assertTrue(args.contains("prootSupportsXnet(proot)"))
        // With forwarding off the proot command is unchanged.
        assertTrue(args.indexOf("if (!File(routes).exists()) return emptyList()") in 0 until args.indexOf("prootSupportsXnet(proot)"))
    }
}
