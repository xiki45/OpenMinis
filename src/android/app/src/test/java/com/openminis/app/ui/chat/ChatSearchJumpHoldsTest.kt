package com.openminis.app.ui.chat

import com.openminis.app.ProductionSources
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [FIX-261025-08] A Search in Chat jump while a reply streams was undone within
 * ~0.1 s: the follow loop still saw a reader at the bottom.
 */
class ChatSearchJumpHoldsTest {
    @Test fun `a search jump marks the reader scrolled away before it moves the list`() {
        val src = ProductionSources.read("ui/chat/ChatScreen.kt")
        val collector = src.substringAfter("chatSearch.navigations.collect { match ->").substringBefore("listState.scrollBy(-(viewport * 0.6f))")
        val away = collector.indexOf("userScrolledAway = true")
        assertTrue("marked", away > 0)
        assertTrue("before the scroll", away < collector.indexOf("listState.scrollToItem(target)"))
        assertTrue(collector.contains("lastInterruptMs = System.currentTimeMillis()"))
    }
}
