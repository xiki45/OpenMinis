package com.openminis.app.ui.chat

import com.openminis.app.ProductionSources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [T-port-composer-hint-debug] The DEV_TOOLS-only `debug.chat.usageHint` RPC
 * that shows the composer usage line with chosen figures, so the Top 7
 * carousel can be tested on a device; and that a release build cannot use it.
 */
class DebugUsageHintTest {

    @Test
    fun `figures and tiers follow the requested percent`() {
        val warn = DebugUsageHint.build(5, 75, 200_000, null)
        assertEquals(5, warn.generation)
        assertEquals(listOf("75%", "150k / 200k"), warn.highlights)
        assertEquals(ContextUsage.Tier.WARNING, warn.tier)
        assertEquals(listOf(UsageHintPage.CONTEXT), warn.pages())
        assertEquals(ContextUsage.Tier.CRITICAL, DebugUsageHint.build(1, 85, 200_000, null).tier)
        assertEquals(ContextUsage.Tier.NORMAL, DebugUsageHint.build(1, 40, 128_000, null).tier)
        // Out-of-range input is clamped, never a crash or a zero usage.
        assertEquals("100%", DebugUsageHint.build(1, 500, 1000, null).highlights[0])
        assertEquals("1%", DebugUsageHint.build(1, 0, 1000, null).highlights[0])
    }

    @Test
    fun `a quota gives the second page with its own tier`() {
        val h = DebugUsageHint.build(2, 72, 200_000, DebugUsageHint.SAMPLE_QUOTA.copy(tier = DebugUsageHint.tierOf("warning")))
        assertEquals(listOf(UsageHintPage.CONTEXT, UsageHintPage.QUOTA), h.pages())
        assertEquals("Quota 5h 14% / 7d 32% used", h.quotaText)
        assertEquals("14%", h.quotaHighlight)
        assertEquals(ContextUsage.Tier.WARNING, h.quotaTier)
        assertEquals(ContextUsage.Tier.CRITICAL, DebugUsageHint.tierOf("depleted"))
        assertEquals(ContextUsage.Tier.NORMAL, DebugUsageHint.tierOf(null))
        assertNull(DebugUsageHint.build(1, 72, 1000, null).quotaText)
    }

    @Test
    fun `release builds cannot use it`() {
        val vm = ProductionSources.read("ui/chat/ChatViewModel.kt")
        val fn = vm.substringAfter("internal fun debugShowContextUsageHint(").substringBefore("\n    }\n")
        assertTrue("the view model refuses outside DEV_TOOLS", fn.contains("if (!com.openminis.app.BuildConfig.DEV_TOOLS) return null"))
        val rpc = ProductionSources.read("debug/UsageHintDebugMethods.kt")
        assertTrue(rpc.contains("if (!BuildConfig.DEV_TOOLS) throw RPCException("))
        val handler = ProductionSources.read("debug/DebugRPCHandler.kt")
        assertTrue(handler.contains("\"debug.chat.usageHint\" -> UsageHintDebugMethods.show(context, params)"))
        assertTrue(ProductionSources.read("debug/DebugMethodRegistry.kt").contains("name = \"debug.chat.usageHint\""))
        // The debug server itself only starts with DEV_TOOLS, which release sets false.
        assertTrue(ProductionSources.read("MinisApp.kt").contains("if (BuildConfig.DEV_TOOLS) {\n            try {\n                com.openminis.app.debug.DebugServer(this).start()"))
        val gradle = File(ProductionSources.mainRoot()!!, "../../../../../../build.gradle.kts").readText()
        assertTrue(gradle.contains("buildConfigField(\"boolean\", \"DEV_TOOLS\", \"false\")"))
        val release = gradle.substringAfter("        release {").substringBefore("\n        }\n")
        assertTrue("release must not turn DEV_TOOLS on", !release.contains("\"DEV_TOOLS\", \"true\""))
    }
}
