package com.openminis.app.provider

import com.openminis.app.ProductionSources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** [T-sse-idle-timeout] Per-provider response idle timeout (port of iOS b15ac8aa3). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ResponseIdleTimeoutSettingTest {

    private val s = ResponseIdleTimeoutSetting

    @Test
    fun `same default, range and presets as iOS`() {
        assertEquals(120, s.DEFAULT_SECONDS)
        assertEquals(30, s.clamp(5))
        assertEquals(3600, s.clamp(99_999))
        assertEquals(listOf(120, 300, 600, 1800, 3600), s.PRESETS)
    }

    @Test
    fun `nothing stored is the default, and storing the default removes the key`() {
        val ctx = RuntimeEnvironment.getApplication()
        assertEquals(120, s.seconds(ctx, "inst-a"))
        assertEquals(120, s.seconds(ctx, null))
        s.set(ctx, "inst-a", 1800)
        assertEquals(1800, s.seconds(ctx, "inst-a"))
        assertEquals("per instance", 120, s.seconds(ctx, "inst-b"))
        s.set(ctx, "inst-a", 120)
        val prefs = ctx.getSharedPreferences("provider_response_idle_timeout", android.content.Context.MODE_PRIVATE)
        assertTrue(!prefs.contains(s.key("inst-a")))
        s.set(ctx, "inst-a", 10)
        assertEquals("clamped on write", 30, s.seconds(ctx, "inst-a"))
    }

    @Test
    fun `the socket read timeout is only ever raised`() {
        assertNull(s.readTimeoutSeconds(120))
        assertNull(s.readTimeoutSeconds(600))
        assertEquals(1830, s.readTimeoutSeconds(1800))
        assertEquals(3630, s.readTimeoutSeconds(3600))
    }

    @Test
    fun `only Chat Completions reads it, for the first-byte budget and the read timeout`() {
        val src = ProductionSources.read("provider/openai/OpenAIProvider.kt")
        assertTrue(src.contains("val ttfbBudgetMs = if (usesChatCompletionsAPI) responseIdleTimeoutSec * 1000L else STREAM_TTFB_TIMEOUT_MS"))
        assertTrue(src.contains("if (sinceUploadMs >= ttfbBudgetMs)"))
        assertTrue(src.contains("val call = callClient.newCall("))
        val factory = ProductionSources.read("provider/ProviderFactory.kt")
        assertTrue(factory.contains("ResponseIdleTimeoutSetting.seconds(context, instance.id)"))
    }
}
