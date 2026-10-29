package com.openminis.app.ctx

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.imagerepro.ImageRealFixtures
import org.json.JSONArray
import org.json.JSONObject

/**
 * A8: one parsed shape for every provider's request body, the seven
 * invariant checkers, and the golden snapshot JSON.
 *
 * RequestView = [{role, parts:[{kind: text|image|toolUse|toolResult, text,
 * image:{w,h,mime,bytes,sha256,base64Len}, id}]}], in wire order. Mapping:
 *  - anthropic: messages[].content blocks; a tool_result's text is the part's
 *    text and its images follow it as image parts with `inResultOf` = its id;
 *  - openai.chat: assistant tool_calls → toolUse; role "tool" → toolResult;
 *    user content array / string → text / image;
 *  - openai.responses: input[] items, one RequestView message per item —
 *    function_call → assistant toolUse, function_call_output → role "tool"
 *    toolResult, message → its role;
 *  - gemini: contents[].parts; functionCall / functionResponse carry no id,
 *    so they pair by name (id = "fn:<name>"); an inline image right after a
 *    functionResponse is marked `inResultOf` it.
 */
object CtxRequestView {

    data class Image(
        val w: Int?, val h: Int?, val mime: String, val magic: String, val bytes: Int, val sha256: String, val base64Len: Int,
        /** Whether the JDK's ImageIO can decode the bytes (a stand-in for "a provider can decode it"). */
        val decodable: Boolean,
    ) {
        val longEdge: Int? get() = if (w == null || h == null) null else maxOf(w, h)
    }

    data class Part(val kind: String, val text: String? = null, val image: Image? = null, val id: String? = null, val inResultOf: String? = null)

    data class Message(val role: String, val parts: List<Part>)

    // ── parsing ─────────────────────────────────────────────────────────────

    fun parse(provider: CtxProvider, body: JSONObject): List<Message> = when (provider) {
        CtxProvider.ANTHROPIC -> anthropic(body)
        CtxProvider.OPENAI_CHAT -> openAIChat(body)
        CtxProvider.OPENAI_RESPONSES -> responses(body)
        CtxProvider.GEMINI -> gemini(body)
    }

    private fun image(mime: String, b64: String): Image {
        val bytes = java.util.Base64.getDecoder().decode(b64)
        val d = ImageRealFixtures.dims(bytes)
        return Image(d?.width, d?.height, mime, magic(bytes), bytes.size, ImageRealFixtures.sha256(bytes), b64.length, jdkDecodes(bytes))
    }

    private fun dataUrl(url: String): Image? {
        if (!url.startsWith("data:")) return null
        return image(url.substringAfter("data:").substringBefore(";"), url.substringAfter("base64,"))
    }

    private fun anthropic(body: JSONObject): List<Message> {
        val msgs = body.optJSONArray("messages") ?: JSONArray()
        return (0 until msgs.length()).map { i ->
            val m = msgs.getJSONObject(i)
            val parts = mutableListOf<Part>()
            when (val c = m.opt("content")) {
                is String -> parts += Part("text", c)
                is JSONArray -> for (j in 0 until c.length()) {
                    val b = c.getJSONObject(j)
                    when (b.optString("type")) {
                        "text" -> parts += Part("text", b.optString("text"))
                        "image" -> b.optJSONObject("source")?.let { s -> parts += Part("image", image = image(s.optString("media_type"), s.optString("data"))) }
                        "tool_use" -> parts += Part("toolUse", id = b.optString("id"), text = b.optString("name"))
                        "tool_result" -> {
                            val id = b.optString("tool_use_id")
                            val inner = b.opt("content")
                            val texts = mutableListOf<String>()
                            val imgs = mutableListOf<Part>()
                            if (inner is String) texts += inner
                            if (inner is JSONArray) for (k in 0 until inner.length()) {
                                val x = inner.getJSONObject(k)
                                when (x.optString("type")) {
                                    "text" -> texts += x.optString("text")
                                    "image" -> x.optJSONObject("source")?.let { s -> imgs += Part("image", image = image(s.optString("media_type"), s.optString("data")), inResultOf = id) }
                                }
                            }
                            parts += Part("toolResult", texts.joinToString("\n"), id = id)
                            parts += imgs
                        }
                        else -> parts += Part("other:" + b.optString("type"))
                    }
                }
            }
            Message(m.optString("role"), parts)
        }
    }

    private fun openAIChat(body: JSONObject): List<Message> {
        val msgs = body.optJSONArray("messages") ?: JSONArray()
        return (0 until msgs.length()).mapNotNull { i ->
            val m = msgs.getJSONObject(i)
            val role = m.optString("role")
            if (role == "system") return@mapNotNull null
            val parts = mutableListOf<Part>()
            if (role == "tool") {
                parts += Part("toolResult", m.optString("content"), id = m.optString("tool_call_id"))
                return@mapNotNull Message(role, parts)
            }
            when (val c = m.opt("content")) {
                is String -> if (c.isNotEmpty()) parts += Part("text", c)
                is JSONArray -> for (j in 0 until c.length()) {
                    val b = c.getJSONObject(j)
                    when (b.optString("type")) {
                        "text" -> parts += Part("text", b.optString("text"))
                        "image_url" -> dataUrl(b.getJSONObject("image_url").optString("url"))?.let { parts += Part("image", image = it) }
                        else -> parts += Part("other:" + b.optString("type"))
                    }
                }
            }
            m.optJSONArray("tool_calls")?.let { calls ->
                for (j in 0 until calls.length()) {
                    val tc = calls.getJSONObject(j)
                    parts += Part("toolUse", id = tc.optString("id"), text = tc.optJSONObject("function")?.optString("name"))
                }
            }
            Message(role, parts)
        }
    }

    private fun responses(body: JSONObject): List<Message> {
        val items = body.optJSONArray("input") ?: JSONArray()
        return (0 until items.length()).mapNotNull { i ->
            val it = items.getJSONObject(i)
            when (it.optString("type", "message")) {
                "function_call" -> Message("assistant", listOf(Part("toolUse", id = it.optString("call_id"), text = it.optString("name"))))
                "function_call_output" -> Message("tool", listOf(Part("toolResult", it.optString("output"), id = it.optString("call_id"))))
                "reasoning" -> null
                else -> {
                    val parts = mutableListOf<Part>()
                    when (val c = it.opt("content")) {
                        is String -> parts += Part("text", c)
                        is JSONArray -> for (j in 0 until c.length()) {
                            val b = c.getJSONObject(j)
                            when (b.optString("type")) {
                                "input_text", "output_text", "text" -> parts += Part("text", b.optString("text"))
                                "input_image" -> dataUrl(b.optString("image_url"))?.let { img -> parts += Part("image", image = img) }
                                else -> parts += Part("other:" + b.optString("type"))
                            }
                        }
                    }
                    Message(it.optString("role"), parts)
                }
            }
        }
    }

    private fun gemini(body: JSONObject): List<Message> {
        val contents = body.optJSONArray("contents") ?: JSONArray()
        return (0 until contents.length()).map { i ->
            val c = contents.getJSONObject(i)
            val ps = c.optJSONArray("parts") ?: JSONArray()
            // An inline image right after a functionResponse is that result's image
            // (GeminiProvider emits functionResponse then inlinePart), like Anthropic's nested one.
            var lastResult: String? = null
            val parts = (0 until ps.length()).map { j ->
                val p = ps.getJSONObject(j)
                if (!(p.has("inlineData") || p.has("inline_data")) && !p.has("functionResponse")) lastResult = null
                when {
                    p.has("text") -> Part("text", p.optString("text"))
                    p.has("inlineData") || p.has("inline_data") -> {
                        val s = p.optJSONObject("inlineData") ?: p.getJSONObject("inline_data")
                        Part("image", image = image(s.optString("mimeType", s.optString("mime_type")), s.optString("data")), inResultOf = lastResult)
                    }
                    p.has("functionCall") -> Part("toolUse", id = "fn:" + p.getJSONObject("functionCall").optString("name"), text = p.getJSONObject("functionCall").optString("name"))
                    p.has("functionResponse") -> {
                        val r = p.getJSONObject("functionResponse")
                        lastResult = "fn:" + r.optString("name")
                        Part("toolResult", r.optJSONObject("response")?.optString("result"), id = lastResult)
                    }
                    else -> Part("other")
                }
            }
            Message(c.optString("role"), parts)
        }
    }

    private val decodeCache = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    /** ImageIO can read the whole image (cached by content). */
    fun jdkDecodes(b: ByteArray): Boolean = decodeCache.getOrPut(ImageRealFixtures.sha256(b)) {
        if (b.isEmpty()) return@getOrPut false
        System.setProperty("java.awt.headless", "true")
        runCatching {
            Class.forName("javax.imageio.ImageIO").getMethod("read", java.io.InputStream::class.java).invoke(null, b.inputStream()) != null
        }.getOrDefault(false)
    }

    // ── invariants ──────────────────────────────────────────────────────────

    fun magic(b: ByteArray): String {
        fun s(i: Int, n: Int) = if (b.size >= i + n) String(b, i, n, Charsets.ISO_8859_1) else ""
        return when {
            b.size >= 3 && (b[0].toInt() and 0xFF) == 0xFF && (b[1].toInt() and 0xFF) == 0xD8 -> "image/jpeg"
            s(1, 3) == "PNG" -> "image/png"
            s(0, 3) == "GIF" -> "image/gif"
            s(0, 4) == "RIFF" && s(8, 4) == "WEBP" -> "image/webp"
            s(4, 4) == "ftyp" && s(8, 4) in setOf("heic", "heix", "mif1", "msf1", "hevc") -> "image/heic"
            s(0, 2) == "BM" -> "image/bmp"
            s(0, 4) == "II*\u0000" || s(0, 4) == "MM\u0000*" -> "image/tiff"
            else -> "unknown"
        }
    }

    /** ① Every toolUse group is followed IMMEDIATELY by results for all its ids (a trailing, unanswered group is mid-round and allowed). */
    fun adjacency(view: List<Message>): List<String> {
        val items = view.flatMap { m -> m.parts.filter { it.inResultOf == null }.map { m.role to it } }
        val problems = mutableListOf<String>()
        val seenUses = mutableSetOf<String>()
        var i = 0
        while (i < items.size) {
            val (_, p) = items[i]
            if (p.kind == "toolResult" && p.id !in seenUses) problems += "orphan result ${p.id}"
            if (p.kind != "toolUse") { i++; continue }
            val group = mutableListOf<String>()
            while (i < items.size && items[i].second.kind == "toolUse") { group += items[i].second.id!!; i++ }
            seenUses += group
            if (i >= items.size) break // trailing group: mid-round
            val results = mutableListOf<String>()
            var k = i
            while (k < items.size && items[k].second.kind == "toolResult") { results += items[k].second.id!!; k++ }
            val missing = group.filter { it !in results }
            if (missing.isNotEmpty()) {
                val next = items[i].second
                problems += "toolUse ${missing.joinToString(",")} not followed by its result (next: ${next.kind}${next.text?.let { " \"" + it.take(30).replace("\n", " ") + "…\"" } ?: ""})"
            }
        }
        return problems
    }

    data class Thresholds(val maxEdgeOver8192: Int, val maxEdgeOver2000: Int, val over3_5MB: Int, val overB64_5MB: Int, val totalB64: Long) {
        val totalOver25MB: Boolean get() = totalB64 > 25L * 1024 * 1024
    }

    /** ② Images over each catalog tier (8192 / 2000 px, 3.5 MB raw, 5 MB base64, 25 MB total). */
    fun thresholds(view: List<Message>): Thresholds {
        val imgs = images(view)
        return Thresholds(
            imgs.count { (it.longEdge ?: 0) > 8192 },
            imgs.count { (it.longEdge ?: 0) > 2000 },
            imgs.count { it.bytes > 3_500_000 },
            imgs.count { it.base64Len > 5 * 1024 * 1024 },
            imgs.sumOf { it.base64Len.toLong() },
        )
    }

    /** ③ Images whose declared MIME disagrees with their magic bytes. */
    fun mimeMismatches(view: List<Message>): List<String> =
        images(view).filter { it.mime != it.magic }.map { "${it.mime} on ${it.magic} bytes" }

    /** ⑤ The same bytes attached more than once in one request. */
    fun duplicates(view: List<Message>): Int {
        val shas = images(view).map { it.sha256 }
        return shas.size - shas.toSet().size
    }

    fun images(view: List<Message>): List<Image> = view.flatMap { m -> m.parts.mapNotNull { it.image } }

    val PLACEHOLDERS = listOf(
        "[image elided to fit 25MB request budget",
        "[Image omitted: its file format could not be converted",
        "[Image attached but this model does not support vision input]",
    )

    /** ⑦ Every placeholder text in the request, in order. */
    fun placeholders(view: List<Message>): List<String> =
        view.flatMap { m -> m.parts.mapNotNull { it.text } }
            .flatMap { t -> PLACEHOLDERS.flatMap { p -> Regex(Regex.escape(p) + "[^\\]]*\\]").findAll(t).map { it.value }.toList() } }

    /** Images in a history (each image once: contentParts if present, else imageParts). */
    fun historyImages(history: List<LLMMessage>): Int = history.sumOf { m ->
        if (m.contentParts.isNotEmpty()) {
            m.contentParts.count { it is AgentContentPart.ImageData } +
                m.contentParts.count { it is AgentContentPart.ToolResult && it.imageData != null }
        } else {
            m.imageParts.size
        }
    }

    // ── golden snapshot ─────────────────────────────────────────────────────

    private val UUID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

    /** Text as it goes in a snapshot: UUIDs / temp roots masked, long texts cut with their length and hash. */
    fun stableText(t: String, root: String?): String {
        var s = t
        if (root != null) s = s.replace(root, "<root>")
        s = UUID.replace(s, "<uuid>")
        return if (s.length <= 240) s else s.take(200) + "…[" + s.length + " chars sha256:" + ImageRealFixtures.sha256(s).take(12) + "]"
    }

    /** Keys sorted, timestamps / UUIDs out. [withBytes] = false is the cross-platform view (no sizes / hashes). */
    fun snapshot(view: List<Message>, root: String?, withBytes: Boolean = true): String {
        val arr = JSONArray()
        for (m in view) {
            val parts = JSONArray()
            for (p in m.parts) {
                val o = sortedMapOf<String, Any?>("kind" to p.kind)
                p.text?.let { o["text"] = stableText(it, root) }
                p.id?.let { o["id"] = it }
                p.inResultOf?.let { o["inResultOf"] = it }
                p.image?.let { img ->
                    val io = sortedMapOf<String, Any?>("w" to img.w, "h" to img.h, "mime" to img.mime, "magic" to img.magic, "decodable" to img.decodable)
                    if (withBytes) { io["bytes"] = img.bytes; io["base64Len"] = img.base64Len; io["sha256"] = img.sha256.take(16) }
                    o["image"] = JSONObject(io as Map<*, *>)
                }
                parts.put(JSONObject(o as Map<*, *>))
            }
            arr.put(JSONObject(sortedMapOf<String, Any?>("role" to m.role, "parts" to parts) as Map<*, *>))
        }
        return canonical(arr)
    }

    /** Pretty JSON with keys sorted at every level (org.json does not keep insertion order). */
    fun canonical(v: Any?, indent: String = ""): String = when (v) {
        is JSONObject -> if (v.length() == 0) "{}" else v.keys().asSequence().sorted().joinToString(",\n", "{\n", "\n$indent}") { k ->
            "$indent  ${JSONObject.quote(k)}: ${canonical(v.get(k), "$indent  ")}"
        }
        is JSONArray -> if (v.length() == 0) "[]" else (0 until v.length()).joinToString(",\n", "[\n", "\n$indent]") { "$indent  " + canonical(v.get(it), "$indent  ") }
        is String -> JSONObject.quote(v)
        JSONObject.NULL, null -> "null"
        else -> v.toString()
    }
}
