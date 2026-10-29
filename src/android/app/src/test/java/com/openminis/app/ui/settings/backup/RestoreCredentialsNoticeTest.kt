package com.openminis.app.ui.settings.backup

import com.openminis.app.backup.BackupImporter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [B01 cross-platform read] The credentials line under a finished restore.
 *
 * In the B01 cross-device test a backup whose provider_config.json could not
 * be read restored nothing ("unreadable 1"), and the report still said
 * "restored providers have no credentials - enter each API key": there were
 * no restored providers. The line is only for providers the restore wrote.
 */
class RestoreCredentialsNoticeTest {

    private fun providers(
        imported: Int = 0,
        updated: Int = 0,
        skipped: Int = 0,
        unreadable: Int = 0,
        restored: Int = 0,
        kept: Int = 0,
    ) = BackupImporter.CategoryReport(
        "providers",
        imported = imported,
        updated = updated,
        skipped = skipped,
        unreadable = unreadable,
        credentialsRestored = restored,
        credentialsKept = kept,
    )

    @Test
    fun `nothing restored and the file unreadable - no credentials line`() {
        assertNull(restoreCredentialsNotice(providers(unreadable = 1)))
    }

    @Test
    fun `nothing written because everything was already up to date - no credentials line`() {
        assertNull(restoreCredentialsNotice(providers(skipped = 5)))
    }

    @Test
    fun `providers restored without keys in the package - the enter-your-keys line`() {
        assertEquals(RestoreCredentialsNotice.NONE_IN_PACKAGE, restoreCredentialsNotice(providers(imported = 2)))
        assertEquals(RestoreCredentialsNotice.NONE_IN_PACKAGE, restoreCredentialsNotice(providers(updated = 1)))
    }

    @Test
    fun `keys restored or kept are reported as before`() {
        assertEquals(RestoreCredentialsNotice.RESTORED, restoreCredentialsNotice(providers(imported = 1, restored = 1)))
        assertEquals(RestoreCredentialsNotice.KEPT, restoreCredentialsNotice(providers(skipped = 3, kept = 3)))
    }
}
