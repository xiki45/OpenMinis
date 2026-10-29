package com.openminis.app.provider.gemini

import com.openminis.app.provider.thinking.ThinkingRuleResolver.GeminiThinkingFormat
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * [OpenMinis#470] The safety net behind the version rule in
 * `ThinkingRuleResolver.geminiThinkingConfig`: when Google answers 400 because of
 * the thinking parameter itself, the request is sent ONCE more in the other
 * shape, and the shape that worked is remembered for this (provider instance,
 * model) for the rest of the process.
 *
 * Covers ids the version rule cannot judge (relay renames, future families) and
 * Google changing what a model accepts. Matching is on the English error text,
 * which relays such as CLIProxyAPI pass through unchanged.
 */
object GeminiThinkingRetry {

    private val preferred = ConcurrentHashMap<String, GeminiThinkingFormat>()

    private fun key(instance: String, modelId: String) = "$instance\u0000$modelId"

    /** The shape that worked last time for this instance and model, if any. */
    fun preferred(instance: String, modelId: String): GeminiThinkingFormat? =
        preferred[key(instance, modelId)]

    fun remember(instance: String, modelId: String, format: GeminiThinkingFormat) {
        preferred[key(instance, modelId)] = format
    }

    /** Tests only. */
    internal fun clear() = preferred.clear()

    /**
     * The shape to retry in for a 400 whose body is [errorBody], or null when the
     * 400 is not about the thinking parameter. Callers retry only when the new
     * shape actually changes the thinkingConfig they sent.
     *
     * The MINIMAL check comes first: "Thinking level MINIMAL is not supported"
     * also contains "thinking level" + "not supported", and switching that model
     * to a budget would be wrong — it takes levels, just not that one.
     */
    fun formatFor(errorBody: String): GeminiThinkingFormat? {
        val text = errorBody.lowercase()
        return when {
            "thinking_level_minimal" in text || "minimal is not supported" in text ->
                GeminiThinkingFormat.LEVEL_NO_MINIMAL
            "thinking budget" in text &&
                ("not supported" in text || "use thinking level" in text || "deprecated" in text) ->
                GeminiThinkingFormat.LEVEL
            "thinking level" in text && "not supported" in text -> GeminiThinkingFormat.BUDGET
            else -> null
        }
    }

    /** `generationConfig.thinkingConfig` of a request body, for comparing two bodies. */
    fun thinkingConfigOf(body: JSONObject): String? =
        body.optJSONObject("generationConfig")?.optJSONObject("thinkingConfig")?.toString()

    /** "level=low" / "budget=128" for the log line: the shape only, no request content. */
    fun describe(config: String?): String {
        val c = config?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return "none"
        return when {
            c.has("thinkingLevel") -> "level=${c.optString("thinkingLevel")}"
            c.has("thinkingBudget") -> "budget=${c.optInt("thinkingBudget")}"
            else -> "empty"
        }
    }
}
