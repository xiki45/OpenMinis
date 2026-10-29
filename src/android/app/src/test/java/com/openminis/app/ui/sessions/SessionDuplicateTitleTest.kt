package com.openminis.app.ui.sessions

import com.openminis.app.ProductionSources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** [ISS-038] Duplicated sessions get distinct, localized titles (port of iOS 03da252a1). */
class SessionDuplicateTitleTest {

    private val known = listOf("Copy", "副本")
    private fun make(src: String?, existing: List<String> = emptyList(), word: String = "Copy") =
        SessionDuplicateTitle.make(src, existing, word, known, "Untitled")

    @Test
    fun `copies are numbered, never stacked`() {
        assertEquals("Foo (Copy)", make("Foo"))
        assertEquals("Foo (Copy 2)", make("Foo", listOf("Foo", "Foo (Copy)")))
        assertEquals("Foo (Copy 3)", make("Foo (Copy)", listOf("Foo", "Foo (Copy)", "Foo (Copy 2)")))
        assertEquals("copying a copy uses the original base", "Foo (Copy 2)", make("Foo (Copy)", listOf("Foo (Copy)")))
    }

    @Test
    fun `the smallest free number is reused`() {
        assertEquals("Foo (Copy 2)", make("Foo", listOf("Foo (Copy)", "Foo (Copy 3)")))
    }

    @Test
    fun `localized word, and copies from another language are recognized`() {
        assertEquals("Foo (副本)", make("Foo", word = "副本"))
        assertEquals("Foo (副本 2)", make("Foo (Copy)", listOf("Foo (Copy)"), word = "副本"))
        assertEquals("full-width parentheses", "Foo (副本 2)", make("Foo（副本）", listOf("Foo（副本）"), word = "副本"))
    }

    @Test
    fun `text that is not a copy suffix stays part of the title`() {
        for (t in listOf("Foo (draft)", "Foo (Copy) notes", "Foo (Copy 2x)", "Foo (Copy 0)", "Foo (Copy 1)", "Foo (Copy 02)")) {
            assertNull(t, SessionDuplicateTitle.parse(t, known))
        }
        assertEquals("Foo (draft) (Copy)", make("Foo (draft)"))
        assertEquals("a bare suffix keeps itself as the base", "(Copy) (Copy)", make("(Copy)"))
        assertEquals("case-insensitive", "Foo" to 4, SessionDuplicateTitle.parse("Foo (copy 4)", known))
    }

    @Test
    fun `a blank title uses Untitled`() {
        assertEquals("Untitled (Copy)", make(null))
        assertEquals("Untitled (Copy 2)", make("  ", listOf("Untitled (Copy)")))
    }

    @Test
    fun `duplicateSession uses the rule and reserves the title`() {
        val src = ProductionSources.read("ui/sessions/SessionListViewModel.kt")
        val body = src.substringAfter("fun duplicateSession(id: String) {").substringBefore("fun allCopySuffixWords")
        assertTrue(body.contains("SessionDuplicateTitle.make("))
        assertTrue(body.contains("existingTitles = existingTitles + reservedDuplicateTitles,"))
        assertTrue(body.contains("reservedDuplicateTitles.add(title)"))
        assertFalse("no hard-coded English suffix", body.contains("} (Copy)"))
    }
}
