package com.openminis.app.ui.chat

/**
 * [T-stop-confirm] What a tap on the composer's stop button does. Mirrors iOS
 * `StopConfirmPolicy` (ChatSendButton.swift) so both platforms ask the same
 * way. Only that button asks: programmatic cancels (debug RPC
 * chat.session.cancel, shortcuts, scheduled and sub-agent internals, a sub
 * agent card's Stop, New Chat's own "stop and start new" dialog, cancelling a
 * queued prompt, read-aloud / voice) call `stopByUser()` / `cancelStream()`
 * directly and never pass through here.
 *
 * Why: accidental one-handed taps on the composer's stop button ended long
 * agent runs halfway (Telegram feedback, 2026-10-04..07).
 */
object StopConfirmPolicy {

    /** Appearance pref key; same name as iOS `@AppStorage("chat.confirmBeforeStopping")`. */
    const val SETTINGS_KEY = "chat.confirmBeforeStopping"

    /**
     * [T-stop-mode-longpress] Default OFF (stop at once), as on iOS; opt in from
     * Settings > Appearance, the stop button's long-press, or minis-config.
     */
    const val DEFAULT_ENABLED = false

    enum class TapAction {
        /**
         * [T-stop-choice] Something besides the turn would be lost (a sub agent,
         * or a scheduled task still due): ask "Stop Current Tool Only / Stop
         * All". Replaces the scheduled-tasks-only warning, which never mentioned
         * the sub agents that Stop always ended too.
         */
        CHOOSE_SCOPE,

        /** Ask "Stop this task?" first. */
        CONFIRM_STOP,

        /** Stop at once (the setting is off). */
        STOP_NOW,
    }

    /**
     * Only reached while the stop button is shown, i.e. the conversation is busy.
     * [hasBackground] is [StopSummary.hasBackground]: without background work
     * nothing changes - the setting decides, and by default Stop is immediate.
     */
    fun onTap(hasBackground: Boolean, confirmEnabled: Boolean): TapAction = when {
        hasBackground -> TapAction.CHOOSE_SCOPE
        confirmEnabled -> TapAction.CONFIRM_STOP
        else -> TapAction.STOP_NOW
    }

    /**
     * Whether the dialog's Stop still refers to the task it was opened for.
     * [turnAtOpen] / [turnNow] are the chat loop's live-turn tokens
     * (`LiveTurnGuard.liveGeneration()`, null when no own turn is running).
     * Nothing is stopped once the conversation is idle, and a dialog opened
     * during one turn never stops the next one (a queued prompt that started
     * while the dialog was up has a different token). One opened while only
     * sub agents or timers kept the conversation busy (token null) applies
     * while that is still the case.
     */
    fun confirmStillApplies(turnAtOpen: Long?, turnNow: Long?, isBusy: Boolean): Boolean =
        isBusy && turnAtOpen == turnNow
}
