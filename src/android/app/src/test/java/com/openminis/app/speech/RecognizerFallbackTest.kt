package com.openminis.app.speech

import android.speech.SpeechRecognizer
import com.openminis.app.ProductionSources
import com.openminis.app.speech.RecognizerFallback.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-431-fallback] GH #431: the CLI took the on-device recognizer on Android
 * 12+ and never fell back, so a missing language pack (a zh-CN Pixel 4a: error
 * 12) or an unreachable service (vivo: ERROR_CLIENT) was a dead end.
 */
class RecognizerFallbackTest {

    @Test
    fun `connection and language errors switch, others do not`() {
        assertTrue(RecognizerFallback.shouldSwitch(SpeechRecognizer.ERROR_CLIENT))
        assertTrue(RecognizerFallback.shouldSwitch(RecognizerErrorCodes.SERVER_DISCONNECTED))
        assertTrue(RecognizerFallback.shouldSwitch(RecognizerErrorCodes.LANGUAGE_NOT_SUPPORTED))
        assertTrue(RecognizerFallback.shouldSwitch(RecognizerErrorCodes.LANGUAGE_UNAVAILABLE))
        assertFalse(RecognizerFallback.shouldSwitch(SpeechRecognizer.ERROR_NETWORK))
        assertFalse(RecognizerFallback.shouldSwitch(SpeechRecognizer.ERROR_NO_MATCH))
        assertFalse(RecognizerFallback.shouldSwitch(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS))
        // Quota exhaustion is a different problem: switching would just spend
        // the other recognizer's quota too.
        assertFalse(RecognizerFallback.shouldSwitch(RecognizerErrorCodes.TOO_MANY_REQUESTS))
    }

    @Test
    fun `regular first by default, on-device first on request`() {
        assertEquals(listOf(Kind.REGULAR, Kind.ON_DEVICE), RecognizerFallback.plan(false, 33, true))
        assertEquals(listOf(Kind.ON_DEVICE, Kind.REGULAR), RecognizerFallback.plan(true, 33, true))
    }

    @Test
    fun `no on-device recognizer means one attempt`() {
        assertEquals(listOf(Kind.REGULAR), RecognizerFallback.plan(true, 33, false))
        assertEquals(listOf(Kind.REGULAR), RecognizerFallback.plan(true, 30, null))
        assertEquals(listOf(Kind.REGULAR), RecognizerFallback.plan(false, 31, false))
    }

    @Test
    fun `next attempt only after a switching error and only once`() {
        val plan = RecognizerFallback.plan(false, 33, true)
        assertEquals(Kind.ON_DEVICE, RecognizerFallback.next(plan, 0, SpeechRecognizer.ERROR_CLIENT))
        assertNull(RecognizerFallback.next(plan, 1, SpeechRecognizer.ERROR_CLIENT))
        assertNull(RecognizerFallback.next(plan, 0, SpeechRecognizer.ERROR_NETWORK))
    }

    @Test
    fun `the chat voice input uses the same rule`() {
        val src = ProductionSources.read("speech/SystemSpeechRecognitionEngine.kt")
        assertTrue(src.contains("RecognizerFallback.shouldSwitch(errorCode) && mayFallBack && usingOnDevice"))
    }

    @Test
    fun `the chat voice input never falls back once the take has something to lose`() {
        // [T-voice-fallback-after-stop] ERROR_CLIENT after the user's stop wiped
        // the transcript and restarted a recognizer that opened the mic again.
        val src = ProductionSources.read("speech/SystemSpeechRecognitionEngine.kt")
        assertTrue(src.contains("val beforeAnything = !sessionCommitted && !stopRequested && !heardSpeech &&\n                segmentTranscript.isEmpty() && bufferedPartial.isNullOrEmpty()"))
        assertTrue(src.contains("val mayFallBack = RecognizerErrorCodes.isLanguageError(errorCode) || replayPcm != null || beforeAnything"))
        val stop = src.substringAfter("override fun stop() {").substringBefore("stopVad()")
        assertTrue("stop marks the take before it finalises", stop.contains("stopRequested = true"))
        assertEquals("declared, then reset for a new take and for a replay", 3, src.split("stopRequested = false").size - 1)
    }
}
