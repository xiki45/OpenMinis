package com.openminis.app.ui.chat

import com.openminis.app.ProductionSources
import com.openminis.app.ui.chat.StopConfirmPolicy.TapAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-stop-confirm] The composer's stop button asks "Stop this task?" first
 * (Settings > Appearance > Confirm Before Stopping, default ON); nothing else
 * that stops a task asks.
 */
class StopConfirmPolicyTest {

    // ── decision ──────────────────────────────────────────────────────

    @Test
    fun `with the setting on a tap asks first`() {
        assertEquals(TapAction.CONFIRM_STOP, StopConfirmPolicy.onTap(hasBackground = false, confirmEnabled = true))
    }

    @Test
    fun `with the setting off a tap stops at once - today's behaviour`() {
        assertEquals(TapAction.STOP_NOW, StopConfirmPolicy.onTap(hasBackground = false, confirmEnabled = false))
    }

    @Test
    fun `background work asks what to stop, setting on or off`() {
        // [T-stop-choice] Sub agents or scheduled tasks: "Stop Current Tool Only / Stop All".
        assertEquals(TapAction.CHOOSE_SCOPE, StopConfirmPolicy.onTap(hasBackground = true, confirmEnabled = true))
        assertEquals(TapAction.CHOOSE_SCOPE, StopConfirmPolicy.onTap(hasBackground = true, confirmEnabled = false))
    }

    @Test
    fun `default is on, under the same key as iOS`() {
        // [T-stop-mode-longpress] Default OFF: the stop button stops at once.
        assertFalse(StopConfirmPolicy.DEFAULT_ENABLED)
        assertEquals(TapAction.STOP_NOW, StopConfirmPolicy.onTap(hasBackground = false, confirmEnabled = StopConfirmPolicy.DEFAULT_ENABLED))
        assertEquals("chat.confirmBeforeStopping", StopConfirmPolicy.SETTINGS_KEY)
    }

    // ── the dialog only stops the task it was opened for ──────────────

    @Test
    fun `stop applies while the same turn is still running`() {
        assertTrue(StopConfirmPolicy.confirmStillApplies(turnAtOpen = 7, turnNow = 7, isBusy = true))
    }

    @Test
    fun `stop is a no-op once the conversation is idle`() {
        assertFalse(StopConfirmPolicy.confirmStillApplies(turnAtOpen = 7, turnNow = null, isBusy = false))
        assertFalse(StopConfirmPolicy.confirmStillApplies(turnAtOpen = null, turnNow = null, isBusy = false))
    }

    @Test
    fun `stop never ends the next turn (a queued prompt started while the dialog was up)`() {
        assertFalse(StopConfirmPolicy.confirmStillApplies(turnAtOpen = 7, turnNow = 8, isBusy = true))
    }

    @Test
    fun `opened while only sub agents or timers were busy - applies while that is still so`() {
        assertTrue(StopConfirmPolicy.confirmStillApplies(turnAtOpen = null, turnNow = null, isBusy = true))
        assertFalse("a turn of the chat's own loop started meanwhile",
            StopConfirmPolicy.confirmStillApplies(turnAtOpen = null, turnNow = 3, isBusy = true))
    }

    // ── wiring ────────────────────────────────────────────────────────

    private val screen = ProductionSources.read("ui/chat/ChatScreen.kt")

    @Test
    fun `the composer stop button goes through the policy and reads the setting at tap time`() {
        val stopBlock = screen.substringAfter("if (showStop) {").substringBefore("} else {\n                            // Streaming with content")
        assertTrue(stopBlock.contains("val summary = viewModel.stopSummary()"))
        assertTrue(stopBlock.contains("StopConfirmPolicy.onTap(summary.hasBackground, confirmEnabled)"))
        assertTrue(stopBlock.contains(".getBoolean(StopConfirmPolicy.SETTINGS_KEY, StopConfirmPolicy.DEFAULT_ENABLED)"))
        assertTrue("the turn it was opened for", stopBlock.contains("confirmStopTurn = viewModel.liveTurnToken()"))
        assertTrue(stopBlock.contains("StopConfirmPolicy.confirmStillApplies(confirmStopTurn, viewModel.liveTurnToken(), sessionBusy)"))
        assertTrue("Keep Running is the dismiss button", stopBlock.contains("dismissText = stringResource(R.string.chat_stop_confirm_keep_running)"))
        assertTrue(stopBlock.contains("isDestructive = true"))
        // The dialog lives inside the stop button's block: when the task ends
        // the button (and with it the dialog) leaves composition.
        assertTrue(stopBlock.contains("if (confirmStop) {"))
    }

    @Test
    fun `minis-config exposes the same pref and default`() {
        val builtins = ProductionSources.read("config/ConfigBuiltins.kt")
        val field = builtins.substringAfter("path = \"chat.confirmBeforeStopping\"").substringBefore("\n        )")
        assertTrue(field.contains("prefs = appearancePrefs"))
        assertTrue(field.contains("key = com.openminis.app.ui.chat.StopConfirmPolicy.SETTINGS_KEY"))
        assertTrue(field.contains("defaultValue = com.openminis.app.ui.chat.StopConfirmPolicy.DEFAULT_ENABLED"))
    }

    @Test
    fun `programmatic stops do not ask`() {
        val rpc = ProductionSources.read("debug/ChatMutationMethods.kt")
        assertTrue("chat.session.cancel cancels directly", rpc.contains("HeadlessChatRunner.cancel(context, sessionId)"))
        for (file in listOf(
            "debug/ChatMutationMethods.kt", "debug/HeadlessChatRunner.kt", "ui/chat/HelperUi.kt",
            "ui/chat/SubAgentRunDetail.kt", "scheduled/ScheduledAgentRunner.kt", "service/AgentForegroundService.kt",
            "ui/chat/ChatViewModel.kt",
        )) {
            assertFalse("$file must not route through the confirmation", ProductionSources.read(file).contains("StopConfirmPolicy.onTap"))
        }
        assertEquals("only the composer button uses it", 1, Regex("StopConfirmPolicy\\.onTap\\(").findAll(screen).count())
        assertTrue("New Chat's own dialog still stops directly", screen.contains("showNewChatStopDialog = false\n                        viewModel.cancelStream()"))
    }

    @Test
    fun `the setting is in Appearance and the strings exist in en, zh-CN and zh-TW`() {
        val appearance = ProductionSources.read("ui/settings/AppearanceScreen.kt")
        assertTrue(appearance.contains("prefs.edit().putBoolean(com.openminis.app.ui.chat.StopConfirmPolicy.SETTINGS_KEY, it).apply()"))
        val base = listOf("src/main/res", "app/src/main/res").map { java.io.File(it) }.first { it.exists() }
        for (dir in listOf("values", "values-zh", "values-zh-rTW")) {
            val xml = java.io.File(base, "$dir/strings.xml").readText()
            for (key in listOf("chat_stop_confirm_title", "chat_stop_confirm_message", "chat_stop_confirm_stop",
                "chat_stop_confirm_keep_running", "appearance_confirm_before_stopping_title", "appearance_confirm_before_stopping_footer",
                "stop_mode_menu_title", "stop_mode_immediately", "stop_mode_a11y_action")) {
                assertTrue("$dir/$key", xml.contains("name=\"$key\""))
            }
        }
        val en = java.io.File(base, "values/strings.xml").readText()
        assertTrue(en.contains(">Stop this task?<"))
        assertTrue(en.contains(">The current reply and any running tools will be stopped.<"))
        assertTrue(en.contains(">Keep Running<"))
        assertTrue(en.contains(">Confirm Before Stopping<"))
    }
}
