package com.openminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-stream-grow-reveal] A row of the streaming reply that appears
 * while following keeps the list pinned to the bottom, so it opens in place
 * instead of landing below the viewport.
 */
class StreamGrowRevealTest {

    private fun row(key: String, owner: String? = "m2") = StreamGrowRevealTracker.Row(key, owner)

    private val history = listOf(row("user:m1", null), row("mdblock:m1:b:0", "m1"), row("user:m2", null))

    @Test
    fun `matches iOS duration`() {
        assertEquals(200, STREAM_GROW_DURATION_MS)
    }

    @Test
    fun `a new reply row while following pins`() {
        val t = StreamGrowRevealTracker()
        val base = history + row("header:m2") + row("thinking:m2:t")
        t.onRows(base, "m2", pinned = true)
        assertTrue(t.onRows(base + row("mdblock:m2:b:0"), "m2", pinned = true))
    }

    @Test
    fun `a recomposition with nothing new does not pin`() {
        val t = StreamGrowRevealTracker()
        val rows = history + row("header:m2") + row("mdblock:m2:b:0")
        t.onRows(rows, "m2", pinned = true)
        assertFalse(t.onRows(rows, "m2", pinned = true))
    }

    @Test
    fun `nothing is new on the first list of a session`() {
        assertFalse(StreamGrowRevealTracker().onRows(history + row("header:m2"), "m2", pinned = true))
    }

    @Test
    fun `no pin once the stream is over`() {
        val t = StreamGrowRevealTracker()
        val base = history + row("header:m2")
        t.onRows(base, "m2", pinned = true)
        assertFalse(t.onRows(base + row("usage:m2"), null, pinned = true))
    }

    @Test
    fun `a reader who scrolled away is not pinned`() {
        val t = StreamGrowRevealTracker()
        val base = history + row("header:m2")
        t.onRows(base, "m2", pinned = true)
        assertFalse(t.onRows(base + row("tool:m2:x"), "m2", pinned = false))
    }

    @Test
    fun `a send that also adds the user bubble is left to the send path`() {
        val t = StreamGrowRevealTracker()
        t.onRows(history, "m2", pinned = true)
        assertFalse(t.onRows(history + row("user:m3", null) + row("header:m3", "m3"), "m3", pinned = true))
    }

    @Test
    fun `the typing row being replaced does not pin`() {
        val t = StreamGrowRevealTracker()
        t.onRows(history + row("header:m2") + row("typing:m2"), "m2", pinned = true)
        assertFalse(t.onRows(history + row("header:m2") + row("mdblock:m2:b:0"), "m2", pinned = true))
    }

    @Test
    fun `a burst of rows does not pin`() {
        val t = StreamGrowRevealTracker()
        val base = history + row("header:m2")
        t.onRows(base, "m2", pinned = true)
        val burst = (0..StreamGrowRevealTracker.MAX_NEW_ROWS).map { row("tool:m2:$it") }
        assertFalse(t.onRows(base + burst, "m2", pinned = true))
    }

    @Test
    fun `a row inserted above the bottom does not pin`() {
        val t = StreamGrowRevealTracker()
        val base = history + row("header:m2") + row("mdblock:m2:b:0")
        t.onRows(base, "m2", pinned = true)
        val inserted = history + row("header:m2") + row("thinking:m2:t") + row("mdblock:m2:b:0")
        assertFalse(t.onRows(inserted, "m2", pinned = true))
    }

    @Test
    fun `split ids map to the reply they belong to`() {
        assertEquals("m2", FlatChatItem.AssistantTyping("m2#1").growOwnerMessageId())
    }
}
