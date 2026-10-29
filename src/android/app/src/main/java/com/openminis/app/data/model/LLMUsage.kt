package com.openminis.app.data.model

import kotlinx.serialization.Serializable

@Serializable
data class LLMUsage(
    val inputTokens: Int,
    val outputTokens: Int,
    val cacheCreationInputTokens: Int? = null,
    val cacheReadInputTokens: Int? = null,
    /**
     * The total input token count reported by the API for this call.
     * Represents how much of the context window has been consumed.
     * Used by dynamicMaxTokens() to compute remaining space.
     * Mirrors iOS's latestContextTokens.
     */
    val latestContextTokens: Int = 0,
    /**
     * [T-android-dsml-leak] Reasoning tokens inside [outputTokens], when the
     * provider reports them (Chat Completions
     * `completion_tokens_details.reasoning_tokens`). Null when not reported.
     */
    val reasoningTokens: Int? = null,
    /**
     * [T-usage-step1] Whether the response carried any cache field, even
     * with the value 0. Null when the parser does not know (hand-built
     * usage). Persisted so the Usage page can tell "no cache hit" (0%) from
     * "this provider never reports a cache" (em dash).
     */
    val cacheReported: Boolean? = null,
    /**
     * [T-usage-step1] The block this usage was parsed from, with absent
     * fields still null, so a stream consumer can merge field by field
     * (com.openminis.app.usage.UsageChunkMerger). Not serialized.
     */
    @kotlinx.serialization.Transient
    val chunk: com.openminis.app.usage.UsageChunk? = null,
)
