package com.openminis.app.config.readable

import com.openminis.app.config.ConfigSchema
import com.openminis.app.config.ConfigValue
import com.openminis.app.config.fields.ClosureField
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [T-config-readable] Sentences the renderer builds per formatter, against a
 * fake name source and the English keys (no Android resources). Covers the
 * list-diff clauses, secrets, long text, numbers, images, the DNS string list
 * and the generic fallback.
 */
class ConfigReadableRendererTest {

    private val fakeNames = object : ReadableNameSource {
        val entries = mapOf("i1/a" to "Model A (P1)", "i1/b" to "Model B (P1)", "i2/a" to "Model A (P2)")
        override fun modelEntryLabel(id: String) = entries[id]
        override fun instanceLabel(id: String) = mapOf("i1" to "P1", "i2" to "P2", "i9" to "Gone Inc")[id]
        override fun groupName(id: String) = mapOf("g1" to "Fast", "g2" to "Default Model")[id]
        override fun thinkingRuleLabel(instanceId: String, ruleId: String) = null
        override fun groupsContaining(entryId: String) = emptyList<String>()
        override fun modelCountOf(instanceId: String) = 0
    }

    @Before fun setUp() { ConfigRefResolver.names = fakeNames }
    @After fun tearDown() { ConfigRefResolver.names = object : ReadableNameSource by fakeNames {} }

    private fun field(path: String, readable: ConfigReadable, displayName: String = "Field") = ClosureField(
        path = path, displayName = displayName, description = "", valueSchema = ConfigSchema.Json,
        readable = readable, reader = { ConfigValue.Null }, writer = {},
    )

    private fun text(path: String, spec: ConfigReadable, old: ConfigValue, new: ConfigValue, name: String = "Field"): String {
        val leaf = path.substringAfterLast('.')
        val f = field(path, spec, name)
        return ConfigReadableRenderer.forFieldWrite(f, RedactedConfigValue.field(leaf, old), RedactedConfigValue.field(leaf, new)).text
    }

    private fun strs(vararg s: String) = ConfigValue.Arr(s.map { ConfigValue.Str(it) })

    @Test
    fun `group entries read as primary, added, removed and order clauses`() {
        val spec = ConfigReadable(ConfigValueFormatter.RefList(ConfigRefKind.ModelEntry, ordered = true), label = "Models")
        assertEquals(
            "Models: primary is now Model B (P1); added Model B (P1); removed Model A (P2); rest of the order unchanged",
            text("defaults.x", spec, strs("i1/a", "i2/a"), strs("i1/b", "i1/a")),
        )
        assertEquals("Models: order changed", text("defaults.x", spec, strs("i1/a", "i1/b", "i2/a"), strs("i1/a", "i2/a", "i1/b")))
        assertEquals("Models: unchanged", text("defaults.x", spec, strs("i1/a"), strs("i1/a")))
        assertEquals("Models: removed Model A (P1); now empty", text("defaults.x", spec, strs("i1/a"), strs()))
    }

    @Test
    fun `unknown ids stay named`() {
        val spec = ConfigReadable(ConfigValueFormatter.RefList(ConfigRefKind.ModelEntry, ordered = false))
        assertEquals(
            "Field: added unknown model gone-model (Gone Inc), unknown model abcdef12…",
            text("defaults.x", spec, strs(), strs("i9/gone-model", "abcdef1234567890")),
        )
    }

    @Test
    fun `duplicates and long lists are counted`() {
        val spec = ConfigReadable(ConfigValueFormatter.StringList(ordered = false))
        assertEquals("Field: removed a ×2", text("x.y", spec, strs("a", "a", "b"), strs("b")))
        assertEquals(
            "Field: added a, b, c, d, e and 2 more",
            text("x.y", spec, strs(), strs("a", "b", "c", "d", "e", "f", "g")),
        )
    }

    @Test
    fun `DNS servers stored as one string still diff as a list`() {
        val spec = ConfigReadable(
            ConfigValueFormatter.StringList(ordered = true, splitText = com.openminis.app.config.fields.ContainerDnsFields::splitServers),
            label = "Container custom DNS servers",
        )
        assertEquals(
            "Container custom DNS servers: added 9.9.9.9; removed 8.8.8.8; rest of the order unchanged",
            text("network.dnsCustomServers", spec, ConfigValue.Str("1.1.1.1\n8.8.8.8"), strs("1.1.1.1", "9.9.9.9")),
        )
        assertEquals(
            "Container custom DNS servers: first is now 223.5.5.5; added 223.5.5.5; rest of the order unchanged",
            text("network.dnsCustomServers", spec, ConfigValue.Str("1.1.1.1"), ConfigValue.Str("223.5.5.5, 1.1.1.1")),
        )
    }

    @Test
    fun `a secret never renders its value`() {
        val spec = ConfigReadable(ConfigValueFormatter.Secret)
        val literal = text("providers.p.apiKey", spec, ConfigValue.Str(""), ConfigValue.Str("sk-live-SECRET-1"), "API Key")
        assertEquals("API Key: updated (value hidden)", literal)
        assertFalse(literal.contains("SECRET"))
        assertEquals(
            "API Key: now uses the environment variable \$OPENAI_KEY",
            text("providers.p.apiKey", spec, ConfigValue.Str(""), ConfigValue.Str("\$\$OPENAI_KEY"), "API Key"),
        )
    }

    @Test
    fun `long text reports line counts, never content`() {
        val spec = ConfigReadable(ConfigValueFormatter.LongText, label = "Soul personality body")
        val t = text("soul.body", spec, ConfigValue.Str("a\nb\nc"), ConfigValue.Str("a\nc\nd\ne"))
        assertEquals("Soul personality body: edited (+2 / −1 lines, 7 characters now)", t)
        assertEquals("Soul personality body: cleared", text("soul.body", spec, ConfigValue.Str("a"), ConfigValue.Str("")))
        assertEquals("Soul personality body: unchanged", text("soul.body", spec, ConfigValue.Str("a"), ConfigValue.Str("a")))
    }

    @Test
    fun `values read through their labels and units`() {
        assertEquals(
            "Theme: System → Dark",
            text("appearance.theme", ConfigReadable(ConfigValueFormatter.EnumLabels(mapOf("system" to "System", "dark" to "Dark"))), ConfigValue.Str("system"), ConfigValue.Str("dark"), "Theme"),
        )
        assertEquals(
            "Field: No limit → 64K tokens",
            text("x.y", ConfigReadable(ConfigValueFormatter.Number(NumberUnit.TOKENS, zero = "No limit")), ConfigValue.Int(0), ConfigValue.Int(64000)),
        )
        assertEquals(
            "Visibility: Shown → Hidden",
            text("x.y", ConfigReadable(ConfigValueFormatter.Bool(on = "Hidden", off = "Shown"), label = "Visibility"), ConfigValue.Bool(false), ConfigValue.Bool(true)),
        )
        assertEquals(
            "Field: “Minis” (unchanged)",
            text("x.y", ConfigReadable(ConfigValueFormatter.Text()), ConfigValue.Str("Minis"), ConfigValue.Str("Minis")),
        )
        assertEquals(
            "Field: Default → https://api.example.com/v1",
            text("x.y", ConfigReadable(ConfigValueFormatter.Url()), ConfigValue.Str(""), ConfigValue.Str("https://u:p@api.example.com/v1?key=SECRET")),
        )
        assertEquals(
            "Field: Not set → group “Fast”",
            text("x.y", ConfigReadable(ConfigValueFormatter.Ref(ConfigRefKind.ModelSource)), ConfigValue.Str(""), ConfigValue.Str("group:g1")),
        )
    }

    @Test
    fun `the soul icon names an emoji but never an image payload or path`() {
        val spec = ConfigReadable(ConfigValueFormatter.Image)
        assertEquals("Field: Default icon → ⚡", text("soul.icon", spec, ConfigValue.Str(""), ConfigValue.Str("⚡")))
        assertEquals(
            "Field: ⚡ → Custom image",
            text("soul.icon", spec, ConfigValue.Str("⚡"), ConfigValue.Str("data:image/png;base64,iVBORw0KGgo")),
        )
        val path = text("soul.icon", spec, ConfigValue.Str("<image>"), ConfigValue.Str("minis://attachments/icon.png"))
        assertEquals("Field: Custom image (unchanged)", path)
        val bare = text("soul.icon", spec, ConfigValue.Str(""), ConfigValue.Str("iVBORw0KGgoAAAANSUhEUgAAAAEAAAAB"))
        assertEquals("Field: Default icon → Custom image", bare)
    }

    @Test
    fun `an unexpected shape or a generic field falls back to the generic sentence`() {
        assertEquals(
            "Field: 2 items → On",
            text("x.y", ConfigReadable(ConfigValueFormatter.Text()), strs("a", "b"), ConfigValue.Bool(true)),
        )
        assertEquals(
            "Field: “a” → (empty)",
            text("x.y", ConfigReadable.generic("test"), ConfigValue.Str("a"), ConfigValue.Str("")),
        )
        // A formatter that throws must not block the write.
        val boom = ConfigReadable(ConfigValueFormatter.Sentence { _, _ -> error("boom") })
        assertEquals("Field: (none) → 3", text("x.y", boom, ConfigValue.Null, ConfigValue.Int(3)))
    }

    @Test
    fun `summaries carry their lines for re-rendering in another language`() {
        val f = field("appearance.theme", ConfigReadable(ConfigValueFormatter.EnumLabels(mapOf("dark" to "Dark", "light" to "Light"))), "Theme")
        val s = ConfigReadableRenderer.forFieldWrite(
            f, RedactedConfigValue.field("theme", ConfigValue.Str("light")), RedactedConfigValue.field("theme", ConfigValue.Str("dark")),
        )
        val zh = mapOf("%1\$@: %2\$@ → %3\$@" to "%1\$s：%2\$s → %3\$s", "Theme" to "主题", "Light" to "浅色", "Dark" to "深色")
        assertEquals("主题：浅色 → 深色", s.displayText { zh[it] })
        assertTrue(s.subject == null)
    }

    @Test
    fun `launch validation runs off the main thread`() {
        // ConfigRegistry.init runs in Application.onCreate; expanding a
        // collection child reads the database, so the check must not block.
        val init = com.openminis.app.ProductionSources.read("config/ConfigRegistry.kt").substringAfter("fun init(")
        val call = init.indexOf("validateRegistry(r.allFieldsForValidation())")
        assertTrue(call > 0)
        assertTrue(init.substring(0, call).substringAfterLast("registerInto(").contains("Thread({"))
    }
}
