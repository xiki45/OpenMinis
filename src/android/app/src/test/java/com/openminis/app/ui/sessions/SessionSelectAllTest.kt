package com.openminis.app.ui.sessions

import com.openminis.app.ProductionSources
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [FIX-261025-07] During a search, "Select All" then Delete deleted every chat,
 * not the results on screen; and "Deselect All" re-selected instead.
 */
class SessionSelectAllTest {

    @Test fun `deselect only when every shown row is selected`() {
        assertTrue(allShownSelected(setOf("a", "b", "z"), setOf("a", "b")))
        assertFalse(allShownSelected(setOf("a"), setOf("a", "b")))
        assertFalse("an empty list never reads Deselect All", allShownSelected(emptySet(), emptySet()))
    }

    @Test fun `select all takes the shown rows, not every chat, and replaces the selection`() {
        val vm = ProductionSources.read("ui/sessions/SessionListViewModel.kt")
        val body = vm.substringAfter("fun toggleSelectAll() {").substringBefore("\n    }")
        assertTrue(body.contains("displayedSessions.value"))
        assertFalse("never every chat", body.contains("_allSessions"))
        assertTrue(body.contains("if (allShownSelected(selectedIds.value, shown)) emptySet() else shown"))
        val screen = ProductionSources.read("ui/sessions/SessionListScreen.kt")
        assertTrue(screen.contains("onClick = { viewModel.toggleSelectAll() }"))
        assertFalse(screen.contains("viewModel.selectAll()"))
    }
}
