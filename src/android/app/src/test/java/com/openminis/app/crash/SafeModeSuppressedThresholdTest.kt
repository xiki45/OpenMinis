package com.openminis.app.crash

import android.app.Application
import android.content.Context
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * [T-safe-mode-suppressed-threshold] After "Not now" the 24 h suppress window
 * raises the safe-mode threshold from 2 to 5 crashes in the last hour instead
 * of switching detection off (which let a crash-on-launch loop run with no
 * safe mode and no exit).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class SafeModeSuppressedThresholdTest {

    private val hour = 60L * 60L * 1000L
    private lateinit var app: Application
    private lateinit var logs: File

    @Before
    fun setUp() {
        app = RuntimeEnvironment.getApplication()
        logs = File(app.filesDir, "logs").also { it.deleteRecursively(); it.mkdirs() }
        prefs().edit().clear().commit()
        resetSafeMode()
    }

    @After
    fun tearDown() {
        resetSafeMode()
        logs.deleteRecursively()
    }

    private fun prefs() = app.getSharedPreferences("crash_freq_prefs", Context.MODE_PRIVATE)

    private fun resetSafeMode() {
        val f = CrashFrequencyDetector::class.java.getDeclaredField("_safeMode").apply { isAccessible = true }
        (f.get(CrashFrequencyDetector) as java.util.concurrent.atomic.AtomicBoolean).set(false)
        val p = CrashFrequencyDetector::class.java.getDeclaredField("pendingShareFiles").apply { isAccessible = true }
        p.set(CrashFrequencyDetector, null)
    }

    /** [n] crash files from the last few minutes, one per crash, alternating both sources. */
    private fun crashes(n: Int) {
        val now = System.currentTimeMillis()
        repeat(n) { i ->
            val name = if (i % 2 == 0) "crash-test-$i.log" else "native-crash-test-$i.log"
            File(logs, name).apply { writeText("x"); setLastModified(now - (i + 1) * 60_000L) }
        }
    }

    private fun suppressFor(ms: Long) {
        prefs().edit().putLong("suppress_until", System.currentTimeMillis() + ms).commit()
    }

    @Test
    fun `threshold is 5 only inside the suppress window`() {
        val now = 1_000_000_000L
        assertEquals("never dismissed", 2, CrashFrequencyDetector.thresholdFor(now, 0L))
        assertEquals("inside the window", 5, CrashFrequencyDetector.thresholdFor(now, now + 1))
        assertEquals("window just expired", 2, CrashFrequencyDetector.thresholdFor(now, now))
        assertEquals("long expired", 2, CrashFrequencyDetector.thresholdFor(now, now - 24 * hour))
        for (r in 1..4) assertFalse("recent=$r inside", CrashFrequencyDetector.tripsSafeMode(r, now, now + hour))
        assertTrue(CrashFrequencyDetector.tripsSafeMode(5, now, now + hour))
        assertFalse(CrashFrequencyDetector.tripsSafeMode(1, now, 0L))
        assertTrue("outside: 2 still trips", CrashFrequencyDetector.tripsSafeMode(2, now, 0L))
        assertTrue("expired: back to 2", CrashFrequencyDetector.tripsSafeMode(2, now, now - 1))
    }

    @Test
    fun `inside the window 1 to 4 recent crashes stay silent`() {
        for (n in 1..4) {
            setUp()
            suppressFor(20 * hour)
            crashes(n)
            CrashFrequencyDetector.checkAtLaunch(app)
            assertFalse("recent=$n: no safe mode", CrashFrequencyDetector.isSafeMode())
            assertNull("recent=$n: no dialog", CrashFrequencyDetector.pendingShareFiles)
        }
    }

    @Test
    fun `inside the window 5 recent crashes enter safe mode with the dialog as exit`() {
        suppressFor(20 * hour)
        crashes(5)
        CrashFrequencyDetector.checkAtLaunch(app)
        assertTrue(CrashFrequencyDetector.isSafeMode())
        assertEquals("the dialog is not swallowed by the window", 5, CrashFrequencyDetector.pendingShareFiles?.size)
        assertTrue(CrashFrequencyDetector.shouldForceHomeOnLaunch(app))
    }

    @Test
    fun `outside the window 2 recent crashes still trip, unchanged`() {
        crashes(1)
        CrashFrequencyDetector.checkAtLaunch(app)
        assertFalse(CrashFrequencyDetector.isSafeMode())
        setUp()
        crashes(2)
        CrashFrequencyDetector.checkAtLaunch(app)
        assertTrue(CrashFrequencyDetector.isSafeMode())
        assertNotNull(CrashFrequencyDetector.pendingShareFiles)
    }

    @Test
    fun `an expired window falls back to the normal threshold`() {
        prefs().edit().putLong("suppress_until", System.currentTimeMillis() - 1_000L).commit()
        crashes(2)
        CrashFrequencyDetector.checkAtLaunch(app)
        assertTrue(CrashFrequencyDetector.isSafeMode())
    }

    @Test
    fun `archived crash logs in a subdirectory do not count`() {
        suppressFor(20 * hour)
        val archive = File(logs, "repro-2026-10-04").also { it.mkdirs() }
        repeat(8) { File(archive, "crash-old-$it.log").writeText("x") }
        crashes(4)
        CrashFrequencyDetector.checkAtLaunch(app)
        assertFalse(CrashFrequencyDetector.isSafeMode())
    }
}
