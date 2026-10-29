package com.openminis.app.provider

import android.content.Context

/**
 * [T-sse-idle-timeout] The per-provider "response idle timeout": the longest
 * wait for a streamed reply to start, and the floor for the wait between its
 * pieces. It is NOT a limit on how long a reply may take - a stream that
 * keeps delivering never times out. Port of iOS ResponseIdleTimeoutSetting
 * (b15ac8aa3); same default, range, presets and per-device storage.
 *
 * What it changes on Android (OpenAI-compatible Chat Completions only):
 * - The time-to-first-byte budget, 120 s until now. A local model prefilling
 *   a long context can send nothing - not even response headers - for
 *   minutes; that read as a dead connection and was retried.
 * - The socket read timeout, 600 s until now, is raised to setting + 30 s
 *   when the setting is above it, so the transport never gives up first.
 *   It is never lowered: OkHttp's read timeout counts any bytes, so it is
 *   already the between-pieces limit, and long reasoning silences on some
 *   backends need the 600 s.
 * Unlike iOS there is no stream-event watchdog to feed: hidden reasoning
 * arrives as bytes and resets the read timeout already.
 *
 * Stored per device, keyed by provider instance id; nothing stored means the
 * default, so existing providers behave exactly as before. Not a
 * ProviderInstance field (it would need a DB column, sync and backup wiring
 * for a value about this device's link to one server), so it is not synced
 * and not in backups - as on iOS.
 */
object ResponseIdleTimeoutSetting {
    const val DEFAULT_SECONDS = 120
    const val MIN_SECONDS = 30
    /** No "never": a stream that really died would hold the turn, a session slot and the keep-alive forever. */
    const val MAX_SECONDS = 3600
    val PRESETS = listOf(120, 300, 600, 1800, 3600)

    /** The transport's own read timeout, which the setting only ever raises. */
    const val SOCKET_READ_DEFAULT_SECONDS = 600

    private const val PREFS = "provider_response_idle_timeout"

    fun clamp(seconds: Int): Int = seconds.coerceIn(MIN_SECONDS, MAX_SECONDS)

    fun key(instanceId: String): String = "provider.responseIdleTimeout.$instanceId"

    fun seconds(context: Context, instanceId: String?): Int {
        if (instanceId.isNullOrEmpty()) return DEFAULT_SECONDS
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!p.contains(key(instanceId))) return DEFAULT_SECONDS
        return clamp(p.getInt(key(instanceId), DEFAULT_SECONDS))
    }

    /** Storing the default removes the key, so "default" stays "never set". */
    fun set(context: Context, instanceId: String, seconds: Int?) {
        val e = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        if (seconds == null || clamp(seconds) == DEFAULT_SECONDS) e.remove(key(instanceId)) else e.putInt(key(instanceId), clamp(seconds))
        e.apply()
    }

    /** Socket read timeout a streaming request needs; null = leave the client's 600 s. */
    fun readTimeoutSeconds(idleSeconds: Int): Int? =
        if (idleSeconds > SOCKET_READ_DEFAULT_SECONDS) idleSeconds + 30 else null
}
