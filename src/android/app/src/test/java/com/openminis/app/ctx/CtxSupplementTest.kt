package com.openminis.app.ctx

import com.openminis.app.ctx.CtxKit.Preset
import com.openminis.app.data.ContextSizeMeter
import com.openminis.app.imagerepro.ImageRealFixtures
import com.openminis.app.imagerepro.ImageRealJvmCodec
import com.openminis.app.provider.ImageBudget
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Checks around the C01–C32 table:
 *  - the Fixture Kit itself (A1–A4 generators produce what they claim);
 *  - every A1 format / variant attached as an image, live and after restart
 *    (the format matrix, written to build/ctx-report/formats.md);
 *  - A5 tool products that reach the model as text (file_read, shell, MCP JSON
 *    with base64) and the model-use → read_image path.
 */
class CtxSupplementTest {

    private val dims = ImageRealFixtures::dims

    // ── the kit ─────────────────────────────────────────────────────────────

    @Test fun `A1 generators produce real images of the requested size and format`() {
        assertEquals("jpeg 4032x3024", dims(CtxKit.jpeg(Preset.CAMERA_4032x3024))!!.let { "${it.format} ${it.width}x${it.height}" })
        assertEquals("jpeg 9000x6000", dims(CtxKit.jpeg(Preset.VIEWPORT_BIG_9000x6000))!!.let { "${it.format} ${it.width}x${it.height}" })
        assertEquals("png 1x9000", dims(CtxKit.png(Preset.TALL_1x9000))!!.let { "${it.format} ${it.width}x${it.height}" })
        assertEquals("png 9000x300", dims(CtxKit.png(Preset.WIDE_9000x300))!!.let { "${it.format} ${it.width}x${it.height}" })
        assertEquals("png 2760x21891", dims(CtxKit.png(Preset.FULLPAGE_IOS_2760x21891))!!.let { "${it.format} ${it.width}x${it.height}" })
        assertEquals("gif 300x200", dims(CtxKit.gif(300, 200, 3))!!.let { "${it.format} ${it.width}x${it.height}" })
        assertEquals("bmp 64x48", dims(CtxKit.bmp(64, 48))!!.let { "${it.format} ${it.width}x${it.height}" })
        for (b in listOf(CtxKit.jpeg(40, 30), CtxKit.png(40, 30, alpha = true), CtxKit.gif(40, 30, 3), CtxKit.bmp(40, 30), CtxKit.tiff(40, 30))) {
            assertTrue("decodable by the JDK", CtxRequestView.jdkDecodes(b))
        }
        assertEquals("image/tiff", CtxRequestView.magic(CtxKit.tiff(40, 30)))
        // Header-only formats are recognisable but not decodable.
        assertEquals("image/heic", CtxRequestView.magic(CtxKit.heicHeaderOnly())); assertFalse(CtxRequestView.jdkDecodes(CtxKit.heicHeaderOnly()))
        assertEquals("image/webp", CtxRequestView.magic(CtxKit.webpHeaderOnly())); assertFalse(CtxRequestView.jdkDecodes(CtxKit.webpHeaderOnly()))
        for (o in 1..8) assertEquals(o, dims(CtxKit.exifJpeg(160, 120, o))!!.exifOrientation)
        assertEquals(1024, CtxKit.truncated(CtxKit.jpeg(1200, 900)).size)
        assertEquals("unknown", CtxRequestView.magic(CtxKit.notImage()))
    }

    @Test fun `A1 GIF has the requested frames and PNG alpha is real`() {
        val gif = CtxKit.gif(50, 40, frames = 3)
        // Three image descriptors (0x2C) after the header / colour tables.
        assertTrue(gif.count { it == 0x2C.toByte() } >= 3)
        val png = CtxKit.png(20, 10, alpha = true)
        assertEquals(6, png[25].toInt()) // IHDR colour type 6 = RGBA
    }

    @Test fun `A3 and A4 fixtures`() {
        assertEquals("ftyp", String(CtxKit.mp4HeaderOnly().bytes!!, 4, 4))
        assertTrue(String(CtxKit.pdf().bytes!!, Charsets.ISO_8859_1).startsWith("%PDF-1.4") && String(CtxKit.pdf().bytes!!, Charsets.ISO_8859_1).trimEnd().endsWith("%%EOF"))
        assertEquals("WAVE", String(CtxKit.wav().bytes!!, 8, 4))
        assertEquals(2, java.util.zip.ZipInputStream(CtxKit.zip().bytes!!.inputStream()).let { z -> generateSequence { z.nextEntry }.count() })
        val dir = ImageRealFixtures.tempDir("ctxkit")
        try {
            val big = CtxKit.sparse(dir, "x.bin", 5L * 1024 * 1024 * 1024)
            assertEquals(5L * 1024 * 1024 * 1024, big.size)
        } finally { dir.deleteRecursively() }
        val shot = CtxKit.browserScreenshot(1080, 2400, fullPage = false)
        val jpeg = java.util.Base64.getDecoder().decode(shot.base64Image)
        assertEquals("jpeg 1080x2400", dims(jpeg)!!.let { "${it.format} ${it.width}x${it.height}" })
        assertFalse("NO_WRAP like BrowserUseManager", shot.base64Image!!.contains('\n'))
        assertEquals(200_000, CtxKit.Text.long(200_000).length)
        assertEquals(2_000_000, CtxKit.Text.long(2_000_000).length)
        assertEquals(10_000, CtxKit.Text.cjk(10_000).length)
    }

    // ── A1 format matrix ────────────────────────────────────────────────────

    private data class Fmt(val label: String, val name: String, val mime: String, val bytes: ByteArray, val expected: String)

    /**
     * Each A1 format / variant attached alone. Expected = "live → restart" as
     * the Anthropic body carries it: `WxH mime` per image, `ph` = the format
     * gate's placeholder, `-` = nothing. Measured on this code (JVM codec).
     */
    private val formats = listOf(
        Fmt("JPEG 1600×1200", "a.jpg", "image/jpeg", CtxKit.jpeg(1600, 1200), "1600x1200 image/jpeg → 1600x1200 image/jpeg"),
        Fmt("JPEG CAMERA", "cam.jpg", "image/jpeg", CtxKit.jpeg(Preset.CAMERA_4032x3024), "2000x1500 image/jpeg → 2000x1500 image/jpeg"),
        Fmt("PNG opaque 3000", "o.png", "image/png", CtxKit.png(3000, 2000), "2000x1333 image/png → 2000x1333 image/png"),
        Fmt("PNG alpha 800", "a.png", "image/png", CtxKit.png(800, 600, alpha = true), "800x600 image/png → 800x600 image/png"),
        Fmt("GIF 3 frames 640", "g.gif", "image/gif", CtxKit.gif(640, 480, 3), "640x480 image/gif → 640x480 image/gif"),
        // The format gate converts a decodable BMP to JPEG at the provider boundary.
        Fmt("BMP 640", "b.bmp", "image/bmp", CtxKit.bmp(640, 480), "640x480 image/jpeg → 640x480 image/jpeg"),
        Fmt("TIFF 640", "t.tiff", "image/tiff", CtxKit.tiff(640, 480), "ph → ph"),
        // WebP is an accepted format: the gate passes it by magic without decoding, so an
        // undecodable WebP reaches the wire as is (sizes unknown).
        Fmt("WebP (header only)", "w.webp", "image/webp", CtxKit.webpHeaderOnly(), "nullxnull image/webp → nullxnull image/webp"),
        Fmt("HEIC (header only)", "h.heic", "image/heic", CtxKit.heicHeaderOnly(), "ph → ph"),
        Fmt("TINY 10×10", "t.png", "image/png", CtxKit.png(Preset.TINY_10x10), "10x10 image/png → 10x10 image/png"),
        Fmt("WIDE 9000×300", "w.png", "image/png", CtxKit.png(Preset.WIDE_9000x300), "2000x67 image/png → 2000x67 image/png"),
        Fmt("TALL 1×9000", "tall.png", "image/png", CtxKit.png(Preset.TALL_1x9000), "1x2000 image/png → 1x2000 image/png"),
        Fmt("EXIF=3 small", "e3.jpg", "image/jpeg", CtxKit.exifJpeg(1600, 1200, 3), "1600x1200 image/jpeg → 1600x1200 image/jpeg"),
        Fmt("JPEG bytes named .png", "x.png", "image/png", CtxKit.jpeg(640, 480), "640x480 image/jpeg → 640x480 image/jpeg"),
        Fmt("PNG bytes named .jpg", "x.jpg", "image/jpeg", CtxKit.png(640, 480), "640x480 image/png → 640x480 image/png"),
        Fmt("HEIC bytes named .jpg", "h.jpg", "image/jpeg", CtxKit.heicHeaderOnly(), "ph → ph"),
    )

    @Test fun `A1 format matrix - every format attached alone, live and after restart`() {
        val rows = mutableListOf<String>()
        val wrong = mutableListOf<String>()
        ImageRealJvmCodec.with { _ ->
            for (f in formats) CtxSession("fmt").use { s ->
                s.user("format ${f.label}", listOf(CtxKit.image(f.name, f.bytes, f.mime)))
                fun outcome(): String {
                    val view = CtxRequestView.parse(CtxProvider.ANTHROPIC, CtxSession.wire(CtxProvider.ANTHROPIC, s.request().messages))
                    val imgs = CtxRequestView.images(view).map { "${it.w}x${it.h} ${it.mime}" }
                    val ph = CtxRequestView.placeholders(view).map { "ph" }
                    return (imgs + ph).joinToString(", ").ifEmpty { "-" }
                }
                val live = outcome()
                s.restart()
                val got = "$live → ${outcome()}"
                rows += "| ${f.label} | `${f.name}` ${f.mime} | $got |"
                if (got != f.expected) wrong += "${f.label}: expected <${f.expected}> but was <$got>"
            }
        }
        java.io.File("build/ctx-report").mkdirs()
        java.io.File("build/ctx-report/formats.md").writeText("| format | attached as | Anthropic live → restart |\n|---|---|---|\n" + rows.joinToString("\n") + "\n")
        println(rows.joinToString("\n"))
        assertTrue(wrong.joinToString("\n"), wrong.isEmpty())
    }

    // ── P-a5: the request budget measures what the format gate sends ───────

    /**
     * [T-request-budget-post-gate] Six 1100×1100 BMPs (~3.6 MB each, under the
     * 3.75 MB per-image cap, so the compose budget keeps them as BMP). Their raw
     * base64 is ~29 MB, but the provider's format gate sends each as a small
     * JPEG. The request budget now measures that output, so nothing is elided:
     * before this fix it dropped one for a request that left at ~236 KB.
     */
    @Test fun `P-a5 the request budget measures what the format gate sends - six BMPs all go out`() {
        ImageRealJvmCodec.with { _ ->
            CtxSession("pa5").use { s ->
                for (i in 1..6) {
                    s.user("bmp $i", listOf(CtxKit.image("b$i.bmp", CtxKit.bmp(1100, 1100), "image/bmp")))
                    s.assistant("ok $i")
                }
                s.user("compare")
                val ctx = s.request()
                val raw = s.history.flatMap { it.imageParts }.sumOf { ImageBudget.estimatedBase64Length(it.data.size) }
                for (p in CtxProvider.entries) {
                    val view = CtxRequestView.parse(p, CtxSession.wire(p, ctx.messages))
                    val wire = CtxRequestView.images(view)
                    println("[P-a5] ${p.id}: plan=${ctx.diagnostics.budget} | raw base64 $raw | wire ${wire.size} images, ${wire.sumOf { it.base64Len }} base64 chars, mimes=${wire.map { it.mime }.distinct()}")
                    assertEquals("${p.id}: all six images reach the wire", 6, wire.size)
                    assertEquals("${p.id}: no elision placeholder", 0, CtxRequestView.placeholders(view).size)
                    assertTrue("${p.id}: every BMP left as JPEG", wire.all { it.mime == "image/jpeg" })
                    assertTrue("${p.id}: the wire is far under 25 MB", wire.sumOf { it.base64Len.toLong() } < 5L * 1024 * 1024)
                }
                assertTrue("fixture: the raw images are over the 25 MB budget", raw > ImageBudget.MAX_REQUEST_BYTES)
                assertEquals("the budget elided nothing", null, ctx.diagnostics.budget)
            }
        }
    }

    /**
     * [T-request-budget-post-gate] Drops are decided per position (message
     * index + part index), not per array identity: the same array in two
     * messages is two images, and only the occurrence that does not fit is
     * elided. Keyed by identity, both occurrences were replaced.
     */
    @Test fun `P-a5 request budget drops by position, not by array identity`() {
        val mb = 1024 * 1024
        // Undecodable on purpose, so the gate cannot shrink them: each goes out as is.
        fun blob(n: Int) = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + ByteArray(n)
        val shared = blob(3 * mb)
        fun turn(img: ByteArray, path: String) = com.openminis.app.data.model.LLMMessage(
            com.openminis.app.data.model.LLMMessage.Role.USER, "",
            contentParts = listOf(com.openminis.app.data.model.AgentContentPart.ImageData(img, "image/png", linuxPath = path)),
        )
        // eldest: shared; then six 3 MB fillers; latest: shared again. 8 × 4 MB base64 > 25 MB.
        val history = listOf(turn(shared, "/p/shared-old.png")) + (0 until 6).map { turn(blob(3 * mb + it), "/p/f$it.png") } +
            listOf(turn(shared, "/p/shared-new.png"))
        val applied = ImageBudget.applyRequestBudget(history) { null }
        val plan = applied.plan!!
        fun elided(i: Int) = applied.messages[i].contentParts.none { it is com.openminis.app.data.model.AgentContentPart.ImageData }
        println("[P-a5 ids] dropped=${plan.droppedIndices.sorted()} of ${plan.totalCount}")
        assertEquals("the two eldest positions", setOf(0, 1), plan.droppedIndices)
        assertTrue("eldest occurrence of the shared array elided", elided(0))
        assertFalse("latest occurrence of the same array kept", elided(7))
        assertTrue(applied.messages[0].contentParts.single().let { it is com.openminis.app.data.model.AgentContentPart.Text && it.text.contains("/p/shared-old.png") })
    }

    // ── A5 tool products ────────────────────────────────────────────────────

    @Test fun `A5 text tool products go out verbatim, and a generated image reaches the model only through read_image`() {
        ImageRealJvmCodec.with { _ ->
            CtxSession("tools").use { s ->
                val big = CtxKit.bigText(5_000)
                val mcp = CtxKit.mcpJsonWithBase64()
                s.user("Read the log, list files, call the MCP tool, and generate an image")
                val read = CtxSession.Call("call_read", "file_read", JSONObject().put("path", "/var/minis/workspace/log.txt"))
                val shell = CtxSession.Call("call_sh", "shell_execute", JSONObject().put("command", "ls -la"))
                val mcpCall = CtxSession.Call("call_mcp", "shell_execute", JSONObject().put("command", "minis-mcp call img.render"))
                val gen = CtxSession.Call("call_gen", "shell_execute", JSONObject().put("command", "minis-model-use image 'a cat' -o /var/minis/workspace/gen.png"))
                s.assistant("", read, shell, mcpCall, gen)
                // model-use writes a file and reports its path as shell text (offload/ModelUseManager).
                val genFile = java.io.File(s.filesDir, "minis-sessions/${s.sid}/workspace/gen.png").apply { parentFile.mkdirs(); writeBytes(CtxKit.png(1024, 1024)) }
                s.toolResults(
                    read to s.text(big),
                    shell to s.text("total 8\ndrwxr-xr-x  2 u u 4096 .\n"),
                    mcpCall to s.text(mcp),
                    gen to s.text("Saved /var/minis/workspace/gen.png (1024x1024)"),
                )
                val r1 = s.request()
                for (p in CtxProvider.entries) {
                    val view = CtxRequestView.parse(p, CtxSession.wire(p, r1.messages))
                    val text = view.flatMap { m -> m.parts.mapNotNull { it.text } }.joinToString("\n")
                    assertTrue("${p.id}: file_read output whole", text.contains(big))
                    assertTrue("${p.id}: MCP base64 stays text", text.contains(mcp))
                    assertEquals("${p.id}: no image yet", 0, CtxRequestView.images(view).size)
                    assertEquals("${p.id}: adjacency", emptyList<String>(), CtxRequestView.adjacency(view))
                }
                // The GH#352 payload audit does NOT flag the base64 text: it is "suspicious" at
                // >= 64 bytes per estimated token, and without a loaded vocabulary BPETokenizer
                // estimates code points / 3, i.e. 3 bytes per token for base64.
                val audit = r1.diagnostics.audit.map { "${it.kind}:${it.id}:suspicious=${it.finding.suspicious}:dangerous=${it.finding.dangerous}" }
                println("[A5] audit=$audit estimate=${ContextSizeMeter.estimateTokens(r1.outbound)}")
                // The 5,000-line file_read (~330 KB) is over half the 200k window: "dangerous".
                assertEquals(listOf("tool_result:call_read:suspicious=false:dangerous=true"), audit)

                // The agent then reads the generated file.
                val readGen = CtxSession.Call("call_ri", "read_image", JSONObject().put("path", "/var/minis/workspace/gen.png"))
                s.assistant("", readGen)
                s.toolResults(readGen to s.readImage(genFile, "/var/minis/workspace/gen.png"))
                val live = CtxRequestView.parse(CtxProvider.ANTHROPIC, CtxSession.wire(CtxProvider.ANTHROPIC, s.request().messages))
                assertEquals(listOf("1024x1024"), CtxRequestView.images(live).map { "${it.w}x${it.h}" })
                s.restart()
                val after = CtxRequestView.parse(CtxProvider.ANTHROPIC, CtxSession.wire(CtxProvider.ANTHROPIC, s.request().messages))
                assertEquals("tool images are not persisted (#412 / P-a2)", 0, CtxRequestView.images(after).size)
            }
        }
    }
}
