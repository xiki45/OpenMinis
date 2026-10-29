package com.openminis.app.service

import android.app.ForegroundServiceStartNotAllowedException
import android.content.ComponentName
import android.content.ContextWrapper
import android.content.Intent
import com.openminis.app.ProductionSources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-fgs-start-not-allowed-guard] A foreground-service start the system
 * refuses (Android 12+, background, no exemption) must not crash the process:
 * it crashed twice on the Pixel 4a from SessionActivityTracker.setActive, and
 * two crashes put the app in safe mode.
 */
class FgsStartGuardTest {

    /** A Context whose service starts behave like the system's. */
    private class FakeContext(private val refuse: Boolean) : ContextWrapper(null) {
        var calls = 0
        private fun answer(): ComponentName? {
            calls++
            if (refuse) {
                throw ForegroundServiceStartNotAllowedException(
                    "startForegroundService() not allowed due to mAllowStartForeground false",
                )
            }
            return null
        }
        override fun startForegroundService(service: Intent?): ComponentName? = answer()
        override fun startService(service: Intent?): ComponentName? = answer()
        override fun getPackageName(): String = "com.openminis.app"
    }

    @Test
    fun `a refused start is caught and reported, not thrown`() {
        val ctx = FakeContext(refuse = true)
        val started = AgentForegroundService.startService(ctx, 1, "Working")
        assertFalse(started)
        assertEquals(1, ctx.calls)
    }

    @Test
    fun `an accepted start reports true`() {
        val ctx = FakeContext(refuse = false)
        assertTrue(AgentForegroundService.startService(ctx, 1, "Working"))
        assertEquals(1, ctx.calls)
    }

    @Test
    fun `the exception the device threw is what the guard catches`() {
        // ForegroundServiceStartNotAllowedException -> ServiceStartNotAllowedException
        // -> IllegalStateException: one catch covers it on every API level.
        assertTrue(IllegalStateException::class.java.isAssignableFrom(ForegroundServiceStartNotAllowedException::class.java))
    }

    @Test
    fun `every agent service start goes through the guarded call`() {
        val svc = ProductionSources.read("service/AgentForegroundService.kt")
        val start = svc.substringAfter("fun startService(context: Context, sessionCount: Int, toolStatus: String): Boolean {")
            .substringBefore("\n        }\n")
        assertTrue(start.contains("return try {"))
        assertTrue(start.contains("} catch (e: IllegalStateException) {"))
        assertTrue(start.contains("context.startForegroundService(intent)"))
        // Nothing else in the app calls the raw start for this service.
        val raw = ProductionSources.allKotlinFiles().filter { f ->
            val t = f.readText()
            t.contains("Intent(") && t.contains("AgentForegroundService::class.java") &&
                (t.contains("startForegroundService(") && !f.name.endsWith("AgentForegroundService.kt"))
        }
        assertTrue("raw starts outside the guard: ${raw.map { it.name }}", raw.isEmpty())
    }

    @Test
    fun `the tracker records a refusal and retries on foreground`() {
        val tracker = ProductionSources.read("service/SessionActivityTracker.kt")
        assertEquals(
            "both start paths dispatch through dispatchStart",
            2, Regex("serviceWanted = true\\s+dispatchStart\\(context\\)").findAll(tracker).count(),
        )
        assertFalse(tracker.contains("AgentForegroundService.startService(\n                context,\n                sessionCountForNotification(),\n                statusForNotification(),\n            )\n        }\n    }\n\n    private fun updateService"))
        val dispatch = tracker.substringAfter("private fun dispatchStart(context: Context) {").substringBefore("\n    }\n")
        assertTrue(dispatch.contains("startRefused = !started"))
        assertTrue(dispatch.contains("if (!started) serviceWanted = false"))
        val retry = tracker.substringAfter("fun onAppForegrounded() {").substringBefore("\n    }\n")
        assertTrue(retry.contains("if (shouldRunService() && !AgentForegroundService.isAlive)"))
        assertTrue(retry.contains("startServiceIfNeeded()"))
        val app = ProductionSources.read("MinisApp.kt")
        assertTrue(app.contains("if (wasBackgrounded) SessionActivityTracker.onAppForegrounded()"))
    }
}
