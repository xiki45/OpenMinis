package com.openminis.app.backup.remote

import com.openminis.app.ProductionSources
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * [T-rclone-rpc-concurrency-diag] The concurrency diagnostic around rclone's
 * native RPC: it counts and logs, and it never makes one call wait for another
 * — above all, a `job/stop` must get through while some other RPC is stuck.
 *
 * The native call is replaced by a block the test controls; nothing here
 * touches libgojni.
 */
class RcloneRpcTrackerTest {

    private val lines: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private lateinit var savedLog: (String) -> Unit

    @Before
    fun setUp() {
        savedLog = RcloneRpcTracker.log
        RcloneRpcTracker.log = { lines += it }
        assertEquals("tracker must start idle", 0, RcloneRpcTracker.inFlightCount)
    }

    @After
    fun tearDown() {
        RcloneRpcTracker.log = savedLog
    }

    @Test
    fun `a lone call is counted while it runs and logs nothing`() {
        val seen = RcloneRpcTracker.track("core/version") { RcloneRpcTracker.inFlightCount }
        assertEquals(1, seen)
        assertEquals(0, RcloneRpcTracker.inFlightCount)
        assertTrue("no noise for the normal single-caller path", lines.isEmpty())
    }

    @Test
    fun `a call that throws is still unregistered`() {
        runCatching { RcloneRpcTracker.track<Unit>("operations/list") { error("boom") } }
        assertEquals(0, RcloneRpcTracker.inFlightCount)
    }

    @Test
    fun `job stop gets through while another RPC is stuck - nothing is serialised`() {
        val stuckEntered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val stuck = Thread {
            RcloneRpcTracker.track("operations/list") {
                stuckEntered.countDown()
                release.await(30, TimeUnit.SECONDS) // stands in for a 300 s stalled server
            }
        }.apply { start() }
        assertTrue(stuckEntered.await(5, TimeUnit.SECONDS))

        val t0 = System.nanoTime()
        val result = RcloneRpcTracker.track("job/stop") { "stopped" }
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertEquals("stopped", result)
        assertTrue("job/stop must not wait for the stuck list (took ${ms}ms)", ms < 1_000)
        assertTrue("the stuck call is still in flight", stuck.isAlive)

        release.countDown()
        stuck.join(5_000)
        assertEquals(0, RcloneRpcTracker.inFlightCount)
    }

    @Test
    fun `concurrency is logged with method names and age, never params`() {
        val firstEntered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val first = Thread {
            RcloneRpcTracker.track("operations/copyfile") {
                firstEntered.countDown()
                release.await(30, TimeUnit.SECONDS)
            }
        }.apply { start() }
        assertTrue(firstEntered.await(5, TimeUnit.SECONDS))

        RcloneRpcTracker.track("core/obscure") { RcloneRpcTracker.inFlightCount }
        release.countDown()
        first.join(5_000)

        val enter = lines.single { it.contains("concurrent RPC enter") }
        assertTrue(enter, enter.contains("core/obscure with 2 in flight"))
        assertTrue(enter, enter.contains("operations/copyfile"))
        assertTrue(enter, Regex("""operations/copyfile \d+ms""").containsMatchIn(enter))
        assertTrue(lines.any { it.startsWith("[Rclone] concurrent RPC exit: core/obscure after ") })
        // The FIRST call saw nobody else when it entered, so it stays quiet.
        assertFalse(lines.any { it.contains("exit: operations/copyfile") })
        assertEquals(0, RcloneRpcTracker.inFlightCount)
    }

    @Test
    fun `nested tracking on one thread neither deadlocks nor leaks`() {
        val inner = RcloneRpcTracker.track("options/set") {
            RcloneRpcTracker.track("core/version") { RcloneRpcTracker.inFlightCount }
        }
        assertEquals(2, inner)
        assertEquals(0, RcloneRpcTracker.inFlightCount)
    }

    // ── Source guards on RcloneBridge ──────────────────────────────────────

    private val bridge = ProductionSources.read("backup/remote/RcloneBridge.kt")

    @Test
    fun `both native entry points go through the tracker`() {
        val native = Regex("""Gomobile\.rcloneRPC\(method, input\)""").findAll(bridge).count()
        val tracked = Regex("""RcloneRpcTracker\.track\(method\) \{ Gomobile\.rcloneRPC\(method, input\) \}""")
            .findAll(bridge).count()
        assertEquals(2, native)
        assertEquals("rpc() and rpcUnchecked() are both tracked", 2, tracked)
    }

    @Test
    fun `no lock, mutex or semaphore guards the RPC path`() {
        val body = bridge.substringAfter("private fun rpcUnchecked(").substringBefore("private fun toJsonValue(")
        for (forbidden in listOf("synchronized", "@Synchronized", "Mutex", "Semaphore", "ReentrantLock", "tryLock")) {
            assertFalse("$forbidden would let a stalled call hold back job/stop", body.contains(forbidden))
        }
        val tracker = ProductionSources.read("backup/remote/RcloneRpcTracker.kt")
        for (forbidden in listOf("synchronized", "Mutex", "Semaphore", "ReentrantLock", "tryLock")) {
            assertFalse(tracker.substringAfter("internal object RcloneRpcTracker").contains(forbidden))
        }
    }
}
