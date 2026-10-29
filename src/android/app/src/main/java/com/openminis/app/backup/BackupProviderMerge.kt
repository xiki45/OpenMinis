package com.openminis.app.backup

import com.openminis.app.data.model.ModelEntry
import com.openminis.app.data.model.ModelGroup
import com.openminis.app.data.model.ProviderConfig
import com.openminis.app.data.model.ProviderInstance
import com.openminis.app.data.model.SubAgentDefinition
import com.openminis.app.data.model.SubAgentRoster
import org.json.JSONObject

/**
 * [T-backup-restore-semantics / B01] The restore-only merge of a backup's
 * provider_config.json into this device's config. Pure, so the shared scenario
 * file (`src/shared/backup-restore/b01_restore_scenarios.json`) drives it on
 * both platforms. Owner decision 2026-10-15, the same on iOS:
 *
 *  - instances: union by id in the BACKUP's order, local-only appended. A
 *    same-id instance takes the backup's fields (label, base URL, type, ...);
 *    only the cached image-endpoint probe stays local when the mode matches.
 *  - entries: union by `{instanceId}/{modelId}`, non-custom entries included.
 *    On the same key the backup's `overrides` and `isHidden` win, with no
 *    timestamp comparison; `baseModel` (the API's truth) and `absentSince`
 *    stay the device's.
 *  - groups: a same-id group takes the backup's name, strategies, thinking
 *    level, context limits and member order, local-only members appended. A
 *    backup group whose NAME matches a local group with another id is merged
 *    into that local group the same way: one group, the backup's content, the
 *    LOCAL id. The backup's id is recorded in [Result.groupIdRewrites] and
 *    every reference to it is rewritten.
 *  - agent-loop lists: union. Sub agents: union by id / name, as before.
 *  - nothing only on this device is deleted, and the backup's own tombstones
 *    are never applied (Android keeps none).
 *
 * This used to keep the LOCAL copy of every same-id group and existing entry
 * and leave a same-name backup group as a second group, so a restore could
 * not bring back parameters or group contents the user had changed since.
 */
object BackupProviderMerge {

    data class Result(
        val config: ProviderConfig,
        /** Backup group id -> the local id it was merged into (same name). */
        val groupIdRewrites: Map<String, String>,
        /** Entries the backup added / whose parameters it replaced. */
        val entriesAdded: Int = 0,
        val entriesUpdated: Int = 0,
        /** Backup instances this device did not have. */
        val instancesAdded: Set<String> = emptySet(),
        /**
         * Instances already here whose content the backup really changed: their
         * own fields, or one of their model entries (added, or its parameters,
         * hidden state or name replaced). An instance the backup matched
         * exactly is not in here.
         */
        val instancesChanged: Set<String> = emptySet(),
        /** Backup groups this device did not have (by id or by name). */
        val groupsAdded: Int = 0,
        /** Groups already here whose content the backup changed, plus every same-name merge. */
        val groupsChanged: Int = 0,
    )

    /** What the restore page shows for the providers category. */
    data class RestoreCounts(val imported: Int, val updated: Int, val skipped: Int)

    /**
     * [T-b01-restore-counts] The providers category's restore counts, by what
     * really changed. The unit is the package's instances: one not on this
     * device is `imported`; one whose content the backup changed - its fields,
     * one of its model entries, or a credential written over a different value
     * ([credentialsWritten]) - is `updated`, once however many of those
     * happened; one the backup matched exactly is `skipped` ("up to date").
     * Groups are counted on top: a restored group is `imported`, a changed or
     * same-name-merged one `updated`; an unchanged group is not counted.
     *
     * The two false readings this sits between: restoring a package onto the
     * device that produced it used to say "Updated 9" while changing nothing
     * (fix 93cad55ae, iOS), and after B01 a restore that put back a deleted
     * group, a model's name and an API key said "Up to date 27".
     */
    fun counts(result: Result, packageInstanceIds: Collection<String>, credentialsWritten: Collection<String>): RestoreCounts {
        val pkg = packageInstanceIds.toSet()
        val added = result.instancesAdded intersect pkg
        val changed = ((result.instancesChanged + credentialsWritten) intersect pkg) - added
        return RestoreCounts(
            imported = added.size + result.groupsAdded,
            updated = changed.size + result.groupsChanged,
            skipped = pkg.size - added.size - changed.size,
        )
    }

    private fun key(e: ModelEntry) = "${e.providerInstanceId}/${e.baseModel.id}"

    fun merge(local: ProviderConfig, remote: ProviderConfig, log: ((String) -> Unit)? = null): Result {
        // Group members and agent-loop entries may name an entry by its legacy
        // uuid (an older package) or by its composite key. Resolve both sides
        // to the composite key, the id every lookup uses after a save.
        val localIds = local.modelEntries.associate { it.uuid to key(it) }
        val remoteIds = remote.modelEntries.associate { it.uuid to key(it) }
        fun localKey(id: String) = localIds[id] ?: id
        fun remoteKey(id: String) = remoteIds[id] ?: id

        // ── instances ──
        val localInstances = local.instances.associateBy { it.id }
        val instances = mutableListOf<ProviderInstance>()
        val placedInstances = mutableSetOf<String>()
        val instancesAdded = mutableSetOf<String>()
        val instancesChanged = mutableSetOf<String>()
        for (ri in remote.instances) {
            if (!placedInstances.add(ri.id)) continue
            val li = localInstances[ri.id]
            if (li == null) {
                instances += ri
                instancesAdded += ri.id
                continue
            }
            val next = ri.copy(
                // A per-device probe cache, valid only for the mode it probed.
                imageEndpointResolved = if (li.imageEndpointMode == ri.imageEndpointMode) li.imageEndpointResolved else null,
            )
            // createdAt is not content, and an iOS package carries it without
            // the milliseconds: comparing it would call every instance an iOS
            // backup round-tripped "updated".
            if (next.copy(createdAt = li.createdAt) != li) instancesChanged += li.id
            instances += next
        }
        for (li in local.instances) if (placedInstances.add(li.id)) instances += li

        // ── entries ──
        val entries = local.modelEntries.toMutableList()
        val indexByKey = HashMap<String, Int>()
        entries.forEachIndexed { i, e -> indexByKey.putIfAbsent(key(e), i) }
        var added = 0
        var updated = 0
        for (re in remote.modelEntries) {
            val k = key(re)
            val i = indexByKey[k]
            if (i == null) {
                indexByKey[k] = entries.size
                entries += re.copy(uuid = k)
                added++
                if (re.providerInstanceId in localInstances) instancesChanged += re.providerInstanceId
                continue
            }
            val le = entries[i]
            var next = le
            if (le.overrides != re.overrides || le.isHidden != re.isHidden) {
                next = next.copy(
                    overrides = re.overrides,
                    isHidden = re.isHidden,
                    userModifiedAt = re.userModifiedAt ?: le.userModifiedAt,
                )
                updated++
            }
            // A local name that is only the raw id gains the backup's readable
            // one (kept from the previous merge; the API never sent a name).
            val ln = le.baseModel.displayName
            val rn = re.baseModel.displayName
            if ((ln.isBlank() || ln == le.baseModel.id) && rn.isNotBlank() && rn != re.baseModel.id) {
                next = next.copy(baseModel = next.baseModel.copy(displayName = rn))
            }
            if (next != le) instancesChanged += le.providerInstanceId
            entries[i] = next
        }

        // ── groups ──
        val localGroups = local.modelGroups.map { it.copy(memberEntryIds = it.memberEntryIds.map(::localKey).toMutableList()) }
        val remoteGroups = remote.modelGroups.map { it.copy(memberEntryIds = it.memberEntryIds.map(::remoteKey).toMutableList()) }
        val localById = localGroups.associateBy { it.id }
        // Local groups an id match claims are not available to a name match.
        val claimed = remoteGroups.map { it.id }.filter { it in localById }.toMutableSet()
        val rewrites = LinkedHashMap<String, String>()
        val groups = mutableListOf<ModelGroup>()
        val placedGroups = mutableSetOf<String>()
        var groupsAdded = 0
        var groupsChanged = 0
        for (rg in remoteGroups) {
            val target = localById[rg.id]
                ?: localGroups.firstOrNull { it.name == rg.name && it.id !in claimed }
                    ?.also {
                        claimed += it.id
                        rewrites[rg.id] = it.id
                        log?.invoke("[Restore] group '${rg.name}' ${rg.id} merged into local ${it.id} (same name)")
                    }
            val id = target?.id ?: rg.id
            if (!placedGroups.add(id)) continue
            if (target == null) {
                groups += rg
                groupsAdded++
                continue
            }
            val next = backupWins(rg, target)
            // A same-name merge always counts: the backup's group was folded
            // into this one even when its content matched.
            if (next != target || rg.id != target.id) groupsChanged++
            groups += next
        }
        for (lg in localGroups) if (placedGroups.add(lg.id)) groups += lg

        // ── agent loop, sub agents, pointers ──
        fun rw(id: String?) = id?.let { rewrites[it] ?: it }
        val agentEntries = (local.agentLoopModelEntryIds.map(::localKey) + remote.agentLoopModelEntryIds.map(::remoteKey)).distinct()
        val agentGroups = (local.agentLoopGroupIds + remote.agentLoopGroupIds).map { rewrites[it] ?: it }.distinct()

        val subAgents = rewriteSubAgents(local.subAgents, rewrites).toMutableList()
        for (r in rewriteSubAgents(remote.subAgents, rewrites)) {
            val clash = subAgents.any { it.id == r.id || SubAgentRoster.nameKey(it.name) == SubAgentRoster.nameKey(r.name) }
            if (!clash) subAgents += r
        }

        val merged = local.copy(
            instances = instances,
            modelEntries = entries,
            modelGroups = groups,
            subAgents = SubAgentRoster.normalize(subAgents, log).toMutableList(),
            agentLoopModelEntryIds = agentEntries.toMutableList(),
            agentLoopGroupIds = agentGroups.toMutableList(),
            defaultPrimaryGroupId = rw(local.defaultPrimaryGroupId),
            defaultSubGroupId = rw(local.defaultSubGroupId),
            voiceInputGroupId = rw(local.voiceInputGroupId),
            voiceOutputGroupId = rw(local.voiceOutputGroupId),
            visionGroupId = rw(local.visionGroupId),
        )
        return Result(
            merged, rewrites, added, updated,
            instancesAdded = instancesAdded,
            instancesChanged = instancesChanged,
            groupsAdded = groupsAdded,
            groupsChanged = groupsChanged,
        )
    }

    /** The backup's group content under [local]'s id; local-only members follow. */
    private fun backupWins(backup: ModelGroup, local: ModelGroup): ModelGroup {
        val members = backup.memberEntryIds.distinct().toMutableList()
        for (m in local.memberEntryIds) if (m !in members) members += m
        return backup.copy(id = local.id, memberEntryIds = members)
    }

    /**
     * A session's / scheduled task's `modelBinding` with a swallowed group id
     * rewritten. Anything that is not a group binding to a rewritten id comes
     * back as it went in, byte for byte.
     */
    fun rewriteBindingJson(binding: String?, rewrites: Map<String, String>): String? {
        if (binding.isNullOrBlank() || rewrites.isEmpty()) return binding
        val o = runCatching { JSONObject(binding) }.getOrNull() ?: return binding
        if (o.optString("type") != "group") return binding
        val to = rewrites[o.optString("groupId")] ?: return binding
        return o.put("groupId", to).toString()
    }

    /** A scheduled task's legacy `modelId` of the form `group:<id>`. */
    fun rewriteLegacyModelId(modelId: String?, rewrites: Map<String, String>): String? {
        if (modelId == null || !modelId.startsWith("group:")) return modelId
        val to = rewrites[modelId.removePrefix("group:")] ?: return modelId
        return "group:$to"
    }

    fun rewriteSubAgents(agents: List<SubAgentDefinition>, rewrites: Map<String, String>): List<SubAgentDefinition> =
        if (rewrites.isEmpty()) agents else agents.map { a ->
            val to = a.modelGroupId?.let { rewrites[it] }
            if (to == null) a else a.copy(modelGroupId = to)
        }
}
