package com.openminis.app.backup

import com.openminis.app.ProductionSources
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [T-backup-restore-semantics / B01] The restore page's copy promised the old
 * semantics ("only replaced when the backup's copy is newer", "existing keys
 * on this device were kept"). It now says the backup wins, keys included, and
 * that nothing only on this device is deleted.
 */
class BackupRestoreCopyTest {

    // ── copy ─────────────────────────────────────────────────────────────

    @Test
    fun `the restore page says the backup wins, keys included, and nothing local is deleted`() {
        val screen = ProductionSources.read("ui/settings/backup/BackupAndRestoreScreen.kt")
        assertTrue(screen.contains("R.string.backup_restore_merge_footer"))
        assertTrue(screen.contains("R.string.backup_restore_creds_restored"))
        assertTrue(screen.contains("R.string.backup_restore_creds_kept"))
        // The old copy promised "only replaced when newer" / "existing keys kept".
        assertFalse(screen.contains("R.string.backup_restore_footer)"))
        assertFalse(screen.contains("R.string.backup_creds_restored"))
        assertFalse(screen.contains("R.string.backup_creds_kept"))
        val res = File(ProductionSources.mainRoot()!!.path.substringBefore("/java/") + "/res")
        val en = File(res, "values/strings_backup_restore.xml").readText()
        assertTrue(en.contains("the backup\\'s version is used, even if the one on this device is newer"))
        assertTrue(en.contains("That includes API keys"))
        assertTrue(en.contains("Nothing that exists only on this device is deleted"))
        val locales = res.listFiles()!!.filter { File(it, "strings.xml").isFile }
        for (dir in locales) {
            assertTrue("${dir.name} has no strings_backup_restore.xml", File(dir, "strings_backup_restore.xml").isFile)
        }
    }
}
