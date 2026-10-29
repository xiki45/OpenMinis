package com.openminis.app.mcp.oauth

import android.content.Context
import android.content.ContextWrapper
import android.content.res.Resources
import org.robolectric.RuntimeEnvironment
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
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
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLDecoder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * [T-mcp-oauth-signin-android] The browser sign-in end to end, against a mock
 * MCP server + authorization server, with a fake "browser" that answers the
 * loopback redirect the way the real page does (iOS #432; Windows
 * mcp_oauth.rs sign_in tests).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class McpOAuthSignInTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var server: MockWebServer
    private val registrations = AtomicInteger()
    private val tokenForms = CopyOnWriteArrayList<String>()

    /** What the token endpoint answers, by call number (1-based). */
    private var tokenAnswer: (Int) -> MockResponse = { ok("access-$it") }

    /** What the fake browser sends back to the loopback. */
    private var browser: (authUrl: String) -> String? = { url -> "code=the-code&state=${param(url, "state")}" }

    private lateinit var secrets: FakeMcpOAuthSecrets
    private lateinit var cache: FakeMcpDcrCache
    private lateinit var bridgeDir: File
    private val opened = CopyOnWriteArrayList<String>()

    private fun base() = server.url("/").toString().trimEnd('/')

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when (path) {
                    "/mcp" -> MockResponse().setResponseCode(401)
                        .setHeader("WWW-Authenticate", """Bearer resource_metadata="${base()}/prm"""")
                    "/prm" -> json("""{"authorization_servers":["${base()}/as"]}""")
                    "/as/.well-known/oauth-authorization-server" -> json(
                        """{"issuer":"${base()}/as","authorization_endpoint":"${base()}/as/authorize",
                           "token_endpoint":"${base()}/as/token","registration_endpoint":"${base()}/as/register",
                           "code_challenge_methods_supported":["S256"]}""",
                    )
                    "/as/register" -> {
                        val n = registrations.incrementAndGet()
                        MockResponse().setResponseCode(201).setBody("""{"client_id":"client-$n"}""")
                    }
                    "/as/token" -> {
                        tokenForms.add(request.body.readUtf8())
                        tokenAnswer(tokenForms.size)
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        secrets = FakeMcpOAuthSecrets()
        cache = FakeMcpDcrCache()
        bridgeDir = tmp.newFolder("oauth")
    }

    @After
    fun tearDown() = server.shutdown()

    private fun json(body: String) = MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody(body)

    private fun ok(access: String) =
        json("""{"access_token":"$access","refresh_token":"refresh-1","expires_in":3600,"token_type":"Bearer"}""")

    private fun param(url: String, name: String): String? =
        URI(url).rawQuery.split('&').map { it.split('=', limit = 2) }
            .firstOrNull { it[0] == name }?.getOrNull(1)?.let { URLDecoder.decode(it, "UTF-8") }

    private fun controller(timeoutMs: Long = 20_000, dismissAfterMs: Long? = null) = MCPOAuthController(
        context = StringsContext(RuntimeEnvironment.getApplication()),
        secrets = secrets,
        dcrCache = cache,
        http = OkHttpClient(),
        bridgeDir = bridgeDir,
        openBrowser = { authUrl ->
            opened.add(authUrl)
            val query = browser(authUrl) ?: return@MCPOAuthController
            val redirect = param(authUrl, "redirect_uri")!!
            thread {
                Thread.sleep(150)
                val conn = URL("$redirect?$query").openConnection() as HttpURLConnection
                runCatching { conn.inputStream.use { it.readBytes() } }
                conn.disconnect()
            }
        },
        awaitForeground = {},
        callbackTimeoutMs = timeoutMs,
        // The user closing the page = Minis back in front with no redirect.
        watchDismissal = { onDismissed ->
            val t = dismissAfterMs?.let { ms -> thread { runCatching { Thread.sleep(ms); onDismissed() } } }
            val stop: () -> Unit = { t?.interrupt() }
            stop
        },
    )

    private fun signIn(c: MCPOAuthController = controller(), persisted: MutableList<MCPOAuthConfig> = mutableListOf(),
                       phases: MutableList<MCPOAuthController.SignInPhase> = mutableListOf()) =
        runBlocking {
            c.signIn("srv", "${base()}/mcp", persist = { persisted.add(it) }, progress = { phases.add(it) })
        }

    @Test
    fun `one action signs in, saves the entry first and writes the guest bridge`() {
        val persisted = mutableListOf<MCPOAuthConfig>()
        val phases = mutableListOf<MCPOAuthController.SignInPhase>()
        val out = signIn(persisted = persisted, phases = phases)

        assertTrue(out is MCPOAuthController.SignInOutcome.SignedIn)
        assertEquals("client-1", persisted.single().clientId)
        assertEquals("dynamic", persisted.single().mode)
        assertEquals(
            listOf(
                MCPOAuthController.SignInPhase.DISCOVERING,
                MCPOAuthController.SignInPhase.WAITING_FOR_BROWSER,
                MCPOAuthController.SignInPhase.FINISHING,
            ),
            phases,
        )
        // The page carried PKCE, the client and the RFC 8707 resource.
        val page = opened.single()
        assertEquals("client-1", param(page, "client_id"))
        assertEquals("S256", param(page, "code_challenge_method"))
        assertEquals("${base()}/mcp", param(page, "resource"))
        // The token request sent the code, the verifier and the resource.
        val form = tokenForms.single()
        assertTrue(form, form.contains("code=the-code"))
        assertTrue(form, form.contains("code_verifier="))
        assertTrue(form, form.contains("resource="))
        assertEquals("access-1", secrets.tokens("srv")?.accessToken)

        // The guest bridge minis-mcp-cli reads.
        val bridge = JSONObject(File(bridgeDir, "srv.json").readText())
        assertEquals("access-1", bridge.getString("access_token"))
        assertEquals("refresh-1", bridge.getString("refresh_token"))
        assertEquals("${base()}/as/token", bridge.getString("token_endpoint"))
        assertEquals("client-1", bridge.getString("client_id"))
        assertEquals("${base()}/mcp", bridge.getString("resource"))
        assertTrue(bridge.getLong("expires_at") > System.currentTimeMillis() / 1000)
    }

    @Test
    fun `sign-in registers again when the server forgot the client`() {
        tokenAnswer = { n -> if (n == 1) MockResponse().setResponseCode(401).setBody("""{"error":"invalid_client"}""") else ok("access-$n") }
        val phases = mutableListOf<MCPOAuthController.SignInPhase>()
        val out = signIn(phases = phases)
        assertTrue(out is MCPOAuthController.SignInOutcome.SignedIn)
        assertEquals("client-2", (out as MCPOAuthController.SignInOutcome.SignedIn).config.clientId)
        assertEquals(2, registrations.get())
        assertEquals(2, opened.size) // the page opened a second time
        assertTrue(phases.contains(MCPOAuthController.SignInPhase.REGISTERING_AGAIN))
        // ...but only once: the cached client-2 is refused, client-3 is
        // registered and refused too, and the sign-in ends there.
        tokenAnswer = { MockResponse().setResponseCode(401).setBody("""{"error":"invalid_client"}""") }
        val again = signIn()
        assertEquals(
            MCPOAuthController.SignInOutcome.NotSignedIn(MCPOAuthController.Result.ClientRejected),
            again,
        )
        assertEquals(3, registrations.get())
    }

    @Test
    fun `a refused sign-in says why`() {
        // Deny on the consent page.
        browser = { url -> "error=access_denied&state=${param(url, "state")}" }
        assertEquals(MCPOAuthController.SignInOutcome.NotSignedIn(MCPOAuthController.Result.Denied), signIn())

        // invalid_grant clears the tokens, keeps the registration.
        browser = { url -> "code=c&state=${param(url, "state")}" }
        secrets.setTokens("srv", MCPOAuthStore.StoredTokens("old", null, 0))
        File(bridgeDir, "srv.json").writeText("""{"access_token":"old"}""")
        tokenAnswer = { MockResponse().setResponseCode(400).setBody("""{"error":"invalid_grant"}""") }
        assertEquals(MCPOAuthController.SignInOutcome.NotSignedIn(MCPOAuthController.Result.GrantRejected), signIn())
        assertNull(secrets.tokens("srv"))
        assertFalse(File(bridgeDir, "srv.json").exists())
        assertEquals(1, registrations.get())

        // A page nobody answers times out.
        browser = { null }
        assertEquals(
            MCPOAuthController.SignInOutcome.NotSignedIn(MCPOAuthController.Result.TimedOut),
            signIn(controller(timeoutMs = 500)),
        )

        // Closing the page ends the sign-in as "not completed" at once,
        // not after the timeout (A3 device test, bug 1).
        val closedAt = System.currentTimeMillis()
        assertEquals(
            MCPOAuthController.SignInOutcome.NotSignedIn(MCPOAuthController.Result.Cancelled),
            signIn(controller(timeoutMs = 20_000, dismissAfterMs = 300)),
        )
        assertTrue(System.currentTimeMillis() - closedAt < 10_000)
        // A redirect that lands before the user is back still signs in: the
        // late "back in front" finds nothing left to stop.
        browser = { url -> "code=c&state=${param(url, "state")}" }
        tokenAnswer = { ok("after-return") }
        assertTrue(signIn(controller(dismissAfterMs = 2_000)) is MCPOAuthController.SignInOutcome.SignedIn)

        // Other token errors are shown as the service said them.
        browser = { url -> "code=c&state=${param(url, "state")}" }
        tokenAnswer = { MockResponse().setResponseCode(500).setBody("""{"error":"server_error","error_description":"try later"}""") }
        assertEquals(
            MCPOAuthController.SignInOutcome.NotSignedIn(MCPOAuthController.Result.Failed("Token exchange failed: try later")),
            signIn(),
        )
    }

    @Test
    fun `reset forgets the registration and the sign-in`() {
        val c = controller()
        signIn(c)
        assertTrue(c.isAuthorized("srv"))
        c.resetSignIn("srv", "client-1")
        assertFalse(c.isAuthorized("srv"))
        assertFalse(File(bridgeDir, "srv.json").exists())
        assertNull(cache.v2()?.opt("${base()}/as"))
        signIn(c)
        assertEquals(2, registrations.get())
    }

    @Test
    fun `freshness renews an expired token or signs out`() {
        val c = controller()
        val oauth = MCPOAuthConfig("dynamic", "client-1", "${base()}/as/authorize", "${base()}/as/token")
        // Fresh: nothing is sent.
        secrets.setTokens("srv", MCPOAuthStore.StoredTokens("a", "r", System.currentTimeMillis() + 3_600_000))
        assertEquals(MCPOAuthController.Freshness.FRESH, runBlocking { c.freshness("srv", oauth, "${base()}/mcp") })
        assertTrue(tokenForms.isEmpty())
        // Expired: renewed with the refresh token, bridge rewritten.
        secrets.setTokens("srv", MCPOAuthStore.StoredTokens("a", "r", System.currentTimeMillis() - 1_000))
        assertEquals(MCPOAuthController.Freshness.RENEWED, runBlocking { c.freshness("srv", oauth, "${base()}/mcp") })
        assertTrue(tokenForms.last().contains("grant_type=refresh_token"))
        assertEquals("access-1", JSONObject(File(bridgeDir, "srv.json").readText()).getString("access_token"))
        // Refused renewal: signed out.
        secrets.setTokens("srv", MCPOAuthStore.StoredTokens("a", "r", System.currentTimeMillis() - 1_000))
        File(bridgeDir, "srv.json").delete()
        tokenAnswer = { MockResponse().setResponseCode(400).setBody("""{"error":"invalid_grant"}""") }
        assertEquals(MCPOAuthController.Freshness.NEEDS_SIGN_IN, runBlocking { c.freshness("srv", oauth, "${base()}/mcp") })
        assertNull(secrets.tokens("srv"))
        // Not signed in at all.
        assertEquals(MCPOAuthController.Freshness.NEEDS_SIGN_IN, runBlocking { c.freshness("other", oauth, null) })
    }
}

/**
 * This module's Robolectric runs without Android resources
 * (includeAndroidResources is off), so a string lookup throws. The loopback's
 * error path builds its message through the context; this one answers it.
 */
private class StringsContext(base: Context) : ContextWrapper(base) {
    @Suppress("DEPRECATION")
    private val res = object : Resources(base.resources.assets, base.resources.displayMetrics, base.resources.configuration) {
        override fun getString(id: Int): String = "denied on the page"
        override fun getString(id: Int, vararg formatArgs: Any?): String = "callback error: ${formatArgs.joinToString()}"
        override fun getText(id: Int): CharSequence = "text"
    }

    override fun getResources(): Resources = res
    override fun getApplicationContext(): Context = this
}
