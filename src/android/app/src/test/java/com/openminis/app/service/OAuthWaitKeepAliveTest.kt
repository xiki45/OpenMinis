package com.openminis.app.service

import com.openminis.app.ProductionSources
import com.openminis.app.auth.OAuthCallbackServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.ServerSocket
import java.net.Socket

/**
 * [T-oauth-wait-fgs] While an OAuth sign-in waits for its localhost redirect,
 * a dataSync foreground service keeps Minis out of the cached-apps freezer
 * (field log: the redirect hung 39 s, served the millisecond the process
 * thawed). Bookkeeping, the callback-server wiring, and source guards for the
 * compatibility rules the service must follow.
 */
class OAuthWaitKeepAliveTest {

    // ── Flow bookkeeping ────────────────────────────────────────────────────

    @Test
    fun `flows are counted by id, and ending one never ends another`() {
        val f = OAuthWaitFlows(maxHoldMs = 1_000)
        assertEquals(1, f.add("a", "claude", now = 0))
        assertEquals(2, f.add("b", "mcp", now = 0))
        assertTrue(f.remove("a"))
        assertFalse("a second end of the same flow is a no-op", f.remove("a"))
        assertEquals(1, f.count)
        assertTrue(f.remove("b"))
        assertEquals(0, f.count)
        assertNull(f.nextDeadline())
    }

    @Test
    fun `a flow that never ends is released at its deadline`() {
        val f = OAuthWaitFlows(maxHoldMs = 1_000)
        f.add("old", "openai", now = 0)
        f.add("new", "xai", now = 600)
        assertEquals(1_000L, f.nextDeadline())
        assertEquals(emptyList<String>(), f.prune(now = 999))
        assertEquals(listOf("openai"), f.prune(now = 1_000))
        assertEquals(1, f.count)
        assertEquals(1_600L, f.nextDeadline())
        assertEquals(1, f.clear())
        assertEquals(0, f.count)
    }

    @Test
    fun `the keep-alive holds for 5 minutes, the token exchange's own wait`() {
        assertEquals(5 * 60_000L, OAuthWaitKeepAlive.MAX_HOLD_MS)
        assertEquals(com.openminis.app.auth.OAuthForegroundGate.MAX_WAIT_MS, OAuthWaitKeepAlive.MAX_HOLD_MS)
    }

    @Test
    fun `begin and end are balanced even out of order and repeated`() {
        val base = OAuthWaitKeepAlive.active.value
        val a = OAuthWaitKeepAlive.begin("test-a")
        val b = OAuthWaitKeepAlive.begin("test-b")
        assertEquals(base + 2, OAuthWaitKeepAlive.active.value)
        OAuthWaitKeepAlive.end(a, "test")
        OAuthWaitKeepAlive.end(a, "test")
        assertEquals("b is still protected", base + 1, OAuthWaitKeepAlive.active.value)
        OAuthWaitKeepAlive.end(b, "test")
        assertEquals(base, OAuthWaitKeepAlive.active.value)
    }

    // ── Callback server wiring ──────────────────────────────────────────────

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private fun get(port: Int, path: String): String {
        val deadline = System.currentTimeMillis() + 3_000
        while (true) {
            try {
                Socket("127.0.0.1", port).use { s ->
                    s.soTimeout = 3_000
                    s.getOutputStream().write("GET $path HTTP/1.1\r\nHost: localhost\r\n\r\n".toByteArray())
                    return s.getInputStream().readBytes().toString(Charsets.UTF_8)
                }
            } catch (e: java.net.ConnectException) {
                if (System.currentTimeMillis() > deadline) throw e
                Thread.sleep(20)
            }
        }
    }

    private fun awaitActive(expected: Int) {
        val deadline = System.currentTimeMillis() + 3_000
        while (OAuthWaitKeepAlive.active.value != expected && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertEquals(expected, OAuthWaitKeepAlive.active.value)
    }

    private fun server(port: Int) = OAuthCallbackServer(port, onError = {}) { _, _ -> }

    @Test
    fun `a server holds the keep-alive from start until the callback, and stop after it is a no-op`() {
        val base = OAuthWaitKeepAlive.active.value
        val port = freePort()
        val s = server(port)
        s.start()
        assertEquals(base + 1, OAuthWaitKeepAlive.active.value)
        assertTrue(get(port, "/callback?code=abc&state=x").startsWith("HTTP/1.1 200"))
        awaitActive(base)
        s.stop()
        s.stop()
        assertEquals(base, OAuthWaitKeepAlive.active.value)
    }

    @Test
    fun `error callback, external stop and a failed bind each release it`() {
        val base = OAuthWaitKeepAlive.active.value
        val p1 = freePort()
        val denied = server(p1).also { it.start() }
        assertTrue(get(p1, "/callback?error=access_denied").startsWith("HTTP/1.1 400"))
        awaitActive(base)
        denied.stop()

        val p2 = freePort()
        val cancelled = server(p2).also { it.start() }
        get(p2, "/favicon.ico")
        assertEquals(base + 1, OAuthWaitKeepAlive.active.value)
        cancelled.stop()
        assertEquals(base, OAuthWaitKeepAlive.active.value)

        ServerSocket(0).use { taken ->
            val s = server(taken.localPort).also { it.start() }
            awaitActive(base)
            s.stop()
        }
        assertEquals(base, OAuthWaitKeepAlive.active.value)
    }

    @Test
    fun `two flows at once - one ending leaves the other protected`() {
        val base = OAuthWaitKeepAlive.active.value
        val pa = freePort()
        val pb = freePort()
        val a = server(pa).also { it.start() }
        val b = server(pb).also { it.start() }
        get(pb, "/favicon.ico")
        assertEquals(base + 2, OAuthWaitKeepAlive.active.value)
        a.stop()
        assertEquals(base + 1, OAuthWaitKeepAlive.active.value)
        assertTrue(get(pb, "/callback?code=x").startsWith("HTTP/1.1 200"))
        awaitActive(base)
        b.stop()
        assertEquals(base, OAuthWaitKeepAlive.active.value)
    }

    @Test
    fun `restarting a server does not leak its previous entry`() {
        val base = OAuthWaitKeepAlive.active.value
        val port = freePort()
        val s = server(port)
        s.start()
        s.stop()
        s.start()
        assertEquals(base + 1, OAuthWaitKeepAlive.active.value)
        s.stop()
        assertEquals(base, OAuthWaitKeepAlive.active.value)
    }

    // ── Compatibility guards ────────────────────────────────────────────────

    private val svc = ProductionSources.read("service/OAuthWaitService.kt")

    @Test
    fun `startForeground goes through ServiceCompat with the dataSync type, first thing`() {
        assertTrue(svc.contains("ServiceCompat.startForeground("))
        assertTrue(svc.contains("ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC"))
        assertFalse("shortService is 34+ only and capped at 3 minutes", svc.contains("SHORT_SERVICE"))
        val onStart = svc.substringAfter("override fun onStartCommand(").substringBefore("private fun observe()")
        assertTrue("foreground before any other work", onStart.indexOf("goForeground()") < onStart.indexOf("OAuthWaitKeepAlive.prune()"))
        assertTrue("a start after the flows ended still stops", onStart.contains("if (n == 0) {") && onStart.contains("stopNow(\"no-active-flows\")"))
        assertTrue(onStart.contains("return START_NOT_STICKY"))
        assertFalse(svc.contains("START_STICKY\n") || svc.contains("START_REDELIVER_INTENT"))
        assertTrue("the channel exists before the notification", svc.contains("ensureChannel(this)"))
    }

    @Test
    fun `a refused start never breaks the sign-in`() {
        val start = svc.substringAfter("fun start(context: Context) {").substringBefore("fun ensureChannel(")
        assertTrue(start.contains("ContextCompat.startForegroundService("))
        assertTrue(start.contains("} catch (e: Exception) {"))
        assertTrue(start.contains("ForegroundServiceStartNotAllowedException"))
        assertTrue(start.contains("sign-in continues without it"))
        val fg = svc.substringAfter("private fun goForeground(): Boolean {").substringBefore("private fun stopNow(")
        assertTrue(fg.contains("} catch (e: Exception) {") && fg.contains("false"))
        val keep = ProductionSources.read("service/OAuthWaitKeepAlive.kt")
        assertTrue(keep.contains("OAuthWaitService.start(ctx)"))
    }

    @Test
    fun `the service stops itself, handles the dataSync timeout and the task being removed`() {
        assertTrue(svc.contains("override fun onTimeout(startId: Int, fgsType: Int) {"))
        val timeout = svc.substringAfter("override fun onTimeout(startId: Int, fgsType: Int) {").substringBefore("}")
        assertTrue(timeout.contains("stopSelf()"))
        assertTrue(svc.contains("override fun onTimeout(startId: Int) = onTimeout(startId, 0)"))
        assertTrue(svc.contains("stopSelfResult(lastStartId)"))
        assertFalse("stopService between startForegroundService and startForeground crashes", svc.contains("stopService("))
        assertTrue(svc.contains("override fun onTaskRemoved("))
        assertFalse("never asks for POST_NOTIFICATIONS", svc.contains("requestPermission") || svc.contains("RequestPermission"))
    }

    @Test
    fun `manifest declares the service unexported with the dataSync type and permission`() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        val decl = manifest.substringAfter("android:name=\".service.OAuthWaitService\"").substringBefore("/>")
        assertTrue(decl.contains("android:exported=\"false\""))
        assertTrue(decl.contains("android:foregroundServiceType=\"dataSync\""))
        assertTrue(manifest.contains("android.permission.FOREGROUND_SERVICE_DATA_SYNC"))
    }

    @Test
    fun `every exit of the callback server releases it, and the app installs it`() {
        val src = ProductionSources.read("auth/OAuthCallbackServer.kt")
        for (r in listOf("bind-failed", "callback", "error-callback", "server-exit", "stopped")) {
            assertTrue(r, src.contains("releaseKeepAlive(\"$r\""))
        }
        assertTrue(src.contains("OAuthWaitKeepAlive.begin(\"oauth-callback port=\$port\")"))
        assertTrue(ProductionSources.read("MinisApp.kt").contains("com.openminis.app.service.OAuthWaitKeepAlive.install(this)"))
    }
}
