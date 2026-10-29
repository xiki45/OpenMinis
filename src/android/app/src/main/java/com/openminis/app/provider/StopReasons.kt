package com.openminis.app.provider

/**
 * [T-android-model-refusal] Terminal stop reasons that mean "the model DECLINED
 * this request". Port of iOS `AgentStopReason.refusal` (54c8ea2a4 + the OpenAI
 * mappings in OpenAIAgentProvider).
 *
 * Android carries a turn's stop reason as the provider's own string
 * ([LLMStreamChunk.Finished.stopReason]), so this is the one place that knows
 * which spellings are a refusal:
 *   - Anthropic `stop_reason: "refusal"` — the safety classifier declined the
 *     request: HTTP 200, input tokens billed, EMPTY content. Seen as a false
 *     positive on ordinary agent turns (large system prompt + tool set) on
 *     Claude Fable 5 / Sonnet 5.5.
 *   - OpenAI `finish_reason: "content_filter"` (Chat Completions), and the
 *     Responses API's `response.incomplete` with
 *     `incomplete_details.reason = "content_filter"` — the safety system
 *     blocked the response.
 *
 * A refusal is deterministic: resending the identical request is declined
 * again. It must not go through the empty-turn nudge / retry path, and it
 * must not end the run silently — the agent loop surfaces it on the reply.
 */
object StopReasons {
    const val REFUSAL = "refusal"
    const val CONTENT_FILTER = "content_filter"

    fun isRefusal(stopReason: String?): Boolean =
        stopReason == REFUSAL || stopReason == CONTENT_FILTER

    /**
     * [T-android-max-tokens-notice] The reply hit the output-token limit. Port
     * of iOS `AgentStopReason.maxTokens`. Spellings that reach the agent loop:
     *   - OpenAI Chat Completions `finish_reason: "length"` (the Responses
     *     path maps `incomplete_details.reason = "max_output_tokens"` to
     *     "length" too, OpenAIProvider);
     *   - Anthropic `stop_reason: "max_tokens"`;
     *   - Gemini `finishReason: MAX_TOKENS`, mapped to "max_tokens"
     *     (GeminiProvider).
     * "max_output_tokens" is accepted as well, in case a path forwards the
     * Responses reason unmapped.
     */
    fun isMaxTokens(stopReason: String?): Boolean =
        stopReason == "length" || stopReason == "max_tokens" || stopReason == "max_output_tokens"

    /**
     * [FIX-261018-12] The error shown for a refusal. Port of iOS
     * `AIChatViewModel.refusalErrorText`: with the provider's category
     * (`categoryMessage`, already formatted) it names the policy and says
     * resending will not help; otherwise the generic text. The provider's own
     * explanation (English, from the API) follows after a blank line.
     */
    fun refusalErrorText(categoryMessage: String?, genericMessage: String, explanation: String?): String {
        val base = categoryMessage?.takeIf { it.isNotBlank() } ?: genericMessage
        val detail = explanation?.trim()?.takeIf { it.isNotEmpty() } ?: return base
        return base + "\n\n" + detail
    }
}
