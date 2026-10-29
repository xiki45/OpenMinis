package com.openminis.app.imagerepro

import com.openminis.app.data.db.MessageEntity
import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.MediaRef
import com.openminis.app.provider.ImageBudget
import com.openminis.app.tools.ReadImageTool
import com.openminis.app.ui.chat.ChatViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Behaviour-invariance evidence for the image-pipeline extraction
 * (refactor+test, no behaviour change).
 *
 * Every case runs through ChatViewModel's OWN private entry points —
 * escapeJson, buildUserPartsJson, buildMediaRefPartJson, uniqueUploadFileName,
 * MessageEntity.toLLMMessage, applyRequestImageBudget, resizeJpegToMaxEdge —
 * plus ReadImageTool.execute and the public ImageBudget functions, on fixed
 * inputs. The instance is allocated without its constructor and only the
 * fields those functions read are set. Before the extraction those entry points
 * held the code; after it they delegate to the extracted functions. [GOLDEN]
 * holds the sha256 of every output captured on the code BEFORE the extraction
 * (HEAD 3605f1fd1); the same harness must reproduce them after it.
 *
 * JVM note: BitmapFactory is stubbed here, so every decode returns null and
 * every resize is a no-op on both sides — those rows show the control flow is
 * unchanged, not that pixels are.
 */
class ImageRealInvarianceTest {

    private val F = ImageRealFixtures

    /** sha256 of each case's canonical output, captured before the extraction. */
    private val GOLDEN: Map<String, String> = mapOf(
        "escapeJson" to "0323faa76dffe7cbcab864e8cbf50a4998337b409bfd2e2297639f501cf2fb3e",
        "userParts.text+ref+xml" to "1960b86f0afbceb8e80bdc589c5b3d45cf0b7b09b53fc32dd73894f50df877f1",
        "userParts.emptyText+ref" to "291bd2a9d8fe7b2e13718fad8e45ee4e3145fe96a8deb8e46f9b818cf2ecbbb7",
        "userParts.emptyText+none" to "b943d37aa9529b95c86dc88f5cf69b962f4968aebfbf9bb47b88e2f140f6e0eb",
        "userParts.bodyParts" to "97df1b458b61e8c234d0174e813dff02e0153ee172d87835228dfed3ebc5816b",
        "mediaRef.full" to "3533e218804b06b3332f2712fa22ead816b804e077ccb590ba2355535e30a736",
        "mediaRef.bare" to "e1bc984b81ed0dd8af85bb1cd3adf328613501b7552765f17a6bd139f7e15d50",
        "uniqueName" to "9dea85fce8df778798f014c5989e7ad2978f08142ebe57c8e659e476d4458f21",
        "toLLMMessage.u1" to "33a365377f8f2acc0992a1a4ff833e3fead3de87d0fb97a0c89426d2d8e346d7",
        "toLLMMessage.u2" to "10905e47c82c08a3676d970cda57d2d048b8de607ae1821c5c5bb8411e5d9e8d",
        "toLLMMessage.u3" to "317ef7f2d2ea36485dbb823d3cc52d41bc13c723e0d1e7a318740e705d56062b",
        "toLLMMessage.u4" to "f7379a4abd1b2135270b51a8357b2ce0f2a6836b861b17246393058de456a0fb",
        "toLLMMessage.a1" to "d6ba4f8d286096d378e7ebe167918b6ea370ea1327417c08b56ad2c69de8513f",
        "toLLMMessage.u5" to "a21de287ea326a0bac0280006cc742c4e3c8d7847a6ae39545a453e5e0ae78ff",
        "toLLMMessage.u6" to "87190c014b213fb689b4d2454de903638d9fe23ce8cf615660ec13100425bcff",
        // [T-request-budget-post-gate] Changed on purpose: the budget now measures the
        // gate's output (these undecodable 6 MB images go out at ~8.4 MB, so fewer fit);
        // was ca7831113041c4f724c992da05919d968496a7e2f01dfdcba851df1744ae116c.
        "applyRequestImageBudget" to "e1d85bf6a8f895a565208420bec92ad3fefd8b03a10a3f60b0dafcd85959d7d7",
        // [T-request-budget-post-gate] Changed on purpose: the budget now measures the
        // gate's output (these undecodable 6 MB images go out at ~8.4 MB, so fewer fit);
        // was e0905eea8f888376191f3e167de17211d0d63aaf40e6de76b691a2493581aa02.
        "applyRequestImageBudget.spillover" to "3c65e2ed38399aafc17c5584e0408234596a9fb271adda00fedf3753ac6d1c5d",
        "resizeJpegToMaxEdge" to "74234e98afe7498fb5daf1f36ac2d78acc339464f950703b8c019892f982b90b",
        "readImage.blank" to "4181ac48432b2751e71c90902f333cbd5c6ccfae3995606f53c8dbbbb815355f",
        "readImage.badJson" to "8383a22c5054f4debe7602c4156c2d7bc04a499bb1678f40aea247278ee5e53f",
        "budget.prepare.jpegAsHeic" to "f3368e2d2d5530684ceb5070a6dfc644a5125f62800a8e56e5d44c5ea4d787f5",
        "budget.prepare.png" to "88292256934cff90424e7474059f8ddda0d446c39cd2643c6ba02cbd77d23402",
        "budget.prepare.bmp" to "74234e98afe7498fb5daf1f36ac2d78acc339464f950703b8c019892f982b90b",
        "budget.prepare.heic" to "74234e98afe7498fb5daf1f36ac2d78acc339464f950703b8c019892f982b90b",
        "budget.prepare.junk" to "409b2d82e03ca8231887333e0b639b19918697c3faf5c91a179bbc19332a3f52",
        "budget.prepare.big" to "eef5b997d8bcaf3e7d477f8cbc8f8c5ba33af485570a0f4dcbe7cc0fdf30a572",
        "budget.downscale.long" to "74234e98afe7498fb5daf1f36ac2d78acc339464f950703b8c019892f982b90b",
        "budget.downscale.file" to "74234e98afe7498fb5daf1f36ac2d78acc339464f950703b8c019892f982b90b",
        "budget.compressBytes" to "8ae17b40f54d1fc5d42d3ef9a7b4f1d1cbd54db9e9c0903a6e70e42f1e2c7b22",
        "budget.compressUnderBudget" to "7d575bea017171374ecd77958f8fa0d4fe618308716d0d06d753ed22e4886d40",
        "budget.applyMessageBudget" to "2b562435b911ba2b0bbdff62c2028ef43a3089fc42e4999b09d7a686ae7fe6c7",
        "budget.planRequest" to "29d9f358b785d1679c290debdc85fbd24fa36150f1e6d229dd1cfac624e5bbe4",
        "budget.spillover" to "d28ed4ca2ac0044bb283534a4730758e2c3649df5f5765de7d4e2079ee3df59a",
        "budget.elided" to "025f5a993ff1de4cb68294c28fdf4e4d0fe7680e2198013c5427177faa7ce060",
    )

    private fun vm(filesDir: java.io.File): Any {
        val vm = F.allocate(ChatViewModel::class.java)
        F.setField(vm, "context", F.context(filesDir))
        F.setField(vm, "mediaStore", com.openminis.app.data.storage.MediaStore(F.context(filesDir)))
        F.setField(vm, "currentModel", ImageRealWire.visionModel)
        F.setField(vm, "sessionId", "sess-1")
        F.setField(vm, "realSessionId", "sess-1")
        F.setField(vm, "_requestBudgetEvent", MutableSharedFlow<ImageBudget.RequestBudgetPlan>(extraBufferCapacity = 4))
        return vm
    }

    private fun entity(id: String, role: String, parts: String, reasoning: String? = null) =
        MessageEntity(id = id, sessionId = "sess-1", role = role, partsJson = parts, createdAt = 0L, sortOrder = 0, reasoningContent = reasoning)

    private fun outputs(): Map<String, String> {
        val out = linkedMapOf<String, String>()
        val files = F.tempDir("imgreal-inv")
        val vm = vm(files)

        // escapeJson
        out["escapeJson"] = F.callPrivate(vm, "escapeJson", "a\"b\\c\nd\re\tf\u0001g 中文 /x") as String

        // buildUserPartsJson
        val ref = """{"type":"mediaRef","value":{"id":"m1","relativePath":"2026/10/04/sess-1/m1.jpg","mimeType":"image/jpeg"}}"""
        out["userParts.text+ref+xml"] = F.callPrivate(vm, "buildUserPartsJson", "hi \"there\"", listOf(ref), "<user-attached-files>\n</user-attached-files>", null) as String
        out["userParts.emptyText+ref"] = F.callPrivate(vm, "buildUserPartsJson", "", listOf(ref), null, null) as String
        out["userParts.emptyText+none"] = F.callPrivate(vm, "buildUserPartsJson", "", emptyList<String>(), null, null) as String
        out["userParts.bodyParts"] = F.callPrivate(vm, "buildUserPartsJson", "ignored", listOf(ref), null, listOf("""{"type":"text","value":"a"}""", ref)) as String

        // buildMediaRefPartJson
        out["mediaRef.full"] = F.callPrivate(vm, "buildMediaRefPartJson", MediaRef("m1", "2026/10/04/sess-1/m1.jpg", "image/jpeg", "IMG 1.jpg"), "/var/minis/attachments/uploads/IMG_1.jpg") as String
        out["mediaRef.bare"] = F.callPrivate(vm, "buildMediaRefPartJson", MediaRef("m2", "r/m2.png", "image/png", null), null) as String

        // uniqueUploadFileName
        val up = java.io.File(files, "uploads").apply { mkdirs() }
        java.io.File(up, "a.jpg").writeText("x"); java.io.File(up, "a_1.jpg").writeText("x")
        out["uniqueName"] = listOf("a.jpg", "我的 图.png", "", "dir/x.y.z", "back\\slash.gif", "noext")
            .joinToString("|") { F.callPrivate(vm, "uniqueUploadFileName", up, it) as String }

        // toLLMMessage
        val media = java.io.File(files, "media").apply { mkdirs() }
        val jpg = F.jpeg(64, 32)
        val png = F.png(10, 10)
        val longPng = F.png(2760, 21891)
        fun put(rel: String, b: ByteArray) = java.io.File(media, rel).apply { parentFile.mkdirs(); writeBytes(b) }
        put("d/a.jpg", jpg); put("d/b.png", png); put("d/long.png", longPng)
        java.io.File(media, "d/p.txt").apply { parentFile.mkdirs(); writeText("pasted body") }
        val entities = listOf(
            entity("u1", "user", """[{"type":"text","value":"look"},{"type":"mediaRef","value":{"id":"a","relativePath":"d/a.jpg","mimeType":"image/jpeg","linuxPath":"/var/minis/attachments/uploads/a.jpg"}},{"type":"text","value":"<user-attached-files>\n  <file path=\"/var/minis/attachments/uploads/a.jpg\" />\n</user-attached-files>"}]"""),
            entity("u2", "user", """[{"type":"mediaRef","value":{"id":"b","relativePath":"d/b.png","mimeType":"image/png"}},{"type":"mediaRef","value":{"id":"l","relativePath":"d/long.png","mimeType":"image/png","linuxPath":"/var/minis/attachments/uploads/long.png"}}]"""),
            entity("u3", "user", """[{"type":"text","value":"gone"},{"type":"mediaRef","value":{"id":"x","relativePath":"d/missing.jpg","mimeType":"image/jpeg"}}]"""),
            entity("u4", "user", """[{"type":"text","value":"before "},{"type":"mediaRef","value":{"id":"p","relativePath":"d/p.txt","mimeType":"text/plain","originalFileName":"Pasted#1.txt"}},{"type":"mediaRef","value":{"id":"q","relativePath":"d/nope.txt","mimeType":"text/plain","originalFileName":"Pasted#2.txt"}},{"type":"mediaRef","value":{"id":"f","relativePath":"d/doc.pdf","mimeType":"application/pdf"}}]"""),
            entity("a1", "assistant", """[{"type":"text","value":"calling"},{"type":"toolUse","value":{"toolUseId":"t1","name":"browser_use","input":"{\"action\":\"screenshot\"}","thoughtSignature":"sig"}}]""", reasoning = "think"),
            entity("u5", "user", """[{"type":"toolResult","value":{"toolUseId":"t1","name":"browser_use","output":"shot\nminis_url: minis://browser/s.jpg","success":true}},{"type":"toolResult","value":{"toolUseId":"t2","name":"read_image","output":"Error","success":false}}]"""),
            entity("u6", "user", "not json"),
        )
        for (e in entities) {
            out["toLLMMessage.${e.id}"] = F.dump(F.callPrivate(vm, "toLLMMessage", e) as LLMMessage)
        }

        // applyRequestImageBudget: 7 × 6 MB-raw images, some without a path (spillover).
        val big = (0 until 7).map { i -> F.png(8, 8).copyOf(64) + ByteArray(6 * 1024 * 1024 + i) }
        val history = listOf(
            LLMMessage(LLMMessage.Role.USER, "u", contentParts = listOf(
                AgentContentPart.Text("u"),
                AgentContentPart.ImageData(big[0], "image/png", linuxPath = "/var/minis/attachments/uploads/0.png"),
                AgentContentPart.ImageData(big[1], "image/png"),
            )),
            LLMMessage(LLMMessage.Role.ASSISTANT, "", contentParts = listOf(AgentContentPart.ToolUse("t1", "browser_use", org.json.JSONObject()))),
            LLMMessage(LLMMessage.Role.USER, "", contentParts = listOf(
                AgentContentPart.ToolResult("t1", "browser_use", "shot", imageData = big[2], imageMimeType = "image/jpeg", imageLinuxPath = "/var/minis/browser/s.jpg"),
                AgentContentPart.ToolResult("t2", "read_image", "", imageData = big[3], imageMimeType = null),
            )),
            LLMMessage(LLMMessage.Role.USER, "x", contentParts = (4 until 7).map { AgentContentPart.ImageData(big[it], "image/png") }),
        )
        @Suppress("UNCHECKED_CAST")
        val budgeted = F.callPrivate(vm, "applyRequestImageBudget", history) as List<LLMMessage>
        out["applyRequestImageBudget"] = budgeted.joinToString("\n") { F.dump(it) }
        out["applyRequestImageBudget.spillover"] = java.io.File(files, "minis-sessions/sess-1/attachments/spillover")
            .listFiles().orEmpty().map { it.name + ":" + it.length() }.sorted().joinToString(",")

        // resizeJpegToMaxEdge (JVM: decode stub → null)
        out["resizeJpegToMaxEdge"] = (F.callPrivate(vm, "resizeJpegToMaxEdge", F.jpeg(64, 32), 2000) as ByteArray?)?.let { F.sha256(it) }.toString()

        // ReadImageTool: deterministic branches only (path resolution of other paths reads global mount state).
        out["readImage.blank"] = ReadImageTool.execute("""{"path":"","tool_title":"t"}""").let { "${it.output}|${it.success}|${it.toolTitle}" }
        out["readImage.badJson"] = ReadImageTool.execute("not json").let { "${it.output}|${it.success}" }

        // ImageBudget public functions (JVM stub path).
        val heicJpg = F.jpeg(64, 32)
        out["budget.prepare.jpegAsHeic"] = ImageBudget.prepareForUpload(heicJpg, "image/heic")!!.let { F.sha256(it.bytes) + "," + it.mimeType }
        out["budget.prepare.png"] = ImageBudget.prepareForUpload(png, "image/png")!!.let { F.sha256(it.bytes) + "," + it.mimeType }
        out["budget.prepare.bmp"] = ImageBudget.prepareForUpload(F.bmp(), "image/bmp").toString()
        out["budget.prepare.heic"] = ImageBudget.prepareForUpload(F.heicHeaderOnly(), "image/heic").toString()
        out["budget.prepare.junk"] = ImageBudget.prepareForUpload(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8), "image/png")!!.let { F.sha256(it.bytes) + "," + it.mimeType }
        out["budget.prepare.big"] = ImageBudget.prepareForUpload(big[0], "image/png")!!.let { F.sha256(it.bytes) + "," + it.mimeType }
        out["budget.downscale.long"] = ImageBudget.downscaleForModel(longPng).toString()
        out["budget.downscale.file"] = ImageBudget.downscaleForModel(java.io.File(media, "d/long.png"), longPng).toString()
        out["budget.compressBytes"] = F.sha256(ImageBudget.compressBytes(longPng))
        out["budget.compressUnderBudget"] = F.sha256(ImageBudget.compressUnderBudget(big[0]))
        out["budget.applyMessageBudget"] = ImageBudget.applyMessageBudget(big.take(6)).let { r -> "${r.keptBytes.map { F.sha256(it) }}|${r.compressedCount}|${r.droppedCount}|${r.totalBytes}" }
        out["budget.planRequest"] = ImageBudget.planRequestBudget(big.map { ImageBudget.BudgetImage(it, null, "image/png") })
            .let { "${it.droppedCount}|${it.keptBytes}|${it.elidedBytes}|${it.totalCount}" }
        out["budget.spillover"] = ImageBudget.ensureSpillover(java.io.File(files, "att"), jpg, "image/jpeg").toString()
        out["budget.elided"] = ImageBudget.elidedImagePlaceholder("/p") + "|" + ImageBudget.elidedImagePlaceholder(null)

        files.deleteRecursively()
        return out
    }

    @Test fun `extracted code reproduces the pre-extraction outputs byte for byte`() {
        val now = outputs().mapValues { F.sha256(it.value) }
        for ((k, v) in now) println("[GOLDEN] \"$k\" to \"$v\",")
        if (GOLDEN.isEmpty()) return // bootstrap run on the pre-extraction code
        assertEquals(GOLDEN.keys, now.keys)
        for (k in GOLDEN.keys) assertEquals("output changed: $k", GOLDEN[k], now[k])
    }
}
