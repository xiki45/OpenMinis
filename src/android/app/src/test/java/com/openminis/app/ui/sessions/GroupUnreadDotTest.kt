package com.openminis.app.ui.sessions

import com.openminis.app.data.db.ChatSessionEntity
import com.openminis.app.data.db.FolderEntity
import com.openminis.app.service.SessionUnreadStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [UI-261023-07] Unread replies: which chats carry the dot, and which groups
 * pass it to their tile (iOS `SessionBadgeState.unread` / `anyUnread`).
 */
class GroupUnreadDotTest {

    private val token = Any()

    @After
    fun tearDown() {
        SessionUnreadStore.stopViewing(token)
        SessionUnreadStore.ids.value.forEach { SessionUnreadStore.markRead(it) }
    }

    private fun folder(id: String) =
        FolderEntity(id = id, name = "F-$id", createdAt = 0, updatedAt = 0)

    private fun session(id: String, folderId: String?) =
        ChatSessionEntity(id = id, modelId = "m", createdAt = 0, updatedAt = 0, folderId = folderId)

    private fun blocksFor(unread: Set<String>) =
        partitionByFolder(
            sessions = listOf(session("s1", "a"), session("s2", "a"), session("s3", "b"), session("loose", null)),
            folders = listOf(folder("a"), folder("b")),
            expandedFolderId = null,
            unreadIds = unread,
        ).first

    @Test
    fun `a group with an unread member is unread`() {
        val blocks = blocksFor(unread = setOf("s2"))
        assertTrue(blocks.first { it.folder.id == "a" }.anyUnread)
        assertFalse(blocks.first { it.folder.id == "b" }.anyUnread)
    }

    @Test
    fun `an unread loose chat marks no group`() {
        blocksFor(unread = setOf("loose")).forEach { assertFalse(it.anyUnread) }
    }

    @Test
    fun `a reply marks the chat unread, opening it clears it`() {
        SessionUnreadStore.markUnread("s1")
        assertEquals(setOf("s1"), SessionUnreadStore.ids.value)
        SessionUnreadStore.startViewing(token) { "s1" }
        assertTrue(SessionUnreadStore.ids.value.isEmpty())
    }

    @Test
    fun `a reply to the chat on screen raises no dot`() {
        SessionUnreadStore.startViewing(token) { "s1" }
        SessionUnreadStore.markUnread("s1")
        assertTrue(SessionUnreadStore.ids.value.isEmpty())
        // Once the user has left, the next reply counts.
        SessionUnreadStore.stopViewing(token)
        SessionUnreadStore.markUnread("s1")
        assertEquals(setOf("s1"), SessionUnreadStore.ids.value)
    }
}
