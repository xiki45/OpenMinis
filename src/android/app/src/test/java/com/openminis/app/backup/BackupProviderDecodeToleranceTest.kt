package com.openminis.app.backup

import com.openminis.app.data.model.FallbackStrategy
import com.openminis.app.data.model.ModelGroup
import com.openminis.app.data.model.RoutingStrategy
import com.openminis.app.data.model.ThinkingLevel
import java.io.File
import java.util.zip.ZipFile
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * [B01 cross-platform read] What Android accepts in a `provider_config.json`
 * from another build or the other platform (shared rule table in the B01
 * cross-platform spec): a MISSING field takes its default -
 * `instances[].isEnabled` true, `appendV1Suffix` true (iOS's own default too),
 * `modelEntries[].isHidden` / `isCustom` false, `modelGroups[].strategy`
 * fallback, `fallbackStrategy` default, absent top-level arrays empty; iOS's
 * fallbackStrategy `limited` IS Android's `default`; thinking levels in any
 * case. A field that is present with the wrong type is still rejected as
 * before.
 */
class BackupProviderDecodeToleranceTest {

    private val sharedDir: File by lazy {
        var dir: File? = File(".").absoluteFile
        while (dir != null && !File(dir, "src/shared/backup-restore").isDirectory) dir = dir.parentFile
        File(dir ?: error("src/shared/backup-restore not found"), "src/shared/backup-restore")
    }

    private fun parse(text: String) = BackupImporter.parseProviderConfigLeniently(text)

    @Test
    fun `an iOS package decodes with its groups, members, thinking levels and strategies`() {
        // B01X part of a real iOS 1.16 (1) export, unchanged.
        val parsed = parse(File(sharedDir, "b01x_ios_v116_provider_config.json").readText())
        val c = parsed.config
        assertEquals(0, parsed.droppedInstances)
        assertEquals(listOf("B01X-P1"), c.instances.map { it.label })
        assertEquals(3, c.modelEntries.size)
        val m1 = c.modelEntries.single { it.baseModel.id == "b01x-m1" }
        assertTrue(m1.isHidden)
        assertEquals("Y", m1.overrides.displayName)

        val groups = c.modelGroups.associateBy { it.name }
        assertEquals(setOf("B01X-G-CODEX", "B01X-G-NEW", "B01X-G-SAME"), groups.keys)
        val codex = groups.getValue("B01X-G-CODEX")
        assertEquals(listOf("b01x-m1", "b01x-m2"), codex.memberEntryIds.map { it.substringAfterLast('/') })
        assertEquals(ThinkingLevel.HIGH, codex.defaultThinkingLevel)
        assertEquals(RoutingStrategy.fallback, codex.strategy)
        assertEquals(FallbackStrategy.default, codex.fallbackStrategy) // iOS "limited"
        val same = groups.getValue("B01X-G-SAME")
        assertEquals(FallbackStrategy.always, same.fallbackStrategy)
        assertEquals(ThinkingLevel.MEDIUM, same.defaultThinkingLevel)
        assertEquals(64000, same.contextLimitTokens)
        assertEquals(2, groups.getValue("B01X-G-NEW").memberEntryIds.size)
    }

    @Test
    fun `iOS's limited is Android's default without relying on coerceInputValues`() {
        // A decoder that does not coerce unknown enum values: "limited" must be
        // a name of `default`, not an accident of coercion.
        val strict = Json { ignoreUnknownKeys = true }
        val group = strict.decodeFromString(
            ModelGroup.serializer(),
            """{"id":"g","name":"B01X-G","memberEntryIds":[],"strategy":"fallback","fallbackStrategy":"limited"}""",
        )
        assertEquals(FallbackStrategy.default, group.fallbackStrategy)
        // Android still writes its own name for the device's config.
        assertEquals(
            "\"default\"",
            Json.encodeToString(FallbackStrategy.serializer(), FallbackStrategy.default),
        )
    }

    @Test
    fun `missing fields take their defaults`() {
        val c = parse(
            """{"instances":[{"id":"i","label":"B01X-P1","providerType":"openAI","credentialType":"apiKey","createdAt":"2026-10-16T04:31:29Z"}],
               "modelEntries":[{"providerInstanceId":"i","model":{"id":"b01x-m1","displayName":"M1","provider":"OpenAI"}}],
               "modelGroups":[{"id":"g","name":"B01X-G","memberEntryIds":["i/b01x-m1"]}]}""",
        ).config
        val i = c.instances.single()
        assertTrue(i.isEnabled)
        assertTrue(i.appendV1Suffix)
        val e = c.modelEntries.single()
        assertFalse(e.isHidden)
        assertFalse(e.isCustom)
        val g = c.modelGroups.single()
        assertEquals(RoutingStrategy.fallback, g.strategy)
        assertEquals(FallbackStrategy.default, g.fallbackStrategy)
        assertEquals(listOf("i/b01x-m1"), g.memberEntryIds)

        val empty = parse("{}").config
        assertTrue(empty.instances.isEmpty() && empty.modelEntries.isEmpty() && empty.modelGroups.isEmpty())
    }

    @Test
    fun `thinking levels are read in any case`() {
        for (raw in listOf("high", "HIGH", "High")) {
            val g = parse("""{"modelGroups":[{"id":"g","name":"B01X-G","memberEntryIds":[],"defaultThinkingLevel":"$raw"}]}""")
                .config.modelGroups.single()
            assertEquals(raw, ThinkingLevel.HIGH, g.defaultThinkingLevel)
        }
    }

    @Test
    fun `a field present with the wrong type is still rejected`() {
        val parsed = parse(
            """{"instances":[
                 {"id":"bad","label":"B01X-BAD","providerType":"openAI","credentialType":"apiKey","createdAt":"2026-10-16T04:31:29Z","isEnabled":"yes"},
                 {"id":"ok","label":"B01X-OK","providerType":"openAI","credentialType":"apiKey","createdAt":"2026-10-16T04:31:29Z"}]}""",
        )
        assertEquals(1, parsed.droppedInstances)
        assertEquals(listOf("ok"), parsed.config.instances.map { it.id })
    }

    // -- the real packages of the B01 cross-device test, when present --------
    // They hold real provider names and addresses (no keys), so they are read
    // from /tmp at test time only and never copied into the repository; only
    // counts and B01X test data are asserted.

    private fun providerConfigIn(pkg: File): String =
        ZipFile(pkg).use { zip ->
            zip.getInputStream(zip.getEntry("data/provider_config.json")).bufferedReader().readText()
        }

    private fun groupCount(text: String) =
        BackupFormat.json.parseToJsonElement(text).jsonObject["modelGroups"]?.jsonArray?.size ?: 0

    @Test
    fun `the real iOS package of the cross-device test decodes whole`() {
        val pkg = File("/tmp/xcase/ios_ACEG.minisbak")
        assumeTrue("real package not on this machine", pkg.isFile)
        val text = providerConfigIn(pkg)
        val raw = BackupFormat.json.parseToJsonElement(text).jsonObject
        val parsed = parse(text)
        assertEquals(0, parsed.droppedInstances)
        assertEquals(raw["instances"]!!.jsonArray.size, parsed.config.instances.size)
        assertEquals(groupCount(text), parsed.config.modelGroups.size)
        assertEquals(raw["modelEntries"]!!.jsonArray.size, parsed.config.modelEntries.size)
        val b01x = parsed.config.modelGroups.filter { it.name.startsWith("B01X-") }
        assertEquals(3, b01x.size)
        assertTrue(b01x.all { it.memberEntryIds.isNotEmpty() })
    }

    @Test
    fun `the real pre-fix Android package of the cross-device test decodes whole`() {
        val pkg = File("/tmp/xcase/android_BDFH.minisbak")
        assumeTrue("real package not on this machine", pkg.isFile)
        val text = providerConfigIn(pkg)
        val raw = BackupFormat.json.parseToJsonElement(text).jsonObject
        val parsed = parse(text)
        assertEquals(0, parsed.droppedInstances)
        assertEquals(raw["instances"]!!.jsonArray.size, parsed.config.instances.size)
        assertEquals(groupCount(text), parsed.config.modelGroups.size)
        val codex = parsed.config.modelGroups.single { it.name == "B01X-G-CODEX" }
        assertEquals(RoutingStrategy.fallback, codex.strategy)
        assertEquals(ThinkingLevel.HIGH, codex.defaultThinkingLevel)
        assertEquals("221762f2-7d8b-47bd-a553-bbb284d27731", raw["instances"]!!.jsonArray
            .map { it.jsonObject }.single { it["label"]!!.jsonPrimitive.content == "B01X-P1" }["id"]!!.jsonPrimitive.content)
    }
}
