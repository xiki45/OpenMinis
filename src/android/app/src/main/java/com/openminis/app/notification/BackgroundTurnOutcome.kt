package com.openminis.app.notification

import com.openminis.app.ui.chat.ChatMessage

/**
 * [T-bgnotif-outcome] How a run ended, for the background task notification
 * (iOS BackgroundTurnOutcome, 387bca64c).
 *
 * The notification used to read "completed" unless the run THREW
 * ([com.openminis.app.service.SessionActivityTracker.markStreamError] is only
 * called from the loop's exception handlers). Every deliberate stop of an
 * unfinished turn ended the loop normally and was announced as done: a
 * stream that dropped mid-reply, a max-tokens cut, a refusal, an empty reply
 * that survived the reminder, the DSML give-up (all an inline error plus
 * Resume), the context-full stop (Resume only), and a Stop from the overlay
 * while the app was in the background.
 *
 * iOS's first cause, background time running out, has no Android
 * counterpart: the agent runs in a foreground service.
 */
enum class BackgroundTurnOutcome {
    COMPLETED,
    FAILED,
    INTERRUPTED;

    companion object {
        /**
         * In iOS's order: an error (thrown, or shown on this turn's reply) is
         * a failure; otherwise a turn that can be resumed, or that the user
         * stopped, is paused; anything else completed.
         */
        fun decide(streamError: Boolean, turnError: Boolean, canResume: Boolean, userStopped: Boolean): BackgroundTurnOutcome =
            when {
                streamError || turnError -> FAILED
                canResume || userStopped -> INTERRUPTED
                else -> COMPLETED
            }

        /**
         * The error on THIS turn's reply: the last assistant row after the
         * last user row actually sent (a queued prompt is not sent yet). Not
         * the list's last row, which can be a queued prompt or a notice
         * behind the reply, and never an earlier turn's error.
         */
        fun currentTurnError(messages: List<ChatMessage>): String? {
            val lastSent = messages.indexOfLast { it.role == "user" && !it.isQueued }
            for (i in messages.indices.reversed()) {
                if (i <= lastSent) break
                val m = messages[i]
                if (m.role == "assistant") return m.error?.takeIf { it.isNotBlank() }
            }
            return null
        }
    }
}
