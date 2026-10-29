package com.openminis.app.agent.userask

import org.json.JSONArray
import org.json.JSONObject

/**
 * [T-port-user-ask-recovery] What survives a process kill while a question
 * waits, and what to do with it on the next launch - the pure half of iOS's
 * UserAskRecord / UserAskRecovery (f21eaa98e P2, 7a818b8c0), final state.
 *
 * Android writes a turn's assistant row (the one carrying the tool_use) only
 * together with the batch's tool results, so without a copy a killed app would
 * have nothing to attach the answer to. The record keeps that row's parts
 * JSON, exactly as the turn would have persisted it.
 */
data class UserAskRecord(
    val ask: PendingAsk,
    /** Parts JSON of the assistant row (tool_use included), as persistAssistantTurn writes it. */
    val assistantPartsJson: String? = null,
    /** Other tool_use ids of the same batch: recovery gives them an "interrupted, not run" result. */
    val siblingToolUseIds: List<String> = emptyList(),
    /** The assistant row was written during recovery so the card shows again; delivery then appends only the results. */
    val assistantPersisted: Boolean = false,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("ask", UserAskCodec.toJson(ask))
        .put("assistantPartsJson", assistantPartsJson ?: JSONObject.NULL)
        .put("siblingToolUseIds", JSONArray(siblingToolUseIds))
        .put("assistantPersisted", assistantPersisted)

    companion object {
        fun fromJson(o: JSONObject): UserAskRecord? {
            val ask = o.optJSONObject("ask")?.let { UserAskCodec.fromJson(it) } ?: return null
            val siblings = o.optJSONArray("siblingToolUseIds")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList()
            return UserAskRecord(
                ask = ask,
                assistantPartsJson = o.optString("assistantPartsJson", "").takeIf { it.isNotEmpty() && !o.isNull("assistantPartsJson") },
                siblingToolUseIds = siblings,
                assistantPersisted = o.optBoolean("assistantPersisted", false),
            )
        }
    }
}

enum class UserAskRecoveryAction {
    /** A tool_result for this toolCallId is already in the transcript (or it was consumed). */
    ALREADY_DELIVERED,
    /** Answered / dismissed / timed out but never handed to the model. */
    DELIVER,
    /** Still pending and past its wall-clock deadline: time it out, then deliver. */
    TIME_OUT_THEN_DELIVER,
    /** Still pending and in time: show the card again and keep waiting. */
    AWAIT_USER,
    /**
     * [T-user-ask-deferred] A deferred ask resolved while nothing handed the
     * answer on: fill in its stored result and, for a real answer, send it.
     */
    DELIVER_DEFERRED,
}

object UserAskRecovery {
    /** Records older than this are dropped unseen (iOS purge maxAge). */
    const val MAX_AGE_MS = 7L * 24 * 3600 * 1000

    /** The result a sibling tool of a recovered batch gets: it never ran. */
    const val INTERRUPTED_RESULT =
        "<system-reminder>The app was closed before this tool finished; it did not run. Run it again if it is still needed.</system-reminder>"

    fun action(r: UserAskRecord, nowMs: Long, toolResultInStore: Boolean): UserAskRecoveryAction = when {
        // [T-user-ask-deferred] Its `deferred` result is in the store from the
        // start; what matters is whether the ANSWER was handed on (iOS). A
        // deferred record whose result never reached the store (killed before
        // the batch was written) is recovered like any other ask.
        r.ask.deferred && toolResultInStore -> when {
            r.ask.consumed -> UserAskRecoveryAction.ALREADY_DELIVERED
            r.ask.state.isTerminal -> UserAskRecoveryAction.DELIVER_DEFERRED
            r.ask.isExpired(nowMs) -> UserAskRecoveryAction.TIME_OUT_THEN_DELIVER
            else -> UserAskRecoveryAction.AWAIT_USER
        }
        r.ask.consumed || toolResultInStore -> UserAskRecoveryAction.ALREADY_DELIVERED
        r.ask.state.isTerminal -> UserAskRecoveryAction.DELIVER
        r.ask.isExpired(nowMs) -> UserAskRecoveryAction.TIME_OUT_THEN_DELIVER
        else -> UserAskRecoveryAction.AWAIT_USER
    }

    /** Whether a recovered run continues: only after a real answer or a timeout - the user ended a stopped or replaced one. */
    fun resumesRun(ask: PendingAsk): Boolean = ask.state == UserAskState.ANSWERED || ask.state == UserAskState.TIMED_OUT

    /** Drop delivered records and anything older than [MAX_AGE_MS]. */
    fun purge(records: Map<String, UserAskRecord>, nowMs: Long): Map<String, UserAskRecord> =
        records.filterValues { !it.ask.consumed && nowMs - it.ask.createdAtMs < MAX_AGE_MS }

    /**
     * Fallback when the saved assistant row is missing: the answers go to the
     * model as a plain message instead of being lost.
     */
    fun plainTextFallback(ask: PendingAsk): String =
        (listOf("(Recovered after the app restarted — answers to your earlier user_ask; the original request could not be restored, so please pick up from here.)") +
            answerLines(ask, withStatus = true)).joinToString("\n")

    /** One line per question: "1. question → status: answer (note: …)". */
    fun answerLines(ask: PendingAsk, withStatus: Boolean): List<String> {
        val statuses = ask.resultStatuses
        return ask.questions.mapIndexed { i, q ->
            val status = statuses.getOrNull(i)?.wire ?: "unanswered"
            val a = ask.answers.getOrElse(i) { UserAskAnswer() }
            val answer = mutableListOf<String>()
            q.options?.forEachIndexed { j, o -> if (j in a.selected) answer += o.label }
            a.otherText?.takeIf { it.isNotEmpty() }?.let { answer += it }
            a.confirmChoice?.let { answer += it.wire }
            if (!withStatus && a.delegated && answer.isEmpty()) answer += "you decide"
            var line = "${i + 1}. ${q.question} →"
            if (withStatus) line += " $status" + if (answer.isEmpty()) "" else ":"
            if (answer.isNotEmpty()) line += " " + answer.joinToString(", ")
            a.note?.takeIf { it.isNotEmpty() }?.let { line += " (note: $it)" }
            line
        }
    }

    /** tool_use ids, in order, of a saved assistant row's parts JSON. */
    fun toolUseOrder(assistantPartsJson: String?): List<Pair<String, String>> {
        val arr = runCatching { JSONArray(assistantPartsJson ?: "") }.getOrNull() ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            if (o.optString("type") != "toolUse") return@mapNotNull null
            val v = o.optJSONObject("value") ?: return@mapNotNull null
            v.optString("toolUseId").takeIf { it.isNotEmpty() }?.let { it to v.optString("name") }
        }
    }

    /**
     * The tool_result row recovery appends: the ask's result, and the
     * "interrupted" note for every other tool of the batch, in tool_use order
     * (Anthropic requires results to mirror the calls). Same shape as
     * ChatViewModel.persistToolResultMessage.
     */
    fun resultRowPartsJson(ask: PendingAsk, resultJson: String, toolOrder: List<Pair<String, String>>): String {
        val order = toolOrder.ifEmpty { listOf(ask.toolCallId to UserAskPromptText.TOOL_NAME) }
        val arr = JSONArray()
        for ((id, name) in order) {
            val mine = id == ask.toolCallId
            val output = if (mine) resultJson else INTERRUPTED_RESULT
            arr.put(
                JSONObject().put("type", "toolResult").put(
                    "value",
                    JSONObject()
                        .put("toolUseId", id)
                        .put("name", name.ifEmpty { if (mine) UserAskPromptText.TOOL_NAME else "" })
                        .put("output", output)
                        .put("success", mine)
                        .put("snapshot", JSONObject().put("type", "text").put("text", output.lines().takeLast(30).joinToString("\n"))),
                ),
            )
        }
        return arr.toString()
    }
}

/** JSON for PendingAsk, tolerant of missing fields (older records decode). */
object UserAskCodec {
    fun toJson(a: PendingAsk): JSONObject = JSONObject()
        .put("requestId", a.requestId)
        .put("sessionId", a.sessionId)
        .put("toolCallId", a.toolCallId)
        .put("questions", JSONArray(a.questions.map(::questionJson)))
        .put("answers", JSONArray(a.answers.map(::answerJson)))
        .put("submitted", JSONArray(a.submitted.sorted()))
        .put("currentIndex", a.currentIndex)
        .put("state", a.state.wire)
        .put("source", a.source?.wire ?: JSONObject.NULL)
        .put("dismissReason", a.dismissReason?.wire ?: JSONObject.NULL)
        .put("createdAtMs", a.createdAtMs)
        .put("expiresAtMs", a.expiresAtMs ?: JSONObject.NULL)
        .put("timeoutMinutes", a.timeoutMinutes)
        .put("resultJson", a.resultJson ?: JSONObject.NULL)
        .put("consumed", a.consumed)
        .put("sessionTitle", a.sessionTitle ?: JSONObject.NULL)
        .put("notifiedIndex", a.notifiedIndex ?: JSONObject.NULL)
        .put("deferred", a.deferred)

    fun fromJson(o: JSONObject): PendingAsk? = runCatching {
        val questions = o.getJSONArray("questions").let { arr -> (0 until arr.length()).mapNotNull { questionFrom(arr.getJSONObject(it)) } }
        if (questions.isEmpty()) return null
        val answersArr = o.optJSONArray("answers")
        val answers = List(questions.size) { i -> answersArr?.optJSONObject(i)?.let(::answerFrom) ?: UserAskAnswer() }
        val submitted = o.optJSONArray("submitted")?.let { arr -> (0 until arr.length()).map { arr.getInt(it) }.toSet() } ?: emptySet()
        PendingAsk(
            requestId = o.getString("requestId"),
            sessionId = o.getString("sessionId"),
            toolCallId = o.getString("toolCallId"),
            questions = questions,
            answers = answers,
            submitted = submitted,
            currentIndex = o.optInt("currentIndex", 0).coerceIn(0, questions.lastIndex),
            state = UserAskState.parse(o.optString("state")) ?: UserAskState.PENDING,
            source = UserAskAnswerSource.parse(o.optStringOrNull("source")),
            dismissReason = UserAskDismissReason.parse(o.optStringOrNull("dismissReason")),
            createdAtMs = o.getLong("createdAtMs"),
            expiresAtMs = if (o.isNull("expiresAtMs") || !o.has("expiresAtMs")) null else o.getLong("expiresAtMs"),
            timeoutMinutes = o.optInt("timeoutMinutes", UserAskSettingsSpec.DEFAULT_TIMEOUT_MINUTES),
            resultJson = o.optStringOrNull("resultJson"),
            consumed = o.optBoolean("consumed", false),
            sessionTitle = o.optStringOrNull("sessionTitle"),
            notifiedIndex = if (o.isNull("notifiedIndex") || !o.has("notifiedIndex")) null else o.getInt("notifiedIndex"),
            deferred = o.optBoolean("deferred", false),
        )
    }.getOrNull()

    private fun JSONObject.optStringOrNull(k: String): String? = if (!has(k) || isNull(k)) null else optString(k)

    private fun questionJson(q: UserAskQuestion): JSONObject = JSONObject()
        .put("type", q.type.wire)
        .put("question", q.question)
        .put("header", q.header ?: JSONObject.NULL)
        .put("options", q.options?.let { os -> JSONArray(os.map { JSONObject().put("label", it.label).put("description", it.description ?: JSONObject.NULL) }) } ?: JSONObject.NULL)
        .put("multiSelect", q.multiSelect)
        .put("impact", q.impact ?: JSONObject.NULL)
        .put("risk", q.risk.wire)
        .put("defaultIndex", q.defaultIndex ?: JSONObject.NULL)

    private fun questionFrom(o: JSONObject): UserAskQuestion? {
        val type = UserAskQuestionType.parse(o.optString("type")) ?: return null
        val options = o.optJSONArray("options")?.let { arr ->
            (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.map { UserAskOption(it.getString("label"), it.optStringOrNull("description")) }
        }
        return UserAskQuestion(
            type = type,
            question = o.getString("question"),
            header = o.optStringOrNull("header"),
            options = options,
            multiSelect = o.optBoolean("multiSelect", false),
            impact = o.optStringOrNull("impact"),
            risk = UserAskRisk.parse(o.optString("risk")) ?: UserAskRisk.MEDIUM,
            // A confirm never carries a default (removed policy).
            defaultIndex = if (type == UserAskQuestionType.OPTIONS && o.has("defaultIndex") && !o.isNull("defaultIndex")) o.optInt("defaultIndex") else null,
        )
    }

    private fun answerJson(a: UserAskAnswer): JSONObject = JSONObject()
        .put("selected", JSONArray(a.selected))
        .put("otherText", a.otherText ?: JSONObject.NULL)
        .put("note", a.note ?: JSONObject.NULL)
        .put("delegated", a.delegated)
        .put("confirmChoice", a.confirmChoice?.wire ?: JSONObject.NULL)

    private fun answerFrom(o: JSONObject): UserAskAnswer = UserAskAnswer(
        selected = o.optJSONArray("selected")?.let { arr -> (0 until arr.length()).map { arr.getInt(it) } } ?: emptyList(),
        otherText = o.optStringOrNull("otherText"),
        note = o.optStringOrNull("note"),
        delegated = o.optBoolean("delegated", false),
        confirmChoice = UserAskConfirmChoice.parse(o.optStringOrNull("confirmChoice")),
    )
}

/**
 * [T-user-ask-deferred] user_ask in a run no one can answer (iOS
 * UserAskDeferred, 7a818b8c0): the card is shown and stays open, the tool
 * returns at once, and the answer reaches the model later as the user's next
 * message.
 */
object UserAskDeferred {
    const val STATUS = "deferred"
    const val NOTE = UserAskPromptText.DEFERRED_NOTE

    /**
     * [T-user-ask-deferred-ttl] How long a deferred card stays open (iOS
     * 07fbac2c1). Asked in a run nobody watches, so far longer than the
     * interactive countdown, but it ends: on expiry it times out like any ask
     * - the stored `deferred` result becomes the timeout result, no message
     * is sent, a pending notification is withdrawn and none is posted again.
     */
    const val LIFETIME_MS = 60 * 60_000L

    /** The bubble note under the answers, for the model (stripped from the bubble). */
    const val ANSWER_REMINDER = "<system-reminder>The user's answers to your earlier question card.</system-reminder>"

    /**
     * [T-user-ask-deferred-delivery] How long a deferred answer waits for a
     * running compaction before it is submitted anyway (iOS: 600 x 0.5 s).
     */
    const val COMPACTION_WAIT_MS = 5 * 60_000L

    /** What the tool call returns at once; keys sorted like iOS (note, results; i, status). */
    fun toolResult(questionCount: Int): String =
        (0 until maxOf(questionCount, 1)).joinToString(",", "{\"note\":${JSONObject.quote(NOTE)},\"results\":[", "]}") {
            "{\"i\":$it,\"status\":\"$STATUS\"}"
        }

    /** The user's next message once they answer: the answers as they would say them, then a note for the model. */
    fun answerMessage(ask: PendingAsk): String =
        (UserAskRecovery.answerLines(ask, withStatus = false) + ANSWER_REMINDER).joinToString("\n")

    /** Only a real answer starts a turn; a dismissed, replaced or timed-out card just closes. */
    fun sendsAnswer(ask: PendingAsk): Boolean = ask.deferred && ask.state == UserAskState.ANSWERED
}

/**
 * [T-user-ask-deferred] [partsJson] with the output of the toolResult part for
 * [toolUseId] (and its snapshot text) replaced by [output]; null when no such
 * part is in it.
 */
internal fun UserAskRecovery.replaceToolResultOutput(partsJson: String, toolUseId: String, output: String): String? {
    if (!partsJson.contains(toolUseId)) return null
    val arr = runCatching { JSONArray(partsJson) }.getOrNull() ?: return null
    var found = false
    for (i in 0 until arr.length()) {
        val o = arr.optJSONObject(i) ?: continue
        if (o.optString("type") != "toolResult") continue
        val v = o.optJSONObject("value") ?: continue
        if (v.optString("toolUseId") != toolUseId) continue
        v.put("output", output)
        v.optJSONObject("snapshot")?.takeIf { it.optString("type") == "text" }
            ?.put("text", output.lines().takeLast(30).joinToString("\n"))
        found = true
    }
    return if (found) arr.toString() else null
}
