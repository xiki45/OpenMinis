package com.openminis.app.ui.sessions

import com.openminis.app.ProductionSources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-newchat-fab-shadow] The home New Chat FAB had no shadow: its `.shadow` came after
 * the `.clip(CircleShape)` that keeps the long-press ripple round, so the shadow (and
 * the FAB's own elevation shadow) was clipped away. The shadow must precede the clip,
 * the clip must still precede the long-press clickable, and both FABs must carry the
 * same shadow and elevation.
 */
class SessionListFabShadowTest {
    private val src = ProductionSources.read("ui/sessions/SessionListScreen.kt")
    private val chatFab = src.substringAfter("val chatFab: @Composable () -> Unit = {").substringBefore("val searchFab:")
    private val searchFab = src.substringAfter("val searchFab: @Composable () -> Unit = {").substringBefore("\n    Row(")

    private val shadow = ".shadow(6.dp, CircleShape, ambientColor = Color.Black.copy(alpha = 0.15f))"
    private val elevation = "FloatingActionButtonDefaults.elevation(defaultElevation = 4.dp)"

    @Test fun `new chat shadow is drawn before the clip, clip still before the long-press clickable`() {
        val s = chatFab.indexOf(shadow)
        val clip = chatFab.indexOf(".clip(CircleShape)\n") // the modifier line, not a comment mentioning it
        val click = chatFab.indexOf(".combinedClickable(")
        assertTrue("shadow present", s >= 0)
        assertTrue("shadow before clip", s < clip)
        assertTrue("clip before combinedClickable (round ripple)", clip in 0 until click)
        assertEquals("exactly one shadow on the new chat FAB", 1, chatFab.split(".shadow(").size - 1)
    }

    @Test fun `both FABs carry the same shadow and elevation`() {
        for ((name, fab) in listOf("chat" to chatFab, "search" to searchFab)) {
            assertTrue("$name shadow", fab.contains(shadow))
            assertTrue("$name elevation", fab.contains(elevation))
        }
    }
}
