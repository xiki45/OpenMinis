package com.openminis.app.ui.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-table-cell-katex-false-positive] An inline `$ … $` that paired
 * across a table's column bars is not a formula (port of iOS 5b1d72515,
 * `isTablePipeArtifact`; Windows `is_table_pipe_artifact`).
 *
 * Math is extracted before the table is parsed, so `| a | $x-1 | y$ |` paired
 * the two dollars and rendered "x-1 | y" as one italic formula spanning two
 * cells, taking the column bar with it.
 */
class TablePipeArtifactTest {

    private fun latexOf(md: String): List<String> =
        MarkdownParser.parseWithMath(md).mathSpans.map { it.latex }

    @Test fun `the Windows vector - a price pairing across the column bar`() {
        assertEquals(emptyList<String>(), latexOf("| Monthly | \$20|\$ **3** |"))
    }

    @Test fun `a bar with a space beside it is a column separator`() {
        // looksLikeMath accepts these (they hold - or +): the pipe rule rejects them.
        assertEquals(emptyList<String>(), latexOf("| a | \$x-1 | y\$ |"))
        assertEquals(emptyList<String>(), latexOf("| \$a+b |\$ c |"))
        assertEquals(emptyList<String>(), latexOf("| \$a+b| c\$ |"))
    }

    @Test fun `an odd number of bars is a stray separator`() {
        assertEquals(emptyList<String>(), latexOf("| range | \$n+1|\$ |"))
        assertEquals(emptyList<String>(), latexOf("\$|x|+|y\$"))
    }

    @Test fun `real absolute values and norms are still formulas`() {
        assertEquals(listOf("|x|"), latexOf("\$|x|\$"))
        assertEquals(listOf("|x-1|"), latexOf("\$|x-1|\$"))
        assertEquals(listOf("\\|v\\|"), latexOf("\$\\|v\\|\$"))
        assertEquals(listOf("\\frac{|a|}{|b|}"), latexOf("\$\\frac{|a|}{|b|}\$"))
        // Escaped norm bars are not counted: one bare pair plus two \| is even.
        assertEquals(listOf("|x|\\|y\\|"), latexOf("\$|x|\\|y\\|\$"))
    }

    @Test fun `known loss, same as iOS and Windows - a bar beside a space reads as a column`() {
        // Rule 1 cannot tell `|x| + 1` from `x | y`: both platforms accept
        // losing the spaced absolute value to keep tables intact.
        assertEquals(emptyList<String>(), latexOf("\$|x| + 1\$"))
    }

    @Test fun `formulas inside table cells still render`() {
        assertEquals(listOf("|x|"), latexOf("| abs | \$|x|\$ |"))
        assertEquals(listOf("x^2", "a+b"), latexOf("| \$x^2\$ | \$a+b\$ |"))
    }

    @Test fun `the table keeps both cells when the dollars are refused`() {
        val md = "| a | b |\n|---|---|\n| \$x-1 | y\$ |"
        val blocks = MarkdownParser.parseWithMath(md).blocks
        val table = blocks.single() as MarkdownParser.Block.Table
        assertTrue("row: ${table.rows}", table.rows.single().map { it.trim() } == listOf("\$x-1", "y\$"))
    }
}
