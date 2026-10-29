package com.openminis.app.config.readable

import com.openminis.app.ProductionSources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-config-readable] The readable text is for the user only (confirmation
 * dialog + Logs → Config Changes) and must never reach the model. Source
 * guards on ConfigBridge, port of iOS ConfigReadableTests [3]: the CLI's audit
 * serialization is an unchanged allow-list, no response carries the text, and
 * the description is rendered before the dialog and before any removal.
 */
class ConfigReadableLeakTest {

    private val bridge = ProductionSources.read("config/ConfigBridge.kt")

    private fun between(s: String, a: String, b: String): String {
        val i = s.indexOf(a)
        require(i >= 0) { "missing $a" }
        val j = s.indexOf(b, i + a.length)
        require(j >= 0) { "missing $b" }
        return s.substring(i + a.length, j)
    }

    @Test
    fun `the CLI audit dict is an unchanged allow-list without readable`() {
        val body = between(bridge, "private fun auditEntryDict(e: ConfigAuditEntry): JSONObject = JSONObject().apply {", "\n    }\n")
        val keys = Regex("""put\("([a-z_]+)"""").findAll(body).map { it.groupValues[1] }.toSet()
        assertEquals(
            setOf("id", "at", "actor", "scope", "key", "old", "new", "status", "session_id", "confirmed_at", "revert_of", "caption"),
            keys,
        )
        assertFalse(body.contains("readable"))
    }

    @Test
    fun `no bridge response carries the readable text`() {
        val batch = between(bridge, "suspend fun performWriteBatch(", "private data class Resolved(")
        // Every `put("…", …)` in the write path: none may name or pass it.
        Regex("""put\("[a-z_]+",[^\n]*""").findAll(batch).forEach { m ->
            assertFalse(m.value, m.value.contains("readable", ignoreCase = true) || m.value.contains("displayText"))
        }
        assertFalse(Regex("""put\("(readable|summary|readable_text)"""").containsMatchIn(bridge))
    }

    @Test
    fun `every audit row in the write path carries its description`() {
        val batch = between(bridge, "suspend fun performWriteBatch(", "private data class Resolved(")
        val rows = batch.split("ConfigAuditEntry(").drop(1).map { it.substringBefore("\n                        )").substringBefore("\n                            )") }
        assertEquals(4, rows.size)
        rows.forEach { assertTrue(it, it.contains("readable = ")) }
    }

    @Test
    fun `descriptions are rendered before the dialog and before any removal`() {
        val batch = between(bridge, "suspend fun performWriteBatch(", "private data class Resolved(")
        val confirm = batch.indexOf("ConfigConfirmationGate.requestConfirmation")
        for (call in listOf("forCollectionAdd(", "forCollectionRemove(", "forFieldWrite(")) {
            val at = batch.indexOf(call)
            assertTrue("$call rendered", at in 0 until confirm)
        }
        assertTrue(batch.indexOf("forCollectionRemove(") < batch.indexOf("r.collection.remove(entryId)"))
        // Both sides go through the masking wrapper.
        assertTrue(batch.contains("old = RedactedConfigValue.field(fieldLeaf, oldValue)"))
        assertTrue(batch.contains("new = RedactedConfigValue.field(fieldLeaf, newValue)"))
        assertTrue(batch.contains("RedactedConfigValue.payload(parsedValue)"))
    }
}
