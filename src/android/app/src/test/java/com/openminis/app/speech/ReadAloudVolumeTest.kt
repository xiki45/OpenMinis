package com.openminis.app.speech

import android.app.Application
import android.content.Context
import com.openminis.app.ProductionSources
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * [T-readaloud-volume] One read-aloud volume (0..2, default 1.0, persisted in
 * voice_prefs/readReplies.volume) drives BOTH playback paths: the system
 * engine's KEY_PARAM_VOLUME and the provider MediaPlayer's setVolume, plus
 * [T-readaloud-volume-boost] a LoudnessEnhancer on each path's audio session
 * for the part above 100 %.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ReadAloudVolumeTest {

    private val app: Application get() = RuntimeEnvironment.getApplication()

    private fun prefs() = app.getSharedPreferences("voice_prefs", Context.MODE_PRIVATE)

    /** VoiceOutputState is a process singleton whose init() runs once; reset it. */
    private fun reinit() {
        VoiceOutputState::class.java.getDeclaredField("prefs").apply {
            isAccessible = true
            set(VoiceOutputState, null)
        }
        VoiceOutputState.init(app)
    }

    @Before
    fun setUp() {
        prefs().edit().clear().commit()
        reinit()
    }

    @After
    fun tearDown() {
        VoiceOutputState.setVolume(VoiceOutputState.DEFAULT_VOLUME)
    }

    @Test
    fun `default is 100 percent - unchanged behaviour after upgrade`() {
        assertEquals(1.0f, VoiceOutputState.volume.value)
        assertFalse(prefs().contains("readReplies.volume"))
    }

    @Test
    fun `values outside 0 to 2 are clamped`() {
        VoiceOutputState.setVolume(-0.5f)
        assertEquals(0f, VoiceOutputState.volume.value)
        VoiceOutputState.setVolume(1.7f)
        assertEquals(1.7f, VoiceOutputState.volume.value, 1e-6f)
        VoiceOutputState.setVolume(2.5f)
        assertEquals(2f, VoiceOutputState.volume.value)
        assertEquals(1f, VoiceOutputState.clampVolume(Float.NaN))
    }

    @Test
    fun `a corrupt stored value is clamped on load`() {
        prefs().edit().putFloat("readReplies.volume", 4f).commit()
        reinit()
        assertEquals(2f, VoiceOutputState.volume.value)
    }

    @Test
    fun `a value stored by an older 0 to 1 build loads unchanged`() {
        prefs().edit().putFloat("readReplies.volume", 0.6f).commit()
        reinit()
        assertEquals(0.6f, VoiceOutputState.volume.value, 1e-6f)
    }

    @Test
    fun `volume splits into a player gain up to 1 and a boost above it`() {
        assertEquals(0.4f, ReadAloudBoost.linearGain(0.4f), 1e-6f)
        assertEquals(1f, ReadAloudBoost.linearGain(1.8f))
        assertEquals(0, ReadAloudBoost.boostMillibels(0.4f))
        assertEquals(0, ReadAloudBoost.boostMillibels(1f))
        assertEquals(602, ReadAloudBoost.boostMillibels(2f))
        assertEquals(0, ReadAloudBoost.boostMillibels(Float.NaN))
    }

    @Test
    fun `volume survives a restart`() {
        VoiceOutputState.setVolume(0.3f)
        reinit()
        assertEquals(0.3f, VoiceOutputState.volume.value, 1e-6f)
    }

    @Test
    fun `dragging updates live but only the drag end persists`() {
        VoiceOutputState.setVolume(0.4f, persist = false)
        assertEquals(0.4f, VoiceOutputState.volume.value, 1e-6f)
        assertFalse(prefs().contains("readReplies.volume"))
        VoiceOutputState.persistVolume()
        assertEquals(0.4f, prefs().getFloat("readReplies.volume", -1f), 1e-6f)
    }

    @Test
    fun `both playback paths read the same read-aloud volume`() {
        val src = ProductionSources.read("speech/ReadAloudPlayer.kt")
        val system = src.substring(src.indexOf("private suspend fun speakViaSystem"))
            .substringBefore("system.speak(text)")
        assertTrue("system engine gets the volume before speak()",
            system.contains("val volume = VoiceOutputState.volume.value") &&
                system.contains("system.speechVolume = ReadAloudBoost.linearGain(volume)") &&
                system.contains("system.audioSessionId = systemSessionId") &&
                system.contains("systemBoost.apply(volume)"))
        val provider = src.substring(src.indexOf("private suspend fun playBytes"))
            .substringBefore("mp.start()")
        assertTrue("provider player gets the volume before start()",
            provider.contains("VoiceOutputState.volume.value") && provider.contains("mp.setVolume(gain, gain)") &&
                provider.contains("ReadAloudBoost(mp.audioSessionId).also { it.apply(volume) }"))
        assertTrue("both paths follow the slider live",
            src.contains("VoiceOutputState.volume.collect") && src.contains("player?.runCatching { setVolume(gain, gain) }") &&
                src.contains("playerBoost?.apply(v)") && src.contains("systemBoost.apply(v)"))
    }

    @Test
    fun `system engine forwards speechVolume as KEY_PARAM_VOLUME`() {
        val src = ProductionSources.read("speech/TextToSpeechManager.kt")
        assertTrue(src.contains("putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, speechVolume)"))
        assertTrue(src.contains("putInt(TextToSpeech.Engine.KEY_PARAM_SESSION_ID, audioSessionId)"))
        // Every speak path builds its params through buildSpeechParams.
        assertEquals(
            Regex("""tts\?\.speak\(""").findAll(src).count(),
            Regex("""val params = buildSpeechParams\(\)""").findAll(src).count(),
        )
    }

    @Test
    fun `slider opens above the capsule from its speaker, not as a standing row`() {
        val chat = ProductionSources.read("ui/chat/ChatScreen.kt")
        assertFalse("no standing volume row in the composer", chat.contains("ReadAloudVolumeRow("))
        val ui = ProductionSources.read("ui/chat/voice/SpeechPlayerCapsule.kt")
        assertTrue("speaker toggles the bar", ui.contains("onSpeakerTap = { showVolume = !showVolume; bumpIdle() }"))
        val bar = ui.indexOf("VolumeBar(\n")
        val capsule = ui.indexOf("ExpandedCapsule(\n", bar)
        assertTrue("bar is laid out above the capsule", bar in 0 until capsule)
        assertTrue("bar takes the capsule's measured width",
            ui.contains("width = with(density) { capsuleWidthPx.toDp() }") &&
                ui.contains("Modifier.onSizeChanged { capsuleWidthPx = it.width }"))
        assertTrue("range reaches 200 %", ui.contains("valueRange = 0f..VoiceOutputState.MAX_VOLUME"))
        assertTrue("accessible label", ui.contains("contentDescription = label"))
        assertTrue("persists on drag end", ui.contains("onVolumeChangeFinished = { VoiceOutputState.persistVolume() }"))
    }
}
