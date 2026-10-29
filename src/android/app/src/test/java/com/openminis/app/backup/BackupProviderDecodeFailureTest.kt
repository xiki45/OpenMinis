package com.openminis.app.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * [B01 cross-platform read] When a backup's provider_config.json does not
 * decode, the restore says why. The B01 cross-device test met an unreadable
 * file whose report said only "unreadable 1", with no reason anywhere, so the
 * cause (a missing required key) had to be reconstructed offline.
 */
class BackupProviderDecodeFailureTest {

    private val goodInstance =
        """{"id":"ok","label":"B01X-OK","providerType":"openAI","credentialType":"apiKey","createdAt":"2026-10-16T04:31:29Z"}"""

    @Test
    fun `a dropped instance's reason names the instance and the key`() {
        // `label` is required and has no default.
        val parsed = BackupImporter.parseProviderConfigLeniently(
            """{"instances":[{"id":"bad","providerType":"openAI","credentialType":"apiKey","createdAt":"2026-10-16T04:31:29Z"},$goodInstance]}""",
        )
        assertEquals(1, parsed.droppedInstances)
        assertEquals(1, parsed.dropReasons.size)
        val reason = parsed.dropReasons.single()
        assertTrue(reason, "bad" in reason && "label" in reason)

        val warnings = BackupImporter.providerConfigWarnings(parsed, null)
        assertEquals(1, warnings.size)
        assertTrue(warnings.single(), "bad" in warnings.single() && "label" in warnings.single())
    }

    @Test
    fun `an unreadable file's warning carries the error type, key and path`() {
        // A group without its required `name` fails the whole document.
        val failure = try {
            BackupImporter.parseProviderConfigLeniently(
                """{"instances":[$goodInstance],"modelGroups":[{"id":"g","memberEntryIds":[]}]}""",
            )
            fail("expected the document to fail")
            return
        } catch (e: Exception) {
            e
        }
        val warnings = BackupImporter.providerConfigWarnings(null, failure)
        assertEquals(1, warnings.size)
        val line = warnings.single()
        assertTrue(line, "provider_config.json" in line)
        // The original error type, not a wrapper's.
        assertTrue(line, (failure.cause ?: failure)::class.simpleName!! in line)
        assertTrue(line, "name" in line)
        assertTrue(line, "modelGroups" in line)
    }

    @Test
    fun `a clean file has no warnings`() {
        val parsed = BackupImporter.parseProviderConfigLeniently("""{"instances":[$goodInstance]}""")
        assertTrue(BackupImporter.providerConfigWarnings(parsed, null).isEmpty())
    }
}
