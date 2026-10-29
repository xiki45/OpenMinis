package com.openminis.app.ui.settings

import com.openminis.app.ProductionSources
import com.openminis.app.data.repository.SkillRepository
import com.openminis.app.data.repository.SkillRepository.ImportSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-skill-copy-source-url] Long-press on the Skills screens copies
 * the URL a skill was installed from - and is offered only when there is one.
 */
class SkillSourceUrlTest {

    private fun skill(source: ImportSource, url: String?) =
        SkillRepository.Skill(name = "s", importSource = source, sourceURL = url)

    @Test
    fun `a skill installed from a URL offers that URL`() {
        val url = "https://github.com/OpenMinis/skills/blob/main/pdf/SKILL.md"
        assertEquals(url, SkillSourceUrl.copyable(skill(ImportSource.URL, url)))
        assertEquals("surrounding whitespace is not copied", url, SkillSourceUrl.copyable(skill(ImportSource.URL, "  $url\n")))
    }

    @Test
    fun `a URL import whose URL was never persisted offers nothing`() {
        assertNull(SkillSourceUrl.copyable(skill(ImportSource.URL, null)))
        assertNull(SkillSourceUrl.copyable(skill(ImportSource.URL, "")))
        assertNull(SkillSourceUrl.copyable(skill(ImportSource.URL, "   ")))
    }

    @Test
    fun `file, bundled and session skills offer nothing, even with a leftover URL`() {
        // importFromContent keeps the old source_url when a URL skill is
        // re-imported from a file; that skill no longer comes from the URL.
        for (source in listOf(ImportSource.FILE, ImportSource.BUNDLED, ImportSource.SESSION)) {
            assertNull("$source", SkillSourceUrl.copyable(skill(source, null)))
            assertNull("$source", SkillSourceUrl.copyable(skill(source, "https://example.com/SKILL.md")))
        }
    }

    // -- wiring --

    private val screen = ProductionSources.read("ui/settings/SkillsManagementScreen.kt")
    private val components = ProductionSources.read("ui/settings/SettingsComponents.kt")

    @Test
    fun `the list row and the detail Source row only get a long-press when there is a URL`() {
        assertEquals(
            "both surfaces ask the same helper",
            2, Regex("""val sourceUrl = SkillSourceUrl\.copyable\(skill\)""").findAll(screen).count(),
        )
        assertEquals(
            "a null URL passes a null onLongClick",
            2, Regex("""onLongClick = sourceUrl\?\.let \{""").findAll(screen).count(),
        )
        assertTrue("the menu is not even composed without a URL", screen.contains("if (sourceUrl != null) {"))
        assertTrue(screen.contains("stringResource(R.string.skill_copy_source_url)"))
        assertEquals(2, Regex("""SkillSourceUrl\.copy\(context, """).findAll(screen).count())
    }

    @Test
    fun `a settings row without a long-press keeps its plain clickable`() {
        assertTrue(components.contains("onLongClick: (() -> Unit)? = null,"))
        assertTrue(components.contains("onClick != null && onLongClick != null ->"))
        assertTrue(components.contains("onClick != null -> Modifier.clickable(onClick = onClick)"))
    }

    // -- strings --

    private val res = java.io.File(ProductionSources.mainRoot()!!.parentFile.parentFile.parentFile.parentFile, "res")
    private val keys = listOf("skill_copy_source_url", "skill_source_url_copied")

    @Test
    fun `both strings exist in every shipped locale, with escaped apostrophes`() {
        val dirs = res.listFiles()!!.filter { java.io.File(it, "strings.xml").exists() }.map { it.name }
        assertTrue("found the locales: $dirs", dirs.size >= 18)
        for (dir in dirs) {
            val xml = java.io.File(res, "$dir/strings.xml").readText()
            for (key in keys) {
                val value = Regex("""<string name="$key">(.*?)</string>""").find(xml)?.groupValues?.get(1)
                assertTrue("$dir is missing $key", !value.isNullOrBlank())
                assertTrue("$dir/$key has an unescaped apostrophe", !Regex("""(?<!\\)'""").containsMatchIn(value!!))
            }
        }
    }
}
