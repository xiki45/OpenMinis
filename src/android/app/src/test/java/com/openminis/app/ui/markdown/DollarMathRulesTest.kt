package com.openminis.app.ui.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * [T-math-dollar-rules] Amounts and shell variables are not formulas
 * (iOS 216160978 + 23635b48e, Windows b05bb891e + 18312b750).
 *
 * Before the port, "$5-$10" lost both dollars and rendered the italic formula
 * "5-", and PATH=$HOME/bin:$PATH lost its dollars the same way: the closer of
 * an inline formula was simply the first `$` not after a blank.
 *
 * [VECTORS] is the list iOS (MathExtractorDollarRulesTests.swift) and Windows
 * (`DOLLAR_VECTORS` in minis-markdown/src/math.rs) run: source, then the LaTeX
 * of each formula found, in order. The last test reads the Windows copy and
 * fails if the two lists drift apart.
 */
class DollarMathRulesTest {

    private fun latexOf(md: String): List<String> =
        MarkdownParser.parseWithMath(md).mathSpans.map { it.latex }

    @Test
    fun `amounts and shell variables are not math`() {
        val failures = VECTORS.mapNotNull { (source, expected) ->
            val found = latexOf(source)
            if (found == expected) null else "$source\n    expected $expected\n    found    $found"
        }
        assertTrue("${failures.size} of ${VECTORS.size} vectors failed:\n" + failures.joinToString("\n"),
            failures.isEmpty())
    }

    @Test
    fun `an amount keeps its dollars in the rendered text`() {
        // The visible symptom: both dollars vanished and "5-" went italic.
        val blocks = MarkdownParser.parseWithMath("It costs \$5-\$10 per month.").blocks
        assertEquals(listOf(MarkdownParser.Block.Paragraph("It costs \$5-\$10 per month.")), blocks)
        val shell = MarkdownParser.parseWithMath("PATH=\$HOME/bin:\$PATH").blocks
        assertEquals(listOf(MarkdownParser.Block.Paragraph("PATH=\$HOME/bin:\$PATH")), shell)
    }

    @Test
    fun `existing math is unaffected`() {
        // Code spans still hide dollars, escaped dollars stay literal, display
        // math and the single-letter variables of issue #117 still extract.
        assertEquals(emptyList<String>(), latexOf("`\$HOME` and `\$x\$`"))
        assertEquals(emptyList<String>(), latexOf("\\\$5 and \\\$x\\\$"))
        assertEquals(listOf("x", "n"), latexOf("Let \$x\$ and \$n\$ be"))
        assertEquals(listOf("E = mc^2"), latexOf("\$\$E = mc^2\$\$"))
    }

    @Test
    fun `the vectors match the Windows copy`() {
        // Read-only: src/windows is another platform's tree. Skip when this
        // checkout has no Windows sources (e.g. an Android-only CI image).
        val mathRs = listOf(
            File("../windows/crates/minis-markdown/src/math.rs"),     // CWD = src/android
            File("../../windows/crates/minis-markdown/src/math.rs"),  // CWD = src/android/app
        ).firstOrNull { it.isFile }
        assumeTrue("Windows minis-markdown sources not found", mathRs != null)
        val windows = parseRustDollarVectors(mathRs!!.readText())
        assertTrue("could not read DOLLAR_VECTORS from ${mathRs.path}", windows.isNotEmpty())
        assertEquals("DOLLAR_VECTORS drifted from the Windows list", windows, VECTORS)
    }

    /** `("src", &["a", "b"]),` entries of the `DOLLAR_VECTORS` const. */
    private fun parseRustDollarVectors(source: String): List<Pair<String, List<String>>> {
        val start = source.indexOf("const DOLLAR_VECTORS")
        if (start < 0) return emptyList()
        val body = source.substring(start, source.indexOf("];", start))
        val str = "\"((?:[^\"\\\\]|\\\\.)*)\""
        val entry = Regex("""\(\s*$str\s*,\s*&\[((?:\s*$str\s*,?)*)\s*\]\s*\)""")
        val item = Regex(str)
        fun unescape(s: String) = s.replace(Regex("""\\(.)""")) { it.groupValues[1] }
        return entry.findAll(body).map { m ->
            unescape(m.groupValues[1]) to item.findAll(m.groupValues[2]).map { unescape(it.groupValues[1]) }.toList()
        }.toList()
    }

    companion object {
        val VECTORS: List<Pair<String, List<String>>> = listOf(
            // Amounts.
            "It costs \$5." to listOf(),
            "From \$10.99 up" to listOf(),
            "\$5-\$10" to listOf(),
            "It costs \$5-\$10 per month, or \$50-\$100 a year." to listOf(),
            "\$5+\$3=\$8" to listOf(),
            "(\$5-10)/(\$3)" to listOf(),
            "Pay \$5-\$10 and solve \$x+1\$" to listOf("x+1"),
            "\$x\$ costs \$5" to listOf("x"),
            // A formula may still start with a digit.
            "\$2x+1=5\$" to listOf("2x+1=5"),
            "\$3 \\times 4 = 12\$" to listOf("3 \\times 4 = 12"),
            // Shell variables.
            "PATH=\$HOME/bin:\$PATH" to listOf(),
            "export PATH=\$PATH:\$HOME/bin" to listOf(),
            "echo \${HOME}/bin and \${USER}" to listOf(),
            "\${a}\${b}" to listOf(),
            "use \$HOME or \$USER" to listOf(),
            "cd \$HOME && echo \$USER" to listOf(),
            "echo \$PATH and \$x\$" to listOf("x"),
            "export A=\$HOME; echo \$x+y\$" to listOf("x+y"),
            "see \$HOME, then \$x^2\$" to listOf("x^2"),
            "run \$CMD \$ARGS" to listOf(),
            // Upper-case letters in formulas.
            "\$N\$" to listOf("N"),
            "\$AB\$" to listOf("AB"),
            "\$ABC\$ is a triangle" to listOf("ABC"),
            "\$PV = nRT\$" to listOf("PV = nRT"),
            "\$AB \\parallel CD\$" to listOf("AB \\parallel CD"),
            "\$NaCl\$" to listOf("NaCl"),
            "\$F = ma\$" to listOf("F = ma"),
            "\${}^{14}C\$" to listOf("{}^{14}C"),
            "\$E=mc^2\$ and \$a_1\$" to listOf("E=mc^2", "a_1"),
            // The other spellings are untouched.
            "\\(HOME/x\\) and \$\$PATH = 1\$\$" to listOf("HOME/x", "PATH = 1"),
            // A subscript is not part of a variable's name, and a braced group
            // with a script is a formula's (iOS 23635b48e / Windows 18312b750).
            "\$X_1, X_2, \\ldots, X_n\$" to listOf("X_1, X_2, \\ldots, X_n"),
            "\$H_0: \\mu = 0\$" to listOf("H_0: \\mu = 0"),
            "\$P_1 V_1 = P_2 V_2\$" to listOf("P_1 V_1 = P_2 V_2"),
            "\${a}^{2}+{b}^{2}={c}^{2}\$" to listOf("{a}^{2}+{b}^{2}={c}^{2}"),
            "\${x}^2\$" to listOf("{x}^2"),
            "\$x_1, x_2\$" to listOf("x_1, x_2"),
            "\$AB_1, CD_2\$" to listOf("AB_1, CD_2"),
            "\$A_n\$ and \$B_{n+1}\$" to listOf("A_n", "B_{n+1}"),
            "\${n}_k\$ items" to listOf("{n}_k"),
            // ...while these are still variables.
            "echo \$MY_VAR and \$XDG_CONFIG_HOME/minis" to listOf(),
            "cp \$SRC_DIR/\$FILE_1 \$DST" to listOf(),
            "\${HOME}_backup and \${USER}" to listOf(),
        )
    }
}
