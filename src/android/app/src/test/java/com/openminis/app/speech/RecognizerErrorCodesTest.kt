package com.openminis.app.speech

import android.speech.SpeechRecognizer
import com.openminis.app.ProductionSources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * [T-431-error-codes] GH #431 side finding: the chat voice input labelled the
 * API 31/33 error literals one slot off (11 as LANGUAGE_NOT_SUPPORTED, 12 as
 * LANGUAGE_UNAVAILABLE), so 13 fell through to UNKNOWN and a dropped service
 * (11) read as a language problem. Values are from the SDK's
 * android/speech/SpeechRecognizer.java (android-34).
 */
class RecognizerErrorCodesTest {

    @Test
    fun `literal codes carry their SDK names`() {
        assertEquals("ERROR_TOO_MANY_REQUESTS", RecognizerErrorCodes.name(10))
        assertEquals("ERROR_SERVER_DISCONNECTED", RecognizerErrorCodes.name(11))
        assertEquals("ERROR_LANGUAGE_NOT_SUPPORTED", RecognizerErrorCodes.name(12))
        assertEquals("ERROR_LANGUAGE_UNAVAILABLE", RecognizerErrorCodes.name(13))
        assertEquals("ERROR_CANNOT_CHECK_SUPPORT", RecognizerErrorCodes.name(14))
        assertEquals("ERROR_CLIENT", RecognizerErrorCodes.name(SpeechRecognizer.ERROR_CLIENT))
    }

    @Test
    fun `both language codes are language errors, a disconnect is not`() {
        assertEquals(RecognitionError.LANGUAGE_UNSUPPORTED, RecognizerErrorCodes.classify(12))
        assertEquals(RecognitionError.LANGUAGE_UNSUPPORTED, RecognizerErrorCodes.classify(13))
        assertEquals(RecognitionError.UNKNOWN, RecognizerErrorCodes.classify(11))
        assertTrue(RecognizerErrorCodes.isLanguageError(12))
        assertTrue(RecognizerErrorCodes.isLanguageError(13))
        assertFalse(RecognizerErrorCodes.isLanguageError(11))
    }

    @Test
    fun `the classic codes keep their categories`() {
        assertEquals(RecognitionError.OEM_NO_SERVICE, RecognizerErrorCodes.classify(SpeechRecognizer.ERROR_CLIENT))
        assertEquals(RecognitionError.NO_MATCH, RecognizerErrorCodes.classify(SpeechRecognizer.ERROR_NO_MATCH))
        assertEquals(RecognitionError.NO_MATCH, RecognizerErrorCodes.classify(SpeechRecognizer.ERROR_SPEECH_TIMEOUT))
        assertEquals(RecognitionError.NETWORK, RecognizerErrorCodes.classify(SpeechRecognizer.ERROR_SERVER))
        assertEquals(RecognitionError.AUDIO_ERROR, RecognizerErrorCodes.classify(SpeechRecognizer.ERROR_AUDIO))
        assertEquals(
            RecognitionError.PERMISSION_DENIED,
            RecognizerErrorCodes.classify(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS),
        )
    }

    @Test
    fun `messages name the actual cause`() {
        assertTrue(RecognizerErrorCodes.describe(11).contains("disconnected"))
        assertTrue(RecognizerErrorCodes.describe(12).contains("not supported"))
        assertTrue(RecognizerErrorCodes.describe(13).contains("unavailable"))
        assertTrue(RecognizerErrorCodes.describe(SpeechRecognizer.ERROR_CLIENT).contains("could not connect"))
    }

    @Test
    fun `the chat engine maps through the shared table`() {
        val src = ProductionSources.read("speech/SystemSpeechRecognitionEngine.kt")
        assertTrue(src.contains("RecognizerErrorCodes.classify(code)"))
        assertTrue(src.contains("RecognizerErrorCodes.describe(code)"))
        // The one-slot-off literals are gone.
        assertFalse(src.contains("11 /* ERROR_LANGUAGE_NOT_SUPPORTED */"))
        assertFalse(src.contains("12 /* ERROR_LANGUAGE_UNAVAILABLE"))
    }

    @Test
    fun `recognizer tags drop the script subtag`() {
        assertEquals("zh-CN", RecognizerLocales.recognizerTag(Locale.forLanguageTag("zh-Hans-CN")))
        assertEquals("zh-TW", RecognizerLocales.recognizerTag(Locale.forLanguageTag("zh-Hant")))
        assertEquals("zh-CN", RecognizerLocales.recognizerTag("zh_Hans_CN"))
        assertEquals("en-US", RecognizerLocales.recognizerTag("en-US"))
    }

    @Test
    fun `chat and CLI share one built-in list`() {
        assertTrue(RecognizerLocales.BUILTIN_TAGS.containsAll(listOf("en-US", "zh-CN", "ja-JP")))
        val src = ProductionSources.read("speech/SystemSpeechRecognitionEngine.kt")
        assertTrue(src.contains("RecognizerLocales.BUILTIN_TAGS"))
    }
}
