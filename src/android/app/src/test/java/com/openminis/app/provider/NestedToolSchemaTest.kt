package com.openminis.app.provider

import com.openminis.app.data.ContextSizeMeter
import com.openminis.app.data.model.AgentSchemaNode
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMModel
import com.openminis.app.provider.anthropic.AnthropicProvider
import com.openminis.app.provider.gemini.GeminiProvider
import com.openminis.app.provider.openai.OpenAIProvider
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [T-port-nested-schema] A tool parameter can declare a nested array/object
 * schema (port of iOS 5bbab0250), and every provider puts it on the wire:
 * Anthropic, OpenAI Chat Completions and Responses as standard JSON Schema,
 * Gemini in its dialect. Flat tools are unchanged byte for byte.
 */
class NestedToolSchemaTest {

    private lateinit var server: MockWebServer

    @Before fun setUp() { server = MockWebServer().also { it.start() } }
    @After fun tearDown() { server.shutdown() }

    private val base get() = server.url("/").toString().trimEnd('/')

    /** The user_ask shape: questions[] -> {question, options[] -> {label, description}, kind}. */
    private val questions = AgentSchemaNode.Arr(
        description = "1-4 questions",
        items = AgentSchemaNode.Obj(
            properties = listOf(
                "question" to AgentSchemaNode.Str("The question"),
                "kind" to AgentSchemaNode.Str("Card kind", enumValues = listOf("options", "confirm")),
                "multi_select" to AgentSchemaNode.Bool("Allow several"),
                "timeout" to AgentSchemaNode.Integer("Seconds"),
                "options" to AgentSchemaNode.Arr(
                    items = AgentSchemaNode.Obj(
                        properties = listOf(
                            "label" to AgentSchemaNode.Str("Short label"),
                            "description" to AgentSchemaNode.Str("What it means"),
                        ),
                        required = listOf("label"),
                    ),
                    minItems = 2,
                    maxItems = 4,
                ),
            ),
            required = listOf("question"),
        ),
        minItems = 1,
        maxItems = 4,
    )

    private fun tool() = AgentToolDefinition(
        name = "user_ask",
        description = "Ask the user",
        parameters = mapOf(
            "questions" to AgentToolParam("array", "The questions to ask", schema = questions),
            "tool_title" to AgentToolParam("string", "Title"),
        ),
        required = listOf("questions"),
    )

    /** Standard JSON Schema rendering of [questions], as every non-Gemini provider must send it. */
    private fun assertStandard(prop: JSONObject) {
        assertEquals("array", prop.getString("type"))
        assertEquals("the param's own description wins", "The questions to ask", prop.getString("description"))
        assertEquals(1, prop.getInt("minItems"))
        assertEquals(4, prop.getInt("maxItems"))
        val item = prop.getJSONObject("items")
        assertEquals("object", item.getString("type"))
        assertEquals(listOf("question"), item.getJSONArray("required").strings())
        val p = item.getJSONObject("properties")
        assertEquals("string", p.getJSONObject("question").getString("type"))
        assertEquals(listOf("options", "confirm"), p.getJSONObject("kind").getJSONArray("enum").strings())
        assertEquals("boolean", p.getJSONObject("multi_select").getString("type"))
        assertEquals("integer", p.getJSONObject("timeout").getString("type"))
        val opts = p.getJSONObject("options")
        assertEquals(2, opts.getInt("minItems"))
        val optItem = opts.getJSONObject("items")
        assertEquals("object", optItem.getString("type"))
        assertEquals(listOf("label"), optItem.getJSONArray("required").strings())
        assertEquals("string", optItem.getJSONObject("properties").getJSONObject("description").getString("type"))
    }

    private fun JSONArray.strings() = (0 until length()).map { getString(it) }

    private fun body(): JSONObject = JSONObject(server.takeRequest().body.readUtf8())

    @Test fun `anthropic sends the nested schema in input_schema`() = runBlocking {
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody("event: message_stop\ndata: {\"type\":\"message_stop\"}\n\n"))
        runCatching {
            AnthropicProvider(apiKey = "k", model = LLMModel.claudeSonnet55, basePath = base)
                .streamMessage(listOf(LLMMessage(LLMMessage.Role.USER, "hi")), null, 1024, tools = listOf(tool())).toList()
        }
        val t = body().getJSONArray("tools").getJSONObject(0)
        assertStandard(t.getJSONObject("input_schema").getJSONObject("properties").getJSONObject("questions"))
    }

    @Test fun `openai chat completions sends the nested schema`() = runBlocking {
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody("data: [DONE]\n\n"))
        runCatching {
            OpenAIProvider(apiKey = "k", model = openAiModel(), basePath = base, useResponsesAPI = false)
                .streamMessage(listOf(LLMMessage(LLMMessage.Role.USER, "hi")), null, 1024, tools = listOf(tool())).toList()
        }
        val fn = body().getJSONArray("tools").getJSONObject(0).getJSONObject("function")
        assertStandard(fn.getJSONObject("parameters").getJSONObject("properties").getJSONObject("questions"))
    }

    @Test fun `openai responses sends the nested schema`() = runBlocking {
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody("data: [DONE]\n\n"))
        runCatching {
            OpenAIProvider(apiKey = "k", model = openAiModel(), basePath = base, useResponsesAPI = true)
                .streamMessage(listOf(LLMMessage(LLMMessage.Role.USER, "hi")), null, 1024, tools = listOf(tool())).toList()
        }
        val t = body().getJSONArray("tools").getJSONObject(0)
        assertStandard(t.getJSONObject("parameters").getJSONObject("properties").getJSONObject("questions"))
    }

    @Test fun `gemini sends the nested schema in its dialect`() = runBlocking {
        server.enqueue(
            MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
                "data: {\"candidates\":[{\"content\":{\"role\":\"model\",\"parts\":[{\"text\":\"ok\"}]},\"finishReason\":\"STOP\"}]}\n\n",
            ),
        )
        runCatching {
            GeminiProvider(apiKey = "k", model = LLMModel.gemini25Flash, basePath = base)
                .streamMessage(listOf(LLMMessage(LLMMessage.Role.USER, "hi")), null, 1024, tools = listOf(tool())).toList()
        }
        val decl = body().getJSONArray("tools").getJSONObject(0).getJSONArray("functionDeclarations").getJSONObject(0)
        val prop = decl.getJSONObject("parameters").getJSONObject("properties").getJSONObject("questions")
        assertEquals("ARRAY", prop.getString("type"))
        assertEquals("The questions to ask", prop.getString("description"))
        assertFalse("no array bounds in the Gemini dialect", prop.has("minItems") || prop.has("maxItems"))
        val item = prop.getJSONObject("items")
        assertEquals("OBJECT", item.getString("type"))
        assertEquals(listOf("question"), item.getJSONArray("required").strings())
        val kind = item.getJSONObject("properties").getJSONObject("kind")
        assertEquals("STRING", kind.getString("type"))
        assertFalse("enum folded into the description", kind.has("enum"))
        assertEquals("Card kind (values: options, confirm)", kind.getString("description"))
        assertEquals("BOOLEAN", item.getJSONObject("properties").getJSONObject("multi_select").getString("type"))
        assertEquals("INTEGER", item.getJSONObject("properties").getJSONObject("timeout").getString("type"))
        assertFalse(item.getJSONObject("properties").getJSONObject("options").has("minItems"))
    }

    private fun openAiModel() = LLMModel(id = "gpt-5.6-terra", displayName = "Test", provider = "OpenAI", inputModalities = listOf("text"))

    // ── Flat tools are byte-identical ───────────────────────────────────────

    @Test fun `a flat param renders exactly as before`() {
        // Structural equality: the JVM's org.json does not keep key order
        // (Android's does), so key order is covered by the golden test below,
        // which builds the old and new objects with the same put sequence.
        fun same(expected: String, actual: JSONObject) =
            assertTrue("$actual", JSONObject(expected).similar(actual))
        val flat = AgentToolParam("string", "Scroll direction", enumValues = listOf("up", "down"))
        same("""{"type":"string","description":"Scroll direction","enum":["up","down"]}""", flat.toJson())
        same("""{"type":"STRING","description":"Scroll direction","enum":["up","down"]}""", flat.toGeminiJson())
        val plain = AgentToolParam("integer", "Count")
        same("""{"type":"integer","description":"Count"}""", plain.toJson())
        same("""{"type":"INTEGER","description":"Count"}""", plain.toGeminiJson())
        assertEquals(2, plain.toJson().length())
    }

    @Test fun `every shipped tool renders without a schema exactly as the flat path would`() {
        // Golden equivalence: for every built-in tool, the new toJson equals the
        // old flat expression, so nothing on the wire moves for them.
        for (def in com.openminis.app.tools.AgentTools.makeAgentTools()) {
            for ((name, p) in def.parameters) {
                if (p.schema != null) continue
                val old = JSONObject().apply {
                    put("type", p.type); put("description", p.description)
                    if (p.enumValues != null) put("enum", JSONArray(p.enumValues))
                }
                assertEquals("${def.name}.$name", old.toString(), p.toJson().toString())
                val oldGemini = JSONObject().apply {
                    put("type", p.type.uppercase()); put("description", p.description)
                    if (p.enumValues != null) put("enum", JSONArray(p.enumValues))
                }
                assertEquals("${def.name}.$name (gemini)", oldGemini.toString(), p.toGeminiJson().toString())
            }
        }
    }

    @Test fun `objects nest at any depth and the estimate counts the nested text`() {
        val deep = AgentSchemaNode.Obj(
            properties = listOf("inner" to AgentSchemaNode.Obj(properties = listOf("x" to AgentSchemaNode.Str("deep leaf")), required = listOf("x"))),
        ).toJsonSchema(gemini = false)
        assertEquals("deep leaf", deep.getJSONObject("properties").getJSONObject("inner").getJSONObject("properties").getJSONObject("x").getString("description"))

        val withSchema = ContextSizeMeter.estimateFixedTokens(null, listOf(tool()))
        val withoutSchema = ContextSizeMeter.estimateFixedTokens(
            null, listOf(tool().copy(parameters = tool().parameters.mapValues { it.value.copy(schema = null) })),
        )
        assertTrue("nested text costs tokens: $withSchema vs $withoutSchema", withSchema > withoutSchema + 10)
    }
}
