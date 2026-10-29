package com.openminis.app.ui.settings

import com.openminis.app.data.repository.ProviderRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [T-android-hidden-models-group] (#462 / #442) The provider detail screen
 * lists only visible models; hidden ones sit in a collapsible "Hidden models
 * (N)" group at the bottom, where each can be shown again or deleted. Delete
 * is offered for every model, not only custom ones, as on iOS.
 *
 * Source guard: a Compose test would need the whole SettingsScaffold and a
 * ProviderRepository for what is list selection plus one menu condition.
 */
class ProviderDetailHiddenModelsTest {

    private val src = File("src/main/java/com/openminis/app/ui/settings/ProviderDetailScreen.kt").readText()
    private val models = src.substringAfter("// ─── Models ───").substringBefore("MinisOutlinedButton(")

    @Test
    fun `the main list renders visible entries and the hidden group renders hidden ones`() {
        assertTrue(src.contains("val (visibleEntries, hiddenEntries) = entries.partition { !it.isHidden }"))
        assertFalse("no row for every entry", models.contains("entries.forEachIndexed"))
        assertTrue(models.contains("visibleEntries.forEachIndexed"))
        assertTrue(models.contains("if (hiddenEntries.isNotEmpty())"))
        assertTrue(models.contains("R.string.provider_detail_hidden_models_group, hiddenEntries.size"))
        assertTrue("hidden rows only when expanded", models.contains("if (hiddenExpanded) {"))
        assertTrue(models.contains("hiddenEntries.forEachIndexed"))
        // Folded per provider: opening another provider starts collapsed.
        assertTrue(src.contains("var hiddenExpanded by remember(instanceId) { mutableStateOf(false) }"))
    }

    @Test
    fun `delete is offered for every model and API models get the come-back warning`() {
        assertFalse("delete is no longer custom-only", src.contains("if (entry.isCustom) {"))
        assertTrue(Regex("""onClick = \{\s*menuEntryId = null\s*entryToDelete = entry""").containsMatchIn(models))
        assertTrue(src.contains("if (e.isCustom) R.string.provider_detail_delete_model_confirm"))
        assertTrue(src.contains("else R.string.provider_detail_delete_api_model_confirm"))
    }

    @Test
    fun `unhiding writes straight through and the absence grace is unchanged`() {
        assertTrue(src.contains("providerRepository.updateEntry(entry.copy(isHidden = !entry.isHidden))"))
        assertEquals(7L * 24 * 60 * 60 * 1000, ProviderRepository.MODEL_ABSENCE_GRACE_MS)
        val repo = File("src/main/java/com/openminis/app/data/repository/ProviderRepository.kt").readText()
        val remove = repo.substringAfter("fun removeEntry(entryId: String)").substringBefore("// --- Model Group management")
        assertFalse("removeEntry deletes any entry", remove.contains("isCustom"))
    }

    @Test
    fun `the new strings exist in every locale folder and no existing strings xml carries them`() {
        val folders = File("src/main/res").listFiles()!!.filter { File(it, "strings.xml").exists() }
        assertTrue(folders.size >= 23)
        folders.forEach { d ->
            val f = File(d, "strings_hidden_models.xml")
            assertTrue("missing ${f.path}", f.exists())
            val text = f.readText()
            assertTrue(text.contains("name=\"provider_detail_hidden_models_group\">"))
            assertTrue(text.contains("%1\$d"))
            assertTrue(text.contains("name=\"provider_detail_delete_api_model_confirm\">"))
            assertTrue(text.contains("%1\$s"))
            assertFalse(File(d, "strings.xml").readText().contains("provider_detail_hidden_models_group"))
        }
    }
}
