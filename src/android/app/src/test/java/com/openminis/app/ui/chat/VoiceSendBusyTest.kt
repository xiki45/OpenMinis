package com.openminis.app.ui.chat

import com.openminis.app.ProductionSources
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-voice-send-busy] Send tapped while voice is still recording: the button
 * is busy and takes no taps until the message has gone out, and any user
 * change takes the pending send back (port of iOS b48cfdbfe).
 */
class VoiceSendBusyTest {

    private val gate = ProductionSources.read("ui/chat/voice/VoiceSendGate.kt")
    private val panel = ProductionSources.read("ui/chat/voice/InlineVoiceInputPanel.kt")

    @Test
    fun `only the pending attempt can send or release`() {
        val finish = gate.substringAfter("private fun finishCaptureForSend(").substringBefore("\n    }\n}")
        assertTrue(finish.contains("val attempt = ++attempts\n        pendingAttempt = attempt"))
        assertTrue("an abandoned wait stops polling", finish.contains("if (pendingAttempt != attempt) return@launch"))
        assertTrue(finish.contains("if (pendingAttempt == attempt) {\n                    pendingAttempt = null\n                    isFinishingForSend = false"))
        val abandon = gate.substringAfter("fun abandonFinishForSend(reason: String) {").substringBefore("\n    }\n")
        assertTrue(abandon.contains("pendingAttempt = null") && abandon.contains("isFinishingForSend = false"))
    }

    @Test
    fun `every user change abandons the pending send`() {
        for (reason in listOf("transcription cancelled", "mic tapped to record again", "voice panel closed",
            "text edited", "editing", "backspace", "AI correction")) {
            assertTrue(reason, panel.contains("VoiceSendGate.abandonFinishForSend(\"$reason\")"))
        }
        val tap = panel.substringAfter("fun handleMicTap() {").substringBefore("isRecording -> stopCapture()")
        assertTrue("abandoned before the cancel makes recognition idle",
            tap.indexOf("abandonFinishForSend") < tap.indexOf("SpeechRecognitionManager.cancelRecording()"))
        val commit = panel.substringAfter("val joined = captureBase + sep + text").substringBefore("panel.commit")
        assertTrue("a recognition result is not a user edit", !commit.contains("abandonFinishForSend"))
    }

    @Test
    fun `the send button shows the wait and ignores taps`() {
        val btn = ProductionSources.read("ui/chat/SendReturnModeMenu.kt")
        assertTrue(btn.contains("if (busy) return@combinedClickable"))
        assertTrue(btn.contains("DecorativeSpinner("))
        val screen = ProductionSources.read("ui/chat/ChatScreen.kt")
        assertTrue(screen.contains("busy = com.openminis.app.ui.chat.voice.VoiceModePrefs.isVoiceActive &&"))
    }
}
