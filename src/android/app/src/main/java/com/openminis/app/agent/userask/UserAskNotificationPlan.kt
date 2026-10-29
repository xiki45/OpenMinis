package com.openminis.app.agent.userask

/**
 * [T-android-user-ask-notify] Pure plan of the notification posted for a
 * question asked while the app is in the background, and of how an answer
 * given in that notification maps back onto the ask. Port of iOS
 * `UserAskNotificationPlan` (UserAskCore.swift), adapted to Android: a
 * notification shows at most [MAX_ACTIONS] action buttons, where iOS has room
 * for every option plus "Other…".
 *
 * No Android types here - UserAskNotifier builds the notification from it, and
 * UserAskNotificationPlanTest covers it on the JVM.
 */
object UserAskNotificationPlan {
    /** Android shows at most three actions on a notification. */
    const val MAX_ACTIONS = 3

    const val ACTION_OTHER = "other"
    const val ACTION_CANCEL = "confirm.cancel"
    const val ACTION_ALLOW = "confirm.allow"

    fun optionActionId(index: Int) = "opt:$index"

    fun optionIndex(actionId: String): Int? =
        if (actionId.startsWith("opt:")) actionId.removePrefix("opt:").toIntOrNull() else null

    /**
     * The question the notification shows: the first one not yet submitted, so
     * a multi-question ask is answered one question at a time and the
     * notification moves on after each answer. Null once every question is in.
     */
    fun notifiedIndex(ask: PendingAsk): Int? = ask.questions.indices.firstOrNull { it !in ask.submitted }

    /**
     * One notification tag PER QUESTION (iOS T-user-ask-notif-next-id): the
     * next question is a new notification, never an in-place update of the one
     * the user just answered.
     */
    fun tag(requestId: String, index: Int) = "user-ask-$requestId-q$index"

    fun allTags(requestId: String, questionCount: Int): List<String> =
        (0 until maxOf(questionCount, 1)).map { tag(requestId, it) }

    /** Which controls a question's notification carries. */
    enum class Layout {
        /** Cancel / Allow. */
        CONFIRM,
        /** One button per option, plus an "Other…" text reply when it fits. */
        OPTIONS,
        /** An open-ended question: a text reply only. */
        REPLY_ONLY,
        /** Multi-select, or more options than fit: answered in the app. */
        OPEN_ONLY,
    }

    data class Content(
        val index: Int,
        val layout: Layout,
        /** Option indices shown as buttons, in order. */
        val optionButtons: List<Int> = emptyList(),
        /** Whether a RemoteInput text reply is offered. */
        val textReply: Boolean = false,
        val body: String,
        val isApproval: Boolean = false,
        val highRisk: Boolean = false,
    )

    /**
     * What the notification for [ask] shows: its open question and the
     * controls that question can be answered with. Null when nothing is open.
     *
     * Single choice keeps every option or none: with n options, n buttons plus
     * "Other…" when n + 1 fits, n buttons alone when only n fits, and the app
     * when even that does not - a subset of the options would steer the answer.
     */
    fun content(ask: PendingAsk): Content? {
        val i = notifiedIndex(ask) ?: return null
        val q = ask.questions[i]
        val body = body(ask, i)
        if (q.type == UserAskQuestionType.CONFIRM) {
            return Content(i, Layout.CONFIRM, body = body, isApproval = true, highRisk = q.risk == UserAskRisk.HIGH)
        }
        val options = q.options.orEmpty()
        return when {
            q.multiSelect -> Content(i, Layout.OPEN_ONLY, body = body)
            options.isEmpty() -> Content(i, Layout.REPLY_ONLY, textReply = true, body = body)
            options.size + 1 <= MAX_ACTIONS -> Content(i, Layout.OPTIONS, options.indices.toList(), textReply = true, body = body)
            options.size <= MAX_ACTIONS -> Content(i, Layout.OPTIONS, options.indices.toList(), body = body)
            else -> Content(i, Layout.OPEN_ONLY, body = body)
        }
    }

    /** The question text, prefixed "2/3 · " when the ask has several. */
    fun body(ask: PendingAsk, index: Int): String {
        val q = ask.questions[index]
        val text = if (q.type == UserAskQuestionType.CONFIRM) q.question.take(100) else q.question
        return if (ask.questions.size > 1) "${index + 1}/${ask.questions.size} · $text" else text
    }

    /**
     * The question to post a notification for now, or null. Posts only for a
     * pending, unexpired ask while the app is away, and only once per question:
     * going to the background again does not re-alert (iOS 07fbac2c1).
     */
    fun indexToPost(ask: PendingAsk, appInForeground: Boolean, enabled: Boolean, nowMs: Long): Int? {
        if (!enabled || appInForeground) return null
        if (ask.state != UserAskState.PENDING || ask.isExpired(nowMs)) return null
        val i = notifiedIndex(ask) ?: return null
        return i.takeIf { it != ask.notifiedIndex }
    }

    /** Sound only on the ask's first notification; moving to the next question is quiet. */
    fun alertsWithSound(ask: PendingAsk): Boolean = ask.notifiedIndex == null

    /** What an answer given in a notification did. */
    enum class Outcome {
        /** The question was answered and the ask moved on to the next one. */
        ADVANCED,
        /** The last open question was answered: the ask resolves. */
        COMPLETE,
        /** Not a usable answer (empty text, an action this question lacks). */
        REJECTED,
        /** The ask is no longer pending, or is on another question now. */
        STALE,
        /** No such ask in this process. */
        NOT_FOUND,
    }

    /**
     * Apply a notification answer for question [index] to [ask]. First come,
     * first served: an ask already resolved, expired or moved on to another
     * question (answered in the app meanwhile) ignores it. Returns the updated
     * ask (unchanged unless ADVANCED / COMPLETE) and the outcome; COMPLETE
     * still needs the caller to resolve the ask.
     */
    fun apply(ask: PendingAsk, index: Int, actionId: String, text: String?, nowMs: Long): Pair<PendingAsk, Outcome> {
        if (ask.state != UserAskState.PENDING || ask.isExpired(nowMs)) return ask to Outcome.STALE
        if (index != notifiedIndex(ask)) return ask to Outcome.STALE
        val q = ask.questions[index]
        val cur = ask.answers[index]
        val opt = optionIndex(actionId)
        val answer = when {
            q.type == UserAskQuestionType.OPTIONS && !q.multiSelect && opt != null && opt in q.options.orEmpty().indices ->
                cur.copy(selected = listOf(opt), otherText = null, delegated = false)
            q.type == UserAskQuestionType.OPTIONS && !q.multiSelect && actionId == ACTION_OTHER && !text.isNullOrBlank() ->
                cur.copy(selected = emptyList(), otherText = text.trim().take(UserAskParser.MAX_INPUT_LENGTH), delegated = false)
            q.type == UserAskQuestionType.CONFIRM && actionId == ACTION_ALLOW -> cur.copy(confirmChoice = UserAskConfirmChoice.APPROVE)
            q.type == UserAskQuestionType.CONFIRM && actionId == ACTION_CANCEL -> cur.copy(confirmChoice = UserAskConfirmChoice.DENY)
            else -> return ask to Outcome.REJECTED
        }
        val (next, outcome) = ask.setAnswer(answer, index).submit(index, nowMs)
        return when (outcome) {
            is UserAskSubmitOutcome.Advanced -> PendingAsk.preselectRecommended(next, outcome.to) to Outcome.ADVANCED
            UserAskSubmitOutcome.Complete -> next to Outcome.COMPLETE
            UserAskSubmitOutcome.Rejected -> ask to Outcome.REJECTED
        }
    }
}
