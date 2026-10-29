package com.openminis.app.ui.sessions

import com.openminis.app.data.db.ChatSessionEntity
import com.openminis.app.data.db.FolderEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [T-folder-pages] An open folder lists its first 20 members with "Show N more
 * (M left)" for the rest (port of iOS 14bc208b1, FolderPagesTests.swift).
 */
class FolderPagesTest {

    // ── Page maths (iOS vectors) ───────────────────────────────────────────

    @Test fun `pages of 15, 20, 21 and 45`() {
        assertEquals(15 to 0, FolderPages.page(15, 1))
        assertEquals(20 to 0, FolderPages.page(20, 1))
        assertEquals(20 to 1, FolderPages.page(21, 1))
        assertEquals(21 to 0, FolderPages.page(21, 2))
        assertEquals(20 to 25, FolderPages.page(45, 1))
        assertEquals(40 to 5, FolderPages.page(45, 2))
        assertEquals(45 to 0, FolderPages.page(45, 3))
        assertEquals(45 to 0, FolderPages.page(45, 99))
    }

    @Test fun `edges - nothing, a non-positive page or page size`() {
        assertEquals(0 to 0, FolderPages.page(0, 1))
        assertEquals(0 to 0, FolderPages.page(-3, 1))
        assertEquals(20 to 25, FolderPages.page(45, 0))
        assertEquals(20 to 25, FolderPages.page(45, -2))
        assertEquals(1 to 44, FolderPages.page(45, 1, pageSize = 0))
        assertEquals(45 to 0, FolderPages.page(45, Int.MAX_VALUE))
    }

    @Test fun `tapping through until nothing is hidden`() {
        for ((total, taps) in listOf(15 to 0, 20 to 0, 21 to 1, 45 to 2, 100 to 4, 101 to 5)) {
            var pages = 1
            var n = 0
            while (FolderPages.page(total, pages).second > 0) {
                pages += 1
                n += 1
            }
            assertEquals("taps for $total", taps, n)
            assertEquals(total to 0, FolderPages.page(total, pages))
        }
    }

    @Test fun `the row offers the next page, capped by what is left`() {
        assertEquals(20, FolderPages.nextPageCount(25))
        assertEquals(5, FolderPages.nextPageCount(5))
        assertEquals(0, FolderPages.nextPageCount(0))
    }

    @Test fun `reveal widens to the page holding the row, never narrows`() {
        assertEquals(1, FolderPages.revealing(null, 1))
        assertEquals(1, FolderPages.revealing(19, 1))
        assertEquals(2, FolderPages.revealing(20, 1))
        assertEquals(3, FolderPages.revealing(44, 1))
        assertEquals(3, FolderPages.revealing(5, 3))
        assertEquals(1, FolderPages.revealing(-1, 0))
    }

    // ── Stored pages: a state model of the view model's map ────────────────

    @Test fun `show more, collapse resets, reveal is kept, other folders are ignored`() {
        var pages = emptyMap<String, Int>()
        pages = FolderPages.afterShowMore(pages, open = "a", folderId = "a", pagesShown = 1)
        assertEquals(mapOf("a" to 2), pages)
        pages = FolderPages.afterShowMore(pages, open = "a", folderId = "a", pagesShown = 2)
        assertEquals(mapOf("a" to 3), pages)
        // A stale tap for a folder that is not open changes nothing.
        assertEquals(pages, FolderPages.afterShowMore(pages, open = "a", folderId = "b", pagesShown = 1))
        // Collapsing (null) or opening another folder (accordion) drops the entry.
        assertEquals(emptyMap<String, Int>(), FolderPages.afterOpenChanged(pages, null))
        assertEquals(emptyMap<String, Int>(), FolderPages.afterOpenChanged(pages, "b"))
        assertEquals(pages, FolderPages.afterOpenChanged(pages, "a"))
        // A reveal is stored, and never narrows a wider choice.
        assertEquals(mapOf("b" to 2), FolderPages.afterReveal(emptyMap(), open = "b", folderId = "b", revealed = 2))
        assertEquals(mapOf("b" to 3), FolderPages.afterReveal(mapOf("b" to 3), open = "b", folderId = "b", revealed = 2))
        assertEquals(emptyMap<String, Int>(), FolderPages.afterReveal(emptyMap(), open = null, folderId = "b", revealed = 2))
    }

    @Test fun `show more after a reveal counts from the pages on screen`() {
        // Stored 1, but a reveal shows 3: the tap must open page 4, not 2.
        assertEquals(mapOf("a" to 4), FolderPages.afterShowMore(emptyMap(), open = "a", folderId = "a", pagesShown = 3))
    }

    // ── partitionByFolder ──────────────────────────────────────────────────

    private fun folder(id: String) = FolderEntity(id = id, name = "F-$id", createdAt = 0, updatedAt = 0)

    /** n members of folder [fid], newest first; ids "fid-0" (newest) … */
    private fun members(fid: String, n: Int, pinned: Set<Int> = emptySet()) = (0 until n).map { i ->
        ChatSessionEntity(
            id = "$fid-$i", modelId = "m", createdAt = 0, updatedAt = 10_000L - i, folderId = fid,
            pinnedAt = if (i in pinned) 1L else null,
        )
    }

    private fun block(
        n: Int,
        pages: Map<String, Int> = emptyMap(),
        selecting: Boolean = false,
        reveal: Set<String> = emptySet(),
        expanded: String? = "a",
        pinned: Set<Int> = emptySet(),
    ): FolderGroupBlock =
        partitionByFolder(
            sessions = members("a", n, pinned),
            folders = listOf(folder("a")),
            expandedFolderId = expanded,
            folderPages = pages,
            showAllMembers = selecting,
            revealIds = reveal,
        ).first.single()

    @Test fun `an open folder shows its first 20, the rest behind show more`() {
        val b = block(45)
        assertEquals((0 until 20).map { "a-$it" }, b.ids)
        assertEquals(25, b.hiddenCount)
        assertEquals(1, b.pagesShown)
        assertEquals("the card still counts every member", 45, b.totalCount)
    }

    @Test fun `stored pages show more`() {
        assertEquals(40 to 5, block(45, pages = mapOf("a" to 2)).let { it.ids.size to it.hiddenCount })
        assertEquals(45 to 0, block(45, pages = mapOf("a" to 3)).let { it.ids.size to it.hiddenCount })
    }

    @Test fun `a small folder is unchanged`() {
        val b = block(15)
        assertEquals(15, b.ids.size)
        assertEquals(0, b.hiddenCount)
    }

    @Test fun `select mode lists every member`() {
        val b = block(45, selecting = true)
        assertEquals(45, b.ids.size)
        assertEquals(0, b.hiddenCount)
    }

    @Test fun `the selected session is never paged out`() {
        val b = block(45, reveal = setOf("a-33"))
        assertTrue("a-33" in b.ids)
        assertEquals(2, b.pagesShown)
        assertEquals(40 to 5, b.ids.size to b.hiddenCount)
        // A revealed id outside the folder changes nothing.
        assertEquals(1, block(45, reveal = setOf("elsewhere")).pagesShown)
    }

    @Test fun `pinned members stay first and count toward the first page`() {
        val b = block(30, pinned = setOf(25, 29))
        assertEquals(listOf("a-25", "a-29"), b.ids.take(2))
        assertEquals(20, b.ids.size)
        assertEquals(10, b.hiddenCount)
    }

    @Test fun `a collapsed folder has no rows and nothing hidden`() {
        val b = block(45, expanded = null, pages = mapOf("a" to 3))
        assertEquals(emptyList<String>(), b.ids)
        assertEquals(0, b.hiddenCount)
        assertEquals(45, b.totalCount)
    }

    // ── Localization ───────────────────────────────────────────────────────

    @Test fun `the show-more row is translated in every locale the app ships`() {
        val res = listOf(File("src/main/res"), File("app/src/main/res")).first { it.isDirectory }
        val localeDirs = res.listFiles()!!.filter { it.name.startsWith("values") && File(it, "strings.xml").isFile }
        assertTrue(localeDirs.size >= 20)
        for (dir in localeDirs) {
            val f = File(dir, "strings_folder_pages.xml")
            assertTrue("${dir.name} has no strings_folder_pages.xml", f.isFile)
            val text = f.readText()
            assertTrue("${dir.name}: both counts, positional", text.contains("%1\$d") && text.contains("%2\$d"))
        }
    }
}
