package com.openminis.app.backup

import com.openminis.app.ProductionSources
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [T-port-backup-secrets-defaults] `secrets.json` is written with `v` and
 * every section present, even when empty.
 *
 * iOS builds before e0fafc8cd decode BackupSecrets with the synthesized
 * Decodable, which requires every non-optional stored property - including
 * `var v: Int = 1`, whose default the synthesized decoder ignores. Android's
 * shared encoder omitted defaults, so a package from a phone without MCP OAuth
 * tokens failed to decode there as a whole and every provider key and env
 * var value was lost on restore.
 */
class BackupSecretsDefaultsTest {

    private val repoRoot: File by lazy {
        var dir: File? = ProductionSources.mainRoot()
        while (dir != null && !File(dir, "src/ios").isDirectory) dir = dir.parentFile
        requireNotNull(dir) { "repo root with src/ios not found" }
    }

    /**
     * Non-optional stored properties of `struct <name>` in iOS
     * BackupSecrets.swift - the keys a synthesized decoder requires.
     * Only the struct's own members are read (nested structs are skipped).
     */
    private fun iosRequiredKeys(structName: String): List<String> {
        val src = File(repoRoot, "src/ios/Agent/Backup/BackupSecrets.swift").readText()
        val start = src.indexOf("struct $structName: Codable {")
        require(start >= 0) { "struct $structName not found" }
        var depth = 0
        var i = src.indexOf('{', start)
        val bodyStart = i
        while (i < src.length) {
            when (src[i]) {
                '{' -> depth++
                '}' -> { depth--; if (depth == 0) break }
            }
            i++
        }
        // Keep only depth-1 text, so nested struct bodies / init bodies drop out.
        val body = StringBuilder()
        depth = 0
        for (c in src.substring(bodyStart + 1, i)) {
            if (c == '}') depth--
            if (depth == 0) body.append(c)
            if (c == '{') depth++
        }
        val prop = Regex("""^\s+(?:let|var) (\w+): ([^=/\n]+?)\s*(?:=.*)?(?://.*)?$""")
        return body.lines()
            .filter { !it.contains("{") } // computed properties
            .mapNotNull { prop.find(it) }
            .filter { !it.groupValues[2].trim().endsWith("?") }
            .map { it.groupValues[1] }
    }

    private fun parse(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    @Test
    fun `the iOS struct still has the four required sections this test relies on`() {
        assertEquals(listOf("v", "providers", "envVars", "mcpOAuth"), iosRequiredKeys("BackupSecrets"))
    }

    @Test
    fun `an env-only package writes v and every empty section`() {
        val text = BackupFormat.encodeSecrets(
            BackupSecrets(envVars = listOf(BackupSecrets.EnvVarSecret("ZZ_ON", "dmFsdWU="))),
        )
        val obj = parse(text)
        for (key in iosRequiredKeys("BackupSecrets")) assertTrue("$key missing in $text", key in obj)
        assertEquals(1, obj["v"]!!.jsonPrimitive.int)
        assertEquals(JsonArray(emptyList()), obj["providers"])
        assertEquals(JsonArray(emptyList()), obj["mcpOAuth"])
        assertEquals(1, obj["envVars"]!!.jsonArray.size)
    }

    @Test
    fun `a provider-only package writes every section and no null credentials`() {
        val text = BackupFormat.encodeSecrets(
            BackupSecrets(
                providers = listOf(
                    BackupSecrets.ProviderSecret(instanceId = "i1", label = "OpenAI", providerType = "openai", apiKey = "c2s="),
                ),
                mcpOAuth = listOf(BackupSecrets.MCPOAuthSecret(serverId = "s1", token = "dA==")),
            ),
        )
        val obj = parse(text)
        for (key in iosRequiredKeys("BackupSecrets")) assertTrue("$key missing in $text", key in obj)
        // Absent optional credentials stay absent (iOS omits them too), not "null".
        assertFalse(text, text.contains("null"))
        val provider = obj["providers"]!!.jsonArray[0].jsonObject
        for (key in iosRequiredKeys("ProviderSecret")) assertTrue("$key missing in $provider", key in provider)
        assertEquals(setOf("instanceId", "label", "providerType", "apiKey"), provider.keys)
        val mcp = obj["mcpOAuth"]!!.jsonArray[0].jsonObject
        for (key in iosRequiredKeys("MCPOAuthSecret")) assertTrue("$key missing in $mcp", key in mcp)
        assertEquals(setOf("serverId", "token"), mcp.keys)
    }

    @Test
    fun `the env var entry carries every key iOS requires`() {
        val obj = parse(BackupFormat.encodeSecrets(BackupSecrets(envVars = listOf(BackupSecrets.EnvVarSecret("K", "dg==")))))
        val entry = obj["envVars"]!!.jsonArray[0].jsonObject
        assertEquals(iosRequiredKeys("EnvVarSecret").toSet(), entry.keys)
    }

    @Test
    fun `round trip through the importer's parser is lossless`() {
        val secrets = BackupSecrets(
            providers = listOf(
                BackupSecrets.ProviderSecret(instanceId = "i1", apiKey = "a2V5", oauthEmail = "bUBleA=="),
            ),
            envVars = listOf(BackupSecrets.EnvVarSecret("A", "MQ=="), BackupSecrets.EnvVarSecret("B", "Mg==")),
            mcpOAuth = listOf(BackupSecrets.MCPOAuthSecret(serverId = "s", token = "dA==", clientSecret = "Yw==")),
        )
        val back = BackupFormat.json.decodeFromString(BackupSecrets.serializer(), BackupFormat.encodeSecrets(secrets))
        assertEquals(secrets, back)

        val empty = BackupSecrets()
        val emptyText = BackupFormat.encodeSecrets(empty)
        assertEquals("""{"v":1,"providers":[],"envVars":[],"mcpOAuth":[]}""", emptyText)
        assertEquals(empty, BackupFormat.json.decodeFromString(BackupSecrets.serializer(), emptyText))
    }

    @Test
    fun `the shared backup encoder is unchanged - only secrets json writes defaults`() {
        // Other members keep their compact shape; the defaults switch is scoped.
        assertEquals("""{"envVars":[{"name":"K","value":"dg=="}]}""",
            BackupFormat.json.encodeToString(BackupSecrets.serializer(),
                BackupSecrets(envVars = listOf(BackupSecrets.EnvVarSecret("K", "dg==")))))
    }

    @Test
    fun `the exporter writes secrets json through encodeSecrets`() {
        val exporter = ProductionSources.read("backup/BackupExporter.kt")
        assertTrue(exporter.contains("""File(staging, "secrets.json").writeText(BackupFormat.encodeSecrets(secrets))"""))
        assertFalse(exporter.contains("encodeToString(BackupSecrets.serializer()"))
    }
}
