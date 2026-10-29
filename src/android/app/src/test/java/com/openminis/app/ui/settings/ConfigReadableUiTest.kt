package com.openminis.app.ui.settings

import com.openminis.app.ProductionSources
import com.openminis.app.config.ConfigRisk
import com.openminis.app.config.confirm.PendingConfigChangeItem
import com.openminis.app.config.readable.ReadableArg
import com.openminis.app.config.readable.ReadableLine
import com.openminis.app.config.readable.ReadableSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-config-readable] The approval dialog's batch summary and the detail
 * page's raw-JSON rendering (iOS ConfigConfirmSheet.summaryLines /
 * ConfigAuditDetailView.prettyJSON), plus wiring guards for the hairline and
 * the Config Changes tap-through.
 */
class ConfigReadableUiTest {

    private fun item(text: String, subject: String?, approved: Boolean = true) = PendingConfigChangeItem(
        displayName = "x", path = "x", oldDisplay = "", newDisplay = "", verb = "set", risk = ConfigRisk.NORMAL,
        readable = ReadableSummary(
            locale = "en", text = text,
            lines = listOf(ReadableLine("%@", listOf(ReadableArg.Text(text)))), subject = subject,
        ),
        isApproved = approved,
    )

    @Test
    fun `summary keeps rows about one record together and drops switched-off rows`() {
        val items = listOf(
            item("group A entries", "groups:A"),
            item("theme", null),
            item("group B name", "groups:B", approved = false),
            item("group A thinking", "groups:A"),
            item("language", null),
        )
        assertEquals(
            listOf("group A entries", "group A thinking", "theme", "language"),
            configSummaryLines(items, localize = { null }),
        )
        assertEquals(emptyList<String>(), configSummaryLines(items.map { it.copy(isApproved = false) }) { null })
    }

    @Test
    fun `raw JSON is pretty-printed with sorted keys, strings unquoted, empty as a dash`() {
        assertEquals("—", prettyConfigJSON(""))
        assertEquals("dark", prettyConfigJSON("\"dark\""))
        assertEquals("64000", prettyConfigJSON("64000"))
        assertEquals("[\n  \"a/b\",\n  \"c\"\n]", prettyConfigJSON("[\"a\\/b\",\"c\"]"))
        assertEquals("{\n  \"a\" : 1,\n  \"b\" : {\n    \"c\" : true\n  }\n}", prettyConfigJSON("{\"b\":{\"c\":true},\"a\":1}"))
        assertEquals("{not json", prettyConfigJSON("{not json"))
    }

    @Test
    fun `the hairline is drawn only together with a sentence, and records open their details`() {
        val dialog = ProductionSources.read("ui/settings/ConfigConfirmDialog.kt")
        assertTrue(Regex("""if \(readable\.isNotEmpty\(\)\) \{\s*ConfigReadableDivider\(\)""").containsMatchIn(dialog))
        assertTrue(dialog.contains("if (workingItems.size > 1) configSummaryLines(workingItems, localize)"))
        val list = ProductionSources.read("ui/settings/ConfigAuditScreen.kt")
        assertTrue(Regex("""if \(readable\.isNotEmpty\(\)\) \{\s*ConfigReadableDivider\(\)""").containsMatchIn(list))
        assertTrue(list.contains(".clickable(onClick = onOpen)"))
        assertTrue(list.contains("configRevertMessage(entry, localize)"))
        // The detail page separates description and raw change by sections, not a hairline.
        val detail = ProductionSources.read("ui/settings/ConfigAuditDetailScreen.kt")
        assertTrue(!detail.contains("ConfigReadableDivider()"))
    }
}
