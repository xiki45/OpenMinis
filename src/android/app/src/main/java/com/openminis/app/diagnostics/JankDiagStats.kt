package com.openminis.app.diagnostics

import kotlin.math.max

/**
 * [T-android-jankdiag] Pure aggregation behind [JankDiag]: no Android types, so
 * JankDiagStatsTest runs it on the JVM. Everything here is fed from the
 * JankDiag background thread and read back once per summary window.
 */

/** Frame durations for one window, in nanoseconds, stage by stage. */
internal class JankFrameWindow(initialCapacity: Int = 1024) {
    enum class Stage { TOTAL, INPUT, ANIMATION, LAYOUT_MEASURE, DRAW, SYNC, COMMAND_ISSUE, SWAP, GPU }

    private var size = 0
    private var data = Array(Stage.entries.size) { LongArray(initialCapacity) }
    var janky = 0
        private set
    var over100ms = 0
        private set
    var deadlineMissed = 0
        private set
    var firstVsyncNs = Long.MAX_VALUE
        private set
    var lastVsyncNs = 0L
        private set

    val count: Int get() = size

    /**
     * One frame. [deadlineNs] is the frame's budget (FrameMetrics.DEADLINE on
     * API 31+, else the display's vsync period); a frame whose total passes it
     * counts as janky.
     */
    fun add(stages: LongArray, deadlineNs: Long, intendedVsyncNs: Long) {
        if (size == data[0].size) data = Array(data.size) { data[it].copyOf(size * 2) }
        for (i in data.indices) data[i][size] = stages.getOrElse(i) { 0L }
        size++
        val total = stages[Stage.TOTAL.ordinal]
        if (deadlineNs > 0 && total > deadlineNs) { janky++; deadlineMissed++ }
        if (total > 100_000_000L) over100ms++
        if (intendedVsyncNs > 0) {
            if (intendedVsyncNs < firstVsyncNs) firstVsyncNs = intendedVsyncNs
            if (intendedVsyncNs > lastVsyncNs) lastVsyncNs = intendedVsyncNs
        }
    }

    /** The [q] quantile (0..1) of a stage, in ms; 0 when empty. */
    fun percentileMs(stage: Stage, q: Double): Double {
        if (size == 0) return 0.0
        val sorted = data[stage.ordinal].copyOf(size).also { it.sort() }
        val idx = ((size - 1) * q).toInt().coerceIn(0, size - 1)
        return sorted[idx] / 1_000_000.0
    }

    fun maxMs(stage: Stage): Double {
        var m = 0L
        for (i in 0 until size) m = max(m, data[stage.ordinal][i])
        return m / 1_000_000.0
    }

    /** Frames per second over the window's own vsync span (0 below two frames). */
    fun fps(): Double {
        if (size < 2 || lastVsyncNs <= firstVsyncNs) return 0.0
        return (size - 1) * 1e9 / (lastVsyncNs - firstVsyncNs)
    }

    /**
     * Whether the window is worth a line: a few janky frames, or one frame long
     * enough to be a visible hitch. Quiet windows print nothing.
     */
    fun worthReporting(minJanky: Int = 3): Boolean = janky >= minJanky || over100ms > 0

    fun summary(): String {
        fun f(v: Double) = "%.1f".format(v)
        val s = Stage.TOTAL
        return "frames=$size fps=${f(fps())} janky=$janky over100ms=$over100ms " +
            "total p50=${f(percentileMs(s, .5))} p90=${f(percentileMs(s, .9))} p99=${f(percentileMs(s, .99))} max=${f(maxMs(s))} | " +
            listOf(Stage.INPUT, Stage.ANIMATION, Stage.LAYOUT_MEASURE, Stage.DRAW, Stage.SYNC, Stage.COMMAND_ISSUE, Stage.SWAP, Stage.GPU)
                .joinToString(" ") { st -> "${st.name.lowercase()}=${f(percentileMs(st, .9))}/${f(maxMs(st))}" } +
            " (p90/max ms)"
    }

    fun reset() {
        size = 0; janky = 0; over100ms = 0; deadlineMissed = 0
        firstVsyncNs = Long.MAX_VALUE; lastVsyncNs = 0L
    }
}

/** A small reservoir of millisecond samples (parse times, log call times): p50/p90/max. */
internal class JankMsSamples(private val capacity: Int = 512) {
    private val values = DoubleArray(capacity)
    private var n = 0
    var total = 0
        private set
    var sumMs = 0.0
        private set
    var maxMs = 0.0
        private set

    fun add(ms: Double) {
        total++
        sumMs += ms
        if (ms > maxMs) maxMs = ms
        if (n < capacity) values[n++] = ms else values[(total - 1) % capacity] = ms
    }

    fun percentile(q: Double): Double {
        if (n == 0) return 0.0
        val sorted = values.copyOf(n).also { it.sort() }
        return sorted[((n - 1) * q).toInt().coerceIn(0, n - 1)]
    }

    fun reset() { n = 0; total = 0; sumMs = 0.0; maxMs = 0.0 }
}

/** Readable page label from a navigation route: "chat/{sessionId}" -> "chat". */
internal fun jankScreenLabel(route: String?): String {
    if (route.isNullOrBlank()) return "none"
    return route.substringBefore("/{").substringBefore("?").take(40)
}

/** "a.b.C.m:12 <- d.E.n:3 ..." — the first [depth] frames of a stack, one line. */
internal fun jankStackLine(stack: Array<StackTraceElement>, depth: Int = 18): String =
    stack.take(depth).joinToString(" <- ") { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" }
