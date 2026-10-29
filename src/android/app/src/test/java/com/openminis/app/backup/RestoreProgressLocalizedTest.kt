package com.openminis.app.backup

import com.openminis.app.ProductionSources
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-restore-progress-localized] / [T-restore-lock-passphrase] Restore progress
 * lines are localized and name the category, and starting a restore puts the
 * keyboard away (port of iOS f587513fc).
 */
class RestoreProgressLocalizedTest {

    @Test
    fun `importer progress lines are string resources, the category by its localized name`() {
        val src = ProductionSources.read("backup/BackupImporter.kt")
        assertFalse(src.contains("invoke(\"Verifying integrity…\")"))
        assertFalse(src.contains("invoke(\"Decrypting…\")"))
        assertFalse("never the wire key", src.contains("\"Restoring \${category.key}…\""))
        assertTrue(src.contains("R.string.backup_restoring_category,"))
        assertTrue(src.contains("categoryNameRes(category)"))
    }

    @Test
    fun `view model status lines are string resources`() {
        val vm = ProductionSources.read("ui/settings/backup/BackupViewModel.kt")
        for (lit in listOf("\"Reading backup…\"", "\"Listing…\"", "\"Restoring…\"")) {
            assertFalse(lit, vm.contains("_statusText.value = $lit"))
        }
    }

    @Test
    fun `starting a restore clears the focus`() {
        val ui = ProductionSources.read("ui/settings/backup/BackupAndRestoreScreen.kt")
        assertTrue(ui.contains("restoreFocus.clearFocus(force = true)\n                vm.startRestore("))
    }
}
