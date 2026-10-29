package com.openminis.app.scheduled

import com.openminis.app.data.model.ModelEntry
import com.openminis.app.data.model.ModelGroup
import org.json.JSONObject

/**
 * [OpenMinis#445] What `minis-scheduled create --model` names, resolved at
 * create time into the same `modelBinding` the task editor writes.
 *
 * The flag used to be stored verbatim as `modelId`, and a fire matched it
 * against `model.id`: `entry:<id>` (the form `minis-config get
 * session.primaryModel` prints) or `<instanceId>/<modelId>` matched nothing and
 * the run quietly fell back to the default group, while a bare model id served
 * by two provider instances ran on whichever sorted first - possibly another
 * account. Accepted now, the same on iOS:
 *   - `entry:<entryId>` or a bare entry id -> that exact entry
 *   - `group:<groupId>` -> that group
 *   - a bare model id -> the one visible entry serving it; two or more is an
 *     error that lists their entry ids instead of picking one
 * Anything else is an error at create time.
 *
 * Pure: callers pass the entries and groups, so this is unit-tested directly.
 * The JSON is written the way the task editor and ChatViewModel write it.
 */
object ScheduledModelSource {

    data class EntryRef(val entryId: String, val modelId: String, val visible: Boolean)
    data class GroupRef(val groupId: String)

    /** [bindingJson] goes to ScheduledTask.modelBinding, [modelId] to its legacy modelId. */
    data class Resolved(val bindingJson: String, val modelId: String?, val display: String)

    private const val LOOKUP_HINT = "run `minis-config get models` for entry ids, then pass entry:<id> or group:<id>"

    /** @throws IllegalArgumentException with a message the CLI prints as is. */
    fun resolve(raw: String, entries: List<EntryRef>, groups: List<GroupRef>): Resolved {
        val s = raw.trim()
        require(s.isNotEmpty()) { "--model is empty; $LOOKUP_HINT" }
        if (s.startsWith("group:")) {
            val gid = s.removePrefix("group:")
            require(gid.isNotEmpty()) { "--model group id is empty" }
            require(groups.any { it.groupId == gid }) { "--model: no model group with id '$gid'" }
            return Resolved("""{"type":"group","groupId":"$gid"}""", null, "group:$gid")
        }
        if (s.startsWith("entry:")) {
            val eid = s.removePrefix("entry:")
            require(eid.isNotEmpty()) { "--model entry id is empty; $LOOKUP_HINT" }
            val e = entries.firstOrNull { it.entryId == eid }
                ?: throw IllegalArgumentException("--model: no model entry with id '$eid'; $LOOKUP_HINT")
            return entry(e)
        }
        entries.firstOrNull { it.entryId == s }?.let { return entry(it) }
        val serving = entries.filter { it.visible && it.modelId == s }
        return when (serving.size) {
            1 -> entry(serving.single())
            0 -> throw IllegalArgumentException("--model: '$s' is not a model entry, group or visible model id; $LOOKUP_HINT")
            else -> throw IllegalArgumentException(
                "--model: '$s' is served by ${serving.size} model entries; pick one: " +
                    serving.joinToString(", ") { "entry:${it.entryId}" },
            )
        }
    }

    /**
     * Fire-time reading of a task created before create-time resolution: its
     * modelId may hold `entry:<id>`, `group:<id>` or a bare entry id. Those are
     * honoured; anything else (a bare model id, an entry that is gone) returns
     * null and the task keeps its old modelId path unchanged.
     */
    fun healLegacy(modelId: String, entries: List<EntryRef>, groups: List<GroupRef>): Resolved? {
        val s = modelId.trim()
        val named = s.startsWith("entry:") || s.startsWith("group:") || entries.any { it.entryId == s }
        if (!named) return null
        return runCatching { resolve(s, entries, groups) }.getOrNull()
    }

    /** The task's model in the CLI's own words, for `list` / `create` output. */
    fun display(bindingJson: String?, modelId: String?): String? {
        val o = bindingJson?.let { runCatching { JSONObject(it) }.getOrNull() }
        when (o?.optString("type")) {
            "entry" -> o.optString("entryId").takeIf { it.isNotEmpty() }?.let { return "entry:$it" }
            "group" -> o.optString("groupId").takeIf { it.isNotEmpty() }?.let { return "group:$it" }
        }
        return modelId
    }

    fun entryRefs(entries: List<ModelEntry>, visible: List<ModelEntry>): List<EntryRef> {
        val visibleIds = visible.mapTo(HashSet()) { it.id }
        return entries.map { EntryRef(it.id, it.baseModel.id, it.id in visibleIds) }
    }

    fun groupRefs(groups: List<ModelGroup>): List<GroupRef> = groups.map { GroupRef(it.id) }

    private fun entry(e: EntryRef) = Resolved(
        """{"type":"entry","entryId":"${e.entryId}"}""",
        e.modelId,
        "entry:${e.entryId}",
    )
}
