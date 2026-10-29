package com.openminis.app.ui.chat

import com.openminis.app.agent.jobs.AgentCallback
import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage

/**
 * [T-callback-queued-reminder] Tells a running parent loop that a sub agent it
 * delegated has finished and its result is waiting in the prompt queue.
 *
 * Why: a finished `<agent_callback>` is a PROGRAMMATIC prompt, so it waits for
 * the loop to end ([QueuedPromptBatching]) and is never slipped in at a tool
 * boundary. That delivery rule stays exactly as it is. But until the loop
 * ends the parent model cannot know a result is there, and keeps working
 * through a long plan the result might have changed. This adds one
 * `<system-reminder>` to the next model request, so the model can decide to
 * wrap up or carry on.
 *
 * Rules:
 *  - only finished sub agent callbacks still in the queue count: not scheduled
 *    fires, not user follow-ups, not progress reports, not other RPC sends;
 *  - one reminder per job ([QueuedPrompt.reminderSent]); a later reminder
 *    lists only jobs that arrived since;
 *  - the text goes into the REQUEST only. It is not written to agentHistory,
 *    not persisted and not shown;
 *  - a job is marked reminded once the request carrying it got an answer, and
 *    a retry of the same turn re-sends the same text. So a failed request
 *    neither loses the reminder nor sends a second one later.
 */
internal object QueuedCallbackReminder {

    /** One reminder: the queued prompts it covers and the text to send. */
    class Plan(val promptIds: List<String>, val text: String)

    /** Finished sub agent callbacks in [queue] the model has not been told about. */
    fun pending(queue: List<QueuedPrompt>): List<Pair<QueuedPrompt, AgentCallback>> =
        queue.mapNotNull { prompt ->
            if (prompt.reminderSent || prompt.isScheduledFire) return@mapNotNull null
            if (prompt.origin != QueuedPromptOrigin.PROGRAMMATIC) return@mapNotNull null
            val callback = AgentCallback.parse(prompt.text) ?: return@mapNotNull null
            if (callback.kind != AgentCallback.Kind.FINISHED) return@mapNotNull null
            prompt to callback
        }

    /** The reminder owed for [queue], or null when there is nothing new to say. */
    fun plan(queue: List<QueuedPrompt>): Plan? {
        val pending = pending(queue)
        if (pending.isEmpty()) return null
        return Plan(pending.map { it.first.id }, text(pending.map { it.second }))
    }

    fun text(callbacks: List<AgentCallback>): String {
        val jobs = callbacks.joinToString("; ") {
            "${it.title.ifBlank { "sub agent" }} (job ${it.jobId}, ${it.status})"
        }
        return "<system-reminder>\n" +
            "Background update: sub agent(s) you delegated have finished, and the result is queued for this conversation: $jobs\n" +
            "\n" +
            "It has NOT been delivered to you yet. It arrives as a new message only after your current turn ends, i.e. when you reply without calling more tools. Your call:\n" +
            "- If the result could change what you do next, or your remaining work is short or non-essential, wrap up now and end the turn with a brief summary. The result will then arrive.\n" +
            "- If your current work is independent of it and must finish first, keep going. The result stays queued and won't be lost.\n" +
            "\n" +
            "Don't poll for it, re-delegate, or mention this reminder to the user.\n" +
            "</system-reminder>"
    }

    /**
     * [messages] with [text] appended to the end of the last message, or null
     * when that message is not a user turn (nothing is sent then, and the jobs
     * stay unreminded for the next request).
     *
     * The message keeps its shape: the text joins the last text or tool_result
     * part (the same place the empty-turn nudge uses), so a tool_result stays a
     * tool_result and no new message or role is introduced. The input list and
     * its messages are not modified.
     */
    fun inject(messages: List<LLMMessage>, text: String): List<LLMMessage>? {
        val tail = messages.lastOrNull() ?: return null
        if (tail.role != LLMMessage.Role.USER) return null
        val suffix = "\n\n$text"
        val parts = tail.contentParts
        val newTail = when (val last = parts.lastOrNull()) {
            null -> tail.copy(content = tail.content + suffix)
            is AgentContentPart.Text -> tail.copy(contentParts = parts.dropLast(1) + AgentContentPart.Text(last.text + suffix))
            is AgentContentPart.ToolResult -> tail.copy(contentParts = parts.dropLast(1) + last.copy(content = last.content + suffix))
            else -> tail.copy(contentParts = parts + AgentContentPart.Text(text))
        }
        return messages.dropLast(1) + newTail
    }

    /** [queue] with the prompts of [plan] marked as reminded. */
    fun markSent(queue: List<QueuedPrompt>, plan: Plan): List<QueuedPrompt> {
        val ids = plan.promptIds.toSet()
        return queue.map { if (it.id in ids && !it.reminderSent) it.copy(reminderSent = true) else it }
    }
}
