package com.openminis.app.ui.settings

import com.openminis.app.ProductionSources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-soul-lang-button-wrap] The Persona language row's "English" broke
 * mid-word ("Englis" / "h") on a narrow phone: three equal-width buttons with
 * the default 24dp side padding and a label allowed to wrap.
 */
class SoulLangButtonWrapTest {

    @Test
    fun `language buttons keep their labels on one line with a tight side padding`() {
        val src = ProductionSources.read("ui/settings/SoulSettingsScreen.kt")
        val picker = src.substringAfter("private fun LangPicker(").substringBefore("\n}\n")
        assertEquals("both buttons use the tight padding", 2, Regex("contentPadding = padding,").findAll(picker).count())
        assertEquals("both buttons use the one-line label", 2, Regex("\\{ LangButtonLabel\\(label\\) \\}").findAll(picker).count())
        val label = src.substringAfter("private fun LangButtonLabel(label: String) {").substringBefore("\n}\n")
        assertTrue(label.contains("maxLines = 1"))
        assertTrue(label.contains("softWrap = false"))
        assertEquals(8f, LANG_BUTTON_SIDE_PADDING.value)
    }
}
