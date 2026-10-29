package com.openminis.app.ui.chat

import com.openminis.app.ProductionSources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-subagent-detail-rich] The sub agent detail card's facts are read, never
 * estimated: tool calls / outcomes / durations from the child's blocks, the
 * task from the delegation's own arguments, the budget from the start record.
 * Mirrors iOS MinisTests/Standalone/SubAgentRunDigestTests.
 */
class SubAgentRunDigestTest {

    private fun tool(id: String, name: String, status: ToolBlockStatus, title: String = "", args: String = "", ms: Long = 0) =
        AssistantBlock(id = id, kind = "tool_use", toolName = name, toolStatus = status, toolTitle = title, toolArgs = args, durationMs = ms)

    private fun text(id: String, s: String) = AssistantBlock(id = id, kind = "text", content = s)

    private fun assistant(vararg blocks: AssistantBlock, content: String = "") =
        ChatMessage(id = blocks.firstOrNull()?.id ?: content, role = "assistant", content = content, toolBlocks = blocks.toList())

    @Test
    fun `calls, states and durations come from the child's tool blocks`() {
        val msgs = listOf(
            ChatMessage(id = "u", role = "user", content = "do it"),
            assistant(
                text("t1", "Looking around."),
                tool("a", "shell_execute", ToolBlockStatus.SUCCESS, title = "ls -la\nsecond line", ms = 840),
                tool("b", "file_read", ToolBlockStatus.FAILED, args = """{"path":"/tmp/x.go"}""", ms = 12_400),
            ),
            assistant(
                tool("c", "shell_execute", ToolBlockStatus.TIMEOUT, ms = 75_000),
                tool("d", "browser_use", ToolBlockStatus.CANCELLED),
                tool("e", "shell_execute", ToolBlockStatus.RUNNING, title = "go build ./...", ms = 999),
                text("t2", "  Building now.  "),
            ),
        )
        val d = SubAgentRunDigest.from(msgs, turns = 3, tokensIn = 1234, tokensOut = 56)
        assertEquals(listOf("a", "b", "c", "d", "e"), d.calls.map { it.id })
        assertEquals("ls -la", d.calls[0].title)                   // first line of the title
        assertEquals("/tmp/x.go", d.calls[1].title)               // falls back to the path argument
        assertEquals(SubAgentRunDigest.CallState.FAILED, d.calls[2].state) // timeout reads as failed
        assertEquals(SubAgentRunDigest.CallState.CANCELLED, d.calls[3].state)
        assertNull("a running call has no duration yet", d.calls[4].durationMs)
        assertNull("an unrecorded duration is left out, not 0s", d.calls[3].durationMs)
        assertEquals(840L, d.calls[0].durationMs)
        assertEquals(1, d.okCount)
        assertEquals(2, d.failedCount)
        assertEquals("Building now.", d.latestText)
        assertEquals(3, d.turns)
        assertTrue(d.hasTokens)
        assertFalse(d.isEmpty)
    }

    @Test
    fun `latest text falls back to message content when there are no text blocks`() {
        val d = SubAgentRunDigest.from(listOf(assistant(content = "Plain reply")))
        assertEquals("Plain reply", d.latestText)
        assertTrue(SubAgentRunDigest.from(emptyList()).isEmpty)
    }

    @Test
    fun `byTool is most used first with ties in first-use order, recent is newest first`() {
        val d = SubAgentRunDigest.from(listOf(assistant(
            tool("1", "file_read", ToolBlockStatus.SUCCESS),
            tool("2", "shell_execute", ToolBlockStatus.SUCCESS),
            tool("3", "browser_use", ToolBlockStatus.SUCCESS),
            tool("4", "shell_execute", ToolBlockStatus.SUCCESS),
        )))
        assertEquals(listOf("shell_execute" to 2, "file_read" to 1, "browser_use" to 1), d.byTool)
        assertEquals(listOf("4", "3"), d.recent(2).map { it.id })
        assertEquals(4, d.recent(99).size)
        assertTrue(d.recent(-1).isEmpty())
    }

    @Test
    fun `compact and duration labels`() {
        assertEquals("950", SubAgentRunDigest.compact(950))
        assertEquals("1.2k", SubAgentRunDigest.compact(1234))
        assertEquals("46k", SubAgentRunDigest.compact(45_678))
        assertEquals("2.3M", SubAgentRunDigest.compact(2_300_000))
        assertEquals("0.8s", SubAgentRunDigest.durationLabel(840))
        assertEquals("12s", SubAgentRunDigest.durationLabel(12_400))
        assertEquals("1m 15s", SubAgentRunDigest.durationLabel(75_000))
        assertEquals("1h 2m", SubAgentRunDigest.durationLabel(3_720_000))
        assertEquals("0s", SubAgentRunDigest.durationLabel(-5))
    }

    @Test
    fun `task args are parsed from a delegation and absent for a control call`() {
        val a = SubAgentTaskArgs.parse(
            """{"task":"  Port the HTTP client ","context":"see pkg/http","max_minutes":15.0,"wait":true,"progress_report":"FREQUENT","agent":"coder"}""",
        )!!
        assertEquals("Port the HTTP client", a.task)
        assertEquals("see pkg/http", a.context)
        assertEquals(15, a.maxMinutes)
        assertEquals(true, a.wait)
        assertEquals("frequent", a.progressReport)
        assertEquals("coder", a.agent)
        val bare = SubAgentTaskArgs.parse("""{"task":"x","progress_report":"loud"}""")!!
        assertNull(bare.maxMinutes)
        assertNull("absent wait is not invented as false", bare.wait)
        assertNull("an unknown level is dropped", bare.progressReport)
        assertNull(SubAgentTaskArgs.parse("""{"action":"status"}"""))
        assertNull(SubAgentTaskArgs.parse("not json"))
        assertNull(SubAgentTaskArgs.parse(null))
    }

    @Test
    fun `time budget prefers the start record and clamps a request like the runner`() {
        assertEquals(12, SubAgentTimeBudget.minutes(12, 99, true, 10, 60))
        assertEquals(60, SubAgentTimeBudget.minutes(null, 99, true, 10, 60))
        assertEquals(1, SubAgentTimeBudget.minutes(null, 0, true, 10, 60))
        assertEquals(10, SubAgentTimeBudget.minutes(null, null, true, 10, 60))
        assertNull("no arguments, no record: no number is made up", SubAgentTimeBudget.minutes(null, null, false, 10, 60))
        assertEquals(30_000L, SubAgentTimeBudget.remainingMs(1, startedAtMs = 0, nowMs = 30_000))
        assertEquals(8 to 5, SubAgentTimeBudget.remainingLabel(485_400))
        assertNull("nothing left reads as nothing, not 0s", SubAgentTimeBudget.remainingLabel(999))
    }

    @Test
    fun `long text is collapsed by lines or characters`() {
        assertFalse(SubAgentTextPreview.isLong("one\ntwo\nthree\nfour"))
        assertTrue(SubAgentTextPreview.isLong("1\n2\n3\n4\n5"))
        assertTrue(SubAgentTextPreview.isLong("x".repeat(221)))
    }

    @Test
    fun `clock label is 24-hour and locale-pinned`() {
        val label = subAgentClockLabel(0L)
        assertTrue(label, Regex("""\d{2}:\d{2}:\d{2}""").matches(label))
    }

    @Test
    fun `stat chips are compact - pinned line box, no font padding`() {
        // User report 2026-10-06: Android chips ~30dp tall vs ~21pt on iOS. The
        // height came from Compose's default line box, not the padding.
        val src = ProductionSources.read("ui/chat/SubAgentRunDetail.kt")
        val style = src.substring(src.indexOf("private fun chipTextStyle")).substringBefore("\n)")
        assertTrue(style.contains("includeFontPadding = false"))
        assertTrue(style.contains("lineHeight = (size + 2).sp"))
        assertTrue(style.contains("LineHeightStyle.Trim.Both"))
        val chip = src.substring(src.indexOf("private fun StatChip")).substringBefore("\n}")
        assertTrue("fixed height so CJK and Latin chips match", chip.contains(".height(STAT_CHIP_HEIGHT)"))
        assertTrue(src.contains("private val STAT_CHIP_HEIGHT = 20.dp"))
        assertTrue(chip.contains("style = chipTextStyle(11, mono = true)"))
    }

    @Test
    fun `detail sheet wires every new card and both stops share one path`() {
        val ui = ProductionSources.read("ui/chat/HelperUi.kt")
        val body = ui.substring(ui.indexOf("internal fun HelperDetailContent(")).substringBefore("// ─── Callback card")
        for (needle in listOf(
            "SubAgentRunDigest.from(", "SubAgentRunClock.read(", "SubAgentTaskArgs.parse(",
            "SubAgentStatChips(", "SubAgentTaskCard(", "SubAgentToolTimelineCard(",
            "SubAgentLatestOutputCard(", "SubAgentActionsCard(", "vm.loadSessionTokenStats()",
        )) assertTrue("HelperDetailContent uses $needle", body.contains(needle))
        // While the child streams, its tool blocks are only in the side-channel:
        // the digest must read the merged live view, not `messages` alone.
        assertTrue("digest reads the live overlay", body.contains("mergeStreamingOverlay(canonical, stream)"))
        assertFalse("not the canonical list alone", body.contains("val msgs = vm?.messages?.value.orEmpty()"))
        // Cold process: the child's session loads asynchronously; read after it is in.
        assertTrue("waits for the child session to load", body.indexOf("vm?.awaitSessionLoaded()") in 0 until body.indexOf("val msgs = vm?.let"))
        val screen = ProductionSources.read("ui/chat/ChatScreen.kt")
        assertTrue(screen.contains("stopSubAgentByUser(context, it, \"user-stop-block\")"))
        val detail = ProductionSources.read("ui/chat/SubAgentRunDetail.kt")
        assertTrue("a user steer is marked as the user's", detail.contains("\"(sent directly by the user) \$message\""))
        assertTrue("stop goes through the registry's sibling cancel", detail.contains("AgentJobRegistry.cancelSiblings(childSessionId, reason)"))
    }
}
