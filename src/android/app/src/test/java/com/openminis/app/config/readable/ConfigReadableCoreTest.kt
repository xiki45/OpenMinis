package com.openminis.app.config.readable

import com.openminis.app.config.ConfigValue
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-config-readable] Pure logic of the readable config descriptions: list
 * diff, placeholders, rendering with the bad-translation fallback, secret and
 * image masking, URL scrubbing, number formats and the persisted JSON. Same
 * cases as iOS MinisTests/Standalone/ConfigReadableTests.swift [1].
 */
class ConfigReadableCoreTest {

    private fun ids(items: List<ReadableCore.ListItem>) =
        items.joinToString(",") { if (it.count > 1) "${it.id}x${it.count}" else it.id }

    private fun d(o: List<String>, n: List<String>, ordered: Boolean = true) = ReadableCore.listDiff(o, n, ordered)

    private fun row(x: ReadableCore.ListDiff) =
        "${x.newFirst ?: "-"}|${ids(x.added)}|${ids(x.removed)}|${x.reordered}|${x.keptCount}"

    // -- list diff --

    @Test
    fun `reorder only reads as a new first and a reorder`() {
        assertEquals("c|||true|3", row(d(listOf("a", "b", "c"), listOf("c", "a", "b"))))
    }

    @Test
    fun `a model moved to the top plus a duplicate removed`() {
        assertEquals("n|n|bx2|false|2", row(d(listOf("a", "b", "b", "c"), listOf("n", "a", "c"))))
    }

    @Test
    fun `append is an addition and never a reorder`() {
        assertEquals("-|c||false|2", row(d(listOf("a", "b"), listOf("a", "b", "c"))))
    }

    @Test
    fun `removing the first element changes the primary but is not a reorder`() {
        assertEquals("b||a|false|2", row(d(listOf("a", "b", "c"), listOf("b", "c"))))
    }

    @Test
    fun `identical, from-empty and to-empty lists`() {
        assertTrue(d(listOf("a", "b"), listOf("a", "b")).isEmpty)
        assertEquals("a|a||false|0", row(d(emptyList(), listOf("a"))))
        assertEquals("-||a,b|false|0", row(d(listOf("a", "b"), emptyList())))
    }

    @Test
    fun `unordered lists never report a new first but still show a reorder`() {
        val x = d(listOf("a", "b", "c"), listOf("c", "b", "a"), ordered = false)
        assertNull(x.newFirst)
        assertTrue(x.reordered)
    }

    @Test
    fun `duplicates count`() {
        val x = d(listOf("a", "a", "a"), listOf("a"))
        assertEquals("ax2", ids(x.removed))
        assertFalse(x.reordered)
    }

    // -- placeholders and rendering --

    @Test
    fun `placeholder counts`() {
        val counts = listOf(
            "%@: %@ → %@", "%1\$@: %2\$@ → %3\$@", "100%% done %@", "no args", "%d bad",
            "%1\$@ and %@ mixed", "\$%1\$@ – %2\$@",
            // Android resource placeholders count the same way.
            "%1\$s: %2\$s", "%s items",
        ).map(ReadableCore::placeholderCount)
        assertEquals(listOf(3, 3, 1, 0, -1, -1, 2, 2, 1), counts)
    }

    private val zh = mapOf(
        "%1\$@: %2\$@ → %3\$@" to "%1\$s：%2\$s → %3\$s",
        "Group “%1\$@” – %2\$@" to "分组「%1\$s」的%2\$s",
        "Default thinking level" to "默认思考强度",
        "High" to "高", "Med" to "中",
        "; " to "；", ", " to "、",
        "added %@" to "新增 %1\$s",
        // A translation with an extra placeholder.
        "broken %1\$@" to "坏的 %1\$s %2\$s",
        "positional %1\$@ then %2\$@" to "先 %2\$s 后 %1\$s",
    )
    private val loc: (String) -> String? = { zh[it] }

    private val line = ReadableLine(
        "%1\$@: %2\$@ → %3\$@",
        listOf(
            ReadableArg.Line(
                ReadableLine(
                    "Group “%1\$@” – %2\$@",
                    listOf(ReadableArg.Text("Light Models"), ReadableArg.Key("Default thinking level")),
                ),
            ),
            ReadableArg.Key("High"),
            ReadableArg.Key("Med"),
        ),
    )

    @Test
    fun `renders a nested sentence in Chinese and in English`() {
        assertEquals("分组「Light Models」的默认思考强度：高 → 中", ReadableCore.render(line, loc))
        assertEquals(
            "Group “Light Models” – Default thinking level: High → Med",
            ReadableCore.render(line) { null },
        )
    }

    @Test
    fun `clauses and lists use the localized separators`() {
        val l = ReadableLine(
            "%1\$@: %2\$@",
            listOf(
                ReadableArg.Key("Models"),
                ReadableArg.Clauses(
                    listOf(
                        ReadableArg.Line(
                            ReadableLine("added %@", listOf(ReadableArg.Items(listOf(ReadableArg.Text("A"), ReadableArg.Text("B"))))),
                        ),
                        ReadableArg.Key("order changed"),
                    ),
                ),
            ),
        )
        assertEquals("Models: 新增 A、B；order changed", ReadableCore.render(l, loc))
    }

    @Test
    fun `a bad translation or an argument mismatch never crashes`() {
        assertEquals("broken X", ReadableCore.render(ReadableLine("broken %1\$@", listOf(ReadableArg.Text("X"))), loc))
        assertEquals(
            "先 B 后 A",
            ReadableCore.render(
                ReadableLine("positional %1\$@ then %2\$@", listOf(ReadableArg.Text("A"), ReadableArg.Text("B"))),
                loc,
            ),
        )
        assertEquals("100% %@ %d", ReadableCore.render(ReadableLine("%@", listOf(ReadableArg.Text("100% %@ %d"))), loc))
        assertEquals(
            "%@ and %@ only one",
            ReadableCore.render(ReadableLine("%@ and %@", listOf(ReadableArg.Text("only one"))), loc),
        )
    }

    // -- redaction --

    @Test
    fun `secrets and inline images are masked`() {
        fun field(leaf: String, v: String) = (RedactedConfigValue.field(leaf, ConfigValue.Str(v)).value as ConfigValue.Str).value
        assertEquals("••• (hidden)", field("apiKey", "sk-live-SECRET-123"))
        assertEquals("\$\$OPENAI_KEY", field("apiKey", "\$\$OPENAI_KEY"))
        assertEquals("sk-visible-label", field("label", "sk-visible-label"))
        val png = "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAAB"
        assertEquals("<image>", field("icon", png))
        val payload = RedactedConfigValue.payload(
            ConfigValue.Obj(
                mapOf(
                    "label" to ConfigValue.Str("P"),
                    "apiKey" to ConfigValue.Str("sk-live-SECRET-456"),
                    "nested" to ConfigValue.Obj(
                        mapOf("oauthToken" to ConfigValue.Str("tok-SECRET-789"), "img" to ConfigValue.Str(png)),
                    ),
                ),
            ),
        ).value.let(::strings).joinToString("|")
        assertFalse(payload, payload.contains("SECRET"))
        assertFalse(payload, payload.contains("iVBOR"))
        assertTrue(payload, payload.contains("••• (hidden)") && payload.contains("<image>"))
    }

    private fun strings(v: ConfigValue): List<String> = when (v) {
        is ConfigValue.Str -> listOf(v.value)
        is ConfigValue.Arr -> v.value.flatMap(::strings)
        is ConfigValue.Obj -> v.value.values.flatMap(::strings)
        else -> emptyList()
    }

    // -- helpers --

    @Test
    fun `urls lose user-info, query and fragment`() {
        assertEquals("https://api.example.com/v1", ReadableCore.safeURL("https://user:pw@api.example.com/v1?key=SECRET#frag"))
        assertEquals("api.example.com/v1", ReadableCore.safeURL("api.example.com/v1?token=SECRET"))
        assertEquals("http://localhost:8080/v1", ReadableCore.safeURL("http://localhost:8080/v1?x=1"))
    }

    @Test
    fun `line deltas, counts, numbers and quotes`() {
        assertEquals(2 to 1, ReadableCore.lineDelta("a\nb\nc", "a\nc\nd\ne"))
        assertEquals(
            "64K,128K,1M,2M,1,500,999",
            listOf(64000, 131072, 1_048_576, 2_000_000, 1500, 999).joinToString(",") { ReadableCore.compactCount(it) },
        )
        assertEquals("1,0.5,1.25,0.33", listOf(1.0, 0.5, 1.25, 0.333).joinToString(",") { ReadableCore.plainNumber(it) })
        assertEquals("“line one lin…”", ReadableCore.quoted("line one\nline two that is long", 12))
    }

    // -- persisted JSON --

    @Test
    fun `summary JSON round-trips and old or garbage rows decode to null`() {
        val sum = ReadableSummary(locale = "zh-Hans", text = "t", lines = listOf(line), subject = "groups:G1")
        assertEquals(sum, ReadableSummary.decode(sum.jsonString()))
        val global = sum.copy(subject = null)
        assertEquals(global, ReadableSummary.decode(global.jsonString()))
        assertNull(ReadableSummary.decode(null))
        assertNull(ReadableSummary.decode(""))
        assertNull(ReadableSummary.decode("{not json"))
    }

    @Test
    fun `summary JSON has the iOS Codable shape`() {
        // What iOS JSONEncoder writes for the same ReadableSummary; a record
        // must decode the same on both platforms.
        val ios = """
            {"v":1,"locale":"zh-Hans","text":"t","subject":"groups:G1","lines":[
              {"key":"%1${'$'}@: %2${'$'}@","args":[
                {"key":{"_0":"Models"}},
                {"clauses":{"_0":[
                  {"line":{"_0":{"key":"added %@","args":[{"list":{"_0":[{"text":{"_0":"A"}}]}}]}}},
                  {"key":{"_0":"order changed"}}]}}]}]}
        """.trimIndent()
        val decoded = ReadableSummary.decode(ios)!!
        assertEquals("groups:G1", decoded.subject)
        assertEquals("Models: added A; order changed", decoded.displayText { null })
        // Re-encoding yields the same structure.
        assertEquals(JSONObject(ios).similar(JSONObject(decoded.jsonString())), true)
    }

    @Test
    fun `display text re-renders from lines and falls back to text`() {
        val sum = ReadableSummary(locale = "en", text = "frozen", lines = listOf(line), subject = null)
        assertEquals("分组「Light Models」的默认思考强度：高 → 中", sum.displayText(loc))
        assertEquals("frozen", sum.copy(lines = emptyList()).displayText(loc))
    }

    @Test
    fun `generic opt-out is flagged and a real formatter is not`() {
        assertTrue(ConfigReadable.generic("r").isGeneric)
        assertTrue(ConfigReadable.notWritable.isGeneric)
        assertFalse(ConfigReadable(ConfigValueFormatter.Bool()).isGeneric)
    }
}
