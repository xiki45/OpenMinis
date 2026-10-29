package com.openminis.app.sandbox

import com.openminis.app.ProductionSources
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Hardening around the native-offload socket: the listener is close-on-exec
 * [T-native-offload-cloexec], only this app's uid may connect
 * [T-native-offload-peer-uid], and the terminal child closes inherited fds
 * before exec [T-pty-close-fds].
 */
class NativeOffloadHardeningTest {

    private val server = ProductionSources.read("sandbox/NativeOffload.kt")

    @Test
    fun `the listening socket is set close-on-exec as soon as it is bound`() {
        val onBound = server.substringAfter("private fun onBound(s: LocalServerSocket) {").substringBefore("\n    }\n")
        assertTrue(onBound.trimStart().startsWith("setCloseOnExec(s)"))
        assertTrue(server.contains("OsConstants.F_SETFD, android.system.OsConstants.FD_CLOEXEC)"))
    }

    @Test
    fun `only the app's own uid may use the socket`() {
        assertTrue(PeerCheck.allowed(10379, 10379))
        assertFalse("another app", PeerCheck.allowed(10234, 10379))
        assertFalse("adb shell", PeerCheck.allowed(2000, 10379))
        assertFalse("root", PeerCheck.allowed(0, 10379))
        assertFalse("credentials unreadable", PeerCheck.allowed(-1, 10379))
        val handle = server.substringAfter("private fun handleClient(client: LocalSocket) {").take(1200)
        assertTrue("checked before any byte is read",
            handle.indexOf("PeerCheck.allowed(") in 0 until handle.indexOf("DataInputStream(client.inputStream)"))
    }

    @Test
    fun `the terminal child closes inherited fds with raw syscalls before exec`() {
        val pty = File(ProductionSources.mainRoot()!!, "../../../../cpp/pty_bridge.c").readText()
        val child = pty.substringAfter("if (pid == 0) {").substringBefore("execve(cmd, argv, envp);")
        assertTrue(child.contains("closeInheritedFds();"))
        val helper = pty.substringAfter("static void closeInheritedFds(void) {").substringBefore("\n}\n")
        assertTrue(helper.contains("syscall(__NR_close, (int) fd)"))
        assertFalse("no fdsan-checked close() / malloc after fork", helper.contains(" close(") || helper.contains("opendir"))
        assertTrue("starts above stdio", helper.contains("for (long fd = 3;"))
    }
}
