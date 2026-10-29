package com.openminis.app.imagerepro

import com.openminis.app.data.model.LLMMessage
import com.openminis.app.imagerepro.ImageReproFixtures.WireImage
import com.openminis.app.provider.ImageBudget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Test

/**
 * Image-pipeline repro, Android: what each user scenario puts on the wire of
 * EVERY serializer (Anthropic / OpenAI Chat / OpenAI Responses / Gemini), on
 * the live turn and after a persist → reload, and in what message structure.
 *
 * (A) characterization tests pass on today's code and pin today's behaviour.
 * (B) desired-behaviour tests are @Ignore("known gap …") and fail today.
 *
 * How the image bytes are chosen, honestly:
 *  - The live turn's bytes come out of a decode+scale step that cannot run in a
 *    JVM test (BitmapFactory is stubbed). They are therefore REAL images
 *    generated at the size the production code would produce on a device,
 *    computed with the production arithmetic: ImageBudget.fitWithin (round)
 *    for downscaleForModel; floor(w*scale) for ReadImageTool /
 *    resizeJpegToMaxEdge (that expression is pinned by
 *    ImageReproHistoryFactsTest). Bytes on a device will differ; sizes and
 *    formats will not.
 *  - The serializer and the gate (ImageBudget.prepareForUpload) DO run for
 *    real. For compliant JPEG/PNG the gate is a pass-through on a device too,
 *    so what is measured below is what a device sends.
 * Each test prints `[REPRO] …` rows.
 */
class ImageReproWireMatrixTest {

    private fun report(scenario: String, path: String, provider: String, imgs: List<WireImage>, extra: String = "") {
        val cells = if (imgs.isEmpty()) "0 images" else imgs.joinToString(" ; ") { w ->
            val d = w.dim
            val flags = buildList {
                if (d != null && d.longEdge > 8192) add(">8192")
                if (d != null && d.longEdge > 2000) add(">2000")
                if (w.bytes.size > 3_500_000) add(">3.5MB")
            }
            "${w.mime} ${d?.let { "${it.width}x${it.height}" } ?: "?"} ${w.bytes.size}B b64=${w.base64Length}" +
                (if (flags.isEmpty()) "" else " FLAGS${flags}")
        }
        println("[REPRO] $scenario | $path | $provider | $cells${if (extra.isEmpty()) "" else " | $extra"}")
    }

    private fun fitRound(w: Int, h: Int) = ImageBudget.fitWithin(w, h, 2000) ?: (w to h)
    private fun fitFloor(w: Int, h: Int): Pair<Int, Int> {
        if (maxOf(w, h) <= 2000) return w to h
        val scale = 2000f / maxOf(w, h)
        return (w * scale).toInt() to (h * scale).toInt()
    }

    /** Every provider body carries exactly [n] images, each [w]×[h] [mime]. */
    private fun assertWire(
        scenario: String, path: String, msgs: List<LLMMessage>, n: Int, w: Int? = null, h: Int? = null, mime: String? = null,
    ): Map<String, List<WireImage>> {
        val out = linkedMapOf<String, List<WireImage>>()
        for ((p, body) in ImageReproWire.all(msgs)) {
            val imgs = ImageReproFixtures.wireImages(body)
            report(scenario, path, p, imgs)
            assertEquals("$scenario/$path/$p image count", n, imgs.size)
            for (img in imgs) {
                if (mime != null) assertEquals("$scenario/$path/$p mime", mime, img.mime)
                if (w != null) assertEquals("$scenario/$path/$p width", w, img.dim!!.width)
                if (h != null) assertEquals("$scenario/$path/$p height", h, img.dim!!.height)
            }
            out[p] = imgs
        }
        return out
    }

    // ── S1 browser full-page screenshot (tool image) ────────────────────────

    /** (A) S1 / P-a2 / P-a7: live turn sends the floor-fitted JPEG; after reload the image is gone everywhere. */
    @Test fun `S1 full-page 1080x32768 - live 65x2000 jpeg on all four wires, after reload 0 images (P-a2)`() {
        val (w, h) = fitFloor(1080, 32768)
        assertEquals(65 to 2000, w to h)
        val sent = ImageReproFixtures.jpeg(w, h)
        val output = "[screenshot 1080x32768 full_page]\nminis_url: minis://browser/screenshot_1790000000.jpg"
        val live = listOf(ImageReproWire.leadingUser()) +
            ImageReproWire.liveToolRound("t1", "browser_use", output, sent, "image/jpeg", "/var/minis/browser/screenshot_1790000000.jpg")
        val liveWire = assertWire("S1 fullpage 1080x32768", "live", live, n = 1, w = 65, h = 2000, mime = "image/jpeg")
        assertTrue("every wire carries the same bytes", liveWire.values.map { it.single().bytes.toList() }.distinct().size == 1)

        val reloaded = listOf(ImageReproWire.leadingUser()) + ImageReproWire.rebuiltToolRound("t1", "browser_use", output)
        assertWire("S1 fullpage 1080x32768", "after-reload", reloaded, n = 0)
        // What survives is the tool's TEXT, including the minis_url of the saved original.
        for ((p, body) in ImageReproWire.all(reloaded)) {
            assertTrue("$p keeps the minis_url text", ImageReproFixtures.strings(body).any { "minis_url: minis://browser/" in it })
        }
    }

    /**
     * (A) S1 iOS-scale long screenshot attached by the USER (2760×21891 PNG):
     * downscaleForModel keeps PNG and fits with rounding → 252×2000 on both the
     * live turn and the rebuild; the note survives, the caption does not (N1).
     */
    @Test fun `S1 user-attached 2760x21891 png - live and reload both 252x2000 png, caption only on live (N1)`() {
        val (w, h) = fitRound(2760, 21891)
        assertEquals(252 to 2000, w to h)
        val sent = ImageReproFixtures.png(w, h)
        val path = "/var/minis/attachments/uploads/long.png"
        val note = ImageBudget.downscaleNote(ImageBudget.Downscaled(sent, "image/png", 2760, 21891, w, h), path)
        val live = assertWire("S1 attached 2760x21891", "live",
            listOf(ImageReproWire.liveUserTurn("read this", sent, "image/png", path, note)), 1, w, h, "image/png")
        val rebuilt = assertWire("S1 attached 2760x21891", "after-reload",
            listOf(ImageReproWire.rebuiltUserTurn("read this", sent, "image/png", path, note)), 1, w, h, "image/png")
        for (p in live.keys) {
            assertTrue("$p: same bytes live vs reload (same function, same original)",
                live.getValue(p).single().bytes.contentEquals(rebuilt.getValue(p).single().bytes))
        }
        val liveText = ImageReproFixtures.strings(ImageReproWire.anthropic(listOf(ImageReproWire.liveUserTurn("read this", sent, "image/png", path, note))))
        val reText = ImageReproFixtures.strings(ImageReproWire.anthropic(listOf(ImageReproWire.rebuiltUserTurn("read this", sent, "image/png", path, note))))
        assertTrue(liveText.any { it == "[attached image: $path]" })
        assertFalse("N1: the caption is not rebuilt", reText.any { it.startsWith("[attached image:") })
        assertTrue("the downscale note names the path on both", reText.any { "2760×21891" in it && path in it })
        println("[REPRO] N1 | anthropic text parts live=${liveText.size} reload=${reText.size}")
    }

    // ── S2 viewport screenshots ─────────────────────────────────────────────

    /** (A) S2: default mobile/desktop viewports and an 8250×11000 custom one, live; all gone after reload. */
    @Test fun `S2 viewport screenshots - live fitted on all wires, after reload 0 images (P-a2)`() {
        // Pixel 4a density 2.75: mobile 412x915 CSS, desktop 1280x800 CSS, set_viewport 3000x4000 CSS.
        val cases = listOf(
            "mobile 1133x2516" to (1133 to 2516),
            "desktop 3520x2200" to (3520 to 2200),
            "custom 8250x11000" to (8250 to 11000),
        )
        for ((name, size) in cases) {
            val (w, h) = fitFloor(size.first, size.second)
            val sent = ImageReproFixtures.jpeg(w, h)
            val live = listOf(ImageReproWire.leadingUser()) +
                ImageReproWire.liveToolRound("t1", "browser_use", "[screenshot $name]", sent, "image/jpeg", "/var/minis/browser/s.jpg")
            assertWire("S2 viewport $name", "live", live, n = 1, w = w, h = h, mime = "image/jpeg")
            assertTrue(maxOf(w, h) <= 2000)
            assertWire("S2 viewport $name", "after-reload",
                listOf(ImageReproWire.leadingUser()) + ImageReproWire.rebuiltToolRound("t1", "browser_use", "[screenshot $name]"), n = 0)
        }
    }

    // ── S3 / S4 user attachments ────────────────────────────────────────────

    /** (A) S3 camera 4032×3024 JPEG / S4 gallery 4000×6000 JPEG: 2000-fitted, identical live vs reload. */
    @Test fun `S3 camera and S4 gallery jpeg - fitted with rounding, same bytes live vs reload, one image per wire (P-a1)`() {
        for ((name, size) in listOf("S3 camera 4032x3024" to (4032 to 3024), "S4 gallery 4000x6000" to (4000 to 6000))) {
            val (w, h) = fitRound(size.first, size.second)
            val sent = ImageReproFixtures.jpeg(w, h)
            val path = "/var/minis/attachments/uploads/photo.jpg"
            val live = ImageReproWire.liveUserTurn("what is this", sent, "image/jpeg", path)
            // P-a1 shape: ONE array, two holders.
            assertTrue(live.imageParts.single().data === (live.contentParts.filterIsInstance<com.openminis.app.data.model.AgentContentPart.ImageData>().single().data))
            val lw = assertWire(name, "live", listOf(live), 1, w, h, "image/jpeg")
            val rw = assertWire(name, "after-reload", listOf(ImageReproWire.rebuiltUserTurn("what is this", sent, "image/jpeg", path)), 1, w, h, "image/jpeg")
            for (p in lw.keys) assertTrue(lw.getValue(p).single().bytes.contentEquals(rw.getValue(p).single().bytes))
        }
    }

    /**
     * (A) S4 HEIC that cannot be decoded here: the composer keeps the raw HEIC
     * (downscaleForModel → null), labels it image/heic (sniff has no MIME for
     * HEIF), and the gate replaces it with the unsendable placeholder on every
     * wire. On a device (API 28+) BitmapFactory decodes HEIC and the gate
     * transcodes it to JPEG instead — this row is the JVM path only.
     */
    @Test fun `S4 heic (header-only, undecodable on the JVM) - every wire gets the unsendable placeholder, never image-heic`() {
        val heic = ImageReproFixtures.heicHeaderOnly()
        assertEquals(ImageBudget.ImageFormat.HEIF, ImageBudget.sniffFormat(heic))
        assertEquals(null, ImageBudget.downscaleForModel(heic))
        for (path in listOf("live", "after-reload")) {
            val msg = if (path == "live") ImageReproWire.liveUserTurn("x", heic, "image/heic", "/var/minis/attachments/uploads/a.heic")
            else ImageReproWire.rebuiltUserTurn("x", heic, "image/heic", "/var/minis/attachments/uploads/a.heic")
            for ((p, body) in ImageReproWire.all(listOf(msg))) {
                val imgs = ImageReproFixtures.wireImages(body)
                report("S4 heic (JVM, undecodable)", path, p, imgs, extra = "placeholder")
                assertEquals(0, imgs.size)
                assertTrue(ImageReproFixtures.strings(body).any { it == ImageBudget.UNSENDABLE_IMAGE_PLACEHOLDER })
                assertFalse(body.toString().contains("image/heic"))
            }
        }
    }

    /** (A) S4 EXIF-6 photo ≤2000 px and a transparent PNG: passed through byte-for-byte, EXIF / alpha intact. */
    @Test fun `S4 exif-6 jpeg and transparent png under 2000 - gate passes bytes unchanged (P-a6 pass-through half)`() {
        val exif = ImageReproFixtures.jpegWithExifOrientation(400, 200, 6)
        assertEquals(6, ImageReproFixtures.dims(exif)!!.exifOrientation)
        val alpha = ImageReproFixtures.png(200, 200, alpha = true)
        for ((name, bytes, mime) in listOf(Triple("exif6 400x200", exif, "image/jpeg"), Triple("rgba png 200x200", alpha, "image/png"))) {
            val wires = assertWire("S4 $name", "live", listOf(ImageReproWire.liveUserTurn("x", bytes, mime, "/var/minis/attachments/uploads/a")), 1, mime = mime)
            for ((p, imgs) in wires) assertTrue("$p sends the original bytes", imgs.single().bytes.contentEquals(bytes))
        }
        // EXIF survives the gate, so the provider sees orientation 6 (portrait) for a SMALL photo.
        val sent = ImageReproFixtures.wireImages(ImageReproWire.openAIChat(listOf(ImageReproWire.liveUserTurn("x", exif, "image/jpeg", null)))).single()
        assertEquals(6, sent.dim!!.exifOrientation)
    }

    // ── S5 read_image ───────────────────────────────────────────────────────

    /** (A) S5: read_image of a 2760×21891 PNG → floor-fitted JPEG live; gone after reload; reading twice sends two copies. */
    @Test fun `S5 read_image big png - live 252x2000 jpeg, twice means two images, after reload 0 (P-a2)`() {
        val (w, h) = fitFloor(2760, 21891)
        val sent = ImageReproFixtures.jpeg(w, h)
        val meta = "[/var/minis/workspace/long.png | 2760x21891 | 210000 bytes]"
        val once = listOf(ImageReproWire.leadingUser()) + ImageReproWire.liveToolRound("r1", "read_image", meta, sent, "image/jpeg", null)
        assertWire("S5 read_image 2760x21891", "live", once, 1, w, h, "image/jpeg")
        // Same file read twice: ReadImageTool decodes again; two independent arrays.
        val second = ImageReproFixtures.jpeg(w, h)
        val twice = once + ImageReproWire.liveToolRound("r2", "read_image", meta, second, "image/jpeg", null)
        assertWire("S5 read_image twice", "live", twice, 2, w, h, "image/jpeg")
        assertWire("S5 read_image 2760x21891", "after-reload",
            listOf(ImageReproWire.leadingUser()) + ImageReproWire.rebuiltToolRound("r1", "read_image", meta), 0)
    }

    // ── structure: one image per user attachment on the wire ───────────────

    /**
     * (A) P-a1 wire half: the twin (imageParts + contentParts.ImageData) is NOT
     * sent twice — every serializer reads contentParts when present. Passing
     * the same parts again as the TOP-LEVEL imageParts argument (the agent loop
     * never does; ImageReproHistoryFactsTest pins that) would duplicate on the
     * OpenAI Responses wire only (measured: Chat Completions dedups, Anthropic
     * and Gemini ignore it once contentParts exist).
     */
    @Test fun `P-a1 the double-held image is sent once, top-level imageParts would duplicate on openai responses only`() {
        val img = ImageReproFixtures.jpeg(64, 32)
        val msg = ImageReproWire.liveUserTurn("look", img, "image/jpeg", "/var/minis/attachments/uploads/a.jpg")
        assertWire("P-a1 twin", "live", listOf(msg), 1)
        val dup = ImageReproWire.all(listOf(msg), imageParts = msg.imageParts).mapValues { ImageReproFixtures.wireImages(it.value).size }
        println("[REPRO] P-a1 top-level imageParts passed again | counts=$dup")
        assertEquals(mapOf("anthropic" to 1, "openai.chat" to 1, "openai.responses" to 2, "gemini" to 1), dup)
    }

    // ── (B) desired behaviour ───────────────────────────────────────────────

    /** (B) P-a2 / S7: a tool image the model saw on the live turn is still there after reload. */
    @Ignore("known gap #412 / P-a2: persistToolResultMessage stores tool text only; reload drops every tool image")
    @Test fun `desired - tool image survives reload on every wire`() {
        val sent = ImageReproFixtures.jpeg(65, 2000)
        val reloaded = listOf(ImageReproWire.leadingUser()) + ImageReproWire.rebuiltToolRound("t1", "browser_use", "shot")
        for ((p, body) in ImageReproWire.all(reloaded)) {
            assertEquals("$p carries the tool image after reload", 1, ImageReproFixtures.wireImages(body).size)
        }
        assertTrue(sent.isNotEmpty())
    }

    /** (B) N1: live and reloaded user turns put identical text parts on the wire. */
    @Ignore("known gap N1: the '[attached image: <path>]' caption is built only on the live turn and never persisted")
    @Test fun `desired - reloaded user turn keeps the attached-image caption`() {
        val img = ImageReproFixtures.jpeg(64, 32)
        val path = "/var/minis/attachments/uploads/a.jpg"
        val live = ImageReproFixtures.strings(ImageReproWire.openAIChat(listOf(ImageReproWire.liveUserTurn("x", img, "image/jpeg", path))))
        val re = ImageReproFixtures.strings(ImageReproWire.openAIChat(listOf(ImageReproWire.rebuiltUserTurn("x", img, "image/jpeg", path))))
        assertEquals(live.filterNot { it.startsWith("data:") }, re.filterNot { it.startsWith("data:") })
    }
}
