package com.openminis.app.tools

import com.openminis.app.data.repository.MemoryRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * [PAR-261022-03] GLOBAL.md is writable through file_edit on every platform,
 * so nothing the model reads may call it read-only: a tester's agent took the
 * old "read-only, user-maintained" wording literally and refused to edit it.
 * Same wording as iOS and Windows.
 */
class GlobalMemoryWritableWordingTest {

    @Test
    fun injectedGlobalHeaderSaysWritable() {
        val dir = Files.createTempDirectory("global-mem").toFile()
        try {
            File(dir, "GLOBAL.md").writeText("metric units")
            val fragment = MemoryRepository(dir).loadGlobalMemoryFragment()!!
            assertTrue(
                fragment.startsWith(
                    "Global memory (GLOBAL.md — user-curated and writable; you may update it when the user asks)."
                )
            )
            assertFalse(fragment.contains("read-only"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun memoryWriteDescriptionsSayWritable() {
        val tail = "GLOBAL.md is writable but not through this tool; update it when the user asks."
        val json = MemoryTools.memoryWriteToolDefinition().getString("description")
        assertTrue(json.endsWith(tail))
        assertFalse(json.contains("read-only"))

        val def = AgentTools.makeAgentTools().single { it.name == "memory_write" }
        assertEquals(json, def.description)
    }
}
