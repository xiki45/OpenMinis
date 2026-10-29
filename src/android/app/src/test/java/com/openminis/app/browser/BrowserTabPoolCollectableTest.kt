package com.openminis.app.browser

import com.openminis.app.ProductionSources
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.lang.ref.WeakReference

/**
 * [GH #453] A BrowserTabPool nobody references is collected. Its idle-eviction
 * loop used to capture the pool, and a coroutine parked in delay() on
 * Dispatchers.Default is reachable from the dispatcher, so every pool - one per
 * chat opened, one per sessions-list visit - lived for the life of the process
 * with whatever it referenced (a chat's ChatViewModel via onDownloadEvent).
 */
@RunWith(RobolectricTestRunner::class)
class BrowserTabPoolCollectableTest {

    private fun newUnreferencedPool(): WeakReference<BrowserTabPool> =
        WeakReference(BrowserTabPool(RuntimeEnvironment.getApplication()))

    @Test fun `an unreferenced pool is garbage collected while its eviction loop waits`() {
        val refs = List(3) { newUnreferencedPool() }
        repeat(50) {
            if (refs.all { it.get() == null }) return@repeat
            System.gc()
            System.runFinalization()
            Thread.sleep(20)
        }
        refs.forEach { assertNull("pool still reachable", it.get()) }
    }

    @Test fun `the loop holds its pool weakly`() {
        val src = ProductionSources.read("browser/BrowserTabPool.kt")
        assertTrue(src.contains("evictionJob = startEvictionLoop(evictionScope, java.lang.ref.WeakReference(this))"))
        val loop = src.substringAfter("internal fun startEvictionLoop(").substringBefore("\n\n")
        assertTrue(loop.contains("pool.get()?.evictIdleTabs() != null"))
        assertFalse("no capture of the pool in the loop", loop.contains("this@BrowserTabPool"))
    }
}
