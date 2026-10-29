package com.openminis.app.ui.sessions

import com.openminis.app.data.db.ChatSessionEntity
import com.openminis.app.data.db.FolderEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [PAR-261022-02] A group's avatar is a WeChat-style tile of its most recently
 * updated members (Windows UI-261022-02..04): which members it shows, in what
 * order, and where their cells sit.
 */
class GroupMemberTileTest {

    private fun folder(id: String) =
        FolderEntity(id = id, name = "F-$id", createdAt = 0, updatedAt = 0)

    private fun session(id: String, updatedAt: Long, category: String? = id, parent: String? = null) =
        ChatSessionEntity(
            id = id, modelId = "m", createdAt = 0, updatedAt = updatedAt,
            category = category, folderId = "a", parentSessionId = parent,
        )

    private fun tileOf(sessions: List<ChatSessionEntity>) =
        partitionByFolder(
            sessions = sessions,
            folders = listOf(folder("a")),
            expandedFolderId = null,
        ).first.single().tileMembers

    @Test
    fun `the tile shows the four newest members, newest first`() {
        val tile = tileOf((1..6).map { session("c$it", updatedAt = it * 10L) })
        assertEquals(listOf("c6", "c5", "c4", "c3"), tile.map { it.category })
    }

    @Test
    fun `the tile follows the members' update order, not the list order`() {
        // A pinned member is listed first in an open group; the tile still goes by recency.
        val tile = tileOf(listOf(session("old", 1), session("new", 9), session("mid", 5)))
        assertEquals(listOf("new", "mid", "old"), tile.map { it.category })
    }

    @Test
    fun `sub agent chats are not members`() {
        val tile = tileOf(listOf(session("p", 1), session("child", 9, parent = "p")))
        assertEquals(listOf("p"), tile.map { it.category })
    }

    @Test
    fun `an empty group has no cells`() {
        // Freshly created, so the empty group keeps its card.
        val blocks = partitionByFolder(
            sessions = emptyList(),
            folders = listOf(folder("a").copy(createdAt = 1_000L)),
            expandedFolderId = null,
            nowMs = 2_000L,
        ).first
        assertTrue(blocks.single().tileMembers.isEmpty())
        assertTrue(memberTileCells(0).isEmpty())
    }

    @Test
    fun `cells follow the WeChat layouts`() {
        // 44dp tile, 4dp inset, 2dp gap: cells are 17dp; far = 23, middle = 13.5.
        val one = memberTileCells(1).single()
        assertEquals(MemberTileCell(13.5f, 13.5f, 17f), one)
        assertEquals(listOf(MemberTileCell(4f, 13.5f, 17f), MemberTileCell(23f, 13.5f, 17f)), memberTileCells(2))
        assertEquals(
            listOf(MemberTileCell(13.5f, 4f, 17f), MemberTileCell(4f, 23f, 17f), MemberTileCell(23f, 23f, 17f)),
            memberTileCells(3),
        )
        val four = memberTileCells(4)
        assertEquals(memberTileCells(MEMBER_TILE_MAX), four)
        assertEquals(listOf(4f to 4f, 23f to 4f, 4f to 23f, 23f to 23f), four.map { it.x to it.y })
        // Every cell stays inside the tile with the inset all round.
        four.forEach { assertTrue(it.x >= 4f && it.x + it.side <= 40f && it.y + it.side <= 40f) }
    }
}
