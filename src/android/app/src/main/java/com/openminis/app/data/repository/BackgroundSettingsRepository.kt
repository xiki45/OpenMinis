package com.openminis.app.data.repository

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * T180-bg-notif: persistence for background-related toggles. Currently
 * only "Task Notifications" lives here; iOS exposes the same toggle in
 * `EnhancedBackgroundSettingsView` bound to
 * `BackgroundKeepAliveManager.backgroundNotificationsEnabled`.
 *
 * Default value is `true` to match iOS, where the toggle ships ON so
 * Live Activity and task-completion notifications work out-of-the-box
 * on first install. The user can opt out from Settings.
 */
class BackgroundSettingsRepository(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _taskNotificationsEnabled =
        MutableStateFlow(prefs.getBoolean(KEY_TASK_NOTIFICATIONS, DEFAULT_TASK_NOTIFICATIONS))

    /**
     * Live state of the toggle. Compose surfaces collect this so flipping
     * the switch in Settings is reflected immediately at every consumer
     * (notifier, FG service status text, etc).
     */
    val taskNotificationsEnabled: StateFlow<Boolean> =
        _taskNotificationsEnabled.asStateFlow()

    fun setTaskNotificationsEnabled(value: Boolean) {
        prefs.edit().putBoolean(KEY_TASK_NOTIFICATIONS, value).apply()
        _taskNotificationsEnabled.value = value
    }

    /**
     * T-bg-overlay phase 2: "show floating tool-status overlay while the
     * app is backgrounded" toggle. Defaults to OFF — the overlay needs
     * SYSTEM_ALERT_WINDOW which is a separate system permission flow, so
     * we won't surface anything until the user opts in.
     */
    private val _backgroundOverlayEnabled =
        MutableStateFlow(prefs.getBoolean(KEY_BG_OVERLAY_ENABLED, false))
    val backgroundOverlayEnabled: StateFlow<Boolean> =
        _backgroundOverlayEnabled.asStateFlow()

    fun setBackgroundOverlayEnabled(value: Boolean) {
        prefs.edit().putBoolean(KEY_BG_OVERLAY_ENABLED, value).apply()
        _backgroundOverlayEnabled.value = value
    }

    /**
     * [T-android-dynamic-island] "Show live status on the dynamic island"
     * toggle (Android 16 Live Updates). Defaults to OFF — the capability only
     * exists on Android 16+ with the per-app grant, and when ON it REPLACES the
     * floating overlay (mutual exclusion in AgentForegroundService.applyOverlayState),
     * so we don't want it silently changing behavior on upgrade. Reactive:
     * flipping it re-drives the FG service's combined flow so the overlay
     * appears/disappears without an app restart.
     */
    private val _dynamicIslandEnabled =
        MutableStateFlow(prefs.getBoolean(KEY_DYNAMIC_ISLAND_ENABLED, false))
    val dynamicIslandEnabled: StateFlow<Boolean> =
        _dynamicIslandEnabled.asStateFlow()

    fun setDynamicIslandEnabled(value: Boolean) {
        prefs.edit().putBoolean(KEY_DYNAMIC_ISLAND_ENABLED, value).apply()
        _dynamicIslandEnabled.value = value
    }

    /**
     * [FIX-261025-03] How long before a scheduled task's next run its chat
     * counts as active again, bringing back the ongoing notification, the
     * capsule / Live Updates countdown and the keep-alive. Stored as an index
     * into [SCHEDULED_COUNTDOWN_CASES]; see [scheduledCountdownLeadMs].
     * Default 1 minute: holding the chat for the whole wait kept a daily
     * task's notification and status chip up all day.
     */
    private val _scheduledCountdownLead =
        MutableStateFlow(readScheduledCountdownLead(prefs))
    val scheduledCountdownLead: StateFlow<Int> = _scheduledCountdownLead.asStateFlow()

    fun setScheduledCountdownLead(index: Int) {
        val safe = index.takeIf { it in SCHEDULED_COUNTDOWN_CASES.indices } ?: DEFAULT_SCHEDULED_COUNTDOWN_LEAD
        prefs.edit().putInt(KEY_SCHEDULED_COUNTDOWN_LEAD, safe).apply()
        _scheduledCountdownLead.value = safe
    }

    /**
     * `minis-config set background.notifications|dynamicIsland|...` writes
     * this prefs file directly (ConfigBuiltins PrefsBoolField), not through
     * the setters above. Without this listener the flows kept the value read
     * at construction, so a tester's `minis-config set background.dynamicIsland
     * false` left the ongoing notification promoted to the lock-screen chip
     * until the app restarted. Held in a field: SharedPreferences keeps only
     * a weak reference to its listeners.
     */
    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { p, key ->
        when (key) {
            KEY_TASK_NOTIFICATIONS ->
                _taskNotificationsEnabled.value = p.getBoolean(key, DEFAULT_TASK_NOTIFICATIONS)
            KEY_BG_OVERLAY_ENABLED -> _backgroundOverlayEnabled.value = p.getBoolean(key, false)
            KEY_DYNAMIC_ISLAND_ENABLED -> _dynamicIslandEnabled.value = p.getBoolean(key, false)
            KEY_SCHEDULED_COUNTDOWN_LEAD -> _scheduledCountdownLead.value = readScheduledCountdownLead(p)
        }
    }

    init {
        prefs.registerOnSharedPreferenceChangeListener(prefsListener)
    }

    /**
     * Last persisted overlay position (window x/y in pixels) from the
     * previous drag, or null when none was ever stored - the overlay
     * controller then picks a default near the bottom-left, 10 dp from each
     * edge ([T-bg-overlay-polish]).
     *
     * [T-overlay-position-restore] "Never stored" is the keys being absent,
     * not a value: a capsule dragged partly off the left or top edge has a
     * negative coordinate, and the old `-1` default made such a position
     * look like no position at all.
     */
    fun getOverlayPosition(landscape: Boolean = false): Pair<Int, Int>? {
        val (kx, ky) = overlayKeys(landscape)
        if (!prefs.contains(kx) || !prefs.contains(ky)) return null
        return prefs.getInt(kx, 0) to prefs.getInt(ky, 0)
    }

    fun setOverlayPosition(x: Int, y: Int, landscape: Boolean = false) {
        val (kx, ky) = overlayKeys(landscape)
        prefs.edit().putInt(kx, x).putInt(ky, y).apply()
    }

    /**
     * [T-overlay-orientation-position] Portrait keeps the original keys, so
     * the one position earlier builds stored is read as the portrait one
     * without a migration, and an older build reading this file still finds
     * a position. Landscape gets its own pair.
     */
    private fun overlayKeys(landscape: Boolean): Pair<String, String> =
        if (landscape) KEY_BG_OVERLAY_LAND_X to KEY_BG_OVERLAY_LAND_Y else KEY_BG_OVERLAY_X to KEY_BG_OVERLAY_Y

    companion object {
        internal const val PREFS_NAME = "background_settings"
        internal const val KEY_TASK_NOTIFICATIONS = "taskNotificationsEnabled"
        internal const val DEFAULT_TASK_NOTIFICATIONS = true
        private const val KEY_BG_OVERLAY_ENABLED = "backgroundOverlayEnabled"
        private const val KEY_BG_OVERLAY_X = "backgroundOverlayX"
        private const val KEY_BG_OVERLAY_Y = "backgroundOverlayY"
        private const val KEY_BG_OVERLAY_LAND_X = "backgroundOverlayLandscapeX"
        private const val KEY_BG_OVERLAY_LAND_Y = "backgroundOverlayLandscapeY"
        internal const val KEY_DYNAMIC_ISLAND_ENABLED = "dynamicIslandEnabled"

        internal const val KEY_SCHEDULED_COUNTDOWN_LEAD = "scheduledCountdownLead"

        /** minis-config values, in stored-index order. */
        internal val SCHEDULED_COUNTDOWN_CASES = listOf("off", "1m", "5m", "15m", "always")
        internal const val DEFAULT_SCHEDULED_COUNTDOWN_LEAD = 1

        internal fun readScheduledCountdownLead(p: SharedPreferences): Int =
            p.getInt(KEY_SCHEDULED_COUNTDOWN_LEAD, DEFAULT_SCHEDULED_COUNTDOWN_LEAD)
                .takeIf { it in SCHEDULED_COUNTDOWN_CASES.indices } ?: DEFAULT_SCHEDULED_COUNTDOWN_LEAD

        /**
         * The lead for a stored index: 0 = never (no wait is shown), [Long.MAX_VALUE] =
         * the whole wait (the behavior before this setting).
         */
        fun scheduledCountdownLeadMs(index: Int): Long = when (index) {
            0 -> 0L
            1 -> 60_000L
            2 -> 5 * 60_000L
            3 -> 15 * 60_000L
            else -> Long.MAX_VALUE
        }
    }
}
