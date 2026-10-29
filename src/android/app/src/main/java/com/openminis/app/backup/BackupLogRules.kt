package com.openminis.app.backup

/**
 * [T-android-backup-progress-log] How a run's log grows — the rules of iOS
 * `BackupHistory.log(_:_:isProblem:isTransient:)`.
 *
 * A backup to a local (SAF) folder reported progress once per 8 KB buffer and
 * every report became a log line: a 2.13 GB package left 16,827 lines after 13
 * minutes (2,600 per percent), each one rewriting the whole history file, and
 * the detail screen that composes them froze the app for 32–70 s.
 */
internal object BackupLogRules {

    /**
     * Append [entry] to [log] the way iOS does, returning false when it is
     * dropped:
     * - the same message as the last line is dropped — a long phase reports
     *   the same status repeatedly;
     * - a transient (progress) last line is removed first, so the log keeps
     *   one moving progress row instead of one row per report.
     */
    fun append(log: MutableList<BackupHistory.LogEntry>, entry: BackupHistory.LogEntry): Boolean {
        val last = log.lastOrNull()
        if (last?.message == entry.message) return false
        if (last?.isTransient == true) log.removeAt(log.lastIndex)
        log.add(entry)
        return true
    }

    // -- [T-android-backup-log-sanitize] Cleaning records written before ------

    /** Lines a record keeps; older ones are folded into one note. */
    const val MAX_LOG_LINES = 200

    /**
     * Stored as the note's message and mapped to a translated string when
     * shown — like [BackupHistory.INTERRUPTED_MARKER] — so a record cleaned
     * in one language reads correctly in another.
     */
    const val OMITTED_MARKER_PREFIX = "[omitted-earlier-lines]:"

    private val PROGRESS_LINE = Regex("""^(Saving|Sending) to .+… \d{1,3}%$""")

    private fun isProgress(e: BackupHistory.LogEntry) = e.isTransient || PROGRESS_LINE.matches(e.message)

    /** How many lines a note stands for, or null when [e] is not a note. */
    fun omittedCount(e: BackupHistory.LogEntry): Int? =
        e.message.takeIf { it.startsWith(OMITTED_MARKER_PREFIX) }
            ?.removePrefix(OMITTED_MARKER_PREFIX)?.toIntOrNull()

    /**
     * Clean a log written before progress lines replaced each other:
     * - each run of consecutive progress lines keeps only its last line,
     *   marked transient, as [append] would have left it;
     * - over [MAX_LOG_LINES] lines, only the newest are kept, behind one note
     *   saying how many were left out (an earlier note's count is folded in).
     * Every other line is kept as it is. Cleaning a cleaned log changes nothing.
     */
    fun sanitize(log: List<BackupHistory.LogEntry>): List<BackupHistory.LogEntry> {
        val merged = ArrayList<BackupHistory.LogEntry>(minOf(log.size, 1_024))
        for (e in log) {
            if (!isProgress(e)) {
                merged.add(e)
                continue
            }
            val line = if (e.isTransient) e else e.copy(isTransient = true)
            if (merged.isNotEmpty() && isProgress(merged.last())) merged[merged.lastIndex] = line
            else merged.add(line)
        }
        val earlier = merged.firstOrNull()?.let(::omittedCount)
        val body = if (earlier != null) merged.subList(1, merged.size) else merged
        if (body.size <= MAX_LOG_LINES) return merged
        val dropped = body.size - MAX_LOG_LINES
        val kept = body.subList(dropped, body.size)
        val note = BackupHistory.LogEntry(
            at = kept.first().at,
            message = OMITTED_MARKER_PREFIX + ((earlier ?: 0) + dropped),
        )
        return listOf(note) + kept
    }
}

/**
 * Lets a byte-level progress callback through only when the percentage
 * changed AND at least [minIntervalMs] passed since the last one — the
 * `lastPct` of [BackupRunController.copyWithProgress] plus the 1 s interval of
 * iOS `BackupProgressReporter`.
 */
internal class PercentGate(
    private val minIntervalMs: Long = 1_000,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private var lastPct = Int.MIN_VALUE
    private var lastAt = 0L

    @Synchronized
    fun admit(pct: Int): Boolean {
        if (pct == lastPct) return false
        val now = clock()
        if (lastPct != Int.MIN_VALUE && now - lastAt < minIntervalMs) return false
        lastPct = pct
        lastAt = now
        return true
    }
}

/**
 * Coalesces history writes to at most one per [intervalMs]: a leading write,
 * then one trailing write scheduled for the end of the interval so the last
 * throttled line still lands (the running row's subtitle IS that line).
 *
 * [close] forces the final write and turns later requests — and a trailing
 * write still in flight — into no-ops, so nothing older can land after it.
 * [write] runs under this object's lock: a trailing write and the final one
 * never interleave.
 */
internal class ThrottledWriter(
    private val intervalMs: Long = 1_000,
    private val clock: () -> Long = System::currentTimeMillis,
    private val schedule: (delayMs: Long, block: () -> Unit) -> Unit,
    private val write: () -> Unit,
) {
    private val lock = Any()
    private var lastWriteAt = 0L
    private var written = false
    private var dirty = false
    private var trailingScheduled = false
    private var closed = false

    fun request() = synchronized(lock) {
        if (closed) return
        val now = clock()
        if (!written || now - lastWriteAt >= intervalMs) {
            doWrite(now)
            return
        }
        dirty = true
        if (!trailingScheduled) {
            trailingScheduled = true
            schedule(intervalMs - (now - lastWriteAt)) { trailing() }
        }
    }

    /** The run ended — succeeded, failed, cancelled or stopped. Always writes. */
    fun close() = synchronized(lock) {
        doWrite(clock())
        closed = true
    }

    private fun trailing() = synchronized(lock) {
        trailingScheduled = false
        if (closed || !dirty) return
        doWrite(clock())
    }

    private fun doWrite(now: Long) {
        dirty = false
        written = true
        lastWriteAt = now
        write()
    }
}
