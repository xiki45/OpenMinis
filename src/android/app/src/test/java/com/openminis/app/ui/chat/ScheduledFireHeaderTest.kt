package com.openminis.app.ui.chat

import com.openminis.app.agent.jobs.AgentCallback
import com.openminis.app.agent.jobs.HelperModelTier
import com.openminis.app.agent.jobs.HelperRunner
import com.openminis.app.scheduled.ScheduledTaskMarker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-sched-fire-header] One avatar + name header per run of assistant
 * messages, as on iOS: only a REAL user turn restarts it. A scheduled-task
 * fire (a system-written user message rendered as a card) must not, and
 * neither may the sub-agent / tool cards inside assistant messages.
 */
class ScheduledFireHeaderTest {

    private fun user(id: String, text: String) = ChatMessage(id = id, role = "user", content = text)
    private fun assistant(id: String, vararg blocks: AssistantBlock) =
        ChatMessage(id = id, role = "assistant", content = "", toolBlocks = blocks.toList())
    private fun text(id: String, s: String) = AssistantBlock(id = id, kind = "text", content = s)
    private fun fire(id: String) = user(id, ScheduledTaskMarker("task-1", "t2-sub", null, "Say: T2 scheduled ping").xml)
    private fun headers(msgs: List<ChatMessage>) =
        buildFlatChatItems(msgs).filterIsInstance<FlatChatItem.AssistantHeader>().map { it.messageId }

    @Test
    fun `the reply to a scheduled fire does not get a second header`() {
        val items = buildFlatChatItems(listOf(
            user("u1", "create a task that fires in a minute"),
            assistant("a1", text("t1", "created, it fires in 1 minute")),
            fire("f1"),
            assistant("a2", text("t2", "T2 scheduled ping")),
        ))
        assertEquals(1, items.filterIsInstance<FlatChatItem.ScheduledTaskCardItem>().size)
        assertEquals(listOf("a1"), items.filterIsInstance<FlatChatItem.AssistantHeader>().map { it.messageId })
    }

    @Test
    fun `several fires in a row, with sub-agent and tool cards between, still share one header`() {
        val agentBlock = AssistantBlock(
            id = "sa", kind = "tool_use", toolName = HelperRunner.TOOL_NAME, toolTitle = "Survey",
            content = HelperRunner.resultJson("completed", "r", "m", HelperModelTier.PRIMARY, "primary", 1, 1000, "C", "J"),
            toolStatus = ToolBlockStatus.SUCCESS,
        )
        val toolBlock = AssistantBlock(id = "sh", kind = "tool_use", toolName = "shell_execute", content = "{}", toolStatus = ToolBlockStatus.SUCCESS)
        assertEquals(
            listOf("a1"),
            headers(listOf(
                user("u1", "go"),
                assistant("a1", agentBlock, text("t1", "delegated")),
                fire("f1"),
                assistant("a2", toolBlock, text("t2", "polled")),
                fire("f2"),
                assistant("a3", text("t3", "polled again")),
                user("cb", AgentCallback(AgentCallback.Kind.FINISHED, "J", "C", "t", "done", body = "r").xml),
                assistant("a4", text("t4", "sub agent done")),
            )),
        )
    }

    @Test
    fun `a fire that opens the transcript still gets a header, and a real user turn restarts it`() {
        assertEquals(listOf("a1"), headers(listOf(fire("f1"), assistant("a1", text("t1", "ping")))))
        assertEquals(
            listOf("a1", "a3"),
            headers(listOf(
                user("u1", "hi"),
                assistant("a1", text("t1", "x")),
                fire("f1"),
                assistant("a2", text("t2", "y")),
                user("u3", "thanks, and now?"),
                assistant("a3", text("t3", "z")),
            )),
        )
    }

    @Test
    fun `the rule treats only system-written user messages as non-turns`() {
        assertTrue(AssistantHeaderRule.isSystemWrittenTurn(fire("f")))
        assertTrue(AssistantHeaderRule.isSystemWrittenTurn(user("c", AgentCallback(AgentCallback.Kind.PROGRESS, "J", "C", "t", "running", body = "w").xml)))
        assertFalse(AssistantHeaderRule.isSystemWrittenTurn(user("u", "hello")))
        // Text a user typed that merely LOOKS like the tag is not a fire (no id).
        assertFalse(AssistantHeaderRule.isSystemWrittenTurn(user("u", "<scheduled_task> hi")))
    }
}
