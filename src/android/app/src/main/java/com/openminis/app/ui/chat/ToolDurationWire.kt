package com.openminis.app.ui.chat

import org.json.JSONObject

/**
 * [T-tool-duration-persist] A tool call's execution time, persisted with its
 * result in the iOS wire shape so it survives a reload and crosses platforms.
 *
 * iOS stores it as `ToolResult.snapshot.duration` (`ToolSnapshot.duration:
 * TimeInterval?`, SECONDS, a Double) and restores `block.toolDuration` from it
 * on reload (T-tool-duration-restore). Android's toolResult part already
 * writes iOS's `snapshot` object, so the time goes in the same object under
 * the same key and unit. Backup and sync copy the parts JSON verbatim, so an
 * iOS row's duration reaches Android and Android's reaches iOS.
 *
 * In memory Android keeps milliseconds (`AssistantBlock.durationMs`); only the
 * wire is seconds. A missing, null or non-positive value means "not recorded"
 * (rows written before this, cancelled or blocked calls) and restores as 0,
 * which shows no badge: a time is never invented.
 */
internal object ToolDurationWire {
    /** Key inside `toolResult.value.snapshot`, verbatim from iOS ToolSnapshot. */
    const val KEY = "duration"

    /** `,"duration":<seconds>` to append inside the snapshot object, or "" when unknown. */
    fun snapshotField(durationMs: Long?): String =
        if (durationMs == null || durationMs <= 0) "" else ",\"$KEY\":${durationMs / 1000.0}"

    /** Milliseconds from a toolResult part's `value`; 0 when not recorded. */
    fun restoreMs(value: JSONObject?): Long {
        val snapshot = value?.optJSONObject("snapshot") ?: return 0L
        if (!snapshot.has(KEY) || snapshot.isNull(KEY)) return 0L
        val seconds = snapshot.optDouble(KEY, Double.NaN)
        if (seconds.isNaN() || seconds.isInfinite() || seconds <= 0) return 0L
        return Math.round(seconds * 1000)
    }
}
