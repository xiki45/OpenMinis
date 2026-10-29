package com.openminis.app.provider.gemini

import com.openminis.app.data.model.LLMError
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.LLMStreamChunk
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.provider.thinking.ThinkingRuleResolver.GeminiThinkingFormat
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * [OpenMinis#470] A 400 about the thinking parameter is sent once more in the
 * other shape, and the shape that worked is remembered per (instance, model).
 */
class GeminiThinkingRetryTest {
    private lateinit var server: MockWebServer

    private val ok = """{"candidates":[{"content":{"parts":[{"text":"ok"}],"role":"model"},"finishReason":"STOP"}]}"""

    @Before
    fun setUp() {
        GeminiThinkingRetry.clear()
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
        GeminiThinkingRetry.clear()
    }

    private fun provider(id: String, tag: String = "prov:a") = GeminiProvider(
        apiKey = "test-key",
        model = LLMModel(id, id, "Google"),
        basePath = server.url("/").toString().trimEnd('/'),
    ).also { it.netTag = tag }

    private fun error400(message: String) = MockResponse().setResponseCode(400).setBody(
        """{"error":{"code":400,"message":"$message","status":"INVALID_ARGUMENT"}}""",
    )

    private fun thinkingOf(): JSONObject? {
        val body = JSONObject(server.takeRequest().body.readUtf8())
        return body.getJSONObject("generationConfig").optJSONObject("thinkingConfig")
    }

    private fun send(p: GeminiProvider, level: ThinkingLevel) = runBlocking {
        p.sendMessage(listOf(LLMMessage(LLMMessage.Role.USER, "Hi")), null, 256, thinkingLevel = level)
    }

    @Test
    fun `the error texts pick the shape`() {
        assertEquals(
            GeminiThinkingFormat.LEVEL,
            GeminiThinkingRetry.formatFor("Thinking budget is not supported for this model."),
        )
        assertEquals(GeminiThinkingFormat.LEVEL, GeminiThinkingRetry.formatFor("Thinking budget: use thinking level instead"))
        assertEquals(GeminiThinkingFormat.LEVEL, GeminiThinkingRetry.formatFor("Thinking budget is deprecated for Gemini 4"))
        assertEquals(
            GeminiThinkingFormat.BUDGET,
            GeminiThinkingRetry.formatFor("Thinking level is not supported for this model."),
        )
        // MINIMAL is checked before the generic level text it also contains.
        assertEquals(
            GeminiThinkingFormat.LEVEL_NO_MINIMAL,
            GeminiThinkingRetry.formatFor("Thinking level MINIMAL is not supported for this model."),
        )
        assertEquals(
            GeminiThinkingFormat.LEVEL_NO_MINIMAL,
            GeminiThinkingRetry.formatFor("Thinking level is unsupported: THINKING_LEVEL_MINIMAL"),
        )
        assertNull(GeminiThinkingRetry.formatFor("Invalid JSON payload received. Unknown name \"foo\""))
        assertNull(GeminiThinkingRetry.formatFor("Function call is missing a thought_signature"))
    }

    @Test
    fun `a budget rejected for a level is retried once as a level, then remembered`() {
        val p = provider("gemini-2.5-flash")
        server.enqueue(error400("Thinking budget is not supported for this model. Use thinking level instead."))
        server.enqueue(MockResponse().setBody(ok))
        server.enqueue(MockResponse().setBody(ok))

        assertEquals("ok", send(p, ThinkingLevel.HIGH).text)
        assertEquals(8192, thinkingOf()!!.getInt("thinkingBudget"))
        val retried = thinkingOf()!!
        assertEquals("high", retried.getString("thinkingLevel"))
        assertFalse(retried.has("thinkingBudget"))

        // The next request goes straight out as a level.
        send(p, ThinkingLevel.LOW)
        assertEquals("low", thinkingOf()!!.getString("thinkingLevel"))
        assertEquals(3, server.requestCount)

        // Per instance: another instance with the same model starts from the budget.
        val other = provider("gemini-2.5-flash", tag = "prov:b")
        server.enqueue(MockResponse().setBody(ok))
        send(other, ThinkingLevel.LOW)
        assertEquals(1024, thinkingOf()!!.getInt("thinkingBudget"))
    }

    @Test
    fun `a level rejected outright is retried as a budget`() {
        val p = provider("gemini-3.6-flash")
        server.enqueue(error400("Thinking level is not supported for this model."))
        server.enqueue(MockResponse().setBody(ok))
        send(p, ThinkingLevel.HIGH)
        assertEquals("high", thinkingOf()!!.getString("thinkingLevel"))
        val retried = thinkingOf()!!
        assertEquals(8192, retried.getInt("thinkingBudget"))
        assertFalse(retried.has("thinkingLevel"))
        assertEquals(GeminiThinkingFormat.BUDGET, GeminiThinkingRetry.preferred("prov:a", "gemini-3.6-flash"))
    }

    @Test
    fun `a rejected minimal is raised to low`() {
        for (message in listOf(
            "Thinking level MINIMAL is not supported for this model.",
            "Thinking level is unsupported: THINKING_LEVEL_MINIMAL",
        )) {
            GeminiThinkingRetry.clear()
            val p = provider("gemini-3.6-flash")
            server.enqueue(error400(message))
            server.enqueue(MockResponse().setBody(ok))
            send(p, ThinkingLevel.OFF)
            assertEquals("minimal", thinkingOf()!!.getString("thinkingLevel"))
            assertEquals("low", thinkingOf()!!.getString("thinkingLevel"))
        }
    }

    @Test
    fun `the retry is never retried and a failed retry is not remembered`() {
        val p = provider("gemini-2.5-flash")
        server.enqueue(error400("Thinking budget is not supported for this model."))
        server.enqueue(error400("Thinking level is not supported for this model."))
        server.enqueue(MockResponse().setBody(ok))
        try {
            send(p, ThinkingLevel.HIGH)
            fail("expected the second 400")
        } catch (e: LLMError.ProviderError) {
            assertTrue(e.detail, e.detail.contains("Thinking level is not supported"))
        }
        assertEquals(2, server.requestCount)
        assertNull(GeminiThinkingRetry.preferred("prov:a", "gemini-2.5-flash"))
    }

    @Test
    fun `other 400s are not retried`() {
        val p = provider("gemini-4-argon")
        server.enqueue(error400("Invalid JSON payload received."))
        server.enqueue(MockResponse().setBody(ok))
        try {
            send(p, ThinkingLevel.HIGH)
            fail("expected the 400")
        } catch (e: LLMError.ProviderError) {
            assertEquals(400, e.httpStatus)
        }
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `a model sent without a thinking config is not retried`() {
        val p = provider("gemini-flash-latest")
        server.enqueue(error400("Thinking budget is not supported for this model."))
        try {
            send(p, ThinkingLevel.HIGH)
            fail("expected the 400")
        } catch (_: LLMError.ProviderError) {
        }
        assertNull(thinkingOf())
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `the stream retries before any output`() = runBlocking {
        val p = provider("gemini-2.5-pro")
        server.enqueue(error400("Thinking budget is not supported for this model."))
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody("data: $ok\n\n"),
        )
        val chunks = p.streamMessage(
            listOf(LLMMessage(LLMMessage.Role.USER, "Hi")), null, 256, thinkingLevel = ThinkingLevel.MEDIUM,
        ).toList()
        assertTrue(chunks.any { it is LLMStreamChunk.Text && it.text == "ok" })
        assertEquals(8192, thinkingOf()!!.getInt("thinkingBudget"))
        assertEquals("medium", thinkingOf()!!.getString("thinkingLevel"))
        assertEquals(2, server.requestCount)
    }

    /** gemini-4-argon with no retry at all: the version rule already sends levels. */
    @Test
    fun `gemini-4-argon sends a level on the first request`() {
        val p = provider("gemini-4-argon")
        server.enqueue(MockResponse().setBody(ok))
        server.enqueue(MockResponse().setBody(ok))
        send(p, ThinkingLevel.OFF)
        val off = thinkingOf()!!
        assertEquals("low", off.getString("thinkingLevel"))
        assertFalse(off.has("includeThoughts"))
        send(p, ThinkingLevel.MEDIUM)
        assertEquals("medium", thinkingOf()!!.getString("thinkingLevel"))
        assertEquals(2, server.requestCount)
    }
}
