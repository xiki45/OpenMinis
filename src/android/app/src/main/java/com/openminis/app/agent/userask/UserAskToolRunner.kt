package com.openminis.app.agent.userask

import com.openminis.app.logging.AppLogger
import com.openminis.app.tools.ToolExecutionResult

/**
 * [T-port-user-ask] The user_ask tool call inside the agent loop (iOS
 * `AIChatViewModel.executeUserAsk`, final state): parse -> register the ask
 * (the card appears) -> wait -> result JSON.
 *
 * A bad call fails with the parser's message, phrased for the model, so it can
 * fix the call. The wait suspends the tool call only; Stop cancels it (the
 * ask resolves as stopped) and a new user message dismisses it.
 */
object UserAskToolRunner {
    private const val TAG = "UserAsk"

    /**
     * Hook for persistence: called with the new ask before the card shows
     * (Top 5 saves it, with the turn's assistant row, so a kill mid-wait can
     * be recovered).
     */
    fun interface BeforeShow {
        fun onAsk(ask: PendingAsk)
    }

    /**
     * [T-user-ask-deferred] user_ask in a run no one can answer (iOS
     * executeUserAsk's deferred branch): the record is saved, the card goes
     * up (a notification follows when the app is away), and the call returns
     * at once with one `deferred` status per question. No countdown; the
     * answer reaches the model later as the user's next message.
     */
    fun executeDeferred(
        argsJson: String,
        toolCallId: String,
        sessionId: String?,
        sessionTitle: String?,
        nowMs: Long = System.currentTimeMillis(),
        requestId: String = java.util.UUID.randomUUID().toString(),
        beforeShow: BeforeShow? = null,
    ): ToolExecutionResult {
        val request = UserAskParser.parse(argsJson).getOrElse {
            return ToolExecutionResult("Error: ${it.message}", false, toolTitle = "")
        }
        if (sessionId.isNullOrEmpty()) {
            return ToolExecutionResult(UserAskResolver.encode(UserAskResolver.unavailable(request.questions)), true, toolTitle = "")
        }
        val ask = PendingAsk.create(requestId, sessionId, toolCallId, request.questions, nowMs, timeoutMinutes = 0)
            .copy(sessionTitle = sessionTitle, deferred = true)
        runCatching { beforeShow?.onAsk(ask) }.onFailure {
            AppLogger.warning(TAG, "[UserAsk] record not saved for req=${ask.requestId.take(8)}: ${it.javaClass.simpleName}")
        }
        UserAskCenter.beginDeferred(ask)
        AppLogger.info(
            TAG,
            "[UserAsk] DEFERRED sid=${sessionId.take(8)} tool=${toolCallId.take(16)} questions=${request.questions.size} - card left open, run continues",
        )
        return ToolExecutionResult(UserAskDeferred.toolResult(request.questions.size), true, toolTitle = "")
    }

    suspend fun execute(
        argsJson: String,
        toolCallId: String,
        sessionId: String?,
        sessionTitle: String?,
        timeoutMinutes: Int,
        nowMs: Long = System.currentTimeMillis(),
        requestId: String = java.util.UUID.randomUUID().toString(),
        beforeShow: BeforeShow? = null,
    ): ToolExecutionResult {
        val request = UserAskParser.parse(argsJson).getOrElse {
            return ToolExecutionResult("Error: ${it.message}", false, toolTitle = "")
        }
        if (sessionId.isNullOrEmpty()) {
            return ToolExecutionResult(UserAskResolver.encode(UserAskResolver.unavailable(request.questions)), true, toolTitle = "")
        }
        val ask = PendingAsk.create(
            requestId, sessionId, toolCallId, request.questions, nowMs,
            UserAskSettingsSpec.clampTimeout(timeoutMinutes),
        ).copy(sessionTitle = sessionTitle)
        // [T-port-user-ask-fk-crash] Owned before the record exists: a recovery
        // scan must never treat this live ask as one left behind by a kill.
        UserAskCenter.claim(ask.requestId)
        // Persisted before the card can show it (iOS §12.3 step 1). A failure
        // to persist only costs recovery after a kill, never the question.
        runCatching { beforeShow?.onAsk(ask) }.onFailure {
            AppLogger.warning(TAG, "[UserAsk] record not saved for req=${ask.requestId.take(8)}: ${it.javaClass.simpleName}")
        }
        AppLogger.info(TAG, "[UserAsk] waiting_user ENTER sid=${sessionId.take(8)} tool=${toolCallId.take(16)}")
        UserAskCenter.begin(ask)
        val result = UserAskCenter.wait(ask.requestId)
        // Statuses and length only: the result carries the user's own words.
        AppLogger.info(
            TAG,
            "[UserAsk] waiting_user EXIT sid=${sessionId.take(8)} statuses=${UserAskResolver.statuses(result).map { it.wire }} resultLen=${result.length}",
        )
        return ToolExecutionResult(result, true, toolTitle = "")
    }
}
