package com.openminis.app.browser

import com.openminis.app.ProductionSources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ISS-040] Browser settings → Cookies & Website Data: Clear All gave no
 * feedback and the per-site delete icon did nothing.
 *
 * The CookieManager calls themselves were verified on a Pixel 4a (see the
 * commit); these pin the pure parts — which cookies a delete targets and how
 * they are expired — and, as source guards, the two root causes.
 */
class SiteCookieCleanerTest {

    // ── Pure helpers ───────────────────────────────────────────────────────

    @Test
    fun `cookie names are parsed from a getCookie header`() {
        assertEquals(setOf("a", "b", "flag", "__Host-x"),
            SiteCookieCleaner.cookieNames("a=1; b=2=3;  flag ; __Host-x=y"))
        assertEquals(emptySet<String>(), SiteCookieCleaner.cookieNames(null))
        assertEquals(emptySet<String>(), SiteCookieCleaner.cookieNames("  "))
    }

    @Test
    fun `a www entry also covers the bare host, nothing else is widened`() {
        assertEquals(listOf("www.example.com", "example.com"), SiteCookieCleaner.hostsFor("WWW.Example.com."))
        assertEquals(listOf("example.com"), SiteCookieCleaner.hostsFor("example.com"))
        assertEquals(listOf("mail.example.com"), SiteCookieCleaner.hostsFor("mail.example.com"))
        assertEquals("www. alone is not stripped to a bare TLD", listOf("www.com"), SiteCookieCleaner.hostsFor("www.com"))
        assertEquals(emptyList<String>(), SiteCookieCleaner.hostsFor(" "))
    }

    @Test
    fun `Domain candidates are the host and each parent with two or more labels`() {
        assertEquals(listOf("a.b.example.com", "b.example.com", "example.com"),
            SiteCookieCleaner.domainAttributes("a.b.example.com"))
        assertEquals(listOf("example.com"), SiteCookieCleaner.domainAttributes("example.com"))
        assertEquals("an IP has no parents", listOf("10.0.0.1"), SiteCookieCleaner.domainAttributes("10.0.0.1"))
        assertEquals(listOf("localhost"), SiteCookieCleaner.domainAttributes("localhost"))
    }

    @Test
    fun `every expiry is already expired, root-scoped, and comes with a Secure twin`() {
        val values = SiteCookieCleaner.expiryCookies("sid", "www.example.com")
        // host-only + www.example.com + example.com, each with and without Secure
        assertEquals(6, values.size)
        values.forEach {
            assertTrue(it, it.startsWith("sid=; "))
            assertTrue(it, it.contains("Max-Age=0"))
            assertTrue(it, it.contains("Expires=Thu, 01 Jan 1970 00:00:00 GMT"))
            assertTrue(it, it.contains("Path=/"))
        }
        assertTrue("host-only form (needed for __Host- cookies)",
            values.any { !it.contains("Domain=") && it.endsWith("; Secure") })
        assertTrue(values.any { it.contains("; Domain=example.com") && !it.contains("Secure") })
        assertTrue(values.any { it.contains("; Domain=www.example.com; Secure") })
    }

    @Test
    fun `the section lists only history sites that hold cookies, in history order`() {
        val history = listOf("a.com", "b.com", "c.com")
        assertEquals(listOf("a.com", "c.com"),
            SiteCookieCleaner.sitesWithCookies(history) { it != "b.com" })
        // After Clear All every probe is false: the list empties, so the
        // existing "No cookies stored" text shows instead of stale rows.
        assertEquals(emptyList<String>(), SiteCookieCleaner.sitesWithCookies(history) { false })
    }

    @Test
    fun `a result is cleared only when nothing is left`() {
        assertTrue(SiteCookieCleaner.Result("x", setOf("a"), emptySet()).cleared)
        assertFalse(SiteCookieCleaner.Result("x", setOf("a", "b"), setOf("b")).cleared)
    }

    // ── Source guards on the sheet ─────────────────────────────────────────

    private val sheet = ProductionSources.read("ui/browser/BrowserSettingsSheet.kt")

    @Test
    fun `hasCookies is no longer a bare composition-time read`() {
        assertFalse(sheet.contains("val hasCookies = CookieManager.getInstance().hasCookies()"))
        assertTrue(sheet.contains("var cookieRevision by remember { mutableIntStateOf(0) }"))
        assertTrue("the section reloads when the revision changes",
            sheet.contains("produceState<SiteCookieCleaner.SectionState?>(initialValue = null, cookieRevision)"))
        assertTrue(sheet.contains("val hasCookies = cookieSection?.hasCookies ?: false"))
    }

    @Test
    fun `the per-site delete expires real cookies and reports what is left`() {
        // The old call: a bare host where a URL belongs, and an empty cookie —
        // WebView ignores it. (The comment that explains this is allowed.)
        assertFalse("setCookie(domain, \"\") is a no-op", sheet.contains(".setCookie(domain"))
        val tap = sheet.substringAfter("items(domains) { domain ->").substringBefore("Icons.Default.Delete")
        assertTrue(tap.contains("withContext(Dispatchers.IO) { SiteCookieCleaner.clear(domain) }"))
        assertTrue("never silent", tap.contains("cookieDeleteFailed = if (result.cleared) null else domain"))
        assertTrue(tap.contains("cookieRevision++"))
        assertTrue(sheet.contains("R.string.browser_settings_clear_cookies_for_failed"))
    }

    @Test
    fun `Clear All refreshes from the removeAllCookies callback`() {
        assertFalse(sheet.contains("removeAllCookies(null)"))
        val confirm = sheet.substringAfter("CookieManager.getInstance().removeAllCookies { removed ->")
            .substringBefore("}) {")
        assertTrue(confirm.contains("CookieManager.getInstance().flush()"))
        assertTrue(confirm.contains("coroutineScope.launch(Dispatchers.Main)"))
        assertTrue(confirm.contains("cookieRevision++"))
    }

    @Test
    fun `the failure text exists in every locale`() {
        val res = java.io.File(ProductionSources.mainRoot()!!.path.substringBefore("/java/") + "/res")
        val dirs = res.listFiles { f -> f.isDirectory && f.name.startsWith("values") && java.io.File(f, "strings.xml").exists() }!!
        val withKey = dirs.filter { java.io.File(it, "strings.xml").readText().contains("name=\"browser_settings_clear_cookies_for\"") }
        assertTrue(withKey.size >= 18)
        withKey.forEach { d ->
            val xml = java.io.File(d, "strings.xml").readText()
            assertTrue("${d.name} misses the failure text",
                Regex("""name="browser_settings_clear_cookies_for_failed">[^<]*%1\${'$'}s""").containsMatchIn(xml))
        }
    }
}
