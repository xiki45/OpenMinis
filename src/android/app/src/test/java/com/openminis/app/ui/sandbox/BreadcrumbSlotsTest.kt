package com.openminis.app.ui.sandbox

import com.openminis.app.ui.sandbox.BreadcrumbSlot.Collapsed
import com.openminis.app.ui.sandbox.BreadcrumbSlot.Component
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [path-ellipsis] Deep paths fold their middle into one `···` capsule: root,
 * the folded folders (in order), the parent, the current folder.
 */
class BreadcrumbSlotsTest {

    @Test
    fun `up to four components show in full`() {
        assertEquals(emptyList<BreadcrumbSlot>(), breadcrumbSlots(0))
        assertEquals(listOf(Component(0)), breadcrumbSlots(1))
        assertEquals((0 until 4).map { Component(it) }, breadcrumbSlots(4))
    }

    @Test
    fun `five components fold the one in the middle`() {
        assertEquals(
            listOf(Component(0), Collapsed(1..2), Component(3), Component(4)),
            breadcrumbSlots(5),
        )
    }

    @Test
    fun `a deep path keeps root, parent and current, and folds every folder between in order`() {
        val slots = breadcrumbSlots(9)
        assertEquals(listOf(Component(0), Collapsed(1..6), Component(7), Component(8)), slots)
        // Every component is reachable exactly once: shown, or in the menu.
        val reachable = slots.flatMap {
            when (it) {
                is Component -> listOf(it.index)
                is Collapsed -> it.indices.toList()
            }
        }
        assertEquals((0 until 9).toList(), reachable)
    }
}
