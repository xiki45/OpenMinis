package com.openminis.app.sandbox.offload

import com.openminis.app.speech.RecognizerFallback
import org.json.JSONArray
import org.json.JSONObject

/**
 * [T-431-engine] Pure rules for `android-speech transcribe --engine
 * system|provider|auto` (GH #431 feature request 1/2, from the issue author):
 * transcribe with the speech-to-text model configured in the Voice Input
 * group, from the microphone or from an audio file.
 *
 * Data destination is the point of these rules. The system engine keeps audio
 * with Android's recognizer; the provider engine uploads it to a third-party
 * service the user configured, which may charge for it. So:
 *  - the default stays `system` - no silent change of where audio goes;
 *  - `provider` must be asked for;
 *  - `auto` uses the provider only when the system recognizer is known not
 *    to work or fails to connect, and every provider result states where the
 *    audio went (`audio_sent_to`, `notice`).
 */
internal object SpeechEngines {
    enum class Engine(val label: String) {
        SYSTEM("system"),
        PROVIDER("provider"),
        AUTO("auto"),
    }

    /** Null [raw] is the default (system); an unknown name is null. */
    fun parse(raw: String?): Engine? {
        val s = raw?.trim()?.lowercase() ?: return Engine.SYSTEM
        return Engine.entries.firstOrNull { it.label == s }
    }

    /** A Voice Input group member, without credentials. */
    data class VoiceModel(val providerLabel: String, val modelId: String, val host: String)

    fun destination(m: VoiceModel): String = "provider \"${m.providerLabel}\" (model ${m.modelId}, ${m.host})"

    fun sentNotice(m: VoiceModel): String = "Audio was sent to ${destination(m)}."

    /**
     * `auto`, before capturing: go straight to the provider when the system
     * recognizer is known not to work here (no service, default hidden, last
     * attempt ERROR_CLIENT) - recording first would make the user speak twice.
     */
    fun autoStartsWithProvider(servicesListed: Boolean, systemUsable: Boolean, haveModel: Boolean): Boolean =
        haveModel && (!servicesListed || !systemUsable)

    /**
     * `auto`, after the system engine answered: fall back to the provider for
     * the failures that mean "cannot reach a recognizer" - the same set the
     * system engine itself switches recognizers on - and a recognizer that
     * could not be created. Silence, a timeout or a network error are not
     * reasons to send audio elsewhere.
     */
    fun autoFallsBack(outcome: RecognizeOutcome): Boolean = when (outcome) {
        is RecognizeOutcome.Failure -> RecognizerFallback.shouldSwitch(outcome.code)
        is RecognizeOutcome.CreationFailed -> true
        else -> false
    }

    fun noModelMessage(): String =
        "No voice-input model is configured. Add a speech-to-text model (e.g. whisper-1 or " +
            "gpt-4o-transcribe) from a provider to Settings > Model Groups > Voice Input, and make sure " +
            "voice input is not set to System. Then run `android-speech transcribe --engine provider`."

    /** The `engine` block of `status`. Names and hosts only, never keys. */
    fun status(models: List<VoiceModel>, servicesListed: Boolean, systemUsable: Boolean): JSONObject {
        val first = models.firstOrNull()
        val auto = if (autoStartsWithProvider(servicesListed, systemUsable, models.isNotEmpty())) {
            "provider"
        } else if (models.isNotEmpty()) {
            "system, then provider if the system recognizer cannot be reached"
        } else {
            "system"
        }
        return JSONObject()
            .put("default", Engine.SYSTEM.label)
            .put("auto_would_use", auto)
            .put(
                "voice_input_model",
                JSONObject()
                    .put("configured", first != null)
                    .put("provider_label", first?.providerLabel ?: JSONObject.NULL)
                    .put("model", first?.modelId ?: JSONObject.NULL)
                    .put("host", first?.host ?: JSONObject.NULL)
                    .put("candidates", models.size),
            )
    }

    fun providerErrors(errors: List<Pair<VoiceModel, String>>): JSONArray = JSONArray().apply {
        errors.forEach { (m, msg) ->
            put(
                JSONObject().put("provider_label", m.providerLabel).put("model", m.modelId)
                    .put("host", m.host).put("message", msg),
            )
        }
    }
}

/**
 * [T-431-engine] Audio handling for the provider engine. Voice providers take
 * one WAV (the request is built as `voice.wav`, `audio/wav`), so the CLI sends
 * WAV: a WAV file small enough goes as-is, anything else Android can decode
 * (MP3, M4A/AAC, FLAC, OGG/Opus, AMR, WebM, large or multi-channel WAV) is
 * decoded and re-encoded as 16 kHz mono PCM WAV on the device first.
 */
internal object SpeechAudio {
    const val SAMPLE_RATE = 16_000

    /** Refuse bigger input files before reading them. */
    const val MAX_FILE_BYTES = 100L * 1024 * 1024

    /** OpenAI's /v1/audio/transcriptions limit; most compatible services match it. */
    const val MAX_UPLOAD_BYTES = 25L * 1024 * 1024

    /** Decoded audio cap: 10 min of 16 kHz mono is 19.2 MB, under [MAX_UPLOAD_BYTES]. */
    const val MAX_FILE_SECONDS = 600

    /** Microphone capture cap, as the chat voice input and iOS apple-speech. */
    const val MAX_MIC_SECONDS = 60

    enum class Format(val label: String) {
        WAV("wav"), MP3("mp3"), MP4("m4a/mp4"), FLAC("flac"), OGG("ogg"), AMR("amr"),
        WEBM("webm"), AAC("aac"), UNKNOWN("unknown"),
    }

    /** Identify an audio container from its first bytes. */
    fun sniff(head: ByteArray): Format {
        fun at(i: Int, s: String) = head.size >= i + s.length &&
            s.indices.all { head[i + it] == s[it].code.toByte() }
        return when {
            at(0, "RIFF") && at(8, "WAVE") -> Format.WAV
            at(0, "ID3") -> Format.MP3
            head.size >= 2 && head[0] == 0xFF.toByte() && (head[1].toInt() and 0xF6) == 0xF0 -> Format.AAC
            head.size >= 2 && head[0] == 0xFF.toByte() && (head[1].toInt() and 0xE0) == 0xE0 -> Format.MP3
            at(4, "ftyp") -> Format.MP4
            at(0, "fLaC") -> Format.FLAC
            at(0, "OggS") -> Format.OGG
            at(0, "#!AMR") -> Format.AMR
            head.size >= 4 && head[0] == 0x1A.toByte() && head[1] == 0x45.toByte() &&
                head[2] == 0xDF.toByte() && head[3] == 0xA3.toByte() -> Format.WEBM
            else -> Format.UNKNOWN
        }
    }

    /**
     * Length of a WAV sent unchanged, from its header's byte rate (offset 28)
     * and the file size; null when the header is not a usable PCM WAV.
     */
    fun wavSeconds(head: ByteArray, sizeBytes: Long): Double? {
        if (sniff(head) != Format.WAV || head.size < 44) return null
        val byteRate = (head[28].toInt() and 0xFF) or ((head[29].toInt() and 0xFF) shl 8) or
            ((head[30].toInt() and 0xFF) shl 16) or ((head[31].toInt() and 0xFF) shl 24)
        if (byteRate <= 0) return null
        return (sizeBytes - 44).coerceAtLeast(0) / byteRate.toDouble()
    }

    /** A WAV small enough to upload unchanged. */
    fun passThrough(format: Format, sizeBytes: Long): Boolean =
        format == Format.WAV && sizeBytes <= MAX_UPLOAD_BYTES

    /** Average interleaved channels into mono. */
    fun downmix(interleaved: ShortArray, channels: Int): ShortArray {
        if (channels <= 1) return interleaved
        val frames = interleaved.size / channels
        return ShortArray(frames) { f ->
            var sum = 0
            for (c in 0 until channels) sum += interleaved[f * channels + c]
            (sum / channels).toShort()
        }
    }

    /** Linear-interpolation resampling; speech-recognition quality is enough. */
    fun resample(mono: ShortArray, fromRate: Int, toRate: Int): ShortArray {
        if (fromRate == toRate || mono.isEmpty()) return mono
        val outLen = ((mono.size.toLong() * toRate) / fromRate).toInt()
        return ShortArray(outLen) { i ->
            val pos = i.toDouble() * fromRate / toRate
            val i0 = pos.toInt().coerceAtMost(mono.size - 1)
            val i1 = (i0 + 1).coerceAtMost(mono.size - 1)
            val frac = pos - i0
            (mono[i0] + (mono[i1] - mono[i0]) * frac).toInt().toShort()
        }
    }

    /**
     * [T-speech-decode-streaming] [downmix] + [resample] fed one decoder buffer
     * at a time, so a long file never sits in memory at its source rate and
     * channel count (10 min of 48 kHz stereo was ~115 MB of shorts, copied
     * again before the downmix). Same output as the batch pair: the
     * interpolation positions are computed with the same expression, and an
     * output sample is emitted as soon as both input samples it reads exist.
     * The output is capped at [maxOut] samples (null = no cap).
     */
    class StreamingResampler(
        private val channels: Int,
        private val fromRate: Int,
        private val toRate: Int,
        private val maxOut: Int? = null,
    ) {
        private var out = ShortArray(minOf(maxOut ?: 1 shl 16, 1 shl 16).coerceAtLeast(1))
        private var outLen = 0
        private var inCount = 0L
        private var prev: Short = 0
        private var nextOut = 0L

        /** Output samples so far. */
        val size: Int get() = outLen

        private fun emit(v: Short) {
            if (maxOut != null && outLen >= maxOut) return
            if (outLen == out.size) out = out.copyOf(maxOf(out.size * 2, 1).let { if (maxOut != null) minOf(it, maxOut) else it })
            out[outLen++] = v
        }

        fun push(interleaved: ShortArray, length: Int = interleaved.size) {
            val ch = maxOf(channels, 1)
            val frames = length / ch
            for (f in 0 until frames) {
                val s: Short = if (ch == 1) interleaved[f] else {
                    var sum = 0
                    for (c in 0 until ch) sum += interleaved[f * ch + c]
                    (sum / ch).toShort()
                }
                val g = inCount
                if (fromRate == toRate) {
                    emit(s)
                } else if (g > 0) {
                    // Every output whose left input sample is g-1.
                    while (true) {
                        val pos = nextOut.toDouble() * fromRate / toRate
                        val i0 = pos.toLong()
                        if (i0 > g - 1) break
                        val frac = pos - i0
                        emit((prev + (s - prev) * frac).toInt().toShort())
                        nextOut++
                    }
                }
                prev = s
                inCount++
            }
        }

        /** The 16-bit mono result; call once, after the last [push]. */
        fun finish(): ShortArray {
            if (fromRate != toRate && inCount > 0) {
                val total = (inCount * toRate) / fromRate
                // Downsampling can compute an output just past the batch
                // length before the stream's end is known: drop it.
                if (outLen > total) outLen = total.toInt()
                // The tail [resample] clamps to the last sample.
                while (nextOut < total) {
                    emit(prev)
                    nextOut++
                }
            }
            return out.copyOf(outLen)
        }
    }

    fun toLittleEndianBytes(samples: ShortArray): ByteArray {
        val out = ByteArray(samples.size * 2)
        for (i in samples.indices) {
            val v = samples[i].toInt()
            out[2 * i] = (v and 0xFF).toByte()
            out[2 * i + 1] = ((v shr 8) and 0xFF).toByte()
        }
        return out
    }
}
