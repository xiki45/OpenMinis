package com.openminis.app.sandbox.offload

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaRecorder
import com.openminis.app.MinisApp
import com.openminis.app.data.model.ModelEntry
import com.openminis.app.data.model.ProviderInstance
import com.openminis.app.logging.AppLogger
import com.openminis.app.provider.voice.VoiceInputRequest
import com.openminis.app.provider.voice.VoiceProvider
import com.openminis.app.provider.voice.VoiceProviderFactory
import com.openminis.app.speech.MicInUse
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteOrder
import java.util.Locale

/**
 * [T-431-engine] The provider engine's system side: the Voice Input group's
 * models, a fixed-length microphone capture, audio-file decoding and the
 * upload - through the same [VoiceProviderFactory] / [VoiceProvider] the chat
 * voice input ([com.openminis.app.speech.ProviderSpeechRecognitionEngine])
 * uses. API keys stay inside the repository and the provider; nothing here
 * returns or logs them.
 */
internal class SpeechProviderBridge(private val context: Context) {

    private fun repository() =
        (context.applicationContext as? MinisApp)?.takeIf { it.subsystemsReady() }?.providerRepository

    private fun candidates(): List<Pair<ProviderInstance, ModelEntry>> =
        repository()?.resolveVoiceInputCandidates(
            loadBalanceSeed = kotlin.random.Random.nextInt(Int.MAX_VALUE),
        ).orEmpty()

    private fun describe(instance: ProviderInstance, entry: ModelEntry): SpeechEngines.VoiceModel {
        val base = runCatching { VoiceProviderFactory.make(instance, null)?.baseURL }.getOrNull()
            ?: instance.customBaseURL
        val host = base?.let { runCatching { java.net.URI(it).host }.getOrNull() ?: it } ?: "unknown host"
        return SpeechEngines.VoiceModel(instance.label, entry.baseModel.id, host)
    }

    fun models(): List<SpeechEngines.VoiceModel> = candidates().map { (i, e) -> describe(i, e) }

    /** Record [seconds] of 16 kHz mono PCM16 from the microphone, as WAV. */
    @SuppressLint("MissingPermission") // the handler's permission gate runs first
    fun record(seconds: Int, isCancelled: () -> Boolean = { false }): CaptureOutcome {
        val rate = SpeechAudio.SAMPLE_RATE
        val minBuf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) return CaptureOutcome.Failed("audio_error", "AudioRecord reports no usable buffer size.")
        val recorder = try {
            AudioRecord(MediaRecorder.AudioSource.MIC, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuf * 4)
        } catch (e: Exception) {
            return CaptureOutcome.Failed(micCode(), "The microphone could not be opened: ${e.message}")
        }
        try {
            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                return CaptureOutcome.Failed(micCode(), "The microphone could not be opened (AudioRecord not initialized).")
            }
            val pcm = ByteArrayOutputStream()
            val buf = ByteArray(minBuf)
            val wantBytes = rate * 2 * seconds.coerceIn(1, SpeechAudio.MAX_MIC_SECONDS)
            val deadline = System.currentTimeMillis() + seconds * 1000L + 2_000L
            recorder.startRecording()
            var nextSilenceCheck = System.currentTimeMillis() + 400L
            var nextCancelCheck = System.currentTimeMillis() + 250L
            while (pcm.size() < wantBytes && System.currentTimeMillis() < deadline) {
                val now = System.currentTimeMillis()
                if (now >= nextCancelCheck) {
                    nextCancelCheck = now + 250L
                    // [T-431-cancel] The caller is gone: stop recording; the
                    // handler then skips the upload.
                    if (isCancelled()) return CaptureOutcome.Cancelled
                }
                if (now >= nextSilenceCheck) {
                    nextSilenceCheck = now + 1_000L
                    // [T-voice-mic-preempted] A call or another recorder owns the mic.
                    if (MicInUse.isSilenced(context, recorder.audioSessionId)) {
                        return CaptureOutcome.Failed(
                            "mic_in_use",
                            "Another app or a call is using the microphone; the recording was silenced.",
                        )
                    }
                }
                val n = recorder.read(buf, 0, minOf(buf.size, wantBytes - pcm.size()))
                if (n > 0) pcm.write(buf, 0, n)
            }
            val bytes = pcm.toByteArray()
            return CaptureOutcome.Ok(VoiceProvider.wrapPcm16InWav(bytes, rate), bytes.size / (rate * 2.0))
        } catch (e: Exception) {
            return CaptureOutcome.Failed(micCode(), "Recording failed: ${e.message}")
        } finally {
            runCatching { recorder.stop() }
            recorder.release()
        }
    }

    private fun micCode(): String =
        if (MicInUse.captureFailure(context) == com.openminis.app.speech.RecognitionError.MIC_IN_USE) "mic_in_use" else "audio_error"

    /** A WAV within the upload limit as-is; anything else decoded to 16 kHz mono WAV. */
    fun decode(file: File, isCancelled: () -> Boolean = { false }): DecodeOutcome {
        val size = file.length()
        if (size > SpeechAudio.MAX_FILE_BYTES) {
            return DecodeOutcome.Failed(
                "file_too_large",
                "The file is ${size / (1024 * 1024)} MB; the limit is ${SpeechAudio.MAX_FILE_BYTES / (1024 * 1024)} MB.",
            )
        }
        val head = ByteArray(44)
        val read = runCatching { file.inputStream().use { it.read(head) } }.getOrElse {
            return DecodeOutcome.Failed("file_unreadable", "The file could not be read: ${it.message}")
        }
        val format = SpeechAudio.sniff(head.copyOf(maxOf(read, 0)))
        if (SpeechAudio.passThrough(format, size)) {
            val h = head.copyOf(maxOf(read, 0))
            return DecodeOutcome.Ok(file.readBytes(), SpeechAudio.wavSeconds(h, size), format, decoded = false)
        }
        return decodeWithMediaCodec(file, format, isCancelled)
    }

    private fun decodeWithMediaCodec(file: File, format: SpeechAudio.Format, isCancelled: () -> Boolean): DecodeOutcome {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(file.absolutePath)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return DecodeOutcome.Failed(
                "unsupported_format",
                "No audio track Android can read was found (detected: ${format.label}).",
            )
            extractor.selectTrack(track)
            val inFormat = extractor.getTrackFormat(track)
            val mime = inFormat.getString(MediaFormat.KEY_MIME)!!
            var rate = inFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = inFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(inFormat, null, null, 0)
            codec.start()

            // [T-speech-decode-streaming] Each output buffer goes straight to
            // 16 kHz mono; only that result (at most 10 min = 9.6M samples,
            // 19.2 MB) is kept. A reused scratch array holds one buffer.
            var resampler: SpeechAudio.StreamingResampler? = null
            var scratch = ShortArray(0)
            var totalFrames = 0L
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            val maxOut = SpeechAudio.SAMPLE_RATE * SpeechAudio.MAX_FILE_SECONDS
            while (!outputDone) {
                if (isCancelled()) return DecodeOutcome.Failed("cancelled", "The caller stopped before the audio was decoded.")
                if (!inputDone) {
                    val inIdx = codec.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val buf = codec.getInputBuffer(inIdx)!!
                        val n = extractor.readSampleData(buf, 0)
                        if (n < 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIdx, 0, n, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIdx = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val f = codec.outputFormat
                        rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    }
                    outIdx >= 0 -> {
                        val out = codec.getOutputBuffer(outIdx)!!
                        out.position(info.offset)
                        out.limit(info.offset + info.size)
                        val count = info.size / 2
                        if (scratch.size < count) scratch = ShortArray(count)
                        out.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(scratch, 0, count)
                        val r = resampler ?: SpeechAudio.StreamingResampler(channels, rate, SpeechAudio.SAMPLE_RATE, maxOut)
                            .also { resampler = it }
                        r.push(scratch, count)
                        totalFrames += count / maxOf(channels, 1)
                        codec.releaseOutputBuffer(outIdx, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                        if (totalFrames > rate.toLong() * SpeechAudio.MAX_FILE_SECONDS) {
                            return DecodeOutcome.Failed(
                                "audio_too_long",
                                "The audio is longer than ${SpeechAudio.MAX_FILE_SECONDS / 60} minutes; trim it or split it.",
                            )
                        }
                    }
                }
            }
            val mono = resampler?.finish() ?: ShortArray(0)
            val wav = VoiceProvider.wrapPcm16InWav(SpeechAudio.toLittleEndianBytes(mono), SpeechAudio.SAMPLE_RATE)
            return DecodeOutcome.Ok(wav, mono.size / SpeechAudio.SAMPLE_RATE.toDouble(), format, decoded = true)
        } catch (e: OutOfMemoryError) {
            // [T-speech-decode-streaming] Never take the app down for one file.
            AppLogger.warning(TAG, "decode ran out of memory")
            return DecodeOutcome.Failed("unsupported_format", "Android ran out of memory decoding this file; trim or split it.")
        } catch (e: Exception) {
            AppLogger.warning(TAG, "decode failed: ${e.message}")
            return DecodeOutcome.Failed(
                "unsupported_format",
                "Android could not decode this file (detected: ${format.label}): ${e.message}",
            )
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            extractor.release()
        }
    }

    /** Try the Voice Input candidates in order; the first transcript wins. */
    fun transcribe(wav: ByteArray, language: String?): ProviderOutcome {
        val repo = repository() ?: return ProviderOutcome.NoModel
        val list = candidates()
        if (list.isEmpty()) return ProviderOutcome.NoModel
        val errors = mutableListOf<Pair<SpeechEngines.VoiceModel, String>>()
        for ((instance, entry) in list) {
            val model = describe(instance, entry)
            val key = repo.loadApiKey(instance.id)
            val provider = VoiceProviderFactory.make(instance, key)
            if (provider == null) {
                errors += model to "This provider type cannot serve voice input."
                continue
            }
            try {
                val response = runBlocking {
                    provider.transcribe(
                        VoiceInputRequest(
                            audioData = wav,
                            model = entry.baseModel.id,
                            language = language?.let { Locale.forLanguageTag(it).toLanguageTag() },
                            resolvedModel = entry.model,
                        ),
                    )
                }
                return ProviderOutcome.Success(response.text.trim(), model)
            } catch (e: Exception) {
                // Messages carry the HTTP status / body excerpt, not the key.
                AppLogger.warning(TAG, "provider ${instance.label} failed: ${e.javaClass.simpleName}")
                errors += model to SpeechAudioRedact.redact(e.message ?: e.javaClass.simpleName, key)
            }
        }
        return ProviderOutcome.Failed(errors)
    }

    companion object {
        private const val TAG = "SpeechOffload"
    }
}

/**
 * Some services echo the (partial) key in a 401 body ("Incorrect API key
 * provided: sk-...abcd"). The CLI prints provider errors, so the key and any
 * sk-style token are masked first.
 */
internal object SpeechAudioRedact {
    private val TOKEN = Regex("""(sk|pk|ak|key)[-_][A-Za-z0-9_\-*.]{6,}""", RegexOption.IGNORE_CASE)

    fun redact(message: String, key: String?): String {
        var out = message
        if (!key.isNullOrBlank()) out = out.replace(key, "<redacted>")
        return TOKEN.replace(out, "<redacted>")
    }
}

internal sealed class CaptureOutcome {
    class Ok(val wav: ByteArray, val seconds: Double) : CaptureOutcome()
    class Failed(val code: String, val message: String) : CaptureOutcome()
    object Cancelled : CaptureOutcome()
}

internal sealed class DecodeOutcome {
    class Ok(val wav: ByteArray, val seconds: Double?, val format: SpeechAudio.Format, val decoded: Boolean) : DecodeOutcome()
    class Failed(val code: String, val message: String) : DecodeOutcome()
}

internal sealed class ProviderOutcome {
    class Success(val text: String, val model: SpeechEngines.VoiceModel) : ProviderOutcome()
    class Failed(val errors: List<Pair<SpeechEngines.VoiceModel, String>>) : ProviderOutcome()
    object NoModel : ProviderOutcome()
}
