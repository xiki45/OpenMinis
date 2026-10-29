package com.openminis.app.ctx

import com.openminis.app.browser.BrowserActionResult
import com.openminis.app.data.db.CompactMarkerEntity
import com.openminis.app.data.db.MessageEntity
import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.repository.ChatRepository
import com.openminis.app.data.storage.MediaStore
import com.openminis.app.imagerepro.ImageRealFixtures
import com.openminis.app.imagerepro.ImageRealWire
import com.openminis.app.provider.ImageBudget
import com.openminis.app.provider.anthropic.AnthropicProvider
import com.openminis.app.provider.gemini.GeminiProvider
import com.openminis.app.provider.openai.OpenAIProvider
import com.openminis.app.tools.ReadImageTool
import com.openminis.app.tools.ToolExecutionResult
import com.openminis.app.ui.chat.BrowserArtifacts
import com.openminis.app.ui.chat.ChatAttachmentIngest
import com.openminis.app.ui.chat.ChatHistoryRebuild
import com.openminis.app.ui.chat.ChatPartsJson
import com.openminis.app.ui.chat.ChatViewModel
import com.openminis.app.ui.chat.InputAttachment
import com.openminis.app.ui.chat.PastedText
import com.openminis.app.ui.chat.RequestContextBuilder
import com.openminis.app.ui.chat.ToolResultWrap
import kotlinx.coroutines.runBlocking
import org.json.JSONObject

/** The four Android serializers a request is checked against (names shared with iOS). */
enum class CtxProvider(val id: String) {
    ANTHROPIC("anthropic"),
    OPENAI_CHAT("openai.chat"),
    OPENAI_RESPONSES("openai.responses"),
    GEMINI("gemini"),
}

/**
 * A6 conversation DSL + A7 storage isolation.
 *
 * One session lives in its own temp directory (filesDir, MediaStore, uploads,
 * browser dir, spillover) and a list of persisted rows standing in for the
 * messages table; nothing touches real app data. Each verb runs the REAL
 * production functions in the order the app runs them; the glue between them
 * mirrors the ChatViewModel call site named in each KDoc (that glue is not
 * itself extracted, so a source guard pins it where it matters).
 *
 *  - [user]      sendMessage: ChatAttachmentIngest.prepare → buildPastedParts
 *                → ChatPartsJson.buildUserPartsJson → row → liveUserTurn
 *  - [assistant] buildAssistantPartsJson (row) + the loop's assistant message
 *  - [toolResults] ToolResultWrap.part → .persist → .historyMessage
 *  - [browser] / [readImage]  BrowserArtifacts.toolResult / ReadImageTool.imageResult
 *  - [compaction] a v2 marker anchored at a row, as compactAll writes it
 *  - [restart]   loadSession: rows → ChatHistoryRebuild.toLLMMessage
 *  - [queue]     drainQueuedPrompts: prepare → queuedTurn → row → queuedHistoryMessage
 *  - [edit]      editMessage + truncateBeforeEdit + the resend
 *  - [retry]     retryFromMessage (rows after the user turn deleted, history rebuilt)
 *  - [request]   RequestContextBuilder.build → the provider's own body builder
 */
class CtxSession(val name: String, val window: Int? = 200_000) : AutoCloseable {
    val root: java.io.File = ImageRealFixtures.tempDir("ctx-$name-")
    val filesDir = java.io.File(root, "files").apply { mkdirs() }
    val sid = "sess-$name"
    private val context = ImageRealFixtures.context(filesDir)
    val mediaStore = MediaStore(context)

    /** The messages table. */
    val rows = mutableListOf<MessageEntity>()

    /** agentHistory. */
    val history = mutableListOf<LLMMessage>()

    /** The fixed clock every verb reads (attachments' modified=, screenshot names). */
    var clockMs: Long = 1_760_000_000_000L

    private var marker: CompactMarkerEntity? = null
    private var summary: String? = null
    private val warmUpCache = mutableMapOf<String, Int>()
    var warmUpBudget: Int? = null

    /** Budget plans the request stage reported (the UI event in production). */
    val budgetPlans = mutableListOf<ImageBudget.RequestBudgetPlan>()

    /** MessageBudget results reported at compose time. */
    val composeBudgets = mutableListOf<ImageBudget.BudgetResult>()

    private val pending = mutableMapOf<String, CtxKit.Attachment>()
    private var rowSeq = 0

    private val vm: Any by lazy {
        ImageRealFixtures.allocate(ChatViewModel::class.java).also {
            ImageRealFixtures.setField(it, "mediaStore", mediaStore)
            ImageRealFixtures.setField(it, "context", context)
        }
    }

    val uploadsDir: java.io.File get() = java.io.File(filesDir, "minis-sessions/$sid/attachments/uploads")
    val browserDir: java.io.File get() = java.io.File(filesDir, "minis-sessions/$sid/browser")
    val spillDir: java.io.File get() = java.io.File(filesDir, "minis-sessions/$sid/attachments/spillover")

    fun advance(ms: Long) { clockMs += ms }

    private fun append(role: String, partsJson: String): MessageEntity {
        rowSeq++
        val row = MessageEntity(id = "row-$rowSeq", sessionId = sid, role = role, partsJson = partsJson, createdAt = clockMs, sortOrder = rowSeq)
        rows += row
        return row
    }

    // ── user ────────────────────────────────────────────────────────────────

    private fun inputs(attachments: List<CtxKit.Attachment>): List<InputAttachment> = attachments.map { a ->
        val ia = InputAttachment(
            fileName = a.fileName,
            uri = uriFor(a.fileName),
            mimeType = a.mimeType,
            kind = if (a.isImage) InputAttachment.Kind.IMAGE else InputAttachment.Kind.DOCUMENT,
        )
        pending[ia.id] = a
        ia
    }

    private fun prepare(attachments: List<CtxKit.Attachment>) = ChatAttachmentIngest.prepare(
        attachments = inputs(attachments),
        sessionId = sid,
        filesDir = filesDir,
        mediaStore = mediaStore,
        openStream = { pending[it.id]?.open() },
        clockMs = { clockMs },
        onBudgetMutated = { composeBudgets += it },
        fileUri = { uriFor(it.name) },
    )

    /** ChatAttachmentIngest.pastedParts (buildPastedParts) with [pastes] in the paste buffer: (partsJson, modelText). */
    private fun pasted(text: String, pastes: List<String>): Pair<List<String>, String>? {
        val buffer = pastes.mapIndexed { i, t -> PastedText(i + 1, t) }
        val p = ChatAttachmentIngest.pastedParts(text, { buffer }, sid, mediaStore) { uriFor(it.name) } ?: return null
        return p.partsJson to p.modelText
    }

    /**
     * sendMessage (ChatViewModel ~8925-8985). [pastes] become `[Pasted#N]`
     * markers appended to [text], as the composer writes them.
     */
    fun user(text: String, attachments: List<CtxKit.Attachment> = emptyList(), pastes: List<String> = emptyList()): MessageEntity {
        val composed = text + pastes.indices.joinToString("") { " " + PastedText.placeholderFor(it + 1) }
        val trimmed = composed.trim()
        val prepared = prepare(attachments)
        val paste = pasted(trimmed, pastes)
        val partsJson = ChatPartsJson.buildUserPartsJson(trimmed, prepared.mediaRefPartsJson, prepared.attachedFilesXml, paste?.first)
        val row = append("user", partsJson)
        history += ChatAttachmentIngest.liveUserTurn(
            paste?.second ?: trimmed, prepared.imageParts, prepared.imageUploadPaths, prepared.imageModelNotes,
            prepared.attachedFilesXml, row.id, NO_VISION,
        )
        return row
    }

    // ── assistant + tools ───────────────────────────────────────────────────

    class Call(val id: String, val name: String, val input: JSONObject = JSONObject())

    /** persistAssistantTurn's row (buildAssistantPartsJson, private) and the loop's assistant message. */
    fun assistant(text: String = "", vararg calls: Call): MessageEntity {
        val parts = buildList<AgentContentPart> {
            if (text.isNotEmpty()) add(AgentContentPart.Text(text))
            calls.forEach { add(AgentContentPart.ToolUse(it.id, it.name, it.input)) }
        }
        val json = ImageRealFixtures.callPrivate(vm, "buildAssistantPartsJson", parts, emptyMap<String, Any>()) as String
        val row = append("assistant", json)
        history += LLMMessage(LLMMessage.Role.ASSISTANT, text, contentParts = parts, dbMessageId = row.id)
        return row
    }

    /** The agent loop's tool-result step: ToolResultWrap.part per call, persisted, appended. */
    fun toolResults(vararg results: Pair<Call, ToolExecutionResult>): MessageEntity? {
        val parts = results.map { (call, r) -> ToolResultWrap.part(call.id, call.name, r.output, r) }
        val id = runBlocking {
            ToolResultWrap.persist(parts, emptyMap(), { sid }) { _, role, json -> append(role, json).id }
        }
        history += ToolResultWrap.historyMessage(parts, id)
        return rows.lastOrNull { it.id == id }
    }

    /** browser_use result → tool result, with this session's dir and clock. */
    fun browser(result: BrowserActionResult, title: String = "Take screenshot"): ToolExecutionResult =
        BrowserArtifacts.toolResult(
            result = result,
            toolTitle = title,
            decodeBase64 = { java.util.Base64.getMimeDecoder().decode(it) },
            clockMs = { clockMs },
            persist = { fn, data -> BrowserArtifacts.persist(sid, { filesDir }, fn, data) },
        )

    /** read_image of a file the agent can see at [linuxPath]. */
    fun readImage(file: java.io.File, linuxPath: String, title: String = "Read image"): ToolExecutionResult =
        ReadImageTool.imageResult(file, linuxPath, title)

    fun text(output: String, success: Boolean = true) = ToolExecutionResult(output, success)

    // ── compaction / restart ────────────────────────────────────────────────

    /** A v2 marker whose anchor (last compacted message) is [anchorRowId]. */
    fun compaction(anchorRowId: String, summaryText: String) {
        summary = summaryText
        marker = CompactMarkerEntity(
            id = "marker-$anchorRowId", sessionId = sid, summary = summaryText, firstKeptSortOrder = 0,
            compactedCount = 0, createdAt = clockMs, lastCompactedMessageId = anchorRowId, version = 2,
        )
    }

    /** loadSession: drop memory, rebuild agentHistory from the rows (the in-memory warm-up cache resets). */
    fun restart() {
        history.clear()
        warmUpCache.clear()
        for (row in rows) {
            if (ChatRepository.isEmptyAssistantCarrier(row.role, row.partsJson)) continue
            history += ChatHistoryRebuild.toLLMMessage(row, mediaStore.mediaBaseDir, NO_VISION)
        }
    }

    // ── queue / edit / retry / fork ─────────────────────────────────────────

    class Prompt(val text: String, val attachments: List<CtxKit.Attachment> = emptyList())

    /** drainQueuedPrompts (ChatViewModel ~8595-8645): one row and one history message per batch. */
    fun queue(vararg prompts: Prompt): MessageEntity {
        val prepared = prepare(prompts.flatMap { it.attachments })
        val turn = ChatAttachmentIngest.queuedTurn(prompts.map { it.text }, prepared, NO_VISION)
        val paste = pasted(turn.text, emptyList())
        val partsJson = ChatPartsJson.buildUserPartsJson(turn.text, prepared.mediaRefPartsJson, prepared.attachedFilesXml, paste?.first)
        val row = append("user", partsJson)
        history += ChatAttachmentIngest.queuedHistoryMessage(turn.text, paste?.second, prepared.imageParts, turn.parts, null, null)
        return row
    }

    /**
     * editMessage + truncateBeforeEdit + resend (ChatViewModel ~7970-8050):
     * the edited bubble's media files come back as attachments (names from the
     * bubble, MIME guessed from the extension), the rows from that bubble on
     * are deleted, history is rebuilt from what remains, and the new text is
     * sent like any message.
     */
    fun edit(userRow: MessageEntity, newText: String): MessageEntity {
        val idx = rows.indexOfFirst { it.id == userRow.id }
        require(idx >= 0)
        val restored = mediaRefs(userRow).map { (file, name, mime) ->
            // guessMimeType reads android.webkit.MimeTypeMap (a stub on the JVM); the
            // JDK's extension table gives the same answer for the extensions used here.
            val fallback = if (mime.startsWith("image/")) "image/*" else "application/octet-stream"
            val guessed = name.substringAfterLast('.', "").takeIf { it.isNotEmpty() }
                ?.let { java.net.URLConnection.guessContentTypeFromName(name) } ?: fallback
            CtxKit.Attachment(name, guessed, isImage = mime.startsWith("image/"), file = file)
        }
        while (rows.size > idx) rows.removeAt(rows.size - 1)
        restart()
        return user(newText, restored)
    }

    /** retryFromMessage: keep the user row, delete everything after it, rebuild history. */
    fun retry(userRow: MessageEntity) {
        val idx = rows.indexOfFirst { it.id == userRow.id }
        require(idx >= 0)
        while (rows.size > idx + 1) rows.removeAt(rows.size - 1)
        restart()
    }

    /** (file, originalFileName, mimeType) of every mediaRef part in [row]. */
    fun mediaRefs(row: MessageEntity): List<Triple<java.io.File, String, String>> {
        val arr = org.json.JSONArray(row.partsJson)
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.getJSONObject(i)
            if (o.optString("type") != "mediaRef") return@mapNotNull null
            val v = o.getJSONObject("value")
            Triple(java.io.File(mediaStore.mediaBaseDir, v.getString("relativePath")), v.optString("originalFileName", "file"), v.getString("mimeType"))
        }
    }

    /**
     * SessionListViewModel.duplicateSession: rows copied verbatim into a new
     * session id; no media / uploads copied. Returns the copy (sharing this
     * filesDir, i.e. the same device).
     */
    fun duplicate(newName: String): CtxSession {
        val copy = CtxSession(newName, window)
        copy.rows += rows.mapIndexed { i, r -> r.copy(id = "dup-${i + 1}", sessionId = copy.sid) }
        copy.restartOn(filesDir)
        return copy
    }

    /** Rebuild this session's history against [mediaFilesDir]'s media store (another device = another dir). */
    fun restartOn(mediaFilesDir: java.io.File) {
        val store = MediaStore(ImageRealFixtures.context(mediaFilesDir))
        history.clear()
        for (row in rows) {
            if (ChatRepository.isEmptyAssistantCarrier(row.role, row.partsJson)) continue
            history += ChatHistoryRebuild.toLLMMessage(row, store.mediaBaseDir, NO_VISION)
        }
    }

    // ── request ─────────────────────────────────────────────────────────────

    fun input(): RequestContextBuilder.Input = RequestContextBuilder.Input(
        history = history,
        compaction = summary?.let {
            RequestContextBuilder.Compaction(
                summary = it,
                marker = marker,
                cachedWarmUpDrop = { id -> warmUpCache[id] },
                recordWarmUpDrop = { id, drop -> warmUpCache[id] = drop },
                warmUpBudget = { warmUpBudget },
            )
        },
        keepRecentUserTurns = 3,
        sessionId = { sid },
        contextWindowTokens = { window },
        resolveAttachmentsRoot = { java.io.File(filesDir, "minis-sessions/$sid/attachments") },
        onRequestBudget = { budgetPlans += it },
    )

    fun request(): RequestContextBuilder.RequestContext = RequestContextBuilder.build(input())

    override fun close() {
        root.deleteRecursively()
    }

    companion object {
        val NO_VISION: (String?) -> String? = { null }

        /**
         * A Uri for a fixture: the real Uri.fromFile where android.net is real
         * (Robolectric), the test-only CtxFakeUri where it is the unit-test stub
         * (fromFile returns null there). Never read by the code under test.
         */
        fun uriFor(name: String): android.net.Uri {
            val real: android.net.Uri? = android.net.Uri.fromFile(java.io.File(name))
            return real ?: android.net.CtxFakeUri(name)
        }

        /** The body [provider] builds for [messages] on the streaming path the agent loop uses. */
        fun wire(provider: CtxProvider, messages: List<LLMMessage>): JSONObject = when (provider) {
            CtxProvider.ANTHROPIC -> {
                val p = AnthropicProvider(apiKey = "test-key", model = LLMModel.claudeHaiku45, basePath = "https://example.invalid")
                privateBody(p, messages, withStream = true)
            }
            CtxProvider.GEMINI -> {
                val p = GeminiProvider(apiKey = "test-key", model = LLMModel.gemini25Flash, basePath = "https://example.invalid")
                privateBody(p, messages, withStream = false)
            }
            CtxProvider.OPENAI_CHAT -> OpenAIProvider(apiKey = "k", model = ImageRealWire.visionModel, basePath = "https://example.invalid/v1")
                .buildRequestBody(messages, null, 4096, true, null, emptyList())
            CtxProvider.OPENAI_RESPONSES -> OpenAIProvider(apiKey = "k", model = ImageRealWire.visionModel, basePath = "https://example.invalid/v1", useResponsesAPI = true)
                .buildResponsesAPIBody(messages, null, 4096, true, emptyList())
        }

        /**
         * The private buildRequestBody of Anthropic (with `stream`) / Gemini (without).
         * Gemini's takes a trailing `thinkingFormat` since 270e09b4d (FIX-261019-07);
         * null is its default, so the body is the one the agent loop builds with
         * thinking off.
         */
        private fun privateBody(provider: Any, messages: List<LLMMessage>, withStream: Boolean): JSONObject {
            val m = provider.javaClass.declaredMethods.first { it.name == "buildRequestBody" && it.parameterCount == 8 }
            m.isAccessible = true
            val off = com.openminis.app.data.model.ThinkingLevel.OFF
            return try {
                if (withStream) m.invoke(provider, messages, null, 4096, true, null, emptyList<Any>(), emptyList<Any>(), off) as JSONObject
                else m.invoke(provider, messages, null, 4096, null, emptyList<Any>(), emptyList<Any>(), off, null) as JSONObject
            } catch (e: java.lang.reflect.InvocationTargetException) {
                throw e.targetException
            }
        }
    }
}
