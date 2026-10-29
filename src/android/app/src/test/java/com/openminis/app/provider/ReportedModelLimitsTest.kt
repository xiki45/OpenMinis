package com.openminis.app.provider

import com.openminis.app.ProductionSources
import com.openminis.app.data.model.LLMModel
import com.openminis.app.provider.openai.OpenAIModelsApi
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [OpenMinis#438] A custom OpenAI-compatible endpoint that reports
 * `context_length` / `max_output_tokens` on /v1/models is believed: the values
 * are read, and models.dev no longer replaces them. Before, a routing-prefixed
 * id (`cn:deepseek-v4-pro`) matched no catalog key and fell to a 128K guess,
 * and an id that did match took another provider's (possibly larger) window.
 */
class ReportedModelLimitsTest {

    private fun limits(json: String) = OpenAIModelsApi.reportedLimits(JSONObject(json))

    // ── parsing ───────────────────────────────────────────────────────

    @Test
    fun `the issue's shape`() {
        assertEquals(1_000_000 to 393_216, limits("""{"id":"cn:deepseek-v4-pro","context_length":1000000,"max_output_tokens":393216}"""))
    }

    @Test
    fun `every documented spelling of the context window`() {
        for (key in listOf("context_length", "context_window", "contextWindow", "max_context_length")) {
            assertEquals(key, 200_000, limits("""{"id":"m","$key":200000}""").first)
        }
        assertEquals(64_000, limits("""{"id":"m","limit":{"context":64000,"output":8000}}""").first)
        assertEquals(32_000, limits("""{"id":"m","top_provider":{"context_length":32000}}""").first)
    }

    @Test
    fun `every documented spelling of the output limit`() {
        for (key in listOf("max_output_tokens", "maxOutputTokens", "max_completion_tokens")) {
            assertEquals(key, 16_384, limits("""{"id":"m","$key":16384}""").second)
        }
        assertEquals(8_000, limits("""{"id":"m","limit":{"context":64000,"output":8000}}""").second)
        assertEquals(4_096, limits("""{"id":"m","top_provider":{"max_completion_tokens":4096}}""").second)
    }

    @Test
    fun `top-level wins over limit, limit over top_provider`() {
        assertEquals(1, limits("""{"context_length":1,"limit":{"context":2},"top_provider":{"context_length":3}}""").first)
        assertEquals(2, limits("""{"limit":{"context":2},"top_provider":{"context_length":3}}""").first)
    }

    @Test
    fun `numeric strings and whole doubles count, junk does not`() {
        assertEquals(128_000, limits("""{"context_length":"128000"}""").first)
        assertEquals(128_000, limits("""{"context_length":128000.0}""").first)
        for (bad in listOf("0", "-5", "\"abc\"", "true", "null", "\"\"", "1e30")) {
            assertNull(bad, limits("""{"context_length":$bad}""").first)
        }
        assertEquals(null to null, limits("""{"id":"m"}"""))
    }

    // ── models.dev merge: the endpoint's value wins, the catalog fills gaps ──

    private fun dev(context: Int?, output: Int?) = ModelsDevApi.ModelDevEntry(
        id = "deepseek-v4-pro", name = "DeepSeek V4 Pro", family = null,
        contextWindow = context, maxOutputTokens = output, reasoning = true, interleavedField = null,
        inputModalities = listOf("text"), outputModalities = listOf("text"), reasoningEffortValues = listOf("high", "max"),
        releaseDate = null, outputCost = null,
    )

    @Test
    fun `an endpoint-reported window is not replaced by a larger catalog value`() {
        val reported = LLMModel("deepseek-v4-pro", "x", "Custom", contextWindow = 200_000, limitsReportedByEndpoint = true)
        val out = ModelsDevApi.applyDevData(reported, dev(context = 1_000_000, output = 384_000))
        assertEquals("the gateway's own limit stays", 200_000, out.contextWindow)
        assertEquals("the catalog fills what the endpoint left out", 384_000, out.maxOutputTokens)
        assertEquals("other fields still come from the catalog", true, out.supportsReasoning)
        assertEquals(listOf("high", "max"), out.reasoningEffortValues)
        assertEquals(true, out.limitsReportedByEndpoint)
    }

    @Test
    fun `a model nobody described keeps the catalog-first rule`() {
        val builtIn = LLMModel("deepseek-v4-pro", "x", "Custom", contextWindow = 128_000, maxOutputTokens = 8_192)
        val out = ModelsDevApi.applyDevData(builtIn, dev(context = 1_000_000, output = 384_000))
        assertEquals(1_000_000, out.contextWindow)
        assertEquals(384_000, out.maxOutputTokens)
    }

    @Test
    fun `re-enriching twice cannot bring the catalog value back`() {
        val reported = LLMModel("m", "x", "Custom", contextWindow = 200_000, maxOutputTokens = 16_000, limitsReportedByEndpoint = true)
        val once = ModelsDevApi.applyDevData(reported, dev(1_000_000, 384_000))
        val twice = ModelsDevApi.applyDevData(once, dev(1_000_000, 384_000))
        assertEquals(200_000, twice.contextWindow)
        assertEquals(16_000, twice.maxOutputTokens)
    }

    // ── the fetch itself ──────────────────────────────────────────────

    private lateinit var server: MockWebServer

    @Before
    fun setUp() { server = MockWebServer(); server.start() }

    @After
    fun tearDown() = server.shutdown()

    private fun fetch(body: String): List<LLMModel> = runBlocking {
        server.enqueue(MockResponse().setBody(body))
        OpenAIModelsApi.fetchModels("k", server.url("/v1").toString().trimEnd('/'), forceRefresh = true)
    }

    @Test
    fun `a routing-prefixed id gets the endpoint's window instead of a guess`() {
        val m = fetch("""{"data":[{"id":"cn:deepseek-v4-pro","context_length":1000000,"max_output_tokens":393216}]}""").single()
        assertEquals(1_000_000, m.contextWindow)
        assertEquals(1_000_000, m.contextWindowTokens)
        assertEquals(393_216, m.maxOutputTokens)
        assertEquals(true, m.limitsReportedByEndpoint)
    }

    @Test
    fun `an entry with no limits is exactly what it was before`() {
        val m = fetch("""{"data":[{"id":"cn:some-unknown-model-xyz"}]}""").single()
        assertNull(m.limitsReportedByEndpoint)
        assertNull("no catalog match, nothing reported", m.contextWindow)
    }

    // [T-anthropic-list-reported-limits] The same response sample as the
    // Windows test (minis-providers models_api.rs ios_vectors_reported_limits,
    // "issue #438 body"), served to an Anthropic-type instance on a relay.
    private val issue438Body =
        """{"object":"list","data":[{"id":"cn:deepseek-v4-pro","object":"model","owned_by":"workbuddy","context_length":1000000,"max_output_tokens":393216}]}"""

    @Test
    fun `an OpenAI-type instance reads the 1M window from the issue body`() {
        val m = fetch(issue438Body).single()
        assertEquals(1_000_000, m.contextWindowTokens)
        assertEquals(393_216, m.maxOutputTokens)
    }

    @Test
    fun `an Anthropic-type relay reads the 1M window from the same body`() {
        server.enqueue(MockResponse().setBody(issue438Body))
        val m = runBlocking {
            com.openminis.app.provider.anthropic.AnthropicModelsApi.fetchModels(
                "k", server.url("/v1").toString().trimEnd('/'), forceRefresh = true,
            )
        }.single()
        assertEquals("cn:deepseek-v4-pro", m.id)
        assertEquals(1_000_000, m.contextWindow)
        assertEquals("not the deepseek family's 128K guess", 1_000_000, m.contextWindowTokens)
        assertEquals(393_216, m.maxOutputTokens)
        assertEquals(true, m.limitsReportedByEndpoint)
    }

    @Test
    fun `an Anthropic-type list with no limits is exactly what it was before`() {
        server.enqueue(MockResponse().setBody("""{"data":[{"id":"cn:some-unknown-model-xyz","display_name":"X"}]}"""))
        val m = runBlocking {
            com.openminis.app.provider.anthropic.AnthropicModelsApi.fetchModels(
                "k", server.url("/v1").toString().trimEnd('/'), forceRefresh = true,
            )
        }.single()
        assertNull(m.contextWindow)
        assertNull(m.limitsReportedByEndpoint)
    }

    // ── saved data ────────────────────────────────────────────────────

    @Test
    fun `a model saved before the field existed loads, and the field round-trips`() {
        val json = Json { ignoreUnknownKeys = true }
        val old = json.decodeFromString(LLMModel.serializer(), """{"id":"m","displayName":"M","provider":"Custom","contextWindow":128000}""")
        assertNull(old.limitsReportedByEndpoint)
        assertEquals(128_000, old.contextWindow)
        val now = LLMModel("m", "M", "Custom", contextWindow = 200_000, limitsReportedByEndpoint = true)
        assertEquals(now, json.decodeFromString(LLMModel.serializer(), json.encodeToString(LLMModel.serializer(), now)))
    }

    @Test
    fun `the user's override still wins, and export carries the flag`() {
        val config = ProductionSources.read("data/model/ProviderConfig.kt")
        assertTrue(config.contains("contextWindow = overrides.contextWindow ?: baseModel.contextWindow,"))
        val repo = ProductionSources.read("data/repository/ProviderRepository.kt")
        assertTrue(repo.contains("if (entry.baseModel.limitsReportedByEndpoint == true) put(\"limitsReportedByEndpoint\", true)"))
        assertTrue(repo.contains("limitsReportedByEndpoint = if (m.optBoolean(\"limitsReportedByEndpoint\", false)) true else null,"))
        val modelUse = ProductionSources.read("offload/ModelUseManager.kt")
        assertTrue(modelUse.contains("(entry.model.contextWindow ?: enrichedModel.contextWindow)?.let { put(\"context_window\", it) }"))
    }
}
