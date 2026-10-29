package com.openminis.app.ui.chat

import com.openminis.app.ProductionSources
import com.openminis.app.data.repository.MCPRepository.MCPServerConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-session-mcp-detail] The chat's MCPs sheet gets the search and
 * Enable All / Disable All of iOS SessionMCPsView and of the Skills sheet, and
 * a row opens a read-only detail page that never shows header or env values.
 */
class SessionMcpsSheetDetailTest {

    private val sheet = ProductionSources.read("ui/chat/SessionMcpsSheet.kt")

    private val notion = MCPServerConfig(id = "notion", note = "Team wiki", url = "https://mcp.notion.com/mcp")
    private val github = MCPServerConfig(id = "github", note = null, command = "npx", args = listOf("-y", "gh"))
    private val search = MCPServerConfig(id = "Brave", note = "Web SEARCH")

    // -- search --

    @Test
    fun `search matches name or note, trimmed and case-insensitive, like iOS`() {
        val all = listOf(notion, github, search)
        assertEquals(all, filterMcpServers(all, ""))
        assertEquals(all, filterMcpServers(all, "   "))
        assertEquals(listOf(notion), filterMcpServers(all, " NOTION "))
        assertEquals("note matches", listOf(notion), filterMcpServers(all, "wiki"))
        assertEquals(listOf(search), filterMcpServers(all, "search"))
        assertEquals("a null note never matches", emptyList<MCPServerConfig>(), filterMcpServers(listOf(github), "wiki"))
        assertEquals("the URL is not searched (iOS neither)", emptyList<MCPServerConfig>(), filterMcpServers(all, "mcp.notion.com"))
    }

    @Test
    fun `bulk toggles act on the filtered rows and write through`() {
        val setAll = sheet.substringAfter("fun setAll(enabled: Boolean) {").substringBefore("StandardChatSheet(")
        assertTrue(setAll.contains("for (server in filteredServers) {"))
        assertTrue(setAll.contains("mcpRepository.setSessionOverride(sessionId, server.id, enabled)"))
        assertTrue(sheet.contains("onClick = { setAll(true) },"))
        assertTrue(sheet.contains("onClick = { setAll(false) },"))
        assertTrue(sheet.contains("stringResource(R.string.mcps_search_no_match, searchQuery)"))
    }

    // -- detail --

    @Test
    fun `secret names are listed sorted, and only the names`() {
        val names = mcpSecretNames(mapOf("X-Api-Key" to "sk-live-123", "Authorization" to "Bearer abc"))
        assertEquals(listOf("Authorization", "X-Api-Key"), names)
        assertFalse(names.any { it.contains("sk-live") || it.contains("Bearer") })
    }

    @Test
    fun `the detail page never reads header or env values`() {
        val detail = sheet.substringAfter("private fun SessionMcpDetail(").substringBefore("private fun McpDetailRow(")
        assertTrue(detail.contains("mcpSecretNames(server.headers)"))
        assertTrue(detail.contains("mcpSecretNames(server.env)"))
        for (leak in listOf("headers.values", "env.values", "headers.entries", "env.entries", "headers[", "env[", ".value }")) {
            assertFalse("detail must not touch $leak", detail.contains(leak))
        }
        assertFalse("the full JSON export would carry the values", detail.contains("exportServerJSON"))
        assertTrue(detail.contains("R.string.session_mcp_detail_values_hidden"))
    }

    @Test
    fun `the detail page is read-only`() {
        val detail = sheet.substringAfter("private fun SessionMcpDetail(").substringBefore("private fun McpDetailRow(")
        for (write in listOf("mcpRepository", ".update(", ".delete(", "setEnabled", "signOut", "setClientSecret")) {
            assertFalse("detail must not call $write", detail.contains(write))
        }
    }

    @Test
    fun `a row tap opens the detail, and the URL row copies on long-press`() {
        assertTrue(sheet.contains("onClick = { detailId = server.id },"))
        assertTrue(sheet.contains("SessionMcpDetail(detailServer)"))
        assertTrue(sheet.contains("onLongClick = url.takeIf { it.isNotBlank() }?.let {"))
        assertTrue(sheet.contains("copyMcpUrl(context, it)"))
        assertTrue(sheet.contains("R.string.session_mcp_url_copied"))
    }

    @Test
    fun `list state lives above the page switch, and back pops the detail first`() {
        val beforeSheet = sheet.substringBefore("StandardChatSheet(\n        title =")
        for (state in listOf("val overrides = remember(servers)", "var searchQuery by remember", "val listScroll = rememberScrollState()")) {
            assertTrue("$state must be held above the page switch", beforeSheet.contains(state))
        }
        assertTrue(sheet.contains(".verticalScroll(listScroll)"))
        assertTrue(sheet.contains("if (detailId == null) false else {\n                detailId = null\n                true"))
        assertTrue(sheet.contains("IconButton(onClick = { detailId = null })"))
        assertTrue("a removed server falls back to the list", sheet.contains("if (detailGone) detailId = null"))
    }

    @Test
    fun `empty card, footer and the stale-override fix are kept`() {
        for (kept in listOf("EmptyMcpsCard()", "R.string.session_mcps_footer", "val overrides = remember(servers) {")) {
            assertTrue("$kept is gone", sheet.contains(kept))
        }
    }

    // -- strings --

    private val res = java.io.File(ProductionSources.mainRoot()!!.parentFile.parentFile.parentFile.parentFile, "res")
    private val keys = listOf(
        "mcps_search_placeholder", "mcps_search_no_match",
        "session_mcp_detail_transport", "session_mcp_detail_args", "session_mcp_detail_note",
        "session_mcp_detail_headers", "session_mcp_detail_env", "session_mcp_detail_values_hidden",
        "session_mcp_oauth_not_authorized", "session_mcp_url_copied",
    )

    @Test
    fun `new strings exist in every shipped locale, escaped, with the placeholder kept`() {
        val dirs = res.listFiles()!!.filter { java.io.File(it, "strings.xml").exists() }.map { it.name }
        assertTrue("found the locales: $dirs", dirs.size >= 18)
        for (dir in dirs) {
            val xml = java.io.File(res, "$dir/strings.xml").readText()
            for (key in keys) {
                val value = Regex("""<string name="$key">(.*?)</string>""").find(xml)?.groupValues?.get(1)
                assertTrue("$dir is missing $key", !value.isNullOrBlank())
                assertFalse("$dir/$key has an unescaped apostrophe", Regex("""(?<!\\)'""").containsMatchIn(value!!))
                assertFalse("$dir/$key has an unescaped double quote", Regex("""(?<!\\)"""").containsMatchIn(value))
            }
            val noMatch = Regex("""<string name="mcps_search_no_match">(.*?)</string>""").find(xml)!!.groupValues[1]
            assertTrue("$dir no-match keeps %1\$s", noMatch.contains("%1\$s"))
        }
    }
}
