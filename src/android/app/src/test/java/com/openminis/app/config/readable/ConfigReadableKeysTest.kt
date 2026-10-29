package com.openminis.app.config.readable

import com.openminis.app.ProductionSources
import com.openminis.app.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [T-config-readable] Every label / sentence key the config sources use has a
 * string resource, looked up through ConfigReadableStrings, with zh and zh-TW
 * translations whose placeholders match the English. Port of iOS
 * ConfigReadableTests [5]. The extraction follows the shapes keys are written
 * in (ReadableLine("…"), ReadableArg.Key("…"), label = / on = / off = /
 * zero = / empty =, label maps, *Key constants, subject formats, unknown(…),
 * writable fields' displayName) — iOS once shipped three untranslated keys
 * because its extractor missed a shape, so the regression check below pins
 * those.
 */
class ConfigReadableKeysTest {

    private val configRoot = File(ProductionSources.mainRoot(), "config")
    private val res = File(ProductionSources.mainRoot()!!.parentFile.parentFile.parentFile.parentFile, "res")
    private val str = """"((?:[^"\\]|\\.)*)""""

    private fun extract(text: String): Set<String> {
        val keys = HashSet<String>()
        val single = listOf(
            """ReadableLine\(\s*$str""", """ReadableArg\.Key\(\s*$str""", """\blabel = $str""",
            """\b(?:on|off|zero|empty) = $str""", """readableSubjectFormat: String get\(\) = $str""",
            """const val \w+Key = $str""", """\b(?:on|off|zero|empty|label): String\?? = $str""",
            """displayName: String get\(\) = $str""", """\bunknown\(\s*$str""",
        )
        for (p in single) Regex(p).findAll(text).forEach { keys.add(it.groupValues[1]) }
        val pair = Regex("""$str\s+to\s+$str""")
        val blocks = Regex("""(?:[Ll]abels|levels|valueLabels)\b[^=\n]*=\s*(?:mapOf|linkedMapOf)\((.*?)\)(?:\s*\+|\n)""", RegexOption.DOT_MATCHES_ALL)
            .findAll(text).map { it.groupValues[1] } +
            Regex("""EnumLabels\(\s*mapOf\((.*?)\)\s*\)""", RegexOption.DOT_MATCHES_ALL).findAll(text).map { it.groupValues[1] }
        for (b in blocks) pair.findAll(b).forEach { keys.add(it.groupValues[2]) }
        Regex("""\+ \($str to $str\)""").findAll(text).forEach { keys.add(it.groupValues[2]) }
        // A writable field's label defaults to its displayName, unless its
        // readable spec names its own label.
        Regex("""(\w+)\(\s*\n\s*path = [^\n]*\n(?:[^\n]*\n)?\s*displayName = $str""").findAll(text).forEach { m ->
            if (m.groupValues[1] in setOf("ReadOnlyField", "HiddenField")) return@forEach
            val tail = text.substring(m.range.last + 1).take(4000)
            val body = if ("reader =" in tail) tail.substringBefore("reader =") else tail.take(300)
            if (Regex("""readable = [\s\S]*\blabel = """).containsMatchIn(body)) return@forEach
            keys.add(m.groupValues[2])
        }
        return keys.map { it.replace("\\$", "$").replace("\\\"", "\"") }.filter { it.isNotEmpty() }.toSet()
    }

    private val usedKeys: Set<String> by lazy {
        configRoot.walkTopDown().filter { it.extension == "kt" }.flatMap { extract(it.readText()) }.toSet()
    }

    /** name → value of every string in one locale's strings_config_readable.xml. */
    private fun strings(dir: String): Map<String, String> {
        val f = File(res, "$dir/strings_config_readable.xml")
        if (!f.exists()) return emptyMap()
        return Regex("""<string name="([^"]+)">(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(f.readText()).associate { it.groupValues[1] to it.groupValues[2] }
    }

    private fun resourceName(id: Int): String =
        R.string::class.java.fields.first { it.getInt(null) == id }.name

    @Test
    fun `extraction finds a meaningful key set`() {
        assertTrue("extracted ${usedKeys.size}", usedKeys.size > 120)
    }

    @Test
    fun `REGRESSION - the primary-model clause and both separators are covered`() {
        assertTrue(usedKeys.containsAll(listOf("primary is now %@", "first is now %@", ", ", "; ")))
    }

    @Test
    fun `every used key maps to a string resource`() {
        val missing = usedKeys - ConfigReadableStrings.ids.keys
        assertEquals("keys with no entry in ConfigReadableStrings", emptySet<String>(), missing)
    }

    @Test
    fun `every mapped key has its English text and zh + zh-TW translations`() {
        val base = strings("values")
        val zh = strings("values-zh")
        val tw = strings("values-zh-rTW")
        val problems = ArrayList<String>()
        for ((key, id) in ConfigReadableStrings.ids) {
            val name = resourceName(id)
            if (name !in base) problems.add("$name ($key) not in values")
            if (name !in zh) problems.add("$name ($key) not in values-zh")
            if (name !in tw) problems.add("$name ($key) not in values-zh-rTW")
            // The English resource is the key with Android placeholders.
            val expected = ReadableCore.placeholderCount(key)
            base[name]?.let { v -> if (ReadableCore.placeholderCount(unescape(v)) != expected) problems.add("$name English placeholders") }
        }
        assertEquals(emptyList<String>(), problems)
    }

    @Test
    fun `translations keep every placeholder`() {
        val base = strings("values")
        val dirs = res.listFiles { f -> f.isDirectory && f.name.startsWith("values-") }!!.map { it.name }
        val problems = ArrayList<String>()
        for (dir in dirs) {
            for ((name, v) in strings(dir)) {
                val en = base[name]
                if (en == null) {
                    problems.add("$dir/$name has no English")
                    continue
                }
                if (ReadableCore.placeholderCount(unescape(v)) != ReadableCore.placeholderCount(unescape(en))) {
                    problems.add("$dir/$name")
                }
                if (Regex("""(?<!\\)'""").containsMatchIn(v)) problems.add("$dir/$name unescaped apostrophe")
            }
        }
        assertEquals(emptyList<String>(), problems)
    }

    private fun unescape(v: String) = v.removeSurrounding("\"").replace("\\'", "'").replace("\\\"", "\"")
}
