package com.openminis.app.ui.chat

import com.openminis.app.ProductionSources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-session-skill-detail] In the chat's Skills sheet a row opens the
 * skill's detail (SKILL.md, other files, file preview) like iOS
 * SessionSkillDetailView, and a skill installed from a URL offers "Copy
 * Source URL" on long-press like Settings -> Skills.
 */
class SessionSkillsSheetDetailTest {

    private val sheet = ProductionSources.read("ui/chat/SessionSkillsSheet.kt")
    private val row = ProductionSources.read("ui/settings/SkillRowItem.kt")
    private val preview = ProductionSources.read("ui/sandbox/FilePreviewScreen.kt")

    // -- page stack --

    @Test
    fun `back goes file to detail to list`() {
        val file = SessionSkillsPage.File("pdf", "scripts/run.py")
        assertEquals(SessionSkillsPage.Detail("pdf"), file.back())
        assertEquals(SessionSkillsPage.List, SessionSkillsPage.Detail("pdf").back())
        assertEquals(SessionSkillsPage.List, SessionSkillsPage.List.back())
    }

    @Test
    fun `detail and file pages name their skill, the list does not`() {
        assertNull(SessionSkillsPage.List.skillId)
        assertEquals("pdf", SessionSkillsPage.Detail("pdf").skillId)
        assertEquals("pdf", SessionSkillsPage.File("pdf", "a.png").skillId)
    }

    @Test
    fun `other files leave out SKILL md in any case and keep the rest in order`() {
        val all = listOf("SKILL.md", "assets/logo.png", "scripts/run.py", "skill.MD")
        assertEquals(listOf("assets/logo.png", "scripts/run.py"), sessionSkillOtherFiles(all))
        assertEquals(emptyList<String>(), sessionSkillOtherFiles(listOf("SKILL.md")))
        assertEquals(emptyList<String>(), sessionSkillOtherFiles(emptyList()))
    }

    // -- wiring --

    @Test
    fun `a row tap opens the detail page instead of doing nothing`() {
        assertTrue(sheet.contains("onClick = { page = SessionSkillsPage.Detail(skill.id) },"))
        assertTrue(sheet.contains("onFileClick = { path -> page = SessionSkillsPage.File(pageSkill.id, path) },"))
    }

    @Test
    fun `list state lives above the page switch, so it survives a trip into a detail page`() {
        val beforeSwitch = sheet.substringBefore("when (currentPage) {\n            is SessionSkillsPage.Detail ->")
        for (state in listOf(
            "val overrides = remember(skills)",
            "var searchQuery by remember",
            "val listScroll = rememberScrollState()",
        )) {
            assertTrue("$state must be held above the page switch", beforeSwitch.contains(state))
        }
        assertTrue(sheet.contains(".verticalScroll(listScroll)"))
        // Toggles still write through to the repository, as before.
        assertTrue(sheet.contains("skillRepository.setSessionOverride(sessionId, skill.id, enabled)"))
    }

    @Test
    fun `back arrow and system back pop one page, and only off the list`() {
        assertTrue(sheet.contains("if (page == SessionSkillsPage.List) false else {\n                page = page.back()\n                true"))
        // The sheet's built-in back-dismiss runs ahead of a BackHandler in its
        // content, so it is switched off whenever a sheet takes back itself.
        val std = ProductionSources.read("ui/chat/StandardChatSheet.kt")
        assertTrue(std.contains("properties = ModalBottomSheetProperties(shouldDismissOnBackPress = onBack == null),"))
        assertTrue(std.contains("if (!onBack()) {\n                    scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() }"))
        assertTrue(sheet.contains("leadingAction = if (currentPage == SessionSkillsPage.List) null else {"))
        assertTrue(sheet.contains("IconButton(onClick = { page = currentPage.back() })"))
    }

    @Test
    fun `SKILL md is shown whole, monospaced and selectable`() {
        val detail = sheet.substringAfter("private fun SessionSkillDetail(").substringBefore("private fun SessionSkillFilePreview(")
        assertTrue(detail.contains("skillRepository.readSkillFile(skill.id, \"SKILL.md\") ?: skill.body"))
        assertTrue(detail.contains("SelectionContainer {"))
        assertTrue(detail.contains("fontFamily = FontFamily.Monospace"))
        assertFalse("not truncated", detail.contains("maxLines"))
        assertTrue(detail.contains("SettingsSection(header = stringResource(R.string.session_skill_other_files))"))
    }

    @Test
    fun `file preview reuses the file browser's image and text previews`() {
        assertTrue(sheet.contains("if (item.isImageFile) ImagePreview(item) else TextPreview(item)"))
        assertTrue(preview.contains("internal fun ImagePreview(item: FileItem) {"))
        assertTrue(preview.contains("internal fun TextPreview(item: FileItem) {"))
    }

    @Test
    fun `long-press copy reuses SkillSourceUrl and is attached only when there is a URL`() {
        assertTrue(sheet.contains("val sourceUrl = SkillSourceUrl.copyable(skill)"))
        assertTrue(sheet.contains("onLongClick = sourceUrl?.let {"))
        assertTrue("the menu is not even composed without a URL", sheet.contains("if (sourceUrl != null) {"))
        assertTrue(sheet.contains("SkillSourceUrl.copy(context, sourceUrl)"))
        assertTrue(sheet.contains("haptic.performHapticFeedback(HapticFeedbackType.LongPress)"))
        assertTrue(sheet.contains("stringResource(R.string.skill_copy_source_url)"))
    }

    @Test
    fun `a skill row without a long-press keeps its plain clickable`() {
        assertTrue(row.contains("onLongClick: (() -> Unit)? = null,"))
        assertTrue(row.contains("onClick != null && onLongClick != null ->"))
        assertTrue(row.contains("onClick != null -> Modifier.clickable(onClick = onClick)"))
    }

    @Test
    fun `search, bulk toggles, header and empty card are kept`() {
        for (kept in listOf(
            "R.string.skills_search_placeholder",
            "R.string.session_skills_header_installed",
            "R.string.session_skills_enable_all",
            "R.string.session_skills_disable_all",
            "for (skill in filteredSkills) {",
            "EmptySkillsCard()",
            "R.string.skills_search_no_match",
        )) {
            assertTrue("$kept is gone", sheet.contains(kept))
        }
    }

    // -- strings --

    private val res = java.io.File(ProductionSources.mainRoot()!!.parentFile.parentFile.parentFile.parentFile, "res")

    @Test
    fun `Other Files exists in every shipped locale`() {
        val dirs = res.listFiles()!!.filter { java.io.File(it, "strings.xml").exists() }.map { it.name }
        assertTrue("found the locales: $dirs", dirs.size >= 18)
        for (dir in dirs) {
            val xml = java.io.File(res, "$dir/strings.xml").readText()
            val value = Regex("""<string name="session_skill_other_files">(.*?)</string>""").find(xml)?.groupValues?.get(1)
            assertTrue("$dir is missing session_skill_other_files", !value.isNullOrBlank())
            assertTrue("$dir has an unescaped apostrophe", !Regex("""(?<!\\)'""").containsMatchIn(value!!))
        }
    }
}
