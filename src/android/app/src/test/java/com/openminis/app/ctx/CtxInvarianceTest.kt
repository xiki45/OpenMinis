package com.openminis.app.ctx

import com.openminis.app.data.BPETokenizer
import com.openminis.app.data.db.CompactMarkerEntity
import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.ProviderConfig
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.imagerepro.ImageRealFixtures
import com.openminis.app.ui.chat.ChatViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Behaviour-invariance evidence for the request-context extraction
 * (refactor+test, no behaviour change, no bug fixes).
 *
 * Every case drives ChatViewModel's OWN private entry points on an instance
 * allocated without its constructor, with only the fields those functions read
 * set:
 *  - effectiveAgentHistory()  (compaction summary injection v2 / v1, warm-up
 *    walk-back, cached / decided / undecided warm-up trim, orphan repair),
 *  - offloadWarmUpScanPlan()  (shares the walk-back),
 *  - dropOrphanedToolParts(), auditOutgoingPayload(),
 *  - persistBrowserArtifact(), linuxPathToMinisURL(),
 * plus the public BPETokenizer.countImageTokens. [GOLDEN] holds the sha256 of
 * every output captured on the code BEFORE the extraction (HEAD 0def96471, two
 * identical runs); after it those entry
 * points delegate to the extracted functions and must reproduce every value.
 * The per-marker warm-up cache is part of each output, so a changed caching
 * decision shows up as well as a changed message list.
 */
class CtxInvarianceTest {

    private val F = ImageRealFixtures

    /** sha256 of each case's canonical output, captured before the extraction. */
    private val GOLDEN: Map<String, String> = mapOf(
        "eah.noCompaction" to "07edc9f6a250f4c465ef70d98516d5e94f0baa70b7bd43a485e4915debefc398",
        "eah.v2.cached0" to "798d527ee82b322b28a7ead52692620eefd41000b2b637cec6817be25f73f6f7",
        "eah.v2.cached1" to "097d51762e7b55fd6b37cfb7484ef95a08d68fa6c4df275fde2b3567dcde6f48",
        "eah.v2.cachedHuge" to "31591481bc658c940c7dd0054baebd0cc86b46bcdf38243ec4d832d34c5ba419",
        "eah.v2.decidedFits" to "798d527ee82b322b28a7ead52692620eefd41000b2b637cec6817be25f73f6f7",
        "eah.v2.decidedTrims" to "bec0ea6af5c7561e8445678f82b7be2f1e6ec198fdfa16bc3139fb20d4fab93e",
        "eah.v2.undecidedNoFixed" to "8ab3c8117226257250e7210c3e70f03856d83ebc4e55b498af786f543898774f",
        "eah.v2.undecidedNoWindow" to "798d527ee82b322b28a7ead52692620eefd41000b2b637cec6817be25f73f6f7",
        // [T-404-summary-after-tool-results] Changed on purpose by the #404 fix: the
        // anchor is a tool call, so the summary now follows the carrier's tool result
        // (was 426653b8fb6b40348bedb9e28c69bccf8dab15c554ac5c7cf4c314a8ef9cbfb0, summary first).
        "eah.v2.anchorToolCall.404" to "852c13ac5cb486fa68a1c7e68ffe74bf16582f7f63ddab43aa27204b044dab42",
        "eah.v2.anchorLast" to "a91d3a40e3e7dca70538f95578b45d7b19f1f27854f89d8d1132c78d34e87e8c",
        "eah.v2.anchorUnknown" to "07edc9f6a250f4c465ef70d98516d5e94f0baa70b7bd43a485e4915debefc398",
        "eah.v2.blankSummary" to "07edc9f6a250f4c465ef70d98516d5e94f0baa70b7bd43a485e4915debefc398",
        "eah.v2.firstUser" to "3414e73fcb67cfb43085267f27dd363e25220abe2f233268d3164a1342c732e7",
        "eah.v2.imageOnlyAfterAnchor" to "ab9169538555769120aa73a90f97a957f6df5313b78e49d4dae0b078041a9fcf",
        "eah.v2.partlessAfterAnchor" to "adca90b81ba0b8ea8171757a28163d8a36caa3fcc49e8eca30379ea88f71501e",
        "eah.v2.walkBackCap" to "fea466d7304019d8c9049a0e4b671205d2c6c498b5e4dca56bb3aa60d6586bd9",
        "eah.v1.firstKept" to "9c7a36c21882334f76473ec0e0a774b6d4fb8864d6b6ce0294d326008a2d42d2",
        "eah.v1.boundary" to "d580b8089a4e6bb1824fcb59b2f08636a90e3aa388df6e6a4ff257cf4b2585b2",
        "eah.v1.firstKeptUnknown" to "07edc9f6a250f4c465ef70d98516d5e94f0baa70b7bd43a485e4915debefc398",
        "eah.v1.lcm" to "9c7a36c21882334f76473ec0e0a774b6d4fb8864d6b6ce0294d326008a2d42d2",
        "eah.v1.lcmUnknown" to "7823489ee40f1e59cf5f291b7e6390f3e35b9905f84638c7a2cb6add1ca93e17",
        "eah.v1.nothing" to "7823489ee40f1e59cf5f291b7e6390f3e35b9905f84638c7a2cb6add1ca93e17",
        "eah.orphans" to "bcb327e0d4980017d94e6bbd0c45a661949ada42bb4cce60b96437463d36edc0",
        "dropOrphanedToolParts.mixed" to "3750856d00bd7f16fba0e0601f5a2dc3fd4b03697f50af6105476e51ff1946f0",
        "dropOrphanedToolParts.clean" to "b51143a6d207523fa7df55b1e921e61574d96c7ac52080a06da95f13db9617aa",
        "dropOrphanedToolParts.pipeIds" to "c34d3e0a8e67b2fe3ad717d807b85850f2fb2b42341d7697e147e72eb8d3f1b6",
        "scanPlan.v2.cached0" to "4aaab544173ead839b6300e26fc474956a9bc905ff6332361281944cc3827e5a",
        "scanPlan.v2.cached1" to "f2be27e9c8c2f902e4424c5734bf573e99f309fcf795b9ed58f3bc8e66e673f7",
        "scanPlan.v2.undecided" to "4aaab544173ead839b6300e26fc474956a9bc905ff6332361281944cc3827e5a",
        "auditOutgoingPayload" to "ac8bd99e5c73ab52e53be5c159de1a056fe50d6ca52d0886e191fb605559bde9",
        "countImageTokens" to "4a01b8d1d835d7e7871e7b3e0e527fc4c82e2d689f40625c2cd284bff2b03993",
        "persistBrowserArtifact" to "d30a43e84ceb36d6148f0b2943c96acf6d57ddaed050e7a0bbf029eac36b7a07",
        "persistBrowserArtifact.noSession" to "74234e98afe7498fb5daf1f36ac2d78acc339464f950703b8c019892f982b90b",
        "linuxPathToMinisURL" to "b40526428bf5894f86acbb0204024134b0ac8e76dc882ba5aaf558015f9378f5",
    )

    private fun providerRepo(): ProviderRepository {
        val repo = F.allocate(ProviderRepository::class.java) as ProviderRepository
        val flow = MutableStateFlow(ProviderConfig())
        F.setField(repo, "_config", flow)
        F.setField(repo, "config", flow)
        return repo
    }

    private fun vm(
        filesDir: java.io.File,
        history: List<LLMMessage>,
        summary: String? = null,
        marker: CompactMarkerEntity? = null,
        cache: Map<String, Int> = emptyMap(),
        window: Int? = null,
        fixedTokens: Int = 0,
    ): Any {
        val vm = F.allocate(ChatViewModel::class.java)
        F.setField(vm, "context", F.context(filesDir))
        F.setField(vm, "sessionId", "sess-1")
        F.setField(vm, "realSessionId", "sess-1")
        F.setField(vm, "agentHistory", ArrayList(history))
        F.setField(vm, "_compactSummary", MutableStateFlow(summary))
        F.setField(vm, "_cachedLatestMarker", marker)
        F.setField(vm, "warmUpDropByMarker", LinkedHashMap(cache))
        F.setField(vm, "providerRepository", providerRepo())
        F.setField(vm, "_activeEntryId", MutableStateFlow<String?>(null))
        F.setField(vm, "_selectedGroupId", MutableStateFlow<String?>(null))
        F.setField(vm, "currentModel", LLMModel("ctx-test", "Ctx Test", "test", contextWindow = window, inputModalities = listOf("text", "image")))
        F.setField(vm, "contextCalibrationRatios", LinkedHashMap<String, Double>())
        CtxReflect.setInt(vm, "contextFixedTokens", fixedTokens)
        return vm
    }

    private fun u(text: String, db: String?) = LLMMessage(LLMMessage.Role.USER, text, contentParts = listOf(AgentContentPart.Text(text)), dbMessageId = db)
    private fun a(text: String, db: String?) = LLMMessage(LLMMessage.Role.ASSISTANT, text, contentParts = listOf(AgentContentPart.Text(text)), dbMessageId = db)
    private fun call(id: String, db: String?, name: String = "file_read") =
        LLMMessage(LLMMessage.Role.ASSISTANT, "", contentParts = listOf(AgentContentPart.ToolUse(id, name, JSONObject().put("path", "/x"))), dbMessageId = db)
    private fun result(id: String, db: String?, body: String = "ok", image: ByteArray? = null, name: String = "file_read") =
        LLMMessage(LLMMessage.Role.USER, "", contentParts = listOf(
            AgentContentPart.ToolResult(id, name, body, imageData = image, imageMimeType = image?.let { "image/jpeg" }, imageLinuxPath = image?.let { "/var/minis/browser/s.jpg" }),
        ), dbMessageId = db)

    /** 11 messages; anchor candidates a3 (idx 7) and a4 (idx 9, a tool call). */
    private fun base(): List<LLMMessage> = listOf(
        u("q1 " + "lorem ipsum ".repeat(300), "u0"),
        a("a1", "a0"),
        u("q2 " + "dolor sit ".repeat(300), "u1"),
        call("t1", "a1"),
        result("t1", "r1", body = "R".repeat(1500)),
        a("a2", "a2"),
        u("q3", "u2"),
        a("a3", "a3"),
        u("q4", "u3"),
        call("t2", "a4", name = "browser_use"),
        result("t2", "r2", body = "shot", image = F.jpeg(40, 20), name = "browser_use"),
    )

    private fun v2(anchor: String, id: String = "mk-$anchor") =
        CompactMarkerEntity(id = id, sessionId = "sess-1", summary = "S", firstKeptSortOrder = 0, compactedCount = 0, createdAt = 0, lastCompactedMessageId = anchor, version = 2)

    private fun v1(firstKept: String? = null, boundary: String? = null, lcm: String? = null) =
        CompactMarkerEntity(id = "mk-v1", sessionId = "sess-1", summary = "S", firstKeptSortOrder = 0, compactedCount = 0, createdAt = 0,
            boundaryMessageId = boundary, firstKeptMessageId = firstKept, lastCompactedMessageId = lcm, version = 1)

    private fun dumpList(l: List<LLMMessage>) = l.joinToString("\n") { F.dump(it) }

    @Suppress("UNCHECKED_CAST")
    private fun eah(vm: Any): String {
        val out = F.callPrivate(vm, "effectiveAgentHistory") as List<LLMMessage>
        val cache = CtxReflect.getField(vm, "warmUpDropByMarker") as Map<String, Int>
        return dumpList(out) + "\n|cache=" + cache.toSortedMap()
    }

    private fun outputs(): Map<String, String> {
        val out = linkedMapOf<String, String>()
        val files = F.tempDir("ctx-inv")
        val summary = "the summary"

        out["eah.noCompaction"] = eah(vm(files, base()))
        out["eah.v2.cached0"] = eah(vm(files, base(), summary, v2("a3"), cache = mapOf("mk-a3" to 0)))
        out["eah.v2.cached1"] = eah(vm(files, base(), summary, v2("a3"), cache = mapOf("mk-a3" to 1)))
        out["eah.v2.cachedHuge"] = eah(vm(files, base(), summary, v2("a3"), cache = mapOf("mk-a3" to 99)))
        out["eah.v2.decidedFits"] = eah(vm(files, base(), summary, v2("a3"), window = 100_000, fixedTokens = 2_000))
        out["eah.v2.decidedTrims"] = eah(vm(files, base(), summary, v2("a3"), window = 100_000, fixedTokens = 88_900))
        out["eah.v2.undecidedNoFixed"] = eah(vm(files, base(), summary, v2("a3"), window = 100_000, fixedTokens = 0))
        out["eah.v2.undecidedNoWindow"] = eah(vm(files, base(), summary, v2("a3"), window = null, fixedTokens = 2_000))
        out["eah.v2.anchorToolCall.404"] = eah(vm(files, base(), summary, v2("a4"), cache = mapOf("mk-a4" to 0)))
        out["eah.v2.anchorLast"] = eah(vm(files, base(), summary, v2("r2"), cache = mapOf("mk-r2" to 0)))
        out["eah.v2.anchorUnknown"] = eah(vm(files, base(), summary, v2("nope")))
        out["eah.v2.blankSummary"] = eah(vm(files, base(), "  ", v2("a3")))
        out["eah.v2.firstUser"] = eah(vm(files, base(), summary, v2("u0"), window = 100_000, fixedTokens = 2_000))
        // An image-only user turn right after the anchor: no Text part to prepend onto.
        val imgTurn = base().toMutableList().apply {
            set(8, LLMMessage(LLMMessage.Role.USER, "", imageParts = listOf(LLMMessage.ImagePart(F.png(4, 4), "image/png")),
                contentParts = listOf(AgentContentPart.ImageData(F.png(4, 4), "image/png")), dbMessageId = "u3"))
        }
        out["eah.v2.imageOnlyAfterAnchor"] = eah(vm(files, imgTurn, summary, v2("a3"), cache = mapOf("mk-a3" to 0)))
        // A plain (parts-less) user turn after the anchor.
        val bare = base().toMutableList().apply { set(8, LLMMessage(LLMMessage.Role.USER, "q4 bare", dbMessageId = "u3")) }
        out["eah.v2.partlessAfterAnchor"] = eah(vm(files, bare, summary, v2("a3"), cache = mapOf("mk-a3" to 0)))
        // Walk-back cap: one user-text turn, then 60 tool rounds (results are USER
        // messages but not round starts), then base(); anchored at a1 the walk
        // back would need 123 messages to reach the next user-text turn.
        val long = buildList {
            add(u("w0", "wu0"))
            for (i in 0 until 60) { add(call("c$i", "wc$i")); add(result("c$i", "wr$i")) }
            addAll(base())
        }
        out["eah.v2.walkBackCap"] = eah(vm(files, long, summary, v2("a0"), window = 1_000_000, fixedTokens = 2_000))
        out["eah.v1.firstKept"] = eah(vm(files, base(), summary, v1(firstKept = "u3")))
        out["eah.v1.boundary"] = eah(vm(files, base(), summary, v1(boundary = "u2")))
        out["eah.v1.firstKeptUnknown"] = eah(vm(files, base(), summary, v1(firstKept = "zzz")))
        out["eah.v1.lcm"] = eah(vm(files, base(), summary, v1(lcm = "a3")))
        out["eah.v1.lcmUnknown"] = eah(vm(files, base(), summary, v1(lcm = "zzz")))
        out["eah.v1.nothing"] = eah(vm(files, base(), summary, v1()))

        // Orphans: a stranded result, a mid-history unanswered call, a trailing in-flight call.
        val orphans = listOf(
            u("start", "o0"),
            result("lost", "o1"),
            call("mid", "o2"),
            u("next", "o3"),
            a("text", "o4"),
            call("tail", "o5"),
        )
        out["eah.orphans"] = eah(vm(files, orphans))
        @Suppress("UNCHECKED_CAST")
        out["dropOrphanedToolParts.mixed"] = dumpList(F.callPrivate(vm(files, emptyList()), "dropOrphanedToolParts", orphans) as List<LLMMessage>)
        @Suppress("UNCHECKED_CAST")
        out["dropOrphanedToolParts.clean"] = (F.callPrivate(vm(files, emptyList()), "dropOrphanedToolParts", base()) as List<LLMMessage>)
            .let { r -> dumpList(r) + "|same=" + (r.size == base().size) }
        // Responses-style "call|fc" ids pair with the bare call id.
        val mixedIds = listOf(u("go", null),
            LLMMessage(LLMMessage.Role.ASSISTANT, "", contentParts = listOf(AgentContentPart.ToolUse("call_1|fc_1", "x", JSONObject()))),
            LLMMessage(LLMMessage.Role.USER, "", contentParts = listOf(AgentContentPart.ToolResult("call_1", "x", "r"))))
        @Suppress("UNCHECKED_CAST")
        out["dropOrphanedToolParts.pipeIds"] = dumpList(F.callPrivate(vm(files, emptyList()), "dropOrphanedToolParts", mixedIds) as List<LLMMessage>)

        // offloadWarmUpScanPlan shares the walk-back.
        out["scanPlan.v2.cached0"] = F.callPrivate(vm(files, base(), summary, v2("a3"), cache = mapOf("mk-a3" to 0)), "offloadWarmUpScanPlan").toString()
        out["scanPlan.v2.cached1"] = F.callPrivate(vm(files, base(), summary, v2("a3"), cache = mapOf("mk-a3" to 1)), "offloadWarmUpScanPlan").toString()
        out["scanPlan.v2.undecided"] = F.callPrivate(vm(files, base(), summary, v2("a3")), "offloadWarmUpScanPlan").toString()

        // auditOutgoingPayload: logs only, returns its input.
        val big = listOf(
            u("x".repeat(400_000), null),
            LLMMessage(LLMMessage.Role.USER, "", contentParts = listOf(AgentContentPart.ImageData(F.png(8, 8) + ByteArray(3_000_000), "image/png"))),
            result("t9", null, body = "y".repeat(300_000), image = F.jpeg(10, 10)),
        )
        val auditVm = vm(files, emptyList(), window = 32_000)
        @Suppress("UNCHECKED_CAST")
        val audited = F.callPrivate(auditVm, "auditOutgoingPayload", big) as List<LLMMessage>
        out["auditOutgoingPayload"] = "same=" + (audited === big) + "\n" + dumpList(audited)

        // BPETokenizer.countImageTokens (JVM: android.jar BitmapFactory is a stub).
        out["countImageTokens"] = listOf(ByteArray(0), F.png(10, 10), F.jpeg(4032, 3024), F.heicHeaderOnly(), byteArrayOf(1, 2, 3))
            .joinToString(",") { BPETokenizer.countImageTokens(it).toString() }

        // Browser artifact helpers.
        val bvm = vm(files, emptyList())
        val persisted = F.callPrivate(bvm, "persistBrowserArtifact", "screenshot_1700000000.jpg", byteArrayOf(9, 8, 7)) as String?
        out["persistBrowserArtifact"] = persisted!!.removePrefix(files.absolutePath) + "|" + java.io.File(persisted).readBytes().toList()
        val noSession = vm(files, emptyList()).also { F.setField(it, "sessionId", ""); F.setField(it, "realSessionId", "") }
        out["persistBrowserArtifact.noSession"] = F.callPrivate(noSession, "persistBrowserArtifact", "x.jpg", byteArrayOf(1)).toString()
        out["linuxPathToMinisURL"] = listOf(
            "/var/minis/browser/screenshot_1.jpg", "/var/minis/browser/我的 文件 (1).pdf", "/var/minis/browser",
            "/tmp/x.jpg", "/var/minis/workspace/a/b c.txt", "/var/minis/browser/a+b&c=d.png",
        ).joinToString("|") { F.callPrivate(bvm, "linuxPathToMinisURL", it).toString() }

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

    /** The plain-text outputs, for reading what a GOLDEN row stands for. */
    @Test fun `print readable outputs`() {
        val dir = java.io.File("build/ctx-dump/invariance").apply { mkdirs() }
        for ((k, v) in outputs()) java.io.File(dir, "$k.txt").writeText(v)
    }
}

/** Reflection helpers this suite needs beyond ImageRealFixtures. */
internal object CtxReflect {
    private val unsafe: Any by lazy {
        val c = Class.forName("sun.misc.Unsafe")
        c.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
    }

    private fun field(target: Any, name: String): java.lang.reflect.Field {
        var c: Class<*>? = target.javaClass
        while (c != null) {
            c.declaredFields.firstOrNull { it.name == name }?.let { return it }
            c = c.superclass
        }
        error("no field $name")
    }

    fun setInt(target: Any, name: String, value: Int) {
        val off = unsafe.javaClass.getMethod("objectFieldOffset", java.lang.reflect.Field::class.java).invoke(unsafe, field(target, name)) as Long
        unsafe.javaClass.getMethod("putInt", Any::class.java, Long::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            .invoke(unsafe, target, off, value)
    }

    fun getField(target: Any, name: String): Any? = field(target, name).apply { isAccessible = true }.get(target)
}
