package com.openminis.app.ui.chat

import com.openminis.app.ProductionSources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-thinking-row-follows-run] User report 2026-10-07: the chat said
 * "… is thinking" while the composer showed SEND and no background capsule
 * existed. The log shows a model switch during an auto-retry: the job was
 * cancelled, the run state and the activity tracker were cleared, and the
 * placeholder bubble kept its "running" flags.
 */
class ThinkingRowSettleTest {

    private fun user(id: String) = ChatMessage(id = id, role = "user", content = "hi")
    private fun assistant(id: String, content: String = "", streaming: Boolean = false, awaiting: Boolean = false, blocks: List<AssistantBlock> = emptyList()) =
        ChatMessage(id = id, role = "assistant", content = content, isStreaming = streaming, isAwaitingModelResponse = awaiting, toolBlocks = blocks)

    private fun typingRows(messages: List<ChatMessage>) =
        buildFlatChatItems(messages).filterIsInstance<FlatChatItem.AssistantTyping>()

    @Test
    fun `the placeholder of a cancelled attempt shows the thinking row until it is settled`() {
        // What the loop leaves behind when its job is cancelled before the first chunk.
        val left = listOf(user("u1"), assistant("a1", content = "done"), user("u2"), assistant("a2", streaming = true, awaiting = true))
        assertEquals("this is the row in the screenshot", listOf("a2"), typingRows(left).map { it.messageId })

        val settled = ThinkingRowSettle.settled(left)!!
        assertEquals(emptyList<FlatChatItem.AssistantTyping>(), typingRows(settled))
        assertFalse(settled.any { it.isStreaming || it.isAwaitingModelResponse })
    }

    @Test
    fun `a bubble with tool calls that was waiting for the next response is settled too`() {
        val tool = AssistantBlock(id = "t1", kind = "tool_use", content = "", toolName = "shell_execute", toolStatus = ToolBlockStatus.SUCCESS)
        val left = listOf(user("u1"), assistant("a1", streaming = true, awaiting = true, blocks = listOf(tool)))
        assertEquals(1, typingRows(left).size)
        val settled = ThinkingRowSettle.settled(left)!!
        assertEquals(0, typingRows(settled).size)
        assertEquals("the tool call is untouched", listOf(tool), settled[1].toolBlocks)
    }

    @Test
    fun `every stale bubble is settled, not only the last one`() {
        val left = listOf(user("u1"), assistant("a1", streaming = true, awaiting = true), user("u2"), assistant("a2", streaming = true))
        val settled = ThinkingRowSettle.settled(left)!!
        assertFalse(settled.any { it.isStreaming || it.isAwaitingModelResponse })
        assertEquals(0, typingRows(settled).size)
    }

    @Test
    fun `nothing stale, nothing published - and only the two flags ever change`() {
        val clean = listOf(user("u1"), assistant("a1", content = "answer"))
        assertNull(ThinkingRowSettle.settled(clean))
        assertNull(ThinkingRowSettle.settled(emptyList()))

        val withError = assistant("a2", content = "partial", streaming = true).copy(error = "boom")
        val firstUser = user("u1")
        val settled = ThinkingRowSettle.settled(listOf(firstUser, withError))!!
        assertSame("untouched messages are the same objects", firstUser, settled[0])
        assertEquals(withError.copy(isStreaming = false, isAwaitingModelResponse = false), settled[1])
        assertEquals("partial", settled[1].content)
        assertEquals("boom", settled[1].error)
    }

    // -- wiring --

    private val vm = ProductionSources.read("ui/chat/ChatViewModel.kt")

    @Test
    fun `the thinking row is settled from the run state on every exit, and at once on a model switch`() {
        // The safety net: any transition to "not streaming".
        assertTrue(vm.contains("isStreaming.collect { streaming ->\n                if (!streaming) settleThinkingRows(\"run-ended\")"))
        // Never while a run is in progress.
        val settle = vm.substringAfter("private fun settleThinkingRows(site: String) {").substringBefore("\n    }\n")
        assertTrue(settle.trimStart().startsWith("if (_isStreaming.value) return"))
        assertTrue("pending streamed text is flushed first", settle.indexOf("flushAllStreamingDeltas()") < settle.indexOf("ThinkingRowSettle.settled(_messages.value)"))
        // The path of the report: a model switch that cancels the retry ladder.
        val seam = vm.substringAfter("private fun cancelWorkBoundToPreviousModel(reason: String) {").substringBefore("private fun isBoundToAbandonedModel")
        val stop = seam.indexOf("_isStreaming.value = false")
        val settled = seam.indexOf("settleThinkingRows(\"switch-model\")")
        assertTrue("after the run state is cleared", stop in 1 until settled)
        assertTrue("it still does not pause the conversation the way Stop does", !seam.contains("\n        handleUserCancelledCleanup()"))
    }

    @Test
    fun `what each of the three indicators reads`() {
        // Thinking row: the bubble's own flags.
        assertTrue(ProductionSources.read("ui/chat/ChatFlatItems.kt")
            .contains("if (message.isStreaming && (!hasVisibleContent || message.isAwaitingModelResponse)) {"))
        // Background capsule: the activity tracker, cleared by the same seam.
        val seam = vm.substringAfter("private fun cancelWorkBoundToPreviousModel(reason: String) {").substringBefore("private fun isBoundToAbandonedModel")
        assertTrue(seam.contains("SessionActivityTracker.setInactive(activeSessionId)"))
    }
}
