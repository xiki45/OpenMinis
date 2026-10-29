package com.openminis.app.data.repository

import org.json.JSONObject
import java.io.File

/**
 * [T-envvar-disable] Where each environment variable's enabled / disabled state
 * lives: its own file, `env-vars-state.json`, NOT a field in `env-vars.json`.
 *
 * Why a separate file: builds before this feature read and rewrite
 * `env-vars.json` with only id / key / note / createdAt. A field stored there
 * would be dropped the first time an older build saved (note edit, value edit,
 * add, delete), so a downgrade - or a restore made by an older build - followed
 * by an upgrade would silently turn every disabled variable back on. Older
 * builds never open this file, so whatever they do to `env-vars.json`, the
 * state survives.
 *
 * Indexed by the entry's `id`, not its name:
 * - an older build editing a variable keeps its id, so the state follows it
 *   (including a rename, which keeps the id);
 * - deleting a variable and creating one with the same name - in this build or
 *   an older one - gives the new entry a new id, so it never inherits the old
 *   variable's state. States whose id no longer exists are pruned on load.
 *
 * Absent = enabled. Only a disabled variable needs a record, but an explicit
 * re-enable is kept as a record too, so that [enabledUpdatedAt] (the
 * last-writer-wins clock for this field, independent of value / note edits)
 * is not lost.
 *
 * File shape (version 1):
 * ```
 * {"version":1,"states":{"<entry id>":{"key":"GH_TOKEN","enabled":false,"enabledUpdatedAt":1727740800000}}}
 * ```
 */
class EnvVarStateStore(private val dir: File) {

    data class State(val key: String, val enabled: Boolean, val enabledUpdatedAt: Long)

    private val file: File get() = File(dir, FILE_NAME)
    private var states: MutableMap<String, State> = LinkedHashMap()

    /** True when the file existed but could not be parsed: never prune or overwrite it then. */
    var unreadable: Boolean = false
        private set

    init {
        load()
    }

    fun isEnabled(id: String): Boolean = states[id]?.enabled ?: true

    fun state(id: String): State? = states[id]

    /**
     * Records [enabled] for [id]. The clock never goes backwards for an entry:
     * a write stamped earlier than the one it replaces (device clock moved
     * back) is stamped one millisecond after it instead, so the newest user
     * action always carries the newest stamp.
     */
    fun setEnabled(id: String, key: String, enabled: Boolean, now: Long = System.currentTimeMillis()) {
        val previous = states[id]
        val stamp = if (previous != null && now <= previous.enabledUpdatedAt) previous.enabledUpdatedAt + 1 else now
        states[id] = State(key, enabled, stamp)
        save()
    }

    /** A rename keeps the variable (same id); keep the informational key in step. */
    fun renamed(id: String, newKey: String) {
        val s = states[id] ?: return
        if (s.key == newKey) return
        states[id] = s.copy(key = newKey)
        save()
    }

    fun remove(id: String) {
        if (states.remove(id) != null) save()
    }

    /**
     * Drops states whose variable no longer exists - deleted here, or deleted
     * (and possibly re-created under a new id) by an older build that knows
     * nothing about this file. Only call with the ids of a SUCCESSFUL metadata
     * load: an empty list from a failed read must not erase every state.
     */
    fun prune(liveIds: Set<String>) {
        if (unreadable) return
        val before = states.size
        states.keys.retainAll(liveIds)
        if (states.size != before) save()
    }

    private fun load() {
        val f = file
        if (!f.exists()) return
        try {
            val root = JSONObject(f.readText())
            val obj = root.optJSONObject("states") ?: JSONObject()
            val loaded = LinkedHashMap<String, State>()
            for (id in obj.keys()) {
                val s = obj.optJSONObject(id) ?: continue
                loaded[id] = State(
                    key = s.optString("key", ""),
                    enabled = s.optBoolean("enabled", true),
                    enabledUpdatedAt = s.optLong("enabledUpdatedAt", 0L),
                )
            }
            states = loaded
        } catch (e: Exception) {
            // Keep the file as it is: overwriting an unreadable file would turn
            // every disabled variable back on.
            unreadable = true
        }
    }

    private fun save() {
        if (unreadable) return
        val obj = JSONObject()
        for ((id, s) in states) {
            obj.put(id, JSONObject().apply {
                put("key", s.key)
                put("enabled", s.enabled)
                put("enabledUpdatedAt", s.enabledUpdatedAt)
            })
        }
        val root = JSONObject().apply {
            put("version", 1)
            put("states", obj)
        }
        // Write-then-rename so a crash mid-write never leaves a truncated file.
        val tmp = File(dir, "$FILE_NAME.tmp")
        tmp.writeText(root.toString())
        if (!tmp.renameTo(file)) {
            file.writeText(root.toString())
            tmp.delete()
        }
    }

    companion object {
        const val FILE_NAME = "env-vars-state.json"
    }
}
