package com.openminis.app.ui.chat.share

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID
import com.openminis.app.ui.chat.share.ChatShareCell.Kind as k

/**
 * [T-multi-select-share] Port of iOS MinisTests/Standalone/ChatShareModelTests.swift:
 * unit grouping, cells, round selection, select below, render groups, table
 * chunking, adaptive-table geometry, PDF pagination, the height estimate and the
 * long-image budget.
 */
class ChatShareModelTest {

    private fun src(role: ChatShareUnitSource.Role, callback: Boolean = false, hidden: Boolean = false) =
        ChatShareUnitSource(UUID.randomUUID().toString(), role, callback, hidden)

    private val user = ChatShareUnitSource.Role.USER
    private val asst = ChatShareUnitSource.Role.ASSISTANT
    private val other = ChatShareUnitSource.Role.OTHER
    private fun block(kind: ChatShareBlockSource.Kind, empty: Boolean = false, id: String = UUID.randomUUID().toString()) =
        ChatShareBlockSource(id, kind, empty)

    @Test fun `grouping`() {
        val u1 = src(user); val a1 = src(asst); val a2 = src(asst); val cb = src(user, callback = true)
        val a3 = src(asst); val div = src(other); val a4 = src(asst); val q = src(user, hidden = true)
        val bridge = src(asst, hidden = true); val a5 = src(asst)
        val units = ChatShareUnits.group(listOf(u1, a1, a2, cb, a3, div, a4, bridge, a5, q))
        assertEquals(
            listOf(ChatShareUnit.Kind.USER, ChatShareUnit.Kind.REPLY, ChatShareUnit.Kind.CALLBACK,
                ChatShareUnit.Kind.REPLY, ChatShareUnit.Kind.REPLY),
            units.map { it.kind },
        )
        assertEquals("a reply spans consecutive assistant messages", listOf(a1.id, a2.id), units[1].messageIds)
        assertTrue("a callback card ends the reply and is its own unit",
            units[2].messageIds == listOf(cb.id) && units[3].messageIds == listOf(a3.id))
        assertFalse("a divider ends a reply and is not selectable", units.flatMap { it.messageIds }.contains(div.id))
        assertEquals("a hidden message does not split the reply around it", listOf(a4.id, a5.id), units[4].messageIds)
        assertFalse("queued messages are not selectable", units.flatMap { it.messageIds }.contains(q.id))
        assertEquals("lookup by any message of the unit", a1.id, ChatShareUnits.unitContaining(a2.id, units)?.id)
        assertTrue("empty chat has no units", ChatShareUnits.group(emptyList()).isEmpty())
    }

    @Test fun `cells, round selection, select below, render groups`() {
        val u = src(user); val a1 = src(asst); val a2 = src(asst); val cb = src(user, callback = true)
        val units = ChatShareUnits.group(listOf(u, a1, a2, cb))
        val info = "info"; val empty = "empty"
        val blocks = mapOf(
            a1.id to listOf(block(ChatShareBlockSource.Kind.TEXT), block(ChatShareBlockSource.Kind.TOOL),
                block(ChatShareBlockSource.Kind.HIDDEN, id = info), block(ChatShareBlockSource.Kind.TEXT, empty = true, id = empty)),
            a2.id to listOf(block(ChatShareBlockSource.Kind.THINKING), block(ChatShareBlockSource.Kind.TEXT)),
        )
        val cells = ChatShareCells.cells(units, blocks, setOf(a2.id))
        assertEquals("one row per piece, in order",
            listOf(k.USER, k.TEXT, k.TOOL, k.THINKING, k.TEXT, k.NOTE, k.CALLBACK), cells.map { it.kind })
        assertFalse("info and empty blocks are not rows", cells.any { it.blockId == info || it.blockId == empty })
        assertEquals("a reply's cells share its unit", setOf(a1.id), cells.subList(1, 6).map { it.unitId }.toSet())
        assertEquals("ids are unique", cells.size, cells.map { it.id }.toSet().size)

        val round = ChatShareCells.roundSelection(a1.id, cells)
        assertEquals("long-pressing the reply selects everything in the round", cells.map { it.id }.toSet(), round)
        assertEquals("long-pressing the user message selects the same round", round, ChatShareCells.roundSelection(u.id, cells))
        assertTrue("none without a message", ChatShareCells.roundSelection(null, cells).isEmpty())

        val u2 = src(user); val a3 = src(asst)
        val units2 = ChatShareUnits.group(listOf(u, a1, u2, a3))
        val blocks2 = mapOf(
            a1.id to listOf(block(ChatShareBlockSource.Kind.TEXT)),
            a3.id to listOf(block(ChatShareBlockSource.Kind.TEXT)),
        )
        val cells2 = ChatShareCells.cells(units2, blocks2, emptySet())
        assertEquals("stops at the next user message",
            setOf(cells2[0].id, cells2[1].id), ChatShareCells.roundSelection(a1.id, cells2))
        assertEquals("a reply before any user message starts its own round",
            setOf(cells2[1].id), ChatShareCells.roundSelection(units2[1].id, listOf(cells2[1])))

        val below = ChatShareCells.setBelow(cells[3].id, cells, setOf(cells[0].id), on = true)
        assertEquals("select below: that row and everything after, earlier picks kept",
            (listOf(cells[0].id) + cells.subList(3, cells.size).map { it.id }).toSet(), below)
        assertEquals("deselect below",
            setOf(cells[0].id, cells[3].id), ChatShareCells.setBelow(cells[4].id, cells, below, on = false))

        val groups = ChatShareCells.renderGroups(cells, setOf(cells[0].id, cells[1].id, cells[4].id, cells[6].id))
        assertEquals("selected pieces of one reply render together, skipping unselected tool calls",
            listOf(listOf(k.USER), listOf(k.TEXT, k.TEXT), listOf(k.CALLBACK)), groups.map { g -> g.map { it.kind } })
    }

    @Test fun `chunking`() {
        val md = """
            Intro line.

            | Name | Count |
            | --- | ---: |
            | a | 1 |
            | b | 2 |

            Between.
            ```
            | not | a table |
            | --- | --- |
            ```
            Tail
        """.trimIndent()
        val chunks = ChatShareMarkdownChunker.split(md)
        assertEquals("prose / table / prose", listOf(false, true, false), chunks.map { it.isTable })
        assertEquals("table keeps header, separator and rows", 4, chunks[1].text.split("\n").count { it.isNotEmpty() })
        assertTrue("a table chunk ends with a newline", chunks[1].text.endsWith("|\n"))
        assertTrue("a table inside a code fence stays prose", chunks[2].text.contains("| not | a table |"))
        assertEquals("no table, one chunk", listOf(false), ChatShareMarkdownChunker.split("just text\nmore").map { it.isTable })
        assertTrue("separator forms",
            ChatShareMarkdownChunker.isSeparator("|:-:|--:|") && ChatShareMarkdownChunker.isSeparator("--- | ---") &&
                !ChatShareMarkdownChunker.isSeparator("| a | b |") && !ChatShareMarkdownChunker.isSeparator("---x|---"))
        assertEquals("leading table", listOf(true), ChatShareMarkdownChunker.split("|a|b|\n|-|-|\n|1|2|").map { it.isTable })
    }

    @Test fun `geometry for adaptive tables`() {
        assertTrue("table width = natural + 2, at least the reading width",
            ChatShareGeometry.tableWidth(900f, 382f, 414f) == 902f && ChatShareGeometry.tableWidth(200f, 382f, 414f) == 382f)
        assertTrue("phone pages cap at 1000dp (row = table + padding)",
            ChatShareGeometry.tableWidth(9000f, 382f, 414f) == 1000f - ChatShareGeometry.ROW_PADDING &&
                ChatShareGeometry.canvasWidth(414f, listOf(934f, 5000f)) == 1000f)
        assertTrue("tablet pages get a proportionally wider cap",
            ChatShareGeometry.maxCanvasWidth(1024f) > 1024f && ChatShareGeometry.maxCanvasWidth(1024f) <= 1500f)
        assertEquals("no tables -> reading width", 414f, ChatShareGeometry.canvasWidth(414f, emptyList()))
    }

    @Test fun `pagination`() {
        val S = ChatSharePagination
        fun slice(page: Int, unitY: Float, h: Float, pageY: Float) = ChatSharePagination.Slice(page, unitY, h, pageY)
        val c = ChatSharePagination.Cursor()
        assertEquals("first unit at top", listOf(slice(0, 0f, 300f, 0f)), S.place(300f, emptyList(), 1000f, 20f, c))
        assertEquals(300f, c.y)
        assertEquals("next unit after the gap", listOf(slice(0, 0f, 600f, 320f)), S.place(600f, emptyList(), 1000f, 20f, c))
        assertEquals(920f, c.y)
        assertEquals("a unit that does not fit moves to a new page whole",
            listOf(slice(1, 0f, 500f, 0f)), S.place(500f, emptyList(), 1000f, 20f, c))

        val t = ChatSharePagination.Cursor()
        val tall = S.place(2500f, listOf(400f, 950f, 1200f, 1890f, 2300f), 1000f, 20f, t)
        assertEquals("tall unit cut at the lowest break in each page", listOf(0f, 950f, 1890f), tall.map { it.unitY })
        assertEquals(listOf(950f, 940f, 610f), tall.map { it.height })
        assertEquals(listOf(0, 1, 2), tall.map { it.page })
        assertEquals("cursor after a split is on the last page", ChatSharePagination.Cursor(2, 610f), t)

        val hard = S.place(2100f, listOf(100f), 1000f, 0f, ChatSharePagination.Cursor())
        assertEquals("no usable break -> hard cut at the page edge", listOf(1000f, 1000f, 100f), hard.map { it.height })

        val rest = S.place(1800f, listOf(260f, 1100f, 1700f), 1000f, 0f, ChatSharePagination.Cursor(0, 700f))
        assertEquals("tall unit starts on the current page when a break fits the remainder",
            slice(0, 0f, 260f, 700f), rest.first())
        val fresh = S.place(1800f, listOf(1000f), 1000f, 0f, ChatSharePagination.Cursor(0, 700f))
        assertEquals("without a break in the remainder, a tall unit starts on a new page",
            slice(1, 0f, 1000f, 0f), fresh.first())
        val split = S.place(900f, listOf(300f, 600f, 700f), 1000f, 0f, ChatSharePagination.Cursor(0, 250f))
        assertTrue("with most of the page left, a unit that fits a page is split at a break, not moved",
            split.first() == slice(0, 0f, 700f, 250f) && split.size == 2 && split.sumOf { it.height.toDouble() } == 900.0)
        val moved = S.place(900f, listOf(100f, 150f), 1000f, 0f, ChatSharePagination.Cursor(0, 800f))
        assertEquals("with little of the page left, it still moves whole", listOf(slice(1, 0f, 900f, 0f)), moved)
        assertTrue("slices cover the unit exactly once",
            tall.sumOf { it.height.toDouble() } == 2500.0 && rest.sumOf { it.height.toDouble() } == 1800.0)
    }

    @Test fun `height estimate`() {
        val short = listOf(ChatShareEstimateInput(ChatShareCell.Kind.USER, "你好"), ChatShareEstimateInput(ChatShareCell.Kind.TEXT, "好的。"))
        val shortPts = ChatShareHeightEstimate.points(short, 414f)
        assertTrue("a short exchange is a few hundred dp", shortPts > 100f && shortPts < 400f)
        assertFalse("a short exchange fits one image", ChatShareHeightEstimate.tooTallForImage(shortPts))
        val para = "这是一段很长的中文回复内容。".repeat(40)
        val long = List(60) { ChatShareEstimateInput(ChatShareCell.Kind.TEXT, para) }
        val longPts = ChatShareHeightEstimate.points(long, 414f)
        assertTrue("60 long CJK paragraphs pass 50,000 px at 3x", ChatShareHeightEstimate.tooTallForImage(longPts))
        assertTrue("the tablet width wraps less", ChatShareHeightEstimate.points(long, 1024f) < longPts)
        val table = (listOf("| a | b |", "|---|---|") + List(10) { "| x | y |" }).joinToString("\n")
        assertEquals("a table counts its rows (11 incl. header), not its separator",
            11 * 44f, ChatShareHeightEstimate.height(ChatShareEstimateInput(ChatShareCell.Kind.TEXT, table), 382f))
        assertEquals("tools are fixed rows", 36f,
            ChatShareHeightEstimate.height(ChatShareEstimateInput(ChatShareCell.Kind.TOOL, "x".repeat(5000)), 382f))
    }

    @Test fun `image budget`() {
        assertEquals("normal image at 3x", 3f, ChatShareImageBudget.scale(470f, 10_000f))
        val s = ChatShareImageBudget.scale(470f, 20_000f)
        assertNotNull(s)
        assertTrue("long image steps down but stays >= 1x", s!! < 3f && s >= 1f)
        assertNull("beyond 1x -> null (share as PDF)", ChatShareImageBudget.scale(470f, 200_000f))
        assertTrue("never taller than 50,000 px",
            (ChatShareImageBudget.scale(400f, 30_000f)?.let { it * 30_000f <= 50_000f } ?: true) &&
                ChatShareImageBudget.scale(400f, 60_000f) == null)
    }

    @Test fun `spacing follows the chat list`() {
        val e = ChatShareSpacing.Edge.entries.associateBy { it.name }
        val bubble = e.getValue("BUBBLE"); val card = e.getValue("CARD"); val reply = e.getValue("REPLY")
        assertEquals("a scheduled fire is a card", card, ChatShareSpacing.edge(k.USER, isScheduledCard = true))
        assertEquals("a typed message is a bubble", bubble, ChatShareSpacing.edge(k.USER, isScheduledCard = false))
        assertEquals("a callback is a card", card, ChatShareSpacing.edge(k.CALLBACK, isScheduledCard = false))
        for (kind in listOf(k.TEXT, k.TOOL, k.THINKING, k.NOTE)) assertEquals(reply, ChatShareSpacing.edge(kind, false))
        val row = ChatShareSpacing.ROW_GAP
        assertEquals("the list's spacedBy(2.dp)", 2f, row)
        assertEquals("card -> its reply (no header), or reply -> card without usage", row, ChatShareSpacing.gapBetween(false, false))
        assertEquals("finished reply -> next card / question: + the hidden usage row", 2 * row, ChatShareSpacing.gapBetween(true, false))
        // Where the chat draws the assistant header the export stands in for it:
        // bubble bottom 4 + 2 + 16 = 22, the question/answer gap from before.
        assertEquals("question -> answer", 18f, ChatShareSpacing.gapBetween(false, true))
        assertEquals("finished answer -> answer (question left out)", 20f, ChatShareSpacing.gapBetween(true, true))
        assertTrue(ChatShareSpacing.followsUserRow(bubble) && ChatShareSpacing.followsUserRow(card))
        assertFalse(ChatShareSpacing.followsUserRow(reply) || ChatShareSpacing.followsUserRow(null))
    }

    @Test fun `header rule is the chat's`() {
        val s = ChatShareSpacing.Speaker.entries.associateBy { it.name }
        val u = s.getValue("USER"); val a = s.getValue("ASSISTANT"); val x = s.getValue("SKIPPED")
        assertTrue("first message", ChatShareSpacing.headerAbove(listOf(a), 0))
        assertTrue("answer to a question", ChatShareSpacing.headerAbove(listOf(u, a), 1))
        // The field report: reply, fire card, reply, fire card, reply - no header after a card.
        assertFalse("reply to a fire after a reply", ChatShareSpacing.headerAbove(listOf(u, a, x, a, x, a), 3))
        assertFalse(ChatShareSpacing.headerAbove(listOf(u, a, x, a, x, a), 5))
        assertTrue("a chat that starts with a fire", ChatShareSpacing.headerAbove(listOf(x, a), 1))
        assertTrue("a fire after a question", ChatShareSpacing.headerAbove(listOf(u, x, a), 2))
    }

    @Test fun `pages never end inside a card`() {
        val breaks = listOf(100f, 130f, 150f, 170f, 200f)
        // A card from 120 to 170: its text line at 150 is not a break; its edges are.
        assertEquals(listOf(100f, 170f, 200f), ChatSharePagination.withoutCutsThrough(breaks, listOf(120f to 170f)))
        assertEquals(breaks, ChatSharePagination.withoutCutsThrough(breaks, emptyList()))
        // With no clean break left in the card's page, the card moves whole to the next page.
        // 276 of 678 left: enough that the part would be split, but the card's lines are no breaks.
        val cursor = ChatSharePagination.Cursor(page = 0, y = 400f)
        val slices = ChatSharePagination.place(
            height = 300f, breaks = ChatSharePagination.withoutCutsThrough(listOf(100f, 200f), listOf(0f to 300f)),
            pageHeight = 678f, gap = 2f, cursor = cursor,
        )
        assertEquals(1, slices.size)
        assertEquals(1, slices[0].page)
        assertEquals(0f, slices[0].unitY)
        // Without the filter the same part would have been cut through the card.
        val cut = ChatSharePagination.place(300f, listOf(100f, 200f), 678f, 2f, ChatSharePagination.Cursor(page = 0, y = 400f))
        assertTrue(cut.size > 1)
    }
}
