package com.openminis.app.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * [GH #453] FileToolIO's pool is bounded. A read stuck in the kernel keeps its
 * thread past the 45 s timeout; the pool used to be a cachedThreadPool, so
 * every retry against a dead mount added a thread. Past the cap the call now
 * fails at once with an agent-facing message, and the pool still serves
 * normal work concurrently below it.
 */
class FileToolIOBoundTest {

    @Test fun `a full pool of uninterruptible work refuses the next call at once`() {
        val pool = FileToolIO.newPool(2)
        val release = CountDownLatch(1)
        val running = CountDownLatch(2)
        // Uninterruptible, like FileInputStream.read on a hung FUSE mount.
        val work = { running.countDown(); while (release.count > 0) { try { release.await() } catch (_: InterruptedException) {} }; "late" }
        try {
            val callers = List(2) { thread { runCatching { FileToolIO.runOn(pool, 2, "file_read", false, 100, work) } } }
            assertTrue(running.await(2, TimeUnit.SECONDS))
            callers.forEach { it.join(2_000) }
            assertEquals("both threads still held after their callers timed out", 2, (pool as ThreadPoolExecutor).activeCount)
            val t0 = System.nanoTime()
            try {
                FileToolIO.runOn(pool, 2, "file_edit", writes = true, timeoutMs = 10_000) { "x" }
                fail("should be refused")
            } catch (e: FileToolIO.TimedOut) {
                assertTrue("refused at once", System.nanoTime() - t0 < 1_000_000_000L)
                assertEquals(FileToolIO.busyMessage("file_edit", 2), e.message)
            }
            assertEquals("no thread added", 2, pool.poolSize)
            release.countDown()
            Thread.sleep(100)
            assertEquals("x", FileToolIO.runOn(pool, 2, "file_read", false, 2_000) { "x" })
        } finally {
            release.countDown()
            pool.shutdownNow()
        }
    }

    @Test fun `a pool full of live work waits for a thread instead of blaming a mount`() {
        // [T-fileio-cap-healthy-load] Several chats' parallel file tools can
        // fill the pool with healthy work; the next call waits for a thread.
        val pool = FileToolIO.newPool(2)
        val release = CountDownLatch(1)
        val running = CountDownLatch(2)
        try {
            val callers = List(2) {
                thread { FileToolIO.runOn(pool, 2, "file_read", false, 10_000) { running.countDown(); release.await(); "slow" } }
            }
            assertTrue(running.await(2, TimeUnit.SECONDS))
            thread { Thread.sleep(300); release.countDown() }
            assertEquals("x", FileToolIO.runOn(pool, 2, "file_read", false, 5_000) { "x" })
            callers.forEach { it.join(2_000) }
        } finally {
            release.countDown()
            pool.shutdownNow()
        }
    }

    @Test fun `a pool that stays full of live work says it is busy, not that a mount hung`() {
        val pool = FileToolIO.newPool(2)
        val release = CountDownLatch(1)
        val running = CountDownLatch(2)
        try {
            List(2) { thread { runCatching { FileToolIO.runOn(pool, 2, "file_read", false, 10_000) { running.countDown(); release.await(); "slow" } } } }
            assertTrue(running.await(2, TimeUnit.SECONDS))
            try {
                FileToolIO.runOn(pool, 2, "file_write", writes = true, timeoutMs = 300) { "x" }
                fail("should be refused")
            } catch (e: FileToolIO.TimedOut) {
                assertEquals(FileToolIO.saturatedMessage("file_write", 2), e.message)
            }
        } finally {
            release.countDown()
            pool.shutdownNow()
        }
    }

    @Test fun `a pool is reported hung only when hung calls hold at least half of it`() {
        assertFalse(FileToolIO.poolHeldByHungWork(0, 32))
        assertFalse(FileToolIO.poolHeldByHungWork(15, 32))
        assertTrue(FileToolIO.poolHeldByHungWork(16, 32))
        assertTrue(FileToolIO.poolHeldByHungWork(1, 1))
    }

    @Test fun `normal use runs several calls at once below the cap`() {
        val pool = FileToolIO.newPool(FileToolIO.MAX_THREADS)
        try {
            val gate = CountDownLatch(4)
            val results = java.util.Collections.synchronizedList(mutableListOf<String>())
            val callers = List(4) { i ->
                thread { results += FileToolIO.runOn(pool, FileToolIO.MAX_THREADS, "file_read", false, 5_000) { gate.countDown(); gate.await(); "r$i" } }
            }
            callers.forEach { it.join(5_000) }
            assertEquals(setOf("r0", "r1", "r2", "r3"), results.toSet())
        } finally {
            pool.shutdownNow()
        }
    }

    @Test fun `pool threads carry the mn- prefix`() {
        val pool = FileToolIO.newPool(1)
        try {
            val name = FileToolIO.runOn(pool, 1, "file_read", false, 2_000) { Thread.currentThread().name }
            assertTrue(name, name.startsWith("mn-fileio-"))
            assertTrue(name.length <= 15 || name.substring(0, 15).startsWith("mn-fileio-"))
        } finally {
            pool.shutdownNow()
        }
    }
}
