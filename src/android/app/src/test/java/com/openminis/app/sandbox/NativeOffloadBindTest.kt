package com.openminis.app.sandbox

import com.openminis.app.ProductionSources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-native-offload-bind] A held abstract socket name must not stop the app
 * from starting: the name is per uid, start() degrades instead of throwing,
 * the shells pass `--native-offload` only while the server listens, and the
 * handler stubs report the CLI unavailable instead of exiting 0.
 */
class NativeOffloadBindTest {

    private val server = ProductionSources.read("sandbox/NativeOffload.kt")

    @Test
    fun `the socket name is per uid and pid and never the legacy shared name`() {
        assertEquals("native-offload-10379-4242", NativeOffloadServer.socketNameFor(10379, 4242))
        // App clone / work profile / secondary user: same appId, other user.
        assertNotEquals(NativeOffloadServer.socketNameFor(10379, 4242), NativeOffloadServer.socketNameFor(99910379, 4242))
        // [GH-440] Two processes of the same uid must not share a name either.
        assertNotEquals(NativeOffloadServer.socketNameFor(10379, 4242), NativeOffloadServer.socketNameFor(10379, 4243))
        for (uid in listOf(0, 10379, 99910379, Int.MAX_VALUE)) {
            val name = NativeOffloadServer.socketNameFor(uid, Int.MAX_VALUE)
            assertNotEquals("native-offload", name)
            assertFalse("':' separates the name from the handler list in --native-offload", name.contains(':'))
            assertTrue("abstract names are limited to 107 bytes", name.toByteArray().size < 107)
        }
        assertTrue("the live name is built from this process's uid and pid", server.contains(
            "val socketName: String by lazy {\n" +
                "        socketNameFor(android.os.Process.myUid(), android.os.Process.myPid())\n    }"))
    }

    @Test
    fun `start never throws or sleeps on the caller and retries in the background`() {
        val start = server.substringAfter("fun start(rootfsDir: File) {").substringBefore("\n    }\n")
        assertFalse("start() must not throw", start.contains("throw "))
        assertFalse("no sleeping on the caller's (main) thread", start.contains("Thread.sleep"))
        assertTrue(start.contains("val s = tryBind(attempt = 1)"))
        assertTrue(start.contains("retryThread = thread(name = \"native-offload-rebind\", isDaemon = true) { retryBindLoop() }"))
        assertFalse("the old foreground retry ladder is gone", server.contains("fun bindWithRetry"))
        val loop = server.substringAfter("private fun retryBindLoop() {").substringBefore("\n    }\n")
        assertTrue("retries until it binds", loop.contains("while (true)"))
        assertTrue(loop.contains("onBound(s)"))
    }

    @Test
    fun `awaitListening is false when nothing is bound or pending`() {
        // Not started in a JVM test: no server, no retry - must not wait.
        val t0 = System.currentTimeMillis()
        assertFalse(NativeOffloadServer.awaitListening(5_000L))
        assertTrue("returns at once", System.currentTimeMillis() - t0 < 1_000L)
        val await = server.substringAfter("fun awaitListening(").substringBefore("\n    }\n")
        assertTrue("never blocks the main thread", await.contains("isCurrentThread == true) return false"))
        assertTrue("waits only in the startup window, not on every command while degraded",
            await.contains("if (sinceFail > AWAIT_WINDOW_MS) return false"))
    }

    @Test
    fun `application onCreate cannot be failed by the offload server`() {
        val app = ProductionSources.read("MinisApp.kt")
        val site = app.substringAfter("// [T-native-offload-bind] start() no longer throws").take(700)
        assertTrue(site.contains("try {\n            NativeOffloadServer.start(RootfsManager.getInstance(this).rootfsDir)\n        } catch (t: Throwable) {"))
    }

    @Test
    fun `all four proot launchers pass the flag only while the server listens`() {
        for (f in listOf("FreshProcessShell.kt", "PersistentShell.kt", "TerminalSession.kt", "PRootKernel.kt")) {
            val src = ProductionSources.read("sandbox/$f")
            assertTrue("$f gates --native-offload", src.contains(
                "if (handlers.isNotEmpty() && NativeOffloadServer.awaitListening()) {\n" +
                    "            cmd.add(\"--native-offload=\${NativeOffloadServer.socketName}:"))
            assertFalse("$f has no ungated flag", src.contains("if (handlers.isNotEmpty()) {\n            cmd.add(\"--native-offload="))
        }
    }

    @Test
    fun `handler stubs report unavailable instead of a silent success`() {
        val stub = HandlerStub.content("android-device")
        assertTrue(stub.startsWith("#!/bin/sh\n"))
        assertTrue(stub.contains("android-device: unavailable"))
        assertTrue("to stderr", stub.contains("\" >&2\n"))
        assertTrue(stub.endsWith("exit 69\n"))
        assertNotEquals(HandlerStub.LEGACY_CONTENT, stub)
        val kernel = ProductionSources.read("sandbox/PRootKernel.kt")
        assertTrue("only our own legacy stub is upgraded", kernel.contains("if (have == HandlerStub.LEGACY_CONTENT) {"))
    }
}
