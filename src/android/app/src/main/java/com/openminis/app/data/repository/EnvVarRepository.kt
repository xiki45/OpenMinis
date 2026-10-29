package com.openminis.app.data.repository

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Manages environment variables with encrypted value storage.
 * Metadata (key names, IDs) stored in JSON file.
 * Values stored in EncryptedSharedPreferences (AES256-GCM).
 * Enabled / disabled state stored in [EnvVarStateStore] (its own file, so
 * builds that predate it cannot drop it when they rewrite the metadata).
 * Mirrors iOS EnvVarStore.
 */
class EnvVarRepository internal constructor(
    private val dir: File,
    private val values: ValueStore,
) {

    /** The secret side: one value per variable NAME. */
    interface ValueStore {
        fun get(key: String): String?
        fun put(key: String, value: String)
        fun remove(key: String)
    }

    constructor(context: Context) : this(context.filesDir, EncryptedValueStore(context))

    private class EncryptedValueStore(private val context: Context) : ValueStore {
        private val prefs: SharedPreferences by lazy {
            // T-android-keystore-aead-fail: self-healing wrapper.
            com.openminis.app.util.EncryptedPrefsFactory.safeCreate(context, ENCRYPTED_PREFS_NAME)
        }
        override fun get(key: String): String? = prefs.getString(key, null)
        override fun put(key: String, value: String) { prefs.edit().putString(key, value).apply() }
        override fun remove(key: String) { prefs.edit().remove(key).apply() }
    }

    companion object {
        private const val TAG = "EnvVarRepository"
        internal const val METADATA_FILE = "env-vars.json"
        private const val ENCRYPTED_PREFS_NAME = "env_var_values"
        private val KEY_REGEX = Regex("^[A-Za-z][A-Za-z0-9_]*$")
    }

    data class EnvVarEntry(
        val id: String = UUID.randomUUID().toString(),
        val key: String,
        /**
         * Optional human-readable description of what this variable is for.
         * Empty when omitted. Stored in the JSON metadata file (not secret),
         * mirrors iOS EnvVarEntry.note.
         */
        val note: String = "",
        val createdAt: Long = System.currentTimeMillis(),
        /**
         * [T-envvar-disable] A disabled variable keeps its value and note but
         * is not injected into any shell / terminal / MCP process. Not
         * persisted in env-vars.json - see [EnvVarStateStore].
         */
        val enabled: Boolean = true,
    )

    private val _entries = MutableStateFlow<List<EnvVarEntry>>(emptyList())
    val entries: StateFlow<List<EnvVarEntry>> = _entries.asStateFlow()

    private val states = EnvVarStateStore(dir)

    private val metadataFile: File
        get() = File(dir, METADATA_FILE)

    init {
        loadMetadata()
    }

    // -- Validation --

    fun isValidKey(key: String): Boolean = KEY_REGEX.matches(key)

    // Keep printable ASCII (0x20-0x7E) and tab (0x09); iOS paste can inject
    // invisible control scalars (e.g. \u009B) that break shell env injection.
    private fun sanitizeValue(value: String): String =
        value.filter { ch ->
            val c = ch.code
            (c in 0x20..0x7E) || c == 0x09
        }

    fun isDuplicateKey(key: String, excludeId: String? = null): Boolean =
        _entries.value.any { it.key.equals(key, ignoreCase = true) && it.id != excludeId }

    /**
     * [T-envvar-disable] Names held by more than one entry (case-insensitive,
     * upper-cased). The add / edit form refuses duplicates, but an older build,
     * a restore or a merged file can still produce them; the list marks them so
     * the user can clean up.
     */
    fun duplicateKeys(): Set<String> =
        _entries.value.groupBy { it.key.uppercase() }.filterValues { it.size > 1 }.keys

    // -- CRUD --

    /**
     * Adds a new variable. [enabled] is only for a restore that carries a
     * disabled variable; every user-facing add creates an enabled one.
     */
    fun add(key: String, value: String, note: String = "", enabled: Boolean = true): Boolean {
        val normalizedKey = key.trim().uppercase()
        if (!isValidKey(normalizedKey)) return false
        if (isDuplicateKey(normalizedKey)) return false

        val entry = EnvVarEntry(key = normalizedKey, note = note.trim(), enabled = enabled)
        _entries.value = _entries.value + entry
        values.put(normalizedKey, sanitizeValue(value))
        saveMetadata()
        if (!enabled) states.setEnabled(entry.id, normalizedKey, false)
        Log.i(TAG, "Added env var: $normalizedKey enabled=$enabled")
        return true
    }

    /**
     * Changes the name, value and note. Deliberately has NO `enabled`
     * parameter: every writer that only means to change the value or note
     * (backup restore, minis-config note, the deep link form) goes through
     * here, and a defaulted `enabled = true` would silently re-enable a
     * variable the user turned off. Use [setEnabled] for that.
     */
    fun update(id: String, newKey: String, newValue: String, newNote: String = ""): Boolean {
        val normalizedKey = newKey.trim().uppercase()
        if (!isValidKey(normalizedKey)) return false

        val current = _entries.value.find { it.id == id } ?: return false

        // Check for duplicate (excluding self)
        if (isDuplicateKey(normalizedKey, excludeId = id)) return false

        // Delete old Keychain entry if key changed
        if (current.key != normalizedKey) {
            values.remove(current.key)
        }

        // Update metadata; `enabled` is carried over by copy().
        _entries.value = _entries.value.map {
            if (it.id == id) it.copy(key = normalizedKey, note = newNote.trim()) else it
        }

        // Save new value
        values.put(normalizedKey, sanitizeValue(newValue))
        saveMetadata()
        states.renamed(id, normalizedKey)
        Log.i(TAG, "Updated env var: ${current.key} → $normalizedKey")
        return true
    }

    /** [T-envvar-disable] Turns a variable on or off; its value and note are untouched. */
    fun setEnabled(id: String, enabled: Boolean): Boolean {
        val current = _entries.value.find { it.id == id } ?: return false
        if (current.enabled == enabled && states.state(id) != null) return true
        states.setEnabled(id, current.key, enabled)
        _entries.value = _entries.value.map { if (it.id == id) it.copy(enabled = enabled) else it }
        Log.i(TAG, "Env var ${current.key} enabled=$enabled")
        return true
    }

    fun delete(id: String) {
        val entry = _entries.value.find { it.id == id } ?: return
        values.remove(entry.key)
        _entries.value = _entries.value.filter { it.id != id }
        saveMetadata()
        // A new variable with the same name must not inherit this one's state.
        states.remove(id)
        Log.i(TAG, "Deleted env var: ${entry.key}")
    }

    fun getValue(key: String): String? = values.get(key)

    /** [T-envvar-disable] Whether the variable named [key] exists and is disabled. */
    fun isDisabled(key: String): Boolean =
        _entries.value.any { it.key.equals(key, ignoreCase = true) && !it.enabled }

    /**
     * Returns the ENABLED env vars as a Map<String, String> for sandbox
     * injection (shells, terminals, and through them MCP stdio servers).
     * Reads directly from storage to be thread-safe.
     */
    fun allAsDict(): Map<String, String> {
        val result = mutableMapOf<String, String>()
        for (entry in _entries.value) {
            if (!entry.enabled) continue
            val value = values.get(entry.key)
            if (value != null) {
                result[entry.key] = value
            }
        }
        return result
    }

    /**
     * [T-envvar-disable] Every stored value, disabled ones included, for the
     * output redactor: a disabled secret can still reach tool output (a
     * terminal started before it was disabled, a file the agent reads), and
     * it must stay masked.
     */
    fun allValuesForRedaction(): List<String> =
        _entries.value.mapNotNull { values.get(it.key) }

    // -- Metadata Persistence --

    private fun saveMetadata() {
        try {
            val array = JSONArray()
            for (entry in _entries.value) {
                val obj = JSONObject()
                obj.put("id", entry.id)
                obj.put("key", entry.key)
                if (entry.note.isNotEmpty()) obj.put("note", entry.note)
                obj.put("createdAt", entry.createdAt)
                array.put(obj)
            }
            metadataFile.writeText(array.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save metadata: ${e.message}")
        }
    }

    private fun loadMetadata() {
        try {
            if (!metadataFile.exists()) {
                states.prune(emptySet())
                return
            }
            val array = JSONArray(metadataFile.readText())
            val entries = mutableListOf<EnvVarEntry>()
            var mintedIds = false
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                // An entry without an id gets one now, and it is written back
                // below: a fresh random id on every load would leave a disabled
                // state nothing to attach to.
                val id = obj.optString("id", "").ifEmpty { mintedIds = true; UUID.randomUUID().toString() }
                entries.add(EnvVarEntry(
                    id = id,
                    key = obj.optString("key", ""),
                    note = obj.optString("note", ""),
                    createdAt = obj.optLong("createdAt", 0),
                    enabled = states.isEnabled(id),
                ))
            }
            _entries.value = entries
            if (mintedIds) saveMetadata()
            // Only after a successful read: forget states of variables that are
            // gone, including ones an older build deleted.
            states.prune(entries.map { it.id }.toSet())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load metadata: ${e.message}")
        }
    }
}
