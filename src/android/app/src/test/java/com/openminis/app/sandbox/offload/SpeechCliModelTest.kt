package com.openminis.app.sandbox.offload

import android.speech.SpeechRecognizer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-431] GH #431: `status` said `available: true` on a vivo phone where no
 * recognizer served Minis, `languages` printed an empty list (on every device -
 * confirmed on a Pixel 4a), and `transcribe` printed "Client error" with exit 0.
 */
class SpeechCliModelTest {

    private val google = "com.google.android.tts/com.google.android.apps.speech.tts.googletts.service.GoogleTTSRecognitionService"
    private val vivo = "com.vivo.ai.copilot/com.vivo.ai.copilot.speech.CopilotRecognitionService"

    private fun env(
        services: List<String> = listOf(google),
        default: String? = google,
        mic: Boolean = true,
        lastError: SpeechCli.LastError? = null,
    ) = SpeechCli.Environment(services, default, true, "zh-CN", mic, 33, lastError)

    // ── envelope ─────────────────────────────────────────────────────────

    @Test
    fun `success envelope keeps the flat pre-431 fields`() {
        val out = SpeechCli.ok("status", JSONObject().put("available", true).put("is_available", false))
        assertTrue(out.getBoolean("ok"))
        assertEquals("android-speech", out.getString("tool"))
        assertEquals("status", out.getString("action"))
        assertTrue(out.getJSONObject("data").getBoolean("available"))
        // Legacy readers of `.available` still find it at the top level.
        assertTrue(out.getBoolean("available"))
        assertTrue(out.has("timestamp"))
    }

    @Test
    fun `error envelope carries code and message`() {
        val out = SpeechCli.error("transcribe", "client_error", "msg", JSONObject().put("android_error", 5))
        assertFalse(out.getBoolean("ok"))
        val err = out.getJSONObject("error")
        assertEquals("client_error", err.getString("code"))
        assertEquals("msg", err.getString("message"))
        assertEquals(5, err.getInt("android_error"))
        assertEquals("msg", out.getString("message"))
    }

    // ── status ───────────────────────────────────────────────────────────

    @Test
    fun `no service means not available`() {
        val (ok, reason) = SpeechCli.assess(env(services = emptyList(), default = null))
        assertFalse(ok)
        assertTrue(reason!!.contains("No speech recognition service"))
    }

    @Test
    fun `an unset default service is named as the reason`() {
        val (ok, reason) = SpeechCli.assess(env(default = null))
        assertFalse(ok)
        assertTrue(reason!!.contains("voice_recognition_service is empty"))
    }

    @Test
    fun `a default the package manager does not list is not available`() {
        val (ok, reason) = SpeechCli.assess(env(services = listOf(google), default = vivo))
        assertFalse(ok)
        assertTrue(reason!!.contains(vivo))
    }

    @Test
    fun `short and long class forms of the default match`() {
        val e = env(services = listOf("com.example/com.example.Rec"), default = "com.example/.Rec")
        assertTrue(SpeechCli.defaultVisible(e))
    }

    @Test
    fun `the vivo case - listed, default visible, but ERROR_CLIENT last time`() {
        val e = env(
            services = listOf(vivo, "com.anthropic.claude/com.anthropic.claude.ClaudeRecognitionService"),
            default = vivo,
            lastError = SpeechCli.LastError(SpeechRecognizer.ERROR_CLIENT, 0L, "regular"),
        )
        val data = SpeechCli.status(e)
        // `available` keeps its pre-431 meaning: a service is listed.
        assertTrue(data.getBoolean("available"))
        assertFalse(data.getBoolean("is_available"))
        assertTrue(data.getString("reason").contains("ERROR_CLIENT"))
        assertEquals("ERROR_CLIENT", data.getJSONObject("last_error").getString("name"))
        assertEquals(2, data.getJSONArray("recognition_services").length())
        assertEquals(vivo, data.getString("default_service"))
        assertTrue(data.getBoolean("default_service_visible"))
    }

    @Test
    fun `a healthy device has no reason`() {
        val data = SpeechCli.status(env())
        assertTrue(data.getBoolean("is_available"))
        assertTrue(data.isNull("reason"))
        assertEquals("zh-CN", data.getString("locale"))
        assertTrue(data.getBoolean("supports_on_device"))
        assertEquals("authorized", data.getString("authorization_status"))
    }

    @Test
    fun `missing mic permission is a caveat, not unavailability`() {
        val data = SpeechCli.status(env(mic = false))
        assertTrue(data.getBoolean("is_available"))
        assertFalse(data.getBoolean("has_record_audio_permission"))
        assertTrue(data.getString("reason").contains("Microphone permission"))
    }

    // ── languages ────────────────────────────────────────────────────────

    @Test
    fun `nothing reported is unavailable, never an empty list`() {
        val r = SpeechCli.languages(
            SpeechCli.VoiceDetails(null, "no receiver"),
            null,
            listOf("en-US", "zh-CN"),
            null,
        )
        assertTrue(r is SpeechCli.LanguagesResult.Unavailable)
        assertEquals(SpeechCli.EXIT_NOT_AVAILABLE, r.exitCode)
        val body = r.body
        assertFalse(body.getBoolean("ok"))
        assertFalse(body.has("locales"))
        val err = body.getJSONObject("error")
        assertEquals("unavailable", err.getString("code"))
        assertEquals("no receiver", err.getString("voice_details"))
        val fallback = err.getJSONObject("fallback")
        assertEquals("builtin", fallback.getString("source"))
        assertEquals(2, fallback.getInt("count"))
        assertTrue(fallback.getString("note").contains("NOT what"))
    }

    @Test
    fun `an empty answer from both sources is also unavailable`() {
        val r = SpeechCli.languages(
            SpeechCli.VoiceDetails(emptyList(), "empty"),
            SpeechCli.Support(emptyList(), emptyList(), emptyList(), "regular: ok"),
            emptyList(),
            null,
        )
        assertTrue(r is SpeechCli.LanguagesResult.Unavailable)
    }

    @Test
    fun `voice details are listed in BCP-47`() {
        val r = SpeechCli.languages(SpeechCli.VoiceDetails(listOf("en_US", "zh-CN"), "ok"), null, emptyList(), null)
        assertTrue(r is SpeechCli.LanguagesResult.Found)
        val data = r.body.getJSONObject("data")
        assertEquals(2, data.getInt("count"))
        assertEquals("en-US", data.getJSONArray("locales").getJSONObject(0).getString("locale"))
        assertEquals("voice_details", data.getJSONArray("sources").getString(0))
        // Flat legacy fields.
        assertEquals(2, r.body.getInt("count"))
    }

    @Test
    fun `recognition support marks online and on-device per locale`() {
        val r = SpeechCli.languages(
            SpeechCli.VoiceDetails(null, "no receiver"),
            SpeechCli.Support(listOf("en-US", "zh-CN"), listOf("en-US"), listOf("en-US", "ja-JP"), "ok"),
            emptyList(),
            null,
        )
        val locales = r.body.getJSONObject("data").getJSONArray("locales")
        assertEquals(3, locales.length())
        val en = locales.getJSONObject(0)
        assertEquals("en-US", en.getString("locale"))
        assertTrue(en.getBoolean("online"))
        assertTrue(en.getBoolean("on_device_installed"))
        val ja = locales.getJSONObject(1)
        assertFalse(ja.getBoolean("online"))
        assertTrue(ja.getBoolean("on_device_supported"))
    }

    @Test
    fun `a recognizer that did not answer leaves its flags out`() {
        // Pixel 4a, Android 13: only the on-device recognizer answers
        // checkRecognitionSupport. Printing online=false for every locale
        // would claim "not supported online" when nothing was said.
        val r = SpeechCli.languages(
            SpeechCli.VoiceDetails(null, "no answer"),
            SpeechCli.Support(
                emptyList(), emptyList(), listOf("en-US"), "regular: ERROR_CANNOT_CHECK_SUPPORT; on_device: ok",
                regularAnswered = false,
                onDeviceAnswered = true,
            ),
            emptyList(),
            null,
        )
        val data = r.body.getJSONObject("data")
        val en = data.getJSONArray("locales").getJSONObject(0)
        assertFalse(en.has("online"))
        assertTrue(en.getBoolean("on_device_supported"))
        assertTrue(data.getJSONObject("notes").getString("recognition_support").contains("ERROR_CANNOT_CHECK_SUPPORT"))
    }

    @Test
    fun `language prefix filters`() {
        val r = SpeechCli.languages(SpeechCli.VoiceDetails(listOf("en-US", "en-GB", "zh-CN"), "ok"), null, emptyList(), "en")
        assertEquals(2, r.body.getInt("count"))
    }

    // ── transcribe errors ────────────────────────────────────────────────

    @Test
    fun `error codes are stable snake case`() {
        assertEquals("client_error", SpeechCli.errorCode(SpeechRecognizer.ERROR_CLIENT))
        assertEquals("service_disconnected", SpeechCli.errorCode(11))
        assertEquals("language_not_supported", SpeechCli.errorCode(12))
        assertEquals("language_unavailable", SpeechCli.errorCode(13))
        assertEquals("too_many_requests", SpeechCli.errorCode(10))
    }

    @Test
    fun `client error says which service and what to try`() {
        val msg = SpeechCli.recognizerErrorMessage(SpeechRecognizer.ERROR_CLIENT, null, vivo, listOf("Use X."))
        assertTrue(msg.contains(vivo))
        assertTrue(msg.contains("vivo OriginOS"))
        assertTrue(msg.contains("Use X."))
        assertTrue(msg.contains("android-speech status"))
        assertFalse(msg == "Client error")
    }

    @Test
    fun `language errors name the requested language`() {
        val msg = SpeechCli.recognizerErrorMessage(12, "zh-CN", google, emptyList())
        assertTrue(msg.contains("zh-CN"))
    }
}
