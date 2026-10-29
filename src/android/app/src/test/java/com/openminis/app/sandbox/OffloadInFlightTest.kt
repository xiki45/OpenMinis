package com.openminis.app.sandbox

import com.openminis.app.ProductionSources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * [GH #453] Offload calls in flight: admission up to the cap, a per-command
 * count with the oldest age, and worker thread names that fit the kernel's
 * 15-byte comm.
 */
class OffloadInFlightTest {

    @Test fun `admission stops at the cap and resumes when a call ends`() {
        val f = OffloadInFlight()
        repeat(3) { assertTrue(f.tryAdmit(3)) }
        assertFalse(f.tryAdmit(3))
        assertEquals(3, f.count)
        f.release()
        assertTrue(f.tryAdmit(3))
    }

    @Test fun `admission is exact under contention`() {
        val f = OffloadInFlight()
        val start = CountDownLatch(1)
        val admitted = java.util.concurrent.atomic.AtomicInteger()
        val threads = List(16) { thread { start.await(); repeat(100) { if (f.tryAdmit(128)) admitted.incrementAndGet() } } }
        start.countDown()
        threads.forEach { it.join(5_000) }
        assertEquals(128, admitted.get())
        assertEquals(128, f.count)
    }

    @Test fun `the summary names each command, how many, and the oldest`() {
        var now = 1_000_000L
        val f = OffloadInFlight { now }
        assertEquals("none", f.summary())
        val finish = CountDownLatch(1)
        // begin() is per worker thread; start them one at a time so each reads its own clock value.
        val workers = listOf("android-notification" to 0L, "android-notification" to 5_000L, "model_use" to 9_000L).map { (cmd, at) ->
            now = 1_000_000L + at
            val begun = CountDownLatch(1)
            val t = thread { f.begin(cmd); begun.countDown(); finish.await(); f.end() }
            assertTrue(begun.await(2, TimeUnit.SECONDS))
            t
        }
        now = 1_000_000L + 812_000L
        assertEquals("android-notification:2(oldest 812s),model_use:1(oldest 803s)", f.summary())
        finish.countDown()
        workers.forEach { it.join(2_000) }
        assertEquals("none", f.summary())
    }

    @Test fun `worker thread names fit comm and say which command`() {
        assertEquals("mn-o-notificati", OffloadInFlight.workerThreadName("android-notification"))
        assertEquals("mn-o-scheduled", OffloadInFlight.workerThreadName("minis-scheduled"))
        assertEquals("mn-o-model_use", OffloadInFlight.workerThreadName("model_use"))
        assertEquals("mn-o-a_b", OffloadInFlight.workerThreadName("a.b"))
        assertEquals("mn-offload-wk", OffloadInFlight.WORKER_THREAD_NAME)
        for (n in listOf("android-notification", "minis-scheduled", "x".repeat(40))) {
            assertTrue(OffloadInFlight.workerThreadName(n).length <= 15)
        }
    }

    @Test fun `the server admits through the cap, releases in finally, and refuses with a reply`() {
        val src = ProductionSources.read("sandbox/NativeOffload.kt")
        val accept = src.substringAfter("private fun runAcceptLoop(").substringBefore("private fun rejectBusy(")
        assertTrue(accept.contains("if (!inFlight.tryAdmit(MAX_CONCURRENT_CALLS))"))
        assertTrue(accept.indexOf("rejectBusy(client)") < accept.indexOf("thread(name = OffloadInFlight.WORKER_THREAD_NAME"))
        assertTrue("released when the worker ends", accept.substringAfter("} finally {").contains("inFlight.release()"))
        assertTrue("released when the thread cannot start", accept.substringAfter("} catch (t: Throwable) {").contains("inFlight.release()"))
        val reject = src.substringAfter("private fun rejectBusy(").substringBefore("fun inFlightSummary()")
        assertTrue(reject.contains("client.soTimeout = 2_000"))
        assertTrue(reject.contains("PeerCheck.allowed("))
        assertTrue(reject.contains("exitCode = EXIT_BUSY"))
        val handle = src.substringAfter("private fun handleClient(").substringBefore("private class WireRequest")
        assertTrue(handle.contains("inFlight.begin(name)"))
        assertTrue(handle.substringAfter("} finally {").contains("inFlight.end()"))
        assertTrue(NativeOffloadServer.MAX_CONCURRENT_CALLS >= 64)
    }
}
