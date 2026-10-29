package com.openminis.app.scheduled

import com.openminis.app.ProductionSources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-scheduled-detail-action-cards] The detail page's bottom actions are full-width
 * card buttons with an icon, as iOS ScheduledTaskDetailView.actions draws them
 * (Label + doc.on.doc / calendar.badge.minus on the card background), not bare
 * orange / red text links.
 */
class ScheduledTaskDetailActionsTest {
    private val sheet = ProductionSources.read("ui/chat/ScheduledTaskDetailSheet.kt")
    private val actions = sheet.substringAfter("// Actions:").substringBefore("if (confirmingCancel)")
    private val button = sheet.substringAfter("private fun ActionCardButton(").substringBefore("\n}\n")

    @Test fun `both actions are card buttons with an icon`() {
        assertEquals(2, actions.split("ActionCardButton(").size - 1)
        assertTrue(actions.contains("icon = Icons.Outlined.ContentCopy"))
        assertTrue(actions.contains("icon = Icons.Outlined.EventBusy"))
        assertTrue("no bare text links", !actions.contains("TextButton("))
    }

    @Test fun `copy reads in the primary colour, cancel in red, 10dp apart`() {
        assertTrue(actions.contains("verticalArrangement = Arrangement.spacedBy(10.dp)"))
        val copy = actions.substringAfter("icon = Icons.Outlined.ContentCopy")
        assertTrue(copy.contains("color = ChatColors.primaryText"))
        val cancel = actions.substringAfter("icon = Icons.Outlined.EventBusy").substringBefore("icon = Icons.Outlined.ContentCopy")
        assertTrue(cancel.contains("color = StatusRed"))
    }

    @Test fun `the button is a full-width card like the sections above it`() {
        assertTrue(button.contains(".fillMaxWidth()"))
        assertTrue(button.contains(".clip(RoundedCornerShape(14.dp))"))
        assertTrue(button.contains(".background(ChatColors.secondaryBg)"))
        assertTrue(button.contains(".padding(vertical = 12.dp)"))
        assertTrue(button.indexOf(".clickable(") > button.indexOf(".clip("))
    }
}
