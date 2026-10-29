package com.openminis.app.ui.chat

import com.openminis.app.ProductionSources
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-sched-card-metrics] The scheduled-task card and the sub-agent card share
 * one skeleton (TaskCardFrame), so their height and inner metrics match and
 * cannot drift apart again.
 */
class TaskCardMetricsTest {

    private val scheduled = ProductionSources.read("ui/chat/ScheduledTaskCard.kt")
    private val helper = ProductionSources.read("ui/chat/HelperUi.kt")
    private val frame = ProductionSources.read("ui/chat/TaskCardFrame.kt")

    @Test
    fun `both cards render through the shared frame, chip and title`() {
        for ((name, src) in listOf("scheduled" to scheduled, "helper" to helper)) {
            assertTrue("$name uses TaskCardFrame", src.contains("TaskCardFrame("))
            assertTrue("$name uses TaskCardTitle", src.contains("TaskCardTitle("))
            assertTrue("$name chip goes through TaskCardChip", src.contains("TaskCardChip("))
            assertTrue("$name title row has the shared min height", src.contains("TaskCardTitleRow {"))
            assertTrue("$name status row has the shared min height", src.contains("TaskCardStatusRow {"))
        }
    }

    @Test
    fun `the scheduled card has no control that can set its height`() {
        val card = scheduled.substringAfter("TaskCardFrame(").substringBefore("if (showingDetail)")
        assertFalse("a TextButton (40dp min height) made the card tall", card.contains("TextButton("))
        assertTrue("cancel is a 24dp disc in the action slot", card.contains("TaskCardActionDisc("))
        assertTrue("with an accessible label", card.contains("label = cancelLabel"))
        // The status line is always present and fixed-height, like the helper's.
        assertTrue(card.contains("                subline,"))
        assertTrue(card.contains("lineHeight = TaskCardMetrics.subtitleLineHeight"))
        assertTrue(card.contains("maxLines = 1"))
    }

    @Test
    fun `the frame fixes every text line height and the sub-agent metrics`() {
        assertTrue(frame.contains("val paddingVertical = 12.dp"))
        assertTrue(frame.contains("val iconDisc = 34.dp"))
        assertTrue(frame.contains("val iconGlyph = 13.dp"))
        assertTrue(frame.contains("val rowSpacing = 5.dp"))
        assertTrue(frame.contains("val titleLineHeight = 14.sp"))
        assertTrue(frame.contains("val chipLineHeight = 11.sp"))
        assertTrue(frame.contains("val actionDisc = 24.dp"))
    }

    @Test
    fun `rows use a measured CJK minimum height, never a fixed height that clips`() {
        // A fixed row height clipped the top of CJK glyphs on a Pixel 4a; a
        // CJK line is taller than a Latin one at the same lineHeight.
        assertTrue(frame.contains("measurer.measure(\"\\u56FDAg\", style, maxLines = 1)"))
        assertTrue(frame.contains("Modifier.heightIn(min = maxOf(title, chip))"))
        assertTrue(frame.contains("Modifier.heightIn(min = h)"))
        assertFalse(frame.contains("Modifier.height("))
    }
}
