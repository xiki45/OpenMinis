package com.openminis.app.ui.components

import com.openminis.app.ProductionSources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-model-row-premature-wrap] Port of iOS b86aabda4 + 74025d909: a model
 * row's name (or id) and its tags share one line only when the whole line
 * fits; otherwise the name keeps the full width and the tags drop below.
 */
class FitOrWrapLineTest {

    @Test
    fun `whole line fits - one line`() {
        // Name 180 + gap 4 + tags (pill + Active badge) 60 = 244.
        assertTrue(FitOrWrapRules.fitsOnOneLine(300, leadWidth = 180, separatorWidth = null, tagsWidth = 60, gap = 4))
        // Exactly full is still one line.
        assertTrue(FitOrWrapRules.fitsOnOneLine(244, leadWidth = 180, separatorWidth = null, tagsWidth = 60, gap = 4))
    }

    @Test
    fun `one pixel too wide - tags drop below instead of wrapping the name`() {
        assertFalse(FitOrWrapRules.fitsOnOneLine(243, leadWidth = 180, separatorWidth = null, tagsWidth = 60, gap = 4))
    }

    @Test
    fun `separator counts with its own gap`() {
        // id 150 + 4 + dot 4 + 4 + chips 60 = 222
        assertTrue(FitOrWrapRules.fitsOnOneLine(222, leadWidth = 150, separatorWidth = 4, tagsWidth = 60, gap = 4))
        assertFalse(FitOrWrapRules.fitsOnOneLine(221, leadWidth = 150, separatorWidth = 4, tagsWidth = 60, gap = 4))
        // Without the dot the same line fits in 214.
        assertTrue(FitOrWrapRules.fitsOnOneLine(214, leadWidth = 150, separatorWidth = null, tagsWidth = 60, gap = 4))
    }

    @Test
    fun `no tags or unbounded width - always one line`() {
        assertTrue(FitOrWrapRules.fitsOnOneLine(100, leadWidth = 900, separatorWidth = 4, tagsWidth = 0, gap = 4))
        assertTrue(FitOrWrapRules.fitsOnOneLine(null, leadWidth = 900, separatorWidth = 4, tagsWidth = 60, gap = 4))
    }

    @Test
    fun `every model row with a name or id plus tags uses FitOrWrapLine`() {
        val picker = ProductionSources.read("ui/chat/ChatModelPickerSheet.kt")
        val nameBadge = picker.substring(picker.indexOf("private fun ModelNameWithActiveBadge("))
            .substringBefore("private fun ProviderEntryRowText(")
        assertTrue("name + pill + Active badge", nameBadge.contains("FitOrWrapLine(") && !nameBadge.contains("fill = false"))
        val providerText = picker.substring(picker.indexOf("private fun ProviderEntryRowText("))
            .substringBefore("private fun QuickTestButton(")
        assertTrue("provider rows: hint + id lead, dot separator, chips as tags",
            providerText.contains("FitOrWrapLine(") && providerText.contains("ModelRowSeparatorDot()") &&
                providerText.indexOf("ModelQuotaHintView(") < providerText.indexOf("tags = {"))
        assertEquals("expanded rows and the collapsed representative share the row text (2 calls + definition)",
            3, picker.split("ProviderEntryRowText(\n").size - 1)
        assertFalse("no FlowRow of name/id + chips is left in the chat picker", picker.contains("FlowRow("))

        val unified = ProductionSources.read("ui/components/UnifiedModelPickerSheet.kt")
        // (Its LazyColumn keeps a legitimate weight(1f, fill = false); no row does.)
        assertFalse(unified.contains("FlowRow("))
        assertFalse(unified.contains("style = MaterialTheme.typography.bodyMedium,\n                                            modifier = Modifier.weight(1f, fill = false)"))
        assertEquals("member name line + provider id line", 2, unified.split("FitOrWrapLine(").size - 1)

        val entryPicker = ProductionSources.read("ui/components/ModelEntryPicker.kt")
        assertFalse(entryPicker.contains("FlowRow("))
        assertTrue(entryPicker.contains("FitOrWrapLine(") && entryPicker.contains("ModelRowSeparatorDot()"))

        val group = ProductionSources.read("ui/settings/ModelGroupDetailScreen.kt")
        assertTrue("group page name + quota pill", group.contains("FitOrWrapLine(\n") && !group.contains("Modifier.weight(1f, fill = false)"))
    }

    @Test
    fun `layout measures the lead unwrapped and places tags below when they do not fit`() {
        val src = ProductionSources.read("ui/components/FitOrWrapLine.kt")
        assertTrue(src.contains("lead.maxIntrinsicWidth(Constraints.Infinity)"))
        assertTrue("one-line form holds the lead to its single-line width", src.contains("lead.measure(loose.copy(maxWidth = leadW))"))
        assertTrue("wrapped form gives the lead the full width", src.contains("val lp = lead.measure(loose)"))
        assertTrue("separator only in the one-line form", src.contains("if (sepW > 0) sep?.measure(Constraints()) else null"))
    }
}
