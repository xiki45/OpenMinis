package com.openminis.app.data.repository

import com.openminis.app.ProductionSources
import com.openminis.app.backup.BackupEnvVarMeta
import com.openminis.app.backup.BackupFormat
import com.openminis.app.config.ConfigRisk
import com.openminis.app.config.ConfigValue
import com.openminis.app.config.ConfigError
import com.openminis.app.config.ConfigWriteWarnings
import com.openminis.app.config.collections.EnvVarsCollection
import com.openminis.app.config.collections.resolveEnvReference
import com.openminis.app.ui.settings.disabledEnvRefs
import com.openminis.app.data.EnvVarRedactor
import com.openminis.app.sandbox.TerminalSession
import kotlinx.serialization.builtins.ListSerializer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * [T-envvar-disable] A disabled environment variable keeps its value and note,
 * is not injected anywhere, stays masked in tool output, and - the hard part -
 * survives everything a build from before the feature can do to
 * `env-vars.json`: rewrite it, edit a note, rename, delete and re-create.
 */
class EnvVarDisableTest {

    private lateinit var dir: File
    private val secrets = HashMap<String, String>()
    private val valueStore = object : EnvVarRepository.ValueStore {
        override fun get(key: String) = secrets[key]
        override fun put(key: String, value: String) { secrets[key] = value }
        override fun remove(key: String) { secrets.remove(key) }
    }

    @Before fun setUp() { dir = Files.createTempDirectory("envvar").toFile() }
    @After fun tearDown() { dir.deleteRecursively(); EnvVarRedactor.envVarRepository = null }

    private fun repo() = EnvVarRepository(dir, valueStore)
    private fun metadata() = File(dir, EnvVarRepository.METADATA_FILE)
    private fun entry(r: EnvVarRepository, key: String) = r.entries.value.first { it.key == key }

    /** What a pre-feature build writes: id / key / note / createdAt, nothing else. */
    private fun writeAsOldBuild(vararg entries: Triple<String, String, String>) {
        val arr = JSONArray()
        for ((id, key, note) in entries) {
            arr.put(JSONObject().put("id", id).put("key", key).put("note", note).put("createdAt", 1L))
        }
        metadata().writeText(arr.toString())
    }

    // ---- storage and compatibility -------------------------------------

    @Test fun `old metadata without any state loads as enabled and stays that way`() {
        writeAsOldBuild(Triple("a", "API_KEY", "n"))
        secrets["API_KEY"] = "v"
        val r = repo()
        assertTrue(entry(r, "API_KEY").enabled)
        assertEquals(mapOf("API_KEY" to "v"), r.allAsDict())
        // Round trip through this build keeps the metadata shape old builds read.
        r.update("a", "API_KEY", "v2", "note")
        val saved = JSONArray(metadata().readText()).getJSONObject(0)
        assertFalse("enabled is never written into env-vars.json", saved.has("enabled"))
        assertTrue(repo().entries.value.single().enabled)
    }

    @Test fun `disabled survives a reload`() {
        val r = repo()
        r.add("GH_TOKEN", "ghp_secret")
        r.setEnabled(entry(r, "GH_TOKEN").id, false)
        val again = repo()
        assertFalse(entry(again, "GH_TOKEN").enabled)
        assertEquals("ghp_secret", again.getValue("GH_TOKEN"))
    }

    @Test fun `an old build rewriting env-vars json - note edit, value edit - does not re-enable`() {
        val r = repo()
        r.add("GH_TOKEN", "x", "old note")
        val id = entry(r, "GH_TOKEN").id
        r.setEnabled(id, false)
        // Downgrade: the older build edits the note (same id) and rewrites the file.
        writeAsOldBuild(Triple(id, "GH_TOKEN", "edited by an old build"))
        secrets["GH_TOKEN"] = "changed by an old build"
        val upgraded = repo()
        val e = entry(upgraded, "GH_TOKEN")
        assertFalse("state lives outside env-vars.json, so the rewrite cannot drop it", e.enabled)
        assertEquals("edited by an old build", e.note)
        assertTrue(upgraded.allAsDict().isEmpty())
    }

    @Test fun `an old build renaming keeps the state with the variable`() {
        val r = repo()
        r.add("OLD_NAME", "x")
        val id = entry(r, "OLD_NAME").id
        r.setEnabled(id, false)
        writeAsOldBuild(Triple(id, "NEW_NAME", ""))
        assertFalse(entry(repo(), "NEW_NAME").enabled)
    }

    @Test fun `an old build deleting and re-creating the name gives a fresh, enabled variable`() {
        val r = repo()
        r.add("GH_TOKEN", "x")
        val oldId = entry(r, "GH_TOKEN").id
        r.setEnabled(oldId, false)
        // The old build deletes it and adds one with the same name: a new id.
        writeAsOldBuild(Triple("brand-new-id", "GH_TOKEN", ""))
        val upgraded = repo()
        assertTrue("a new variable never inherits a deleted one's state", entry(upgraded, "GH_TOKEN").enabled)
        assertNull("the orphan state is pruned", EnvVarStateStore(dir).state(oldId))
    }

    @Test fun `deleting here clears the state, so the same name starts enabled`() {
        val r = repo()
        r.add("K", "1")
        r.setEnabled(entry(r, "K").id, false)
        r.delete(entry(r, "K").id)
        r.add("K", "2")
        assertTrue(entry(r, "K").enabled)
        assertTrue(entry(repo(), "K").enabled)
    }

    @Test fun `update has no enabled parameter and never re-enables`() {
        val r = repo()
        r.add("K", "1", "n")
        val id = entry(r, "K").id
        r.setEnabled(id, false)
        r.update(id, "K", "2", "n2")      // value + note (restore, minis-config note, deep link form)
        r.update(id, "K2", "2", "n2")     // rename
        assertFalse(entry(r, "K2").enabled)
        assertFalse(entry(repo(), "K2").enabled)
        val update = EnvVarRepository::class.java.methods.first { it.name == "update" }
        assertEquals("update(id, key, value, note) only", 4, update.parameterCount)
    }

    @Test fun `enabled has its own clock - value and note edits do not move it, and it never goes back`() {
        val r = repo()
        r.add("K", "1")
        val id = entry(r, "K").id
        val store = EnvVarStateStore(dir)
        store.setEnabled(id, "K", false, now = 5_000)
        val stamp = EnvVarStateStore(dir).state(id)!!.enabledUpdatedAt
        val again = repo()
        again.update(id, "K", "2", "note changed")
        assertEquals(stamp, EnvVarStateStore(dir).state(id)!!.enabledUpdatedAt)
        // Device clock moved back: the newer action still gets the newer stamp.
        val s2 = EnvVarStateStore(dir)
        s2.setEnabled(id, "K", true, now = 1_000)
        assertTrue(s2.state(id)!!.enabledUpdatedAt > stamp)
    }

    @Test fun `an unreadable state file is left alone, not overwritten`() {
        val r = repo()
        r.add("K", "1")
        File(dir, EnvVarStateStore.FILE_NAME).writeText("{not json")
        val again = repo()
        again.setEnabled(entry(again, "K").id, false)
        assertEquals("{not json", File(dir, EnvVarStateStore.FILE_NAME).readText())
    }

    @Test fun `entries without an id get one that sticks`() {
        metadata().writeText(JSONArray().put(JSONObject().put("key", "K")).toString())
        val first = repo()
        val id = entry(first, "K").id
        first.setEnabled(id, false)
        assertFalse(entry(repo(), "K").enabled)
    }

    @Test fun `duplicate names are reported`() {
        writeAsOldBuild(Triple("a", "TOKEN", ""), Triple("b", "token", ""), Triple("c", "OTHER", ""))
        assertEquals(setOf("TOKEN"), repo().duplicateKeys())
    }

    // ---- injection and redaction ----------------------------------------

    @Test fun `allAsDict skips disabled, redaction still covers them`() {
        val r = repo()
        r.add("ON_KEY", "on-value-123")
        r.add("OFF_KEY", "off-value-456")
        r.setEnabled(entry(r, "OFF_KEY").id, false)
        assertEquals(mapOf("ON_KEY" to "on-value-123"), r.allAsDict())
        assertEquals(setOf("on-value-123", "off-value-456"), r.allValuesForRedaction().toSet())
        assertTrue(r.isDisabled("off_key"))

        EnvVarRedactor.envVarRepository = r
        val (out, hits) = EnvVarRedactor.redactIfEnabled("leak: off-value-456")
        assertEquals(1, hits)
        assertFalse(out.contains("off-value-456"))
    }

    @Test fun `with every variable disabled a terminal gets none of them`() {
        val r = repo()
        r.add("A_KEY", "a")
        r.add("B_KEY", "b")
        r.entries.value.forEach { r.setEnabled(it.id, false) }
        val system = mapOf("PATH" to "/bin", "HOME" to "/root")
        val env = TerminalSession.terminalEnvironment(system, r.allAsDict())
        assertEquals(system, env)
        assertEquals("the kernel map is not written to", mapOf("PATH" to "/bin", "HOME" to "/root"), system)
    }

    @Test fun `no code puts user variables into the process-wide kernel environment`() {
        for (f in listOf("sandbox/TerminalSession.kt", "sandbox/ExecutionCoordinator.kt",
                         "sandbox/PersistentShell.kt", "sandbox/FreshProcessShell.kt", "sandbox/ShellExecutor.kt")) {
            val src = ProductionSources.read(f)
            assertFalse("$f writes allAsDict() into customEnvironment",
                Regex("""customEnvironment\.putAll\(\s*(envVars|.*allAsDict)""").containsMatchIn(src))
        }
        val terminal = ProductionSources.read("sandbox/TerminalSession.kt")
        assertTrue(terminal.contains("terminalEnvironment(\n                        PRootKernel.customEnvironment,\n                        ExecutionCoordinator.envVarRepository?.allAsDict().orEmpty(),"))
    }

    // ---- minis-config ----------------------------------------------------

    @Test fun `minis-config envvars KEY enabled reads and writes, sensitive and revertable`() {
        val r = repo()
        r.add("GH_TOKEN", "x")
        val field = EnvVarsCollection(r).fields("GH_TOKEN").first { it.path == "envvars.GH_TOKEN.enabled" }
        assertEquals(ConfigRisk.SENSITIVE, field.risk)
        assertTrue(field.revertable)
        assertEquals(ConfigValue.Bool(true), field.read())
        field.write(ConfigValue.Bool(false))
        assertFalse(entry(r, "GH_TOKEN").enabled)
        // A note write through minis-config keeps it disabled.
        EnvVarsCollection(r).fields("GH_TOKEN").first { it.path == "envvars.GH_TOKEN.note" }.write(ConfigValue.Str("n"))
        assertFalse(entry(r, "GH_TOKEN").enabled)
    }

    // ---- backup wire format -----------------------------------------------

    private val metaList = ListSerializer(BackupEnvVarMeta.serializer())

    @Test fun `backup - disabled is written, enabled is the default, missing means enabled`() {
        val json = BackupFormat.json.encodeToString(metaList, listOf(
            BackupEnvVarMeta(id = "1", key = "ON", createdAt = 0, enabled = true),
            BackupEnvVarMeta(id = "2", key = "OFF", createdAt = 0, enabled = false),
        ))
        assertTrue(json, json.contains("\"enabled\":false"))
        val back = BackupFormat.json.decodeFromString(metaList, json)
        assertEquals(listOf(true, false), back.map { it.enabled })
        // A package from before the feature (either platform) has no field.
        val old = BackupFormat.json.decodeFromString(metaList,
            """[{"id":"1","key":"K","note":"","createdAt":"2026-08-21T00:00:00Z"}]""")
        assertTrue(old.single().enabled)
    }

    @Test fun `restore applies enabled only to variables it creates`() {
        val importer = ProductionSources.read("backup/BackupImporter.kt")
        assertTrue(importer.contains("repo.add(meta.key, restored, meta.note, enabled = meta.enabled)"))
        assertFalse("an existing variable's state is never touched by a restore", importer.contains("setEnabled("))
        val exporter = ProductionSources.read("backup/BackupExporter.kt")
        assertTrue(exporter.contains("enabled = it.enabled"))
    }

    // ---- round 2: $$ references, MCP form, strings ------------------------

    @Test fun `a $$ reference to a disabled variable resolves to empty with a warning, not an error`() {
        val r = repo()
        r.add("ON_KEY", "on-value")
        r.add("OFF_KEY", "off-value")
        r.setEnabled(entry(r, "OFF_KEY").id, false)
        val (value, warnings) = ConfigWriteWarnings.collect { resolveEnvReference(r, "$$" + "OFF_KEY", "apiKey") }
        assertEquals("", value)
        assertEquals(1, warnings.size)
        assertTrue(warnings.single(), warnings.single().contains("OFF_KEY") && warnings.single().contains("disabled"))
        // Enabled and literal values are unchanged; a missing one still fails loudly.
        assertEquals("on-value", resolveEnvReference(r, "$$" + "ON_KEY", "apiKey"))
        assertEquals("sk-literal", resolveEnvReference(r, "sk-literal", "apiKey"))
        try {
            resolveEnvReference(r, "$$" + "NOPE", "apiKey"); throw AssertionError("expected InvalidValue")
        } catch (e: ConfigError.InvalidValue) { /* expected */ }
    }

    @Test fun `the minis-config bridge returns write warnings to the agent`() {
        val bridge = ProductionSources.read("config/ConfigBridge.kt")
        assertTrue(bridge.contains("is ConfirmOutcome.Approved -> ConfigWriteWarnings.collect {"))
        assertTrue(bridge.contains("response.put(\"warnings\", JSONArray(warnings))"))
    }

    @Test fun `the MCP form flags references to disabled variables in every spelling`() {
        val off = setOf("GH_TOKEN", "OTHER")
        assertEquals(listOf("GH_TOKEN"), disabledEnvRefs("Authorization: Bearer $$" + "GH_TOKEN", off))
        assertEquals(listOf("GH_TOKEN", "OTHER"),
            disabledEnvRefs("A=$" + "{GH_TOKEN}\nB=$" + "OTHER\nC=$$" + "{GH_TOKEN}\nD=$$" + "ON_KEY", off))
        assertTrue(disabledEnvRefs("no refs", off).isEmpty())
        assertTrue(disabledEnvRefs("$$" + "GH_TOKEN", emptySet()).isEmpty())
    }

    @Test fun `every locale has the same envvar strings and none is a restart hint`() {
        val res = File(ProductionSources.mainRoot()!!.parentFile.parentFile.parentFile.parentFile, "res")
        fun names(dir: String) = Regex("""<string name="([^"]+)">""")
            .findAll(File(res, "$dir/strings_envvar_state.xml").readText()).map { it.groupValues[1] }.toSet()
        val base = names("values")
        assertFalse(base.any { "restart" in it })
        assertTrue(base.containsAll(setOf("env_var_action_disable", "env_var_action_enable", "mcp_form_env_disabled_warning")))
        val dirs = res.listFiles()!!.filter { File(it, "strings_envvar_state.xml").exists() }.map { it.name }
        assertEquals(23, dirs.size)
        for (d in dirs) assertEquals(d, base, names(d))
        val screen = ProductionSources.read("ui/settings/EnvironmentVariablesScreen.kt")
        assertFalse(screen.contains("restart_hint"))
        assertTrue(screen.contains("envVarRepository.setEnabled(entry.id, !entry.enabled)"))
    }
}
