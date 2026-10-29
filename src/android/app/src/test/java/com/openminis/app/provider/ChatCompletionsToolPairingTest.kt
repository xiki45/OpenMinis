package com.openminis.app.provider

import com.openminis.app.IosSources
import com.openminis.app.ProductionSources
import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMModel
import com.openminis.app.provider.openai.ChatCompletionsToolPairing
import com.openminis.app.provider.openai.OpenAIProvider
import com.openminis.app.provider.openai.ResponsesToolPairing
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-chat-tool-adjacency] #382 / #352: a Chat Completions request whose
 * tool results all had a matching call id, but were not right after their call
 * or were duplicated, went out as built; the provider answered 400 and the chat
 * was stuck on it. Reproduced on a Pixel 4a with two shapes cloned from real
 * (synced / imported) sessions, shapes 7a and 7b.
 */
class ChatCompletionsToolPairingTest {

    private val interrupted = ChatCompletionsToolPairing.HISTORY_REPAIR_REPLY

    // ── Wire-level helpers ─────────────────────────────────────────────────

    private fun user(text: String) = JSONObject().put("role", "user").put("content", text)
    private fun asst(text: String, vararg callIds: String) = JSONObject().put("role", "assistant").put("content", text).apply {
        if (callIds.isNotEmpty()) put("tool_calls", JSONArray(callIds.map {
            JSONObject().put("id", it).put("type", "function").put("function", JSONObject().put("name", "shell_execute").put("arguments", "{}"))
        }))
    }
    private fun tool(id: String, content: String) = JSONObject().put("role", "tool").put("tool_call_id", id).put("content", content)
    private fun arr(vararg m: JSONObject) = JSONArray(m.toList())

    /** `U A*(c0,c1) T:c0=a …`, for reading a whole array at a glance. */
    private fun shape(a: JSONArray) = (0 until a.length()).joinToString(" ") { i ->
        val m = a.getJSONObject(i)
        when (m.getString("role")) {
            "tool" -> "T:${m.getString("tool_call_id")}=${m.getString("content").let { if (it == interrupted) "INT" else if (it == ChatCompletionsToolPairing.PLACEHOLDER_REPLY) "PH" else it }}"
            "assistant" -> m.optJSONArray("tool_calls")?.let { c -> "A*(" + (0 until c.length()).joinToString(",") { c.getJSONObject(it).getString("id") } + ")" } ?: "A"
            "user" -> "U"
            else -> m.getString("role")
        }
    }

    /**
     * The provider's rule, as the strict mock in the repro enforced it: each
     * tool_calls block is followed at once by exactly one reply per call, and
     * no other tool message exists. Returns the first violation, or null.
     */
    private fun violation(a: JSONArray): String? {
        var i = 0
        while (i < a.length()) {
            val m = a.getJSONObject(i)
            if (m.getString("role") == "tool") return "messages[$i] tool reply ${m.optString("tool_call_id")} has no preceding assistant tool_calls"
            val calls = m.optJSONArray("tool_calls")
            if (m.getString("role") != "assistant" || calls == null || calls.length() == 0) { i++; continue }
            val want = (0 until calls.length()).map { calls.getJSONObject(it).getString("id") }.toMutableList()
            var j = i + 1
            while (j < a.length() && a.getJSONObject(j).getString("role") == "tool") {
                val id = a.getJSONObject(j).getString("tool_call_id")
                if (!want.remove(id)) return "messages[$j] tool reply $id does not answer the preceding tool_calls"
                j++
            }
            if (want.isNotEmpty()) return "messages[$i] tool_calls $want have no reply"
            i = j
        }
        return null
    }

    private fun sanitize(a: JSONArray) = ChatCompletionsToolPairing.sanitize(a)

    // ── The two reproduced shapes ──────────────────────────────────────────

    @Test fun `7a - a user message stored between the calls and their results`() {
        // What went on the wire (request 0028): the history repair had put
        // "interrupted" results after the calls, and the real ones stayed below
        // the interjected user message.
        val wire = arr(
            user("please run two commands"), asst("Running.", "c0", "c1"),
            tool("c0", interrupted), tool("c1", interrupted),
            user("interjected while the tools were running"),
            tool("c0", "a"), tool("c1", "b"),
            asst("Both commands are done."), user("follow-up"),
        )
        assertEquals("the repro's 400", "messages[5] tool reply c0 has no preceding assistant tool_calls", violation(wire))
        val r = sanitize(wire)
        assertEquals(null, violation(r.messages))
        assertEquals("the REAL results are the ones kept, right after the calls",
            "U A*(c0,c1) T:c0=a T:c1=b U A U", shape(r.messages))
        assertEquals(listOf("c0", "c1"), r.movedReplies)
        assertEquals("the two repair placeholders", listOf("c0", "c1"), r.droppedDuplicateReplies)
        assertTrue(r.droppedOrphanReplies.isEmpty() && r.placeholderCalls.isEmpty())
        assertTrue(r.changed)
    }

    @Test fun `7a without the history repair - the results simply move up`() {
        val raw = arr(user("go"), asst("Running.", "c0", "c1"), user("interjected"), tool("c0", "a"), tool("c1", "b"), asst("Done."), user("next"))
        val r = sanitize(raw)
        assertEquals("U A*(c0,c1) T:c0=a T:c1=b U A U", shape(r.messages))
        assertEquals(null, violation(r.messages))
        assertTrue(r.droppedDuplicateReplies.isEmpty())
    }

    @Test fun `7b - two consecutive tool-call turns, the first one's result after the second`() {
        // Request 0029: A*(a) [INT a] A*(b) [a] [INT b].
        val wire = arr(
            user("go"), asst("", "a"), tool("a", interrupted), asst("", "b"),
            tool("a", "a"), tool("b", interrupted), asst("Done."), user("next"),
        )
        assertEquals("messages[4] tool reply a does not answer the preceding tool_calls", violation(wire))
        val r = sanitize(wire)
        assertEquals(null, violation(r.messages))
        assertEquals("a's real result goes back to a; b, which never had one, keeps its placeholder",
            "U A*(a) T:a=a A*(b) T:b=INT A U", shape(r.messages))
        assertEquals(listOf("a"), r.movedReplies)
        assertEquals(listOf("a"), r.droppedDuplicateReplies)
    }

    // ── The repair rules, one at a time (iOS sanitizeToolCallAdjacency) ────

    @Test fun `a reply no call claims is dropped`() {
        val r = sanitize(arr(user("hi"), tool("ghost", "x"), asst("ok")))
        assertEquals("U A", shape(r.messages))
        assertEquals(listOf("ghost"), r.droppedOrphanReplies)
    }

    @Test fun `a call with no reply gets the placeholder, in call order`() {
        val r = sanitize(arr(user("go"), asst("", "c0", "c1", "c2"), tool("c1", "one"), asst("Done.")))
        assertEquals("U A*(c0,c1,c2) T:c0=PH T:c1=one T:c2=PH A", shape(r.messages))
        assertEquals(listOf("c0", "c2"), r.placeholderCalls)
        assertEquals(null, violation(r.messages))
    }

    @Test fun `of two real replies for one call the first wins`() {
        val r = sanitize(arr(user("go"), asst("", "c0"), tool("c0", "first"), tool("c0", "second"), asst("Done.")))
        assertEquals("U A*(c0) T:c0=first A", shape(r.messages))
        assertEquals(listOf("c0"), r.droppedDuplicateReplies)
        assertTrue("it was already in place", r.movedReplies.isEmpty())
    }

    @Test fun `a real result after a placeholder beats it, whichever placeholder it is`() {
        for (ph in ChatCompletionsToolPairing.SYNTHETIC_REPLIES) {
            val r = sanitize(arr(user("go"), asst("", "c0"), tool("c0", ph), tool("c0", "real"), asst("Done.")))
            assertEquals("U A*(c0) T:c0=real A", shape(r.messages))
        }
        // Two placeholders and nothing real: one stays.
        val r = sanitize(arr(user("go"), asst("", "c0"), tool("c0", interrupted), tool("c0", interrupted)))
        assertEquals("U A*(c0) T:c0=INT", shape(r.messages))
    }

    @Test fun `a result stored before its call moves after it`() {
        val r = sanitize(arr(user("go"), tool("c0", "early"), asst("", "c0"), asst("Done.")))
        assertEquals("U A*(c0) T:c0=early A", shape(r.messages))
        assertEquals(listOf("c0"), r.movedReplies)
    }

    @Test fun `only tool replies move - every other message keeps its place`() {
        val carrier = JSONObject().put("role", "user").put("content", JSONArray().put(JSONObject().put("type", "text").put("text", "[Image returned by read_image (tool_call_id: c1)]")))
        val wire = arr(
            JSONObject().put("role", "system").put("content", "sys"),
            user("u1"), asst("", "c0", "c1"), tool("c0", "x"), carrier, tool("c1", "y"), user("u2"), asst("a2"),
        )
        val r = sanitize(wire)
        assertEquals("system U A*(c0,c1) T:c0=x T:c1=y U U A", shape(r.messages))
        assertSame("the carrier object itself, after the whole run", carrier, r.messages.get(5))
        assertEquals(null, violation(r.messages))
    }

    // ── Well-formed input is not touched ───────────────────────────────────

    @Test fun `healthy sequences come back as the same array`() {
        val cases = listOf(
            arr(user("hi"), asst("hello")),
            arr(user("go"), asst("", "c0"), tool("c0", "ok"), asst("Done.")),
            // Parallel calls, all answered, replies in another order than the calls.
            arr(user("go"), asst("", "c0", "c1", "c2"), tool("c2", "z"), tool("c0", "x"), tool("c1", "y"), asst("Done."), user("next")),
            // Several rounds.
            arr(user("go"), asst("", "a"), tool("a", "1"), asst("", "b"), tool("b", "2"), asst("Done.")),
            // A trailing orphan the history repair already answered.
            arr(user("go"), asst("", "c0"), tool("c0", interrupted)),
            // Mid-round: the request ends on the tool results.
            arr(user("go"), asst("", "c0", "c1"), tool("c0", "x"), tool("c1", "y")),
        )
        for (c in cases) {
            val before = c.toString()
            val r = sanitize(c)
            assertSame(shape(c), c, r.messages)
            assertFalse(r.changed)
            assertEquals("byte for byte", before, r.messages.toString())
            assertEquals(null, violation(c))
        }
    }

    @Test fun `repairing is stable - a second pass changes nothing`() {
        val wire = arr(user("go"), asst("", "c0", "c1"), tool("c0", interrupted), tool("c1", interrupted), user("x"), tool("c0", "a"), tool("c1", "b"), asst("Done."))
        val once = sanitize(wire)
        assertNotSame(wire, once.messages)
        val twice = sanitize(once.messages)
        assertSame(once.messages, twice.messages)
        assertFalse(twice.changed)
        // The same broken history builds the same bytes every time (prompt cache).
        assertEquals(once.messages.toString(), sanitize(JSONArray(wire.toString())).messages.toString())
    }

    // ── Through the real request builder ───────────────────────────────────

    private val model = LLMModel(id = "deepseek-test", displayName = "Test", provider = "test", inputModalities = listOf("text"))
    private fun provider() = OpenAIProvider(apiKey = "test-key", model = model, basePath = "https://example.invalid/v1", useResponsesAPI = false)
    private fun body(history: List<LLMMessage>): JSONArray =
        provider().buildRequestBody(history, systemPrompt = "sys", maxTokens = 256, stream = true, temperature = null, imageParts = emptyList())
            .getJSONArray("messages")

    private fun u(text: String) = LLMMessage(LLMMessage.Role.USER, text, contentParts = listOf(AgentContentPart.Text(text)))
    private fun a(text: String, vararg ids: String) = LLMMessage(
        LLMMessage.Role.ASSISTANT, text,
        contentParts = listOfNotNull(text.takeIf { it.isNotEmpty() }?.let { AgentContentPart.Text(it) }) +
            ids.map { AgentContentPart.ToolUse(id = it, name = "shell_execute", input = JSONObject().put("command", "echo")) },
    )
    private fun results(vararg r: Pair<String, String>) = LLMMessage(
        LLMMessage.Role.USER, "",
        contentParts = r.map { (id, c) -> AgentContentPart.ToolResult(id = id, name = "shell_execute", content = c, isError = c == interrupted) },
    )

    @Test fun `7a through buildRequestBody - the request is well formed and carries the real results`() {
        // agentHistory as ChatViewModel.sanitizeAgentHistory leaves it.
        val wire = body(listOf(
            u("please run two commands"), a("Running.", "call_clA_0", "call_clA_1"),
            results("call_clA_0" to interrupted, "call_clA_1" to interrupted),
            u("interjected while the tools were running"),
            results("call_clA_0" to "a", "call_clA_1" to "b"),
            a("Both commands are done."), u("follow-up"),
        ))
        assertEquals(null, violation(wire))
        assertEquals("system U A*(call_clA_0,call_clA_1) T:call_clA_0=a T:call_clA_1=b U A U", shape(wire))
    }

    @Test fun `7b through buildRequestBody - the request is well formed`() {
        val wire = body(listOf(
            u("go"), a("", "call_clB_a"), results("call_clB_a" to interrupted), a("", "call_clB_b"),
            results("call_clB_a" to "a", "call_clB_b" to interrupted), a("Done."), u("follow-up"),
        ))
        assertEquals(null, violation(wire))
        assertEquals("system U A*(call_clB_a) T:call_clB_a=a A*(call_clB_b) T:call_clB_b=INT A U", shape(wire))
    }

    @Test fun `a healthy history builds exactly what it built before`() {
        val wire = body(listOf(u("go"), a("", "c0", "c1"), results("c0" to "x", "c1" to "y"), a("Done."), u("next")))
        assertEquals("system U A*(c0,c1) T:c0=x T:c1=y A U", shape(wire))
    }

    @Test fun `ids repeated across turns are renamed first, then paired by position`() {
        // Kimi-style ids repeat between turns; the dedupe renames the second pair.
        // [B02] They go out as wire ids: '.' and ':' become '-'.
        val wire = body(listOf(
            u("go"), a("", "functions.run:0"), results("functions.run:0" to "first"),
            a("", "functions.run:0"), results("functions.run:0" to "second"), a("Done."),
        ))
        assertEquals(null, violation(wire))
        assertEquals("system U A*(functions-run-0) T:functions-run-0=first A*(functions-run-0-2) T:functions-run-0-2=second A", shape(wire))
    }

    // [T-kimi-native-tool-id] A request to Kimi keeps Kimi's own ids; the
    // cross-turn dedupe still renames a repeated pair, as before.
    @Test fun `a request to Kimi keeps its native tool ids`() {
        val kimi = LLMModel(id = "kimi-k2-0905-preview", displayName = "K2", provider = "moonshot", inputModalities = listOf("text"))
        val history = listOf(
            u("go"), a("", "functions.run:0"), results("functions.run:0" to "first"),
            a("", "functions.run:0"), results("functions.run:0" to "second"), a("Done."),
        )
        for (p in listOf(
            OpenAIProvider(apiKey = "k", model = kimi, basePath = "https://openrouter.ai/api/v1", useResponsesAPI = false),
            OpenAIProvider(apiKey = "k", model = model, basePath = "https://api.moonshot.ai/v1", useResponsesAPI = false),
        )) {
            val wire = p.buildRequestBody(history, systemPrompt = "sys", maxTokens = 256, stream = true, temperature = null, imageParts = emptyList())
                .getJSONArray("messages")
            assertEquals(null, violation(wire))
            assertEquals("system U A*(functions.run:0) T:functions.run:0=first A*(functions.run:0-2) T:functions.run:0-2=second A", shape(wire))
        }
    }

    @Test fun `a request to Kimi still rewrites ids that are not Kimi-native`() {
        val kimi = LLMModel(id = "moonshotai/kimi-k2", displayName = "K2", provider = "openrouter", inputModalities = listOf("text"))
        val p = OpenAIProvider(apiKey = "k", model = kimi, basePath = "https://openrouter.ai/api/v1", useResponsesAPI = false)
        val wire = p.buildRequestBody(
            listOf(u("go"), a("", "call_abc|fc_def"), results("call_abc|fc_def" to "x"), a("Done.")),
            systemPrompt = "sys", maxTokens = 256, stream = true, temperature = null, imageParts = emptyList(),
        ).getJSONArray("messages")
        assertEquals("system U A*(call_abc) T:call_abc=x A", shape(wire))
        assertTrue(com.openminis.app.data.model.isKimiNativeToolId("functions.web_search:12"))
        assertFalse(com.openminis.app.data.model.isKimiNativeToolId("functions.run:0|fc_x"))
        assertFalse(com.openminis.app.data.model.isKimiNativeToolId("call_abc"))
    }

    // ── Constants and wiring ───────────────────────────────────────────────

    @Test fun `one placeholder wording, shared with the Responses path and iOS`() {
        assertEquals(ResponsesToolPairing.PLACEHOLDER_OUTPUT, ChatCompletionsToolPairing.PLACEHOLDER_REPLY)
        if (IosSources.root != null) {
            val ios = IosSources.declaring("private static func sanitizeToolCallAdjacency(")
            assertTrue(ios.contains("\"content\": \"${ChatCompletionsToolPairing.PLACEHOLDER_REPLY}\""))
        }
        // The history repair's own text: the provider must know it to let a real result win.
        val vm = ProductionSources.read("ui/chat/ChatViewModel.kt")
        val repair = vm.substringAfter("private fun sanitizeAgentHistory() {").substringBefore("\n    }\n")
        assertTrue(repair.contains("content = \"${ChatCompletionsToolPairing.HISTORY_REPAIR_REPLY}\","))
    }

    @Test fun `the Chat Completions builder runs it last, after the id dedupe, and sends its result`() {
        val src = ProductionSources.read("provider/openai/OpenAIProvider.kt")
        val tail = src.substringAfter("        globallyDedupeToolCallIds(messagesArray)\n").take(1400)
        assertTrue(tail.contains("val paired = ChatCompletionsToolPairing.sanitize(messagesArray)"))
        assertTrue(tail.contains("body.put(\"messages\", paired.messages)"))
        assertTrue(tail.contains("[sanitize-chat] repaired outgoing tool pairing"))
        assertFalse("the unrepaired array is not what is sent", src.contains("body.put(\"messages\", messagesArray)"))
        // The Responses path keeps its own gate.
        assertTrue(src.contains("val paired = ResponsesToolPairing.sanitize(input)"))
    }
}
