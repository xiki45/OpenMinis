package com.openminis.app.speech

import android.speech.SpeechRecognizer

/**
 * [T-431-fallback] When one kind of system recognizer fails in a way the other
 * kind may not, try the other kind once. Shared by the chat voice input
 * ([SystemSpeechRecognitionEngine]) and the `android-speech` CLI.
 *
 * - The on-device recognizer (SODA on Pixels) answers 12/13 when it has no
 *   language pack for the locale; the regular, usually cloud, recognizer
 *   often handles the same locale (a zh-CN Pixel 4a).
 * - ERROR_CLIENT / ERROR_SERVER_DISCONNECTED mean Android could not keep a
 *   connection to that recognizer's service. The other kind is bound to a
 *   different service, so it is worth one try (GH #431: a vivo ROM whose
 *   default service refuses third-party apps).
 *
 * The chat voice input only ever falls back FROM on-device: it honours the
 * user's online/offline choice and starts on-device only when asked to. The
 * CLI has no stored choice, so it falls back in both directions.
 */
object RecognizerFallback {
    enum class Kind(val label: String) {
        REGULAR("regular"),
        ON_DEVICE("on_device"),
    }

    /** Errors after which the other kind of recognizer is worth one try. */
    fun shouldSwitch(code: Int): Boolean =
        code == SpeechRecognizer.ERROR_CLIENT ||
            code == RecognizerErrorCodes.SERVER_DISCONNECTED ||
            RecognizerErrorCodes.isLanguageError(code)

    /**
     * The recognizers to try, in order: the preferred one, then the other one
     * when it exists. On-device needs API 31 and a configured service.
     */
    fun plan(preferOnDevice: Boolean, sdkInt: Int, onDeviceAvailable: Boolean?): List<Kind> {
        val onDeviceUsable = sdkInt >= 31 && onDeviceAvailable == true
        return when {
            preferOnDevice && onDeviceUsable -> listOf(Kind.ON_DEVICE, Kind.REGULAR)
            onDeviceUsable -> listOf(Kind.REGULAR, Kind.ON_DEVICE)
            else -> listOf(Kind.REGULAR)
        }
    }

    /** The next recognizer to try after [code] from attempt [index] of [plan], or null. */
    fun next(plan: List<Kind>, index: Int, code: Int): Kind? =
        if (shouldSwitch(code)) plan.getOrNull(index + 1) else null
}
