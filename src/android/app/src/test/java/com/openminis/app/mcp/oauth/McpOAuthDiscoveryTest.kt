package com.openminis.app.mcp.oauth

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * [T-mcp-oauth-signin-android] Discovery + dynamic registration against a mock
 * MCP server and authorization server (iOS #432; Windows mcp_oauth.rs tests).
 */
class McpOAuthDiscoveryTest {

    private lateinit var server: MockWebServer
    private val registrations = AtomicInteger()
    private val registrationBodies = CopyOnWriteArrayList<JSONObject>()
    private val redirect = MCPOAuthController.DEFAULT_REDIRECT_URI

    // Knobs a test turns before it runs discovery.
    private var withHeader = true
    private var protectedStatus = 401
    private var withRegistration = true
    private var pkceMethods: List<String>? = listOf("S256")
    private var registerStatus = 201
    private var registerBody = { n: Int -> """{"client_id":"client-$n"}""" }

    private lateinit var secrets: FakeMcpOAuthSecrets
    private lateinit var cache: FakeMcpDcrCache
    private var now = 1_000.0

    private fun base() = server.url("/").toString().trimEnd('/')

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path == "/mcp" -> MockResponse().setResponseCode(protectedStatus).apply {
                        if (withHeader) {
                            setHeader("WWW-Authenticate", """Bearer realm="x", resource_metadata="${base()}/prm"""")
                        }
                    }
                    path == "/prm" || path == "/.well-known/oauth-protected-resource" ->
                        json("""{"resource":"${base()}/mcp","authorization_servers":["${base()}/as"]}""")
                    path == "/as/.well-known/oauth-authorization-server" -> {
                        val meta = JSONObject()
                            .put("issuer", "${base()}/as")
                            .put("authorization_endpoint", "${base()}/as/authorize")
                            .put("token_endpoint", "${base()}/as/token")
                            .put("scopes_supported", org.json.JSONArray().put("read").put("write"))
                        pkceMethods?.let { meta.put("code_challenge_methods_supported", org.json.JSONArray(it)) }
                        if (withRegistration) meta.put("registration_endpoint", "${base()}/as/register")
                        json(meta.toString())
                    }
                    path == "/as/register" -> {
                        registrationBodies.add(JSONObject(request.body.readUtf8()))
                        val n = registrations.incrementAndGet()
                        MockResponse().setResponseCode(registerStatus).setBody(registerBody(n))
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        secrets = FakeMcpOAuthSecrets()
        cache = FakeMcpDcrCache()
    }

    @After
    fun tearDown() = server.shutdown()

    private fun json(body: String) = MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody(body)

    private fun discovery() = MCPOAuthDiscovery(secrets, cache, now = { now })

    private fun discover(force: Boolean = false, redirectUri: String = redirect) =
        runBlocking { discovery().discover("${base()}/mcp", redirectUri, force) }

    private fun kindOf(block: () -> Unit): McpDiscoveryException {
        try {
            block()
        } catch (e: McpDiscoveryException) {
            return e
        }
        fail("expected a McpDiscoveryException")
        throw IllegalStateException()
    }

    @Test
    fun `discovery registers once per issuer and reuses the registration`() {
        val first = discover()
        assertEquals("client-1", first.config.clientId)
        assertEquals("dynamic", first.config.mode)
        assertEquals("${base()}/as/authorize", first.config.authorizationEndpoint)
        assertEquals("${base()}/as/token", first.config.tokenEndpoint)
        assertEquals("read write", first.config.scopes)
        assertEquals(redirect, first.config.redirectUri)
        assertEquals("${base()}/as", first.issuer)
        assertFalse(first.reusedCachedClient)

        val second = discover()
        assertEquals("client-1", second.config.clientId)
        assertTrue(second.reusedCachedClient)
        assertEquals(1, registrations.get())

        // The registration is a public native client with the loopback redirect.
        val body = registrationBodies.single()
        assertEquals("native", body.getString("application_type"))
        assertEquals("none", body.getString("token_endpoint_auth_method"))
        assertEquals(redirect, body.getJSONArray("redirect_uris").getString(0))
        // The v2 cache entry keeps the redirect URI and when it was made.
        val rec = MCPOAuthSignInLogic.ClientRecord.fromJson(cache.v2()?.opt("${base()}/as"))
        assertEquals(MCPOAuthSignInLogic.ClientRecord("client-1", redirect, 1_000.0, null), rec)
    }

    @Test
    fun `a stale registration is not reused`() {
        discover()
        // Another redirect URI: registered again.
        discover(redirectUri = "http://localhost:1234/callback")
        assertEquals(2, registrations.get())
        // A secret that expired: registered again.
        registerBody = { n -> """{"client_id":"client-$n","client_secret":"s$n","client_secret_expires_at":1500}""" }
        val withSecret = discover(force = true)
        assertEquals("s3", withSecret.clientSecret)
        assertEquals("s3", secrets.registrationSecret("${base()}/as"))
        now = 2_000.0
        val renewed = discover()
        assertFalse(renewed.reusedCachedClient)
        assertEquals("client-4", renewed.config.clientId)
        // forceRegister skips a cache that would otherwise be reused.
        now = 1_000.0
        registerBody = { n -> """{"client_id":"client-$n"}""" }
        discover()
        assertEquals(4, registrations.get())
        discover(force = true)
        assertEquals(5, registrations.get())
    }

    @Test
    fun `a v1 registration is carried over to v2 the first time it is used`() {
        cache.setV1(JSONObject().put("${base()}/as", "legacy-client"))
        val out = discover()
        assertEquals("legacy-client", out.config.clientId)
        assertTrue(out.reusedCachedClient)
        assertEquals(0, registrations.get())
        assertEquals("legacy-client", MCPOAuthSignInLogic.ClientRecord.fromJson(cache.v2()?.opt("${base()}/as"))?.clientId)
        // v1 is left for an older build to find.
        assertEquals("legacy-client", cache.v1()?.optString("${base()}/as"))
    }

    @Test
    fun `forgetting a client drops both cache generations and the secret`() {
        registerBody = { n -> """{"client_id":"client-$n","client_secret":"sec"}""" }
        discover()
        cache.setV1(JSONObject().put("${base()}/as", "client-1"))
        assertEquals(1, discovery().forgetClient("client-1"))
        assertNull(cache.v2()?.opt("${base()}/as"))
        assertFalse(cache.v1()!!.has("${base()}/as"))
        assertNull(secrets.registrationSecret("${base()}/as"))
        discover()
        assertEquals(2, registrations.get())
    }

    @Test
    fun `discovery fallbacks and named failures`() {
        // 401 without the header: the well-known path is tried.
        withHeader = false
        assertEquals("client-1", discover().config.clientId)
        withHeader = true

        // No registration endpoint: the documented manual fallback.
        withRegistration = false
        val dcr = kindOf { discover(force = true) }
        assertEquals(McpDiscoveryException.Kind.DCR_UNSUPPORTED, dcr.kind)
        assertEquals("${base()}/as", dcr.detail)
        withRegistration = true

        // S256 not offered.
        pkceMethods = listOf("plain")
        assertEquals(McpDiscoveryException.Kind.NO_PKCE, kindOf { discover(force = true) }.kind)
        pkceMethods = null // not listed at all is fine
        discover(force = true)

        // A server that does not ask for authorization.
        protectedStatus = 200
        assertEquals(McpDiscoveryException.Kind.NOT_PROTECTED, kindOf { discover() }.kind)
        protectedStatus = 401

        // The server's own registration error is what the user reads.
        registerStatus = 400
        registerBody = { _ -> """{"error":"invalid_redirect_uri","error_description":"loopback not allowed"}""" }
        val rejected = kindOf { discover(force = true) }
        assertEquals(McpDiscoveryException.Kind.REGISTRATION_REJECTED, rejected.kind)
        assertEquals("loopback not allowed", rejected.detail)
        assertEquals(400, rejected.status)
    }

    @Test
    fun `www-authenticate parsing tolerates order, spacing and quoting`() {
        assertEquals("https://a/prm", MCPOAuthDiscovery.resourceMetadataUrl("""Bearer resource_metadata="https://a/prm""""))
        assertEquals("https://a/prm", MCPOAuthDiscovery.resourceMetadataUrl("""Bearer realm="x", Resource_Metadata = "https://a/prm", scope="y""""))
        assertEquals("https://a/prm", MCPOAuthDiscovery.resourceMetadataUrl("Bearer resource_metadata=https://a/prm, realm=x"))
        assertNull(MCPOAuthDiscovery.resourceMetadataUrl("Bearer realm=\"x\""))
        assertNull(MCPOAuthDiscovery.resourceMetadataUrl("Bearer resource_metadata=\"unterminated"))
    }
}
