package com.openminis.app.ui.chat.share

import android.util.Base64
import android.view.ViewGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCompositionContext
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalView
import com.openminis.app.BuildConfig
import com.openminis.app.ui.chat.ChatViewModel
import com.openminis.app.ui.chat.rememberMarkdownPrewarmer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.Collections

/**
 * [T-multi-select-share] Debug-server bridge for the share feature (iOS
 * debug.chatShare.openPicker / debug.chatExport.render). The RPCs run outside any
 * screen, but the picker and the renderer need the chat on screen (its view model,
 * its window, its composition), so a request is posted here and served by the
 * ChatScreen showing that session ([ChatShareDebugHost]).
 */
internal object ChatShareDebugBridge {
    sealed class Event(val sessionId: String)
    class OpenPicker(sessionId: String, val messageId: String?, val reply: CompletableDeferred<String?>) : Event(sessionId)
    class Render(sessionId: String, val params: JSONObject, val reply: CompletableDeferred<JSONObject>) : Event(sessionId)

    val events = MutableSharedFlow<Event>(extraBufferCapacity = 8)

    /** Sessions currently shown by a ChatScreen that serves these requests. */
    val onScreen: MutableSet<String> = Collections.synchronizedSet(LinkedHashSet())
}

/**
 * Serves [ChatShareDebugBridge] requests for the session this ChatScreen shows.
 * Debug builds only.
 */
@Composable
internal fun ChatShareDebugHost(sessionId: String, viewModel: ChatViewModel, openPicker: (String?) -> Unit) {
    if (!BuildConfig.DEV_TOOLS) return
    val view = LocalView.current
    val parent = rememberCompositionContext()
    val prewarm = rememberMarkdownPrewarmer()
    val open by rememberUpdatedState(openPicker)
    DisposableEffect(sessionId) {
        ChatShareDebugBridge.onScreen += sessionId
        onDispose { ChatShareDebugBridge.onScreen -= sessionId }
    }
    LaunchedEffect(sessionId, viewModel) {
        ChatShareDebugBridge.events.collect { event ->
            if (event.sessionId != sessionId) return@collect
            when (event) {
                is ChatShareDebugBridge.OpenPicker -> {
                    val target = event.messageId
                        ?: viewModel.uiMessages.value.lastOrNull { it.role == "user" && !it.isQueued }?.id
                    open(target)
                    event.reply.complete(target)
                }
                is ChatShareDebugBridge.Render -> {
                    val stage = ChatExportStage(view.rootView as ViewGroup, view, parent)
                    try {
                        event.reply.complete(debugRender(viewModel, event.params, ChatExportRenderer(stage, prewarm), view.context.applicationContext))
                    } catch (t: Throwable) {
                        event.reply.completeExceptionally(t)
                    } finally {
                        stage.dispose()
                    }
                }
            }
        }
    }
}

/**
 * Runs the export headlessly for the session on screen: units picked by
 * `unitIndexes` or `lastUnits` (default all), tool calls / thinking only with
 * `includeToolCalls` / `includeThinking`, options `format` (image|pdf),
 * `wideLayout`, `dark`, `adaptiveTables`, `title`. The file stays in the app cache;
 * `inline: true` also returns its bytes (base64, up to 8 MB).
 */
private suspend fun debugRender(
    viewModel: ChatViewModel,
    params: JSONObject,
    renderer: ChatExportRenderer,
    context: android.content.Context,
): JSONObject {
    val messages = com.openminis.app.ui.chat.mergeStreamingOverlay(viewModel.uiMessages.value, viewModel.streamingById.value)
    val cells = ChatShareContent.cells(messages, viewModel.thinkingLevel.value.isEnabled)
    val unitIds = cells.fold(mutableListOf<String>()) { acc, c -> if (acc.lastOrNull() != c.unitId) acc += c.unitId; acc }
    var units: List<String> = unitIds
    params.optJSONArray("unitIndexes")?.let { arr ->
        units = (0 until arr.length()).mapNotNull { unitIds.getOrNull(arr.optInt(it, -1)) }
    } ?: params.optInt("lastUnits", 0).takeIf { it > 0 }?.let { units = unitIds.takeLast(it) }
    val includeTools = params.optBoolean("includeToolCalls", false)
    val includeThinking = params.optBoolean("includeThinking", false)
    val wanted = units.toSet()
    val selected = cells.filter { cell ->
        cell.unitId in wanted && when (cell.kind) {
            ChatShareCell.Kind.TOOL -> includeTools
            ChatShareCell.Kind.THINKING -> includeThinking
            else -> true
        }
    }.map { it.id }.toSet()
    val groups = ChatShareCells.renderGroups(cells, selected)
    val options = ChatExportOptions(
        format = if (params.optString("format") == "pdf") ChatExportOptions.Format.PDF else ChatExportOptions.Format.IMAGE,
        wideLayout = params.optBoolean("wideLayout", false),
        dark = params.optBoolean("dark", false),
        adaptiveTables = params.optBoolean("adaptiveTables", false),
    )
    val title = params.optString("title").ifEmpty { "Debug Export" }
    val started = System.currentTimeMillis()
    val covered = groups.flatten().mapNotNull { c -> messages.firstOrNull { it.id == c.messageId } }
    val subtitle = chatExportSubtitle(
        viewModel.modelName.value.trim().ifEmpty { null },
        chatShareFirstMessageDate(context, covered),
    )
    val result = renderer.render(groups, messages, title, subtitle, options) { _, _ -> }
    return JSONObject().apply {
        put("path", result.file.absolutePath)
        put("bytes", result.file.length())
        put("width", result.width)
        put("height", result.height)
        put("scale", result.scale.toDouble())
        put("pages", result.pageCount)
        put("units", result.units)
        put("totalUnits", unitIds.size)
        put("cells", JSONArray(groups.flatten().map { it.kind.name.lowercase() }))
        put("ms", System.currentTimeMillis() - started)
        if (params.optBoolean("inline", false) && result.file.length() <= 8L * 1024 * 1024) {
            put("base64", Base64.encodeToString(result.file.readBytes(), Base64.NO_WRAP))
        }
    }
}

/** The RPC side of [ChatShareDebugBridge] (dispatched by DebugRPCHandler, debug builds only). */
internal object ChatShareDebugRpc {
    private fun sessionOnScreen(params: JSONObject): String {
        val sid = params.optString("sessionId")
        if (sid.isEmpty()) throw com.openminis.app.debug.RPCException(-32602, "Missing required param: sessionId")
        if (sid !in ChatShareDebugBridge.onScreen) {
            throw com.openminis.app.debug.RPCException(-32602, "session $sid is not on screen - open it in the app first")
        }
        return sid
    }

    /** debug.chatShare.openPicker: opens the picker as a long-press "Share Chat…" on a message would. */
    suspend fun openPicker(params: JSONObject): JSONObject {
        val sid = sessionOnScreen(params)
        val reply = CompletableDeferred<String?>()
        ChatShareDebugBridge.events.emit(
            ChatShareDebugBridge.OpenPicker(sid, params.optString("messageId").ifEmpty { null }, reply),
        )
        val target = kotlinx.coroutines.withTimeout(10_000) { reply.await() }
        return JSONObject().put("ok", true).put("messageId", target ?: JSONObject.NULL)
    }

    /** debug.chatExport.render: renders the export headlessly for the session on screen. */
    suspend fun render(params: JSONObject): JSONObject {
        val sid = sessionOnScreen(params)
        val reply = CompletableDeferred<JSONObject>()
        ChatShareDebugBridge.events.emit(ChatShareDebugBridge.Render(sid, params, reply))
        return try {
            kotlinx.coroutines.withTimeout(180_000) { reply.await() }
        } catch (e: ChatExportError) {
            throw com.openminis.app.debug.RPCException(-32000, "export failed: ${e::class.simpleName}")
        }
    }
}
