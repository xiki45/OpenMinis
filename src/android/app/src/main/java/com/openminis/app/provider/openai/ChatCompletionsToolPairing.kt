package com.openminis.app.provider.openai

import org.json.JSONArray
import org.json.JSONObject

/**
 * [T-android-chat-tool-adjacency] (#382 / #352) Last gate before a Chat
 * Completions request goes out: every assistant `tool_calls` block is followed
 * at once by exactly one `role:"tool"` reply per call, and no `tool` message
 * stands anywhere else. Port of iOS `sanitizeToolCallAdjacency`
 * (OpenAIAgentProvider.swift), the counterpart of [ResponsesToolPairing].
 *
 * Why it is needed: the three earlier layers all pair by ID, none by POSITION.
 *  - ChatViewModel.sanitizeAgentHistory checks only "the next message answers
 *    this call": when it does not, it injects an "interrupted" result right
 *    after the call, and keeps the real result wherever it was, because its id
 *    still exists somewhere in the history;
 *  - RequestContextBuilder.dropOrphanedToolParts pairs over id sets;
 *  - globallyDedupeToolCallIds only renames repeated ids.
 * So a history whose ids all match but whose results are not adjacent, or are
 * duplicated, went out as built. DeepSeek / OpenAI answer
 *     tool calls and tool results do not match … / No tool output found …
 * and, because the request is rebuilt from the same history every time, the
 * 400 repeats on every retry until the chat is cleared. Seen in synced /
 * imported sessions: a user message stored between the calls and their
 * results, or two consecutive tool-call turns with the first one's result
 * after the second.
 *
 * Repair, as iOS does it (two passes, by id):
 *  1. take every `tool` reply out of the array, grouped by `tool_call_id`;
 *  2. re-emit the other messages in their order, and right after each
 *     assistant `tool_calls` block put one reply per call, in call order:
 *     - the reply found for that id (it MOVES there, wherever it was);
 *     - of several replies for one id, the first REAL one wins. A repair
 *       placeholder ([SYNTHETIC_REPLIES]) loses to a real result: the history
 *       repair above puts its "interrupted" result BEFORE the real one, and
 *       first-wins alone would send the fake and drop the tool's output;
 *     - no reply at all: a placeholder ([PLACEHOLDER_REPLY]);
 *  3. replies left over are dropped: duplicates, and replies no call claims.
 *
 * Nothing else moves: user / assistant / system messages, and the image
 * carriers that follow a tool run, keep their order.
 *
 * An array that is already well formed is returned as is (same object, same
 * bytes), so healthy chats keep their prompt-cache prefix.
 *
 * Runs after globallyDedupeToolCallIds, which makes every non-empty call id
 * unique in the request, so "the reply for this id" is unambiguous.
 */
internal object ChatCompletionsToolPairing {

    /** The same wording as iOS and the Responses path; one constant for both. */
    const val PLACEHOLDER_REPLY = ResponsesToolPairing.PLACEHOLDER_OUTPUT

    /**
     * What ChatViewModel's history repair writes for a call it finds
     * unanswered. Kept in step with it by ChatCompletionsToolPairingTest.
     */
    const val HISTORY_REPAIR_REPLY = "Tool execution was interrupted by an unexpected error."

    /** Replies that stand in for a missing result; a real result beats them. */
    val SYNTHETIC_REPLIES: Set<String> = setOf(PLACEHOLDER_REPLY, HISTORY_REPAIR_REPLY)

    class Result(
        val messages: JSONArray,
        /** Call ids whose reply was not right after its call and was moved there. */
        val movedReplies: List<String>,
        /** Ids of extra replies for a call that already had one. */
        val droppedDuplicateReplies: List<String>,
        /** Ids of replies no assistant tool_calls claims. */
        val droppedOrphanReplies: List<String>,
        /** Call ids that got a placeholder reply. */
        val placeholderCalls: List<String>,
    ) {
        val changed: Boolean
            get() = movedReplies.isNotEmpty() || droppedDuplicateReplies.isNotEmpty() ||
                droppedOrphanReplies.isNotEmpty() || placeholderCalls.isNotEmpty()
    }

    fun sanitize(messages: JSONArray): Result {
        if (isWellFormed(messages)) return Result(messages, emptyList(), emptyList(), emptyList(), emptyList())

        // Pass 1: every tool reply, by id, in the order it stands in.
        val replies = LinkedHashMap<String, ArrayList<Pair<Int, JSONObject>>>()
        for (i in 0 until messages.length()) {
            val msg = messages.optJSONObject(i) ?: continue
            if (msg.optString("role") != "tool") continue
            replies.getOrPut(msg.optString("tool_call_id")) { ArrayList() }.add(i to msg)
        }

        val out = JSONArray()
        val moved = mutableListOf<String>()
        val placeholders = mutableListOf<String>()
        val claimedIds = HashSet<String>()
        // Pass 2: the rest in order, each tool_calls block followed by its replies.
        for (i in 0 until messages.length()) {
            val msg = messages.optJSONObject(i)
            if (msg == null) {
                out.put(messages.get(i))
                continue
            }
            if (msg.optString("role") == "tool") continue
            out.put(msg)
            val calls = toolCallIds(msg) ?: continue
            val adjacent = adjacentReplyRange(messages, i)
            for (id in calls) {
                claimedIds.add(id)
                val candidates = replies[id]
                val pick = candidates?.firstOrNull { it.second.optString("content") !in SYNTHETIC_REPLIES }
                    ?: candidates?.firstOrNull()
                if (pick == null) {
                    placeholders += id
                    out.put(JSONObject().apply {
                        put("role", "tool")
                        put("tool_call_id", id)
                        put("content", PLACEHOLDER_REPLY)
                    })
                    continue
                }
                candidates!!.remove(pick)
                if (pick.first !in adjacent) moved += id
                out.put(pick.second)
            }
        }

        val duplicates = mutableListOf<String>()
        val orphans = mutableListOf<String>()
        for ((id, left) in replies) {
            for (unused in left) if (id in claimedIds) duplicates += id else orphans += id
        }
        return Result(out, moved, duplicates, orphans, placeholders)
    }

    /** The ids of a message's tool calls, or null when it is not an assistant tool_calls block. */
    private fun toolCallIds(msg: JSONObject): List<String>? {
        if (msg.optString("role") != "assistant") return null
        val calls = msg.optJSONArray("tool_calls") ?: return null
        if (calls.length() == 0) return null
        return (0 until calls.length()).map { calls.optJSONObject(it)?.optString("id") ?: "" }
    }

    /** Indices of the run of `tool` messages right after the message at [index]. */
    private fun adjacentReplyRange(messages: JSONArray, index: Int): IntRange {
        var end = index
        while (end + 1 < messages.length() && messages.optJSONObject(end + 1)?.optString("role") == "tool") end++
        return (index + 1)..end
    }

    /**
     * Every tool_calls block is followed at once by exactly its replies (one
     * per call, any order), and there is no other `tool` message.
     */
    private fun isWellFormed(messages: JSONArray): Boolean {
        var i = 0
        while (i < messages.length()) {
            val msg = messages.optJSONObject(i)
            if (msg == null) { i++; continue }
            if (msg.optString("role") == "tool") return false
            val calls = toolCallIds(msg)
            if (calls == null) { i++; continue }
            val run = adjacentReplyRange(messages, i)
            val replyIds = run.map { messages.optJSONObject(it)?.optString("tool_call_id") ?: "" }
            if (replyIds.size != calls.size || replyIds.sorted() != calls.sorted()) return false
            i = run.last + 1
        }
        return true
    }
}
