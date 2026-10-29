package com.openminis.app.speech

import android.media.audiofx.LoudnessEnhancer
import com.openminis.app.logging.AppLogger
import kotlin.math.log10
import kotlin.math.roundToInt

/**
 * [T-readaloud-volume-boost] Read-aloud volume above 100 %.
 *
 * Both playback paths cap their own gain at 1.0 (MediaPlayer.setVolume,
 * TextToSpeech KEY_PARAM_VOLUME), so a slider value v in 0..2 is split in two:
 * a linear gain min(v, 1) for the player, and for v > 1 the rest as a
 * [LoudnessEnhancer] on the playback's audio session. LoudnessEnhancer is a
 * gain stage followed by a limiter, so the boost raises quiet speech without
 * hard clipping the peaks — a plain sample multiply would.
 *
 * One instance per audio session. Failures (an OEM build without the effect,
 * a session already gone) are logged and leave playback at 100 %: the boost
 * is a nice-to-have, never a reason to lose the audio.
 */
internal class ReadAloudBoost(private val sessionId: Int) {
    private var enhancer: LoudnessEnhancer? = null
    private var failed = false
    private var lastGainMb = 0

    /** Apply the boost part of [volume]; a no-op (effect disabled) at ≤ 100 %. */
    fun apply(volume: Float) {
        val gainMb = boostMillibels(volume)
        if (gainMb == lastGainMb && (gainMb == 0 || enhancer != null)) return
        lastGainMb = gainMb
        if (gainMb <= 0) {
            enhancer?.runCatching { enabled = false }
            return
        }
        if (failed || sessionId == 0) return
        val fx = enhancer ?: runCatching { LoudnessEnhancer(sessionId) }
            .onFailure {
                failed = true
                AppLogger.error(TAG, "LoudnessEnhancer unavailable on session $sessionId, read-aloud stays at 100%: $it")
            }
            .getOrNull()
            ?.also { enhancer = it }
            ?: return
        fx.runCatching {
            setTargetGain(gainMb)
            enabled = true
        }.onFailure { AppLogger.error(TAG, "LoudnessEnhancer gain $gainMb mB rejected: $it") }
    }

    fun release() {
        enhancer?.runCatching { release() }
        enhancer = null
        lastGainMb = 0
    }

    companion object {
        private const val TAG = "ReadAloud"

        /** The part of [volume] the player itself can apply: 0..1. */
        fun linearGain(volume: Float): Float = volume.coerceIn(0f, 1f)

        /** The part above 100 % as a gain in millibels (200 % ≈ 602 mB); 0 at ≤ 100 %. */
        fun boostMillibels(volume: Float): Int =
            if (volume <= 1f || volume.isNaN()) 0 else (2000.0 * log10(volume.toDouble())).roundToInt()
    }
}
