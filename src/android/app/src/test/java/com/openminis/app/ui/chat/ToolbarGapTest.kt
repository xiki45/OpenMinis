package com.openminis.app.ui.chat

import androidx.compose.ui.unit.dp
import com.openminis.app.ProductionSources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-toolbar-gap] The last row sits the same distance above the
 * floating tool bar whether or not Tool preview draws its thumbnail.
 */
class ToolbarGapTest {

    private fun reserve(preview: Boolean) =
        FloatingToolBarMetrics.visibleTop(preview) + FloatingToolBarMetrics.ROW_GAP

    @Test fun `the gap above the overlay's visible top is the same in both states`() {
        assertEquals("preview ON keeps the reserve it always had (65 + 14)", 79.dp, reserve(true))
        assertEquals("preview OFF reserves for the 38dp bar, not the 65dp thumbnail", 52.dp, reserve(false))
        assertEquals(reserve(true) - FloatingToolBarMetrics.visibleTop(true), reserve(false) - FloatingToolBarMetrics.visibleTop(false))
        assertEquals("the difference is exactly the thumbnail overhang", 27.dp, reserve(true) - reserve(false))
    }

    @Test fun `the list and the bar read one geometry and one preview flag`() {
        val screen = ProductionSources.read("ui/chat/ChatScreen.kt")
        assertTrue(screen.contains("FloatingToolBarMetrics.visibleTop(toolPreviewEnabled) + FloatingToolBarMetrics.ROW_GAP"))
        assertFalse("no reserve pinned to the thumbnail any more", screen.contains("val visualOverlayHeight = 65.dp"))
        assertTrue(screen.contains(".padding(bottom = FloatingToolBarMetrics.WRAPPER_BOTTOM_PADDING),"))
        assertTrue("a toggle re-pins the list", screen.contains("LaunchedEffect(bottomReserve) {"))
        val bar = ProductionSources.read("ui/chat/ChatComposerWidgets.kt")
        assertTrue(bar.contains("val thumbnailHeight = FloatingToolBarMetrics.THUMBNAIL_HEIGHT"))
        assertTrue(bar.contains("val barHeight = FloatingToolBarMetrics.BAR_HEIGHT"))
    }
}
