package com.openminis.app.provider

import com.openminis.app.ProductionSources
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMModel
import com.openminis.app.provider.openai.OpenAIProvider
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * [T-android-codex-prompt-cache-headers] A Codex OAuth (ChatGPT backend)
 * request names its conversation in the headers, with the same id the body
 * carries as `prompt_cache_key` - the backend keys its prompt cache on the
 * headers.
 */
@RunWith(RobolectricTestRunner::class)
class CodexPromptCacheHeadersTest {

    private val model = LLMModel("gpt-5.6-sol", "GPT-5.6", "OpenAI", supportsReasoning = true)

    private fun oauth(accountId: String? = null) =
        OpenAIProvider(oauthTokenProvider = { "test-token" }, model = model, codexAccountId = accountId)

    private fun user(text: String) = LLMMessage(LLMMessage.Role.USER, text)
    private fun assistant(text: String) = LLMMessage(LLMMessage.Role.ASSISTANT, text)

    /** The body the provider builds for [messages], and the request built from that body, as streamMessage does. */
    private fun send(provider: OpenAIProvider, messages: List<LLMMessage>): Pair<JSONObject, Request> {
        val body = provider.buildResponsesAPIBody(messages, "system", 1024, true, emptyList())
        val request = runBlocking {
            provider.buildRequest(body.toString(), routingHint = null, promptCacheKey = body.optString("prompt_cache_key"))
        }
        return body to request
    }

    @Test
    fun `a codex oauth request carries Session_id and Conversation_id equal to the body's prompt_cache_key`() {
        val (body, request) = send(oauth(), listOf(user("Summarise the release notes")))
        val key = body.getString("prompt_cache_key")
        assertTrue(key, key.startsWith("minis-") && key.length > 10)
        assertEquals("https://chatgpt.com/backend-api/codex/responses", request.url.toString())
        assertEquals(key, request.header("Session_id"))
        assertEquals(key, request.header("Conversation_id"))
        // What the "[T321] → REQ ... headerKeys=" log line prints.
        assertTrue(request.headers.names().containsAll(listOf("Session_id", "Conversation_id")))
    }

    @Test
    fun `the id is the same on the next turn of a conversation, and different for another conversation`() {
        val turn1 = listOf(user("Summarise the release notes"))
        val turn2 = turn1 + assistant("Here is the summary.") + user("Now translate it")
        val (_, first) = send(oauth(), turn1)
        val (body2, second) = send(oauth(), turn2)
        assertEquals(first.header("Session_id"), second.header("Session_id"))
        assertEquals(first.header("Conversation_id"), second.header("Conversation_id"))
        assertEquals(body2.getString("prompt_cache_key"), second.header("Session_id"))

        val (_, other) = send(oauth(), listOf(user("A different conversation")))
        assertNotEquals(first.header("Session_id"), other.header("Session_id"))
    }

    @Test
    fun `no key, no headers`() {
        val provider = oauth()
        for (key in listOf(null, "", "   ")) {
            val request = runBlocking { provider.buildRequest("{}", promptCacheKey = key) }
            assertNull(request.header("Session_id"))
            assertNull(request.header("Conversation_id"))
        }
        assertEquals(emptyMap<String, String>(), OpenAIProvider.codexConversationHeaders(null))
        assertEquals(
            mapOf("Session_id" to "minis-abc", "Conversation_id" to "minis-abc"),
            OpenAIProvider.codexConversationHeaders(" minis-abc "),
        )
    }

    @Test
    fun `api-key, custom-base and proxy Responses requests never carry them, even when handed a key`() {
        val providers = listOf(
            OpenAIProvider(apiKey = "k", model = model, basePath = "https://api.openai.com/v1", useResponsesAPI = true),
            OpenAIProvider(apiKey = "k", model = model, basePath = "https://proxy.example.com/v1", useResponsesAPI = true),
            OpenAIProvider(apiKey = "k", model = model, basePath = "https://proxy.example.com/v1", useResponsesAPI = false),
        )
        for (provider in providers) {
            val (body, request) = send(provider, listOf(user("Summarise the release notes")))
            assertFalse(request.url.toString(), request.url.host == "chatgpt.com")
            assertNull(request.url.toString(), request.header("Session_id"))
            assertNull(request.url.toString(), request.header("Conversation_id"))
            assertTrue("the body key is not affected", body.getString("prompt_cache_key").startsWith("minis-"))
        }
    }

    @Test
    fun `the stream path takes the key from the final body, and only the oauth branch sets the headers`() {
        val src = ProductionSources.read("provider/openai/OpenAIProvider.kt")
        assertTrue(src.contains("promptCacheKey = body.optString(\"prompt_cache_key\"),"))
        val build = src.substring(src.indexOf("internal suspend fun buildRequest("))
        val oauthStart = build.indexOf("if (isOAuth && !forceChatCompletions) {")
        val oauthEnd = build.indexOf("return builder.build()", oauthStart)
        val set = build.indexOf("for ((name, value) in codexConversationHeaders(promptCacheKey)) builder.header(name, value)")
        assertTrue(set in oauthStart until oauthEnd)
        assertEquals("set in one place", 1, src.split("in codexConversationHeaders(").size - 1)
        // The request log prints header names only.
        assertTrue(src.contains("\"headerKeys=\${request.headers.names()} authPresent=\$authPresent \" +"))
    }

    @Test
    fun `a known account id is sent on the oauth request, an unknown one is left out`() {
        val (_, with) = send(oauth("acct-123"), listOf(user("hi")))
        assertEquals("acct-123", with.header("Chatgpt-Account-Id"))
        val (_, without) = send(oauth(null), listOf(user("hi")))
        assertNull(without.header("Chatgpt-Account-Id"))
    }
}
