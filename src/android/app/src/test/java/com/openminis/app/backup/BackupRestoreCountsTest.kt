package com.openminis.app.backup

import com.openminis.app.ProductionSources
import com.openminis.app.data.model.ProviderConfig
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [T-b01-restore-counts] The providers category's restore counts follow what
 * the restore really changed, driven by the case file both platforms read:
 * `src/shared/backup-restore/b01_restore_counts.json`. On a Pixel 4a a restore
 * that put back a deleted group, a model's name and an API key reported
 * imported=0 updated=0 skipped=27 ("Up to date 27") and logged a false SHORT
 * warning.
 */
class BackupRestoreCountsTest {

    private val doc: JSONObject by lazy {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "src/shared/backup-restore").isDirectory) dir = dir.parentFile
        val f = File(dir ?: error("src/shared/backup-restore not found"), "src/shared/backup-restore/b01_restore_counts.json")
        JSONObject(f.readText())
    }

    private fun config(o: JSONObject): ProviderConfig =
        BackupImporter.parseProviderConfigLeniently(o.toString()).config

    private fun counts(name: String): Pair<BackupProviderMerge.RestoreCounts, JSONObject> {
        val c = doc.getJSONObject("cases").getJSONObject(name)
        val remote = config(c.getJSONObject("remote"))
        val result = BackupProviderMerge.merge(config(c.getJSONObject("local")), remote)
        val creds = c.getJSONArray("credentialsWritten").let { a -> (0 until a.length()).map { a.getString(it) } }
        val expect = c.getJSONObject("expect")
        expect.optJSONObject("mergedByName")?.let { m ->
            assertEquals("$name: same-name rewrites", m.keys().asSequence().associateWith { m.getString(it) }, result.groupIdRewrites)
        }
        return BackupProviderMerge.counts(result, remote.instances.map { it.id }, creds) to expect
    }

    private fun check(name: String) {
        val (got, want) = counts(name)
        assertEquals(
            "$name: imported/updated/skipped",
            listOf(want.getInt("imported"), want.getInt("updated"), want.getInt("skipped")),
            listOf(got.imported, got.updated, got.skipped),
        )
    }

    // ① identical backup -> up to date, never "updated" (fix 93cad55ae)
    @Test fun identicalBackupIsUpToDate() = check("identical")

    @Test fun localOnlyInstanceIsNotCounted() = check("local_only_instance_ignored")

    @Test fun createdAtPrecisionIsNotAChange() = check("created_at_precision_only")

    // ② the backup changed a same-id instance's content -> updated
    @Test fun changedInstanceFieldsAreUpdated() = check("instance_field_changed")

    @Test fun changedModelParametersUpdateTheirInstance() {
        check("entry_parameter_changed")
        check("entry_hidden_changed")
        check("entry_added_to_existing_instance")
    }

    @Test fun writtenCredentialUpdatesItsInstance() = check("credential_written")

    @Test fun anInstanceChangedSeveralWaysCountsOnce() = check("instance_changed_several_ways")

    // ③ groups: restored -> imported, changed or merged by name -> updated
    @Test fun restoredGroupIsImported() = check("group_restored")

    @Test fun changedGroupIsUpdated() = check("group_content_changed")

    @Test fun sameNameMergeIsUpdated() = check("same_name_merge")

    // ④ not on this device -> imported
    @Test fun newInstanceIsImported() = check("new_instance")

    @Test fun deviceReproCountsWhatChanged() = check("device_repro_2026_10_15")

    /** Every case in the shared file is exercised by a test above. */
    @Test fun everySharedCaseIsCovered() {
        val covered = setOf(
            "identical", "local_only_instance_ignored", "instance_field_changed",
            "entry_parameter_changed", "entry_hidden_changed", "entry_added_to_existing_instance",
            "credential_written", "instance_changed_several_ways", "group_restored",
            "group_content_changed", "same_name_merge", "new_instance", "device_repro_2026_10_15",
            "created_at_precision_only",
        )
        assertEquals(covered, doc.getJSONObject("cases").keys().asSequence().toSet())
    }

    // ⑤ SHORT warning
    @Test fun mergedProvidersDoNotWarnShort() {
        val r = BackupImporter.CategoryReport(BackupCategory.PROVIDERS.key, skipped = 27, declaredAccounted = 27)
        assertNull(BackupImporter.shortfallWarning(r, 27))
    }

    @Test fun undecodableProvidersStillWarnShort() {
        val r = BackupImporter.CategoryReport(BackupCategory.PROVIDERS.key, skipped = 26, unreadable = 1, declaredAccounted = 26)
        val line = BackupImporter.shortfallWarning(r, 27)
        assertNotNull(line)
        assertTrue(line!!, line.contains("declared 27") && line.contains("unreadable=1"))
    }

    @Test fun unreadableProviderConfigStillWarnsShort() {
        // provider_config.json did not parse: nothing accounted for.
        val r = BackupImporter.CategoryReport(BackupCategory.PROVIDERS.key, unreadable = 1)
        assertNotNull(BackupImporter.shortfallWarning(r, 27))
    }

    @Test fun otherCategoriesKeepTheirShortRule() {
        val chats = BackupImporter.CategoryReport(BackupCategory.CHATS.key, imported = 5, skipped = 5)
        assertNotNull("skipped chats are still short", BackupImporter.shortfallWarning(chats, 10))
        assertNull(BackupImporter.shortfallWarning(chats.copy(updated = 5), 10))
        assertNull("no manifest count, no warning", BackupImporter.shortfallWarning(chats, null))
    }

    /** The importer counts from the merge, after the credentials are restored. */
    @Test fun importerCountsFromTheMergeAfterCredentials() {
        val importer = ProductionSources.read("backup/BackupImporter.kt")
        val body = importer.substringAfter("private suspend fun importProviders(").substringBefore("\n    private ")
        assertTrue("uses BackupProviderMerge.counts", body.contains("BackupProviderMerge.counts("))
        assertTrue("counts after the credentials", body.indexOf("restoreBackupProviderSecret") < body.indexOf("BackupProviderMerge.counts("))
        assertFalse("no longer derives skipped from instances - imported", body.contains("config.instances.size - report.imported"))
        assertTrue("accounts for every decoded instance", body.contains("report.declaredAccounted"))
    }
}
