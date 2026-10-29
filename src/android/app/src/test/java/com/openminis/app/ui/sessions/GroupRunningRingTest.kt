package com.openminis.app.ui.sessions

import com.openminis.app.data.db.ChatSessionEntity
import com.openminis.app.data.db.FolderEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-group-running-ring] A collapsed group reports whether any member
 * is running, so the card can show the spinning ring.
 *
 * The bug this pins: a task started inside a group showed its ring on the
 * session row, but collapsing the group hid the row — and the group card had
 * no ring of its own, so the only signal that work was in flight disappeared.
 * iOS has carried `SidebarGroup.anyActive` for this since ContentView:4771;
 * Android aggregated `anyPaused` but never the running state.
 */
class GroupRunningRingTest {

    private fun folder(id: String) =
        FolderEntity(id = id, name = "F-$id", createdAt = 0, updatedAt = 0)

    private fun session(id: String, folderId: String?) =
        ChatSessionEntity(
            id = id, modelId = "m", createdAt = 0, updatedAt = 0, folderId = folderId,
        )

    private fun blocksFor(active: Set<String>, expanded: String? = "b") =
        partitionByFolder(
            sessions = listOf(session("s1", "a"), session("s2", "a"), session("s3", "b")),
            folders = listOf(folder("a"), folder("b")),
            expandedFolderId = expanded,
            activeSessionIds = active,
        ).first

    @Test
    fun `a running member marks its group active`() {
        val blocks = blocksFor(active = setOf("s2"))
        assertTrue("group a has a running member", blocks.first { it.folder.id == "a" }.anyActive)
    }

    @Test
    fun `a group with no running member is not active`() {
        val blocks = blocksFor(active = setOf("s2"))
        assertFalse("group b has none", blocks.first { it.folder.id == "b" }.anyActive)
    }

    @Test
    fun `no running sessions leaves every group inactive`() {
        blocksFor(active = emptySet()).forEach { assertFalse(it.anyActive) }
    }

    @Test
    fun `a running id belonging to no group marks nothing`() {
        // An ungrouped session's ring is its own row's business.
        blocksFor(active = setOf("orphan")).forEach { assertFalse(it.anyActive) }
    }

    @Test
    fun `activity is reported regardless of collapse state`() {
        // The flag is pure data; the collapsed-only rule is a RENDER decision,
        // so an expanded group still reports it. Keeping the two separate means
        // expanding a group can't drop the state.
        val expanded = blocksFor(active = setOf("s1"), expanded = "a")
        assertTrue(expanded.first { it.folder.id == "a" }.anyActive)
    }

    @Test
    fun `anyActive is independent of anyPaused`() {
        // Distinct signals: a group can be running without any paused member.
        val blocks = blocksFor(active = setOf("s1"))
        val a = blocks.first { it.folder.id == "a" }
        assertTrue(a.anyActive)
        assertFalse("no fresh badges were supplied", a.anyPaused)
    }

    @Test
    fun `default has no active groups`() {
        // Callers that don't pass the set (tests, non-list surfaces) must not
        // light up every card.
        val blocks = partitionByFolder(
            sessions = listOf(session("s1", "a")),
            folders = listOf(folder("a")),
            expandedFolderId = null,
        ).first
        assertFalse(blocks.single().anyActive)
    }

    // --- [UI-261023-03] The comet's colour --------------------------------

    private fun member(id: String, updatedAt: Long, category: String, parent: String? = null) =
        ChatSessionEntity(
            id = id, modelId = "m", createdAt = 0, updatedAt = updatedAt,
            category = category, folderId = "a", parentSessionId = parent,
        )

    private fun activeCategoryOf(sessions: List<ChatSessionEntity>, active: Set<String>) =
        partitionByFolder(
            sessions = sessions,
            folders = listOf(folder("a")),
            expandedFolderId = null,
            activeSessionIds = active,
        ).first.single().activeCategory

    @Test
    fun `the comet takes the newest working member's category`() {
        val members = listOf(member("old", 1, "code"), member("new", 9, "health"), member("idle", 20, "math"))
        assertEquals("health", activeCategoryOf(members, active = setOf("old", "new")))
        assertEquals("code", activeCategoryOf(members, active = setOf("old")))
    }

    @Test
    fun `a working chat wins over a newer working sub agent`() {
        val members = listOf(member("p", 1, "writing"), member("child", 9, "research", parent = "p"))
        assertEquals("writing", activeCategoryOf(members, active = setOf("p", "child")))
        assertEquals("research", activeCategoryOf(members, active = setOf("child")))
    }

    @Test
    fun `no working member, no comet colour`() {
        assertNull(activeCategoryOf(listOf(member("a1", 1, "code")), active = emptySet()))
    }
}
