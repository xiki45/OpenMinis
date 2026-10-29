package com.openminis.app.sandbox.offload

import android.speech.SpeechRecognizer
import com.openminis.app.offload.OffloadPermissionManager
import com.openminis.app.sandbox.NativeOffloadRequest
import com.openminis.app.sandbox.NativeOffloadResult
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [T-431] The handler's envelope and exit codes, driven through a fake system.
 * Pre-#431, `transcribe` failures printed `{"error": "Client error"}` with
 * exit 0, so an agent could not tell a failure from a transcript.
 */
class SpeechOffloadHandlerTest {

    internal open class FakeSpeechSystem : SpeechSystem {
        var services = listOf("com.google.android.tts/com.google.Rec")
        var defaultService: String? = "com.google.android.tts/com.google.Rec"
        var mic = true
        var permission = OffloadPermissionManager.AndroidPermissionResult.GRANTED
        var outcomes = ArrayDeque<RecognizeOutcome>()
        val requests = mutableListOf<RecognizeRequest>()
        var lastErrorRecorded: Pair<Int?, String?>? = null
        var details = SpeechCli.VoiceDetails(null, "no receiver")
        var support: SpeechCli.Support? = null
        var onDeviceAvailable: Boolean? = true
        var sdk = 33
        var busy: String? = null

        var models = emptyList<SpeechEngines.VoiceModel>()
        var capture: CaptureOutcome = CaptureOutcome.Ok(ByteArray(44), 3.0)
        var decoded: DecodeOutcome = DecodeOutcome.Ok(ByteArray(44), 12.5, SpeechAudio.Format.MP3, decoded = true)
        var providerAnswer: ProviderOutcome? = null
        val uploads = mutableListOf<Pair<Int, String?>>()
        var recordedSeconds: Int? = null
        var decodedFile: File? = null
        var existingFile: File? = null

        override fun environment() =
            SpeechCli.Environment(services, defaultService, onDeviceAvailable, "zh-CN", mic, sdk, null)
        override fun micBusyReason() = busy
        override fun voiceInputModels() = models
        var alive = true
        var dieDuringCapture = false

        override fun callerAlive(pid: Int) = alive
        override fun recordMic(seconds: Int, isCancelled: () -> Boolean): CaptureOutcome {
            recordedSeconds = seconds
            if (dieDuringCapture) alive = false
            return if (isCancelled()) CaptureOutcome.Cancelled else capture
        }

        override fun decodeAudioFile(file: File, isCancelled: () -> Boolean): DecodeOutcome {
            decodedFile = file
            return decoded
        }

        override fun providerTranscribe(wav: ByteArray, language: String?): ProviderOutcome {
            uploads += wav.size to language
            return providerAnswer ?: models.firstOrNull()?.let { ProviderOutcome.Success("from provider", it) }
                ?: ProviderOutcome.NoModel
        }
        override fun voiceDetails(timeoutMs: Long) = details
        override fun recognitionSupport(timeoutMs: Long) = support
        override fun hasMicPermission() = mic
        override fun requestMicPermission() = permission
        override fun recognize(request: RecognizeRequest): RecognizeOutcome {
            requests += request
            return outcomes.removeFirst()
        }

        override fun probe(timeoutMs: Long): JSONObject = JSONObject().put("ok", true)
        override fun resolveSourcePath(source: String): File? = existingFile ?: File("/nonexistent/$source")
        override fun recordLastError(code: Int?, recognizer: String?) {
            lastErrorRecorded = code to recognizer
        }
    }

    private fun run(system: SpeechSystem, vararg argv: String): NativeOffloadResult =
        SpeechOffloadHandler(system).handle(
            NativeOffloadRequest(pid = 1, argv = listOf("android-speech") + argv, env = emptyMap(), cwd = "/"),
        )

    private fun json(r: NativeOffloadResult) = JSONObject(r.output.trim())

    @Test
    fun `status exits 0 with the new fields`() {
        val r = run(FakeSpeechSystem(), "status")
        assertEquals(0, r.exitCode)
        val out = json(r)
        assertTrue(out.getBoolean("ok"))
        assertTrue(out.getJSONObject("data").has("recognition_services"))
        assertTrue(out.has("available"))
    }

    @Test
    fun `status --probe includes the probe`() {
        val out = json(run(FakeSpeechSystem(), "status", "--probe"))
        assertTrue(out.getJSONObject("data").getJSONObject("probe").getBoolean("ok"))
    }

    @Test
    fun `languages with no answer exits 4`() {
        val r = run(FakeSpeechSystem(), "languages")
        assertEquals(SpeechCli.EXIT_NOT_AVAILABLE, r.exitCode)
        assertEquals("unavailable", json(r).getJSONObject("error").getString("code"))
    }

    @Test
    fun `a client error fails with exit 1 and is remembered`() {
        val sys = FakeSpeechSystem().apply {
            onDeviceAvailable = false // one attempt; fallback is tested below
            outcomes += RecognizeOutcome.Failure(SpeechRecognizer.ERROR_CLIENT, Recognizers.REGULAR)
        }
        val r = run(sys, "transcribe", "--duration", "3")
        assertEquals(SpeechCli.EXIT_ERROR, r.exitCode)
        val out = json(r)
        assertFalse(out.getBoolean("ok"))
        val err = out.getJSONObject("error")
        assertEquals("client_error", err.getString("code"))
        assertEquals(5, err.getInt("android_error"))
        assertEquals("ERROR_CLIENT", err.getString("android_error_name"))
        assertTrue(err.getString("message").contains("could not connect"))
        assertEquals(SpeechRecognizer.ERROR_CLIENT to Recognizers.REGULAR, sys.lastErrorRecorded)
    }

    @Test
    fun `a transcript exits 0 and clears the remembered error`() {
        val sys = FakeSpeechSystem().apply {
            outcomes += RecognizeOutcome.Success(listOf("hello", "hullo"), listOf(0.9f, 0.1f), Recognizers.REGULAR)
        }
        val r = run(sys, "transcribe", "--duration", "3")
        assertEquals(0, r.exitCode)
        val out = json(r)
        assertEquals("hello", out.getJSONObject("data").getString("text"))
        assertEquals("hello", out.getString("text"))
        assertEquals(2, out.getJSONObject("data").getJSONArray("alternatives").length())
        assertEquals(null to null, sys.lastErrorRecorded)
        assertEquals(3L, sys.requests.single().durationSec)
    }

    @Test
    fun `silence is an empty transcript, not a failure`() {
        val sys = FakeSpeechSystem().apply {
            outcomes += RecognizeOutcome.Failure(SpeechRecognizer.ERROR_NO_MATCH, Recognizers.REGULAR)
        }
        val r = run(sys, "transcribe")
        assertEquals(0, r.exitCode)
        val data = json(r).getJSONObject("data")
        assertEquals("", data.getString("text"))
        assertTrue(data.getBoolean("no_speech"))
    }

    @Test
    fun `a timeout fails`() {
        val sys = FakeSpeechSystem().apply { outcomes += RecognizeOutcome.Timeout(3, Recognizers.REGULAR) }
        val r = run(sys, "transcribe", "--duration", "3")
        assertEquals(SpeechCli.EXIT_ERROR, r.exitCode)
        assertEquals("timeout", json(r).getJSONObject("error").getString("code"))
    }

    @Test
    fun `no service exits 4`() {
        val sys = FakeSpeechSystem().apply { services = emptyList() }
        val r = run(sys, "transcribe")
        assertEquals(SpeechCli.EXIT_NOT_AVAILABLE, r.exitCode)
        assertEquals("recognizer_unavailable", json(r).getJSONObject("error").getString("code"))
    }

    @Test
    fun `a denied microphone exits 77`() {
        val sys = FakeSpeechSystem().apply {
            mic = false
            permission = OffloadPermissionManager.AndroidPermissionResult.DENIED
        }
        val r = run(sys, "transcribe")
        assertEquals(77, r.exitCode)
        assertEquals("permission_denied", json(r).getJSONObject("error").getString("code"))
    }

    @Test
    fun `duration is capped at 60 seconds`() {
        val sys = FakeSpeechSystem().apply { outcomes += RecognizeOutcome.Timeout(60, Recognizers.REGULAR) }
        run(sys, "transcribe", "--duration", "600")
        assertEquals(60L, sys.requests.single().durationSec)
    }

    @Test
    fun `a script subtag is stripped before reaching the recognizer`() {
        val sys = FakeSpeechSystem().apply { outcomes += RecognizeOutcome.Timeout(3, Recognizers.REGULAR) }
        run(sys, "transcribe", "--language", "zh-Hans-CN", "--duration", "3")
        assertEquals("zh-CN", sys.requests.single().language)
    }

    @Test
    fun `unknown command exits 2 with an error envelope`() {
        val r = run(FakeSpeechSystem(), "frobnicate")
        assertEquals(SpeechCli.EXIT_INVALID_ARGS, r.exitCode)
        assertEquals("invalid_args", json(r).getJSONObject("error").getString("code"))
    }

    @Test
    fun `quiet prints only the error object`() {
        val sys = FakeSpeechSystem().apply {
            onDeviceAvailable = false
            outcomes += RecognizeOutcome.Failure(SpeechRecognizer.ERROR_CLIENT, Recognizers.REGULAR)
        }
        val r = run(sys, "transcribe", "-q")
        val out = JSONObject(r.output.trim())
        assertEquals("client_error", out.getString("code"))
        assertNull(out.opt("ok"))
    }

    // ── [T-431-fallback] recognizer order and fallback ───────────────────

    @Test
    fun `the regular recognizer is the default`() {
        val sys = FakeSpeechSystem().apply {
            outcomes += RecognizeOutcome.Success(listOf("hi"), null, Recognizers.REGULAR)
        }
        val out = json(run(sys, "transcribe", "--duration", "3"))
        assertEquals(listOf(false), sys.requests.map { it.onDevice })
        assertEquals("regular", out.getJSONObject("data").getString("recognizer"))
        assertFalse(out.getJSONObject("data").getBoolean("fallback_used"))
    }

    @Test
    fun `a client error on the regular recognizer tries on-device once`() {
        val sys = FakeSpeechSystem().apply {
            outcomes += RecognizeOutcome.Failure(SpeechRecognizer.ERROR_CLIENT, Recognizers.REGULAR)
            outcomes += RecognizeOutcome.Success(listOf("hi"), null, Recognizers.ON_DEVICE)
        }
        val r = run(sys, "transcribe", "--duration", "3")
        assertEquals(0, r.exitCode)
        assertEquals(listOf(false, true), sys.requests.map { it.onDevice })
        val data = json(r).getJSONObject("data")
        assertEquals("on_device", data.getString("recognizer"))
        assertTrue(data.getBoolean("on_device"))
        assertTrue(data.getBoolean("fallback_used"))
        assertEquals("ERROR_CLIENT", data.getJSONArray("failed_attempts").getJSONObject(0).getString("error_name"))
    }

    @Test
    fun `--on-device starts on-device and falls back to regular on a missing pack`() {
        val sys = FakeSpeechSystem().apply {
            outcomes += RecognizeOutcome.Failure(12, Recognizers.ON_DEVICE)
            outcomes += RecognizeOutcome.Success(listOf("你好"), null, Recognizers.REGULAR)
        }
        val r = run(sys, "transcribe", "--on-device", "--duration", "3")
        assertEquals(0, r.exitCode)
        assertEquals(listOf(true, false), sys.requests.map { it.onDevice })
        val data = json(r).getJSONObject("data")
        assertEquals("regular", data.getString("recognizer"))
        assertTrue(data.getBoolean("on_device_requested"))
        assertTrue(data.getBoolean("fallback_used"))
    }

    @Test
    fun `language unavailable (13) also falls back`() {
        val sys = FakeSpeechSystem().apply {
            outcomes += RecognizeOutcome.Failure(13, Recognizers.ON_DEVICE)
            outcomes += RecognizeOutcome.Success(listOf("x"), null, Recognizers.REGULAR)
        }
        assertEquals(0, run(sys, "transcribe", "--on-device").exitCode)
        assertEquals(2, sys.requests.size)
    }

    @Test
    fun `a network error does not switch recognizers`() {
        val sys = FakeSpeechSystem().apply {
            outcomes += RecognizeOutcome.Failure(SpeechRecognizer.ERROR_NETWORK, Recognizers.REGULAR)
        }
        val r = run(sys, "transcribe")
        assertEquals(SpeechCli.EXIT_ERROR, r.exitCode)
        assertEquals(1, sys.requests.size)
    }

    @Test
    fun `both recognizers failing is one error naming both attempts`() {
        val sys = FakeSpeechSystem().apply {
            outcomes += RecognizeOutcome.Failure(SpeechRecognizer.ERROR_CLIENT, Recognizers.REGULAR)
            outcomes += RecognizeOutcome.Failure(SpeechRecognizer.ERROR_CLIENT, Recognizers.ON_DEVICE)
        }
        val r = run(sys, "transcribe")
        assertEquals(SpeechCli.EXIT_ERROR, r.exitCode)
        assertEquals(2, sys.requests.size)
        val err = json(r).getJSONObject("error")
        assertEquals("on_device", err.getString("recognizer"))
        assertEquals(1, err.getJSONArray("failed_attempts").length())
        assertTrue(err.getString("message").contains("Both the regular and the on-device"))
    }

    @Test
    fun `without an on-device recognizer there is nothing to fall back to`() {
        val sys = FakeSpeechSystem().apply {
            onDeviceAvailable = false
            outcomes += RecognizeOutcome.Failure(SpeechRecognizer.ERROR_CLIENT, Recognizers.REGULAR)
        }
        val r = run(sys, "transcribe")
        assertEquals(1, sys.requests.size)
        assertTrue(json(r).getJSONObject("error").getString("message").contains("no on-device recognizer"))
    }

    @Test
    fun `--on-device below Android 12 says it used the regular recognizer`() {
        val sys = FakeSpeechSystem().apply {
            sdk = 30
            onDeviceAvailable = null
            outcomes += RecognizeOutcome.Success(listOf("x"), null, Recognizers.REGULAR)
        }
        val data = json(run(sys, "transcribe", "--on-device")).getJSONObject("data")
        assertTrue(data.getString("note").contains("Android 12"))
        assertEquals(listOf(false), sys.requests.map { it.onDevice })
    }

    @Test
    fun `the chat voice input recording makes transcribe busy`() {
        val sys = FakeSpeechSystem().apply { busy = "The chat voice input is using the microphone." }
        val r = run(sys, "transcribe")
        assertEquals(SpeechCli.EXIT_ERROR, r.exitCode)
        assertEquals("busy", json(r).getJSONObject("error").getString("code"))
        assertTrue(sys.requests.isEmpty())
    }

    @Test
    fun `a second concurrent transcribe is busy`() {
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val sys = object : FakeSpeechSystem() {
            override fun recognize(request: RecognizeRequest): RecognizeOutcome {
                entered.countDown()
                release.await(5, java.util.concurrent.TimeUnit.SECONDS)
                return RecognizeOutcome.Success(listOf("x"), null, Recognizers.REGULAR)
            }
        }
        val first = Thread { run(sys, "transcribe") }.apply { start() }
        assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
        val second = run(sys, "transcribe")
        release.countDown()
        first.join(5_000)
        assertEquals("busy", json(second).getJSONObject("error").getString("code"))
        // The lock is released afterwards.
        val third = FakeSpeechSystem().apply { outcomes += RecognizeOutcome.Success(listOf("y"), null, Recognizers.REGULAR) }
        assertEquals(0, run(third, "transcribe").exitCode)
    }

    @Test
    fun `status shows the recognizer order`() {
        val data = json(run(FakeSpeechSystem(), "status")).getJSONObject("data")
        assertEquals("regular", data.getJSONArray("recognizer_order").getString(0))
        assertEquals("on_device", data.getJSONArray("recognizer_order_on_device").getString(0))
    }

    // ── [T-431-engine] --engine system|provider|auto, --source files ─────

    private val mock = SpeechEngines.VoiceModel("i431-mock", "whisper-1", "127.0.0.1")

    @Test
    fun `an unknown engine is an argument error`() {
        val r = run(FakeSpeechSystem(), "transcribe", "--engine", "cloud")
        assertEquals(SpeechCli.EXIT_INVALID_ARGS, r.exitCode)
    }

    @Test
    fun `the system engine refuses files and points at the provider engine`() {
        val sys = FakeSpeechSystem().apply { models = listOf(mock) }
        val r = run(sys, "transcribe", "--source", "/var/minis/a.m4a")
        assertEquals(SpeechCli.EXIT_INVALID_ARGS, r.exitCode)
        val err = json(r).getJSONObject("error")
        assertEquals("not_supported", err.getString("code"))
        assertTrue(err.getString("message").contains("--engine provider"))
        assertTrue(err.getString("message").contains("i431-mock"))
        assertTrue(sys.uploads.isEmpty())
    }

    @Test
    fun `the provider engine without a model says where to configure one`() {
        val r = run(FakeSpeechSystem(), "transcribe", "--engine", "provider")
        assertEquals(SpeechCli.EXIT_NOT_AVAILABLE, r.exitCode)
        val err = json(r).getJSONObject("error")
        assertEquals("no_voice_input_model", err.getString("code"))
        assertTrue(err.getString("message").contains("Model Groups > Voice Input"))
    }

    @Test
    fun `the provider engine records, uploads and says where the audio went`() {
        val sys = FakeSpeechSystem().apply { models = listOf(mock) }
        val r = run(sys, "transcribe", "--engine", "provider", "--duration", "4", "--language", "zh-Hans-CN")
        assertEquals(0, r.exitCode)
        assertTrue(sys.requests.isEmpty())
        assertEquals(4, sys.recordedSeconds)
        assertEquals("zh-CN", sys.uploads.single().second)
        val data = json(r).getJSONObject("data")
        assertEquals("from provider", data.getString("text"))
        assertEquals("provider", data.getString("engine_used"))
        assertEquals("i431-mock", data.getString("provider_label"))
        assertTrue(data.getString("audio_sent_to").contains("127.0.0.1"))
        assertTrue(data.getString("notice").startsWith("Audio was sent to provider"))
        assertFalse(data.has("fallback_from"))
    }

    @Test
    fun `auto stays on the system recognizer when it works`() {
        val sys = FakeSpeechSystem().apply {
            models = listOf(mock)
            outcomes += RecognizeOutcome.Success(listOf("hi"), null, Recognizers.REGULAR)
        }
        val data = json(run(sys, "transcribe", "--engine", "auto")).getJSONObject("data")
        assertEquals("system", data.getString("engine_used"))
        assertTrue(sys.uploads.isEmpty())
    }

    @Test
    fun `auto falls back to the provider on a client error and says so`() {
        val sys = FakeSpeechSystem().apply {
            models = listOf(mock)
            outcomes += RecognizeOutcome.Failure(SpeechRecognizer.ERROR_CLIENT, Recognizers.REGULAR)
            outcomes += RecognizeOutcome.Failure(SpeechRecognizer.ERROR_CLIENT, Recognizers.ON_DEVICE)
        }
        val r = run(sys, "transcribe", "--engine", "auto")
        assertEquals(0, r.exitCode)
        val out = json(r)
        val data = out.getJSONObject("data")
        assertEquals("provider", data.getString("engine_used"))
        assertEquals("auto", data.getString("engine_requested"))
        assertEquals("ERROR_CLIENT", data.getJSONObject("fallback_from").getString("error_name"))
        assertTrue(out.getString("notice").contains("Audio was sent to provider \"i431-mock\""))
        assertEquals(1, sys.uploads.size)
        assertEquals(SpeechRecognizer.ERROR_CLIENT to Recognizers.ON_DEVICE, sys.lastErrorRecorded)
    }

    @Test
    fun `auto does not send audio elsewhere for a network error or silence`() {
        val sys = FakeSpeechSystem().apply {
            models = listOf(mock)
            outcomes += RecognizeOutcome.Failure(SpeechRecognizer.ERROR_NETWORK, Recognizers.REGULAR)
        }
        assertEquals(SpeechCli.EXIT_ERROR, run(sys, "transcribe", "--engine", "auto").exitCode)
        val quiet = FakeSpeechSystem().apply {
            models = listOf(mock)
            outcomes += RecognizeOutcome.Failure(SpeechRecognizer.ERROR_NO_MATCH, Recognizers.REGULAR)
        }
        assertEquals(0, run(quiet, "transcribe", "--engine", "auto").exitCode)
        assertTrue(sys.uploads.isEmpty())
        assertTrue(quiet.uploads.isEmpty())
    }

    @Test
    fun `auto goes straight to the provider when the system recognizer is known not to work`() {
        val sys = FakeSpeechSystem().apply {
            models = listOf(mock)
            defaultService = null
        }
        val data = json(run(sys, "transcribe", "--engine", "auto")).getJSONObject("data")
        assertEquals("provider", data.getString("engine_used"))
        assertTrue(sys.requests.isEmpty())
        assertTrue(data.getJSONObject("fallback_from").getString("reason").contains("voice_recognition_service"))
    }

    @Test
    fun `auto without a model behaves as system and suggests configuring one`() {
        val sys = FakeSpeechSystem().apply {
            onDeviceAvailable = false
            outcomes += RecognizeOutcome.Failure(SpeechRecognizer.ERROR_CLIENT, Recognizers.REGULAR)
        }
        val r = run(sys, "transcribe", "--engine", "auto")
        assertEquals(SpeechCli.EXIT_ERROR, r.exitCode)
        assertTrue(json(r).getJSONObject("error").getString("message").contains("Model Groups > Voice Input"))
    }

    @Test
    fun `system errors mention the configured provider and its host`() {
        val sys = FakeSpeechSystem().apply {
            models = listOf(mock)
            onDeviceAvailable = false
            outcomes += RecognizeOutcome.Failure(SpeechRecognizer.ERROR_CLIENT, Recognizers.REGULAR)
        }
        val msg = json(run(sys, "transcribe")).getJSONObject("error").getString("message")
        assertTrue(msg.contains("--engine provider"))
        assertTrue(msg.contains("127.0.0.1"))
        assertTrue(sys.uploads.isEmpty())
    }

    @Test
    fun `a file goes through the provider engine`() {
        val f = File.createTempFile("i431", ".mp3").apply { writeBytes(ByteArray(10)); deleteOnExit() }
        val sys = FakeSpeechSystem().apply {
            models = listOf(mock)
            existingFile = f
        }
        val r = run(sys, "transcribe", "--engine", "provider", "--source", "/var/minis/attachments/a.mp3")
        assertEquals(0, r.exitCode)
        assertEquals(f, sys.decodedFile)
        val data = json(r).getJSONObject("data")
        assertEquals("audio_file", data.getString("source_type"))
        assertEquals("mp3", data.getString("file_format"))
        assertTrue(data.getBoolean("decoded_on_device"))
        assertTrue(data.has("audio_sent_to"))
    }

    @Test
    fun `auto takes files to the provider too`() {
        val f = File.createTempFile("i431", ".wav").apply { writeBytes(ByteArray(10)); deleteOnExit() }
        val sys = FakeSpeechSystem().apply {
            models = listOf(mock)
            existingFile = f
        }
        assertEquals(0, run(sys, "transcribe", "--engine", "auto", "--source", "x.wav").exitCode)
        assertEquals(1, sys.uploads.size)
    }

    @Test
    fun `a missing file is an argument error and nothing is uploaded`() {
        val sys = FakeSpeechSystem().apply { models = listOf(mock) }
        val r = run(sys, "transcribe", "--engine", "provider", "--source", "/var/minis/none.wav")
        assertEquals(SpeechCli.EXIT_INVALID_ARGS, r.exitCode)
        assertTrue(sys.uploads.isEmpty())
    }

    @Test
    fun `an oversized file is refused before upload`() {
        val f = File.createTempFile("i431", ".wav").apply { writeBytes(ByteArray(10)); deleteOnExit() }
        val sys = FakeSpeechSystem().apply {
            models = listOf(mock)
            existingFile = f
            decoded = DecodeOutcome.Failed("file_too_large", "The file is 200 MB; the limit is 100 MB.")
        }
        val r = run(sys, "transcribe", "--engine", "provider", "--source", "big.wav")
        assertEquals(SpeechCli.EXIT_INVALID_ARGS, r.exitCode)
        assertEquals("file_too_large", json(r).getJSONObject("error").getString("code"))
        assertTrue(sys.uploads.isEmpty())
    }

    @Test
    fun `a failing provider is an error that still says where the audio went`() {
        val sys = FakeSpeechSystem().apply {
            models = listOf(mock)
            providerAnswer = ProviderOutcome.Failed(listOf(mock to "HTTP 401"))
        }
        val r = run(sys, "transcribe", "--engine", "provider")
        assertEquals(SpeechCli.EXIT_ERROR, r.exitCode)
        val err = json(r).getJSONObject("error")
        assertEquals("provider_error", err.getString("code"))
        assertTrue(err.getJSONArray("audio_sent_to").getString(0).contains("i431-mock"))
        assertEquals("HTTP 401", err.getJSONArray("providers").getJSONObject(0).getString("message"))
    }

    @Test
    fun `status reports the voice input model by name and host only`() {
        val sys = FakeSpeechSystem().apply { models = listOf(mock) }
        val engine = json(run(sys, "status")).getJSONObject("data").getJSONObject("engine")
        assertEquals("system", engine.getString("default"))
        val vim = engine.getJSONObject("voice_input_model")
        assertTrue(vim.getBoolean("configured"))
        assertEquals("i431-mock", vim.getString("provider_label"))
        assertFalse(vim.toString().contains("key", ignoreCase = true))
    }

    @Test
    fun `a busy microphone also blocks the provider engine`() {
        val sys = FakeSpeechSystem().apply {
            models = listOf(mock)
            busy = "The chat voice input is using the microphone."
        }
        assertEquals("busy", json(run(sys, "transcribe", "--engine", "provider")).getJSONObject("error").getString("code"))
        assertEquals(null, sys.recordedSeconds)
    }

    // ── [T-431-cancel] a caller that exits stops capture and upload ──────

    @Test
    fun `a caller killed during a provider capture uploads nothing`() {
        val sys = FakeSpeechSystem().apply {
            models = listOf(mock)
            dieDuringCapture = true
        }
        val r = run(sys, "transcribe", "--engine", "provider", "--duration", "20")
        assertEquals(SpeechCli.EXIT_CANCELLED, r.exitCode)
        assertEquals("cancelled", json(r).getJSONObject("error").getString("code"))
        assertTrue(sys.uploads.isEmpty())
    }

    @Test
    fun `a caller gone before a file upload uploads nothing`() {
        val f = File.createTempFile("i431", ".wav").apply { writeBytes(ByteArray(10)); deleteOnExit() }
        val sys = FakeSpeechSystem().apply {
            models = listOf(mock)
            existingFile = f
            alive = false
        }
        val r = run(sys, "transcribe", "--engine", "provider", "--source", "a.wav")
        assertEquals(SpeechCli.EXIT_CANCELLED, r.exitCode)
        assertTrue(sys.uploads.isEmpty())
    }

    @Test
    fun `a cancelled system session neither fails over nor sends audio to the provider`() {
        val sys = FakeSpeechSystem().apply {
            models = listOf(mock)
            outcomes += RecognizeOutcome.Cancelled(Recognizers.REGULAR)
        }
        val r = run(sys, "transcribe", "--engine", "auto")
        assertEquals(SpeechCli.EXIT_CANCELLED, r.exitCode)
        assertEquals(1, sys.requests.size)
        assertTrue(sys.uploads.isEmpty())
        assertNull(sys.lastErrorRecorded)
    }

    @Test
    fun `the recognizer polls the caller`() {
        val sys = FakeSpeechSystem().apply {
            outcomes += RecognizeOutcome.Success(listOf("x"), null, Recognizers.REGULAR)
        }
        run(sys, "transcribe")
        val probe = sys.requests.single().isCancelled
        assertFalse(probe())
        sys.alive = false
        assertTrue(probe())
    }
}
