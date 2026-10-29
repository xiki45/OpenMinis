package com.openminis.app.mcp.oauth

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLDecoder
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/**
 * [T-mcp-oauth-bridge-name] The server name comes from servers.json, which the
 * guest can write; it names the host's bridge file, so a '/' in it pointed the
 * host's writes and deletes outside the bridge directory.
 *
 * [T-mcp-oauth-never-throws] authorize() is documented "never throws", but an
 * endpoint that was not a URL threw out of it into the form's coroutine, which
 * has no handler, and crashed the app.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class McpOAuthGuardsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val opened = CopyOnWriteArrayList<String>()

    private fun controller(bridgeDir: File, answer: Boolean = false, browserFails: Boolean = false) = MCPOAuthController(
        context = RuntimeEnvironment.getApplication(),
        secrets = FakeMcpOAuthSecrets(),
        dcrCache = FakeMcpDcrCache(),
        http = OkHttpClient(),
        bridgeDir = bridgeDir,
        openBrowser = { authUrl ->
            opened.add(authUrl)
            if (browserFails) throw android.content.ActivityNotFoundException("no browser")
            if (!answer) return@MCPOAuthController
            val q = URI(authUrl).rawQuery.split('&').map { it.split('=', limit = 2) }
            fun p(n: String) = q.first { it[0] == n }[1].let { URLDecoder.decode(it, "UTF-8") }
            val redirect = p("redirect_uri")
            val state = p("state")
            thread {
                Thread.sleep(150)
                val conn = URL("$redirect?code=c&state=$state").openConnection() as HttpURLConnection
                runCatching { conn.inputStream.use { it.readBytes() } }
                conn.disconnect()
            }
        },
        awaitForeground = {},
        callbackTimeoutMs = 10_000,
    )

    @Test
    fun `a name is one plain file name or nothing`() {
        for (ok in listOf("srv", "My Server", "a.b", "...x", "日本語")) {
            assertTrue(ok, MCPOAuthController.isBridgeSafeName(ok))
        }
        for (bad in listOf("", "  ", ".", "..", "../evil", "a/b", "/abs", "a\\b", "x\u0000y")) {
            assertFalse(bad, MCPOAuthController.isBridgeSafeName(bad))
        }
    }

    @Test
    fun `the bridge file stays in the bridge directory`() {
        val dir = tmp.newFolder("oauth")
        val c = controller(dir)
        assertEquals(File(dir, "srv.json"), c.bridgeFile("srv"))
        assertNull(c.bridgeFile("../evil"))
        assertNull(c.bridgeFile("../../files/shared_prefs/x"))
        // Sign-out and purge of such a name delete nothing outside.
        val outside = File(dir.parentFile, "evil.json").apply { writeText("{}") }
        c.signOut("../evil")
        c.purge("../evil")
        assertTrue(outside.exists())
    }

    private val config = MCPOAuthConfig(
        clientId = "client",
        authorizationEndpoint = "http://127.0.0.1:1/authorize",
        tokenEndpoint = "http://127.0.0.1:1/token",
    )

    @Test
    fun `an unsafe name is refused before the page opens`() = runBlocking {
        val r = controller(tmp.newFolder("oauth")).authorize("../evil", null, config)
        assertTrue(r is MCPOAuthController.Result.Failed)
        assertTrue(opened.isEmpty())
    }

    @Test
    fun `a token endpoint that is not a URL is a failed result, not a crash`() = runBlocking {
        val r = controller(tmp.newFolder("oauth"), answer = true)
            .authorize("srv", null, config.copy(tokenEndpoint = "not a url"))
        assertTrue("got $r", r is MCPOAuthController.Result.Failed)
        assertEquals(1, opened.size)
    }

    @Test
    fun `a sign-in page that cannot be opened is a failed result, not a crash`() = runBlocking {
        val r = controller(tmp.newFolder("oauth"), browserFails = true).authorize("srv", null, config)
        assertNotNull(r)
        assertTrue("got $r", r is MCPOAuthController.Result.Failed)
    }
}
