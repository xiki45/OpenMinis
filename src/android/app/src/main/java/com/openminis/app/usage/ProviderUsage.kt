package com.openminis.app.usage

import com.openminis.app.data.model.LLMUsage
import org.json.JSONObject

/**
 * [T-usage-step1] One provider usage block normalized to the five internal
 * fields of src/shared/usage/USAGE_METRICS_SPEC.md (spec 1.1). A null field
 * means the block did not carry it, which is what lets [UsageChunkMerger]
 * leave it alone instead of zeroing it.
 */
data class UsageChunk(
    /** Fresh input, cache excluded. */
    val input: Long? = null,
    val cacheRead: Long? = null,
    val cacheWrite: Long? = null,
    /** Output, reasoning included. */
    val output: Long? = null,
    /** Subset of [output]; never added to a total. */
    val reasoning: Long? = null,
    /** Any cache key was present in this block, even with the value 0. */
    val cacheKeyPresent: Boolean = false,
    /** Context size of the request as the provider reports it (not summed). */
    val contextTokens: Long? = null,
) {
    /**
     * The legacy [LLMUsage] shape. Cache counts of 0 stay null there, as every
     * existing consumer expects; [LLMUsage.cacheReported] carries the
     * difference between "reported 0" and "not reported".
     */
    fun toLLMUsage(): LLMUsage = LLMUsage(
        inputTokens = (input ?: 0).toIntSaturated(),
        outputTokens = (output ?: 0).toIntSaturated(),
        cacheCreationInputTokens = cacheWrite?.takeIf { it > 0 }?.toIntSaturated(),
        cacheReadInputTokens = cacheRead?.takeIf { it > 0 }?.toIntSaturated(),
        latestContextTokens = (contextTokens ?: 0).toIntSaturated(),
        reasoningTokens = reasoning?.toIntSaturated(),
        cacheReported = cacheKeyPresent,
        chunk = this,
    )
}

private fun Long.toIntSaturated(): Int = coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()

/**
 * [T-usage-step1] Provider usage JSON -> [UsageChunk], one function per wire
 * protocol (spec 2.1). Every relay that speaks a protocol goes through the
 * same function; provider-specific keys (DeepSeek, Kimi, OpenRouter) are read
 * by the OpenAI Chat parser whenever they appear.
 */
object ProviderUsageParser {

    /** Anthropic Messages: input_tokens already excludes the cache. */
    fun anthropic(u: JSONObject): UsageChunk {
        val cacheWrite = UsageNumbers.provider(u, "cache_creation_input_tokens")
        val cacheRead = UsageNumbers.provider(u, "cache_read_input_tokens")
        val input = UsageNumbers.provider(u, "input_tokens")
        return UsageChunk(
            input = input,
            cacheRead = cacheRead,
            cacheWrite = cacheWrite,
            output = UsageNumbers.provider(u, "output_tokens"),
            reasoning = null,
            cacheKeyPresent = cacheRead != null || cacheWrite != null,
            contextTokens = input,
        )
    }

    /**
     * OpenAI Chat Completions and every compatible relay. prompt_tokens is the
     * FULL input. The cache read is the largest of the keys present (OpenAI
     * prompt_tokens_details.cached_tokens, DeepSeek prompt_cache_hit_tokens,
     * Moonshot top-level cached_tokens): taking the first present key let a
     * reported 0 hide a real DeepSeek hit. The cache write (OpenRouter
     * cache_write_tokens, relays' cache_creation_input_tokens) is treated as
     * part of prompt_tokens; when subtracting it would go negative it is
     * assumed to be reported separately, and when even the read would go
     * negative nothing is subtracted (the pre-existing guard).
     */
    fun openAIChat(u: JSONObject): UsageChunk {
        val details = u.optJSONObject("prompt_tokens_details")
        val cacheRead = maxPresent(
            details?.let { UsageNumbers.provider(it, "cached_tokens") },
            UsageNumbers.provider(u, "prompt_cache_hit_tokens"),
            UsageNumbers.provider(u, "cached_tokens"),
        )
        val cacheWrite = maxPresent(
            details?.let { UsageNumbers.provider(it, "cache_write_tokens") },
            details?.let { UsageNumbers.provider(it, "cache_creation_input_tokens") },
        )
        val prompt = UsageNumbers.provider(u, "prompt_tokens")
        val cr = cacheRead ?: 0
        val cw = cacheWrite ?: 0
        val input = prompt?.let {
            when {
                it - cr - cw >= 0 -> it - cr - cw
                it - cr >= 0 -> it - cr
                else -> it
            }
        }
        return UsageChunk(
            input = input,
            cacheRead = cacheRead,
            cacheWrite = cacheWrite,
            output = UsageNumbers.provider(u, "completion_tokens"),
            reasoning = u.optJSONObject("completion_tokens_details")?.let { UsageNumbers.provider(it, "reasoning_tokens") },
            cacheKeyPresent = cacheRead != null || cacheWrite != null,
            contextTokens = prompt,
        )
    }

    /** OpenAI Responses API / Codex: input_tokens includes the cached part. */
    fun openAIResponses(u: JSONObject): UsageChunk {
        val total = UsageNumbers.provider(u, "input_tokens")
        val cacheRead = u.optJSONObject("input_tokens_details")?.let { UsageNumbers.provider(it, "cached_tokens") }
        val input = total?.let { t -> cacheRead?.let { (t - it).takeIf { d -> d >= 0 } } ?: t }
        return UsageChunk(
            input = input,
            cacheRead = cacheRead,
            cacheWrite = null,
            output = UsageNumbers.provider(u, "output_tokens"),
            reasoning = u.optJSONObject("output_tokens_details")?.let { UsageNumbers.provider(it, "reasoning_tokens") },
            cacheKeyPresent = cacheRead != null,
            contextTokens = total,
        )
    }

    /**
     * Gemini usageMetadata: promptTokenCount includes cachedContentTokenCount
     * (clamped so a relay's over-large cache never yields a negative input);
     * thinking is billed separately in thoughtsTokenCount and counted as output.
     */
    fun gemini(u: JSONObject): UsageChunk {
        val prompt = UsageNumbers.provider(u, "promptTokenCount")
        val cached = UsageNumbers.provider(u, "cachedContentTokenCount")
        val candidates = UsageNumbers.provider(u, "candidatesTokenCount")
        val thoughts = UsageNumbers.provider(u, "thoughtsTokenCount")
        return UsageChunk(
            input = prompt?.let { (it - (cached ?: 0)).coerceAtLeast(0) },
            cacheRead = cached,
            cacheWrite = null,
            output = if (candidates == null && thoughts == null) null else (candidates ?: 0) + (thoughts ?: 0),
            reasoning = thoughts,
            cacheKeyPresent = cached != null,
            contextTokens = prompt,
        )
    }

    private fun maxPresent(vararg values: Long?): Long? = values.filterNotNull().maxOrNull()
}

/**
 * [T-usage-step1] Folds the usage blocks of ONE request (spec 3.1). A field
 * changes only when a block carries it, and then takes the max: Anthropic's
 * message_delta counts are cumulative and may omit input and cache entirely,
 * relays may send 0 placeholders, and many OpenAI-compatible servers repeat
 * cumulative usage on every chunk. The GH#384 exception: when a block newly
 * reports (or raises) the cache read, its input describes the same prompt
 * split differently and replaces the earlier one instead of competing.
 */
object UsageChunkMerger {

    fun merge(state: UsageChunk?, c: UsageChunk): UsageChunk {
        if (state == null) return c
        val newly = c.cacheRead != null && c.cacheRead > (state.cacheRead ?: 0)
        return UsageChunk(
            input = when {
                c.input == null -> state.input
                newly -> c.input
                else -> maxOf(state.input ?: 0, c.input)
            },
            cacheRead = maxField(state.cacheRead, c.cacheRead),
            cacheWrite = maxField(state.cacheWrite, c.cacheWrite),
            output = maxField(state.output, c.output),
            reasoning = maxField(state.reasoning, c.reasoning),
            cacheKeyPresent = state.cacheKeyPresent || c.cacheKeyPresent,
            // Deliberately the LATEST block's value, as before the merge existed:
            // Anthropic's context is input_tokens alone (cache excluded), and
            // carrying message_start's value past a delta-only block would start
            // persisting it, where ContextPolicy.calibrationSample reads it as a
            // context-size sample. That convention is a separate fix (design D7).
            contextTokens = c.contextTokens,
        )
    }

    /**
     * Merge for stream consumers that hold [LLMUsage]. A usage without a
     * [LLMUsage.chunk] (built by hand, e.g. a test or a non-streaming path)
     * replaces the previous value, which is the behaviour before this merge.
     */
    fun merge(state: LLMUsage?, next: LLMUsage): LLMUsage {
        val nextChunk = next.chunk ?: return next
        val stateChunk = state?.chunk ?: return next
        return merge(stateChunk, nextChunk).toLLMUsage()
    }

    private fun maxField(a: Long?, b: Long?): Long? = if (b == null) a else maxOf(a ?: 0, b)
}
