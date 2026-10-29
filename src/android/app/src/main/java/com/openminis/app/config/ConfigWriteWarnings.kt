package com.openminis.app.config

/**
 * [T-envvar-disable] Non-fatal notes a field or collection raises while it is
 * being written - the write still happens. ConfigBridge opens a scope around
 * its apply loop and returns whatever was collected as `warnings` in the
 * response, so the agent learns about it (e.g. "$$GH_TOKEN is disabled and
 * resolved to an empty string") without the batch failing.
 *
 * Thread-local: writes run synchronously inside the bridge's loop, on the
 * thread that opened the scope. Outside a scope (tests, other callers) a
 * warning is simply dropped after being logged by its caller.
 */
object ConfigWriteWarnings {
    private val current = ThreadLocal<MutableList<String>?>()

    fun add(message: String) {
        current.get()?.add(message)
    }

    /** Runs [block] collecting warnings; returns its result and the warnings. */
    fun <T> collect(block: () -> T): Pair<T, List<String>> {
        val previous = current.get()
        val list = ArrayList<String>()
        current.set(list)
        try {
            return block() to list.distinct()
        } finally {
            current.set(previous)
        }
    }
}
