package com.openminis.app.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput
import com.openminis.app.agent.userask.UserAskCenter
import com.openminis.app.agent.userask.UserAskNotificationPlan
import com.openminis.app.agent.userask.UserAskNotificationPlan.Outcome

/**
 * [T-android-user-ask-notify] An answer given in a user_ask notification: an
 * option button, Allow / Cancel, or the "Other…" text reply. Hands it to
 * UserAskCenter (first wins against the card, the timeout and Stop); the
 * notifier then moves the notification on to the next question or clears it.
 */
class UserAskNotificationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val requestId = intent.getStringExtra(UserAskNotifier.EXTRA_REQUEST_ID) ?: return
        val index = intent.getIntExtra(UserAskNotifier.EXTRA_INDEX, -1)
        val action = intent.getStringExtra(UserAskNotifier.EXTRA_ACTION) ?: return
        val text = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(UserAskNotifier.REMOTE_INPUT_KEY)?.toString()
        val outcome = UserAskCenter.answerFromNotification(requestId, index, action, text)
        // A RemoteInput reply keeps its notification alive until the app
        // updates it; end that before moving on (the next question, a repost).
        if (action == UserAskNotificationPlan.ACTION_OTHER) {
            UserAskNotifier.endReply(UserAskNotificationPlan.tag(requestId, index))
        }
        when (outcome) {
            Outcome.ADVANCED, Outcome.COMPLETE -> Unit
            // Empty reply, or the ask is on another question now: show the open
            // question again (this also ends the reply field's spinner).
            Outcome.REJECTED, Outcome.STALE -> UserAskNotifier.repost(requestId)
            // Not in this process (it was killed meanwhile): the agent run that
            // waited for it is gone, and the chat restores the card on open.
            Outcome.NOT_FOUND -> UserAskNotifier.cancel(UserAskNotificationPlan.tag(requestId, index))
        }
    }
}
