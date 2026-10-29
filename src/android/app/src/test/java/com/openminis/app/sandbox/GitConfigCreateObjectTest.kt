package com.openminis.app.sandbox

import com.openminis.app.ProductionSources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * [T-git-createobject-rename] openminis/openminis#409: minis.sh ran
 * `git config --system core.createObject copy`. `copy` is not a valid value
 * (only `link` and `rename` are), so every git command in the sandbox died
 * with "invalid mode for object creation". Now:
 * - minis.sh no longer writes it;
 * - PRootKernel injects core.createObject=rename through GIT_CONFIG_COUNT /
 *   KEY_0 / VALUE_0, so every shell (login or not) gets it;
 * - RootfsManager removes the invalid line from the guest's /etc/gitconfig at
 *   boot, before any shell runs.
 */
class GitConfigCreateObjectTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun migrate(text: String) = RootfsManager.migrateGitConfigText(text)

    // ── Migration (pure) ───────────────────────────────────────────────────

    @Test fun `the only entry - the line goes, the core header stays`() {
        val (out, removed) = migrate("[core]\n\tcreateObject = copy\n")
        assertEquals(1, removed)
        assertEquals("[core]\n", out)
    }

    @Test fun `mixed with other settings - only createObject goes, everything else byte for byte`() {
        val before = """
            |# system config written by the user
            |[core]
            |	autocrlf = input
            |	createObject = copy
            |	editor = vi ; trailing comment
            |[user]
            |	name = Someone
            |	createObject = copy
            |[alias]
            |	st = status
            |""".trimMargin()
        val (out, removed) = migrate(before)
        assertEquals(1, removed)
        assertEquals("only the [core] one (the first) goes", before.replaceFirst("\tcreateObject = copy\n", ""), out)
        assertTrue("a createObject outside [core] is not Git's core setting: kept", out.contains("[user]\n\tname = Someone\n\tcreateObject = copy\n"))
    }

    @Test fun `valid values are kept - rename and link exactly`() {
        for (text in listOf("[core]\n\tcreateObject = rename\n", "[core]\n\tcreateObject = link\n", "[core]\n\tcreateObject = \"rename\" # ok\n")) {
            val (out, removed) = migrate(text)
            assertEquals(text, 0, removed)
            assertEquals(text, out)
        }
    }

    @Test fun `case variants of the section and key are matched, values are as strict as Git's`() {
        val (out, removed) = migrate("[Core]\n\tcreateobject=copy\n\tCREATEOBJECT = Rename\n\tfilemode = false\n")
        assertEquals("Git compares the value case-sensitively: Rename is fatal too", 2, removed)
        assertEquals("[Core]\n\tfilemode = false\n", out)
    }

    @Test fun `a key with no value is fatal in Git and is removed`() {
        assertEquals(1, migrate("[core]\n\tcreateObject\n").second)
    }

    @Test fun `an empty core section and a file without it are untouched`() {
        for (text in listOf("[core]\n", "[core]\n[user]\n\tname = x\n", "", "[user]\n\temail = a@b\n")) {
            val (out, removed) = migrate(text)
            assertEquals(0, removed)
            assertEquals(text, out)
        }
    }

    @Test fun `several core sections and CRLF line endings`() {
        val (out, removed) = migrate("[core]\r\n\tcreateObject = copy\r\n[user]\r\n\tname = x\r\n[core]\r\n\tcreateObject = copy\r\n")
        assertEquals(2, removed)
        assertEquals("[core]\r\n[user]\r\n\tname = x\r\n[core]\r\n", out)
    }

    @Test fun `running it again changes nothing`() {
        val once = migrate("[core]\n\tcreateObject = copy\n\tbare = false\n").first
        assertEquals(once to 0, migrate(once))
    }

    // ── Migration (file) ───────────────────────────────────────────────────

    @Test fun `the file is rewritten in place, and a missing file is fine`() {
        val etc = tmp.newFolder("etc")
        val f = File(etc, "gitconfig")
        assertEquals("absent: nothing to do", 0, RootfsManager.migrateGitConfigFile(f))
        assertFalse(f.exists())
        f.writeText("[core]\n\tcreateObject = copy\n\tautocrlf = input\n")
        assertEquals(1, RootfsManager.migrateGitConfigFile(f))
        assertEquals("[core]\n\tautocrlf = input\n", f.readText())
        assertEquals("idempotent", 0, RootfsManager.migrateGitConfigFile(f))
        assertEquals("no temp file left behind", listOf("gitconfig"), etc.list()!!.toList())
    }

    // ── Guards ─────────────────────────────────────────────────────────────

    @Test fun `minis sh no longer writes the invalid value`() {
        val sh = File(ProductionSources.mainRoot()!!, "../../../../assets/default_mount/etc/profile.d/minis.sh").readText()
        assertFalse(Regex("""^\s*git config[^\n]*createObject""", RegexOption.MULTILINE).containsMatchIn(sh))
        assertFalse(sh.contains("createObject copy\n") && sh.lines().any { it.trimStart().startsWith("git ") && it.contains("copy") })
    }

    @Test fun `PRootKernel injects core createObject rename for every shell, without clobbering a user's set`() {
        val k = ProductionSources.read("sandbox/PRootKernel.kt")
        val block = k.substringAfter("if (!customEnvironment.containsKey(\"GIT_CONFIG_COUNT\")) {").substringBefore("\n        }\n")
        assertTrue(block.contains("customEnvironment.putIfAbsent(\"GIT_CONFIG_COUNT\", \"1\")"))
        assertTrue(block.contains("customEnvironment.putIfAbsent(\"GIT_CONFIG_KEY_0\", \"core.createObject\")"))
        assertTrue(block.contains("customEnvironment.putIfAbsent(\"GIT_CONFIG_VALUE_0\", \"rename\")"))
    }

    @Test fun `the migration runs at the start of every overlay, before anything can return early`() {
        val r = ProductionSources.read("sandbox/RootfsManager.kt")
        val overlay = r.substringAfter("suspend fun applyDefaultMountOverlay() = withContext(Dispatchers.IO) {")
        assertTrue(overlay.indexOf("migrateSystemGitConfig()") in 0 until overlay.indexOf("context.assets.list(DEFAULT_MOUNT_ASSET)"))
        assertTrue(r.contains("private fun migrateSystemGitConfig(): Int = migrateGitConfigFile(File(rootfsDir, \"etc/gitconfig\"))"))
    }
}
