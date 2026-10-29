package com.openminis.app.ui.chat.search

import com.openminis.app.ui.chat.AssistantBlock
import com.openminis.app.ui.chat.ChatMessage
import com.openminis.app.ui.chat.ChatSearchPiece
import com.openminis.app.ui.chat.FlatChatItem
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-in-chat-search] ChatSearchController's flow (iOS ChatSearchController):
 * 250 ms typing debounce, newest match on a new query, wrapping with a notice,
 * a 300 ms throttled re-search on new rows that keeps the current match and
 * does not scroll, and Done clearing everything.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatSearchControllerTest {

    private val source = object : ChatSearchUnits.TextSource {
        override fun markdownPieces(raw: String) = listOf(ChatSearchPiece("b0", raw))
        override fun toolCapsule(block: AssistantBlock): String? = null
    }

    private fun user(id: String, text: String) = FlatChatItem.UserBubble(ChatMessage(id = id, role = "user", content = text))

    private fun TestScope.controller(): ChatSearchController {
        val d = StandardTestDispatcher(testScheduler)
        return ChatSearchController(this, source, background = d)
    }

    @Test fun `typing is debounced 250 ms, then the newest match is current and navigated to`() = runTest {
        val c = controller()
        val navs = mutableListOf<ChatSearchMatch>()
        val collector = launch { c.navigations.toList(navs) }
        c.onRows(listOf(user("u1", "apple"), user("u2", "apple pie"), user("u3", "nothing")))
        c.activate()
        assertTrue(c.isActive)
        c.updateQuery("apple")
        assertTrue("searching flag set at once", c.isSearching)
        advanceTimeBy(200); runCurrent()
        assertEquals("not yet: still inside the debounce", 0, c.matchCount)
        advanceUntilIdle()
        assertEquals(2, c.matchCount)
        assertEquals(1, c.currentIndex)
        assertEquals("2/2", c.counterText)
        assertEquals(ChatSearchUnitKey("user:u2", "body"), c.currentMatch?.key)
        assertEquals(listOf(c.currentMatch), navs)
        assertEquals(1, c.highlight.count(ChatSearchUnitKey("user:u1", "body")))
        assertFalse(c.isSearching)
        collector.cancel()
    }

    @Test fun `a newer query cancels an older one`() = runTest {
        val c = controller()
        c.onRows(listOf(user("u1", "alpha beta")))
        c.activate()
        c.updateQuery("alpha")
        advanceTimeBy(100); runCurrent()
        c.updateQuery("beta")
        advanceUntilIdle()
        assertEquals("beta", c.highlight.query)
        assertEquals(1, c.matchCount)
    }

    @Test fun `stepping wraps with a notice that clears itself`() = runTest {
        val c = controller()
        c.onRows(listOf(user("u1", "x x x")))
        c.activate("x")
        advanceUntilIdle()
        assertEquals(2, c.currentIndex)
        c.next()
        assertEquals(0, c.currentIndex)
        assertEquals(ChatSearchController.Notice.WRAPPED_TO_FIRST, c.notice)
        assertEquals(c.currentMatch, c.highlight.current)
        c.previous()
        assertEquals(2, c.currentIndex)
        assertEquals(ChatSearchController.Notice.WRAPPED_TO_LAST, c.notice)
        advanceTimeBy(ChatSearchController.NOTICE_MS + 1); runCurrent()
        assertNull(c.notice)
    }

    @Test fun `prefill searches at once`() = runTest {
        val c = controller()
        c.onRows(listOf(user("u1", "needle")))
        c.activate("  needle ")
        assertEquals("needle", c.query)
        runCurrent(); advanceUntilIdle()
        assertEquals(1, c.matchCount)
    }

    @Test fun `new rows re-search after 300 ms, keep the current match and do not scroll`() = runTest {
        val c = controller()
        val navs = mutableListOf<ChatSearchMatch>()
        val collector = launch { c.navigations.toList(navs) }
        val rows = listOf(user("u1", "foo"), user("u2", "foo"))
        c.onRows(rows)
        c.activate("foo")
        advanceUntilIdle()
        c.previous()
        runCurrent() // let the collector take previous()'s own navigation
        val kept = c.currentMatch
        assertEquals(ChatSearchUnitKey("user:u1", "body"), kept?.key)
        val navsBefore = navs.size
        assertEquals("the new query's landing + the step", 2, navsBefore)
        c.onRows(rows + user("u3", "foo again"))
        advanceTimeBy(ChatSearchController.STREAMING_THROTTLE_MS - 50); runCurrent()
        assertEquals("throttled", 2, c.matchCount)
        advanceUntilIdle()
        assertEquals(3, c.matchCount)
        assertEquals("same match by identity", kept, c.currentMatch)
        assertEquals("a refresh does not scroll", navsBefore, navs.size)
        collector.cancel()
    }

    @Test fun `rows changing while closed do nothing`() = runTest {
        val c = controller()
        c.onRows(listOf(user("u1", "foo")))
        advanceUntilIdle()
        assertEquals(0, c.matchCount)
        assertFalse(c.isActive)
    }

    @Test fun `done clears query, results and highlights`() = runTest {
        val c = controller()
        c.onRows(listOf(user("u1", "foo")))
        c.activate("foo")
        advanceUntilIdle()
        c.deactivate()
        assertFalse(c.isActive)
        assertEquals("", c.query)
        assertEquals(0, c.matchCount)
        assertNull(c.currentIndex)
        assertEquals(ChatSearchHighlight.NONE, c.highlight)
    }

    @Test fun `no results reads 0 of 0 and has no highlight`() = runTest {
        val c = controller()
        c.onRows(listOf(user("u1", "foo")))
        c.activate("zzz")
        advanceUntilIdle()
        assertEquals(0, c.matchCount)
        assertEquals("0/0", c.counterText)
        assertEquals(ChatSearchHighlight.NONE, c.highlight)
    }
}
