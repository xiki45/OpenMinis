package com.openminis.app.provider.thinking

import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.provider.thinking.ThinkingRuleResolver.GeminiThinkingFormat as F
import com.openminis.app.provider.thinking.ThinkingRuleResolver.GeminiVersion
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [OpenMinis#470] The level/budget choice is made by the Gemini MAJOR version,
 * not by `contains("gemini-3")`: gemini-4-argon used to get no thinking config
 * on Android (the user's level was ignored; iOS/Windows sent a budget and got a
 * 400 on every message). Table-driven over every tier, with the 2.5 rows and
 * the unversioned alias pinned as unchanged.
 */
class GeminiVersionFamilyTest {

    private val levels = ThinkingLevel.values().toList()

    private fun row(id: String): String = levels.joinToString(" ") { lv ->
        val c = ThinkingRuleResolver.geminiThinkingConfig(id, lv)
        "${lv.name}=${shape(c)}"
    }

    /** `low`, `low+t` (includeThoughts), `b128`, `b1024+t`, or `null`. */
    private fun shape(c: JSONObject?): String {
        if (c == null) return "null"
        val base = when {
            c.has("thinkingLevel") -> c.getString("thinkingLevel")
            c.has("thinkingBudget") -> "b${c.getInt("thinkingBudget")}"
            else -> "{}"
        }
        return if (c.optBoolean("includeThoughts")) "$base+t" else base
    }

    private val noMinimal =
        "OFF=low LOW=low+t MEDIUM=medium+t HIGH=high+t XHIGH=high+t MAX=high+t ULTRA=high+t"

    @Test
    fun `every tier of the issue's ids`() {
        val expected = linkedMapOf(
            "gemini-4-argon" to noMinimal,
            "gemini-4-argon-cyber-permissive" to noMinimal,
            "gemini-4-flash" to noMinimal,
            "gemini-5-pro" to noMinimal,
            "google/gemini-4-argon" to noMinimal,
            "models/gemini-4-argon" to noMinimal,
            "GEMINI-4-ARGON" to noMinimal,
            "gemini-3-8-flash" to noMinimal,
            "gemini-3.8-flash" to noMinimal,
            "gemini-3.6-flash" to
                "OFF=minimal LOW=low+t MEDIUM=medium+t HIGH=high+t XHIGH=high+t MAX=high+t ULTRA=high+t",
            // Only low and high exist on 3 Pro preview: Medium goes out as high.
            "gemini-3-pro-preview" to
                "OFF=low LOW=low+t MEDIUM=high+t HIGH=high+t XHIGH=high+t MAX=high+t ULTRA=high+t",
            "gemini-3.1-pro-preview" to noMinimal,
            // 2.5 regression rows: exactly what they were.
            "gemini-2.5-pro" to
                "OFF=b128 LOW=b2048+t MEDIUM=b8192+t HIGH=b16384+t XHIGH=b32768+t MAX=b32768+t ULTRA=b32768+t",
            "gemini-2.5-flash" to
                "OFF=b0 LOW=b1024+t MEDIUM=b4096+t HIGH=b8192+t XHIGH=b16384+t MAX=b16384+t ULTRA=b16384+t",
            "gemini-2.5-flash-lite" to
                "OFF=null LOW=null MEDIUM=null HIGH=null XHIGH=null MAX=null ULTRA=null",
            "gemini-flash-latest" to
                "OFF=null LOW=null MEDIUM=null HIGH=null XHIGH=null MAX=null ULTRA=null",
        )
        val actual = expected.keys.associateWith(::row)
        for ((id, want) in expected) assertEquals(id, want, actual[id])
    }

    @Test
    fun `level and budget are never sent together`() {
        val ids = listOf(
            "gemini-4-argon", "gemini-3.6-flash", "gemini-3-pro-preview", "gemini-2.5-pro", "gemini-2.5-flash",
        )
        for (id in ids) for (lv in levels) for (format in listOf(null) + ThinkingRuleResolver.GeminiThinkingFormat.values()) {
            val c = ThinkingRuleResolver.geminiThinkingConfig(id, lv, format) ?: continue
            assertFalse("$id $lv $format: $c", c.has("thinkingLevel") && c.has("thinkingBudget"))
        }
    }

    @Test
    fun `version parsing`() {
        assertEquals(GeminiVersion(4, null), ThinkingRuleResolver.geminiVersion("gemini-4-argon"))
        assertEquals(GeminiVersion(4, null), ThinkingRuleResolver.geminiVersion("google/gemini-4-argon"))
        assertEquals(GeminiVersion(3, 8), ThinkingRuleResolver.geminiVersion("gemini-3-8-flash"))
        assertEquals(GeminiVersion(3, 8), ThinkingRuleResolver.geminiVersion("gemini-3.8-flash"))
        assertEquals(GeminiVersion(2, 5), ThinkingRuleResolver.geminiVersion("gemini-2.5-flash"))
        assertEquals(GeminiVersion(3, null), ThinkingRuleResolver.geminiVersion("gemini-3-pro-preview"))
        assertNull(ThinkingRuleResolver.geminiVersion("gemini-flash-latest"))
        assertNull(ThinkingRuleResolver.geminiVersion("gemini-exp-1206"))
        assertFalse(ThinkingRuleResolver.isGeminiLevelFamily("gemini-2.5-pro"))
        assertFalse(ThinkingRuleResolver.isGeminiLevelFamily("gemini-flash-latest"))
    }

    /** The retry's forced shapes, for a level model and a budget model. */
    @Test
    fun `forced formats`() {
        fun c(id: String, lv: ThinkingLevel, format: ThinkingRuleResolver.GeminiThinkingFormat) =
            shape(ThinkingRuleResolver.geminiThinkingConfig(id, lv, format))
        assertEquals("high+t", c("gemini-2.5-flash", ThinkingLevel.HIGH, F.LEVEL))
        assertEquals("low", c("gemini-2.5-flash", ThinkingLevel.OFF, F.LEVEL))
        assertEquals("low", c("gemini-3.6-flash", ThinkingLevel.OFF, F.LEVEL_NO_MINIMAL))
        assertEquals("minimal", c("gemini-3.6-flash", ThinkingLevel.OFF, F.LEVEL))
        assertEquals("b8192+t", c("gemini-3.6-flash", ThinkingLevel.HIGH, F.BUDGET))
        assertEquals("b128", c("gemini-4-argon", ThinkingLevel.OFF, F.BUDGET))
        assertEquals("b0", c("gemini-2.5-flash", ThinkingLevel.OFF, F.BUDGET))
        assertEquals("high+t", c("unknown-gemini-model", ThinkingLevel.HIGH, F.LEVEL))
        // The no-thinking models stay without a config whatever the shape.
        assertEquals("null", c("gemini-2.5-flash-lite", ThinkingLevel.HIGH, F.LEVEL))
        assertEquals("null", c("gemini-3.1-flash-tts-preview", ThinkingLevel.HIGH, F.BUDGET))
    }
}
