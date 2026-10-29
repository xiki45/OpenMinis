package com.openminis.app.ctx

import com.openminis.app.ctx.CtxKit.Preset
import com.openminis.app.data.ContextSizeMeter
import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.ui.chat.RequestContextBuilder
import org.json.JSONObject

/**
 * One request phase of a scenario: the builder's output and every provider's
 * parsed body. "live" is the request right after the turn; "restart" is the
 * same conversation after persist → clear memory → rebuild from storage.
 */
class CtxPhase(
    val name: String,
    val ctx: RequestContextBuilder.RequestContext,
    val bodies: Map<CtxProvider, JSONObject>,
    val history: List<LLMMessage>,
) {
    val views: Map<CtxProvider, List<CtxRequestView.Message>> = bodies.mapValues { (p, b) -> CtxRequestView.parse(p, b) }
    fun view(p: CtxProvider) = views.getValue(p)
    fun images(p: CtxProvider) = CtxRequestView.images(view(p))
    fun placeholders(p: CtxProvider) = CtxRequestView.placeholders(view(p))
    fun adjacency(p: CtxProvider) = CtxRequestView.adjacency(view(p))
    fun texts(p: CtxProvider): List<String> = view(p).flatMap { m -> m.parts.mapNotNull { it.text } }
    fun allText(p: CtxProvider) = texts(p).joinToString("\n")
    val historyImages: Int get() = CtxRequestView.historyImages(history)
    /** ContextSizeMeter's estimate of the outbound history (what the meter and compaction read). */
    val tokenEstimate: Int get() = ContextSizeMeter.estimateTokens(ctx.outbound)
    fun dims(p: CtxProvider): List<String> = images(p).map { "${it.w}x${it.h}" }
}

class CtxRun(val scenario: CtxScenario, val session: CtxSession, val live: CtxPhase, val restarted: CtxPhase?) {
    val phases: List<CtxPhase> get() = listOfNotNull(live, restarted)
    val facts = linkedMapOf<String, Any?>()
}

/**
 * A desired-behaviour check. [kind]:
 *  - GAP: a known product gap — today it must NOT hold (the test fails when it
 *    starts holding, so the table gets flipped);
 *  - JVM: cannot be judged on the JVM (decoder is not Android's); reported only;
 *  - CONFIRM: no agreed expectation yet (catalog: "待与用户确认预期"); reported only;
 *  - HOLDS: expected and true today.
 */
class CtxDesired(val text: String, val kind: Kind, val ref: String? = null, val holds: (CtxRun) -> Boolean) {
    enum class Kind { GAP, JVM, CONFIRM, HOLDS }
}

class CtxScenario(
    val id: String,
    val title: String,
    /** The catalog's "预期（当前）" column, as written there (reference only; the assertions below are measured). */
    val catalogCurrent: String,
    /** The catalog's "期望" column. */
    val catalogDesired: String,
    val build: (CtxSession) -> Unit,
    /** Today's behaviour, measured on this code (characterization). */
    val current: (CtxRun) -> Unit,
    val desired: List<CtxDesired> = emptyList(),
    /** Between the live request and the restart; default nothing. */
    val beforeRestart: (CtxSession, CtxRun) -> Unit = { _, _ -> },
    /** How the restart phase rebuilds; default [CtxSession.restart]. */
    val restart: ((CtxSession) -> Unit)? = { it.restart() },
    val window: Int? = 200_000,
    val note: String = "",
)

/** Small assertion helpers so each scenario reads like its table row. */
internal object Expect {
    fun eq(what: String, expected: Any?, actual: Any?) {
        if (expected != actual) throw AssertionError("$what: expected <$expected> but was <$actual>")
    }

    fun ok(what: String, cond: Boolean) {
        if (!cond) throw AssertionError(what)
    }

    /** Every provider of [phase] sends exactly these image sizes (in order). */
    fun dims(phase: CtxPhase, vararg wh: String) {
        for (p in CtxProvider.entries) eq("${phase.name}/${p.id} image dims", wh.toList(), phase.dims(p))
    }

    fun mimes(phase: CtxPhase, vararg m: String) {
        for (p in CtxProvider.entries) eq("${phase.name}/${p.id} image mimes", m.toList(), phase.images(p).map { it.mime })
    }

    fun noImages(phase: CtxPhase) = dims(phase)

    fun adjacencyOk(phase: CtxPhase, vararg providers: CtxProvider = CtxProvider.entries.toTypedArray()) {
        for (p in providers) eq("${phase.name}/${p.id} adjacency", emptyList<String>(), phase.adjacency(p))
    }

    fun placeholders(phase: CtxPhase, n: Int) {
        for (p in CtxProvider.entries) eq("${phase.name}/${p.id} placeholders", n, phase.placeholders(p).size)
    }

    fun sameImageBytes(a: CtxPhase, b: CtxPhase) {
        for (p in CtxProvider.entries) eq("${p.id} image sha ${a.name} vs ${b.name}", a.images(p).map { it.sha256 }, b.images(p).map { it.sha256 })
    }

    fun textContains(phase: CtxPhase, needle: String, vararg providers: CtxProvider = CtxProvider.entries.toTypedArray()) {
        for (p in providers) ok("${phase.name}/${p.id} text contains \"${needle.take(60)}\"", phase.allText(p).contains(needle))
    }

    fun textLacks(phase: CtxPhase, needle: String) {
        for (p in CtxProvider.entries) ok("${phase.name}/${p.id} text lacks \"${needle.take(60)}\"", !phase.allText(p).contains(needle))
    }
}

/**
 * B: scenarios C01–C32 (ids shared with iOS). Each runs live and after a
 * restart, against all four serializers. "current" asserts what this code
 * does today (measured; it is noted where a value differs
 * from the catalog's column); "desired" holds the catalog's expected
 * behaviour, as KNOWN GAP where it does not hold.
 */
object CtxScenarios {
    private val A = CtxProvider.ANTHROPIC
    private val CHAT = CtxProvider.OPENAI_CHAT
    private val RESP = CtxProvider.OPENAI_RESPONSES
    private val GEM = CtxProvider.GEMINI
    private val ALL = CtxProvider.entries

    private fun liveEqualsRestartImages(r: CtxRun) = ALL.all { p -> r.live.images(p).map { it.sha256 } == r.restarted!!.images(p).map { it.sha256 } }
    private fun liveEqualsRestartDims(r: CtxRun) = ALL.all { p -> r.live.dims(p) == r.restarted!!.dims(p) }
    private fun allWithin(r: CtxRun, edge: Int) = r.phases.all { ph -> ALL.all { p -> ph.images(p).all { (it.longEdge ?: 0) <= edge } } }
    private fun mimeMatches(r: CtxRun) = r.phases.all { ph -> ALL.all { p -> CtxRequestView.mimeMismatches(ph.view(p)).isEmpty() } }

    private val SUMMARY = "Earlier: the user asked for a screenshot of example.com; it was taken and described."

    /** user → assistant(browser_use call) → tool result with [shot]. */
    private fun browserTurn(s: CtxSession, shot: com.openminis.app.browser.BrowserActionResult, id: String = "call_shot1") {
        s.user("Open example.com and take a screenshot")
        val call = CtxSession.Call(id, "browser_use", JSONObject().put("action", "screenshot"))
        s.assistant("", call)
        s.toolResults(call to s.browser(shot))
    }

    /** A file the agent can read at /var/minis/workspace/<name>. */
    private fun workspaceFile(s: CtxSession, name: String, bytes: ByteArray): Pair<java.io.File, String> {
        val f = java.io.File(s.filesDir, "minis-sessions/${s.sid}/workspace/$name").apply { parentFile.mkdirs(); writeBytes(bytes) }
        return f to "/var/minis/workspace/$name"
    }

    private fun readImageTurn(s: CtxSession, file: java.io.File, path: String, vararg ids: String) {
        s.user("Look at $path")
        val calls = ids.map { CtxSession.Call(it, "read_image", JSONObject().put("path", path)) }
        s.assistant("", *calls.toTypedArray())
        s.toolResults(*calls.map { it to s.readImage(file, path) }.toTypedArray())
    }

    val all: List<CtxScenario> by lazy { listOf(
        CtxScenario(
            "C01", "plain text question and answer",
            catalogCurrent = "text only, same on both platforms (to test)", catalogDesired = "same",
            build = { s ->
                s.user(CtxKit.Text.SHORT)
                s.assistant("I can read files, browse and run commands.")
                s.user("Thanks. What is 2+2?\n" + CtxKit.Text.EMOJI_ZWJ + "\n" + CtxKit.Text.CODE_AND_TABLE + "\n" + CtxKit.Text.SUMMARY_LOOKALIKE)
            },
            current = { r ->
                for (ph in r.phases) {
                    Expect.noImages(ph); Expect.adjacencyOk(ph)
                    Expect.textContains(ph, CtxKit.Text.SHORT); Expect.textContains(ph, "What is 2+2?")
                    // Emoji / ZWJ, code fences, tables and a user-typed summary tag go out verbatim.
                    Expect.textContains(ph, CtxKit.Text.EMOJI_ZWJ); Expect.textContains(ph, CtxKit.Text.CODE_AND_TABLE)
                    Expect.textContains(ph, CtxKit.Text.SUMMARY_LOOKALIKE)
                    Expect.eq("${ph.name} no compaction in play", RequestContextBuilder.CompactionMode.NONE, ph.ctx.diagnostics.compaction.mode)
                }
                for (p in ALL) Expect.eq("${p.id} live == restart", CtxRequestView.snapshot(r.live.view(p), null), CtxRequestView.snapshot(r.restarted!!.view(p), null))
            },
            desired = listOf(CtxDesired("live request == restarted request", CtxDesired.Kind.HOLDS) { r ->
                ALL.all { CtxRequestView.snapshot(r.live.view(it), null) == CtxRequestView.snapshot(r.restarted!!.view(it), null) }
            }),
        ),

        CtxScenario(
            "C02", "200,000-character text input (typed / programmatic, and pasted past the 15k file threshold)",
            catalogCurrent = "to test: truncation, offload, token estimate", catalogDesired = "no silent truncation",
            build = { s ->
                s.user(CtxKit.Text.long(200_000))
                s.assistant("Got it.")
                // The composer turns a paste over PASTE_AS_FILE_THRESHOLD (15,000) into a
                // text/plain DOCUMENT named Pasted_<yyyyMMdd-HHmmss>-<uuid8>.txt (stashPastedTextAsFile).
                s.user("Summarise the attached text", listOf(CtxKit.text("Pasted_20251009-120000-abcd1234.txt", CtxKit.Text.cjk(200_000))))
            },
            current = { r ->
                for (ph in r.phases) {
                    Expect.noImages(ph)
                    for (p in ALL) {
                        val texts = ph.texts(p)
                        // sendMessage trims surrounding whitespace (the generated text ends in a space): 199,999 chars, nothing cut.
                        Expect.ok("${ph.name}/${p.id}: the typed 200k text goes out whole", texts.any { it == CtxKit.Text.long(200_000).trim() })
                        Expect.ok("${ph.name}/${p.id}: the pasted file is not inlined", texts.none { it.contains(CtxKit.Text.cjk(2_000)) })
                    }
                    Expect.textContains(ph, "/var/minis/attachments/uploads/Pasted_20251009-120000-abcd1234.txt")
                }
                r.facts["tokenEstimate.live"] = r.live.tokenEstimate
                r.facts["tokenEstimate.200kAscii"] = ContextSizeMeter.estimateTokens(CtxKit.Text.long(200_000))
                r.facts["tokenEstimate.200kCjk"] = ContextSizeMeter.estimateTokens(CtxKit.Text.cjk(200_000))
                r.facts["tokenEstimate.2MAscii"] = ContextSizeMeter.estimateTokens(CtxKit.Text.long(2_000_000))
                r.facts["tokenEstimate.10kAscii"] = ContextSizeMeter.estimateTokens(CtxKit.Text.long(10_000))
                r.facts["bpe.200kAscii"] = com.openminis.app.data.BPETokenizer.countTokens(CtxKit.Text.long(200_000))
                r.facts["uploads"] = r.session.uploadsDir.list()?.sorted()
            },
            desired = listOf(CtxDesired("no silent truncation (typed text whole; pasted file addressable by path)", CtxDesired.Kind.HOLDS) { r ->
                r.phases.all { ph -> ALL.all { p -> ph.texts(p).any { it == CtxKit.Text.long(200_000).trim() } && ph.allText(p).contains("Pasted_20251009-120000-abcd1234.txt") } }
            }),
        ),

        CtxScenario(
            "C03", "pasted long text ([Pasted#N] → text/plain mediaRef), and the paste file missing after restart",
            catalogCurrent = "to test; a missing paste file has a visible placeholder (known)", catalogDesired = "same",
            build = { s ->
                s.user("Please review:", pastes = listOf(CtxKit.Text.long(5_000)))
                s.assistant("Reviewed.")
                s.user("And this one:", pastes = listOf("second paste body " + CtxKit.Text.long(3_000)))
            },
            // The second paste's file disappears before the restart.
            beforeRestart = { s, r ->
                val last = s.rows.last { it.role == "user" }
                val (file, name, _) = s.mediaRefs(last).single()
                r.facts["deletedPaste"] = name
                file.delete()
            },
            current = { r ->
                Expect.textContains(r.live, CtxKit.Text.long(5_000))
                Expect.textContains(r.live, "second paste body ")
                Expect.textContains(r.restarted!!, CtxKit.Text.long(5_000))
                Expect.textContains(r.restarted, "[pasted content unavailable — the stored text file is missing]")
                Expect.textLacks(r.restarted, "second paste body ")
                Expect.textLacks(r.live, "[Pasted#")
            },
            desired = listOf(CtxDesired("missing paste file → visible placeholder", CtxDesired.Kind.HOLDS) { r ->
                ALL.all { r.restarted!!.allText(it).contains("[pasted content unavailable") }
            }),
        ),

        CtxScenario(
            "C04", "camera photo 4032×3024 JPEG",
            catalogCurrent = "2000×1500 when sent and after restart, same bytes (verified)", catalogDesired = "same",
            build = { s -> s.user("What is in this photo?", listOf(CtxKit.image("IMG_0001.jpg", CtxKit.jpeg(Preset.CAMERA_4032x3024), "image/jpeg"))) },
            current = { r ->
                for (ph in r.phases) { Expect.dims(ph, "2000x1500"); Expect.mimes(ph, "image/jpeg"); Expect.placeholders(ph, 0) }
                Expect.sameImageBytes(r.live, r.restarted!!)
                // P-a1: the live turn holds the image twice (imageParts + ImageData); the meter counts both.
                val turn = r.live.history.last()
                Expect.ok("live turn holds the same array twice", turn.imageParts.single().data === (turn.contentParts.first { it is AgentContentPart.ImageData } as AgentContentPart.ImageData).data)
                val withTwin = ContextSizeMeter.estimateTokens(turn)
                val once = ContextSizeMeter.estimateTokens(turn.copy(imageParts = emptyList()))
                r.facts["imageTokens(BPETokenizer)"] = com.openminis.app.data.BPETokenizer.countImageTokens(turn.imageParts.single().data)
                r.facts["estimate.withTwin"] = withTwin
                r.facts["estimate.imageOnce"] = once
                Expect.eq("the twin adds exactly one more image's tokens", com.openminis.app.data.BPETokenizer.countImageTokens(turn.imageParts.single().data), withTwin - once)
            },
            desired = listOf(
                CtxDesired("2000×1500 live and after restart, same bytes", CtxDesired.Kind.HOLDS) { r -> liveEqualsRestartImages(r) && allWithin(r, 2000) },
                CtxDesired("the meter counts the image once (P-a1)", CtxDesired.Kind.GAP, "P-a1") { r ->
                    val t = r.live.history.last(); ContextSizeMeter.estimateTokens(t) == ContextSizeMeter.estimateTokens(t.copy(imageParts = emptyList()))
                },
            ),
        ),

        CtxScenario(
            "C05", "album JPEG 4000×6000",
            catalogCurrent = "1333×2000, same bytes (verified)", catalogDesired = "same",
            build = { s -> s.user("Describe", listOf(CtxKit.image("album.jpg", CtxKit.jpeg(Preset.ALBUM_4000x6000), "image/jpeg"))) },
            current = { r ->
                for (ph in r.phases) { Expect.dims(ph, "1333x2000"); Expect.mimes(ph, "image/jpeg") }
                Expect.sameImageBytes(r.live, r.restarted!!)
            },
            desired = listOf(CtxDesired("1333×2000 live and after restart, same bytes", CtxDesired.Kind.HOLDS) { r -> liveEqualsRestartImages(r) && allWithin(r, 2000) }),
        ),

        CtxScenario(
            "C06", "HEIC 4032×3024 (HEADER ONLY on the JVM)",
            catalogCurrent = "re-encoded to JPEG 2000×1500 (iOS verified; Android JVM header only, to test)", catalogDesired = "same",
            build = { s -> s.user("Photo", listOf(CtxKit.image("IMG_0002.HEIC", CtxKit.heicHeaderOnly(), "image/heic"))) },
            current = { r ->
                // JVM: the header cannot be decoded, so nothing is resized; the provider
                // gate cannot convert it either and sends its placeholder instead.
                for (ph in r.phases) { Expect.noImages(ph); Expect.placeholders(ph, 1) }
                Expect.textContains(r.live, "[Image omitted: its file format could not be converted")
                r.facts["liveHistoryMime"] = r.live.history.last().imageParts.map { it.mimeType }
            },
            desired = listOf(CtxDesired("JPEG 2000×1500 (device decodes HEIC; the JVM cannot)", CtxDesired.Kind.JVM) { r -> r.live.dims(A) == listOf("2000x1500") }),
        ),

        CtxScenario(
            "C07", "small HEIC 1200×900 (HEADER ONLY on the JVM)",
            catalogCurrent = "iOS: HEIC bytes sent as image/jpeg (verified); Android JVM: placeholder", catalogDesired = "transcoded to a compliant JPEG, MIME = bytes",
            build = { s -> s.user("Small photo", listOf(CtxKit.image("small.heic", CtxKit.heicHeaderOnly(2048), "image/heic"))) },
            current = { r ->
                for (ph in r.phases) { Expect.noImages(ph); Expect.placeholders(ph, 1) }
                r.facts["historyMime"] = r.live.history.last().imageParts.map { it.mimeType }
            },
            desired = listOf(CtxDesired("compliant JPEG with matching MIME (device-only)", CtxDesired.Kind.JVM) { r -> r.live.images(A).singleOrNull()?.mime == "image/jpeg" }),
        ),

        CtxScenario(
            "C08", "EXIF orientation photos: 1600×1200 with each orientation 1–8, and 4032×3024 with EXIF=6",
            catalogCurrent = "iOS upright; Android small: original bytes, EXIF kept", catalogDesired = "upright on both platforms",
            build = { s ->
                s.user("Small ones", (1..8).map { o -> CtxKit.image("exif$o.jpg", CtxKit.exifJpeg(1600, 1200, o), "image/jpeg") })
                s.assistant("OK")
                s.user("Big one", listOf(CtxKit.image("big_exif6.jpg", CtxKit.exifJpeg(4032, 3024, 6), "image/jpeg")))
            },
            current = { r ->
                for (ph in r.phases) for (p in ALL) {
                    val imgs = ph.images(p)
                    Expect.eq("${ph.name}/${p.id} count", 9, imgs.size)
                    // Small: original bytes pass through, EXIF orientation preserved.
                    Expect.eq("${ph.name}/${p.id} small sizes", List(8) { "1600x1200" }, imgs.take(8).map { "${it.w}x${it.h}" })
                    // Big: re-encoded to 2000×1500 (stored orientation), EXIF dropped.
                    Expect.eq("${ph.name}/${p.id} big size", "2000x1500", imgs[8].let { "${it.w}x${it.h}" })
                }
                val bytes = r.live.bodies.getValue(A).let { com.openminis.app.imagerepro.ImageRealFixtures.wireImages(it) }
                Expect.eq("small EXIF orientations on the wire", (1..8).toList(), bytes.take(8).map { it.dim?.exifOrientation })
                Expect.eq("big image EXIF on the wire", null, bytes[8].dim?.exifOrientation)
            },
            desired = listOf(
                CtxDesired("the big EXIF=6 photo goes out upright (here: 2000×1500 stored orientation, EXIF dropped → sideways)", CtxDesired.Kind.GAP, "P-a6") { r ->
                    r.live.images(A).getOrNull(8)?.let { it.w == 1500 && it.h == 2000 } == true
                },
                CtxDesired("small photos: EXIF kept, so they show upright only if the provider honours EXIF (unverified)", CtxDesired.Kind.CONFIRM) { false },
            ),
        ),

        CtxScenario(
            "C09", "transparent PNG 512×512 and 3000×3000",
            catalogCurrent = "iOS: 512 kept, 3000 → JPEG on white; Android: black (device, not JVM-verifiable)", catalogDesired = "white on both, or keep PNG",
            build = { s ->
                s.user("Logos", listOf(
                    CtxKit.image("logo512.png", CtxKit.png(512, 512, alpha = true), "image/png"),
                    CtxKit.image("logo3000.png", CtxKit.png(3000, 3000, alpha = true), "image/png"),
                ))
                s.assistant("Two logos.")
                // A detailed transparent image: after the 2000 px downscale (kept PNG) it is still
                // over the 3.75 MB per-image cap, so the compose-time budget re-encodes it — as JPEG.
                s.user("A detailed one", listOf(CtxKit.image("art3000.png", CtxKit.png(3000, 3000, alpha = true, content = CtxKit.Content.NOISE), "image/png")))
            },
            current = { r ->
                for (ph in r.phases) {
                    Expect.dims(ph, "512x512", "2000x2000", "2000x2000")
                    Expect.mimes(ph, "image/png", "image/png", "image/jpeg")
                }
                r.facts["composeBudget"] = r.session.composeBudgets.map { "compressed=${it.compressedCount} dropped=${it.droppedCount} totalBytes=${it.totalBytes}" }
            },
            desired = listOf(
                CtxDesired("simple transparent PNGs: alpha kept (PNG out)", CtxDesired.Kind.HOLDS) { r ->
                    r.phases.all { ph -> ALL.all { p -> ph.images(p).take(2).all { it.mime == "image/png" } } }
                },
                CtxDesired("a transparent image re-encoded for size keeps alpha or is flattened on white (here: JPEG; the background colour is a device question)", CtxDesired.Kind.GAP, "C09") { r ->
                    r.live.images(A).getOrNull(2)?.mime == "image/png"
                },
            ),
            note = "Measured: Android keeps PNG for a PNG source when downscaling (ImageBudget.downscaleForModel); only the compose-time size budget re-encodes to JPEG, which is where a transparent image loses its alpha.",
        ),

        CtxScenario(
            "C10", "multi-frame GIF 2400×1800",
            catalogCurrent = "iOS queue path labels gif but sends JPEG (verified)", catalogDesired = "MIME from the bytes",
            build = { s -> s.user("Animated", listOf(CtxKit.image("anim.gif", CtxKit.gif(2400, 1800, frames = 3), "image/gif"))) },
            current = { r ->
                for (ph in r.phases) { Expect.dims(ph, "2000x1500"); Expect.mimes(ph, "image/jpeg") }
            },
            desired = listOf(CtxDesired("MIME matches the bytes", CtxDesired.Kind.HOLDS) { mimeMatches(it) }),
        ),

        CtxScenario(
            "C11", "extension ≠ bytes: JPEG bytes named .webp (image/webp)",
            catalogCurrent = "iOS send path labels image/webp (verified)", catalogDesired = "decided by magic bytes",
            build = { s -> s.user("Look", listOf(CtxKit.image("fake.webp", CtxKit.jpeg(800, 600), "image/webp"))) },
            current = { r ->
                for (ph in r.phases) { Expect.dims(ph, "800x600"); Expect.mimes(ph, "image/jpeg") }
                r.facts["live.historyMime"] = r.live.history.last().imageParts.single().mimeType
                r.facts["restart.historyMime"] = r.restarted!!.history.last().imageParts.single().mimeType
            },
            desired = listOf(CtxDesired("wire MIME decided by magic", CtxDesired.Kind.HOLDS) { mimeMatches(it) }),
        ),

        CtxScenario(
            "C12", "0-byte, truncated (first 1 KB) and non-image bytes attached as images",
            catalogCurrent = "iOS: non-image bytes sent as image/jpeg (verified); rest to test", catalogDesired = "visible placeholder, never non-compliant bytes",
            build = { s -> s.user("Broken files", listOf(
                CtxKit.image("empty.jpg", CtxKit.zeroBytes(), "image/jpeg"),
                CtxKit.image("cut.jpg", CtxKit.truncated(CtxKit.jpeg(1200, 900)), "image/jpeg"),
                CtxKit.image("notes.png", CtxKit.notImage(), "image/png"),
            )) },
            current = { r ->
                r.facts["live.historyImages"] = r.live.historyImages
                r.facts["restart.historyImages"] = r.restarted!!.historyImages
                for (ph in r.phases) for (p in ALL) {
                    r.facts["${ph.name}.${p.id}"] = ph.images(p).map { "${it.mime}/${it.magic}/${it.bytes}B" } + ph.placeholders(p).map { it.take(40) }
                }
            },
            desired = listOf(CtxDesired("no non-compliant bytes reach the wire (each broken file → a placeholder)", CtxDesired.Kind.GAP, "C12") { r ->
                r.phases.all { ph -> ALL.all { p -> ph.images(p).all { it.magic != "unknown" && it.mime == it.magic && it.w != null } } }
            }),
        ),

        CtxScenario(
            "C13", "video attachment (mp4, HEADER ONLY)",
            catalogCurrent = "not verified", catalogDesired = "to agree with the user",
            build = { s -> s.user("What happens in this clip?", listOf(CtxKit.mp4HeaderOnly())) },
            current = { r ->
                for (ph in r.phases) {
                    Expect.noImages(ph); Expect.placeholders(ph, 0)
                    Expect.textContains(ph, "<file path=\"/var/minis/attachments/uploads/clip.mp4\" url=\"minis://attachments/uploads/clip.mp4\" size=\"65536\"")
                }
            },
            desired = listOf(CtxDesired("video: only path + metadata reach the model (no frames, no bytes) — expectation to be confirmed", CtxDesired.Kind.CONFIRM) { false }),
        ),

        CtxScenario(
            "C14", "PDF / audio / plain text / zip attachments",
            catalogCurrent = "to test (expected: only path + metadata)", catalogDesired = "same",
            build = { s -> s.user("Files", listOf(CtxKit.pdf(), CtxKit.wav(), CtxKit.text(), CtxKit.zip())) },
            current = { r ->
                for (ph in r.phases) {
                    Expect.noImages(ph)
                    for (n in listOf("doc.pdf", "voice.wav", "notes.txt", "bundle.zip")) Expect.textContains(ph, "/var/minis/attachments/uploads/$n")
                    Expect.textLacks(ph, "Hello PDF"); Expect.textLacks(ph, "plain text attachment")
                }
            },
            desired = listOf(CtxDesired("only path + metadata in the context", CtxDesired.Kind.HOLDS) { r ->
                r.phases.all { ph -> ALL.all { p -> !ph.allText(p).contains("plain text attachment") && ph.images(p).isEmpty() } }
            }),
        ),

        CtxScenario(
            "C15", "large (64 MB sparse), duplicate, Chinese / space and extension-less file names",
            catalogCurrent = "to test", catalogDesired = "nothing lost, nothing overwritten",
            build = { s -> s.user("More files", listOf(
                CtxKit.sparse(s.root, "big.bin", 64L * 1024 * 1024),
                CtxKit.pdf("report.pdf"), CtxKit.pdf("report.pdf"),
                CtxKit.text("报告 第1版.txt", "v1"), CtxKit.text("报告 第2版.txt", "v2"),
                CtxKit.text("README", "no extension"),
            )) },
            current = { r ->
                val xml = r.live.allText(A)
                val paths = Regex("<file path=\"([^\"]+)\" url=\"[^\"]+\" size=\"(\\d+)\"").findAll(xml).map { it.groupValues[1].substringAfterLast('/') + ":" + it.groupValues[2] }.toList()
                r.facts["xmlEntries"] = paths
                Expect.eq("six entries, none overwritten", 6, paths.map { it.substringBefore(':') }.toSet().size)
                Expect.ok("64 MB file kept its size", paths.contains("big.bin:${64L * 1024 * 1024}"))
                r.facts["uploads"] = r.session.uploadsDir.list()?.sorted()
                r.facts["mediaNames"] = r.session.mediaRefs(r.session.rows.last()).map { it.second + " → ." + it.first.extension }
                for (ph in r.phases) Expect.noImages(ph)
            },
            desired = listOf(
                CtxDesired("nothing lost or overwritten", CtxDesired.Kind.HOLDS) { r ->
                    Regex("<file path=\"([^\"]+)\"").findAll(r.live.allText(A)).map { it.groupValues[1] }.toSet().size == 6
                },
            ),
        ),

        CtxScenario(
            "C16", "browser viewport screenshot (Pixel-class 1080×2400 physical px, JPEG q80)",
            catalogCurrent = "iOS live 925×2000, restart 1170×2532 (verified); Android 900×2000, restart 0", catalogDesired = "live = restart",
            build = { s -> browserTurn(s, CtxKit.browserScreenshot(1080, 2400, fullPage = false)) },
            current = { r ->
                Expect.dims(r.live, "900x2000"); Expect.mimes(r.live, "image/jpeg"); Expect.adjacencyOk(r.live)
                Expect.noImages(r.restarted!!); Expect.placeholders(r.restarted, 0); Expect.adjacencyOk(r.restarted)
                Expect.textContains(r.live, "minis_url: minis://browser/screenshot_1760000000.jpg")
                r.facts["browserDir"] = r.session.browserDir.listFiles()?.map { it.name + ":" + (com.openminis.app.imagerepro.ImageRealFixtures.dims(it.readBytes())?.let { d -> "${d.width}x${d.height}" }) }
            },
            desired = listOf(CtxDesired("the tool image survives a restart (live = restart)", CtxDesired.Kind.GAP, "#412 / P-a2") { liveEqualsRestartDims(it) }),
        ),

        CtxScenario(
            "C17", "browser screenshot of a 9000×6000 viewport",
            catalogCurrent = "iOS restart sends 9000×6000 (verified); Android restart 0", catalogDesired = "≤2000 and consistent",
            build = { s -> browserTurn(s, CtxKit.browserScreenshot(Preset.VIEWPORT_BIG_9000x6000.w, Preset.VIEWPORT_BIG_9000x6000.h, fullPage = false)) },
            current = { r ->
                Expect.dims(r.live, "2000x1333"); Expect.noImages(r.restarted!!)
            },
            desired = listOf(CtxDesired("≤2000 and live = restart", CtxDesired.Kind.GAP, "#412") { liveEqualsRestartDims(it) && allWithin(it, 2000) }),
        ),

        CtxScenario(
            "C18", "browser full-page screenshot #412 (Android caps page height at 32768: 1080×32768)",
            catalogCurrent = "iOS live 253×2000, restart 2760×21891 (verified); Android live 65×2000, restart 0", catalogDesired = "consistent and within limits",
            build = { s -> browserTurn(s, CtxKit.browserScreenshot(Preset.FULLPAGE_AND_1080x32768.w, Preset.FULLPAGE_AND_1080x32768.h, fullPage = true)) },
            current = { r ->
                Expect.dims(r.live, "65x2000"); Expect.noImages(r.restarted!!)
            },
            desired = listOf(CtxDesired("consistent across restart, within limits, legible", CtxDesired.Kind.GAP, "#412") { liveEqualsRestartDims(it) && allWithin(it, 2000) }),
        ),

        CtxScenario(
            "C19", "two browser screenshots in the same second",
            catalogCurrent = "Android second-resolution names overwrite (N3, to prove with the real function)", catalogDesired = "unique file names",
            build = { s ->
                s.user("Take two screenshots")
                val c1 = CtxSession.Call("call_a", "browser_use", JSONObject().put("action", "screenshot"))
                val c2 = CtxSession.Call("call_b", "browser_use", JSONObject().put("action", "screenshot"))
                s.assistant("", c1, c2)
                val r1 = s.browser(CtxKit.browserScreenshot(1080, 2400, false, CtxKit.Content.TEXT))
                s.advance(400) // same second
                val r2 = s.browser(CtxKit.browserScreenshot(1080, 2400, false, CtxKit.Content.NOISE))
                s.toolResults(c1 to r1, c2 to r2)
            },
            current = { r ->
                val files = r.session.browserDir.listFiles()!!.map { it.name }
                Expect.eq("one file on disk for two screenshots", listOf("screenshot_1760000000.jpg"), files)
                Expect.eq("both results point at the same minis_url", 2,
                    Regex("minis_url: minis://browser/screenshot_1760000000.jpg").findAll(r.live.allText(A)).count())
                Expect.dims(r.live, "900x2000", "900x2000")
                Expect.adjacencyOk(r.live)
                Expect.ok("the two live images differ", r.live.images(A).map { it.sha256 }.toSet().size == 2)
                val onDisk = com.openminis.app.imagerepro.ImageRealFixtures.sha256(java.io.File(r.session.browserDir, files.single()).readBytes())
                r.facts["fileIsSecondShot"] = onDisk
            },
            desired = listOf(CtxDesired("unique names: both screenshots kept on disk", CtxDesired.Kind.GAP, "N3") { r ->
                (r.session.browserDir.list()?.size ?: 0) == 2
            }),
        ),

        CtxScenario(
            "C20", "takeover screenshot (iOS only)",
            catalogCurrent = "iOS: 9000×6000 sent as is, no mediaRef; Android has no such source", catalogDesired = "scaled and persisted",
            build = { s -> s.user("(Android has no takeover image source; the request carries text only)") },
            current = { r -> for (ph in r.phases) Expect.noImages(ph) },
            desired = listOf(CtxDesired("N/A on Android (no takeover image source; see ImageRealFactsTest P-a9)", CtxDesired.Kind.HOLDS) { true }),
            note = "N/A on Android.",
        ),

        CtxScenario(
            "C21", "read_image of a large image (2760×21891 PNG)",
            catalogCurrent = "iOS live 253×2000, restart original; Android live 252×2000, restart 0", catalogDesired = "consistent on both",
            build = { s ->
                val (file, path) = workspaceFile(s, "page.png", CtxKit.png(Preset.FULLPAGE_IOS_2760x21891))
                readImageTurn(s, file, path, "call_r1")
            },
            current = { r ->
                Expect.dims(r.live, "252x2000"); Expect.mimes(r.live, "image/jpeg"); Expect.noImages(r.restarted!!)
                Expect.textContains(r.live, "[/var/minis/workspace/page.png | 2760x21891 |")
            },
            desired = listOf(CtxDesired("live = restart", CtxDesired.Kind.GAP, "#412 / P-a2") { liveEqualsRestartDims(it) }),
        ),

        CtxScenario(
            "C22", "read_image of the same image twice",
            catalogCurrent = "iOS stores 2 copies; Android 2 images in the request (verified)", catalogDesired = "deduplicated by content",
            build = { s ->
                val (file, path) = workspaceFile(s, "chart.png", CtxKit.png(1200, 800))
                readImageTurn(s, file, path, "call_r1", "call_r2")
            },
            current = { r ->
                Expect.dims(r.live, "1200x800", "1200x800")
                Expect.adjacencyOk(r.live)
                for (p in ALL) Expect.eq("${p.id} duplicate bytes", 1, CtxRequestView.duplicates(r.live.view(p)))
                Expect.noImages(r.restarted!!)
            },
            desired = listOf(CtxDesired("one copy per distinct image", CtxDesired.Kind.GAP, "C22") { r -> ALL.all { CtxRequestView.duplicates(r.live.view(it)) == 0 } }),
        ),

        CtxScenario(
            "C23", "read_image of a HEIC (HEADER ONLY) and of an EXIF=6 photo",
            catalogCurrent = "iOS: HEIC labelled jpeg after restart, EXIF photo squashed on Anthropic (verified)", catalogDesired = "correct transcode and orientation",
            build = { s ->
                val (heic, hp) = workspaceFile(s, "shot.heic", CtxKit.heicHeaderOnly())
                val (exif, ep) = workspaceFile(s, "rotated.jpg", CtxKit.exifJpeg(4032, 3024, 6))
                s.user("Read both")
                val c1 = CtxSession.Call("call_h", "read_image", JSONObject().put("path", hp))
                val c2 = CtxSession.Call("call_e", "read_image", JSONObject().put("path", ep))
                s.assistant("", c1, c2)
                s.toolResults(c1 to s.readImage(heic, hp), c2 to s.readImage(exif, ep))
            },
            current = { r ->
                Expect.dims(r.live, "2000x1500")
                Expect.textContains(r.live, "Error: '/var/minis/workspace/shot.heic' exists (")
                Expect.noImages(r.restarted!!)
            },
            desired = listOf(
                CtxDesired("EXIF=6 photo upright (1500×2000)", CtxDesired.Kind.GAP, "P-a6") { r -> r.live.dims(A).contains("1500x2000") },
                CtxDesired("HEIC decoded and transcoded (device decodes HEIC; the JVM cannot)", CtxDesired.Kind.JVM) { r -> r.live.images(A).size == 2 },
            ),
        ),

        CtxScenario(
            "C24", "six large images over the 25 MB request budget",
            catalogCurrent = "iOS-C: plan drops 1, rewrite drops all, placeholders all point at one file; Android drops 1 (verified)",
            catalogDesired = "only the planned drops, each placeholder pointing at its own file",
            build = { s ->
                // Six turns, one noise PNG each: 1100×1100 RGB noise does not deflate, so each
                // is ~3.6 MB raw — just under the 3.75 MB per-image cap the compose-time
                // budget enforces (MAX_PER_IMAGE_RAW_BYTES), so it is attached as is. Six of
                // them are ~29 MB base64, over the 25 MB request budget.
                for (i in 1..6) {
                    s.user("Image $i", listOf(CtxKit.image("noise$i.png", CtxKit.png(1100, 1100, content = CtxKit.Content.NOISE), "image/png")))
                    s.assistant("Seen $i")
                }
                s.user("Compare them all")
            },
            current = { r ->
                val plan = r.live.ctx.diagnostics.budget
                r.facts["rawSizes"] = r.live.history.flatMap { it.imageParts }.map { it.data.size }
                r.facts["plan"] = plan?.let { "dropped=${it.droppedCount}/${it.totalCount} keptBytes=${it.keptBytes} elidedBytes=${it.elidedBytes}" }
                Expect.ok("the request budget dropped something", plan != null)
                for (ph in r.phases) for (p in ALL) {
                    Expect.eq("${ph.name}/${p.id} images + placeholders = 6", 6, ph.images(p).size + ph.placeholders(p).size)
                    Expect.eq("${ph.name}/${p.id} placeholders = planned drops", ph.ctx.diagnostics.budget?.droppedCount ?: 0, ph.placeholders(p).size)
                }
                r.facts["placeholderPaths"] = r.live.placeholders(A).map { it.substringAfter("Original at ").substringBefore(" —") }
            },
            desired = listOf(CtxDesired("only planned drops; each placeholder names its own file", CtxDesired.Kind.HOLDS) { r ->
                val paths = r.live.placeholders(A).map { it.substringAfter("Original at ").substringBefore(" —") }
                paths.size == (r.live.ctx.diagnostics.budget?.droppedCount ?: -1) && paths.toSet().size == paths.size
            }),
        ),

        CtxScenario(
            "C25", "compaction summary, first message after the anchor is a plain user turn",
            catalogCurrent = "summary prepended to that user turn (verified)", catalogDesired = "same",
            build = { s ->
                s.user("q1"); s.assistant("a1"); s.user("q2"); val anchor = s.assistant("a2")
                s.user("q3 after compaction")
                s.compaction(anchor.id, SUMMARY)
            },
            current = { r ->
                for (ph in r.phases) {
                    Expect.eq("${ph.name} mode", RequestContextBuilder.CompactionMode.V2_PREPENDED_TO_TEXT, ph.ctx.diagnostics.compaction.mode)
                    for (p in ALL) {
                        val last = ph.view(p).last()
                        Expect.ok("${ph.name}/${p.id} summary leads the post-anchor user turn",
                            last.parts.first { it.kind == "text" }.text!!.startsWith("<context-summary>") && last.parts.any { it.text?.contains("q3 after compaction") == true })
                    }
                    Expect.adjacencyOk(ph)
                }
            },
            desired = listOf(CtxDesired("summary prepended to the first post-anchor user turn", CtxDesired.Kind.HOLDS) { r ->
                r.phases.all { it.ctx.diagnostics.compaction.mode == RequestContextBuilder.CompactionMode.V2_PREPENDED_TO_TEXT }
            }),
        ),

        CtxScenario(
            "C26", "compaction summary, first message after the anchor is a tool-result carrier (#404)",
            catalogCurrent = "iOS Responses: function_call → message(summary) → function_call_output (verified); Android Responses output first then text, Anthropic native [text, tool_result] (static)",
            catalogDesired = "summary after the tool results; tool_result right after tool_use",
            build = { s ->
                s.user("q1"); s.assistant("a1")
                s.user("Read the file")
                val call = CtxSession.Call("call_f", "file_read", JSONObject().put("path", "/var/minis/workspace/a.txt"))
                val anchor = s.assistant("", call)
                s.toolResults(call to s.text("file contents"))
                s.compaction(anchor.id, SUMMARY)
            },
            current = { r ->
                for (ph in r.phases) {
                    // [T-404-summary-after-tool-results] The summary is appended after the
                    // carrier's tool results; Anthropic / Gemini also put results first.
                    Expect.eq("${ph.name} mode", RequestContextBuilder.CompactionMode.V2_APPENDED_AFTER_TOOL_RESULTS, ph.ctx.diagnostics.compaction.mode)
                    Expect.adjacencyOk(ph)
                    Expect.textContains(ph, "<context-summary>")
                    Expect.textContains(ph, SUMMARY)
                    for (p in ALL) {
                        // The summary sits after the tool result in the wire order.
                        val kinds = ph.view(p).flatMap { m -> m.parts.filter { it.inResultOf == null }.map { it.kind to (it.text ?: "") } }
                        val resultAt = kinds.indexOfFirst { it.first == "toolResult" }
                        val summaryAt = kinds.indexOfFirst { it.second.contains("<context-summary>") }
                        Expect.ok("${ph.name}/${p.id} summary after the tool result", resultAt >= 0 && summaryAt > resultAt)
                    }
                    // The wire order per provider, kind by kind.
                    for (p in ALL) r.facts["${ph.name}.${p.id}.order"] = ph.view(p).takeLast(3).joinToString(" | ") { m -> m.role + ":" + m.parts.joinToString(",") { it.kind } }
                }
            },
            desired = listOf(CtxDesired("tool_result immediately after tool_use on every provider (#404)", CtxDesired.Kind.HOLDS, "#404") { r ->
                r.phases.all { ph -> ALL.all { ph.adjacency(it).isEmpty() } }
            }),
        ),

        CtxScenario(
            "C27", "the same history on every provider",
            catalogCurrent = "to test: are the image bytes the same on every provider", catalogDesired = "same image bytes",
            build = { s ->
                s.user("Photo and screenshot", listOf(CtxKit.image("cam.jpg", CtxKit.jpeg(Preset.CAMERA_4032x3024), "image/jpeg")))
                s.assistant("Taking a screenshot")
                val call = CtxSession.Call("call_s", "browser_use", JSONObject().put("action", "screenshot"))
                s.assistant("", call)
                s.toolResults(call to s.browser(CtxKit.browserScreenshot(1080, 2400, false)))
            },
            current = { r ->
                for (ph in r.phases) {
                    val shas = ALL.map { p -> ph.images(p).map { it.sha256 } }
                    Expect.ok("${ph.name}: identical image bytes on every provider", shas.toSet().size == 1)
                }
                Expect.dims(r.live, "2000x1500", "900x2000")
                Expect.dims(r.restarted!!, "2000x1500")
            },
            desired = listOf(CtxDesired("image bytes identical across providers", CtxDesired.Kind.HOLDS) { r ->
                r.phases.all { ph -> ALL.map { p -> ph.images(p).map { it.sha256 } }.toSet().size == 1 }
            }),
        ),

        CtxScenario(
            "C28", "edit an old message and resend; retry",
            catalogCurrent = "to test; Android edit-resend copies media and uploads (static)", catalogDesired = "no duplicate storage",
            build = { s ->
                val u = s.user("Describe", listOf(CtxKit.image("photo.jpg", CtxKit.jpeg(1200, 900), "image/jpeg")))
                s.assistant("A gradient.")
                s.edit(u, "Describe it in one word")
                s.assistant("Gradient.")
                s.retry(s.rows.last { it.role == "user" })
            },
            current = { r ->
                r.facts["uploads"] = r.session.uploadsDir.list()?.sorted()
                r.facts["mediaFiles"] = r.session.mediaStore.mediaBaseDir.walkTopDown().count { it.isFile }
                Expect.eq("uploads after edit + retry", listOf("photo.jpg", "photo_1.jpg"), r.session.uploadsDir.list()?.sorted())
                Expect.eq("media files after edit + retry", 2, r.session.mediaStore.mediaBaseDir.walkTopDown().count { it.isFile })
                for (ph in r.phases) Expect.dims(ph, "1200x900")
                Expect.textContains(r.live, "/var/minis/attachments/uploads/photo_1.jpg")
            },
            desired = listOf(CtxDesired("edit-resend stores no second copy", CtxDesired.Kind.GAP, "C28") { r -> r.session.uploadsDir.list()?.size == 1 }),
        ),

        CtxScenario(
            "C29", "queued send vs normal send (same text + JPEG; and a GIF)",
            catalogCurrent = "iOS: same bytes for a plain JPEG, GIF label / compression differ (verified)", catalogDesired = "same",
            build = { s ->
                s.user("normal", listOf(CtxKit.image("a.jpg", CtxKit.jpeg(1500, 1000), "image/jpeg"), CtxKit.image("g.gif", CtxKit.gif(2400, 1800), "image/gif")))
                s.assistant("ok")
                s.queue(CtxSession.Prompt("queued", listOf(CtxKit.image("a.jpg", CtxKit.jpeg(1500, 1000), "image/jpeg"), CtxKit.image("g.gif", CtxKit.gif(2400, 1800), "image/gif"))))
            },
            current = { r ->
                for (ph in r.phases) for (p in ALL) {
                    val users = ph.view(p).filter { m -> m.parts.any { it.image != null } }
                    Expect.eq("${ph.name}/${p.id} two image turns", 2, users.size)
                    Expect.eq("${ph.name}/${p.id} same image bytes normal vs queued", users[0].parts.mapNotNull { it.image?.sha256 }, users[1].parts.mapNotNull { it.image?.sha256 })
                    Expect.eq("${ph.name}/${p.id} same mimes", users[0].parts.mapNotNull { it.image?.mime }, users[1].parts.mapNotNull { it.image?.mime })
                }
                val live = r.live.history
                r.facts["normal.dbMessageId"] = live[0].dbMessageId
                r.facts["queued.dbMessageId"] = live[2].dbMessageId
            },
            desired = listOf(CtxDesired("queued and normal sends produce the same parts", CtxDesired.Kind.HOLDS) { r ->
                ALL.all { p -> r.live.view(p).filter { m -> m.parts.any { it.image != null } }.map { m -> m.parts.mapNotNull { it.image?.sha256 } }.toSet().size == 1 }
            }),
        ),

        CtxScenario(
            "C30", "a media file is missing at restart",
            catalogCurrent = "both platforms drop the image silently, no placeholder (verified)", catalogDesired = "visible placeholder",
            build = { s -> s.user("Photo", listOf(CtxKit.image("lost.jpg", CtxKit.jpeg(1200, 900), "image/jpeg"))) },
            beforeRestart = { s, _ -> s.mediaRefs(s.rows.last()).forEach { it.first.delete() } },
            current = { r ->
                Expect.dims(r.live, "1200x900")
                Expect.noImages(r.restarted!!); Expect.placeholders(r.restarted, 0)
                Expect.eq("restart history images", 0, r.restarted.historyImages)
            },
            desired = listOf(CtxDesired("a missing image leaves a visible placeholder", CtxDesired.Kind.GAP, "P-a3") { r ->
                ALL.all { r.restarted!!.placeholders(it).isNotEmpty() || r.restarted.allText(it).contains("missing") }
            }),
        ),

        CtxScenario(
            "C31", "a tool-result image earlier in history",
            catalogCurrent = "iOS: survives restart as the original; Android: disappears (verified)", catalogDesired = "same on both",
            build = { s ->
                browserTurn(s, CtxKit.browserScreenshot(1080, 2400, false))
                s.assistant("The page shows a heading.")
                s.user("Thanks, now what?")
            },
            current = { r ->
                Expect.dims(r.live, "900x2000"); Expect.noImages(r.restarted!!); Expect.adjacencyOk(r.restarted)
            },
            desired = listOf(CtxDesired("the tool image is still in history after a restart", CtxDesired.Kind.GAP, "#412 / P-a2") { liveEqualsRestartDims(it) }),
        ),

        CtxScenario(
            "C32", "after duplicate-session (fork), deleting the source, and a backup restore on another device",
            catalogCurrent = "to test", catalogDesired = "no image lost",
            build = { s -> s.user("Keep this photo", listOf(CtxKit.image("keep.jpg", CtxKit.jpeg(1200, 900), "image/jpeg"))) },
            beforeRestart = { s, r ->
                // Fork on the same device: rows copied, media shared by relative path.
                s.duplicate("fork").use { fork ->
                    r.facts["fork.images"] = CtxRequestView.historyImages(fork.history)
                    // Deleting the SOURCE session wipes its media dirs (MediaStore.deleteSessionMedia).
                    s.mediaStore.deleteSessionMedia(s.sid)
                    fork.restartOn(s.filesDir)
                    r.facts["fork.imagesAfterSourceDeleted"] = CtxRequestView.historyImages(fork.history)
                }
            },
            // Backup → restore on another device: the rows travel, filesDir/media does not.
            restart = { s -> s.restartOn(java.io.File(s.root, "other-device").apply { mkdirs() }) },
            current = { r ->
                Expect.dims(r.live, "1200x900")
                Expect.eq("fork sees the image", 1, r.facts["fork.images"])
                Expect.eq("fork after the source is deleted", 0, r.facts["fork.imagesAfterSourceDeleted"])
                Expect.noImages(r.restarted!!); Expect.placeholders(r.restarted, 0)
            },
            desired = listOf(
                CtxDesired("a fork keeps its images after the source is deleted", CtxDesired.Kind.GAP, "C32") { r -> r.facts["fork.imagesAfterSourceDeleted"] == 1 },
                CtxDesired("a backup restore keeps images", CtxDesired.Kind.GAP, "C32") { r -> r.restarted!!.images(A).isNotEmpty() },
            ),
            note = "Rename: Android has no attachment / session-file rename that rewrites mediaRefs (session id moves only on draft → real), so there is nothing to test.",
        ),
    ) }
}
