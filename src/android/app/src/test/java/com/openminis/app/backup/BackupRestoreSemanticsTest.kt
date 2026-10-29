package com.openminis.app.backup

import com.openminis.app.ProductionSources
import com.openminis.app.data.model.ModelGroup
import com.openminis.app.data.model.ModelOverrides
import com.openminis.app.data.model.ProviderConfig
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [T-backup-restore-semantics / B01] The restore semantics the owner decided
 * (2026-10-15), driven by the scenario file both platforms read:
 * `src/shared/backup-restore/b01_restore_scenarios.json`. One test per spec row
 * (a)-(i). Users reported that a restore left the model list, parameters and
 * groups as they were on the device (Android: same-id groups and existing
 * entries kept the LOCAL copy; a same-name group from the backup appeared as a
 * second group), while iOS dropped them outright.
 */
class BackupRestoreSemanticsTest {

    private val doc: JSONObject by lazy {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "src/shared/backup-restore").isDirectory) dir = dir.parentFile
        val f = File(dir ?: error("src/shared/backup-restore not found"), "src/shared/backup-restore/b01_restore_scenarios.json")
        JSONObject(f.readText())
    }

    private fun scenario(name: String) = doc.getJSONObject("scenarios").getJSONObject(name)

    private fun config(o: JSONObject): ProviderConfig =
        BackupImporter.parseProviderConfigLeniently(o.toString()).config

    private fun run(name: String): Pair<BackupProviderMerge.Result, JSONObject> {
        val s = scenario(name)
        return BackupProviderMerge.merge(config(s.getJSONObject("local")), config(s.getJSONObject("remote"))) to
            s.getJSONObject("expect")
    }

    private fun overrides(o: JSONObject): ModelOverrides =
        BackupFormat.json.decodeFromString(ModelOverrides.serializer(), o.toString())

    private fun strings(o: JSONObject, key: String): List<String> {
        val a = o.getJSONArray(key)
        return (0 until a.length()).map { a.getString(it) }
    }

    private fun entryKey(e: com.openminis.app.data.model.ModelEntry) = "${e.providerInstanceId}/${e.baseModel.id}"

    private fun assertEntries(merged: ProviderConfig, expect: JSONObject) {
        val entries = expect.optJSONObject("entries") ?: return
        for (key in entries.keys()) {
            val want = entries.getJSONObject(key)
            val got = merged.modelEntries.firstOrNull { entryKey(it) == key }
            assertNotNull("entry $key is missing", got)
            got!!
            assertEquals("overrides of $key", overrides(want.getJSONObject("overrides")), got.overrides)
            assertEquals("isHidden of $key", want.getBoolean("isHidden"), got.isHidden)
            if (want.has("baseDisplayName")) {
                assertEquals("baseModel of $key is the API's, not the backup's", want.getString("baseDisplayName"), got.baseModel.displayName)
            }
            if (want.optBoolean("absentSinceKept")) assertNotNull("absentSince of $key is kept", got.absentSince)
        }
    }

    private fun assertGroups(merged: ProviderConfig, expect: JSONObject) {
        val groups = expect.optJSONArray("groups") ?: return
        for (i in 0 until groups.length()) {
            val want = groups.getJSONObject(i)
            val id = want.getString("id")
            val got: ModelGroup? = merged.modelGroups.firstOrNull { it.id == id }
            assertNotNull("group $id is missing", got)
            got!!
            assertEquals("name of $id", want.getString("name"), got.name)
            assertEquals("members of $id", strings(want, "memberEntryIds"), got.memberEntryIds.toList())
            assertEquals("fallbackStrategy of $id", want.getString("fallbackStrategy"), got.fallbackStrategy.name)
            if (want.has("defaultThinkingLevel")) {
                assertEquals("thinking of $id", want.getString("defaultThinkingLevel").uppercase(), got.defaultThinkingLevel?.name)
            }
            if (want.has("contextLimitTokens")) {
                assertEquals("context limit of $id", want.getInt("contextLimitTokens"), got.contextLimitTokens)
            }
            assertEquals("exactly one group named ${want.getString("name")}", 1, merged.modelGroups.count { it.name == want.getString("name") })
        }
    }

    // ── (a) empty device ─────────────────────────────────────────────────

    @Test
    fun `a - onto an empty device every entry comes back, non-custom ones included`() {
        val (r, expect) = run("a_empty_local")
        val merged = r.config
        assertEquals(strings(expect, "instanceIds"), merged.instances.map { it.id })
        assertEquals(expect.getInt("entryCount"), merged.modelEntries.size)
        assertEntries(merged, expect)
        assertGroups(merged, expect)
        assertEquals(strings(expect, "agentLoopModelEntryIds"), merged.agentLoopModelEntryIds.toList())
        assertEquals(strings(expect, "agentLoopGroupIds"), merged.agentLoopGroupIds.toList())
    }

    // ── (b) same entry key, different parameters ─────────────────────────

    @Test
    fun `b - the backup's parameters win over the device's, the API's baseModel stays`() {
        val (r, expect) = run("b_same_key_backup_wins")
        val merged = r.config
        assertEquals(expect.getInt("entryCount"), merged.modelEntries.size)
        assertEntries(merged, expect)
    }

    @Test
    fun `b - the backup's instance fields win`() {
        val (r, expect) = run("b_same_key_backup_wins")
        val want = expect.getJSONObject("instances")
        for (id in want.keys()) {
            val got = r.config.instances.single { it.id == id }
            assertEquals(want.getJSONObject(id).getString("label"), got.label)
            assertEquals(want.getJSONObject(id).getString("customBaseURL"), got.customBaseURL)
        }
    }

    // ── (c) same group id ────────────────────────────────────────────────

    @Test
    fun `c - a group with the same id takes the backup's fields and order, local extras appended`() {
        val (r, expect) = run("c_same_id_group")
        assertGroups(r.config, expect)
    }

    // ── (d) same name, different id ──────────────────────────────────────

    @Test
    fun `d - a same-name group collapses into one with the backup's content`() {
        val (r, expect) = run("d_same_name_different_id")
        assertGroups(r.config, expect)
        assertEquals(1, r.config.modelGroups.size)
        val rewrites = expect.getJSONObject("groupIdRewrites")
        assertEquals(rewrites.keys().asSequence().associateWith { rewrites.getString(it) }, r.groupIdRewrites)
    }

    @Test
    fun `d - every config reference to the swallowed id is rewritten`() {
        val (r, expect) = run("d_same_name_different_id")
        val c = r.config
        val p = expect.getJSONObject("pointers")
        assertEquals("defaultPrimaryGroupId", p.getString("defaultPrimaryGroupId"), c.defaultPrimaryGroupId)
        assertEquals("defaultSubGroupId", p.getString("defaultSubGroupId"), c.defaultSubGroupId)
        assertEquals("voiceInputGroupId", p.getString("voiceInputGroupId"), c.voiceInputGroupId)
        assertEquals("voiceOutputGroupId", p.getString("voiceOutputGroupId"), c.voiceOutputGroupId)
        assertEquals("visionGroupId", p.getString("visionGroupId"), c.visionGroupId)
        assertEquals("agentLoopGroupIds", strings(expect, "agentLoopGroupIds"), c.agentLoopGroupIds.toList())
        val agents = expect.getJSONObject("subAgentModelGroupIds")
        for (id in agents.keys()) {
            assertEquals("sub agent $id", agents.getString(id), c.subAgents.single { it.id == id }.modelGroupId)
        }
    }

    @Test
    fun `d - sub agents from sub_agents jsonl are rewritten too`() {
        val (r, _) = run("d_same_name_different_id")
        val incoming = listOf(
            com.openminis.app.data.model.SubAgentDefinition(id = "jsonl", name = "J", description = "d", modelGroupId = "g-remote"),
        )
        assertEquals("g-local", BackupProviderMerge.rewriteSubAgents(incoming, r.groupIdRewrites).single().modelGroupId)
    }

    @Test
    fun `d - session and scheduled-task bindings to the swallowed id are rewritten`() {
        val (r, expect) = run("d_same_name_different_id")
        val bindings = expect.getJSONArray("bindings")
        for (i in 0 until bindings.length()) {
            val b = bindings.getJSONObject(i)
            val out = BackupProviderMerge.rewriteBindingJson(b.getString("in"), r.groupIdRewrites)
            if (b.optBoolean("unchanged")) {
                assertEquals(b.getString("in"), out)
            } else {
                assertEquals(b.getString("in"), b.getString("groupId"), JSONObject(out!!).getString("groupId"))
            }
        }
        val legacy = expect.getJSONArray("legacyModelIds")
        for (i in 0 until legacy.length()) {
            val l = legacy.getJSONObject(i)
            assertEquals(l.getString("out"), BackupProviderMerge.rewriteLegacyModelId(l.getString("in"), r.groupIdRewrites))
        }
    }

    @Test
    fun `d - the restore applies the rewrites to sessions, scheduled tasks and sub_agents jsonl`() {
        val importer = ProductionSources.read("backup/BackupImporter.kt")
        assertTrue(importer.contains("rewriteSessionGroupBindings("))
        assertTrue(importer.contains("rewriteScheduledTaskGroupBindings("))
        assertTrue(importer.contains("BackupProviderMerge.rewriteSubAgents("))
    }

    // ── (e) agent-loop lists ─────────────────────────────────────────────

    @Test
    fun `e - agent loop lists are a union`() {
        val (r, expect) = run("e_agent_loop_union")
        assertEquals(strings(expect, "agentLoopModelEntryIds"), r.config.agentLoopModelEntryIds.toList())
        assertEquals(strings(expect, "agentLoopGroupIds"), r.config.agentLoopGroupIds.toList())
    }

    // ── (f) tombstones ───────────────────────────────────────────────────

    @Test
    fun `f - a group gone from this device comes back, the backup's own tombstones are not applied`() {
        val (r, expect) = run("f_tombstones")
        assertEquals(strings(expect, "groupIds"), r.config.modelGroups.map { it.id })
        assertEquals(strings(expect, "entryKeys"), r.config.modelEntries.map(::entryKey))
    }

    // ── (g) nothing local is deleted ─────────────────────────────────────

    @Test
    fun `g - items only on this device survive`() {
        val (r, expect) = run("g_local_only_survives")
        assertEquals(strings(expect, "instanceIds"), r.config.instances.map { it.id })
        assertEquals(strings(expect, "entryKeys").sorted(), r.config.modelEntries.map(::entryKey).sorted())
        assertEquals(strings(expect, "groupIds"), r.config.modelGroups.map { it.id })
        assertEntries(r.config, expect)
    }

    @Test
    fun `members given as legacy uuids are translated to the entry's composite key`() {
        val local = ProviderConfig()
        val remote = config(
            JSONObject(
                """
                {"instances": [], "modelGroups": [{"id": "g", "name": "G", "memberEntryIds": ["uuid-a"]}],
                 "modelEntries": [{"providerInstanceId": "inst-1", "model": {"id": "m-a", "displayName": "A", "provider": "openAI"},
                                   "uuid": "uuid-a"}],
                 "agentLoopModelEntryIds": ["uuid-a"]}
                """.trimIndent(),
            ),
        )
        val merged = BackupProviderMerge.merge(local, remote).config
        assertEquals(listOf("inst-1/m-a"), merged.modelGroups.single().memberEntryIds.toList())
        assertEquals(listOf("inst-1/m-a"), merged.agentLoopModelEntryIds.toList())
        assertEquals("inst-1/m-a", merged.modelEntries.single().id)
    }

    // ── (i) one restore merge, not the sync merger ───────────────────────

    @Test
    fun `i - the restore uses the restore-only merge`() {
        val repo = ProductionSources.read("data/repository/ProviderRepository.kt")
        val body = repo.substringAfter("fun mergeBackupProviderConfig(").substringBefore("\n    fun ")
        assertTrue(body.contains("BackupProviderMerge.merge("))
        assertFalse(body.contains("mergeProviderConfigForRestore"))
        assertFalse(body.contains("existing ?: rg"))
        val merge = ProductionSources.read("backup/BackupProviderMerge.kt")
        assertFalse(merge.contains("mergeProviderConfigForRestore"))
        assertFalse(merge.contains("OLD-SEMANTICS"))
    }
}
