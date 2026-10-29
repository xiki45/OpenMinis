package com.openminis.app.usage

import com.openminis.app.data.model.LLMUsage
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.DayOfWeek
import java.time.OffsetDateTime
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * [T-usage-step1] Runs the SHARED usage vectors (src/shared/usage, the file iOS
 * loads too) through the Android usage layer: provider parsing and stream
 * merging, the lenient stored-JSON parser, model-name normalization, and the
 * report builder. Every field present in a case's `expected` is compared; the
 * expected values are hand-computed in the file, not produced by either app.
 */
class UsageVectorsTest {

    // Gradle runs unit tests with the module dir (src/android/app) as CWD.
    private val vectorsFile: File = sequenceOf(
        File("../shared/usage/usage_test_vectors.json"),     // CWD = src/android
        File("../../shared/usage/usage_test_vectors.json"),  // CWD = src/android/app
    ).firstOrNull { it.isFile } ?: File("../../shared/usage/usage_test_vectors.json")

    private val cases: JSONArray by lazy { JSONObject(vectorsFile.readText()).getJSONArray("cases") }

    @Test
    fun `all shared usage vectors pass`() {
        val failures = mutableListOf<String>()
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val name = c.getString("name")
            val errs = mutableListOf<String>()
            when (val kind = c.getString("kind")) {
                "parse_provider" -> checkProvider(c, errs)
                "parse_stored" -> checkStored(c, errs)
                "model_key" -> checkModelKey(c, errs)
                "report" -> checkReport(c, errs)
                else -> errs += "unknown kind $kind"
            }
            println("[UsageVectors] ${if (errs.isEmpty()) "PASS" else "FAIL"} $name")
            if (errs.isNotEmpty()) failures += "$name:\n  " + errs.joinToString("\n  ")
        }
        assertTrue("${failures.size} of ${cases.length()} failed:\n" + failures.joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun `the vector file holds the 44 cases this suite was written against`() {
        assertEquals(44, cases.length())
    }

    // ---- parse_provider -------------------------------------------------

    private fun checkProvider(c: JSONObject, errs: MutableList<String>) {
        val protocol = c.getJSONObject("options").getString("protocol")
        val parse: (JSONObject) -> UsageChunk = when (protocol) {
            "anthropic" -> ProviderUsageParser::anthropic
            "openai_chat" -> ProviderUsageParser::openAIChat
            "openai_responses" -> ProviderUsageParser::openAIResponses
            "gemini" -> ProviderUsageParser::gemini
            else -> { errs += "unknown protocol $protocol"; return }
        }
        val records = c.getJSONArray("records")
        var chunk: UsageChunk? = null
        var llm: LLMUsage? = null
        for (i in 0 until records.length()) {
            val next = parse(records.getJSONObject(i))
            chunk = UsageChunkMerger.merge(chunk, next)
            llm = UsageChunkMerger.merge(llm, next.toLLMUsage())
        }
        val e = c.getJSONObject("expected")
        val m = chunk!!
        eq(errs, "input", e.getLong("input"), m.input ?: 0)
        eq(errs, "cacheRead", e.getLong("cacheRead"), m.cacheRead ?: 0)
        eq(errs, "cacheWrite", e.getLong("cacheWrite"), m.cacheWrite ?: 0)
        eq(errs, "output", e.getLong("output"), m.output ?: 0)
        eq(errs, "reasoning", optLong(e, "reasoning"), m.reasoning)
        eq(errs, "cacheReported", e.getBoolean("cacheReported"), m.cacheKeyPresent)
        // The same fold through the LLMUsage path the stream consumer uses.
        val u = llm!!
        eq(errs, "llm.input", e.getLong("input"), u.inputTokens.toLong())
        eq(errs, "llm.output", e.getLong("output"), u.outputTokens.toLong())
        eq(errs, "llm.cacheRead", e.getLong("cacheRead"), (u.cacheReadInputTokens ?: 0).toLong())
        eq(errs, "llm.cacheWrite", e.getLong("cacheWrite"), (u.cacheCreationInputTokens ?: 0).toLong())
        eq(errs, "llm.reasoning", optLong(e, "reasoning"), u.reasoningTokens?.toLong())
        eq(errs, "llm.cacheReported", e.getBoolean("cacheReported"), u.cacheReported)
    }

    // ---- parse_stored ---------------------------------------------------

    private fun checkStored(c: JSONObject, errs: MutableList<String>) {
        val records = c.getJSONArray("records")
        val results = c.getJSONObject("expected").getJSONArray("results")
        eq(errs, "count", results.length(), records.length())
        for (i in 0 until records.length()) {
            val x = results.getJSONObject(i)
            val p = StoredUsageJson.parse(usageText(records.get(i)))
            if (!x.getBoolean("valid")) {
                if (p != StoredUsageParse.Invalid) errs += "[$i] expected invalid, got $p"
                continue
            }
            val u = (p as? StoredUsageParse.Valid)?.usage ?: run { errs += "[$i] expected valid, got $p"; null } ?: continue
            eq(errs, "[$i].input", x.getLong("input"), u.input)
            eq(errs, "[$i].cacheRead", x.getLong("cacheRead"), u.cacheRead)
            eq(errs, "[$i].cacheWrite", x.getLong("cacheWrite"), u.cacheWrite)
            eq(errs, "[$i].output", x.getLong("output"), u.output)
            eq(errs, "[$i].reasoning", optLong(x, "reasoning"), u.reasoning)
            eq(errs, "[$i].cacheReported", optBool(x, "cacheReported"), u.cacheReported)
            eq(errs, "[$i].copied", x.getBoolean("copied"), u.copied)
        }
    }

    // ---- model_key ------------------------------------------------------

    private fun checkModelKey(c: JSONObject, errs: MutableList<String>) {
        val keys = c.getJSONObject("expected").getJSONArray("keys")
        for (i in 0 until keys.length()) {
            val k = keys.getJSONObject(i)
            val id = k.getString("id")
            eq(errs, "merged($id)", k.getString("key"), ModelKey.merged(id))
            eq(errs, "exact($id)", k.getString("exactKey"), ModelKey.exact(id))
        }
    }

    // ---- report ---------------------------------------------------------

    private fun checkReport(c: JSONObject, errs: MutableList<String>) {
        val o = c.getJSONObject("options")
        val query = UsageQuery(
            range = UsageRange.valueOf(o.getString("range")),
            zone = ZoneId.of(o.getString("timezone")),
            nowMs = ms(o.getString("now")),
            groupBy = UsageGroupBy.valueOf(o.getString("groupBy").uppercase()),
            exactModelId = o.optBoolean("exactModelId", false),
            firstWeekday = DayOfWeek.valueOf(o.optString("firstWeekday", "monday").uppercase()),
            liveInstances = objects(o.optJSONArray("liveInstances")).map {
                LiveInstance(it.getString("id"), it.getString("providerType"), it.getString("name"))
            },
            liveModels = objects(o.optJSONArray("liveModels")).map { LiveModel(it.getString("id"), it.getString("providerType")) },
        )
        val rows = objects(c.getJSONArray("records")).map { r ->
            UsageRow(
                atMs = ms(r.getString("at")),
                sessionId = r.getString("session"),
                modelId = optStr(r, "modelId"),
                sessionModelId = optStr(r, "sessionModelId"),
                modelDisplayName = optStr(r, "modelDisplayName"),
                providerType = optStr(r, "providerType"),
                providerInstanceId = optStr(r, "providerInstanceId"),
                usageJson = usageText(r.opt("usage")),
            )
        }
        val rep = UsageReportBuilder.build(rows, query)
        val e = c.getJSONObject("expected")

        if (e.isNull("range")) {
            eq(errs, "range", null, rep.rangeStartMs)
        } else {
            val r = e.getJSONObject("range")
            eq(errs, "range.start", ms(r.getString("start")), rep.rangeStartMs)
            eq(errs, "range.end", ms(r.getString("end")), rep.rangeEndMs)
        }
        eq(errs, "granularity", optStr(e, "granularity"), rep.granularity?.name?.lowercase())
        val sk = e.getJSONObject("skipped")
        eq(errs, "skipped.copied", sk.getInt("copied"), rep.skipped.copied)
        eq(errs, "skipped.invalid", sk.getInt("invalid"), rep.skipped.invalid)
        eq(errs, "skipped.noUsage", sk.getInt("noUsage"), rep.skipped.noUsage)

        val t = e.getJSONObject("totals")
        val tt = rep.totals
        eq(errs, "totals.input", t.getLong("input"), tt.tokens.input)
        eq(errs, "totals.cacheRead", t.getLong("cacheRead"), tt.tokens.cacheRead)
        eq(errs, "totals.cacheWrite", t.getLong("cacheWrite"), tt.tokens.cacheWrite)
        eq(errs, "totals.output", t.getLong("output"), tt.tokens.output)
        eq(errs, "totals.total", t.getLong("total"), tt.tokens.total)
        eq(errs, "totals.nonCached", t.getLong("nonCached"), tt.tokens.nonCached)
        eq(errs, "totals.reasoning", optLong(t, "reasoning"), tt.reasoning)
        eq(errs, "totals.reasoningComplete", t.getBoolean("reasoningComplete"), tt.reasoningComplete)
        eq(errs, "totals.hitNum", t.getLong("hitNum"), tt.hitNum)
        eq(errs, "totals.hitDen", t.getLong("hitDen"), tt.hitDen)
        dbl(errs, "totals.hitRate", optDouble(t, "hitRate"), tt.hitRate)
        dbl(errs, "totals.cacheBasis", optDouble(t, "cacheBasis"), tt.cacheBasis)

        eq(errs, "activeDays", e.getInt("activeDays"), rep.activeDays)
        eq(errs, "sessions", e.getInt("sessions"), rep.sessions)
        eq(errs, "calendarDays", e.getInt("calendarDays"), rep.calendarDays)
        dbl(errs, "dailyAvg", optDouble(e, "dailyAvg"), rep.dailyAvg)

        val series = e.getJSONArray("series")
        eq(errs, "series.size", series.length(), rep.series.size)
        for (i in 0 until minOf(series.length(), rep.series.size)) {
            val b = series.getJSONObject(i)
            eq(errs, "series[$i].start", ms(b.getString("start")), rep.series[i].startMs)
            eq(errs, "series[$i].total", b.getLong("total"), rep.series[i].tokens.total)
        }
        if (!e.isNull("seriesEnd")) eq(errs, "seriesEnd", ms(e.getString("seriesEnd")), rep.series.lastOrNull()?.endMs)

        checkGroups(errs, "groups", e.getJSONArray("groups"), rep.groups)
    }

    private fun checkGroups(errs: MutableList<String>, path: String, expected: JSONArray, actual: List<UsageGroup>) {
        eq(errs, "$path.size", expected.length(), actual.size)
        for (i in 0 until minOf(expected.length(), actual.size)) {
            val x = expected.getJSONObject(i)
            val g = actual[i]
            val p = "$path[$i]"
            eq(errs, "$p.key", x.getString("key"), g.key)
            if (x.has("label")) eq(errs, "$p.label", optStr(x, "label"), g.label)
            eq(errs, "$p.total", x.getLong("total"), g.tokens.total)
            if (x.has("hitNum")) eq(errs, "$p.hitNum", x.getLong("hitNum"), g.hitNum)
            if (x.has("hitDen")) eq(errs, "$p.hitDen", x.getLong("hitDen"), g.hitDen)
            if (x.has("hitRate")) dbl(errs, "$p.hitRate", optDouble(x, "hitRate"), g.hitRate)
            if (x.has("rawIds")) eq(errs, "$p.rawIds", strings(x.getJSONArray("rawIds")), g.rawIds)
            if (x.has("children")) checkGroups(errs, "$p.children", x.getJSONArray("children"), g.children)
            else eq(errs, "$p.children.size", 0, g.children.size)
        }
    }

    // ---- helpers --------------------------------------------------------

    /** A record's `usage`: an object is serialized, a string is the raw column text, null is NULL. */
    private fun usageText(v: Any?): String? = when (v) {
        null, JSONObject.NULL -> null
        is JSONObject -> v.toString()
        else -> v.toString()
    }

    private fun ms(iso: String): Long = OffsetDateTime.parse(iso).toInstant().toEpochMilli()

    private fun objects(a: JSONArray?): List<JSONObject> = if (a == null) emptyList() else List(a.length()) { a.getJSONObject(it) }

    private fun strings(a: JSONArray): List<String> = List(a.length()) { a.getString(it) }

    private fun optStr(o: JSONObject, k: String): String? = if (!o.has(k) || o.isNull(k)) null else o.getString(k)

    private fun optLong(o: JSONObject, k: String): Long? = if (!o.has(k) || o.isNull(k)) null else o.getLong(k)

    private fun optDouble(o: JSONObject, k: String): Double? = if (!o.has(k) || o.isNull(k)) null else o.getDouble(k)

    private fun optBool(o: JSONObject, k: String): Boolean? = if (!o.has(k) || o.isNull(k)) null else o.getBoolean(k)

    private fun eq(errs: MutableList<String>, what: String, expected: Any?, actual: Any?) {
        if (expected != actual) errs += "$what: expected $expected, got $actual"
    }

    /** Vectors round ratios to 6 decimals; compare the actual value rounded the same way. */
    private fun dbl(errs: MutableList<String>, what: String, expected: Double?, actual: Double?) {
        val ok = if (expected == null || actual == null) expected == actual
        else abs((actual * 1e6).roundToLong() / 1e6 - expected) < 1e-9
        if (!ok) errs += "$what: expected $expected, got $actual"
    }
}
