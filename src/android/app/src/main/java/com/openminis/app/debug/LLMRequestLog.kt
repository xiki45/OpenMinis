package com.openminis.app.debug

import com.openminis.app.BuildConfig
import org.json.JSONArray
import org.json.JSONObject

/**
 * Ring buffer that captures recent LLM API requests and responses for debugging.
 * Accessible via debug.llmRequests JSON-RPC method.
 */
object LLMRequestLog {

    private const val MAX_ENTRIES = 20

    /**
     * T302: cap each retained `requestBody` at this many characters. Real
     * agent-loop request bodies can run 30+ MB (full message history with
     * heavy tool outputs); pinning 20 of those in a ring buffer was the
     * direct cause of an OOM crash on a HONOR PTP-AN00 — the JVM heap
     * blew past target footprint trying to hold ~600 MB of transient
     * debug strings. The truncated body is still useful for diagnosing
     * cache-prefix stability and small request shape.
     */
    private const val MAX_BODY_CHARS = 64 * 1024

    data class Entry(
        val provider: String,
        val timestamp: Long = System.currentTimeMillis(),
        val requestURL: String,
        val requestMethod: String = "POST",
        val requestHeaders: Map<String, String>,
        val requestBody: String,      // JSON string — capped at MAX_BODY_CHARS by add()
        val durationMs: Long = 0,
        val responseStatusCode: Int = 0,
        val responseBody: String = "", // first 2000 chars
        val usage: JSONObject? = null,
    )

    private val entries = mutableListOf<Entry>()

    /**
     * The newest request body, untruncated - one slot only, so the T302 heap
     * bound stays at one body. Checking what a request really replays (the
     * reasoning and tool calls of every past turn) needs the tail, which the
     * 64 KB cap cuts off. Served by debug.llmRequests {fullLast:true}.
     */
    private var lastFullBody: String? = null
    private var lastFullTimestamp: Long = 0

    @Synchronized
    fun add(entry: Entry) {
        // T302: belt-and-suspenders. Release builds short-circuit the whole
        // log because the only consumer is the debug.llmRequests JSON-RPC
        // surface, which release users never reach — keeping a 20-deep ring
        // buffer of multi-MB request bodies hot for nobody is exactly the
        // OOM the HONOR PTP-AN00 user hit. Debug builds still record, with
        // each entry truncated to MAX_BODY_CHARS so even a single retained
        // entry can't push a memory-tight device over the line.
        if (!BuildConfig.DEV_TOOLS) return
        // [T-android-llmlog-redact] Never retain credentials: the ring is
        // served by debug.llmRequests, which a LAN client reaches with only
        // the debug-server token, and callers hand over the raw request
        // header map (Authorization: Bearer <key>, x-api-key). Redact here,
        // once, so every current and future caller is covered.
        val redacted = entry.copy(
            requestHeaders = redactHeaders(entry.requestHeaders),
            requestURL = redactUrl(entry.requestURL),
        )
        lastFullBody = redacted.requestBody
        lastFullTimestamp = redacted.timestamp
        val safeEntry = if (redacted.requestBody.length > MAX_BODY_CHARS) {
            redacted.copy(
                requestBody = redacted.requestBody.take(MAX_BODY_CHARS) +
                    "\n…[truncated ${redacted.requestBody.length - MAX_BODY_CHARS} chars]",
            )
        } else redacted
        entries.add(safeEntry)
        if (entries.size > MAX_ENTRIES) {
            entries.removeAt(0)
        }
    }

    /** Header names whose value is a credential (compared case-insensitively). */
    private val SECRET_HEADERS = setOf(
        "authorization", "proxy-authorization", "x-api-key", "api-key",
        "x-goog-api-key", "cookie", "set-cookie",
    )

    /** Query parameters that carry an API key (e.g. Gemini's `?key=`). */
    private val SECRET_QUERY_PARAMS = setOf("key", "api_key", "apikey", "access_token", "token")

    /**
     * Credential headers become `***`; an `Authorization` value keeps its
     * scheme (`Bearer ***`, `Basic ***`) like iOS, so the shape of the request
     * stays visible. Every other header, including `x-opencode-session`, is
     * kept as is.
     */
    fun redactHeaders(headers: Map<String, String>): Map<String, String> =
        headers.mapValues { (name, value) ->
            val lower = name.lowercase()
            val trimmed = value.trim()
            when {
                lower !in SECRET_HEADERS -> value
                (lower == "authorization" || lower == "proxy-authorization") && ' ' in trimmed ->
                    "${trimmed.substringBefore(' ')} ***"
                else -> "***"
            }
        }

    /** Replaces the value of key-carrying query parameters with `***`. */
    fun redactUrl(url: String): String {
        val q = url.indexOf('?')
        if (q < 0) return url
        val fragmentAt = url.indexOf('#', q).let { if (it < 0) url.length else it }
        val query = url.substring(q + 1, fragmentAt).split('&').joinToString("&") { pair ->
            val name = pair.substringBefore('=')
            if ('=' in pair && name.lowercase() in SECRET_QUERY_PARAMS) "$name=***" else pair
        }
        return url.substring(0, q + 1) + query + url.substring(fragmentAt)
    }

    @Synchronized
    fun getAll(): List<Entry> = entries.toList()

    @Synchronized
    fun getLast(n: Int): List<Entry> = entries.takeLast(n)

    @Synchronized
    fun clear() {
        entries.clear()
        lastFullBody = null
    }

    /** {timestamp, length, requestBody} of the newest request, untruncated; null when none. */
    @Synchronized
    fun lastFullJSON(): JSONObject? {
        val body = lastFullBody ?: return null
        return JSONObject().apply {
            put("timestamp", lastFullTimestamp)
            put("length", body.length)
            put("requestBody", body)
        }
    }

    fun toJSON(last: Int? = null): JSONObject {
        val list = if (last != null) getLast(last) else getAll()
        val array = JSONArray()
        for (entry in list) {
            array.put(JSONObject().apply {
                put("provider", entry.provider)
                put("timestamp", entry.timestamp)
                put("requestURL", entry.requestURL)
                put("requestMethod", entry.requestMethod)
                put("requestHeaders", JSONObject(entry.requestHeaders))
                put("requestBody", try { JSONObject(entry.requestBody) } catch (_: Exception) { entry.requestBody })
                put("durationMs", entry.durationMs)
                put("responseStatusCode", entry.responseStatusCode)
                if (entry.responseBody.isNotEmpty()) {
                    put("responseBody", entry.responseBody)
                }
                if (entry.usage != null) {
                    put("usage", entry.usage)
                }
            })
        }
        return JSONObject().apply {
            put("count", list.size)
            put("requests", array)
        }
    }
}
