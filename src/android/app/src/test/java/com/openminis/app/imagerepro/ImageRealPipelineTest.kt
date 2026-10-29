package com.openminis.app.imagerepro

import com.openminis.app.data.ContextSizeMeter
import com.openminis.app.data.db.MessageEntity
import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.storage.MediaStore
import com.openminis.app.provider.ImageBudget
import com.openminis.app.tools.ReadImageTool
import com.openminis.app.ui.chat.ChatAttachmentIngest
import com.openminis.app.ui.chat.ChatHistoryRebuild
import com.openminis.app.ui.chat.ChatPartsJson
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Test

/**
 * Image-pipeline repro on the REAL production functions (round 2 of the
 * image-pipeline repro suite).
 *
 * Every scenario runs through the functions production calls:
 *  - user attachment: ChatAttachmentIngest.ingestImage (real MediaStore, real
 *    uploads dir) → ChatAttachmentIngest.liveUserTurn (what sendMessage appends)
 *    → ChatPartsJson.buildUserPartsJson (what is persisted) → MessageEntity →
 *    ChatHistoryRebuild.toLLMMessage (what a reload sends);
 *  - tool image: ImageBudget.resizeJpegToMaxEdge (browser_use) /
 *    ReadImageTool.imageResult (read_image) → ToolResult → ChatPartsJson
 *    .toolResultPartsJson (persisted) → ChatHistoryRebuild.toLLMMessage;
 *  - then all four real serializers (ImageRealWire).
 * Pixel work runs on [ImageRealJvmCodec] plugged into the production seam
 * ImageBudget.codec, so the production sizing / format / quality code runs; the
 * decoder itself is the JDK's (see that class for what that does NOT prove).
 *
 * (A) characterization tests pass on today's code; (B) desired tests are
 * @Ignore("known gap …"). Rows are printed as `[REAL] …` and compared with the
 * previous round's `[REPRO] …` rows.
 */
class ImageRealPipelineTest {

    private val F = ImageRealFixtures
    private val files = F.tempDir("imgreal")
    private val mediaStore = MediaStore(F.context(files))
    private val uploads = java.io.File(files, "minis-sessions/sess-1/attachments/uploads").apply { mkdirs() }
    private val noVision: (String?) -> String? = { null } // a native-vision main model

    @After fun cleanup() { files.deleteRecursively() }

    // ── helpers on the real functions ───────────────────────────────────────

    private class UserTurn(val ingested: ChatAttachmentIngest.IngestedImage, val live: LLMMessage, val rebuilt: LLMMessage, val persisted: String)

    /** Attach [raw] as the user would; return the live turn and the turn after persist → reload. */
    private fun userTurn(body: String, raw: ByteArray, mime: String, name: String): UserTurn {
        val ing = ChatAttachmentIngest.ingestImage(raw, mime, name, "sess-1", mediaStore, uploads)!!
        val live = ChatAttachmentIngest.liveUserTurn(
            body, listOf(ing.imagePart), listOfNotNull(ing.linuxPath), listOf(ing.modelNote), null, "u1", noVision,
        )
        val persisted = ChatPartsJson.buildUserPartsJson(body, listOf(ing.mediaRefPartJson))
        val rebuilt = ChatHistoryRebuild.toLLMMessage(entity("u1", "user", persisted), mediaStore.mediaBaseDir, noVision)
        return UserTurn(ing, live, rebuilt, persisted)
    }

    private fun entity(id: String, role: String, parts: String) =
        MessageEntity(id = id, sessionId = "sess-1", role = role, partsJson = parts, createdAt = 0L, sortOrder = 0)

    private class ToolRound(val live: List<LLMMessage>, val rebuilt: List<LLMMessage>, val persisted: String)

    /** A tool call whose result carries [image]; persisted and reloaded through the real functions. */
    private fun toolRound(tool: String, output: String, image: ByteArray?, path: String?): ToolRound {
        val use = LLMMessage(LLMMessage.Role.ASSISTANT, "", contentParts = listOf(AgentContentPart.ToolUse("t1", tool, org.json.JSONObject().put("a", 1))))
        val result = AgentContentPart.ToolResult("t1", tool, output, imageData = image, imageMimeType = image?.let { "image/jpeg" }, imageLinuxPath = path)
        val persisted = ChatPartsJson.toolResultPartsJson(listOf(result), mapOf("t1" to 1200L))
        val rebuiltResult = ChatHistoryRebuild.toLLMMessage(entity("u2", "user", persisted), mediaStore.mediaBaseDir, noVision)
        val lead = ImageRealWire.leadingUser()
        return ToolRound(
            live = listOf(lead, use, LLMMessage(LLMMessage.Role.USER, "", contentParts = listOf(result))),
            rebuilt = listOf(lead, use, rebuiltResult),
            persisted = persisted,
        )
    }

    private fun row(scenario: String, path: String, msgs: List<LLMMessage>): Map<String, List<ImageRealFixtures.WireImage>> {
        val out = linkedMapOf<String, List<ImageRealFixtures.WireImage>>()
        for ((p, body) in ImageRealWire.all(msgs)) {
            val imgs = F.wireImages(body)
            val cells = if (imgs.isEmpty()) "0 images" else imgs.joinToString(" ; ") { w ->
                val d = w.dim
                val flags = buildList {
                    if (d != null && d.longEdge > 8192) add(">8192")
                    if (d != null && d.longEdge > 2000) add(">2000")
                    if (w.bytes.size > 3_500_000) add(">3.5MB")
                }
                "${w.mime} ${d?.let { "${it.width}x${it.height}" } ?: "?"} ${w.bytes.size}B b64=${w.base64Length}" + (if (flags.isEmpty()) "" else " FLAGS$flags")
            }
            println("[REAL] $scenario | $path | $p | $cells")
            out[p] = imgs
        }
        return out
    }

    private fun assertAll(rows: Map<String, List<ImageRealFixtures.WireImage>>, n: Int, w: Int? = null, h: Int? = null, mime: String? = null) {
        for ((p, imgs) in rows) {
            assertEquals("$p count", n, imgs.size)
            for (i in imgs) {
                mime?.let { assertEquals("$p mime", it, i.mime) }
                w?.let { assertEquals("$p width", it, i.dim!!.width) }
                h?.let { assertEquals("$p height", it, i.dim!!.height) }
            }
        }
    }

    private fun onDisk(): List<String> = files.walkTopDown().filter { it.isFile }
        .map { it.relativeTo(files).path.replace(Regex("\\d{4}/\\d{2}/\\d{2}"), "<date>").replace(Regex("[0-9a-f-]{36}"), "<uuid>") + " " + it.length() + "B" }
        .sorted().toList()

    // ── S1' / S3 / S4: user attachments ─────────────────────────────────────

    /** (A) S1′ 2760×21891 PNG attached: real ingest → 252×2000 PNG + note; two original copies; reload sends the same bytes. */
    @Test fun `S1 attached 2760x21891 png - real ingest, live and reload identical bytes, caption only live (N1)`() = ImageRealJvmCodec.with { codec ->
        val raw = F.png(2760, 21891)
        val t = userTurn("read this", raw, "image/png", "long.png")
        println("[REAL] S1' codec ${codec.log}")
        val part = t.ingested.imagePart
        assertEquals(ImageRealFixtures.Dim("png", 252, 2000), F.dims(part.data))
        assertEquals("image/png", part.mimeType)
        assertEquals("/var/minis/attachments/uploads/long.png", t.ingested.linuxPath)
        assertTrue(t.ingested.modelNote!!.contains("the original is 2760×21891 px, shown here at 252×2000 px"))
        // Disk: the ORIGINAL twice (MediaStore + uploads), never the model copy.
        val disk = onDisk()
        println("[REAL] S1' disk $disk")
        assertEquals(listOf("media/<date>/sess-1/<uuid>.png ${raw.size}B", "minis-sessions/sess-1/attachments/uploads/long.png ${raw.size}B"), disk)
        // Reload rebuilds the same model copy (same function, same original, same codec).
        val livePart = t.live.imageParts.single(); val rePart = t.rebuilt.imageParts.single()
        assertTrue(livePart.data.contentEquals(rePart.data))
        assertEquals(livePart.mimeType, rePart.mimeType)
        val live = row("S1 attached 2760x21891", "live", listOf(t.live))
        val re = row("S1 attached 2760x21891", "after-reload", listOf(t.rebuilt))
        assertAll(live, 1, 252, 2000, "image/png"); assertAll(re, 1, 252, 2000, "image/png")
        // N1: the caption is on the live turn only.
        val liveText = t.live.contentParts.filterIsInstance<AgentContentPart.Text>().map { it.text }
        val reText = t.rebuilt.contentParts.filterIsInstance<AgentContentPart.Text>().map { it.text }
        println("[REAL] N1 live text parts=${liveText.size} reload text parts=${reText.size}")
        assertTrue(liveText.contains("[attached image: /var/minis/attachments/uploads/long.png]"))
        assertFalse(reText.any { it.startsWith("[attached image:") })
        assertEquals(liveText - "[attached image: /var/minis/attachments/uploads/long.png]", reText)
    }

    /** (A) S3 camera 4032×3024 / S4 gallery 4000×6000 JPEG: real ingest fits with rounding; reload identical. */
    @Test fun `S3 camera and S4 gallery jpeg - real ingest 2000x1500 and 1333x2000, identical after reload`() = ImageRealJvmCodec.with {
        for ((name, size) in listOf("S3 camera 4032x3024" to (4032 to 3024), "S4 gallery 4000x6000" to (4000 to 6000))) {
            val t = userTurn("what is this", F.jpeg(size.first, size.second), "image/jpeg", "photo.jpg")
            val (w, h) = ImageBudget.fitWithin(size.first, size.second, 2000)!!
            assertTrue(t.live.imageParts.single().data.contentEquals(t.rebuilt.imageParts.single().data))
            assertAll(row(name, "live", listOf(t.live)), 1, w, h, "image/jpeg")
            assertAll(row(name, "after-reload", listOf(t.rebuilt)), 1, w, h, "image/jpeg")
        }
    }

    /** (A) S4 EXIF-6 400×200 and RGBA 200×200 PNG: real ingest keeps the original bytes (no downscale), EXIF/alpha intact on every wire. */
    @Test fun `S4 small exif-6 jpeg and rgba png - real ingest passes the original bytes through`() = ImageRealJvmCodec.with {
        val exif = F.jpegWithExifOrientation(400, 200, 6)
        val rgba = F.png(200, 200, alpha = true)
        for ((name, raw, mime) in listOf(Triple("S4 exif6 400x200", exif, "image/jpeg"), Triple("S4 rgba png 200x200", rgba, "image/png"))) {
            val t = userTurn("x", raw, mime, "a")
            assertSame("no downscale: the attachment bytes ARE the model bytes", raw, t.ingested.imagePart.data)
            assertNull(t.ingested.modelNote)
            for ((_, imgs) in row(name, "live", listOf(t.live))) assertTrue(imgs.single().bytes.contentEquals(raw))
            for ((_, imgs) in row(name, "after-reload", listOf(t.rebuilt))) assertTrue(imgs.single().bytes.contentEquals(raw))
        }
        assertEquals(6, F.dims(F.wireImages(ImageRealWire.openAIChat(listOf(userTurn("x", exif, "image/jpeg", "e.jpg").rebuilt))).single().bytes)!!.exifOrientation)
    }

    /**
     * (A) S4 EXIF-6 camera photo 4032×3024: real ingest re-encodes it, and the
     * stored (landscape) orientation is what gets sized: 2000×1500 with no EXIF.
     * On the JVM that is this codec's doing; that BitmapFactory ALSO ignores
     * orientation is device-only (P-a6, androidTest ImageRealDeviceTest).
     */
    @Test fun `S4 big exif-6 photo - re-encoded at its stored orientation, EXIF gone (JVM codec)`() = ImageRealJvmCodec.with { codec ->
        val t = userTurn("x", F.jpegWithExifOrientation(4032, 3024, 6), "image/jpeg", "cam.jpg")
        val d = F.dims(t.ingested.imagePart.data)!!
        println("[REAL] S4 exif6 4032x3024 -> ${d.width}x${d.height} exif=${d.exifOrientation} codec=${codec.log}")
        assertEquals(2000 to 1500, d.width to d.height)
        assertNull(d.exifOrientation)
    }

    /** (A) S4 HEIC (header only; no JDK reader): real ingest keeps raw HEIC labelled image/heic; every wire gets the placeholder. */
    @Test fun `S4 heic - real ingest keeps image-heic, gate placeholders it on all four (JVM)`() = ImageRealJvmCodec.with {
        val heic = F.heicHeaderOnly()
        val t = userTurn("x", heic, "image/heic", "a.heic")
        assertSame(heic, t.ingested.imagePart.data)
        assertEquals("image/heic", t.ingested.imagePart.mimeType)
        for ((label, msg) in listOf("live" to t.live, "after-reload" to t.rebuilt)) {
            for ((p, body) in ImageRealWire.all(listOf(msg))) {
                println("[REAL] S4 heic (JVM, undecodable) | $label | $p | ${F.wireImages(body).size} images | placeholder")
                assertEquals(0, F.wireImages(body).size)
                assertTrue(F.strings(body).contains(ImageBudget.UNSENDABLE_IMAGE_PLACEHOLDER))
            }
        }
    }

    // ── S1 / S2: browser screenshots ────────────────────────────────────────

    /** (A) S1 full page 1080×32768: real resizeJpegToMaxEdge → 65×2000 (floor); real persist/reload → 0 images (P-a2). */
    @Test fun `S1 browser full-page 1080x32768 - real resize 65x2000, gone after real reload (P-a2, N4)`() = ImageRealJvmCodec.with { codec ->
        val raw = F.jpeg(1080, 32768, gray = true)
        val sent = ImageBudget.resizeJpegToMaxEdge(raw, 2000)!!
        println("[REAL] S1 resize codec ${codec.log}")
        assertEquals(listOf("decode(full)", "recycle", "compress(jpeg q=85)", "recycle"), codec.log.filterNot { it.startsWith("scale") })
        assertEquals(ImageRealFixtures.Dim("jpeg", 65, 2000), F.dims(sent))
        // N4: the attachment path rounds the same original to 66 wide.
        assertEquals(66 to 2000, ImageBudget.fitWithin(1080, 32768, 2000))
        val r = toolRound("browser_use", "[screenshot]\nminis_url: minis://browser/screenshot_1.jpg", sent, "/var/minis/browser/screenshot_1.jpg")
        assertAll(row("S1 fullpage 1080x32768", "live", r.live), 1, 65, 2000, "image/jpeg")
        assertAll(row("S1 fullpage 1080x32768", "after-reload", r.rebuilt), 0)
        for ((_, body) in ImageRealWire.all(r.rebuilt)) assertTrue(F.strings(body).any { "minis_url: minis://browser/" in it })
    }

    /** (A) S2 viewports: real resize of each capture size; all gone after real reload. */
    @Test fun `S2 viewport screenshots - real resize, gone after real reload`() = ImageRealJvmCodec.with {
        for ((name, size, want) in listOf(
            Triple("S2 viewport mobile 1133x2516", 1133 to 2516, 900 to 2000),
            Triple("S2 viewport desktop 3520x2200", 3520 to 2200, 2000 to 1250),
            Triple("S2 viewport custom 8250x11000", 8250 to 11000, 1500 to 2000),
        )) {
            val sent = ImageBudget.resizeJpegToMaxEdge(F.jpeg(size.first, size.second, gray = true), 2000)!!
            assertEquals(want, F.dims(sent)!!.let { it.width to it.height })
            val r = toolRound("browser_use", "[screenshot]", sent, "/var/minis/browser/s.jpg")
            assertAll(row(name, "live", r.live), 1, want.first, want.second, "image/jpeg")
            assertAll(row(name, "after-reload", r.rebuilt), 0)
        }
    }

    // ── S5: read_image ──────────────────────────────────────────────────────

    /** (A) S5 / P-a7: real ReadImageTool.imageResult on a 2760×21891 PNG — FULL decode, floor fit 252×2000 JPEG q85; twice = two arrays; gone after reload. */
    @Test fun `S5 read_image - real full decode, 252x2000 jpeg, twice is two copies, gone after reload (P-a7)`() = ImageRealJvmCodec.with { codec ->
        val file = java.io.File(files, "long.png").apply { writeBytes(F.png(2760, 21891)) }
        val first = ReadImageTool.imageResult(file, "/var/minis/workspace/long.png", "View")
        println("[REAL] S5 read_image codec ${codec.log}")
        assertEquals("P-a7: decodeFile with no sampling", "decodeFile(full)", codec.log.first())
        assertEquals("[/var/minis/workspace/long.png | 2760x21891 | ${file.length()} bytes]", first.output)
        assertEquals(ImageRealFixtures.Dim("jpeg", 252, 2000), F.dims(first.imageData!!))
        assertEquals("image/jpeg", first.imageMimeType)
        val second = ReadImageTool.imageResult(file, "/var/minis/workspace/long.png", "View")
        assertNotSame(first.imageData, second.imageData)
        val r = toolRound("read_image", first.output, first.imageData, null)
        assertAll(row("S5 read_image 2760x21891", "live", r.live), 1, 252, 2000, "image/jpeg")
        val twice = r.live + toolRound("read_image", second.output, second.imageData, null).live.drop(1).map {
            if (it.role == LLMMessage.Role.ASSISTANT) it.copy(contentParts = listOf(AgentContentPart.ToolUse("t2", "read_image", org.json.JSONObject())))
            else it.copy(contentParts = it.contentParts.map { p -> (p as AgentContentPart.ToolResult).copy(id = "t2") })
        }
        assertAll(row("S5 read_image twice", "live", twice), 2, 252, 2000, "image/jpeg")
        assertAll(row("S5 read_image 2760x21891", "after-reload", r.rebuilt), 0)
    }

    // ── P-a1 / N5 ───────────────────────────────────────────────────────────

    /** (A) P-a1 on the real live and rebuilt turns: one array in two holders, counted twice, sent once. */
    @Test fun `P-a1 real live and rebuilt turns hold the image twice, estimate counts it twice, wires send it once`() = ImageRealJvmCodec.with {
        val t = userTurn("look", F.jpeg(4032, 3024), "image/jpeg", "a.jpg")
        for ((label, m) in listOf("live" to t.live, "rebuilt" to t.rebuilt)) {
            val img = m.contentParts.filterIsInstance<AgentContentPart.ImageData>().single()
            assertSame("$label: same array in both holders", m.imageParts.single().data, img.data)
            val twice = ContextSizeMeter.estimateTokens(m)
            val once = ContextSizeMeter.estimateTokens(m.copy(imageParts = emptyList()))
            println("[REAL] P-a1 tokens $label | with imageParts=$twice without=$once imageTokens=${ContextSizeMeter.imageTokens(img.data)}")
            assertEquals(once + ContextSizeMeter.imageTokens(img.data), twice)
            assertAll(row("P-a1 twin", label, listOf(m)), 1)
        }
    }

    // ── P-a2 / P-a3: persistence and rebuild ────────────────────────────────

    /** (A) P-a2: the real persisted toolResult JSON carries no image; the real rebuild returns a text-only ToolResult. */
    @Test fun `P-a2 real persisted tool result has no image and rebuilds text-only`() {
        val r = toolRound("browser_use", "shot", F.jpeg(64, 32), "/var/minis/browser/s.jpg")
        println("[REAL] P-a2 persisted ${r.persisted}")
        val v = org.json.JSONArray(r.persisted).getJSONObject(0).getJSONObject("value")
        assertEquals(setOf("toolUseId", "name", "output", "success", "snapshot"), v.keys().asSequence().toSet())
        val rebuilt = r.rebuilt.last().contentParts.single() as AgentContentPart.ToolResult
        assertNull(rebuilt.imageData); assertNull(rebuilt.imageMimeType); assertNull(rebuilt.imageLinuxPath)
    }

    /** (A) P-a3: real rebuild with the media file gone drops the image with no trace; a missing pasted file gets a placeholder. */
    @Test fun `P-a3 real rebuild skips a missing image silently, unlike a missing pasted file`() {
        val parts = """[{"type":"text","value":"see"},{"type":"mediaRef","value":{"id":"x","relativePath":"gone/a.jpg","mimeType":"image/jpeg","linuxPath":"/var/minis/attachments/uploads/a.jpg"}},""" +
            """{"type":"mediaRef","value":{"id":"p","relativePath":"gone/p.txt","mimeType":"text/plain","originalFileName":"Pasted#1.txt"}}]"""
        val m = ChatHistoryRebuild.toLLMMessage(entity("u", "user", parts), mediaStore.mediaBaseDir, noVision)
        println("[REAL] P-a3 rebuilt ${F.dump(m)}")
        assertTrue(m.imageParts.isEmpty())
        assertTrue(m.contentParts.none { it is AgentContentPart.ImageData })
        assertFalse(m.contentParts.any { it is AgentContentPart.Text && it.text.contains("a.jpg") })
        assertTrue(m.contentParts.any { it is AgentContentPart.Text && it.text == com.openminis.app.ui.chat.PastedMedia.MISSING_PLACEHOLDER })
    }

    /** (A) P-a3: a second rebuild of the same file is served by the (path,size,mtime) cache — no decode — though the file was read again. */
    @Test fun `P-a3 second rebuild of the same original hits the downscale cache`() = ImageRealJvmCodec.with { codec ->
        val t = userTurn("x", F.png(2760, 21891), "image/png", "l.png")
        codec.log.clear()
        val again = ChatHistoryRebuild.toLLMMessage(entity("u1", "user", t.persisted), mediaStore.mediaBaseDir, noVision)
        assertEquals("no codec call: cached by file key", emptyList<String>(), codec.log)
        assertTrue(again.imageParts.single().data.contentEquals(t.rebuilt.imageParts.single().data))
    }

    // ── P-a5: request budget on real history ────────────────────────────────

    /**
     * (A) P-a5: real ImageBudget.applyRequestBudget measures what the gate sends
     * ([T-request-budget-post-gate]) and only touches contentParts (the imageParts
     * twin keeps its bytes). These header-only "PNGs" cannot be decoded, so the
     * gate cannot shrink them: each goes out at ~8.4 MB base64 and three fit
     * under 25 MB. (Before the fix the planner clamped each to 5 MB, kept five —
     * ~42 MB actually on the wire — and dropped one.)
     */
    @Test fun `P-a5 real applyRequestBudget - measured wire bytes, contentParts only, spillover for path-less images`() {
        val header = F.png(2760, 21891).copyOf(64)
        val big = (0 until 6).map { header + ByteArray(6 * 1024 * 1024 + it) }
        val history = big.mapIndexed { i, b ->
            ChatAttachmentIngest.liveUserTurn("m$i", listOf(LLMMessage.ImagePart(b, "image/png", linuxPath = if (i == 0) null else "/var/minis/attachments/uploads/$i.png")),
                if (i == 0) emptyList() else listOf("/var/minis/attachments/uploads/$i.png"), listOf(null), null, "u$i", noVision)
        }
        val root = java.io.File(files, "minis-sessions/sess-1/attachments")
        val applied = ImageBudget.applyRequestBudget(history) { root }
        val plan = applied.plan!!
        println("[REAL] P-a5 applyRequestBudget dropped=${plan.droppedCount}/${plan.totalCount} keptBytes=${plan.keptBytes} spillover=${java.io.File(root, "spillover").list()?.toList()}")
        assertEquals(3, plan.droppedCount)
        assertEquals(setOf(0, 1, 2), plan.droppedIndices)
        assertTrue("kept under the 25 MB request budget", plan.keptBytes <= ImageBudget.MAX_REQUEST_BYTES)
        val eldest = applied.messages.first()
        assertTrue(eldest.contentParts.none { it is AgentContentPart.ImageData })
        assertTrue(eldest.contentParts.any { it is AgentContentPart.Text && it.text.startsWith("[image elided to fit 25MB request budget. Original at /var/minis/attachments/spillover/") })
        assertSame("imageParts twin untouched", big[0], eldest.imageParts.single().data)
        assertEquals(1, java.io.File(root, "spillover").list()!!.size)
    }

    // ── (B) desired behaviour, on the real functions ────────────────────────

    @Ignore("known gap #412 / P-a2: toolResultPartsJson persists text only; the real rebuild has no image")
    @Test fun `desired - a tool image survives the real persist and rebuild`() {
        val r = toolRound("browser_use", "shot", F.jpeg(64, 32), "/var/minis/browser/s.jpg")
        assertNotNull((r.rebuilt.last().contentParts.single() as AgentContentPart.ToolResult).imageData)
    }

    @Ignore("known gap N1: the '[attached image: <path>]' caption is not persisted, so the real rebuild lacks it")
    @Test fun `desired - the real rebuilt user turn equals the live one`() = ImageRealJvmCodec.with {
        val t = userTurn("x", F.jpeg(64, 32), "image/jpeg", "a.jpg")
        assertEquals(F.dump(t.live.copy(dbMessageId = null)), F.dump(t.rebuilt.copy(dbMessageId = null)))
    }

    @Ignore("known gap P-a1: estimateTokens adds imageParts on top of contentParts.ImageData")
    @Test fun `desired - the real live turn counts its image once`() = ImageRealJvmCodec.with {
        val m = userTurn("x", F.jpeg(64, 32), "image/jpeg", "a.jpg").live
        assertEquals(ContextSizeMeter.estimateTokens(m.copy(imageParts = emptyList())), ContextSizeMeter.estimateTokens(m))
    }

    @Ignore("known gap P-a3: a missing image file is skipped with no placeholder")
    @Test fun `desired - a missing image leaves a visible placeholder after the real rebuild`() {
        val parts = """[{"type":"mediaRef","value":{"id":"x","relativePath":"gone/a.jpg","mimeType":"image/jpeg"}}]"""
        val m = ChatHistoryRebuild.toLLMMessage(entity("u", "user", parts), mediaStore.mediaBaseDir, noVision)
        assertTrue(m.contentParts.isNotEmpty())
    }

    /**
     * (B → fixed by [T-request-budget-post-gate]) Images the gate sends small are
     * not elided. Six 1100×1100 BMPs: ~29 MB raw base64, small JPEGs on the wire.
     * (The earlier fixture was header-only PNG bytes, which no decoder can shrink,
     * so it could never have shown this; the claim is unchanged.)
     */
    @Test fun `desired - images that gate small are not elided by the real request budget`() = ImageRealJvmCodec.with {
        val history = (0 until 6).map { LLMMessage(LLMMessage.Role.USER, "", contentParts = listOf(AgentContentPart.ImageData(F.bmp(1100, 1100), "image/bmp"))) }
        assertTrue(history.sumOf { ImageBudget.estimatedBase64Length((it.contentParts.single() as AgentContentPart.ImageData).data.size) } > ImageBudget.MAX_REQUEST_BYTES)
        assertNull(ImageBudget.applyRequestBudget(history) { files }.plan)
    }

    @Ignore("known gap P-a7: ReadImageTool decodes the whole file (decodeFile with no inSampleSize)")
    @Test fun `desired - read_image decodes sampled`() = ImageRealJvmCodec.with { codec ->
        val file = java.io.File(files, "long.png").apply { writeBytes(F.png(2760, 21891)) }
        ReadImageTool.imageResult(file, "/x", "t")
        assertTrue(codec.log.first().startsWith("decode(sample="))
    }
}
