package com.openminis.app.usage

import com.openminis.app.data.model.LLMUsage
import org.json.JSONException
import org.json.JSONObject
import java.math.BigDecimal
import java.math.BigInteger

/**
 * [T-usage-step1] Token counts out of JSON, by the number rule of spec 4.2:
 * only a JSON number counts; a fraction truncates toward zero; negative and
 * above 2^53 - 1 become 0. A string or a boolean is NOT a number here,
 * although org.json's optLong would happily parse "300".
 */
object UsageNumbers {
    const val MAX_COUNT = 9_007_199_254_740_991L // 2^53 - 1

    /** The count for [v], or null when [v] is not a JSON number at all. */
    fun count(v: Any?): Long? = when (v) {
        is Int, is Long, is Short, is Byte -> (v as Number).toLong().let { if (it < 0 || it > MAX_COUNT) 0 else it }
        is BigInteger -> if (v.signum() < 0 || v > BigInteger.valueOf(MAX_COUNT)) 0 else v.toLong()
        is BigDecimal -> if (v.signum() < 0 || v > BigDecimal.valueOf(MAX_COUNT)) 0 else v.toLong()
        is Double, is Float -> (v as Number).toDouble().let { d ->
            // Range-check as a double first: 1e20.toLong() would saturate to Long.MAX_VALUE.
            if (d.isNaN() || d < 0 || d > MAX_COUNT.toDouble()) 0 else d.toLong()
        }
        else -> null
    }

    /** A provider field: present AND a valid number, else null (spec 2.1). */
    fun provider(o: JSONObject, key: String): Long? = if (o.has(key)) count(o.opt(key)) else null

    /** A stored field: the first PRESENT key wins; an invalid value reads 0, a missing one 0. */
    fun stored(o: JSONObject, vararg keys: String): Long {
        for (k in keys) if (o.has(k)) return count(o.opt(k)) ?: 0
        return 0
    }
}

/** [T-usage-step1] One parsed `messages.token_usage` value (spec 4.2). */
data class StoredUsage(
    val input: Long,
    val cacheRead: Long,
    val cacheWrite: Long,
    val output: Long,
    /** Null when the row did not record it; never above [output]. */
    val reasoning: Long?,
    /** Null on rows written before the key existed. */
    val cacheReported: Boolean?,
    /** Copied by a session duplicate/fork; global stats skip it (spec 5). */
    val copied: Boolean,
) {
    val total: Long get() = input + cacheRead + cacheWrite + output
}

sealed class StoredUsageParse {
    /** Column NULL or empty: not a usage row at all. */
    data object NoUsage : StoredUsageParse()
    /** Broken JSON or not an object: skipped and counted. */
    data object Invalid : StoredUsageParse()
    data class Valid(val usage: StoredUsage) : StoredUsageParse()
}

object StoredUsageJson {

    fun parse(text: String?): StoredUsageParse {
        if (text.isNullOrBlank()) return StoredUsageParse.NoUsage
        val o = try { JSONObject(text) } catch (_: JSONException) { return StoredUsageParse.Invalid }
        return StoredUsageParse.Valid(fromObject(o))
    }

    fun fromObject(o: JSONObject): StoredUsage {
        val output = UsageNumbers.stored(o, "outputTokens")
        return StoredUsage(
            input = UsageNumbers.stored(o, "inputTokens"),
            cacheRead = UsageNumbers.stored(o, "cacheReadTokens", "cacheReadInputTokens"),
            cacheWrite = UsageNumbers.stored(o, "cacheCreationTokens", "cacheCreationInputTokens"),
            output = output,
            reasoning = if (o.has("reasoningTokens")) minOf(UsageNumbers.count(o.opt("reasoningTokens")) ?: 0, output) else null,
            cacheReported = o.opt("cacheReported") as? Boolean,
            copied = o.opt("copied") == true,
        )
    }

    /**
     * The keys this step adds to a newly written usage row (spec 4.1), as a
     * `,"k":v` suffix for the hand-built JSON in ChatViewModel. Empty when the
     * usage carries neither.
     */
    fun extraFields(usage: LLMUsage): String = buildString {
        usage.reasoningTokens?.let { append(",\"reasoningTokens\":").append(it) }
        usage.cacheReported?.let { append(",\"cacheReported\":").append(it) }
    }

    /**
     * [usage] with `"copied":true` added, for a message copied into a
     * duplicated or forked session (spec 5). Anything that is not a JSON
     * object comes back unchanged: the stats skip it as invalid anyway.
     */
    fun markCopied(usage: String?): String? {
        if (usage.isNullOrBlank()) return usage
        val o = try { JSONObject(usage) } catch (_: JSONException) { return usage }
        if (o.opt("copied") == true) return usage
        return o.put("copied", true).toString()
    }
}
