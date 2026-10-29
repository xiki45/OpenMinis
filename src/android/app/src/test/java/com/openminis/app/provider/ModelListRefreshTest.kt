package com.openminis.app.provider

import com.openminis.app.ProductionSources
import com.openminis.app.provider.openai.OpenAIModelsApi
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [T-android-model-refresh-visible] "Refresh model list does nothing" (Open
 * Minis group, 2026-10-06, SenseNova at token.sensenova.cn/v1). With a valid
 * key the real endpoint refreshes fine (Pixel 4a, 2026-10-07: STEP1 vendor-api,
 * 10 models). What the user could see as "nothing" was every FAILURE: a 401, a
 * body that is not `{"data":[...]}`, an empty list - each returned an empty
 * list, the custom base had no models.dev match, the old list was kept, and the
 * button said nothing. These pin that a custom-base fetch now (a) reads the
 * shapes gateways send and (b) says why when it cannot use the answer.
 */
class ModelListRefreshTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() { server = MockWebServer(); server.start() }

    @After
    fun tearDown() = server.shutdown()

    private fun base() = server.url("/v1").toString().trimEnd('/')

    private fun fetch(body: String, code: Int = 200): OpenAIModelsApi.FetchResult = runBlocking {
        server.enqueue(MockResponse().setResponseCode(code).setBody(body))
        OpenAIModelsApi.fetchModelsDetailed("k", base(), forceRefresh = true)
    }

    private fun ids(r: OpenAIModelsApi.FetchResult) = r.models.map { it.id }

    // ── shapes ──────────────────────────────────────────────────────────

    @Test
    fun `standard data array`() {
        val r = fetch("""{"object":"list","data":[{"id":"sensenova-6.8-flash-lite","object":"model"},{"id":"glm-5.2"}]}""")
        assertNull(r.error)
        assertEquals(listOf("sensenova-6.8-flash-lite", "glm-5.2"), ids(r))
    }

    @Test
    fun `models wrapper, nested data, bare array`() {
        assertEquals(listOf("a", "b"), ids(fetch("""{"models":[{"id":"a"},{"id":"b"}]}""")))
        assertEquals(listOf("a"), ids(fetch("""{"data":{"models":[{"id":"a"}]}}""")))
        assertEquals(listOf("a", "b"), ids(fetch("""[{"id":"a"},{"id":"b"}]""")))
    }

    @Test
    fun `ids under model or name, and bare id strings`() {
        assertEquals(listOf("m1", "n1"), ids(fetch("""{"data":[{"model":"m1"},{"name":"n1"}]}""")))
        assertEquals(listOf("s1", "s2"), ids(fetch("""{"data":["s1","s2"]}""")))
    }

    @Test
    fun `an id that is also the name gets the derived display name, not a duplicate`() {
        val r = fetch("""{"data":[{"name":"deepseek-v4.1-flash"}]}""")
        assertEquals("deepseek-v4.1-flash", r.models.single().id)
    }

    // ── failures are named, and a custom base keeps the user's list ─────

    @Test
    fun `401 says so with the provider's message`() {
        val r = fetch("""{"error":{"code":16,"message":"Authorization Not Found"}}""", code = 401)
        assertTrue("empty: the caller keeps the existing entries", r.models.isEmpty())
        assertEquals("HTTP 401: Authorization Not Found", r.error)
    }

    @Test
    fun `an error object in a 200 is not a model list`() {
        val r = fetch("""{"error":{"message":"quota exceeded"}}""")
        assertTrue(r.models.isEmpty())
        assertTrue(r.error!!, r.error!!.startsWith("the response is not a model list: quota exceeded"))
    }

    @Test
    fun `an empty list is reported, not taken as success`() {
        val r = fetch("""{"data":[]}""")
        assertTrue(r.models.isEmpty())
        assertEquals("the provider listed no models", r.error)
    }

    @Test
    fun `html from a misconfigured base url is reported`() {
        val r = fetch("<html><body>Not Found</body></html>", code = 404)
        assertTrue(r.error!!.startsWith("HTTP 404: <html>"))
    }

    @Test
    fun `a network failure still throws for the caller to report`() {
        server.shutdown()
        val thrown = runCatching { runBlocking { OpenAIModelsApi.fetchModelsDetailed("k", base(), forceRefresh = true) } }
        assertTrue(thrown.isFailure)
    }

    @Test
    fun `fetchModels keeps its old contract`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody("{}"))
        assertTrue(OpenAIModelsApi.fetchModels("k", base(), forceRefresh = true).isEmpty())
    }

    // ── the outcome reaches the user ─────────────────────────────────────

    @Test
    fun `refresh returns an outcome and the Refresh row and the RPC show it`() {
        val repo = ProductionSources.read("data/repository/ProviderRepository.kt")
        assertTrue(repo.contains("): ModelRefreshOutcome {"))
        assertTrue("the openAI branch uses the detailed fetch",
            repo.contains("OpenAIModelsApi.fetchModelsDetailed(apiKey, baseURL, customUserAgent = instance.customUserAgent,"))
        assertTrue(repo.contains("reportVendorError(\"the provider returned no models\")"))
        assertTrue(repo.contains("reportVendorError(\"no API key for this provider\")"))
        assertTrue(repo.contains("return ModelRefreshSource.KEPT"))
        val screen = ProductionSources.read("ui/settings/ProviderDetailScreen.kt")
        assertTrue(screen.contains("refreshOutcome = providerRepository.refreshModels(instance, forceRefresh = true)"))
        assertTrue(screen.contains("stringResource(R.string.provider_refresh_result_kept, error)"))
        val rpc = ProductionSources.read("debug/ProviderMutationMethods.kt")
        assertTrue(rpc.contains("put(\"vendorError\", e)"))
    }
}
