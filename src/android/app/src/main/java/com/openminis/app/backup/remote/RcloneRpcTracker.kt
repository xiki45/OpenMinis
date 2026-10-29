package com.openminis.app.backup.remote

import com.openminis.app.logging.AppLogger
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * [T-rclone-rpc-concurrency-diag] Records how many rclone RPCs are inside the
 * Go runtime at once, and says so only when it is more than one.
 *
 * Two vivo users hit bare SIGABRTs that may involve `libgojni.so`; whether
 * concurrent RPCs play any part is unknown. This answers that question the
 * next time such a crash comes with its log, at no cost to normal runs: a
 * backup or restore drives rclone from ONE polling thread, so these lines
 * appear only when the user browses or edits destinations meanwhile.
 *
 * Deliberately NOT a lock. RPCs block for up to `IO_TIMEOUT_SECONDS` (300 s),
 * and any serialisation would let a stalled list or copy hold back the
 * `job/stop` that Stop sends (impact assessment
 * summarised in the commit). Every call proceeds exactly as before.
 *
 * Logs method names and timings only — never params: `core/obscure` and
 * `config/create` carry clear-text passwords, and paths can carry user names.
 */
internal object RcloneRpcTracker {

    private const val TAG = "Rclone"

    private data class InFlight(val method: String, val startMs: Long)

    private val nextToken = AtomicLong()
    private val inFlight = ConcurrentHashMap<Long, InFlight>()

    /** Calls currently inside rclone. */
    val inFlightCount: Int get() = inFlight.size

    /** Seams for tests; production uses AppLogger and the wall clock. */
    @Volatile internal var log: (String) -> Unit = { AppLogger.info(TAG, it) }
    @Volatile internal var clock: () -> Long = { System.currentTimeMillis() }

    /** Run [block] (the native RPC) for [method], recording it while it runs. */
    fun <T> track(method: String, block: () -> T): T {
        val token = nextToken.incrementAndGet()
        val start = clock()
        inFlight[token] = InFlight(method, start)
        val others = inFlight.filterKeys { it != token }.values
        val concurrent = others.isNotEmpty()
        if (concurrent) {
            log(
                "[Rclone] concurrent RPC enter: $method with ${others.size + 1} in flight; others=" +
                    others.joinToString(prefix = "[", postfix = "]") { "${it.method} ${start - it.startMs}ms" },
            )
        }
        try {
            return block()
        } finally {
            inFlight.remove(token)
            if (concurrent) {
                log("[Rclone] concurrent RPC exit: $method after ${clock() - start}ms; ${inFlight.size} still in flight")
            }
        }
    }
}
