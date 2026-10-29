package com.openminis.app.ui.chat

import com.openminis.app.ProductionSources
import com.openminis.app.agent.jobs.AgentCallback
import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-callback-queued-reminder] A finished sub agent callback waiting behind a
 * running loop is mentioned to the model once, in the request only.
 */
class QueuedCallbackReminderTest {

    private fun callback(job: String, title: String, kind: AgentCallback.Kind = AgentCallback.Kind.FINISHED, status: String = "done") =
        AgentCallback(kind = kind, jobId = job, childSessionId = "s-$job", title = title, status = status, body = "result of $job")

    private fun queued(
        id: String,
        text: String,
        origin: QueuedPromptOrigin = QueuedPromptOrigin.PROGRAMMATIC,
        scheduledTaskId: String? = null,
    ) = QueuedPrompt(id = id, text = text, origin = origin, scheduledTaskId = scheduledTaskId)

    private fun finished(id: String, job: String, title: String, status: String = "done") =
        queued(id, callback(job, title, status = status).xml)

    // -- the text --

    @Test
    fun `the reminder is the agreed wording with the jobs filled in`() {
        val text = QueuedCallbackReminder.text(listOf(callback("j1", "Audit the logs"), callback("j2", "Draft notes", status = "failed")))
        assertEquals(
            """
            <system-reminder>
            Background update: sub agent(s) you delegated have finished, and the result is queued for this conversation: Audit the logs (job j1, done); Draft notes (job j2, failed)

            It has NOT been delivered to you yet. It arrives as a new message only after your current turn ends, i.e. when you reply without calling more tools. Your call:
            - If the result could change what you do next, or your remaining work is short or non-essential, wrap up now and end the turn with a brief summary. The result will then arrive.
            - If your current work is independent of it and must finish first, keep going. The result stays queued and won't be lost.

            Don't poll for it, re-delegate, or mention this reminder to the user.
            </system-reminder>
            """.trimIndent(),
            text,
        )
    }

    @Test
    fun `title, job id and status come from the parsed callback`() {
        val plan = QueuedCallbackReminder.plan(listOf(finished("p1", "job-42", "Summarise \"Q3\" & more", status = "timeout")))!!
        assertTrue(plan.text, plan.text.contains("conversation: Summarise \"Q3\" & more (job job-42, timeout)\n"))
    }

    // -- which prompts count --

    @Test
    fun `nothing queued, nothing to say`() {
        assertNull(QueuedCallbackReminder.plan(emptyList()))
    }

    @Test
    fun `only finished sub agent callbacks count`() {
        val queue = listOf(
            queued("user", "how is it going?", origin = QueuedPromptOrigin.USER),
            // A user typing the tag by hand is still a user follow-up.
            queued("user-tag", callback("j0", "typed").xml, origin = QueuedPromptOrigin.USER),
            queued("progress", callback("j1", "still going", kind = AgentCallback.Kind.PROGRESS, status = "running").xml),
            queued("scheduled", "<scheduled_task id=\"t1\">check</scheduled_task>", scheduledTaskId = "t1"),
            queued("scheduled-cb", callback("j2", "fired").xml, scheduledTaskId = "t2"),
            queued("rpc", "please also update the README"),
        )
        assertNull(QueuedCallbackReminder.plan(queue))

        val plan = QueuedCallbackReminder.plan(queue + finished("done", "j9", "Real one"))!!
        assertEquals(listOf("done"), plan.promptIds)
        assertTrue(plan.text.contains(": Real one (job j9, done)\n"))
    }

    // -- once per job --

    @Test
    fun `a job is reminded once, and a later reminder lists only the new jobs`() {
        var queue = listOf(finished("p1", "j1", "First"))
        val first = QueuedCallbackReminder.plan(queue)!!
        assertEquals(listOf("p1"), first.promptIds)

        queue = QueuedCallbackReminder.markSent(queue, first)
        assertTrue(queue.single().reminderSent)
        assertNull("every later request stays clean", QueuedCallbackReminder.plan(queue))
        assertNull(QueuedCallbackReminder.plan(queue))

        queue = queue + finished("p2", "j2", "Second")
        val second = QueuedCallbackReminder.plan(queue)!!
        assertEquals(listOf("p2"), second.promptIds)
        assertTrue(second.text.contains(": Second (job j2, done)\n"))
        assertFalse("the one already mentioned is not listed again", second.text.contains("First"))
    }

    @Test
    fun `a request that never got an answer does not use the reminder up`() {
        val queue = listOf(finished("p1", "j1", "First"))
        val attempt1 = QueuedCallbackReminder.plan(queue)!!
        // Nothing was marked: the next attempt owes the same reminder.
        val attempt2 = QueuedCallbackReminder.plan(queue)!!
        assertEquals(attempt1.promptIds, attempt2.promptIds)
        assertEquals(attempt1.text, attempt2.text)
    }

    @Test
    fun `marking touches only the prompts of that reminder, and only the flag`() {
        val queue = listOf(
            queued("user", "hi", origin = QueuedPromptOrigin.USER),
            finished("p1", "j1", "First"),
            finished("p2", "j2", "Second"),
        )
        val plan = QueuedCallbackReminder.plan(listOf(queue[1]))!!
        val marked = QueuedCallbackReminder.markSent(queue, plan)
        assertSame(queue[0], marked[0])
        assertEquals(queue[1].copy(reminderSent = true), marked[1])
        assertSame(queue[2], marked[2])
        assertEquals("delivery order and content are untouched", queue.map { it.id to it.text }, marked.map { it.id to it.text })
    }

    @Test
    fun `a delivered callback is no longer in the queue, so it is not listed`() {
        val queue = listOf(finished("p1", "j1", "First"), finished("p2", "j2", "Second"))
        val afterDelivery = queue.filterNot { it.id == "p1" }
        assertEquals(listOf("p2"), QueuedCallbackReminder.plan(afterDelivery)!!.promptIds)
    }

    @Test
    fun `the reminder does not change what is delivered or when`() {
        val queue = QueuedCallbackReminder.markSent(
            listOf(finished("p1", "j1", "First")),
            QueuedCallbackReminder.plan(listOf(finished("p1", "j1", "First")))!!,
        )
        assertEquals("still held at a tool boundary", emptyList<QueuedPrompt>(), QueuedPromptBatching.nextInsertBatch(queue))
        assertEquals("still drained when the loop ends", listOf("p1"), QueuedPromptBatching.nextDrainBatch(queue).map { it.id })
    }

    // -- where it goes in the request --

    private val text = "<system-reminder>\nX\n</system-reminder>"

    private fun assistantCall(id: String) = LLMMessage(
        role = LLMMessage.Role.ASSISTANT, content = "",
        contentParts = listOf(AgentContentPart.ToolUse(id, "shell_execute", org.json.JSONObject())),
    )

    @Test
    fun `after tool results it joins the last tool_result, which stays a tool_result`() {
        val history = listOf(
            LLMMessage(LLMMessage.Role.USER, "go", contentParts = listOf(AgentContentPart.Text("go"))),
            LLMMessage(
                LLMMessage.Role.ASSISTANT, "",
                contentParts = listOf(
                    AgentContentPart.ToolUse("c1", "shell_execute", org.json.JSONObject()),
                    AgentContentPart.ToolUse("c2", "shell_execute", org.json.JSONObject()),
                ),
            ),
            LLMMessage(
                LLMMessage.Role.USER, "",
                contentParts = listOf(
                    AgentContentPart.ToolResult("c1", "shell_execute", "one"),
                    AgentContentPart.ToolResult("c2", "shell_execute", "two"),
                ),
            ),
        )
        val out = QueuedCallbackReminder.inject(history, text)!!
        assertEquals("no message added", history.size, out.size)
        assertSame(history[0], out[0])
        assertSame(history[1], out[1])
        val parts = out.last().contentParts
        assertEquals(2, parts.size)
        assertEquals(AgentContentPart.ToolResult("c1", "shell_execute", "one"), parts[0])
        val last = parts[1] as AgentContentPart.ToolResult
        assertEquals("c2", last.id)
        assertEquals("two\n\n$text", last.content)
        assertEquals(LLMMessage.Role.USER, out.last().role)
    }

    @Test
    fun `after a user message it joins that message's text`() {
        val parts = listOf(LLMMessage(LLMMessage.Role.USER, "hello", contentParts = listOf(AgentContentPart.Text("hello"))))
        assertEquals(
            listOf(AgentContentPart.Text("hello\n\n$text")),
            QueuedCallbackReminder.inject(parts, text)!!.single().contentParts,
        )
        val plain = listOf(LLMMessage(LLMMessage.Role.USER, "hello"))
        assertEquals("hello\n\n$text", QueuedCallbackReminder.inject(plain, text)!!.single().content)
    }

    @Test
    fun `it is never attached to an assistant message`() {
        assertNull(QueuedCallbackReminder.inject(emptyList(), text))
        assertNull(QueuedCallbackReminder.inject(listOf(LLMMessage(LLMMessage.Role.USER, "go"), assistantCall("c1")), text))
    }

    @Test
    fun `the history it was built from is left as it was`() {
        val tail = LLMMessage(LLMMessage.Role.USER, "", contentParts = listOf(AgentContentPart.ToolResult("c1", "shell_execute", "one")))
        val history = mutableListOf(LLMMessage(LLMMessage.Role.USER, "go"), assistantCall("c1"), tail)
        val snapshot = history.toList()
        val out = QueuedCallbackReminder.inject(history, text)
        assertNotNull(out)
        assertEquals(snapshot, history)
        assertSame(tail, history.last())
        assertEquals("one", (history.last().contentParts.single() as AgentContentPart.ToolResult).content)
    }

    // -- wiring --

    private val vm = ProductionSources.read("ui/chat/ChatViewModel.kt")

    @Test
    fun `the loop puts it on the provider's copy only, and marks jobs when the answer starts`() {
        assertTrue(vm.contains("var requestMessages = requestContext.messages"))
        assertTrue(vm.contains("QueuedCallbackReminder.inject(requestMessages, it.text)"))
        assertTrue(Regex("""currentProvider\.streamMessage\(\s*requestMessages,""").containsMatchIn(vm))
        assertFalse("the built request is no longer sent as is", Regex("""currentProvider\.streamMessage\(\s*requestContext\.messages,""").containsMatchIn(vm))
        // Kept for the turn's retries; marked on the first chunk, not at send.
        assertTrue(vm.contains("val reminder = callbackReminder ?: QueuedCallbackReminder.plan(_promptQueue.value)"))
        val firstChunk = vm.indexOf("if (firstChunkSeen.compareAndSet(false, true)) {")
        val mark = vm.indexOf("_promptQueue.update { QueuedCallbackReminder.markSent(it, sent) }")
        assertTrue(firstChunk >= 0 && mark > firstChunk && mark - firstChunk < 1100)
        assertEquals("marked in one place", 1, Regex("""QueuedCallbackReminder\.markSent\(""").findAll(vm).count())
        // Nothing in this feature writes to the history.
        val helper = ProductionSources.read("ui/chat/QueuedCallbackReminder.kt")
        assertFalse(helper.substringAfter("internal object").contains("agentHistory"))
    }
}
