package com.openminis.app.ui.chat

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-copy-toast-l10n] Tapping inline code in a reply copied it and
 * showed a hard-coded English "Copied: …" toast even in a Chinese UI, while
 * copying a selection showed the localized "已复制：…". Copy feedback - the
 * toast and the copy buttons' TalkBack labels - must come from string
 * resources.
 *
 * Source guard only: the unit tests run without app resources, so the
 * localized toast text itself is not checked here.
 */
class CopyFeedbackLocalizedGuardTest {

    private val ui = File("src/main/java/com/openminis/app/ui")
    private val streaming = File(ui, "chat/StreamingMarkdownText.kt")

    /** Code with comment lines removed, so a quoted example in a comment does not count. */
    private fun code(f: File): String =
        f.readLines().filterNot { it.trimStart().let { t -> t.startsWith("//") || t.startsWith("*") || t.startsWith("/*") } }
            .joinToString("\n")

    @Test
    fun `tapping inline code toasts the localized selection_copied_toast with the same preview`() {
        val src = code(streaming)
        assertTrue("hard-coded \"Copied: \$…\" toast is back", !src.contains("\"Copied: \$"))
        val preview = "val preview = if (snippet.length > 40) snippet.take(37) + \"…\" else snippet"
        val at = src.indexOf(preview)
        assertTrue("inline-code preview computation changed or moved", at >= 0)
        val branch = src.substring(at, minOf(src.length, at + 400))
        assertTrue(
            "the inline-code tap branch must use R.string.selection_copied_toast: $branch",
            branch.contains("context.getString(R.string.selection_copied_toast, preview)"),
        )
    }

    @Test
    fun `the code block copy button has localized TalkBack labels`() {
        val src = code(streaming)
        assertTrue(!src.contains("\"Copy code\"") && !src.contains("\"Copied\""))
        assertTrue(src.contains("stringResource(R.string.image_copied_toast)"))
        assertTrue(src.contains("stringResource(R.string.copy_code_block)"))
    }

    @Test
    fun `no copy feedback in ui is a hard-coded English literal`() {
        // Exact copy-feedback literals. SessionDuplicateTitle matches the
        // English "Copy" suffix of old titles on purpose (logic, not UI text).
        val literal = Regex(""""(Copy|Copied|Copy code|Copied!)"|"Copied: |"Copied to """)
        val allowed = setOf("sessions/SessionDuplicateTitle.kt")
        val hits = ui.walkTopDown().filter { it.isFile && it.extension == "kt" }
            .filterNot { it.relativeTo(ui).path in allowed }
            .flatMap { f ->
                code(f).lines().filter { literal.containsMatchIn(it) }.map { "${f.relativeTo(ui).path}: ${it.trim()}" }
            }.toList()
        assertTrue("hard-coded copy feedback (use a string resource): $hits", hits.isEmpty())
    }
}
