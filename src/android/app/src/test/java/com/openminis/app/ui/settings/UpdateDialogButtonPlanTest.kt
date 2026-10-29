package com.openminis.app.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-dialog-buttons] The update dialog's three actions: one row when they fit
 * (Skip, Later at the start; Download & Install at the end), otherwise
 * end-aligned lines with the primary always last.
 */
class UpdateDialogButtonPlanTest {

    private fun plan(skip: Int, later: Int, primary: Int, width: Int = 274) =
        UpdateDialogButtonPlan.of(listOf(skip, later, primary), width, gap = 8)

    @Test
    fun `all three fit - one row`() {
        // zh-ish widths on a Pixel 4a dialog (~274dp)
        val p = plan(80, 50, 110)
        assertTrue(p.singleRow)
        assertEquals(listOf(listOf(0, 1, 2)), p.lines)
        // Exactly full.
        assertTrue(plan(100, 50, 108).singleRow)
    }

    @Test
    fun `too wide - secondaries above, primary alone and last`() {
        val p = plan(120, 70, 150)
        assertFalse(p.singleRow)
        assertEquals(listOf(listOf(0, 1), listOf(2)), p.lines)
    }

    @Test
    fun `even the secondaries do not fit side by side - one per line, primary last`() {
        val p = plan(200, 120, 150)
        assertEquals(listOf(listOf(0), listOf(1), listOf(2)), p.lines)
        assertEquals(2, p.lines.last().single())
    }
}
