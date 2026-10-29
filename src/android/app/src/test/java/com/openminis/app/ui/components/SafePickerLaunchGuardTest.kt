package com.openminis.app.ui.components

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-picker-anf] Source guard: every system picker launcher in the app
 * is launched through [safeLaunchPicker], never with a bare `.launch(`, so a
 * device without a resolvable DocumentsUI gets a toast instead of a crash.
 *
 * It scans the whole main source tree rather than a fixed file list, so a
 * picker added later in a new file is held to the same rule.
 */
class SafePickerLaunchGuardTest {

    private val root = File("src/main/java/com/openminis/app")

    private val pickerContract = Regex(
        """val\s+(\w+)\s*=\s*rememberLauncherForActivityResult\(\s*(?:contract\s*=\s*)?""" +
            """ActivityResultContracts\.(OpenDocument|OpenMultipleDocuments|CreateDocument|GetContent|""" +
            """OpenDocumentTree|PickVisualMedia|PickMultipleVisualMedia)\b""",
    )

    /** file (relative) -> picker launcher names declared in it */
    private fun pickers(): Map<String, List<String>> = root.walkTopDown()
        .filter { it.isFile && it.extension == "kt" }
        .associate { f -> f.relativeTo(root).path to pickerContract.findAll(f.readText()).map { it.groupValues[1] }.toList() }
        .filterValues { it.isNotEmpty() }

    @Test
    fun `the scan finds the known picker entry points`() {
        val found = pickers()
        // If one of these moves or is renamed the guard would silently scan
        // nothing for it - fail loudly instead.
        val expected = mapOf(
            "ui/chat/ChatScreen.kt" to listOf("mediaPickerLauncher", "filePickerLauncher"),
            "ui/settings/MountedFoldersScreen.kt" to listOf("pickerLauncher"),
            "ui/settings/backup/BackupAndRestoreScreen.kt" to listOf("saveLauncher", "pickLauncher"),
            "ui/settings/backup/RcloneDestinationsScreen.kt" to listOf("folderPicker"),
            "ui/settings/ProviderListScreen.kt" to listOf("importLauncher"),
            "ui/settings/SkillsManagementScreen.kt" to listOf("fileLauncher", "fileUpdateLauncher"),
            "ui/settings/SoulSettingsScreen.kt" to listOf("imagePicker"),
            "ui/sandbox/FilePreviewScreen.kt" to listOf("saveAsLauncher"),
            "webapp/AddToHomeSheet.kt" to listOf("galleryLauncher"),
        )
        for ((file, names) in expected) {
            assertEquals("pickers declared in $file", names, found[file])
        }
    }

    @Test
    fun `no picker launcher is launched bare`() {
        val bare = pickers().flatMap { (file, names) ->
            val src = File(root, file).readText()
            names.filter { Regex("""\b${it}\.launch\(""").containsMatchIn(src) }.map { "$file: $it.launch(" }
        }
        assertTrue("bare picker launches (use safeLaunchPicker): $bare", bare.isEmpty())
    }

    @Test
    fun `every picker launcher goes through safeLaunchPicker`() {
        val unguarded = pickers().flatMap { (file, names) ->
            val src = File(root, file).readText()
            names.filterNot { Regex("""\b${it}\.safeLaunchPicker\(""").containsMatchIn(src) }.map { "$file: $it" }
        }
        assertTrue("picker launchers never launched through safeLaunchPicker: $unguarded", unguarded.isEmpty())
    }

    @Test
    fun `the helper catches ActivityNotFoundException and does not pre-check with resolveActivity`() {
        val src = File(root, "ui/components/SafePickerLaunch.kt")
        assertTrue("SafePickerLaunch.kt is missing", src.isFile)
        val code = src.readLines().filterNot { it.trimStart().startsWith("*") || it.trimStart().startsWith("//") }
            .joinToString("\n")
        assertTrue(code.contains("catch (e: ActivityNotFoundException)"))
        // Package-visibility filtering can make these report nothing on devices
        // where the picker works; using them as a gate would break everyone.
        assertTrue(!code.contains("resolveActivity") && !code.contains("queryIntentActivities"))
    }
}
