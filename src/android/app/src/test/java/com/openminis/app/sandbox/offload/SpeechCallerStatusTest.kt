package com.openminis.app.sandbox.offload

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-431-cancel] A killed android-speech caller is a proot tracee stopped in
 * the offload call: it stays in /proc until the host replies, so "the pid
 * still exists" cannot tell a cancelled caller from a live one. Samples are
 * the fields read on a Pixel 4a before and after `kill -TERM`.
 */
class SpeechCallerStatusTest {

    private fun status(state: String, sigPnd: String = "0000000000000000", shdPnd: String = "0000000000000000") =
        "Name:\tsh\nState:\t$state\nTgid:\t6496\nTracerPid:\t6491\nSigPnd:\t$sigPnd\nShdPnd:\t$shdPnd\n"

    @Test
    fun `a tracee waiting on the offload call is alive`() {
        assertTrue(CallerStatus.alive(status("t (tracing stop)")))
        assertTrue(CallerStatus.alive(status("S (sleeping)")))
    }

    @Test
    fun `a pending SIGTERM means the caller was stopped`() {
        assertFalse(CallerStatus.alive(status("t (tracing stop)", shdPnd = "0000000000004000")))
    }

    @Test
    fun `SIGINT, SIGHUP and SIGKILL count too, other signals do not`() {
        assertFalse(CallerStatus.alive(status("t (tracing stop)", sigPnd = "0000000000000002")))
        assertFalse(CallerStatus.alive(status("t (tracing stop)", shdPnd = "0000000000000001")))
        assertFalse(CallerStatus.alive(status("t (tracing stop)", shdPnd = "0000000000000100")))
        // SIGCHLD (17) is routine.
        assertTrue(CallerStatus.alive(status("t (tracing stop)", shdPnd = "0000000000010000")))
    }

    @Test
    fun `a zombie or a missing process is gone`() {
        assertFalse(CallerStatus.alive(status("Z (zombie)")))
        assertFalse(CallerStatus.alive(null))
    }
}
