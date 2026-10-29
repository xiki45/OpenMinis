package com.openminis.app.data.model

import org.json.JSONObject

/**
 * Agent-loop stream event — a spec-complete superset of [LLMStreamChunk] that
 * carries tool invocations, thinking, reasoning, and structured stop reasons.
 * Mirrors iOS `AgentStreamEvent` (AgentProvider.swift).
 *
 * Provider implementations may surface events via [LLMStreamChunk] (plain chat)
 * or via this channel (tool-using agent loops); the two coexist by design so
 * non-agent sendMessage callers don't pay for agent-specific plumbing.
 */
sealed class AgentStreamEvent {
    /** A new content block started (text or tool use). */
    data class ContentBlockStart(val start: AgentBlockStart) : AgentStreamEvent()
    /** Incremental text for an in-flight text block. */
    data class TextDelta(val delta: String) : AgentStreamEvent()
    /** Accumulated JSON preview for an in-flight tool_use input. */
    data class ToolInputDelta(val name: String, val accumulated: String) : AgentStreamEvent()
    /** A tool_use block finished assembling; args is fully parsed JSON. */
    data class ToolCallComplete(
        val id: String,
        val name: String,
        val args: JSONObject,
        val metadata: ToolCallMetadata? = null,
    ) : AgentStreamEvent()
    /** Token accounting (typically once at stream end). */
    data class Usage(val usage: LLMUsage) : AgentStreamEvent()
    /** Real-time thinking/extended-reasoning delta. */
    data class ThinkingDelta(val delta: String) : AgentStreamEvent()
    /** Accumulated opaque reasoning (DeepSeek/Kimi reasoning_content). */
    data class ReasoningContent(val content: String) : AgentStreamEvent()
    /** Stream ended with a structured stop reason. */
    data class Done(val stopReason: AgentStopReason) : AgentStreamEvent()
}

sealed class AgentBlockStart {
    data object Text : AgentBlockStart()
    data class ToolUse(val id: String, val name: String) : AgentBlockStart()
}

enum class AgentStopReason { END_TURN, TOOL_USE, MAX_TOKENS }

data class ToolCallMetadata(
    /** Gemini thought signature echoed back on the next turn. */
    val thoughtSignature: String? = null,
)

/**
 * Agent-layer message type — content is a list of typed parts (text / tool_use /
 * tool_result / image) rather than a flat string. Mirrors iOS `AgentMessage`.
 *
 * [isInterrupted] flags a stream that was cancelled mid-flight so the agent
 * loop knows whether to inject placeholder tool_result blocks.
 * [reasoningContent] preserves opaque chain-of-thought for reasoning models
 * (Kimi/DeepSeek/QwQ) that must be echoed back on later turns.
 */
data class AgentMessage(
    val role: Role,
    var parts: List<AgentContentPart>,
    var isInterrupted: Boolean = false,
    var reasoningContent: String? = null,
    var dbMessageId: String? = null,
) {
    enum class Role(val value: String) {
        USER("user"),
        ASSISTANT("assistant"),
    }
}

/**
 * Normalize a tool-use id to the regex Anthropic accepts (`^[a-zA-Z0-9_-]+$`).
 * OpenAI Responses can emit ids like `call_abc|fc_def` which Anthropic rejects
 * on the follow-up turn, so every provider echoes ids through this filter.
 */
fun sanitizeToolId(id: String): String = buildString(id.length) {
    for (ch in id) {
        append(if (ch.isLetterOrDigit() || ch == '_' || ch == '-') ch else '-')
    }
}

/** [B02] Longest tool id any request carries (strict gateways such as DeepSeek). */
const val WIRE_TOOL_ID_MAX = 40

/**
 * [B02] The tool id a request carries for a stored id. Used only when a request
 * is encoded; the stored history keeps the raw id, so a session switched back to
 * the Responses API still splits `call_…|fc_…` into its two halves.
 *
 * Same rule as iOS `wireToolId`, pinned by src/shared/tool-id vectors:
 *  1. keep the part before the first `|` (the call_id);
 *  2. replace every code point outside `[A-Za-z0-9_-]` with `-`;
 *  3. if that is empty or longer than [WIRE_TOOL_ID_MAX], use `call_` + the first
 *     35 hex chars of SHA-256 of the WHOLE raw id (UTF-8), 40 chars in all.
 * Deterministic, so a tool_use and its tool_result, encoded separately, pair up.
 * Two raw ids can map to one wire id (same call_id, different fc_id); a request
 * tells them apart with [WireToolIdMap].
 */
fun wireToolId(raw: String): String {
    val head = raw.substringBefore('|')
    val clean = buildString(head.length) {
        var i = 0
        while (i < head.length) {
            val cp = head.codePointAt(i)
            val ok = cp in 'a'.code..'z'.code || cp in 'A'.code..'Z'.code ||
                cp in '0'.code..'9'.code || cp == '_'.code || cp == '-'.code
            append(if (ok) cp.toChar() else '-')
            i += Character.charCount(cp)
        }
    }
    if (clean.isNotEmpty() && clean.length <= WIRE_TOOL_ID_MAX) return clean
    val digest = java.security.MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
    val hex = digest.joinToString("") { "%02x".format(it) }
    return "call_" + hex.take(WIRE_TOOL_ID_MAX - "call_".length)
}

/**
 * [T-kimi-native-tool-id] Kimi's own tool-call id shape, `functions.<name>:<idx>`.
 * K2's chat template puts the id into the prompt verbatim and Moonshot's
 * tool-call guidance has the parser read the function name from it, so a
 * request to Kimi sends these ids unchanged instead of [wireToolId]'s
 * `functions-<name>-<idx>`. Anything else (a Responses-origin `call_…|fc_…`,
 * another provider's id) still goes through [wireToolId].
 */
private val KIMI_NATIVE_TOOL_ID = Regex("^functions\\.[A-Za-z0-9_-]+:[0-9]+$")

/** Longest Kimi-native id kept as is (tool names are at most 64 chars). */
const val KIMI_NATIVE_TOOL_ID_MAX = 80

fun isKimiNativeToolId(raw: String): Boolean =
    raw.length <= KIMI_NATIVE_TOOL_ID_MAX && KIMI_NATIVE_TOOL_ID.matches(raw)

/** [B02] [base] with `-n` appended, the base shortened so the whole stays within [WIRE_TOOL_ID_MAX]. */
fun wireToolIdWithSuffix(base: String, n: Int): String {
    val suffix = "-$n"
    return base.take((WIRE_TOOL_ID_MAX - suffix.length).coerceAtLeast(0)) + suffix
}

/**
 * [B02] The wire ids of one request. Each raw id gets [wireToolId]; when a
 * DIFFERENT raw id already holds that wire id in this request, it gets a `-N`
 * suffix instead, so calls that share a call_id stay distinct while a call and
 * its result (the same raw id) always get the same id. Order-independent per raw
 * id, deterministic for a given history.
 */
class WireToolIdMap(
    /** [T-kimi-native-tool-id] Raw ids sent unchanged (see [isKimiNativeToolId]). */
    private val keepAsIs: (String) -> Boolean = { false },
) {
    private val byRaw = HashMap<String, String>()
    private val taken = HashSet<String>()

    fun idFor(raw: String): String = byRaw.getOrPut(raw) {
        val base = if (keepAsIs(raw)) raw else wireToolId(raw)
        var id = base
        var n = 2
        while (!taken.add(id)) id = wireToolIdWithSuffix(base, n++)
        id
    }

    /** Raw ids whose wire id differs from the raw id (sanitised, shortened or renamed). */
    fun changed(): List<Pair<String, String>> = byRaw.filter { (raw, wire) -> raw != wire }.toList()
}

/**
 * [B02] Diagnostic line when a request rewrote tool ids: lengths and the
 * protocol only, never the ids (they can carry session details).
 */
fun logWireToolIds(protocol: String, ids: WireToolIdMap) {
    val changed = ids.changed()
    if (changed.isEmpty()) return
    android.util.Log.i(
        "ToolId",
        "[ToolId] protocol=$protocol rewritten=${changed.size} " +
            "rawLen=${changed.maxOf { it.first.length }} wireLen=${changed.maxOf { it.second.length }}",
    )
}
