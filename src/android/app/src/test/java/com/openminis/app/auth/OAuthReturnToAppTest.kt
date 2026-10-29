package com.openminis.app.auth

import com.openminis.app.ProductionSources
import com.openminis.app.auth.OAuthCallbackServer.Forward
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-oauth-return-to-app] Field log (Huawei, Android 12): Chrome requested
 * localhost:1455 (served 200 at 42.000), also handed the same URL to
 * OAuthRedirectActivity (42.001) and aborted the tab. The activity forwarded
 * into a closed port and finished; nothing brought Minis back, so the
 * exchange waited for the foreground and the user waited on a spinning page.
 */
class OAuthReturnToAppTest {

    private fun d(listening: Boolean, servedAt: Long?, servedState: String? = "s1", redirectState: String? = "s1", now: Long = 1_000L) =
        OAuthCallbackServer.forwardDecision(listening, servedAt, servedState, redirectState, now)

    @Test
    fun `a redirect the server already answered is not forwarded`() {
        // The field case: served a millisecond before the hand-off, server now closed.
        assertEquals(Forward.ALREADY_SERVED, d(listening = false, servedAt = 999L))
        assertEquals(Forward.ALREADY_SERVED, d(listening = true, servedAt = 999L))
        // An error redirect is stored without a state: any redirect then matches.
        assertEquals(Forward.ALREADY_SERVED, d(listening = false, servedAt = 999L, servedState = null))
    }

    @Test
    fun `a redirect the server is still waiting for is forwarded, so the code is not lost`() {
        assertEquals(Forward.FORWARD, d(listening = true, servedAt = null))
        // Answered a different callback (another state): still forward.
        assertEquals(Forward.FORWARD, d(listening = true, servedAt = 999L, servedState = "old", redirectState = "new"))
        // Answered long ago: a new flow.
        assertEquals(Forward.FORWARD, d(listening = true, servedAt = 0L, now = OAuthCallbackServer.SERVED_WINDOW_MS + 1))
    }

    @Test
    fun `nothing listening and nothing answered - nothing to forward to`() {
        assertEquals(Forward.NOT_LISTENING, d(listening = false, servedAt = null))
    }

    @Test
    fun `the success page links back to Minis with an intent URL, ASCII only`() {
        val url = OAuthCallbackServer.returnToAppUrl("com.openminis.app")
        assertEquals("intent://oauth/return#Intent;scheme=minis;package=com.openminis.app;end", url)
        val page = OAuthCallbackServer.successPage("com.openminis.app")
        assertTrue(page.contains("href=\"$url\""))
        assertTrue(page.contains(">Return to Minis</a>"))
        assertTrue(page.contains("Return to Minis to finish signing in."))
        assertTrue("Content-Length is String.length", page.all { it.code < 128 })
        assertFalse("no auto-redirect", page.contains("location.href") || page.contains("http-equiv=\"refresh\""))
        // The deep link lands on nothing: minis://oauth is not a route.
        assertFalse(ProductionSources.read("deeplink/DeepLinkHandler.kt").contains("\"oauth\" ->"))
    }

    @Test
    fun `the redirect activity brings MainActivity forward like AppAuth, after any forward, before finish`() {
        val src = ProductionSources.read("auth/OAuthRedirectActivity.kt")
        val onCreate = src.substringAfter("override fun onCreate(").substringBefore("private fun forwardIfNeeded(")
        val bring = onCreate.indexOf("bringAppForward()\n        finish()")
        assertTrue(onCreate.indexOf("if (uri != null) forwardIfNeeded(uri)") in 0 until bring)
        val body = src.substringAfter("private fun bringAppForward()")
        assertTrue(body.contains("Intent(this, com.openminis.app.MainActivity::class.java)"))
        assertTrue(body.contains(".addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)"))
        assertFalse("no data: handleDeepLink(null) is a no-op", body.substringBefore("} catch").contains("setData") || body.contains(".data ="))
        assertTrue(body.contains("Log.i(TAG, \"Redirect handed to the app - bringing MainActivity forward\")"))
        assertTrue(src.contains("Log.i(TAG, \"Server on :\$port already answered this callback - not forwarding\")"))
        // An error redirect is forwarded too, so a denial ends the flow.
        assertTrue(src.contains("if (!hasCode && !hasError) {"))
    }

    @Test
    fun `the server marks a callback served before answering and registers by instance`() {
        val src = ProductionSources.read("auth/OAuthCallbackServer.kt")
        val code = src.substringAfter("is CallbackRequest.Code -> {").substringBefore("is CallbackRequest.Error -> {")
        assertTrue(code.indexOf("served[boundPort] = Served(") in 0 until code.indexOf("socket.getOutputStream().write(response"))
        assertTrue(src.contains("listening[boundPort] = this"))
        assertTrue(src.contains("listening.remove(boundPort, this)"))
        assertTrue(src.contains("served.remove(boundPort)"))
    }

    // ── [T-oauth-diag-removal] What the diagnostics' removal must not take with it ──

    @Test
    fun `the tab opens in the caller's task, NEW_TASK only when asked, with no session bind`() {
        val launcher = ProductionSources.read("auth/OAuthTabLauncher.kt")
        assertTrue(launcher.contains("if (newTask) tabs.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)"))
        assertTrue(launcher.contains(".setShowTitle(true)"))
        assertFalse("a session bind delays the tab by up to 1 s", launcher.contains("bindCustomTabsService("))
        assertFalse(launcher.contains("import androidx.browser.customtabs.CustomTabsSession"))
        assertFalse(launcher.contains("CustomTabsIntent.Builder(session"))
    }

    @Test
    fun `every browser sign-in launches through the launcher, NEW_TASK only for OpenRouter`() {
        val sites = mapOf(
            "auth/OpenAIOAuthManager.kt" to "OAuthTabLauncher.launch(context, Uri.parse(authUrl))",
            "auth/ClaudeOAuthManager.kt" to "OAuthTabLauncher.launch(context, Uri.parse(authUrl))",
            "auth/XAIOAuthManager.kt" to "OAuthTabLauncher.launch(context, Uri.parse(authUrl))",
            "mcp/oauth/MCPOAuthController.kt" to "com.openminis.app.auth.OAuthTabLauncher.launch(context, Uri.parse(authUrl))",
            "auth/OpenRouterOAuthManager.kt" to "OAuthTabLauncher.launch(context, authUrl, newTask = true)",
        )
        for ((file, call) in sites) {
            val src = ProductionSources.read(file)
            assertTrue("$file launches via OAuthTabLauncher", src.contains(call))
            assertFalse("$file builds its own CustomTabsIntent", src.contains("CustomTabsIntent.Builder"))
        }
    }

    @Test
    fun `the callback server reads the request line as before, with no diagnostic read timeout`() {
        val server = ProductionSources.read("auth/OAuthCallbackServer.kt")
        assertTrue(server.contains("val requestLine = reader.readLine() ?: continue"))
        assertFalse(server.contains("soTimeout"))
    }
}
