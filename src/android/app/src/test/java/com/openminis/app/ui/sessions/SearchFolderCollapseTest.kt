package com.openminis.app.ui.sessions

import com.openminis.app.ProductionSources
import com.openminis.app.data.db.ChatSessionEntity
import com.openminis.app.data.db.FolderEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [T-search-folder-collapse] A folder in search results can be folded away
 * for that search (iOS 14bc208b1): an unsaved set, no accordion, cleared by
 * the next query, and the card says how many matches the fold hides.
 */
class SearchFolderCollapseTest {

    private fun folder(id: String) = FolderEntity(id = id, name = "F-$id", createdAt = 0, updatedAt = 0)
    private fun matches(fid: String, n: Int, from: Long = 10_000L) = (0 until n).map {
        ChatSessionEntity(id = "$fid-$it", modelId = "m", createdAt = 0, updatedAt = from - it, folderId = fid)
    }

    private fun blocks(collapsed: Set<String>, pages: Map<String, Int> = emptyMap(), expanded: String? = null) =
        partitionByFolder(
            matches("a", 45) + matches("b", 3, from = 1_000L), listOf(folder("a"), folder("b")),
            expandedFolderId = expanded, nowMs = 0, searchResultsActive = true,
            searchFolderPages = pages, searchCollapsedFolderIds = collapsed,
        ).first.associateBy { it.folder.id }

    @Test fun `a folded result shows no rows and no Show more, the others stay open`() {
        val b = blocks(setOf("a"), pages = mapOf("a" to 2))
        assertTrue(b.getValue("a").isCollapsed)
        assertTrue(b.getValue("a").ids.isEmpty())
        assertEquals(0, b.getValue("a").hiddenCount)
        assertEquals("the card still counts its matches", 45, b.getValue("a").totalCount)
        assertFalse("no accordion", b.getValue("b").isCollapsed)
        assertEquals(3, b.getValue("b").ids.size)
    }

    @Test fun `unfolding shows the same pages`() {
        val pages = mapOf("a" to 2)
        assertEquals(40, blocks(emptySet(), pages).getValue("a").ids.size)
        assertEquals(40, blocks(setOf("a"), pages).let { blocks(emptySet(), pages) }.getValue("a").ids.size)
    }

    @Test fun `the saved open folder is neither read nor needed`() {
        // The normal list's open folder "b" does not close "a" in a result set.
        val b = blocks(emptySet(), expanded = "b")
        assertFalse(b.getValue("a").isCollapsed)
        assertFalse(b.getValue("b").isCollapsed)
    }

    @Test fun `wiring - the search set is toggled, never saved, and cleared with the query`() {
        val vm = ProductionSources.read("ui/sessions/SessionListViewModel.kt")
        val toggle = vm.substringAfter("fun toggleSearchFolderCollapsed(folderId: String) {").substringBefore("\n    }\n")
        assertTrue(toggle.contains("searchCollapsedFolderIds.update { if (folderId in it) it - folderId else it + folderId }"))
        assertFalse("nothing persisted", toggle.contains("prefs") || toggle.contains("expandedFolderId"))
        val collect = vm.substringAfter("val folderHits = SearchFolders.matchedFolderIds(folders.value, q)").substringBefore("isSearching.value = false")
        assertEquals(2, collect.split("searchCollapsedFolderIds.value = emptySet()").size - 1)
        val screen = ProductionSources.read("ui/sessions/SessionListScreen.kt")
        // Card tap, mini-bar and "new chat in group" use the search set in a result set.
        assertTrue(screen.contains("if (searchResultsActive) {\n                                                viewModel.toggleSearchFolderCollapsed(block.folder.id)\n                                                return@FolderCard"))
        assertTrue(screen.contains("if (searchResultsActive) viewModel.toggleSearchFolderCollapsed(bar.folder.id)\n                                        else viewModel.toggleFolderCollapsed(bar.folder.id)"))
        assertTrue(screen.contains("viewModel.reopenSearchFolder(block.folder.id)"))
        assertTrue(screen.contains("searchCollapsedFolderIds = if (searchResultsActive) searchCollapsedFolderIds else emptySet(),"))
        // The count line while folded.
        assertTrue(screen.contains("searchQuery != null && block.isCollapsed && block.totalCount > 0 ->\n                            stringResource(R.string.sessionlist_folder_matches_hidden, block.totalCount)"))
    }

    @Test fun `the hidden count line is in every locale`() {
        val res = ProductionSources.mainRoot()!!.resolve("../../../../res").canonicalFile
        val dirs = res.listFiles()!!.filter { it.name.startsWith("values") && File(it, "strings.xml").exists() }
        for (d in dirs) {
            val t = File(d, "strings_search_folders.xml").readText()
            assertTrue(d.name, t.contains("name=\"sessionlist_folder_matches_hidden\"") && t.contains("%1\$d"))
        }
    }
}
