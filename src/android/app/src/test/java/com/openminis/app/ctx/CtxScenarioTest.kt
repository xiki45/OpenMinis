package com.openminis.app.ctx

import com.openminis.app.imagerepro.ImageRealJvmCodec
import org.json.JSONArray
import org.json.JSONObject
import org.junit.AfterClass
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * C: runs every scenario of [CtxScenarios] live and after a restart, against
 * all four serializers, through the one production entry
 * (RequestContextBuilder.build) — one JUnit test per scenario.
 *
 *     ./gradlew :app:testGithubDebugUnitTest --tests 'com.openminis.app.ctx.CtxScenarioTest'
 *
 * Each test
 *  1. asserts the scenario's "current" behaviour (measured on this code);
 *  2. evaluates its "desired" behaviour: KNOWN GAP must still be a gap (a gap
 *     that starts holding fails the test, so the table gets flipped), JVM and
 *     CONFIRM rows are reported only;
 *  3. compares the final RequestView of every provider × phase against the
 *     committed golden src/test/resources/ctx-golden/android/<id>.json
 *     (structure: roles, part kinds and order, texts, image size / MIME;
 *     byte sizes and hashes are recorded but not compared). Create
 *     `ctx-golden-update.flag` in the module directory to rewrite goldens.
 * After the class: build/ctx-report/summary.md and summary.tsv, one row per
 * scenario × provider × phase: image count / max edge / MIMEs / total bytes /
 * compliant / adjacency / placeholders / duplicates.
 *
 * Images are decoded and encoded by [ImageRealJvmCodec] (the JDK) behind the
 * production ImageCodec seam: sizes, formats, qualities and control flow are
 * the production code's own; pixels are not Android's.
 */
@RunWith(Parameterized::class)
class CtxScenarioTest(private val id: String) {

    companion object {
        private val results = java.util.concurrent.ConcurrentHashMap<String, Result>()
        private val goldenSrc = java.io.File("src/test/resources/ctx-golden/android")
        private val reportDir = java.io.File("build/ctx-report")
        private val updateGolden get() = java.io.File("ctx-golden-update.flag").exists()

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun ids(): List<Array<Any>> = CtxScenarios.all.map { arrayOf<Any>(it.id) }

        @JvmStatic
        @AfterClass
        fun writeSummary() {
            if (results.isEmpty()) return
            reportDir.mkdirs()
            val rows = results.values.sortedBy { it.id }.flatMap { it.rows }
            val header = listOf("scenario", "provider", "phase", "images", "maxEdge", "mimes", "totalBytes", "compliant", "adjacency", "placeholders", "duplicates", "silentDrop", "vsLive")
            java.io.File(reportDir, "summary.tsv").writeText((listOf(header) + rows).joinToString("\n") { it.joinToString("\t") } + "\n")
            val md = buildString {
                append("| ").append(header.joinToString(" | ")).append(" |\n")
                append("|").append(header.joinToString("|") { "---" }).append("|\n")
                for (r in rows) append("| ").append(r.joinToString(" | ")).append(" |\n")
                append("\n## Desired behaviour\n\n| scenario | kind | status | desired |\n|---|---|---|---|\n")
                for (res in results.values.sortedBy { it.id }) for (d in res.desired) append("| ${res.id} | ${d.kind} | ${d.status} | ${d.text}${d.ref?.let { " ($it)" } ?: ""} |\n")
                append("\n## Facts recorded\n\n")
                for (res in results.values.sortedBy { it.id }) if (res.facts.isNotEmpty()) {
                    append("- **${res.id}**\n")
                    for ((k, v) in res.facts) append("  - $k: $v\n")
                }
            }
            java.io.File(reportDir, "summary.md").writeText(md)
            println(md)
        }
    }

    data class DesiredResult(val text: String, val kind: CtxDesired.Kind, val ref: String?, val status: String)

    data class Result(val id: String, val rows: List<List<String>>, val desired: List<DesiredResult>, val facts: Map<String, Any?>)

    @Test fun scenario() {
        val sc = CtxScenarios.all.single { it.id == id }
        ImageRealJvmCodec.with { _ ->
            CtxSession(id.lowercase(), sc.window).use { s ->
                sc.build(s)
                val live = phase("live", s)
                val run0 = CtxRun(sc, s, live, null)
                sc.beforeRestart(s, run0)
                val restarted = sc.restart?.let { it(s); phase("restart", s) }
                val run = CtxRun(sc, s, live, restarted).also { it.facts.putAll(run0.facts) }

                val errors = mutableListOf<String>()

                // 1. current behaviour
                try {
                    sc.current(run)
                } catch (e: AssertionError) {
                    errors += "current: ${e.message}"
                }

                // 2. desired behaviour
                val desired = sc.desired.map { d ->
                    val holds = runCatching { d.holds(run) }.getOrElse { false }
                    val status = when (d.kind) {
                        CtxDesired.Kind.HOLDS -> if (holds) "holds" else "NO LONGER HOLDS".also { errors += "desired no longer holds: ${d.text}" }
                        CtxDesired.Kind.GAP -> if (holds) "NOW HOLDS — flip to HOLDS".also { errors += "KNOWN GAP now holds — flip it: ${d.text}" } else "KNOWN GAP"
                        CtxDesired.Kind.JVM -> if (holds) "holds (JVM)" else "not judgeable on the JVM"
                        CtxDesired.Kind.CONFIRM -> "to confirm"
                    }
                    println("[$id] ${d.kind} $status — ${d.text}")
                    DesiredResult(d.text, d.kind, d.ref, status)
                }

                // 3. golden
                val snapshot = JSONObject()
                for (ph in run.phases) {
                    val byProvider = JSONObject()
                    for (p in CtxProvider.entries) byProvider.put(p.id, JSONArray(CtxRequestView.snapshot(ph.view(p), s.root.absolutePath)))
                    snapshot.put(ph.name, byProvider)
                }
                val text = CtxRequestView.canonical(snapshot) + "\n"
                java.io.File(reportDir, "golden").apply { mkdirs() }.resolve("$id.json").writeText(text)
                val golden = java.io.File(goldenSrc, "$id.json")
                if (updateGolden || !golden.exists()) {
                    goldenSrc.mkdirs(); golden.writeText(text)
                    if (!updateGolden) errors += "no golden yet — wrote ${golden.path}; review and commit it"
                } else if (structure(JSONObject(golden.readText())) != structure(snapshot)) {
                    errors += "RequestView differs from golden ${golden.path} (see build/ctx-report/golden/$id.json)"
                }

                results[id] = Result(id, summaryRows(run), desired, run.facts)
                for ((k, v) in run.facts) println("[$id] fact $k = $v")
                if (errors.isNotEmpty()) throw AssertionError("$id:\n  " + errors.joinToString("\n  "))
            }
        }
    }

    private fun phase(name: String, s: CtxSession): CtxPhase {
        val ctx = s.request()
        return CtxPhase(name, ctx, CtxProvider.entries.associateWith { CtxSession.wire(it, ctx.messages) }, s.history.toList())
    }

    /** The golden minus byte sizes and hashes (encoder-dependent), canonical. */
    private fun structure(v: Any?): String {
        fun strip(x: Any?): Any? = when (x) {
            is JSONObject -> JSONObject().also { o -> for (k in x.keys()) if (k !in setOf("bytes", "base64Len", "sha256")) o.put(k, strip(x.get(k))) }
            is JSONArray -> JSONArray().also { a -> for (i in 0 until x.length()) a.put(strip(x.get(i))) }
            else -> x
        }
        return CtxRequestView.canonical(strip(v))
    }

    private fun summaryRows(run: CtxRun): List<List<String>> = run.phases.flatMap { ph ->
        CtxProvider.entries.map { p ->
            val view = ph.view(p)
            val imgs = ph.images(p)
            val th = CtxRequestView.thresholds(view)
            // Compliant: declared MIME = magic bytes, decodable, no image over 8192 px or 5 MB
            // base64, request under 25 MB base64.
            val compliant = CtxRequestView.mimeMismatches(view).isEmpty() && th.maxEdgeOver8192 == 0 && th.overB64_5MB == 0 && !th.totalOver25MB &&
                imgs.all { it.decodable }
            // ⑥ history images that reach the wire neither as an image nor as a placeholder.
            val silentDrop = maxOf(0, ph.historyImages - imgs.size - ph.placeholders(p).size)
            // ④ the restarted request against the live one: same image bytes, or how many were lost.
            val vsLive = if (ph === run.live) "-" else {
                val a = run.live.images(p).map { it.sha256 }
                val b = imgs.map { it.sha256 }
                when {
                    a == b -> "same"
                    b.size < a.size && b.size + ph.placeholders(p).size < a.size + run.live.placeholders(p).size -> "lost ${a.size - b.size}"
                    else -> "differs"
                }
            }
            val adj = ph.adjacency(p)
            listOf(
                run.scenario.id, p.id, ph.name, imgs.size.toString(),
                (imgs.mapNotNull { it.longEdge }.maxOrNull() ?: 0).toString(),
                imgs.map { it.mime }.distinct().joinToString(",").ifEmpty { "-" },
                imgs.sumOf { it.bytes }.toString(),
                if (compliant) "yes" else "NO",
                if (adj.isEmpty()) "ok" else "BROKEN(${adj.size})",
                ph.placeholders(p).size.toString(),
                CtxRequestView.duplicates(view).toString(),
                silentDrop.toString(),
                vsLive,
            )
        }
    }
}
