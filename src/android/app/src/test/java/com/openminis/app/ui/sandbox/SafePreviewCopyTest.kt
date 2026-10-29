package com.openminis.app.ui.sandbox

import com.openminis.app.ProductionSources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [FIX-261025-13] Copying a whole large preview could crash past the binder limit. */
class SafePreviewCopyTest {
    @Test fun `both previews copy through the safe clipboard`() {
        val src = ProductionSources.read("ui/sandbox/FilePreviewScreen.kt")
        assertEquals("text and JSON previews", 2, Regex("SafeCopySelection \\{").findAll(src).count())
        assertFalse("no bare SelectionContainer around a preview", Regex("^\\s+SelectionContainer \\{\\n\\s+Text\\(", RegexOption.MULTILINE).containsMatchIn(src))
        assertTrue(src.contains("LocalClipboard provides safe"))
        assertTrue("under ~1 MB of UTF-16", SafeCopyClipboard.MAX_COPY_CHARS * 2 < 1_000_000)
    }
}
