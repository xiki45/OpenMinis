package com.openminis.app.ui.chat

import com.openminis.app.ProductionSources
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-fold-row-chevron-align] The folded row's chevron starts on the same left
 * edge as the reply text and the tool pills: no start inset on the row, and a
 * chevron drawn edge to edge instead of Material's padded KeyboardArrowRight.
 * The tap target keeps its width and the chevron turns about its own centre.
 */
class ToolGroupRowAlignTest {

    private val src = ProductionSources.read("ui/chat/ToolGroupRow.kt")

    @Test
    fun `no start inset, the same total width moved to the end`() {
        assertFalse(src.contains(".padding(horizontal = 4.dp)"))
        assertTrue(src.contains(".padding(start = 0.dp, end = 8.dp)"))
        // The tap target is the padded row itself, as before.
        // [ISS-017] Now the no-ripple variant; a missing call must fail, not
        // compare -1 below the padding.
        val tap = src.indexOf(".expandToggleClickable(onClick = onToggle)")
        assertTrue(tap >= 0 && tap < src.indexOf(".padding(start = 0.dp, end = 8.dp)"))
    }

    @Test
    fun `the chevron is drawn edge to edge, not the padded Material icon`() {
        assertFalse(src.contains("Icons.AutoMirrored.Filled.KeyboardArrowRight"))
        assertTrue(src.contains("FoldChevron(angle = chevronAngle.value, color = ChatColors.secondaryText)"))
        val chevron = src.substringAfter("private fun FoldChevron(")
        assertTrue("caps end on the box edges", chevron.contains("val inset = stroke / 2f") &&
            chevron.contains("moveTo(inset, inset)") && chevron.contains("lineTo(inset, size.height - inset)"))
        assertTrue(chevron.contains("cap = androidx.compose.ui.graphics.StrokeCap.Round"))
    }

    @Test
    fun `it turns about its own centre`() {
        val chevron = src.substringAfter("private fun FoldChevron(")
        assertTrue(chevron.contains(".graphicsLayer { rotationZ = angle }"))
        assertFalse("a pivot or offset would move the turn off-centre",
            chevron.substringBefore("val stroke").contains("transformOrigin") || chevron.substringBefore("val stroke").contains(".offset("))
        assertTrue(src.contains("animateFloatAsState(if (item.expanded) 90f else 0f"))
    }
}
