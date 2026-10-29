package com.openminis.app.ui.settings

import com.openminis.app.ProductionSources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ISS-040b] Settings → AI providers: in a provider-type card the separators
 * sat one row too high — a line over the card's top edge and none before the
 * last row ("only the first and second provider have a divider").
 *
 * Cause: sh.calvin.reorderable 2.4.0's ReorderableColumn wraps every item in
 * Box(contentAlignment = TopStart), so a divider emitted next to the row's
 * Surface was stacked over the row's first pixel line instead of laid out
 * below it. Measured on a Pixel 4a before/after (see the commit).
 *
 * The project has no Compose UI test runner, so the rule is a pure function
 * and the layout shape is pinned by source guards.
 */
class ProviderListDividerTest {

    private fun dividersFor(n: Int) = (0 until n).count { providerRowHasDividerAbove(it) }

    @Test
    fun `N rows get exactly N-1 separators`() {
        assertEquals(0, dividersFor(0))
        for (n in 1..5) assertEquals("rows=$n", n - 1, dividersFor(n))
    }

    @Test
    fun `the separator sits above every row but the first, never at the card's top`() {
        assertFalse(providerRowHasDividerAbove(0))
        assertTrue(providerRowHasDividerAbove(1))
        assertTrue(providerRowHasDividerAbove(13))
    }

    private val src = ProductionSources.read("ui/settings/ProviderListScreen.kt")
    private val row: String = src
        .substringAfter("ReorderableColumn(")
        .substringAfter("key(instance.id) {")
        .substringBefore("// [T-android-provider-voice]")

    @Test
    fun `each reorderable item lays its separator and row out in its own Column`() {
        val column = row.indexOf("Column {")
        val divider = row.indexOf("if (providerRowHasDividerAbove(index)) {")
        val surface = row.indexOf("Surface(")
        assertTrue("Column wraps the item (the library's Box would stack them)", column >= 0)
        assertTrue("separator first, then the row", divider > column && surface > divider)
        assertFalse("the old below-the-row rule must not come back",
            src.contains("if (index < localOrder.size - 1)"))
    }

    @Test
    fun `drag, swipe and elevation stay on the row itself`() {
        val surface = row.substringAfter("Surface(").substringBefore(") {")
        assertTrue(surface.contains("shadowElevation = elevation"))
        assertTrue(surface.contains("modifier = Modifier.longPressDraggableHandle()"))
        assertTrue(row.contains("SwipeRowActions("))
        assertTrue(row.contains("onClick = { onProviderClick(instance.id) }"))
    }
}
