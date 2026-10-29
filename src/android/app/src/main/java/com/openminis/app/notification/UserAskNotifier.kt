package com.openminis.app.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import com.openminis.app.R
import com.openminis.app.agent.userask.PendingAsk
import com.openminis.app.agent.userask.UserAskCenter
import com.openminis.app.agent.userask.UserAskNotificationPlan
import com.openminis.app.agent.userask.UserAskNotificationPlan.Layout
import com.openminis.app.agent.userask.UserAskSettings
import com.openminis.app.agent.userask.UserAskState
import com.openminis.app.logging.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * [T-android-user-ask-notify] Posts a notification for a user_ask question
 * asked while the app is in the background (or the screen is off), answerable
 * from the notification - iOS UserAskCenter.postNotification /
 * handleNotificationResponse.
 *
 * Driven by UserAskCenter.asks and the process lifecycle, so every rule is one
 * reconciliation pass:
 *  - a pending ask with the app away posts its open question, once per question
 *    (PendingAsk.notifiedIndex), with sound only on the first;
 *  - an ask that resolves (answered, timed out, Stop, a new user message) or is
 *    forgotten loses its notification;
 *  - coming back to the app clears the notifications - the card is on screen -
 *    without re-alerting on the next trip to the background.
 *
 * Gated by the user_ask "Notify when in the background" setting and by the
 * notification permission; without either it silently posts nothing.
 */
object UserAskNotifier {
    private const val TAG = "UserAsk"
    const val CHANNEL_ID = "minis_user_ask"

    internal const val EXTRA_REQUEST_ID = "userAskRequestId"
    internal const val EXTRA_INDEX = "userAskQuestionIndex"
    internal const val EXTRA_ACTION = "userAskAction"
    internal const val REMOTE_INPUT_KEY = "userAskText"

    private val installed = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var app: Context

    /** Tags posted per request, so a resolved or foregrounded ask clears exactly those. */
    private val posted = HashMap<String, Int>()

    fun install(context: Context) {
        if (!installed.compareAndSet(false, true)) return
        app = context.applicationContext
        ensureChannel()
        val foreground = ProcessLifecycleOwner.get().lifecycle.currentStateFlow
            .map { it.isAtLeast(Lifecycle.State.STARTED) }
        scope.launch {
            combine(UserAskCenter.asks, foreground) { asks, fg -> asks to fg }
                .collect { (asks, fg) -> runCatching { sync(asks, fg) }.onFailure {
                    AppLogger.warning(TAG, "[UserAsk] notifier sync failed: ${it.javaClass.simpleName}: ${it.message}")
                } }
        }
    }

    private fun sync(asks: Map<String, PendingAsk>, appInForeground: Boolean) {
        // Resolved or forgotten: its notification goes.
        for (rid in posted.keys.toList()) {
            val a = asks[rid]
            if (a == null || a.state != UserAskState.PENDING) cancelAll(rid)
        }
        if (appInForeground) {
            // The card is on screen; the notification would only go stale.
            for (rid in posted.keys.toList()) cancelAll(rid)
            return
        }
        val enabled = UserAskSettings.notifyInBackground(app)
        val now = System.currentTimeMillis()
        for (a in asks.values) {
            val i = UserAskNotificationPlan.indexToPost(a, appInForeground = false, enabled = enabled, nowMs = now) ?: continue
            post(a, sound = UserAskNotificationPlan.alertsWithSound(a))
            // Marked even when nothing could be posted (permission off), so the
            // question is not retried on every change of the ask.
            UserAskCenter.markNotified(a.requestId, i)
        }
    }

    /**
     * Re-post the open question quietly: after a rejected or stale answer from
     * the notification (which also clears the reply field's spinner).
     */
    fun repost(requestId: String) {
        if (!installed.get()) return
        val a = UserAskCenter.asks.value[requestId]
        if (a == null || a.state != UserAskState.PENDING) { cancelAll(requestId); return }
        post(a, sound = false)
    }

    /** Clear one notification (an answer for an ask this process no longer has). */
    fun cancel(tag: String) {
        if (!installed.get()) return
        NotificationManagerCompat.from(app).cancel(tag, NOTIFICATION_ID)
    }

    /**
     * End a direct reply on [tag]. Android 15+ keeps a notification answered
     * through RemoteInput alive (FLAG_LIFETIME_EXTENDED_BY_DIRECT_REPLY) and
     * ignores the app cancelling it; only an update from the app ends that.
     * Seen on the Pixel 6: after replying to question 2, question 3 was posted
     * and question 2 stayed in the shade. So update it first, then cancel.
     */
    fun endReply(tag: String) {
        if (!installed.get()) return
        val nm = NotificationManagerCompat.from(app)
        runCatching {
            nm.notify(
                tag, NOTIFICATION_ID,
                NotificationCompat.Builder(app, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_notification_question)
                    .setContentTitle(app.getString(R.string.user_ask_needs_answer))
                    .setSilent(true)
                    .setTimeoutAfter(1_000)
                    .build(),
            )
        }
        nm.cancel(tag, NOTIFICATION_ID)
    }

    private fun cancelAll(requestId: String) {
        val count = posted.remove(requestId) ?: UserAskCenter.asks.value[requestId]?.questions?.size ?: 4
        val nm = NotificationManagerCompat.from(app)
        for (t in UserAskNotificationPlan.allTags(requestId, count)) nm.cancel(t, NOTIFICATION_ID)
    }

    private fun post(ask: PendingAsk, sound: Boolean): Boolean {
        val c = UserAskNotificationPlan.content(ask) ?: return false
        val nm = NotificationManagerCompat.from(app)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !nm.areNotificationsEnabled()) {
            AppLogger.info(TAG, "[UserAsk] NOTIFY skipped req=${ask.requestId.take(8)}: notifications disabled")
            return false
        }
        val tag = UserAskNotificationPlan.tag(ask.requestId, c.index)
        val label = app.getString(
            when {
                c.highRisk -> R.string.user_ask_notif_high_risk
                c.isApproval -> R.string.user_ask_needs_approval
                else -> R.string.user_ask_needs_answer
            },
        )
        val b = NotificationCompat.Builder(app, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_question)
            .setContentTitle(ask.sessionTitle?.takeIf { it.isNotBlank() } ?: label)
            .setSubText(label)
            .setContentText(c.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(c.body))
            .setContentIntent(openChat(ask))
            .setAutoCancel(true)
            .setSilent(!sound)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPublicVersion(
                NotificationCompat.Builder(app, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_notification_question)
                    .setContentTitle(label)
                    .setContentText(app.getString(R.string.user_ask_notif_hidden_body))
                    .build(),
            )
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
        ask.expiresAtMs?.let { b.setTimeoutAfter((it - System.currentTimeMillis()).coerceAtLeast(1_000)) }
        when (c.layout) {
            Layout.CONFIRM -> {
                b.addAction(button(ask, c.index, UserAskNotificationPlan.ACTION_CANCEL, app.getString(R.string.user_ask_cancel)))
                // Allow asks the system to unlock the device first (iOS
                // .authenticationRequired): no approving from a locked screen.
                b.addAction(
                    NotificationCompat.Action.Builder(
                        0, app.getString(R.string.user_ask_allow),
                        broadcast(ask, c.index, UserAskNotificationPlan.ACTION_ALLOW, mutable = false),
                    ).setAuthenticationRequired(true).build(),
                )
            }
            Layout.OPTIONS, Layout.REPLY_ONLY -> {
                val options = ask.questions[c.index].options.orEmpty()
                for (j in c.optionButtons) {
                    b.addAction(button(ask, c.index, UserAskNotificationPlan.optionActionId(j), options[j].label))
                }
                if (c.textReply) {
                    val title = app.getString(if (c.layout == Layout.REPLY_ONLY) R.string.user_ask_your_answer else R.string.user_ask_other)
                    val input = RemoteInput.Builder(REMOTE_INPUT_KEY)
                        .setLabel(app.getString(R.string.user_ask_your_answer))
                        .build()
                    b.addAction(
                        NotificationCompat.Action.Builder(
                            0, title, broadcast(ask, c.index, UserAskNotificationPlan.ACTION_OTHER, mutable = true),
                        ).addRemoteInput(input).setAllowGeneratedReplies(false).build(),
                    )
                }
            }
            Layout.OPEN_ONLY -> b.addAction(0, app.getString(R.string.user_ask_notif_open_app), openChat(ask))
        }
        // One notification per ask: clear the request's other questions.
        for (t in UserAskNotificationPlan.allTags(ask.requestId, ask.questions.size)) {
            if (t != tag) nm.cancel(t, NOTIFICATION_ID)
        }
        return try {
            nm.notify(tag, NOTIFICATION_ID, b.build())
            posted[ask.requestId] = ask.questions.size
            AppLogger.info(
                TAG,
                "[UserAsk] NOTIFY req=${ask.requestId.take(8)} q=${c.index + 1}/${ask.questions.size} layout=${c.layout} sound=$sound",
            )
            true
        } catch (se: SecurityException) {
            AppLogger.info(TAG, "[UserAsk] NOTIFY denied (POST_NOTIFICATIONS not granted)")
            false
        }
    }

    private fun button(ask: PendingAsk, index: Int, action: String, title: String) =
        NotificationCompat.Action.Builder(0, title, broadcast(ask, index, action, mutable = false)).build()

    private fun broadcast(ask: PendingAsk, index: Int, action: String, mutable: Boolean): PendingIntent {
        val intent = Intent(app, UserAskNotificationReceiver::class.java)
            .setAction("com.openminis.app.USER_ASK_ANSWER")
            // Distinct data per action, so PendingIntents never collapse into one.
            .setData(Uri.parse("minis-userask://${ask.requestId}/$index/$action"))
            .putExtra(EXTRA_REQUEST_ID, ask.requestId)
            .putExtra(EXTRA_INDEX, index)
            .putExtra(EXTRA_ACTION, action)
        // RemoteInput fills the intent in, which needs a mutable PendingIntent.
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (mutable && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE
            else PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(app, "${ask.requestId}/$index/$action".hashCode(), intent, flags)
    }

    /** Opens the chat (the card is its newest message), like the task-finished notification. */
    private fun openChat(ask: PendingAsk): PendingIntent {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("minis://session/${ask.sessionId}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(
            app, "open/${ask.requestId}".hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = ContextCompat.getSystemService(app, NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        // High: a question that times out in minutes should pop up, not wait in the shade.
        val channel = NotificationChannel(
            CHANNEL_ID, app.getString(R.string.user_ask_notif_channel_name), NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = app.getString(R.string.user_ask_notif_channel_description)
            setShowBadge(true)
        }
        manager.createNotificationChannel(channel)
    }

    private const val NOTIFICATION_ID = 7301
}
