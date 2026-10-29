package com.openminis.app.ui.components

import com.openminis.app.ProductionSources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-badge-clip] Material3's IconButton clips its content to its 40dp
 * circle, so a BadgedBox inside one loses the badge's outer corner (the home
 * toolbar's scheduled-tasks count, user report). Badges go through
 * BadgedIconButton, which wraps the button instead.
 */
class BadgedIconButtonTest {

    @Test
    fun `no BadgedBox is nested inside an IconButton anywhere in the app`() {
        val offenders = mutableListOf<String>()
        for (f in ProductionSources.allKotlinFiles()) {
            if (f.name == "BadgedIconButton.kt") continue
            val lines = f.readLines()
            lines.forEachIndexed { i, line ->
                if (!line.contains("BadgedBox(")) return@forEachIndexed
                val before = lines.subList(maxOf(0, i - 8), i)
                if (before.any { Regex("""\bIconButton\(""").containsMatchIn(it) }) offenders += "${f.name}:${i + 1}"
            }
        }
        assertEquals("BadgedBox inside an IconButton is clipped by its circle: $offenders", emptyList<String>(), offenders)
    }

    @Test
    fun `home toolbar badges use BadgedIconButton and keep their position`() {
        val home = ProductionSources.read("ui/sessions/SessionListScreen.kt")
        assertEquals(2, Regex("""BadgedIconButton\(""").findAll(home).count())
        val helper = ProductionSources.read("ui/components/BadgedIconButton.kt")
        assertTrue("badge outside the button", helper.indexOf("BadgedBox(") < helper.lastIndexOf("IconButton(onClick = onClick, enabled = enabled)"))
        assertTrue("offset back to the icon's corner", helper.contains("Modifier.offset(x = -ANCHOR_INSET, y = ANCHOR_INSET)"))
        assertTrue(helper.contains("internal val ANCHOR_INSET: Dp = 12.dp"))
    }
}
