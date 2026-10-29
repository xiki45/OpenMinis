package com.openminis.app.ui.chat

import com.openminis.app.ProductionSources
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-moveto-empty-content] User report (Honor Magic7): after sharing
 * files into Minis and deleting both attachments, the "Move to…" capsule
 * stayed, and after moving it was still there in the other chat.
 */
class MoveToCapsuleContentTest {

    private val screen = ProductionSources.read("ui/chat/ChatScreen.kt")

    @Test
    fun `movable content is non-blank text or an attachment`() {
        assertFalse(hasMovableShareContent("", 0))
        assertFalse("whitespace is nothing to move", hasMovableShareContent("  \n ", 0))
        assertTrue(hasMovableShareContent("", 1))
        assertTrue(hasMovableShareContent("hi", 0))
    }

    @Test
    fun `the capsule needs both the share flag and something to move`() {
        assertTrue(screen.contains(
            "val showMoveCapsule = shareInjected && hasMovableShareContent(inputText, attachments.size)"))
    }

    @Test
    fun `a moved transfer does not re-arm the capsule in the target chat`() {
        val drain = screen.substringAfter("val transfer = ChatViewModelStore.consumePendingTransfer(sessionId)")
            .substringBefore("// Mirrors `inputText` for the BasicTextField")
        assertTrue(drain.contains("for (a in transfer.attachments) viewModel.addAttachment(a)"))
        assertFalse(drain.contains("viewModel.markShareInjected()"))
        assertTrue("a share still arms it", screen.contains("viewModel.markShareInjected()\n        com.openminis.app.share.SharedShareStore.cleanSharedFiles(context)"))
    }
}
