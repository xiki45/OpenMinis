package com.openminis.app.provider

import com.openminis.app.ProductionSources
import com.openminis.app.data.FastModePrefs
import com.openminis.app.data.SpeedTier
import com.openminis.app.data.SpeedTiers
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMModel
import com.openminis.app.provider.openai.OpenAIProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-speed-tier] Fast Mode became a three-way speed choice (Standard / Fast /
 * Ultrafast). Wire values: none / "priority" / "ultrafast" in the Responses
 * body's `service_tier`. Ultrafast only for an allow-listed model; xAI only
 * ever gets "priority".
 */
class SpeedTierTest {

    @After
    fun tearDown() {
        // Process-wide singleton: never leak a tier into another test's body.
        FastModePrefs.setCachedTierForTest(SpeedTier.STANDARD)
    }

    // ── Storage migration ────────────────────────────────────────────────

    @Test
    fun `legacy boolean true migrates to fast, false or absent to standard`() {
        assertEquals(SpeedTier.FAST, FastModePrefs.resolveStoredTier(null, legacyEnabled = true))
        assertEquals(SpeedTier.STANDARD, FastModePrefs.resolveStoredTier(null, legacyEnabled = false))
        assertEquals(SpeedTier.STANDARD, FastModePrefs.resolveStoredTier(null, legacyEnabled = null))
    }

    @Test
    fun `a stored tier wins over the legacy boolean`() {
        assertEquals(SpeedTier.ULTRAFAST, FastModePrefs.resolveStoredTier("ultrafast", legacyEnabled = true))
        assertEquals(SpeedTier.STANDARD, FastModePrefs.resolveStoredTier("off", legacyEnabled = true))
        assertEquals(SpeedTier.FAST, FastModePrefs.resolveStoredTier("fast", legacyEnabled = false))
    }

    @Test
    fun `an unknown stored value falls back to the legacy boolean`() {
        assertEquals(SpeedTier.FAST, FastModePrefs.resolveStoredTier("hyperfast", legacyEnabled = true))
        assertEquals(SpeedTier.STANDARD, FastModePrefs.resolveStoredTier("hyperfast", legacyEnabled = null))
    }

    @Test
    fun `writes keep the legacy boolean in step and prime stays at app start`() {
        val prefs = ProductionSources.read("data/FastModePrefs.kt")
        assertTrue(prefs.contains(".putBoolean(KEY_LEGACY_ENABLED, tier != SpeedTier.STANDARD)"))
        assertTrue(prefs.contains("private const val KEY_LEGACY_ENABLED = \"codexFastModeEnabled\""))
        assertTrue(ProductionSources.read("MinisApp.kt").contains("com.openminis.app.data.FastModePrefs.prime(this)"))
    }

    // ── Allow-list and fallback ──────────────────────────────────────────

    @Test
    fun `ultrafast whitelist is gpt-6-astra only, by prefix, case-insensitive`() {
        // [T-android-ultrafast-align] OpenAI's Codex docs: Ultrafast is "GPT-6 Astra only".
        for (id in listOf("gpt-6-astra", "GPT-6-Astra", "gpt-6-astra-2026-10-01")) {
            assertTrue(id, SpeedTiers.supportsUltrafast(id))
        }
        for (id in listOf("gpt-6.1-sol", "GPT-6.1-Sol", "gpt-6-sol", "gpt-6-luna", "gpt-5.6-sol", "gpt-5.5", "grok-4", "openai/gpt-6-astra", "")) {
            assertFalse(id, SpeedTiers.supportsUltrafast(id))
        }
    }

    @Test
    fun `ultrafast falls back to fast off the allow-list or off the responses path`() {
        // No catalog data on these models: the whitelist decides.
        assertEquals(SpeedTier.ULTRAFAST, SpeedTiers.effective(SpeedTier.ULTRAFAST, model("gpt-6-astra"), catalogIds = emptyList()))
        assertEquals(SpeedTier.FAST, SpeedTiers.effective(SpeedTier.ULTRAFAST, model("gpt-6.1-sol"), catalogIds = emptyList()))
        assertEquals(SpeedTier.FAST, SpeedTiers.effective(SpeedTier.ULTRAFAST, model("gpt-6-sol"), catalogIds = emptyList()))
        assertEquals(SpeedTier.FAST, SpeedTiers.effective(SpeedTier.ULTRAFAST, model("gpt-6-astra"), ultrafastPathAllowed = false))
        assertEquals(SpeedTier.FAST, SpeedTiers.effective(SpeedTier.FAST, model("gpt-6.1-sol")))
        assertEquals(SpeedTier.STANDARD, SpeedTiers.effective(SpeedTier.STANDARD, model("gpt-6.1-sol")))
    }

    @Test
    fun `the ui shows the same fallback, and nothing for an ineligible model`() {
        assertEquals(SpeedTier.ULTRAFAST, SpeedTiers.displayed(SpeedTier.ULTRAFAST, fastEligible = true, ultrafastEligible = true))
        assertEquals(SpeedTier.FAST, SpeedTiers.displayed(SpeedTier.ULTRAFAST, fastEligible = true, ultrafastEligible = false))
        assertEquals(SpeedTier.STANDARD, SpeedTiers.displayed(SpeedTier.ULTRAFAST, fastEligible = false, ultrafastEligible = false))
        assertEquals(SpeedTier.FAST, SpeedTiers.displayed(SpeedTier.FAST, fastEligible = true, ultrafastEligible = true))
    }

    // ── Request bodies ───────────────────────────────────────────────────

    private val history = listOf(LLMMessage(LLMMessage.Role.USER, "hello"))

    private fun model(id: String) = LLMModel(id = id, displayName = id, provider = "OpenAI", supportsReasoning = true)

    private fun responsesTier(id: String, tier: SpeedTier, xai: Boolean = false): String? {
        FastModePrefs.setCachedTierForTest(tier)
        val body = OpenAIProvider(
            apiKey = "k",
            model = model(id),
            basePath = if (xai) "https://api.x.ai/v1" else "https://api.openai.com/v1",
            useResponsesAPI = true,
        ).also { it.supportsPriorityProcessing = xai }
            .buildResponsesAPIBody(messages = history, systemPrompt = null, maxTokens = 1024, stream = false, imageParts = emptyList())
        return body.optString("service_tier", "").takeIf { it.isNotEmpty() }
    }

    @Test
    fun `three tiers on an allow-listed model - none, priority, ultrafast`() {
        assertNull(responsesTier("gpt-6-astra", SpeedTier.STANDARD))
        assertEquals("priority", responsesTier("gpt-6-astra", SpeedTier.FAST))
        assertEquals("ultrafast", responsesTier("gpt-6-astra", SpeedTier.ULTRAFAST))
    }

    @Test
    fun `a stored ultrafast choice sends priority on a model outside the allow-list`() {
        // [T-android-ultrafast-align] gpt-6.1-sol left the whitelist: sent (and shown) as Fast.
        for (id in listOf("gpt-6.1-sol", "gpt-6-sol", "gpt-5.6-sol", "gpt-5.5")) {
            assertEquals(id, "priority", responsesTier(id, SpeedTier.ULTRAFAST))
        }
        // Non-gpt ids still never carry a tier.
        assertNull(responsesTier("o3-mini", SpeedTier.ULTRAFAST))
    }

    @Test
    fun `fast is sent as priority, never the literal fast`() {
        assertEquals("priority", responsesTier("gpt-5.5", SpeedTier.FAST))
    }

    @Test
    fun `xai never sends ultrafast`() {
        val grok = LLMModel(id = "grok-4.3", displayName = "Grok", provider = "xai")
        FastModePrefs.setCachedTierForTest(SpeedTier.ULTRAFAST)
        val p = OpenAIProvider(apiKey = "k", model = grok, basePath = "https://api.x.ai/v1")
            .also { it.supportsPriorityProcessing = true }
        assertEquals("priority", p.resolvedServiceTier())
        val chat = p.buildRequestBody(
            messages = history, systemPrompt = null, maxTokens = 1024, stream = false,
            temperature = null, imageParts = emptyList(),
        )
        assertEquals("priority", chat.getString("service_tier"))
        // Even a gpt-named model behind xAI's Responses builder keeps xAI's priority.
        assertEquals("priority", responsesTier("gpt-6.1-sol", SpeedTier.ULTRAFAST, xai = true))
    }

    // ── Downgrade receipt ────────────────────────────────────────────────

    @Test
    fun `an echoed default tier is only logged, never shown as an account verdict`() {
        // [T-ultrafast-no-false-downgrade] A Pro user saw "doesn't support
        // Ultrafast" on every Astra reply: on ChatGPT auth the echoed
        // service_tier is not a reliable signal, so nothing may conclude from it.
        val vm = ProductionSources.read("ui/chat/ChatViewModel.kt")
        assertFalse(vm.contains("SpeedTierReceipts"))
        assertFalse(vm.contains("chat_speed_ultrafast_downgraded"))
        val provider = ProductionSources.read("provider/openai/OpenAIProvider.kt")
        assertFalse(provider.contains("SpeedTierReceipts"))
        assertTrue(provider.contains("(not verifiable for ChatGPT auth)"))
        assertFalse(provider.contains("ultrafast requested but response service_tier"))
        assertFalse(ProductionSources.read("data/FastModePrefs.kt").contains("object SpeedTierReceipts"))
    }

    @Test
    fun `without ultrafast the menu keeps the single fast mode switch`() {
        val screen = ProductionSources.read("ui/chat/ChatScreen.kt")
        val speedRow = screen.indexOf("if (showFastMode && showUltrafast) {")
        val switchRow = screen.indexOf("} else if (showFastMode) {")
        assertTrue(speedRow in 0 until switchRow)
        assertTrue(screen.substring(switchRow).substringBefore("Auto Compact").contains("R.string.chat_menu_fast_mode"))
        // [T-android-ultrafast-align] The tier row is named "Fast Mode", not "Speed".
        assertTrue(screen.substring(speedRow, switchRow).contains("R.string.chat_menu_fast_mode_tier"))
        assertFalse(screen.contains("R.string.chat_menu_speed)"))
    }

    // ── Unsupported-Ultrafast log line ───────────────────────────────────

    @Test
    fun `ultrafast chosen but not supported is logged in ios words`() {
        val declared = listOf("gpt-5.6-sol")
        val unsup = SpeedTiers.ultrafastUnsupportedNote(SpeedTier.ULTRAFAST, model("gpt-6.1-sol"), declared)
        assertEquals(
            "Ultrafast not supported for model=gpt-6.1-sol (whitelist=[gpt-6-astra], catalogue declares=[gpt-5.6-sol]); " +
                "sending service_tier=priority",
            unsup,
        )
        val wl = SpeedTiers.ultrafastUnsupportedNote(SpeedTier.ULTRAFAST, model("gpt-6-astra"), emptyList())
        assertEquals(
            "Ultrafast for model=gpt-6-astra comes from the fallback whitelist only; the account catalogue declares it " +
                "for [] - an account without the entitlement is likely served at Standard (service_tier=ultrafast)",
            wl,
        )
        // Declared by the catalogue (account-wide list or the model's own entry): nothing to report.
        assertNull(SpeedTiers.ultrafastUnsupportedNote(SpeedTier.ULTRAFAST, model("gpt-5.6-sol"), declared))
        assertNull(SpeedTiers.ultrafastUnsupportedNote(
            SpeedTier.ULTRAFAST, LLMModel("gpt-6-astra", "A", "OpenAI", serviceTiers = listOf("priority", "ultrafast")), emptyList(),
        ))
        // Another tier, or a non-gpt id (xAI has its own priority path).
        assertNull(SpeedTiers.ultrafastUnsupportedNote(SpeedTier.FAST, model("gpt-5.5"), declared))
        assertNull(SpeedTiers.ultrafastUnsupportedNote(SpeedTier.STANDARD, model("gpt-6.1-sol"), declared))
        assertNull(SpeedTiers.ultrafastUnsupportedNote(SpeedTier.ULTRAFAST, model("grok-4.20"), declared))
    }

    @Test
    fun `the responses builder logs the note next to the tier`() {
        val provider = ProductionSources.read("provider/openai/OpenAIProvider.kt")
        val tier = provider.indexOf(".effective(com.openminis.app.data.FastModePrefs.tier(), model)")
        val note = provider.indexOf(".ultrafastUnsupportedNote(com.openminis.app.data.FastModePrefs.tier(), model)")
        assertTrue(tier >= 0 && note > tier && note - tier < 800)
        assertTrue(provider.substring(note, note + 200).contains("\"[SpeedTier] \$it\""))
        val discovery = ProductionSources.read("provider/openai/CodexModelsApi.kt")
        assertTrue(discovery.split("recordUltrafast(context, ").size - 1 == 2)
    }
}
