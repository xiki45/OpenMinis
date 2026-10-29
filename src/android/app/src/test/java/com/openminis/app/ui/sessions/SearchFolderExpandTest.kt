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
 * [T-search-folder-expand] Search results are grouped by folder, every folder
 * in them open with its matches, and a folder whose name matches brings all
 * its members (iOS 6b0ee14c1). Before, Android hid the whole folder block in
 * search and showed a flat list.
 */
class SearchFolderExpandTest {

    private val now = 1_000_000_000L
    private fun folder(id: String, name: String = "F-$id", createdAt: Long = 0) =
        FolderEntity(id = id, name = name, createdAt = createdAt, updatedAt = 0)
    private fun s(id: String, folder: String?, at: Long, pinned: Boolean = false) =
        ChatSessionEntity(id = id, modelId = "m", createdAt = 0, updatedAt = at, folderId = folder, pinnedAt = if (pinned) 1L else null)

    @Test fun `folder names match any keyword, case-insensitively`() {
        val folders = listOf(folder("a", "Work Notes"), folder("b", "Travel"), folder("c", "Recipes"))
        assertEquals(setOf("a"), SearchFolders.matchedFolderIds(folders, "work"))
        assertEquals(setOf("a", "b"), SearchFolders.matchedFolderIds(folders, "NOTES travel"))
        assertEquals(emptySet<String>(), SearchFolders.matchedFolderIds(folders, "   "))
        assertEquals(emptySet<String>(), SearchFolders.matchedFolderIds(folders, "zzz"))
    }

    @Test fun `a name-matched folder brings all its members, newest first`() {
        val all = listOf(s("x1", "a", 50), s("hit", null, 40), s("x2", "a", 30), s("other", "b", 20))
        val results = listOf(s("hit", null, 40))
        val merged = SearchFolders.withFolderMembers(results, all, setOf("a"))
        assertEquals(listOf("x1", "hit", "x2"), merged.map { it.id })
        // No folder hit: the search's own list, untouched.
        assertTrue(SearchFolders.withFolderMembers(results, all, emptySet()) === results)
        // A member that already matched is not listed twice.
        assertEquals(listOf("x1", "x2"), SearchFolders.withFolderMembers(listOf(s("x1", "a", 50)), all, setOf("a")).map { it.id })
    }

    @Test fun `in a result set every folder is open with its matches, with no accordion`() {
        val folders = listOf(folder("a"), folder("b"))
        val results = listOf(s("a1", "a", 30), s("b1", "b", 20), s("u", null, 10))
        val (blocks, ungrouped) = partitionByFolder(
            results, folders, expandedFolderId = null, nowMs = now, searchResultsActive = true,
        )
        assertEquals(listOf("a", "b"), blocks.map { it.folder.id })
        assertTrue(blocks.none { it.isCollapsed })
        assertEquals(listOf(listOf("a1"), listOf("b1")), blocks.map { it.ids })
        assertEquals(listOf("u"), ungrouped.map { it.id })
        // The saved open folder neither closes the others nor is read.
        val (withOpen, _) = partitionByFolder(results, folders, expandedFolderId = "a", nowMs = now, searchResultsActive = true)
        assertTrue(withOpen.none { it.isCollapsed })
    }

    @Test fun `an empty folder shows in a result set only when its name matched`() {
        val folders = listOf(folder("hit", "Hit name", createdAt = now), folder("fresh", createdAt = now), folder("old"))
        val results = listOf(s("u", null, 10))
        val (blocks, _) = partitionByFolder(
            results, folders, expandedFolderId = null, nowMs = now,
            searchResultsActive = true, searchMatchedFolderIds = setOf("hit"),
        )
        assertEquals("a just-created empty folder is noise in a result set", listOf("hit"), blocks.map { it.folder.id })
        assertEquals(0, blocks.single().totalCount)
        // Outside search the recency rule is unchanged.
        val (normal, _) = partitionByFolder(results, folders, expandedFolderId = null, nowMs = now)
        assertEquals(listOf("hit", "fresh"), normal.map { it.folder.id })
    }

    @Test fun `a result folder counts only matches, pinned first`() {
        val folders = listOf(folder("a"))
        val results = (0 until 12).map { s("a$it", "a", 1_000L - it, pinned = it == 7) }
        val (blocks, _) = partitionByFolder(results, folders, expandedFolderId = null, nowMs = now, searchResultsActive = true)
        val b = blocks.single()
        assertEquals(12, b.ids.size)
        assertEquals(12, b.totalCount)
        assertEquals(0, b.hiddenCount)
        assertEquals("pinned member first, as in the normal list", "a7", b.ids.first())
    }

    @Test fun `outside a result set nothing changes`() {
        val folders = listOf(folder("a"), folder("b"))
        val sessions = listOf(s("a1", "a", 30), s("b1", "b", 20))
        val (blocks, _) = partitionByFolder(sessions, folders, expandedFolderId = "b", nowMs = now)
        assertEquals(listOf(true, false), blocks.map { it.isCollapsed })
    }

    @Test fun `wiring`() {
        val screen = ProductionSources.read("ui/sessions/SessionListScreen.kt")
        assertTrue(screen.contains("val searchResultsActive = isSearchActive && searchQuery.isNotBlank() && searchResultsReady"))
        assertTrue(screen.contains("val showFolderBlock = !isSearchActive || searchQuery.isBlank() || searchResultsActive"))
        assertTrue(screen.contains("searchResultsActive = searchResultsActive,"))
        // A search pass never writes the normal pages back.
        assertTrue(screen.contains("if (searchResultsActive) return@LaunchedEffect\n        for (b in folderPartition.first) {"))
        // The card: highlighted name and "N matching chats". (Its toggle and the
        // mini-bar fold for this search only: SearchFolderCollapseTest.)
        assertTrue(screen.contains("searchQuery = if (searchResultsActive) searchQuery else null,"))
        assertTrue(screen.contains("if (searchQuery != null) highlightedAnnotatedString(block.folder.name, searchQuery)"))
        assertTrue(screen.contains("stringResource(R.string.sessionlist_folder_matching_chats, block.totalCount)"))
        // A name-matched folder with no member is shown instead of "no results".
        assertTrue(screen.contains("if (sessions.isEmpty() && !(searchResultsActive && folderBlocks.isNotEmpty())) {"))
        val vm = ProductionSources.read("ui/sessions/SessionListViewModel.kt")
        assertTrue(vm.contains("SearchFolders.withFolderMembers(hits.map { it.session }, _allSessions.value, folderHits)"))
        assertTrue(vm.contains("searchResultsReady.value = true"))
        assertTrue(vm.contains("searchResultsReady.value = false"))
    }

    @Test fun `the count line is in every locale`() {
        val res = ProductionSources.mainRoot()!!.resolve("../../../../res").canonicalFile
        val dirs = res.listFiles()!!.filter { it.name.startsWith("values") && File(it, "strings.xml").exists() }
        assertTrue(dirs.size >= 20)
        for (d in dirs) {
            val f = File(d, "strings_search_folders.xml")
            assertTrue(d.name, f.exists())
            assertTrue(d.name, f.readText().contains("name=\"sessionlist_folder_matching_chats\"") && f.readText().contains("%1\$d"))
        }
        assertFalse(File(res, "values/strings_search_folders.xml").readText().contains("%lld"))
    }
}
