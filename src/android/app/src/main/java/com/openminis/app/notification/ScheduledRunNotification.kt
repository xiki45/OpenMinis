package com.openminis.app.notification

import com.openminis.app.data.db.MessageHeadRow
import com.openminis.app.data.repository.ChatRepository
import com.openminis.app.scheduled.ScheduledTaskMarker

/**
 * [T-port-sched-notif-single] Which scheduled task opened the run that just
 * ended, so [BackgroundTaskNotifier] can give that task's runs one
 * notification per (chat, task) that each run replaces in place. Port of iOS
 * `BackgroundTaskNotificationID` / `scheduledJobIdOpeningCurrentTurn`
 * (b0c0c87ea).
 *
 * The run is identified from the transcript, as iOS does: a fire arrives as a
 * `<scheduled_task id="…">` envelope in the user row that opened the turn.
 * Rows are read newest-first and stop at the first turn-starting user row
 * (one with anything besides tool results), which is almost always within the
 * last few rows.
 */
internal object ScheduledRunNotification {

    /** Upper bound on rows read back to find the turn's opening user row. */
    const val MAX_SCAN_ROWS = 64

    /**
     * The notification tag for a run opened by task [taskId], or null for any
     * other completion. Posted with the chat's usual id, so the (tag, id) pair
     * is one entry per (chat, task): separate tasks in one chat, and the same
     * task id in another chat, keep their own entries, while an ordinary
     * completion keeps the untagged per-chat entry it always had.
     */
    fun tag(taskId: String?): String? = taskId?.let { "job-$it" }

    /**
     * The scheduled task id whose envelope opened the newest turn, or null.
     * [rowAt] returns the row at that offset counting back from the newest
     * (offset 0 = newest), or null past the oldest.
     */
    suspend fun taskIdOpeningTurn(rowAt: suspend (offset: Int) -> MessageHeadRow?): String? {
        for (offset in 0 until MAX_SCAN_ROWS) {
            val row = rowAt(offset) ?: return null
            if (row.role != "user") continue
            if (!ChatRepository.isTurnStartingUserParts(row.partsJson)) continue
            return taskIdIn(row.partsJson)
        }
        return null
    }

    /**
     * The task id of a scheduled envelope among [partsJson]'s text parts. The
     * envelope may sit next to other parts (e.g. a reminder); a user who
     * merely quotes an envelope mid-text is not a fire, since the marker must
     * open its part.
     */
    fun taskIdIn(partsJson: String): String? {
        val arr = runCatching { org.json.JSONArray(partsJson) }.getOrNull() ?: return null
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optString("type") != "text") continue
            val text = o.optString("value", "").trim()
            ScheduledTaskMarker.parse(text)?.let { return it.taskId }
        }
        return null
    }
}
