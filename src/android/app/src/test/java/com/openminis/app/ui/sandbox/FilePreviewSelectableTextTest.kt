package com.openminis.app.ui.sandbox

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Previewed text and JSON can be long-pressed and copied. Composables need a
 * UI host this JVM test does not have, so pin it as a source fact.
 */
class FilePreviewSelectableTextTest {

    private val src = File("src/main/java/com/openminis/app/ui/sandbox/FilePreviewScreen.kt").readText()

    private fun body(fn: String): String {
        val start = src.indexOf("fun $fn(")
        assertTrue("$fn exists", start >= 0)
        val next = src.indexOf("\n// ====", start)
        return src.substring(start, if (next > 0) next else src.length)
    }

    @Test
    fun `the text preview is selectable`() {
        // [FIX-261025-13] Through SafeCopySelection (a SelectionContainer with a safe clipboard).
        assertTrue(body("TextPreview").contains("SafeCopySelection {"))
    }

    @Test
    fun `the json preview is selectable`() {
        assertTrue(body("JsonPreview").contains("SafeCopySelection {"))
    }
}
