package com.openminis.app.agent.userask

import com.openminis.app.ProductionSources
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [T-port-nested-json-string] Pixel 4a, DeepSeek V4 Flash via SenseNova and
 * Bailian: 5 of 7 user_ask calls carried `questions` as JSON-in-a-string, so
 * the card never showed and the model fell back to asking in text. Both the
 * parser and the generic nested-schema path now decode it; a string that is
 * not JSON gets an error the model can act on.
 */
class UserAskJsonStringArgsTest {

    @Before fun setUp() = UserAskCenter.resetForTest()
    @After fun tearDown() = UserAskCenter.resetForTest()

    private val options = JSONArray()
        .put(JSONObject().put("label", "SQLite").put("description", "file"))
        .put(JSONObject().put("label", "Postgres"))
    private fun question(opts: Any = options) = JSONObject().put("type", "options").put("question", "Which DB?").put("options", opts)
    private val confirm = JSONObject().put("type", "confirm").put("question", "rm -rf node_modules").put("risk", "high")

    /** The exact shape seen on the device: the whole array as a string. */
    private fun stringifiedQuestions() = JSONObject().put("questions", JSONArray().put(question()).put(confirm).toString())

    @Test fun `questions as a JSON string parse like a native array`() {
        val r = UserAskParser.parse(stringifiedQuestions()).getOrThrow()
        assertEquals(2, r.questions.size)
        assertEquals(listOf("SQLite", "Postgres"), r.questions[0].options!!.map { it.label })
        assertEquals(UserAskQuestionType.CONFIRM, r.questions[1].type)
        assertEquals(UserAskRisk.HIGH, r.questions[1].risk)
        // With whitespace around it, too.
        val padded = JSONObject().put("questions", "\n  " + JSONArray().put(question()).toString() + "  ")
        assertEquals(1, UserAskParser.parse(padded).getOrThrow().questions.size)
    }

    @Test fun `options as a JSON string, and both levels stringified`() {
        val optsString = JSONObject().put("questions", JSONArray().put(question(options.toString())))
        assertEquals(listOf("SQLite", "Postgres"), UserAskParser.parse(optsString).getOrThrow().questions[0].options!!.map { it.label })
        val both = JSONObject().put("questions", JSONArray().put(question(options.toString())).toString())
        assertEquals("file", UserAskParser.parse(both).getOrThrow().questions[0].options!![0].description)
    }

    @Test fun `items stringified one by one parse too`() {
        val items = JSONObject().put("questions", JSONArray().put(question().toString()))
        assertEquals("Which DB?", UserAskParser.parse(items).getOrThrow().questions[0].question)
        val optItems = JSONObject().put("questions", JSONArray().put(question(JSONArray().put(options.getJSONObject(0).toString()).put("Postgres"))))
        assertEquals(listOf("SQLite", "Postgres"), UserAskParser.parse(optItems).getOrThrow().questions[0].options!!.map { it.label })
    }

    @Test fun `a broken string gets a clear error, a blank one is simply missing`() {
        val brokenQ = UserAskParser.parse(JSONObject().put("questions", "[{\"type\":\"options\",\"question\":\"x\"")).exceptionOrNull()!!.message!!
        assertTrue(brokenQ, brokenQ.startsWith("`questions` was sent as a string that is not a valid JSON array"))
        assertTrue(brokenQ, brokenQ.endsWith("Send `questions` as a JSON array, not as a string."))
        val notArray = UserAskParser.parse(JSONObject().put("questions", "Which DB do you want?")).exceptionOrNull()!!.message!!
        assertTrue(notArray, notArray.contains("not a valid JSON array"))
        val brokenO = UserAskParser.parse(JSONObject().put("questions", JSONArray().put(question("[{\"label\":")))).exceptionOrNull()!!.message!!
        assertTrue(brokenO, brokenO.startsWith("`questions[0].options` was sent as a string that is not a valid JSON array"))
        val blank = UserAskParser.parse(JSONObject().put("questions", "  ")).exceptionOrNull()!!.message!!
        assertTrue(blank, blank.startsWith("`questions` is required"))
    }

    @Test fun `the card's own re-parse of the stored raw arguments works`() {
        // The card reads block.toolArgs - the model's arguments as sent.
        val raw = stringifiedQuestions().toString()
        assertEquals(2, UserAskParser.parse(raw).getOrThrow().questions.size)
    }

    @Test fun `end to end - a stringified call shows a card and resolves`() = runBlocking {
        val call = async(start = CoroutineStart.UNDISPATCHED) {
            UserAskToolRunner.execute(stringifiedQuestions().toString(), "tc1", "s1", null, 5, requestId = "r1")
        }
        val ask = UserAskCenter.ask("tc1")
        assertNotNull("the card is registered", ask)
        UserAskCenter.submitQuestion("r1", 0)
        UserAskCenter.confirm("r1", 1, UserAskConfirmChoice.APPROVE)
        val out = withTimeout(2_000) { call.await() }.output
        assertEquals(listOf(UserAskResultStatus.ANSWERED, UserAskResultStatus.APPROVED), UserAskResolver.statuses(out))
    }

    // ── The generic nested-schema path ──────────────────────────────────────

    private val def = UserAskPromptText.definition()

    @Test fun `the schema decodes stringified nested arguments to what it declares`() {
        val fixed = def.coerceNestedJsonStrings(stringifiedQuestions())
        val qs = fixed.get("questions")
        assertTrue("now a native array", qs is JSONArray)
        assertTrue((qs as JSONArray).getJSONObject(0).get("options") is JSONArray)
        // Nested: an options string inside native questions.
        val inner = def.coerceNestedJsonStrings(JSONObject().put("questions", JSONArray().put(question(options.toString()))))
        assertTrue(inner.getJSONArray("questions").getJSONObject(0).get("options") is JSONArray)
        // Items stringified one by one.
        val items = def.coerceNestedJsonStrings(JSONObject().put("questions", JSONArray().put(question().toString())))
        assertTrue(items.getJSONArray("questions").get(0) is JSONObject)
    }

    @Test fun `native, malformed and scalar values are left alone`() {
        val native = JSONObject().put("questions", JSONArray().put(question()))
        assertSame("nothing to do: same instance", native, def.coerceNestedJsonStrings(native))
        val broken = JSONObject().put("questions", "[{oops")
        assertSame("a broken string is the parser's to report", broken, def.coerceNestedJsonStrings(broken))
        val flat = com.openminis.app.tools.AgentTools.makeAgentTools().first { it.name == "shell_execute" }
        val shell = JSONObject().put("command", "[1,2]")
        assertSame("a flat string param that looks like JSON stays a string", shell, flat.coerceNestedJsonStrings(shell))
        val prose = JSONObject().put("questions", "Which DB?")
        assertSame(prose, def.coerceNestedJsonStrings(prose))
    }

    @Test fun `the dispatcher decodes before preflight and execution`() {
        val vm = ProductionSources.read("ui/chat/ChatViewModel.kt")
        val slot = vm.substringAfter("val args = agentTools.firstOrNull { it.name == name }?.coerceNestedJsonStrings(args) ?: args")
        assertTrue("decode precedes argsStr", slot.trimStart().startsWith("val argsStr = args.toString()"))
        assertTrue("and preflight", slot.indexOf("preflightValidateToolCall(name, args, agentTools)") > 0)
    }
}
