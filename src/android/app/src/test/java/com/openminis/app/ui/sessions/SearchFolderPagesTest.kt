package com.openminis.app.ui.sessions

import com.openminis.app.ProductionSources
import com.openminis.app.data.db.ChatSessionEntity
import com.openminis.app.data.db.FolderEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-search-folder-pages] A folder in a search result set shows 20 matches at
 * a time with a "Show N more (M left)" row (iOS e709a821e), on the pager the
 * normal list already uses. Vectors from iOS SearchFolderPagesTests.
 */
class SearchFolderPagesTest {

    private fun folder(id: String) = FolderEntity(id = id, name = "F-$id", createdAt = 0, updatedAt = 0)
    private fun matches(fid: String, n: Int) = (0 until n).map {
        ChatSessionEntity(id = "$fid-$it", modelId = "m", createdAt = 0, updatedAt = 10_000L - it, folderId = fid)
    }

    private fun block(n: Int, pages: Map<String, Int> = emptyMap(), selecting: Boolean = false): FolderGroupBlock =
        partitionByFolder(
            matches("a", n), listOf(folder("a")), expandedFolderId = null, nowMs = 0,
            showAllMembers = selecting, searchResultsActive = true, searchFolderPages = pages,
        ).first.single()

    @Test fun `the page maths - 15, 20, 21, 45`() {
        for ((n, want) in listOf(15 to (15 to 0), 20 to (20 to 0), 21 to (20 to 1), 45 to (20 to 25))) {
            val b = block(n)
            assertEquals("$n", want, b.ids.size to b.hiddenCount)
            assertEquals("the card still reports every match", n, b.totalCount)
        }
        assertEquals(40 to 5, block(45, mapOf("a" to 2)).let { it.ids.size to it.hiddenCount })
        assertEquals(45 to 0, block(45, mapOf("a" to 3)).let { it.ids.size to it.hiddenCount })
        assertEquals("overshooting pages", 45 to 0, block(45, mapOf("a" to 9)).let { it.ids.size to it.hiddenCount })
        assertEquals("pages below 1 are the first", 20 to 25, block(45, mapOf("a" to 0)).let { it.ids.size to it.hiddenCount })
    }

    @Test fun `tapping Show more until nothing is left`() {
        for ((n, taps) in listOf(15 to 0, 20 to 0, 21 to 1, 45 to 2, 100 to 4, 101 to 5)) {
            var pages = emptyMap<String, Int>()
            var b = block(n, pages)
            var count = 0
            while (b.hiddenCount > 0) {
                pages = FolderPages.afterSearchShowMore(pages, "a", b.pagesShown)
                b = block(n, pages)
                count++
            }
            assertEquals("$n", taps, count)
            assertEquals(n, b.ids.size)
        }
    }

    @Test fun `search pages are kept apart from the normal ones`() {
        // Every result folder pages, not only "the open one".
        assertEquals(mapOf("x" to 2, "y" to 3), FolderPages.afterSearchShowMore(mapOf("x" to 2), "y", 2))
        // The normal list ignores search pages, and a search ignores the normal ones.
        val normal = partitionByFolder(matches("a", 45), listOf(folder("a")), expandedFolderId = "a", nowMs = 0,
            folderPages = mapOf("a" to 1), searchFolderPages = mapOf("a" to 3)).first.single()
        assertEquals(20, normal.ids.size)
        val search = partitionByFolder(matches("a", 45), listOf(folder("a")), expandedFolderId = "a", nowMs = 0,
            folderPages = mapOf("a" to 3), searchResultsActive = true).first.single()
        assertEquals(20, search.ids.size)
        // As on iOS, search pages apply in select mode too.
        assertEquals(20, block(45, selecting = true).ids.size)
    }

    @Test fun `wiring - reset points and the Show more row`() {
        val vm = ProductionSources.read("ui/sessions/SessionListViewModel.kt")
        val collect = vm.substringAfter("val folderHits = SearchFolders.matchedFolderIds(folders.value, q)").substringBefore("isSearching.value = false")
        // A new result set and a cleared query each start from the first page.
        assertEquals(2, collect.split("searchFolderPages.value = emptyMap()").size - 1)
        assertTrue(vm.contains("searchFolderPages.update { FolderPages.afterSearchShowMore(it, folderId, pagesShown) }"))
        val screen = ProductionSources.read("ui/sessions/SessionListScreen.kt")
        assertTrue(screen.contains("searchFolderPages = if (searchResultsActive) searchFolderPages else emptyMap(),"))
        assertTrue(screen.contains("if (searchResultsActive) {\n                                                        viewModel.showMoreInSearchFolder(block.folder.id, block.pagesShown)"))
        assertTrue(screen.contains("searchResultsActive, searchMatchedFolderIds, searchFolderPages,"))
    }
}
