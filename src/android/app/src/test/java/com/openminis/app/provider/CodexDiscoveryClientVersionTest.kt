package com.openminis.app.provider

import com.openminis.app.ProductionSources
import com.openminis.app.provider.openai.CodexModelsApi
import com.openminis.app.provider.openai.OpenAIProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-codex-discovery-client-version] Codex OAuth discovery never listed
 * gpt-6.1-sol. CLIProxyAPI, which does, moved its Codex client to 0.159.0 in
 * the commit that added the model (c8a2bfe8f) and sends a smaller header set.
 */
class CodexDiscoveryClientVersionTest {

    @Test
    fun `the codex client version is 0_159_0 and every codex call uses the one constant`() {
        assertEquals("0.159.0", OpenAIProvider.CODEX_CLIENT_VERSION)
        val provider = ProductionSources.read("provider/openai/OpenAIProvider.kt")
        assertTrue(provider.contains(".header(\"Version\", CODEX_CLIENT_VERSION)"))
        assertTrue(provider.contains(".header(\"User-Agent\", \"codex_cli_rs/\$CODEX_CLIENT_VERSION (Android; arm64)\")"))
        val repo = ProductionSources.read("data/repository/ProviderRepository.kt")
        assertTrue(repo.contains("clientVersion = com.openminis.app.provider.openai.OpenAIProvider.CODEX_CLIENT_VERSION"))
        val quota = ProductionSources.read("quota/QuotaStore.kt")
        assertTrue(quota.contains("val version = com.openminis.app.provider.openai.OpenAIProvider.CODEX_CLIENT_VERSION"))
        // No stray literal left behind anywhere a codex request is built.
        for (src in listOf(provider, repo, quota, ProductionSources.read("provider/openai/CodexModelsApi.kt"))) {
            assertFalse(src.contains("\"0.155.0\""))
        }
    }

    @Test
    fun `discovery sends the CLIProxyAPI header set with the shared version`() {
        val req = CodexModelsApi.discoveryRequest("TOKEN", "acct-1", OpenAIProvider.CODEX_CLIENT_VERSION).build()
        assertEquals("https://chatgpt.com/backend-api/codex/models?client_version=0.159.0", req.url.toString())
        assertEquals("GET", req.method)
        assertEquals("application/json", req.header("Accept"))
        assertEquals("Bearer TOKEN", req.header("Authorization"))
        assertEquals("codex_cli_rs", req.header("Originator"))
        assertEquals("codex_cli_rs/0.159.0 (Android; arm64)", req.header("User-Agent"))
        assertEquals("acct-1", req.header("Chatgpt-Account-Id"))
        // Responses-only headers are not part of the discovery request.
        assertNull(req.header("Version"))
        assertNull(req.header("Openai-Beta"))
        assertEquals(
            setOf("Accept", "Authorization", "Originator", "User-Agent", "Chatgpt-Account-Id"),
            req.headers.names(),
        )
    }

    @Test
    fun `no account id means no account header`() {
        val req = CodexModelsApi.discoveryRequest("T", "  ", "0.159.0").build()
        assertNull(req.header("Chatgpt-Account-Id"))
    }

    @Test
    fun `the version bump re-fetches instead of serving the old cached catalog`() {
        assertNotEquals(
            CodexModelsApi.cacheKey("acct-1", "tok", "0.155.0"),
            CodexModelsApi.cacheKey("acct-1", "tok", OpenAIProvider.CODEX_CLIENT_VERSION),
        )
    }

    @Test
    fun `a catalog with gpt-6_1-sol parses it, and hide entries stay hidden`() {
        val body = """
            {"models":[
              {"slug":"gpt-6.1-sol","display_name":"GPT-6.1 Sol","visibility":"list","priority":1,
               "context_window":272000,"default_reasoning_level":"low",
               "supported_reasoning_levels":[{"effort":"low"},{"effort":"medium"},{"effort":"high"},{"effort":"xhigh"},{"effort":"max"}],
               "input_modalities":["text","image"]},
              {"slug":"gpt-6-astra","display_name":"GPT-6 Astra","visibility":"list","priority":2},
              {"slug":"gpt-reserve","visibility":"hide","priority":3},
              {"slug":"codex-auto-review","visibility":"hide","priority":4},
              {"slug":"internal-one","visibility":"internal"},
              {"slug":"none-one","visibility":"none"}
            ]}
        """.trimIndent()
        val models = CodexModelsApi.parseModels(body)
        assertEquals(listOf("gpt-6.1-sol", "gpt-6-astra"), models.map { it.id })
        val sol = models.first()
        assertEquals("GPT-6.1 Sol", sol.displayName)
        assertEquals(272_000, sol.contextWindow)
        assertEquals(true, sol.supportsReasoning)
        assertEquals(listOf("low", "medium", "high", "xhigh", "max"), sol.reasoningEffortValues)
    }

    @Test
    fun `the hidden-visibility set is the one shared with iOS`() {
        assertEquals(setOf("hide", "hidden", "internal", "none"), CodexModelsApi.HIDDEN_VISIBILITIES)
    }
}
