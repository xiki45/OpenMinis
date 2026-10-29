package com.openminis.app.provider

import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.provider.openai.OpenAIModelsApi
import com.openminis.app.provider.openai.OpenAIProvider
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [T-gpt61-sol] GPT-6.1 Sol on the Codex OAuth path (OpenAI DevDay
 * 2026-09-29; catalog data from CLIProxyAPI PR #6207).
 *
 * Same data-gap shape as GPT6SolLunaOpus55Test: the id is too new for the
 * bundled models.dev snapshot, so each fact below lives in one literal. The
 * extra trap here is the version dot: "gpt-6.1-sol" does not start with
 * "gpt-6-sol", so the existing thinking rule never matched it.
 */
class GPT61SolTest {

    private lateinit var server: MockWebServer

    @Before fun setUp() { server = MockWebServer(); server.start() }
    @After fun tearDown() { server.shutdown() }

    private val sol61: LLMModel
        get() = OpenAIModelsApi.fetchModelsOAuth().first { it.id == "gpt-6.1-sol" }

    @Test
    fun `gpt-6_1-sol is in the codex oauth list, after astra, with its catalog data`() {
        val list = OpenAIModelsApi.fetchModelsOAuth()
        val ids = list.map { it.id }
        assertEquals("listed right after Astra, as on iOS", ids.indexOf("gpt-6-astra") + 1, ids.indexOf("gpt-6.1-sol"))
        assertEquals(1, list.count { it.id == "gpt-6.1-sol" })
        assertEquals("GPT-6.1 Sol", sol61.displayName)
        assertEquals("OpenAI", sol61.provider)
        assertEquals(272_000, sol61.contextWindow)
        assertEquals(272_000, sol61.contextWindowTokens)
        assertEquals(128_000, sol61.maxOutputTokens)
    }

    @Test
    fun `gpt-6_1-sol declares supportsReasoning`() {
        assertEquals(true, sol61.supportsReasoning)
    }

    @Test
    fun `thinking ceiling is max, not the xhigh default`() {
        assertEquals(ThinkingLevel.MAX, ThinkingLevelCatalog.declaredMaxLevel("gpt-6.1-sol"))
        // Relays that normalise dots to dashes (same pair as iOS).
        assertEquals(ThinkingLevel.MAX, ThinkingLevelCatalog.declaredMaxLevel("gpt-6-1-sol"))
        assertEquals(ThinkingLevel.MAX, sol61.catalogMaxThinkingLevel)
        assertNotEquals(ThinkingLevel.XHIGH, sol61.catalogMaxThinkingLevel)
        // A null flag still reaches MAX via the id rule; an explicit false is off.
        assertEquals(ThinkingLevel.MAX, sol61.copy(supportsReasoning = null).catalogMaxThinkingLevel)
        assertEquals(ThinkingLevel.OFF, sol61.copy(supportsReasoning = false).catalogMaxThinkingLevel)
    }

    private fun responsesBody(model: LLMModel, level: ThinkingLevel): JSONObject {
        val ok = """{"type":"response.completed","response":{"output":[],"status":"completed"}}"""
        repeat(4) {
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(ok))
        }
        val provider = OpenAIProvider(
            apiKey = "test-key",
            model = model,
            basePath = server.url("/v1").toString().trimEnd('/'),
            useResponsesAPI = true,
        )
        runCatching {
            runBlocking {
                provider.sendMessageClamped(
                    messages = listOf(LLMMessage(LLMMessage.Role.USER, "question")),
                    systemPrompt = null,
                    maxTokens = 4096,
                    temperature = null,
                    imageParts = emptyList(),
                    tools = emptyList(),
                    thinkingLevel = level,
                )
            }
        }
        return JSONObject(server.takeRequest().body.readUtf8())
    }

    @Test
    fun `every thinking level reaches the wire as itself, max included`() {
        val expected = mapOf(
            ThinkingLevel.LOW to "low",
            ThinkingLevel.MEDIUM to "medium",
            ThinkingLevel.HIGH to "high",
            ThinkingLevel.XHIGH to "xhigh",
            ThinkingLevel.MAX to "max",
        )
        for ((level, wire) in expected) {
            val body = responsesBody(sol61, level)
            assertEquals("$level must put effort=$wire on the wire: $body", wire,
                body.optJSONObject("reasoning")?.optString("effort"))
        }
    }

    @Test
    fun `resolver and reasoning prefill keep gpt-6_1 in the gpt-6 family`() {
        // The thinking resolver's OpenAI rows and the /v1/models prefill are
        // `gpt-6` prefix matches; 6.1 must stay inside them.
        assertTrue("gpt-6.1-sol".startsWith("gpt-6"))
        val src = com.openminis.app.ProductionSources.read("provider/thinking/ThinkingRuleResolver.kt")
        assertTrue(src.contains("ThinkingRule.Scope.ModelPattern(\"gpt-6*\")"))
        assertTrue(src.contains("lid.startsWith(\"gpt-6\")"))
    }
}
