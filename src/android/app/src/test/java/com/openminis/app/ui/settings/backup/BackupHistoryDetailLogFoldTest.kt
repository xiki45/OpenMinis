package com.openminis.app.ui.settings.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [T-android-backup-log-fold] The detail screen composed every log line in a
 * non-lazy Column: 16,827 lines froze the main thread for 32–70 s (KILO
 * repro). It now composes the newest [LOG_COLLAPSED_COUNT] and offers the rest
 * behind "Show all". Source guard: a Compose test would need the whole
 * SettingsScaffold harness for what is one line of selection logic.
 */
class BackupHistoryDetailLogFoldTest {

    private val src = File("src/main/java/com/openminis/app/ui/settings/backup/BackupHistoryDetailScreen.kt").readText()
    private val logSection = src.substringAfter("// -- Log --").substringBefore("Spacer(Modifier.height(24.dp))")

    @Test
    fun `log rows are composed for the folded slice only`() {
        assertEquals("the cap", 100, LOG_COLLAPSED_COUNT)
        assertFalse(
            "no LogRow for every entry",
            Regex("""entries\.forEachIndexed\s*\{[^}]*LogRow""").containsMatchIn(logSection),
        )
        assertTrue(logSection.contains("entries.take(LOG_COLLAPSED_COUNT)"))
        assertTrue(Regex("""shown\.forEachIndexed\s*\{[^}]*LogRow""").containsMatchIn(logSection))
        assertEquals("one LogRow call site", 1, Regex("""LogRow\(""").findAll(logSection).count())
    }

    @Test
    fun `the rest is behind an explicit show all with the total`() {
        assertTrue(logSection.contains("R.string.backup_history_log_show_all, entries.size"))
        assertTrue(logSection.contains("showAllLog = true"))
        // Folded per record: opening another record starts folded again.
        assertTrue(src.contains("var showAllLog by remember(record.id)"))
    }

    @Test
    fun `the new strings exist in every locale folder and no existing strings xml carries them`() {
        val res = File("src/main/res")
        val folders = res.listFiles()!!.filter { it.name == "values" || File(it, "strings_backup_restore.xml").exists() }
        assertTrue(folders.size >= 23)
        folders.forEach { d ->
            val f = File(d, "strings_backup_log.xml")
            assertTrue("missing ${f.path}", f.exists())
            val text = f.readText()
            assertTrue(text.contains("name=\"backup_history_log_show_all\""))
            assertTrue(text.contains("name=\"backup_history_log_omitted\""))
            assertFalse(File(d, "strings.xml").takeIf { it.exists() }?.readText()?.contains("backup_history_log_show_all") ?: false)
        }
    }
}
