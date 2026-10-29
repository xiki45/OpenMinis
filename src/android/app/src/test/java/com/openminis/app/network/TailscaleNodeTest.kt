package com.openminis.app.network

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-net-android-phase0] The embedded Tailscale node's Kotlin side.
 *
 *  1. Login links are bearer-like (whoever opens one claims the node) and
 *     tsnet prints one every 5 s while it waits, so every log line is
 *     redacted before it reaches AppLogger - same pattern as iOS.
 *  2. On Android tailscale's logpolicy panics without TS_LOGS_DIR (no user
 *     cache dir, cwd "/"), and a Go panic kills the whole app. Measured on
 *     the API 35 emulator before the fix: "panic: no safe place found to
 *     store log state", SIGABRT. The Android entry point must set it.
 */
class TailscaleNodeTest {

    @Test
    fun loginLinksAreRedactedInLogLines() {
        val line = "control: AuthURL is https://login.tailscale.com/a/1a2b3c4d5e6f7"
        val out = TailscaleNode.redact(line)
        assertEquals("control: AuthURL is https://login.tailscale.com/a/<redacted>", out)
        assertFalse(out.contains("1a2b3c4d5e6f7"))
        // Lines without a link pass through untouched.
        assertEquals("link state: wlan0", TailscaleNode.redact("link state: wlan0"))
    }

    @Test
    fun androidStartSetsTheLogsDirBeforeStarting() {
        val go = File("../../../deps/rclone-mobile/gomobile/net.go").readText()
        val start = go.substringAfter("func TsStart(").substringBefore("\n}")
        val setenv = start.indexOf("os.Setenv(\"TS_LOGS_DIR\"")
        assertTrue("TsStart must set TS_LOGS_DIR", setenv >= 0)
        assertTrue("TS_LOGS_DIR must be set before tsnet.Start", setenv < start.indexOf("tsnet.Start("))
    }

    @Test
    fun stateLivesInNoBackupFiles() {
        val src = File("src/main/java/com/openminis/app/network/TailscaleNode.kt").readText()
        assertTrue(src.contains("File(context.noBackupFilesDir, \"tailscale\")"))
    }
}
