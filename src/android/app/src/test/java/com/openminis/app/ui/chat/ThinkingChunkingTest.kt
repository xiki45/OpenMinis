package com.openminis.app.ui.chat

import com.openminis.app.ui.chat.ThinkingChunking.Chunk
import com.openminis.app.ui.chat.ThinkingChunking.Pacing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [#467] The chunking behind the "Show full thinking" viewer. */
class ThinkingChunkingTest {

    private fun lines(n: Int, prefix: String = "line") = (1..n).joinToString("") { "$prefix $it\n" }

    /** Chunks cover the text exactly, in order, none empty. */
    private fun assertCovers(text: String, chunks: List<Chunk>) {
        assertEquals(text, chunks.indices.joinToString("") { ThinkingChunking.chunkText(text, chunks, it) })
        chunks.zipWithNext { a, b -> assertEquals(a.end, b.start) }
        assertTrue(chunks.all { it.length > 0 })
    }

    @Test
    fun emptyTextHasNoChunks() {
        val c = ThinkingChunking.chunks("")
        assertTrue(c.isEmpty())
        assertEquals(0, ThinkingChunking.initialCount(c))
        assertFalse(ThinkingChunking.hasMore(0, c.size))
        assertEquals(IntRange.EMPTY, ThinkingChunking.renderedRange(c, 0))
    }

    @Test
    fun exactlyOneChunk() {
        val text = lines(40)
        val c = ThinkingChunking.chunks(text)
        assertEquals(listOf(Chunk(0, text.length)), c)
        assertEquals(1, ThinkingChunking.initialCount(c))
        assertFalse(ThinkingChunking.hasMore(1, c.size))
        // One line more starts a second chunk.
        assertEquals(2, ThinkingChunking.chunks(lines(41)).size)
        // A text without a final newline is one chunk too.
        assertEquals(1, ThinkingChunking.chunks("short thought").size)
    }

    @Test
    fun manyChunksOfFortyLines() {
        val text = lines(1000)
        val c = ThinkingChunking.chunks(text)
        assertEquals(25, c.size)
        assertCovers(text, c)
        assertTrue(ThinkingChunking.chunkText(text, c, 1).startsWith("line 41\n"))
        assertTrue(c.all { ThinkingChunking.chunkText(text, c, c.indexOf(it)).count { ch -> ch == '\n' } == 40 })
    }

    @Test
    fun revealStartsSmallAndGrowsByBatches() {
        val text = lines(1000)
        val c = ThinkingChunking.chunks(text)
        val first = ThinkingChunking.initialCount(c)
        assertEquals(ThinkingChunking.INITIAL_CHUNKS, first)
        assertEquals(0 until c[first - 1].end, ThinkingChunking.renderedRange(c, first))
        var shown = first
        var batches = 0
        while (ThinkingChunking.hasMore(shown, c.size)) {
            shown = ThinkingChunking.nextCount(shown, c.size)
            batches++
        }
        assertEquals(c.size, shown)
        assertEquals(4, batches)
        assertEquals(0 until text.length, ThinkingChunking.renderedRange(c, shown))
    }

    @Test
    fun initialRevealStopsAtTheCharacterCap() {
        // 40 lines of 100 characters: 4 KB a chunk, so the 10 KB cap stops at 3.
        val text = (1..400).joinToString("") { "x".repeat(99) + "\n" }
        val c = ThinkingChunking.chunks(text)
        assertEquals(3, ThinkingChunking.initialCount(c))
    }

    @Test
    fun aVeryLongSingleLineIsCutIntoBoundedChunks() {
        val text = "a".repeat(50_000)
        val c = ThinkingChunking.chunks(text)
        assertCovers(text, c)
        assertTrue(c.all { it.length <= ThinkingChunking.CHUNK_MAX_CHARS })
        assertEquals(13, c.size)
        // A long line after short ones: the cut prefers the last newline.
        val mixed = "head\n" + "b".repeat(5000)
        val m = ThinkingChunking.chunks(mixed)
        assertEquals(Chunk(0, 5), m.first())
        assertCovers(mixed, m)
    }

    @Test
    fun emojiAndCjkAreNeverSplit() {
        // A ZWJ family emoji is 11 UTF-16 units; CJK characters are 1; a
        // surrogate-pair emoji is 2. With a small limit every cut would
        // otherwise land inside one of them.
        val family = "👨‍👩‍👧‍👦"
        val text = ("思考" + family + "😀").repeat(500)
        for (limit in listOf(3, 7, 10, 13, 4000)) {
            val c = ThinkingChunking.chunks(text, maxChars = limit)
            assertCovers(text, c)
            for (ch in c) {
                val piece = text.substring(ch.start, ch.end)
                assertFalse("chunk starts inside a pair at limit $limit", Character.isLowSurrogate(piece.first()))
                assertFalse("chunk ends inside a pair at limit $limit", Character.isHighSurrogate(piece.last()))
                assertFalse("chunk starts with a joiner at limit $limit", piece.first() == '‍')
            }
        }
        // A grapheme longer than the limit is kept whole in its own chunk.
        val c = ThinkingChunking.chunks(family + "x", maxChars = 3)
        assertEquals(family, ThinkingChunking.chunkText(family + "x", c, 0))
    }

    @Test
    fun copyIsTheWholeOriginal() {
        val text = lines(500, "思考 😀") + "a".repeat(9000) + "end"
        val c = ThinkingChunking.chunks(text)
        val shown = ThinkingChunking.initialCount(c)
        assertTrue(ThinkingChunking.renderedRange(c, shown).last < text.length - 1)
        assertEquals(text, ThinkingChunking.copyText(text))
    }

    @Test
    fun aSlowBatchSwitchesToOneChunkOnRequest() {
        val normal = Pacing()
        assertEquals(normal, ThinkingChunking.paceAfter(normal, 40))
        val slow = ThinkingChunking.paceAfter(normal, ThinkingChunking.SLOW_BATCH_MS)
        assertEquals(Pacing(batch = 1, auto = false), slow)
        assertEquals(6, ThinkingChunking.nextCount(5, 25, slow))
        // It stays slow after a fast frame.
        assertEquals(slow, ThinkingChunking.paceAfter(slow, 1))
    }
}
