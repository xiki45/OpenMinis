package com.openminis.app.network

import com.openminis.app.ProductionSources
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [FEAT-261021-10] A Shell route change reaches shells that are already
 * running: the new block is exported in place (no restart), and keys the
 * module set before but no longer does are cleared - only those.
 */
class ShellRouteBroadcastTest {

    @Before fun reset() = NetworkShellEnvironment.resetForTest()

    @After fun after() = NetworkShellEnvironment.resetForTest()

    private val routed = ShellProxyVariables.make("socks5h://shell:pw@127.0.0.1:1", "http://shell:pw@127.0.0.1:2", routeIsDirect = false)

    @Test
    fun onlyAChangeIsBroadcastAndOnlyTheModulesOwnKeysAreCleared() {
        assertNull("the first publish is the baseline", NetworkShellEnvironment.changedSinceLastBroadcast(emptyMap()))
        assertNull("unchanged", NetworkShellEnvironment.changedSinceLastBroadcast(emptyMap()))
        assertEquals("routed: nothing to clear", emptySet<String>(), NetworkShellEnvironment.changedSinceLastBroadcast(routed))
        assertNull(NetworkShellEnvironment.changedSinceLastBroadcast(routed))
        // Back to direct: every key it had set is cleared, ALL_PROXY included.
        assertEquals(routed.keys, NetworkShellEnvironment.changedSinceLastBroadcast(emptyMap()))
        // Entry restarted on a new port: a change, nothing to clear.
        NetworkShellEnvironment.changedSinceLastBroadcast(routed)
        val moved = ShellProxyVariables.make("socks5h://shell:pw@127.0.0.1:3", "http://shell:pw@127.0.0.1:4", routeIsDirect = false)
        assertEquals(emptySet<String>(), NetworkShellEnvironment.changedSinceLastBroadcast(moved))
    }

    @Test
    fun theHubPushesChangesIntoRunningShells() {
        val hub = ProductionSources.read("network/NetworkHub.kt")
        assertTrue(hub.contains("NetworkShellEnvironment.watchRuntime { cleared ->"))
        assertTrue(hub.contains("ExecutionCoordinator.broadcastProxyChange(cleared)"))
        val coord = ProductionSources.read("sandbox/ExecutionCoordinator.kt")
        val body = coord.substringAfter("suspend fun broadcastProxyChange(cleared: Set<String> = emptySet())").substringBefore("\n    }\n")
        // The cleared keys first, then the system block, then the route's own
        // variables (which win): a key the route stopped setting falls back to
        // the system proxy instead of being blanked over it (FIX-261021-10).
        val clr = body.indexOf("cleared.associateWith { \"\" }")
        val sys = body.indexOf("PRootKernel.updateProxy(appContext)")
        val net = body.indexOf("NetworkShellEnvironment.proxyVariables()")
        assertTrue(clr in 0 until sys && sys < net)
        assertTrue(body.contains("shell.applyEnvironment(env)") && body.contains("TerminalSession.broadcastProxy(env)"))
    }
}
