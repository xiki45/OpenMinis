package com.openminis.app.agent.userask

import android.content.Context
import com.openminis.app.tools.AgentToolSwitch

/**
 * [T-port-user-ask] Settings for the user_ask tool, in the same
 * SharedPreferences file as the other agent tool switches, under the iOS key
 * names (UserAskSettingsSpec). The on/off switch is AgentToolSwitch.USER_ASK.
 */
object UserAskSettings {
    fun isEnabled(context: Context): Boolean = AgentToolSwitch.USER_ASK.isEnabled(context)

    fun setEnabled(context: Context, enabled: Boolean) = AgentToolSwitch.USER_ASK.setEnabled(context, enabled)

    /** Minutes to wait for an answer; 0 = indefinitely. */
    fun timeoutMinutes(context: Context): Int = UserAskSettingsSpec.clampTimeout(
        AgentToolSwitch.prefs(context).getInt(UserAskSettingsSpec.TIMEOUT_MINUTES_KEY, UserAskSettingsSpec.DEFAULT_TIMEOUT_MINUTES),
    )

    fun setTimeoutMinutes(context: Context, minutes: Int) {
        AgentToolSwitch.prefs(context).edit()
            .putInt(UserAskSettingsSpec.TIMEOUT_MINUTES_KEY, UserAskSettingsSpec.clampTimeout(minutes)).apply()
    }

    /** Post a notification for a question asked while the app is in the background (iOS default: on). */
    fun notifyInBackground(context: Context): Boolean =
        AgentToolSwitch.prefs(context).getBoolean(
            UserAskSettingsSpec.NOTIFY_IN_BACKGROUND_KEY, UserAskSettingsSpec.DEFAULT_NOTIFY_IN_BACKGROUND,
        )

    fun setNotifyInBackground(context: Context, on: Boolean) {
        AgentToolSwitch.prefs(context).edit().putBoolean(UserAskSettingsSpec.NOTIFY_IN_BACKGROUND_KEY, on).apply()
    }
}
