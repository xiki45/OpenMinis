package com.openminis.app.debug

import android.content.Context
import com.openminis.app.BuildConfig
import com.openminis.app.MinisApp
import com.openminis.app.ui.chat.ChatViewModel
import com.openminis.app.ui.chat.ChatViewModelStore
import com.openminis.app.ui.chat.DebugUsageHint
import com.openminis.app.ui.chat.pages
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * [T-port-composer-hint-debug] `debug.chat.usageHint`: show the composer's
 * usage line with chosen figures in the chat on screen, so the context /
 * quota carousel can be tested on a device without filling 70% of a context
 * window. DEV_TOOLS builds only: the debug server does not run in release, and
 * ChatViewModel.debugShowContextUsageHint refuses there as well.
 */
internal object UsageHintDebugMethods {

    suspend fun show(context: Context, params: JSONObject): JSONObject {
        if (!BuildConfig.DEV_TOOLS) throw RPCException(-32601, "debug.chat.usageHint is only available in debug builds")
        val app = context.applicationContext as? MinisApp ?: throw RPCException(-32000, "Application is not MinisApp")
        val store = ChatViewModelStore
        val sessionId = params.optString("sessionId", "").ifEmpty { null }
            ?: store.activeSessionId
            ?: throw RPCException(-32000, "No chat is on screen (activeSessionId is null) and no sessionId was given")
        val percent = params.optInt("percent", 75)
        val window = params.optInt("windowTokens", 200_000)
        val quotaMode = params.optString("quota", "sample")
        val custom = params.optString("quotaText", "").ifEmpty { null }
        val quota: DebugUsageHint.Quota? = when {
            custom != null -> DebugUsageHint.Quota(
                custom,
                params.optString("quotaHighlight", "").ifEmpty { null },
                DebugUsageHint.tierOf(params.optString("quotaTier", "normal")),
            )
            quotaMode == "none" || quotaMode == "live" -> null
            else -> DebugUsageHint.SAMPLE_QUOTA.copy(tier = DebugUsageHint.tierOf(params.optString("quotaTier", "normal")))
        }
        val hint = withContext(Dispatchers.Main) {
            val vm = androidx.lifecycle.ViewModelProvider(
                store.ownerFor(sessionId),
                ChatViewModel.factory(
                    sessionId = sessionId,
                    chatRepository = app.chatRepository,
                    providerRepository = app.providerRepository,
                    appContext = app.applicationContext,
                    memoryRepository = app.memoryRepository,
                    skillRepository = app.skillRepository,
                    mcpRepository = app.mcpRepository,
                ),
            )[ChatViewModel::class.java]
            vm.debugShowContextUsageHint(percent, window, quota, useLiveQuota = quotaMode == "live")
        } ?: throw RPCException(-32000, "refused (not a DEV_TOOLS build)")
        return JSONObject().apply {
            put("ok", true)
            put("sessionId", sessionId)
            put("generation", hint.generation)
            put("tier", hint.tier.name)
            put("highlights", JSONArray(hint.highlights))
            put("quotaText", hint.quotaText ?: JSONObject.NULL)
            put("quotaTier", hint.quotaTier.name)
            put("pages", JSONArray(hint.pages().map { it.name }))
            put("note", "Shown only while the composer is empty; typing retires it. Each call is a new generation (3 new rounds).")
        }
    }
}
