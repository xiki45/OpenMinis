package com.openminis.app.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.ui.graphics.vector.VectorGroup
import androidx.compose.ui.graphics.vector.VectorPath
import com.openminis.app.ProductionSources
import com.openminis.app.data.SpeedTier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-speed-tier-icons] Standard = speedometer, Fast = outline bolt, Ultrafast
 * = solid bolt; monochrome single glyphs, no emoji, no double bolt.
 */
class SpeedTierIconsTest {

    private fun paths(group: VectorGroup): List<VectorPath> =
        group.flatMap { node ->
            when (node) {
                is VectorPath -> listOf(node)
                is VectorGroup -> paths(node)
            }
        }

    @Test
    fun `each tier has its own glyph`() {
        assertEquals(Icons.Outlined.Speed, SpeedTierIcons.forTier(SpeedTier.STANDARD))
        assertEquals(SpeedTierIcons.BoltOutline, SpeedTierIcons.forTier(SpeedTier.FAST))
        assertEquals(SpeedTierIcons.BoltFill, SpeedTierIcons.forTier(SpeedTier.ULTRAFAST))
        assertEquals(3, SpeedTier.entries.map { SpeedTierIcons.forTier(it) }.toSet().size)
    }

    @Test
    fun `fast is a stroke-only outline, ultrafast is filled, same silhouette`() {
        val outline = paths(SpeedTierIcons.BoltOutline.root)
        val fill = paths(SpeedTierIcons.BoltFill.root)
        assertEquals(1, outline.size)
        assertEquals(1, fill.size)
        assertNull("the Fast bolt must be hollow", outline[0].fill)
        assertNotNull(outline[0].stroke)
        assertNotNull("the Ultrafast bolt must be solid", fill[0].fill)
        assertEquals("one polygon for both", outline[0].pathData, fill[0].pathData)
        assertEquals(24f, SpeedTierIcons.BoltOutline.viewportWidth)
    }

    @Test
    fun `the chat ui uses the glyphs, no double bolt, badge keeps an accessible name`() {
        val screen = ProductionSources.read("ui/chat/ChatScreen.kt")
        assertFalse(screen.contains("DoubleBolt"))
        assertFalse("no emoji bolt in the chat screen", screen.contains("⚡"))
        val badge = screen.substring(screen.indexOf("private fun SpeedTierNavBadge("))
            .substringBefore("\n}\n")
        assertTrue(badge.contains("SpeedTierIcons.forTier(tier)"))
        assertTrue(badge.contains("contentDescription = speedTierLabel(tier)"))
        // [T-android-ultrafast-align] Sized from the text's cap height, not a fixed 12dp.
        assertTrue(badge.contains("paint.getTextBounds(\"H\", 0, 1, bounds)") && badge.contains("SpeedTierIcons.boltBoxForInk(ink)"))
        assertFalse(badge.contains("Modifier.size(12.dp)"))
        assertTrue(screen.contains("MenuItemIcon(com.openminis.app.ui.components.SpeedTierIcons.Speedometer)"))
        assertTrue(screen.contains("MenuItemIcon(com.openminis.app.ui.components.SpeedTierIcons.forTier(tier))"))
    }

    @Test
    fun `speed strings carry no emoji in any locale`() {
        val res = java.io.File(ProductionSources.mainRoot()!!.path.substringBefore("/java/") + "/res")
        val files = res.listFiles { f -> f.name.startsWith("values") }!!
            .mapNotNull { java.io.File(it, "strings.xml").takeIf { f -> f.exists() } }
        assertTrue(files.size >= 18)
        for (f in files) {
            for (line in f.readLines().filter { it.contains("name=\"chat_speed_") || it.contains("name=\"chat_menu_fast_mode_tier\"") || it.contains("name=\"chat_menu_fast_mode\"") }) {
                assertFalse("${f.parentFile.name}: $line", line.contains("⚡"))
            }
        }
    }

    @Test
    fun `fast mode row, tiers and switch read as on ios`() {
        val res = java.io.File(ProductionSources.mainRoot()!!.path.substringBefore("/java/") + "/res")
        fun strings(dir: String): Map<String, String> =
            Regex("<string name=\"([^\"]+)\">([^<]*)</string>")
                .findAll(java.io.File(res, "$dir/strings.xml").readText())
                .associate { it.groupValues[1] to it.groupValues[2] }
        val dirs = res.listFiles { f -> f.name.startsWith("values") && java.io.File(f, "strings.xml").exists() }!!
        for (d in dirs) {
            val s = strings(d.name)
            assertTrue("${d.name}: Fast Mode row title", !s["chat_menu_fast_mode_tier"].isNullOrBlank())
            assertEquals("${d.name}: Ultrafast stays untranslated", "Ultrafast", s["chat_speed_ultrafast"])
            assertFalse("${d.name}: the Speed key is gone", s.containsKey("chat_menu_speed"))
        }
        val zh = strings("values-zh")
        assertEquals(listOf("快速模式", "标准", "快速", "启用快速模式"),
            listOf(zh["chat_menu_fast_mode_tier"], zh["chat_speed_standard"], zh["chat_speed_fast"], zh["chat_menu_fast_mode"]))
        val tw = strings("values-zh-rTW")
        assertEquals(listOf("快速模式", "標準", "快速", "啟用快速模式"),
            listOf(tw["chat_menu_fast_mode_tier"], tw["chat_speed_standard"], tw["chat_speed_fast"], tw["chat_menu_fast_mode"]))
        assertEquals("Fast Mode", strings("values")["chat_menu_fast_mode_tier"])
        assertEquals("高速モード", strings("values-ja")["chat_menu_fast_mode_tier"])
    }

    @Test
    fun `a bolt box sized for its ink`() {
        // The path spans y 2.5..21.5 with a 1.8 outline: 20.8 of 24 units are ink.
        assertEquals(20.8f / 24f, SpeedTierIcons.BOLT_INK_FRACTION, 1e-6f)
        assertEquals(24f, SpeedTierIcons.boltBoxForInk(20.8f), 1e-4f)
    }
}
