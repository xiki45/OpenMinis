package com.openminis.app.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** A fast double tap on Add Provider's save makes one provider and pops once. */
class AddProviderSaveOnceTest {

    @Test
    fun `only the first claim wins`() {
        val gate = SaveOnce()
        assertTrue(gate.claim())
        assertFalse(gate.claim())
        assertFalse(gate.claim())
    }

    @Test
    fun `every save button of the screen claims before it adds`() {
        val src = File("src/main/java/com/openminis/app/ui/settings/AddProviderScreen.kt").readText()
        val adds = Regex("providerRepository\\.addInstance\\(").findAll(src).count()
        val claims = Regex("if \\(!saveOnce\\.claim\\(\\)\\) return@MinisButton").findAll(src).count()
        assertEquals("each addInstance sits behind a save claim", adds, claims)
    }
}
