package com.openminis.app.backup

import com.openminis.app.data.model.ProviderConfig
import java.io.File
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [B01 cross-platform read] An Android backup's `provider_config.json` must
 * decode on iOS.
 *
 * Android's backup JSON omits every field equal to its default, and iOS
 * decodes these with `decode` (required): `instances[].isEnabled`,
 * `modelEntries[].isHidden` / `isCustom`, the top-level `sessionBindings`;
 * a group without `strategy` is dropped by `decodeModelGroupsLeniently`.
 * Observed on vphone with a real Pixel package: `keyNotFound 'isEnabled'`,
 * the whole file unreadable, nothing restored.
 *
 * The fixture is the B01X part of that real package (Android 1.15-beta.4,
 * build 33), unchanged: it is exactly what the old exporter wrote.
 */
class BackupProviderWireTest {

    private val fixture: String by lazy {
        var dir: File? = File(".").absoluteFile
        while (dir != null && !File(dir, "src/shared/backup-restore").isDirectory) dir = dir.parentFile
        File(
            dir ?: error("src/shared/backup-restore not found"),
            "src/shared/backup-restore/b01x_android_v33_provider_config.json",
        ).readText()
    }

    private fun decode(text: String): ProviderConfig =
        BackupImporter.parseProviderConfigLeniently(text).config

    private fun exported(config: ProviderConfig): JsonObject =
        BackupFormat.json.parseToJsonElement(BackupProviderWire.encode(config)).jsonObject

    private fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.content

    @Test
    fun `the old exporter left out the fields iOS requires`() {
        // Guards the premise: if this ever fails the fixture is not what the
        // field build wrote and the tests below prove nothing.
        val raw = BackupFormat.json.parseToJsonElement(fixture).jsonObject
        val instance = raw["instances"]!!.jsonArray.single().jsonObject
        assertTrue("isEnabled" !in instance)
        val m2 = raw["modelEntries"]!!.jsonArray.map { it.jsonObject }.single { it.str("uuid")!!.endsWith("/b01x-m2") }
        assertTrue("isHidden" !in m2)
        val codex = raw["modelGroups"]!!.jsonArray.map { it.jsonObject }.single { it.str("name") == "B01X-G-CODEX" }
        assertTrue("strategy" !in codex)
        assertTrue("sessionBindings" !in raw)
    }

    @Test
    fun `every field iOS requires is written, default values included`() {
        val out = exported(decode(fixture))

        val instances = out["instances"]!!.jsonArray.map { it.jsonObject }
        assertEquals(1, instances.size)
        for (i in instances) {
            assertEquals("isEnabled", "true", i.str("isEnabled"))
            assertEquals("appendV1Suffix", "true", i.str("appendV1Suffix"))
        }

        val entries = out["modelEntries"]!!.jsonArray.map { it.jsonObject }
        assertEquals(2, entries.size)
        for (e in entries) {
            assertTrue("isHidden in $e", e["isHidden"] is JsonPrimitive)
            assertTrue("isCustom in $e", e["isCustom"] is JsonPrimitive)
        }
        val byModel = entries.associateBy { it.str("uuid")!!.substringAfterLast('/') }
        assertEquals("true", byModel.getValue("b01x-m1").str("isHidden"))
        assertEquals("false", byModel.getValue("b01x-m2").str("isHidden"))
        assertEquals("true", byModel.getValue("b01x-m2").str("isCustom"))

        val groups = out["modelGroups"]!!.jsonArray.map { it.jsonObject }.associateBy { it.str("name")!! }
        assertEquals(3, groups.size)
        for ((name, g) in groups) {
            assertTrue("strategy in $name", g.str("strategy") != null)
            // iOS FallbackStrategy has only `limited` and `always`; Android's
            // `default` is iOS's `limited` and must be written that way.
            assertTrue("fallbackStrategy in $name: ${g.str("fallbackStrategy")}", g.str("fallbackStrategy") in setOf("limited", "always"))
        }
        assertEquals("fallback", groups.getValue("B01X-G-CODEX").str("strategy"))
        assertEquals("limited", groups.getValue("B01X-G-CODEX").str("fallbackStrategy"))
        assertEquals("HIGH", groups.getValue("B01X-G-CODEX").str("defaultThinkingLevel"))
        assertEquals("loadBalance", groups.getValue("B01X-G-SAME").str("strategy"))
        assertEquals("always", groups.getValue("B01X-G-SAME").str("fallbackStrategy"))
        assertEquals("fallback", groups.getValue("B01X-G-NEW").str("strategy"))
        assertEquals("limited", groups.getValue("B01X-G-NEW").str("fallbackStrategy"))

        // Android has no session bindings in its config; iOS requires the key.
        assertEquals(JsonObject(emptyMap()), out["sessionBindings"])
    }

    @Test
    fun `an empty config still writes the arrays and sessionBindings`() {
        val out = exported(ProviderConfig())
        assertEquals(JsonArray(emptyList()), out["instances"])
        assertEquals(JsonArray(emptyList()), out["modelEntries"])
        assertEquals(JsonArray(emptyList()), out["modelGroups"])
        assertEquals(JsonObject(emptyMap()), out["sessionBindings"])
    }

    @Test
    fun `writing the defaults out changes nothing an Android restore reads back`() {
        val before = decode(fixture)
        val after = decode(BackupProviderWire.encode(before))
        // ProviderConfig.equals compares only its in-memory revision counter,
        // so the content is compared part by part.
        assertEquals(before.instances, after.instances)
        assertEquals(before.modelEntries, after.modelEntries)
        assertEquals(before.modelGroups, after.modelGroups)
        assertEquals(before.subAgents, after.subAgents)
        assertEquals(before.agentLoopModelEntryIds, after.agentLoopModelEntryIds)
        assertEquals(
            listOf(before.defaultPrimaryGroupId, before.voiceInputGroupId, before.voiceOutputGroupId),
            listOf(after.defaultPrimaryGroupId, after.voiceInputGroupId, after.voiceOutputGroupId),
        )
    }
}
