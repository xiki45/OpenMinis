package com.openminis.app.ui.chat.share

import com.openminis.app.ProductionSources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T7c-share-statusbar] The share picker and preview are full-screen Dialogs with
 * their own windows; the system bars take their icon colour from the top window,
 * which defaults to white icons. Each must set its window's bar icons from the
 * APP theme (not the export's Appearance option).
 */
class ChatShareStatusBarTest {
    private val picker = ProductionSources.read("ui/chat/share/ChatShareSelectionScreen.kt")
    private val preview = ProductionSources.read("ui/chat/share/ChatSharePreviewScreen.kt")

    @Test fun `both share dialogs set their own window's bar icons`() {
        for ((name, src) in listOf("picker" to picker, "preview" to preview)) {
            val dialog = src.indexOf("    Dialog(\n")
            assertTrue("$name is a Dialog", dialog >= 0)
            val bars = src.indexOf("ChatShareDialogSystemBars()", dialog)
            assertTrue("$name sets the bars inside its Dialog, before its Surface",
                bars > dialog && bars < src.indexOf("Surface(modifier = Modifier.fillMaxSize()", dialog))
        }
        assertEquals("no other full-screen Dialog in the share screens", 2,
            listOf(picker, preview).sumOf { it.split("    Dialog(\n").size - 1 })
    }

    @Test fun `icons follow the app theme, status and navigation bar alike`() {
        val helper = picker.substringAfter("internal fun ChatShareDialogSystemBars()")
        assertTrue(helper.contains("val appDark = LocalChatPalette.current.isDark"))
        assertTrue(helper.contains("isAppearanceLightStatusBars = !appDark"))
        assertTrue(helper.contains("isAppearanceLightNavigationBars = !appDark"))
        assertTrue(helper.contains("if (p is androidx.compose.ui.window.DialogWindowProvider) return p.window"))
        assertTrue("not the export's appearance choice", !helper.contains("darkChoice"))
    }
}
