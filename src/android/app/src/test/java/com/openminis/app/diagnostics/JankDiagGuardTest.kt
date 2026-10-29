package com.openminis.app.diagnostics

import com.openminis.app.ProductionSources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-jankdiag] The diagnostics must not cause the jank they measure: no
 * log line per frame or per token, no /proc read or log write on the main thread.
 */
class JankDiagGuardTest {
    private val src = ProductionSources.read("diagnostics/JankDiag.kt")

    private fun body(signature: String): String {
        val start = src.indexOf(signature)
        assertTrue("missing $signature", start >= 0)
        var depth = 0
        var i = src.indexOf('{', start)
        val from = i
        while (i < src.length) {
            when (src[i]) { '{' -> depth++; '}' -> { depth--; if (depth == 0) return src.substring(from, i + 1) } }
            i++
        }
        return src.substring(from)
    }

    @Test fun `main-thread hooks only store and count, logging goes through the background handler`() {
        for (sig in listOf(
            "fun setScreen(", "fun enterScreen(", "fun leaveScreen(", "private fun applyScreen(", "fun setStreaming(", "fun onStreamChunk(", "fun onStreamPublish(", "fun onNewestRowRecompose(",
            "fun onStreamParse(", "fun beginOpen(", "fun openStage(", "fun openStable(", "fun onCancelStreamStart(", "fun onCancelStreamEnd(",
        )) {
            val b = body(sig)
            val log = b.indexOf("AppLogger.")
            assertTrue("$sig logs outside bg.post", log < 0 || (b.indexOf("bg.post") in 0 until log))
            assertFalse("$sig reads /proc", b.contains("/proc"))
        }
    }

    @Test fun `the looper printer only stores timestamps`() {
        val printer = body("private fun installLooperMonitor()")
        assertFalse(printer.contains("AppLogger."))
        assertFalse(printer.contains("stackTrace"))
        assertFalse(printer.contains("File("))
        assertTrue("a long message is handed to the background thread", printer.contains("bg.post { reportStall("))
    }

    @Test fun `frame metrics arrive on the background handler and are never logged per frame`() {
        assertTrue(src.contains("w.addOnFrameMetricsAvailableListener(listener, bg)"))
        val onFrame = body("private fun onFrame(")
        // The only line onFrame can write is the one-off Stop measurement.
        assertEquals(1, Regex("AppLogger\\.").findAll(onFrame).count())
        assertTrue(onFrame.indexOf("pendingCancel?.let") in 0 until onFrame.indexOf("AppLogger."))
    }

    @Test fun `proc is read only through ResourceWatermark, from the background summary`() {
        // [GH #453] The /proc reads moved to ResourceWatermark; JankDiag calls it
        // from summarize() (the bg handler's tick) and nowhere else.
        assertEquals(0, Regex("/proc/").findAll(src).count())
        assertEquals(1, Regex("ResourceWatermark\\.sample\\(").findAll(src).count())
        assertTrue(body("private fun resources(").contains("ResourceWatermark.sample()"))
        assertTrue(body("private fun summarize(").contains("val res = resources()"))
        assertTrue(body("private fun heartbeat(").contains("res.fields()"))
    }

    @Test fun `resources are sampled in the background too, not only while an Activity is resumed`() {
        // GH #453's process grew for 13 h with nothing on screen; the old heartbeat
        // was gated on resumed > 0 and printed nothing then.
        val summarize = body("private fun summarize(")
        assertTrue(summarize.indexOf("val res = resources()") < summarize.indexOf("if (resumed.get() > 0) heartbeat(log, res)"))
        assertTrue(summarize.contains("[JankDiag] res background"))
        val resources = body("private fun resources(")
        assertTrue("the water mark is written every sample", resources.contains("ResourceWatermark.writeWatermark("))
        assertTrue("the breakdown is gated", resources.contains("alertGate.shouldReport("))
    }

    @Test fun `hot-path hooks are single cheap calls`() {
        val screen = ProductionSources.read("ui/chat/ChatScreen.kt")
        assertTrue(screen.contains("com.openminis.app.diagnostics.JankDiag.onNewestRowRecompose()"))
        val vm = ProductionSources.read("ui/chat/ChatViewModel.kt")
        assertTrue(vm.contains("com.openminis.app.diagnostics.JankDiag.onStreamChunk(content.length)"))
        assertTrue(vm.contains("com.openminis.app.diagnostics.JankDiag.onCancelStreamStart()") &&
            vm.contains("com.openminis.app.diagnostics.JankDiag.onCancelStreamEnd()"))
        val logger = ProductionSources.read("logging/AppLogger.kt")
        assertTrue("log cost is timed only for main-thread callers",
            logger.contains("val t0 = if (onMain) System.nanoTime() else 0L"))
    }
}
