package com.openminis.app.sandbox

import com.openminis.app.diagnostics.ThreadNames
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * [GH #453] The offload calls being served: how many hold a worker thread
 * (the admission count behind [NativeOffloadServer.MAX_CONCURRENT_CALLS]) and
 * which command each is running, so a pile of stuck workers names its cause
 * in the logs.
 */
internal class OffloadInFlight(private val clock: () -> Long = System::currentTimeMillis) {
    private val admitted = AtomicInteger(0)
    private val running = ConcurrentHashMap<Long, Pair<String, Long>>() // worker thread id -> (command, start ms)

    val count: Int get() = admitted.get()

    /** Take a slot, or false when [max] are taken. */
    fun tryAdmit(max: Int): Boolean {
        while (true) {
            val n = admitted.get()
            if (n >= max) return false
            if (admitted.compareAndSet(n, n + 1)) return true
        }
    }

    fun release() { admitted.decrementAndGet() }

    /** The calling worker is now running [command]. */
    fun begin(command: String) { running[Thread.currentThread().id] = command to clock() }

    fun end() { running.remove(Thread.currentThread().id) }

    /** `command:count(oldest Ns)`, most first; `none` when idle. */
    fun summary(): String {
        val now = clock()
        val groups = running.values.groupBy({ it.first }, { it.second })
        if (groups.isEmpty()) return "none"
        return groups.entries
            .sortedWith(compareByDescending<Map.Entry<String, List<Long>>> { it.value.size }.thenBy { it.key })
            .joinToString(",") { (cmd, starts) -> "$cmd:${starts.size}(oldest ${(now - starts.min()) / 1000}s)" }
    }

    companion object {
        /** Before the command is known. */
        val WORKER_THREAD_NAME = ThreadNames.of("offload-wk")

        /**
         * `mn-o-<command>`: the command without its `android-` / `minis-`
         * prefix, cut to the kernel's 15-byte comm, dots replaced (a dotted
         * name would keep its last 15 characters instead of its first).
         */
        fun workerThreadName(command: String): String {
            val short = command.removePrefix("android-").removePrefix("minis-").replace('.', '_')
            return ThreadNames.of("o-$short").take(15)
        }
    }
}
