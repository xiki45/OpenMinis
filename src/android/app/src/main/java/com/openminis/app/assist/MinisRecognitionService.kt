package com.openminis.app.assist

import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.SpeechRecognizer

/**
 * Minimal [RecognitionService] that satisfies the framework's requirement for a
 * default assistant to declare a `recognitionService` in its
 * `voice_interaction_service.xml`. Without it the framework parses
 * `VoiceInteractionServiceInfo` with `parseError = "No recognitionService
 * specified"`, which fails the ASSISTANT role qualification check.
 *
 * Recognition is delegated to the system [SpeechRecognizer] — we do not build
 * our own ASR. No extra permission is required; RECORD_AUDIO is already in the
 * manifest.
 */
class MinisRecognitionService : RecognitionService() {

    // The recognizer is (re)created per start to survive device reconfiguration.
    private var recognizer: SpeechRecognizer? = null

    override fun onStartListening(intent: Intent?, listener: Callback) {
        // Tear down any previous recognizer before creating a fresh one.
        destroyRecognizer()

        val sr = SpeechRecognizer.createSpeechRecognizer(this)
        recognizer = sr

        // Forward the system recognizer's callbacks onto the framework Callback.
        sr.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                listener.readyForSpeech(params)
            }

            override fun onBeginningOfSpeech() {
                listener.beginningOfSpeech()
            }

            override fun onRmsChanged(rmsdB: Float) {
                listener.rmsChanged(rmsdB)
            }

            override fun onBufferReceived(buffer: ByteArray?) {
                listener.bufferReceived(buffer)
            }

            override fun onEndOfSpeech() {
                listener.endOfSpeech()
            }

            override fun onError(error: Int) {
                listener.error(error)
            }

            override fun onResults(results: Bundle?) {
                listener.results(results)
            }

            override fun onPartialResults(partialResults: Bundle?) {
                listener.partialResults(partialResults)
            }

            // RecognitionService.Callback exposes no onEvent equivalent.
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })

        // Forward the caller's recognizer intent as-is.
        sr.startListening(intent ?: Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH))
    }

    override fun onStopListening(listener: Callback) {
        recognizer?.stopListening()
    }

    override fun onCancel(listener: Callback) {
        recognizer?.cancel()
    }

    override fun onDestroy() {
        super.onDestroy()
        destroyRecognizer()
    }

    private fun destroyRecognizer() {
        recognizer?.destroy()
        recognizer = null
    }
}
