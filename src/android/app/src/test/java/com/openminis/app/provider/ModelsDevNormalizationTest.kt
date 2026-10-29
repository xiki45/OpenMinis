package com.openminis.app.provider

import com.openminis.app.ProductionSources
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.ThinkingLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-modelsdev-id-normalization] A relay model id such as
 * `code-buddy/deepseek-v4.1-flash` matched no models.dev entry on Android, so
 * the model fell back to the id heuristic: a 128K window (compaction at 108K),
 * no output cap, no effort tiers (the picker offered xhigh, which DeepSeek does
 * not accept). iOS resolves it through normalization + a majority vote
 * (ModelsDevAPI.swift:127-132, buildStage2Index); Android now does the same.
 */
class ModelsDevNormalizationTest {

    // -- normalization --

    @Test
    fun `namespace, case and version separators normalize to one key`() {
        val key = "deepseek-v4-1-flash"
        for (id in listOf(
            "code-buddy/deepseek-v4.1-flash",
            "Code-Buddy/DeepSeek-V4.1-Flash",
            "deepseek-ai/DeepSeek-V4.1-Flash",
            "deepinfra/deepseek-ai/DeepSeek-V4.1-Flash",
            "code-buddy/deepseek_v4.1_flash",
            "code-buddy/deepseek-v4-1-flash",
            "deepseek-v4.1-flash",
        )) assertEquals(id, key, ModelsDevApi.normalizedModelKey(id))
    }

    @Test
    fun `distinct models stay distinct`() {
        val flash41 = ModelsDevApi.normalizedModelKey("code-buddy/deepseek-v4.1-flash")
        assertNotEquals(flash41, ModelsDevApi.normalizedModelKey("deepseek-v4-flash"))
        assertNotEquals(flash41, ModelsDevApi.normalizedModelKey("deepseek-v4.1-pro"))
        assertNotEquals(ModelsDevApi.normalizedModelKey("glm-5.2"), ModelsDevApi.normalizedModelKey("glm-5.1"))
    }

    // -- the majority vote --

    private fun entry(id: String, ctx: Int?, efforts: List<String>?) = ModelsDevApi.ModelDevEntry(
        id = id, name = null, family = null, contextWindow = ctx, maxOutputTokens = null,
        reasoning = efforts != null, interleavedField = null, inputModalities = null,
        outputModalities = null, reasoningEffortValues = efforts, declaresNoEffortTiers = false,
        releaseDate = null, outputCost = null,
    )

    private fun provider(key: String, vararg models: ModelsDevApi.ModelDevEntry) =
        key to ModelsDevApi.ProviderEntry(id = key, name = key, api = null, models = models.associateBy { it.id })

    @Test
    fun `the most commonly declared effort set wins, first seen on a tie`() {
        val reg = mapOf(
            provider("a-outlier", entry("model-x-1", 1, listOf("none", "minimal", "low", "medium", "high", "xhigh", "max"))),
            provider("b", entry("vendor/model-x.1", 2, listOf("low", "high", "max"))),
            provider("c", entry("Model-X-1", 3, listOf("low", "high", "max"))),
            provider("d", entry("model_x_1", 4, null)),
        )
        val win = ModelsDevApi.buildStage2Index(reg)["model-x-1"]!!
        assertEquals(listOf("low", "high", "max"), win.reasoningEffortValues)
        assertEquals("first declaring entry with the winning set", 2, win.contextWindow)

        val tie = mapOf(
            provider("a", entry("m-y-1", 10, listOf("high", "max"))),
            provider("b", entry("m-y-1", 20, listOf("low", "high"))),
        )
        assertEquals(10, ModelsDevApi.buildStage2Index(tie)["m-y-1"]!!.contextWindow)

        val silent = mapOf(provider("a", entry("m-z-1", 5, null)), provider("b", entry("m-z-1", 6, null)))
        assertEquals("nobody declares: first entry", 5, ModelsDevApi.buildStage2Index(silent)["m-z-1"]!!.contextWindow)
    }

    @Test
    fun `a house suffix reaches its family at a segment boundary`() {
        val index = mapOf("glm-5-3-flash" to entry("glm-5.3-flash", 128_000, null))
        assertEquals(128_000, ModelsDevApi.prefixMatch("glm-5-3-flash-cpa", index)!!.contextWindow)
        assertNull("not a segment boundary", ModelsDevApi.prefixMatch("glm-5-3-flashy", index))
        assertNull("too short to shorten", ModelsDevApi.prefixMatch("glm-5", index))
        assertEquals(listOf("a-b-c", "a-b"), ModelsDevApi.normalizedPrefixes("a-b-c"))
    }

    @Test
    fun `the model's own provider still wins over the vote`() {
        val reg = mapOf(
            provider("openai", entry("gpt-x-1", 400_000, listOf("low", "high"))),
            provider("r1", entry("gpt-x-1", 1, listOf("max"))),
            provider("r2", entry("gpt-x-1", 2, listOf("max"))),
        )
        assertEquals(400_000, ModelsDevApi.resolveDevEntry(reg, "OpenAI", "gpt-x-1")!!.contextWindow)
        assertEquals("relay: vote", 1, ModelsDevApi.resolveDevEntry(reg, "Custom", "relay/GPT_X.1")!!.contextWindow)
    }

    // -- against the bundled catalog --

    private val registry by lazy {
        val assets = java.io.File(ProductionSources.mainRoot()!!.parentFile.parentFile.parentFile.parentFile, "assets")
        ModelsDevApi.parseRegistry(java.io.File(assets, "models-dev-api.json").readText())!!
    }

    @Test
    fun `the reported relay id resolves to DeepSeek V4_1 Flash in the shipped catalog`() {
        for (id in listOf(
            "code-buddy/deepseek-v4.1-flash",
            "CODE-BUDDY/DeepSeek-V4.1-Flash",
            "code-buddy/deepseek_v4.1_flash",
            "code-buddy/deepseek-v4-1-flash",
        )) {
            val hit = ModelsDevApi.resolveDevEntry(registry, "Custom", id)
            assertNotNull(id, hit)
            assertEquals(id, 1_000_000, hit!!.contextWindow)
            assertEquals(id, 384_000, hit.maxOutputTokens)
            assertEquals(id, listOf("low", "high", "max"), hit.reasoningEffortValues)
        }
    }

    @Test
    fun `enriched, the model offers no xhigh and leaves the 128K heuristic`() {
        val bare = LLMModel("code-buddy/deepseek-v4.1-flash", "deepseek-v4.1-flash", "Custom")
        // The field value: no catalog match -> LLMModel.contextWindowTokens heuristic.
        assertEquals(128_000, bare.contextWindowTokens)
        assertTrue("no tiers declared -> no data-driven narrowing", bare.selectableThinkingLevels.isEmpty())

        val hit = ModelsDevApi.resolveDevEntry(registry, "Custom", bare.id)!!
        val enriched = bare.copy(
            contextWindow = hit.contextWindow,
            maxOutputTokens = hit.maxOutputTokens,
            reasoningEffortValues = hit.reasoningEffortValues,
        )
        assertEquals(1_000_000, enriched.contextWindowTokens)
        assertEquals(listOf(ThinkingLevel.LOW, ThinkingLevel.HIGH, ThinkingLevel.MAX), enriched.selectableThinkingLevels)
        assertFalse(ThinkingLevel.XHIGH in enriched.selectableThinkingLevels)
    }

    @Test
    fun `ids that resolved before still resolve`() {
        assertNotNull(ModelsDevApi.resolveDevEntry(registry, "Custom", "deepseek-v4.1-flash"))
        assertNotNull(ModelsDevApi.resolveDevEntry(registry, "OpenRouter", "deepseek/deepseek-v4.1-flash"))
        assertNull(ModelsDevApi.resolveDevEntry(registry, "OpenAI", "definitely-not-a-model-xyz"))
    }

    // -- [T-android-maxtokens-stale-log] --

    @Test
    fun `max_tokens is sized from the request being sent, not from the previous turn`() {
        val vm = ProductionSources.read("ui/chat/ChatViewModel.kt")
        assertFalse(vm.contains("val maxTokens = dynamicMaxTokens(provider, lastContextTokens)"))
        assertTrue(vm.contains("loopSystemPrompt, dynamicMaxTokens(currentProvider, dispatchInputTokens),"))
    }
}
