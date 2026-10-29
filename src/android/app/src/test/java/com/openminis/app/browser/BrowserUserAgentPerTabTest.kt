package com.openminis.app.browser

import com.openminis.app.ProductionSources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ISS-033] The browser UA setting applies to every tab opened after it
 * changes, in any chat, and never changes a tab already open;
 * set_user_agent switches one tab only (port of iOS ceeb63bbd).
 */
class BrowserUserAgentPerTabTest {

    private val pool = ProductionSources.read("browser/BrowserTabPool.kt")
    private val manager = ProductionSources.read("browser/BrowserUseManager.kt")

    @Test
    fun `user_agent accepts aliases and case, and rejects the unknown`() {
        assertEquals(UserAgentProfile.DESKTOP_CHROME, UserAgentProfile.parse("desktop"))
        assertEquals(UserAgentProfile.DESKTOP_CHROME, UserAgentProfile.parse("Desktop-Chrome"))
        assertEquals(UserAgentProfile.MOBILE_CHROME, UserAgentProfile.parse(" MOBILE "))
        assertEquals(UserAgentProfile.MOBILE_CHROME, UserAgentProfile.parse("mobile_chrome"))
        assertNull(UserAgentProfile.parse("tablet"))
        assertNull(UserAgentProfile.parse(null))
        val input = BrowserActionInput.parse("""{"action":"set_user_agent","user_agent":"desktop"}""")!!
        assertEquals(UserAgentProfile.DESKTOP_CHROME, input.userAgent)
        assertEquals("desktop", input.userAgentRaw)
    }

    @Test
    fun `every new tab re-reads the global default, in any pool`() {
        assertTrue(pool.contains("private fun syncGlobalDefaultsFromStore()"))
        val create = pool.substringAfter("syncGlobalDefaultsFromStore()\n        val id = nextTabId++\n        val webView = WebView(context)")
        assertTrue("createTab syncs before building the manager", create.isNotEmpty())
        assertTrue("window.open syncs too",
            pool.contains("syncGlobalDefaultsFromStore()\n        val id = nextTabId++\n        val newWebView = WebView(context)"))
    }

    @Test
    fun `a settings change never touches an open tab`() {
        val ui = pool.substringAfter("fun setUserAgentFromUI(").substringBefore("\n    }\n")
        assertFalse(ui.contains("for (tab in"))
        assertFalse(ui.contains("applyViewportToAllTabs"))
        val vp = pool.substringAfter("suspend fun setGlobalViewport(").substringBefore("\n    }\n")
        assertFalse(vp.contains("applyViewportToAllTabs()"))
    }

    @Test
    fun `set_user_agent changes its own tab only and errors on a bad value`() {
        val fn = manager.substringAfter("private suspend fun setUserAgent(profile: UserAgentProfile?, raw: String?)")
            .substringBefore("// -- User Navigation --")
        assertTrue(fn.contains("return BrowserActionResult.error("))
        assertFalse("no silent fallback to mobile", fn.contains("?: UserAgentProfile.MOBILE_CHROME"))
        assertFalse("nothing persisted", fn.contains("getSharedPreferences"))
        assertTrue("usual viewport precedence", fn.contains("viewportOverride() ?: newProfile.viewportSize"))
        assertTrue(pool.contains("manager.viewportOverride = { customViewportOverride() }"))
    }
}
