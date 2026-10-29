package com.openminis.app.ui.chat

import com.openminis.app.ProductionSources
import com.openminis.app.data.ContextSizeMeter
import com.openminis.app.data.db.CompactMarkerEntity
import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-ctx-reopen-compacted-glow] Alice Q3: after a compaction the composer
 * showed "Context 49% used", but reopening the session turned the glow amber
 * again until one reply came back. loadSession() measured the context
 * (publishMeasuredContextUsage) before it restored the compact marker, so it
 * measured the whole uncompacted history. Reproduced on a Pixel 4a with a
 * 64000 window: 48554 (76%, amber) before compaction, 31047 (49%) after,
 * amber again on reopen.
 *
 * ChatViewModel needs an Application and a database, so the order is locked
 * with a source guard, and the size difference with the same
 * RequestContextBuilder.compacted() the measurement uses.
 */
class ContextUsageReopenCompactedTest {

    private val vm = ProductionSources.read("ui/chat/ChatViewModel.kt")

    private val loadBody: String by lazy {
        val start = vm.indexOf("private fun loadSession(): kotlinx.coroutines.Job?")
        assertTrue("loadSession not found", start >= 0)
        vm.substring(start, vm.indexOf("\n    private fun ", start + 1).let { if (it < 0) vm.length else it })
    }

    @Test fun `loadSession restores the marker before it measures the context`() {
        val restoreMarker = loadBody.indexOf("_cachedLatestMarker = marker")
        val restoreSummary = loadBody.indexOf("_compactSummary.value = marker?.summary")
        // The call, not a comment that mentions it.
        val measure = loadBody.indexOf("runCatching { publishMeasuredContextUsage() }")
        assertTrue("marker restore not found", restoreMarker >= 0)
        assertTrue("summary restore not found", restoreSummary >= 0)
        assertTrue("measurement not found", measure >= 0)
        assertTrue("marker must be restored before the first measurement", restoreMarker < measure)
        assertTrue("summary must be restored before the first measurement", restoreSummary < measure)
    }

    @Test fun `loadSession measures again when the marker was healed`() {
        val graying = loadBody.indexOf("applyCompactMarkerGraying(ordered, marker")
        assertTrue(graying >= 0)
        val after = loadBody.substring(graying)
        val heal = after.indexOf("_cachedLatestMarker !== marker")
        assertTrue("re-measure after a heal", heal >= 0)
        val republish = after.indexOf("runCatching { publishMeasuredContextUsage() }", heal)
        val reset = after.indexOf("contextTierTracker.reset(", heal)
        assertTrue(republish > heal)
        assertTrue(reset > republish)
    }

    @Test fun `the measurement goes through the restored compaction`() {
        assertTrue(vm.contains("applyFrozenTrim(RequestContextBuilder.compacted(agentHistory, currentCompaction(), COMPACT_KEEP_RECENT_USER_TURNS))"))
        val compaction = vm.substringAfter("private fun currentCompaction(): RequestContextBuilder.Compaction =").substringBefore("\n\n")
        assertTrue(compaction.contains("summary = _compactSummary.value"))
        assertTrue(compaction.contains("marker = _cachedLatestMarker"))
    }

    private fun user(t: String, id: String) = LLMMessage(LLMMessage.Role.USER, t, contentParts = listOf(AgentContentPart.Text(t)), dbMessageId = id)
    private fun assistant(t: String, id: String) = LLMMessage(LLMMessage.Role.ASSISTANT, t, dbMessageId = id)

    /** 15 turns of 3000 chars each way, like the device repro's seeded session. */
    private val history: List<LLMMessage> = (0 until 15).flatMap { i ->
        listOf(user("question $i " + "q".repeat(3_000), "u$i"), assistant("answer $i " + "a".repeat(3_000), "a$i"))
    }

    private val marker = CompactMarkerEntity(
        id = "mk", sessionId = "s", summary = "summary", firstKeptSortOrder = 0, compactedCount = 26, createdAt = 0,
        lastCompactedMessageId = "a12", version = 2,
    )

    @Test fun `a compacted session measures the compacted history, not the whole one`() {
        val full = ContextSizeMeter.estimateTokens(history)
        val compacted = ContextSizeMeter.estimateTokens(
            RequestContextBuilder.compacted(history, RequestContextBuilder.Compaction(summary = "summary " + "s".repeat(2_000), marker = marker), 3),
        )
        assertTrue("compacted=$compacted full=$full", compacted < full / 2)
        // A window between the two puts the full history in the warning band
        // and the compacted one below it: the glow on reopen must follow the
        // compacted figure.
        val window = full * 100 / 75
        assertEquals(ContextUsage.Tier.WARNING, ContextUsage.from(full, window)!!.tier)
        assertEquals(ContextUsage.Tier.NORMAL, ContextUsage.from(compacted, window)!!.tier)
    }

    @Test fun `an uncompacted session is measured exactly as before`() {
        val none = RequestContextBuilder.compacted(history, RequestContextBuilder.Compaction(summary = null, marker = null), 3)
        assertEquals(history, none)
        assertEquals(ContextSizeMeter.estimateTokens(history), ContextSizeMeter.estimateTokens(none))
    }
}
