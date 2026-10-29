package com.openminis.app.ui.chat

import com.openminis.app.ProductionSources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [T-tool-preview-longpress-close] / [T-thinking-autoexpand-toggle-longpress]
 * Long-press shortcuts for two Appearance settings (port of iOS 277d5ce1b and
 * b556ea8f8).
 */
class ToolPreviewAndThinkingLongPressTest {

    private val bar = ProductionSources.read("ui/chat/ChatComposerWidgets.kt")
    private val thinking = ProductionSources.read("ui/chat/ChatAssistantMessageUI.kt")

    @Test
    fun `the status bar title toggles the preview and the thumbnail closes it, writing the Appearance key`() {
        val fn = bar.substringAfter("internal fun FloatingToolStatusBar(").substringBefore("internal fun ThinkingLevelPicker(")
        assertTrue(fn.contains(".putBoolean(com.openminis.app.ui.settings.KEY_TOOL_PREVIEW, on)"))
        assertTrue(fn.contains("onLongClick = { showBarMenu = true }"))
        assertTrue(fn.contains("setToolPreview(!previewEnabled)"))
        assertTrue(fn.contains("onLongClick = { showThumbMenu = true }"))
        assertTrue(fn.contains("R.string.tool_preview_closed_toast"))
        assertTrue("pager chevrons keep their own long-press", fn.contains("onLongClick = { currentIndex = 0 }"))
    }

    @Test
    fun `the thumbnail menu is at the mount site, not inside the recursive tile`() {
        val thumb = bar.substringAfter("private fun ToolPreviewThumbnail(").substringBefore("internal fun FloatingToolStatusBar(")
        assertTrue(!thumb.contains("DropdownMenu("))
        assertTrue("the long-press reaches an inner sub agent tile", thumb.contains("onLongClick = onLongClick,"))
    }

    @Test
    fun `a thinking block's long-press toggles the setting and applies it to the live block only`() {
        val fn = thinking.substringAfter("internal fun ThinkingBlock(").substringBefore("AnimatedVisibility(visible = expanded")
        assertTrue(fn.contains(".expandToggleClickable(onLongClick = { showThinkingMenu = true })"))
        assertTrue(fn.contains("putBoolean(KEY_AUTO_EXPAND_THINKING, !on)"))
        assertTrue(fn.contains("if (isStreaming && isLast && userIntent == null && !overHardCap) expanded = !on"))
    }

    @Test
    fun `both strings exist in every locale the setting has`() {
        val res = File(System.getProperty("user.dir"), "src/main/res").takeIf { it.isDirectory }
            ?: File(System.getProperty("user.dir"), "app/src/main/res")
        val dirs = res.listFiles()!!.filter { it.name.startsWith("values") && File(it, "strings.xml").exists() }
        val withSetting = dirs.filter { File(it, "strings.xml").readText().contains("name=\"appearance_tool_preview_title\"") }
        val withNew = withSetting.filter {
            val t = File(it, "strings.xml").readText()
            t.contains("name=\"tool_preview_close_menu\"") && t.contains("name=\"tool_preview_closed_toast\"")
        }
        assertEquals(withSetting.map { it.name }.sorted(), withNew.map { it.name }.sorted())
        assertTrue(withSetting.size >= 18)
    }
}
