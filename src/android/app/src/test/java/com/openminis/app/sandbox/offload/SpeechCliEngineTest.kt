package com.openminis.app.sandbox.offload

import android.speech.SpeechRecognizer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-431-engine] GH #431 feature request: transcribe with the configured
 * speech-to-text model (mic and files). The rules here decide where audio
 * goes, so they are pinned down directly.
 */
class SpeechCliEngineTest {

    @Test
    fun `system is the default engine`() {
        assertEquals(SpeechEngines.Engine.SYSTEM, SpeechEngines.parse(null))
        assertEquals(SpeechEngines.Engine.PROVIDER, SpeechEngines.parse("Provider"))
        assertEquals(SpeechEngines.Engine.AUTO, SpeechEngines.parse(" auto "))
        assertNull(SpeechEngines.parse("cloud"))
    }

    @Test
    fun `auto starts with the provider only when the system is known not to work`() {
        assertTrue(SpeechEngines.autoStartsWithProvider(servicesListed = false, systemUsable = false, haveModel = true))
        assertTrue(SpeechEngines.autoStartsWithProvider(servicesListed = true, systemUsable = false, haveModel = true))
        assertFalse(SpeechEngines.autoStartsWithProvider(servicesListed = true, systemUsable = true, haveModel = true))
        assertFalse(SpeechEngines.autoStartsWithProvider(servicesListed = false, systemUsable = false, haveModel = false))
    }

    @Test
    fun `auto falls back only when no recognizer could be reached`() {
        assertTrue(SpeechEngines.autoFallsBack(RecognizeOutcome.Failure(SpeechRecognizer.ERROR_CLIENT, "regular")))
        assertTrue(SpeechEngines.autoFallsBack(RecognizeOutcome.Failure(13, "on_device")))
        assertTrue(SpeechEngines.autoFallsBack(RecognizeOutcome.CreationFailed("x", "regular")))
        assertFalse(SpeechEngines.autoFallsBack(RecognizeOutcome.Failure(SpeechRecognizer.ERROR_NETWORK, "regular")))
        assertFalse(SpeechEngines.autoFallsBack(RecognizeOutcome.Timeout(3, "regular")))
        assertFalse(SpeechEngines.autoFallsBack(RecognizeOutcome.Success(listOf("x"), null, "regular")))
    }

    @Test
    fun `the notice names provider, model and host`() {
        val m = SpeechEngines.VoiceModel("OpenAI", "whisper-1", "api.openai.com")
        assertEquals(
            "Audio was sent to provider \"OpenAI\" (model whisper-1, api.openai.com).",
            SpeechEngines.sentNotice(m),
        )
    }

    @Test
    fun `status without a model says auto uses the system`() {
        val s = SpeechEngines.status(emptyList(), servicesListed = true, systemUsable = true)
        assertEquals("system", s.getString("auto_would_use"))
        assertFalse(s.getJSONObject("voice_input_model").getBoolean("configured"))
    }

    @Test
    fun `formats are sniffed from the first bytes`() {
        fun b(s: String) = s.toByteArray(Charsets.ISO_8859_1)
        assertEquals(SpeechAudio.Format.WAV, SpeechAudio.sniff(b("RIFF\u0000\u0000\u0000\u0000WAVEfmt ")))
        assertEquals(SpeechAudio.Format.MP3, SpeechAudio.sniff(b("ID3\u0004")))
        assertEquals(SpeechAudio.Format.MP3, SpeechAudio.sniff(byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0, 0)))
        assertEquals(SpeechAudio.Format.AAC, SpeechAudio.sniff(byteArrayOf(0xFF.toByte(), 0xF1.toByte(), 0, 0)))
        assertEquals(SpeechAudio.Format.MP4, SpeechAudio.sniff(b("\u0000\u0000\u0000 ftypM4A ")))
        assertEquals(SpeechAudio.Format.FLAC, SpeechAudio.sniff(b("fLaC")))
        assertEquals(SpeechAudio.Format.OGG, SpeechAudio.sniff(b("OggS")))
        assertEquals(SpeechAudio.Format.AMR, SpeechAudio.sniff(b("#!AMR\n")))
        assertEquals(SpeechAudio.Format.UNKNOWN, SpeechAudio.sniff(b("hello")))
    }

    @Test
    fun `a pass-through WAV gets its length from the header`() {
        val pcm = ByteArray(32_000) // 1 s of 16 kHz mono PCM16
        val wav = com.openminis.app.provider.voice.VoiceProvider.wrapPcm16InWav(pcm, 16_000)
        assertEquals(1.0, SpeechAudio.wavSeconds(wav.copyOf(44), wav.size.toLong())!!, 0.001)
        assertNull(SpeechAudio.wavSeconds("ID3".toByteArray(), 100))
    }

    @Test
    fun `only a WAV within the upload limit passes through`() {
        assertTrue(SpeechAudio.passThrough(SpeechAudio.Format.WAV, 1_000))
        assertFalse(SpeechAudio.passThrough(SpeechAudio.Format.WAV, SpeechAudio.MAX_UPLOAD_BYTES + 1))
        assertFalse(SpeechAudio.passThrough(SpeechAudio.Format.MP3, 1_000))
    }

    @Test
    fun `stereo is averaged to mono`() {
        val mono = SpeechAudio.downmix(shortArrayOf(100, 300, -200, 200), 2)
        assertArrayEquals(shortArrayOf(200, 0), mono)
    }

    @Test
    fun `resampling keeps duration`() {
        val oneSecond48k = ShortArray(48_000) { (it % 100).toShort() }
        assertEquals(16_000, SpeechAudio.resample(oneSecond48k, 48_000, 16_000).size)
        assertEquals(16_000, SpeechAudio.resample(ShortArray(44_100), 44_100, 16_000).size)
    }

    @Test
    fun `pcm bytes are little endian`() {
        assertArrayEquals(byteArrayOf(0x34, 0x12), SpeechAudio.toLittleEndianBytes(shortArrayOf(0x1234)))
    }

    @Test
    fun `provider errors never print the key`() {
        val key = "sk-test-0123456789abcdef"
        val masked = SpeechAudioRedact.redact("401: Incorrect API key provided: $key", key)
        assertFalse(masked.contains(key))
        val partial = SpeechAudioRedact.redact("Incorrect API key provided: sk-...cdef", null)
        assertFalse(partial.contains("sk-...cdef"))
    }

    // [T-speech-decode-streaming] Fed one decoder buffer at a time, the
    // streaming resampler gives exactly what downmix + resample gave the
    // whole file.
    @org.junit.Test
    fun `streaming downmix and resample match the batch result`() {
        val rnd = java.util.Random(7)
        for ((channels, from) in listOf(1 to 16_000, 1 to 44_100, 2 to 48_000, 2 to 22_050, 6 to 48_000, 1 to 8_000)) {
            val frames = 12_345
            val interleaved = ShortArray(frames * channels) { (rnd.nextInt(65_536) - 32_768).toShort() }
            val batch = SpeechAudio.resample(SpeechAudio.downmix(interleaved, channels), from, SpeechAudio.SAMPLE_RATE)
            val r = SpeechAudio.StreamingResampler(channels, from, SpeechAudio.SAMPLE_RATE)
            var at = 0
            while (at < interleaved.size) {
                val n = minOf((rnd.nextInt(700) + 1) * channels, interleaved.size - at)
                r.push(interleaved.copyOfRange(at, at + n), n)
                at += n
            }
            org.junit.Assert.assertArrayEquals("channels=$channels from=$from", batch, r.finish())
        }
    }

    @org.junit.Test
    fun `the streaming result is capped and an empty stream is empty`() {
        val r = SpeechAudio.StreamingResampler(1, 16_000, 16_000, maxOut = 10)
        r.push(ShortArray(100) { it.toShort() })
        org.junit.Assert.assertEquals(10, r.finish().size)
        org.junit.Assert.assertEquals(0, SpeechAudio.StreamingResampler(2, 48_000, 16_000).finish().size)
    }
}
