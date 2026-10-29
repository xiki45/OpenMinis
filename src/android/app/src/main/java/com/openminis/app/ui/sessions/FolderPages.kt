package com.openminis.app.ui.sessions

/**
 * [T-folder-pages] An open folder shows its members 20 at a time, with a
 * "Show N more (M left)" row for the rest. Port of iOS 14bc208b1
 * (`ContentView.folderPage` / `folderPagesRevealing`).
 *
 * Before, an open folder listed every member, so a busy folder took the whole
 * sidebar and pushed every other folder and date bucket off screen.
 *
 * Pure, so the page maths is tested without Compose.
 */
internal object FolderPages {
    const val PAGE_SIZE = 20

    /**
     * Members shown and still hidden for [pages] pages of [total]. A page
     * count below 1 counts as the first page; a page size below 1 as 1.
     * 15 → 15/0, 20 → 20/0, 21 → 20/1, 45 → 20/25 → 40/5 → 45/0.
     */
    fun page(total: Int, pages: Int, pageSize: Int = PAGE_SIZE): Pair<Int, Int> {
        val all = maxOf(0, total)
        val shown = minOf(all.toLong(), maxOf(1, pages).toLong() * maxOf(1, pageSize)).toInt()
        return shown to (all - shown)
    }

    /**
     * Pages needed so the member at [index] is on screen, never fewer than
     * [stored] (or 1). A row that must stay visible — the session open in the
     * detail pane, a chat just started in the folder — is never paged out.
     */
    fun revealing(index: Int?, stored: Int, pageSize: Int = PAGE_SIZE): Int {
        val floor = maxOf(1, stored)
        if (index == null || index < 0) return floor
        return maxOf(floor, index / maxOf(1, pageSize) + 1)
    }

    /** What the "Show N more" row offers: the next page, capped by what is left. */
    fun nextPageCount(hidden: Int, pageSize: Int = PAGE_SIZE): Int = minOf(maxOf(0, hidden), maxOf(1, pageSize))

    // ── Stored pages (SessionListViewModel.folderPages) ───────────────────

    /**
     * The open folder changed to [open] (null = all folded): every other
     * folder's entry goes, so a folder that closes reopens on page 1.
     */
    fun afterOpenChanged(pages: Map<String, Int>, open: String?): Map<String, Int> =
        pages.filterKeys { it == open }

    /** "Show more" on [folderId]: one page past [pagesShown]. Ignored unless it is the open folder. */
    fun afterShowMore(pages: Map<String, Int>, open: String?, folderId: String, pagesShown: Int): Map<String, Int> =
        if (folderId != open) pages else pages + (folderId to maxOf(1, pagesShown) + 1)

    /** Keep the [revealed] pages a selection opened; never narrows. Ignored unless it is the open folder. */
    fun afterReveal(pages: Map<String, Int>, open: String?, folderId: String, revealed: Int): Map<String, Int> =
        if (folderId != open || (pages[folderId] ?: 1) >= revealed) pages else pages + (folderId to revealed)

    // ── Search pages (SessionListViewModel.searchFolderPages) ─────────────

    /**
     * [T-search-folder-pages] "Show more" on a folder in a search result set
     * (iOS e709a821e): every result folder is open, so any of them pages, and
     * the pages are kept apart from the normal ones - a search neither reads
     * nor resets those. Reset when a new result set arrives.
     */
    fun afterSearchShowMore(pages: Map<String, Int>, folderId: String, pagesShown: Int): Map<String, Int> =
        pages + (folderId to maxOf(1, pagesShown) + 1)
}
