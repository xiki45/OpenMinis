package com.openminis.app.ui.markdown

import com.openminis.app.ui.markdown.MarkdownParser.Block
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [FIX-261022-01, OpenMinis#426] The block parser's patterns are compiled once,
 * not per line. A Regex(...) built inside the per-line loop compiled a fresh ICU
 * pattern each time; on a long session the native mappings piled up until
 * vm.max_map_count was hit and Scudo aborted the process.
 */
class MarkdownParserRegexHoistTest {

    @Test
    fun `every block kind still parses after the patterns were hoisted`() {
        val md = """
            # Title
            ---
            - one
              continued
            - two
            3. three
            4. four
            | a | b |
            |:--|--:|
            | 1 | 2 |

            plain text
            more text
            $$
            x^2
            $$
        """.trimIndent()

        val blocks = MarkdownParser.parseWithMath(md).blocks

        assertEquals(Block.Heading(1, "Title"), blocks[0])
        assertEquals(Block.ThematicBreak, blocks[1])
        val bullets = blocks[2] as Block.BulletList
        assertEquals(listOf("one\ncontinued", "two"), bullets.items.map { it.content })
        val numbered = blocks[3] as Block.NumberedList
        assertEquals(3, numbered.startNumber)
        assertEquals(listOf("three", "four"), numbered.items.map { it.content })
        val table = blocks[4] as Block.Table
        assertEquals(listOf("a", "b"), table.headers)
        assertEquals(listOf(listOf("1", "2")), table.rows)
        assertEquals(Block.Paragraph("plain text\nmore text"), blocks[5])
        assertTrue(blocks.drop(6).any { it is Block.MathBlock && it.latex.trim() == "x^2" })
    }

    @Test
    fun `a paragraph ends at a list item or a rule`() {
        val blocks = MarkdownParser.parse("text\n- item\ntext\n12. item\ntext\n***")
        assertEquals(
            listOf(
                Block.Paragraph::class, Block.BulletList::class,
                Block.Paragraph::class, Block.NumberedList::class,
                Block.Paragraph::class, Block.ThematicBreak::class,
            ),
            blocks.map { it::class },
        )
    }

    @Test
    fun `no pattern is compiled inside a function body`() {
        val src = File("src/main/java/com/openminis/app/ui/markdown/MarkdownParser.kt").readLines()
        val inline = src.withIndex().filter { (_, line) ->
            line.contains("Regex(") && !line.trimStart().startsWith("private val ") &&
                !line.trimStart().startsWith("//")
        }
        assertTrue(
            "Regex(...) outside a property: " + inline.joinToString { "${it.index + 1}: ${it.value.trim()}" },
            inline.isEmpty(),
        )
    }
}
