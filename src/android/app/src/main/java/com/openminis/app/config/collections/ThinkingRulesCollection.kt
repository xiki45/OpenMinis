package com.openminis.app.config.collections

import com.openminis.app.config.ConfigCollection
import com.openminis.app.config.ConfigError
import com.openminis.app.config.ConfigField
import com.openminis.app.config.ConfigRisk
import com.openminis.app.config.ConfigSchema
import com.openminis.app.config.ConfigValue
import com.openminis.app.config.fields.ClosureField
import com.openminis.app.config.readable.ConfigReadable
import com.openminis.app.config.readable.ConfigRefKind
import com.openminis.app.config.readable.ConfigRefResolver
import com.openminis.app.config.readable.ConfigReadableRenderer
import com.openminis.app.config.readable.ConfigValueFormatter
import com.openminis.app.config.readable.ReadableArg
import com.openminis.app.config.readable.ReadableLine
import com.openminis.app.config.readable.RedactedConfigValue
import com.openminis.app.config.fields.ReadOnlyField
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.provider.thinking.ThinkingRule
import com.openminis.app.provider.thinking.ThinkingRuleCoding
import org.json.JSONObject

/**
 * [T-android-thinking-rules-phase2 / parity with iOS ThinkingRulesCollection.swift]
 * Exposes user-authored custom thinking rules to minis-config under
 * `thinkingrules.<instanceId>:<ruleId>.<field>`.
 *
 * Only CUSTOM rules are enumerable; built-in rules are never children, so they have no
 * writable path — "you cannot modify a built-in" is a structural guarantee, not a runtime
 * check. Child id is the composite `<instanceId>:<ruleId>` because the config path splits
 * give a collection exactly ONE id segment; instance ids are UUIDs (no colon), so a split
 * on the FIRST ':' cleanly separates instance from rule.
 */
class ThinkingRulesCollection(
    private val repo: ProviderRepository,
) : ConfigCollection {
    override val basePath: String get() = "thinkingrules"
    override val displayName: String get() = "Thinking rules"
    override val description: String get() =
        "User-authored rules that decide which thinking parameters an OpenAI-compatible provider sends."
    override val addable: Boolean get() = true
    override val removable: Boolean get() = true
    override val risk: ConfigRisk get() = ConfigRisk.SENSITIVE
    override val addPayloadSchema: ConfigSchema get() = ConfigSchema.Json

    // ---- child enumeration ----

    override fun childIds(): List<String> = buildList {
        for (inst in repo.config.value.instances) {
            for (id in repo.thinkingRuleIds(inst.id)) add("${inst.id}:$id")
        }
    }

    private fun split(childId: String): Pair<String, String>? {
        val i = childId.indexOf(':')
        if (i <= 0 || i >= childId.length - 1) return null
        return childId.substring(0, i) to childId.substring(i + 1)
    }

    private fun ruleOf(childId: String): Triple<String, String, ThinkingRule>? {
        val (instanceId, ruleId) = split(childId) ?: return null
        val ids = repo.thinkingRuleIds(instanceId)
        val idx = ids.indexOf(ruleId)
        if (idx < 0) return null
        val rule = repo.thinkingRules(instanceId).getOrNull(idx) ?: return null
        return Triple(instanceId, ruleId, rule)
    }

    // ---- fields ----

    override fun fields(forId: String): List<ConfigField> {
        val (instanceId, ruleId, _) = ruleOf(forId) ?: return emptyList()
        return listOf(
            labelField(forId, instanceId, ruleId),
            scopeField(forId, instanceId, ruleId),
            wireFormatField(forId, instanceId, ruleId),
            providerField(forId, instanceId),
        )
    }

    private fun labelField(childId: String, instanceId: String, ruleId: String) = ClosureField(
        path = "$basePath.$childId.label",
        displayName = "Label",
        description = "Human-readable name shown in the rule list and the resolution trace.",
        valueSchema = ConfigSchema.Str(),
        readable = ConfigReadable(ConfigValueFormatter.Text()),
        reader = { ConfigValue.Str(ruleOf(childId)?.third?.label ?: "") },
        writer = { v ->
            val label = (v as? ConfigValue.Str)?.value ?: throw ConfigError.InvalidValue("expected string")
            val rule = ruleOf(childId)?.third ?: throw ConfigError.InvalidValue("rule no longer exists")
            repo.saveThinkingRule(instanceId, rule.copy(label = label), id = ruleId)
        },
    )

    private fun scopeField(childId: String, instanceId: String, ruleId: String) = ClosureField(
        path = "$basePath.$childId.scope",
        displayName = "Scope",
        description = "\"all\" for every model, or a glob pattern like \"deepseek-v4*\".",
        valueSchema = ConfigSchema.Str(),
        risk = ConfigRisk.SENSITIVE,
        readable = ConfigReadable(ConfigValueFormatter.Custom(::scopePhrase), label = "Applies to"),
        reader = {
            val s = ruleOf(childId)?.third?.scope
            ConfigValue.Str(if (s is ThinkingRule.Scope.ModelPattern) s.pattern else "all")
        },
        writer = { v ->
            val str = (v as? ConfigValue.Str)?.value ?: throw ConfigError.InvalidValue("expected string")
            val rule = ruleOf(childId)?.third ?: throw ConfigError.InvalidValue("rule no longer exists")
            val scope = if (str.equals("all", true) || str.isBlank()) {
                ThinkingRule.Scope.AllModels
            } else {
                ThinkingRule.Scope.ModelPattern(str)
            }
            repo.saveThinkingRule(instanceId, rule.copy(scope = scope), id = ruleId)
        },
    )

    private fun wireFormatField(childId: String, instanceId: String, ruleId: String) = ClosureField(
        path = "$basePath.$childId.wireFormat",
        displayName = "Wire format",
        description = "JSON {\"type\":\"reasoning_effort\",\"offValue\":\"none\"} etc. — how the thinking control appears on the wire.",
        valueSchema = ConfigSchema.Json,
        risk = ConfigRisk.SENSITIVE,
        readable = ConfigReadable(ConfigValueFormatter.Custom(::wireFormatPhrase), label = "Request format"),
        reader = {
            val json = ThinkingRuleCoding.encodeWireFormat(ruleOf(childId)?.third?.wireFormat)
            configValueFromJson(json)
        },
        writer = { v ->
            val json = jsonStringFromConfigValue(v)
            val fmt = ThinkingRuleCoding.decodeWireFormat(json)
                ?: throw ConfigError.InvalidValue("unrecognized wire format JSON")
            val rule = ruleOf(childId)?.third ?: throw ConfigError.InvalidValue("rule no longer exists")
            repo.saveThinkingRule(instanceId, rule.copy(wireFormat = fmt), id = ruleId)
        },
    )

    private fun providerField(childId: String, instanceId: String) = ReadOnlyField(
        path = "$basePath.$childId.provider",
        displayName = "Provider",
        description = "The provider instance this rule belongs to.",
        valueSchema = ConfigSchema.Str(),
        reader = {
            val label = repo.config.value.instances.find { it.id == instanceId }?.label ?: instanceId
            ConfigValue.Str("$label ($instanceId)")
        },
    )

    // ---- add / remove ----

    override fun add(payload: ConfigValue): String {
        val obj = (payload as? ConfigValue.Obj)?.value
            ?: throw ConfigError.InvalidValue("Expected JSON object")
        val instanceId = (obj["provider"] as? ConfigValue.Str)?.value
            ?: throw ConfigError.InvalidValue("`provider` (instance id) required")
        if (repo.config.value.instances.none { it.id == instanceId }) {
            throw ConfigError.InvalidValue("provider instance not found: $instanceId")
        }
        val label = (obj["label"] as? ConfigValue.Str)?.value
            ?: throw ConfigError.InvalidValue("`label` required")

        val scopeStr = (obj["scope"] as? ConfigValue.Str)?.value ?: "all"
        val scope = if (scopeStr.equals("all", true) || scopeStr.isBlank()) {
            ThinkingRule.Scope.AllModels
        } else {
            ThinkingRule.Scope.ModelPattern(scopeStr)
        }

        val wfJson = when (val wf = obj["wire_format"]) {
            is ConfigValue.Obj, is ConfigValue.Str -> jsonStringFromConfigValue(wf)
            else -> throw ConfigError.InvalidValue("`wire_format` required (JSON object)")
        }
        val fmt = ThinkingRuleCoding.decodeWireFormat(wfJson)
            ?: throw ConfigError.InvalidValue("unrecognized wire_format")

        val rule = ThinkingRule(
            kind = ThinkingRule.Kind.CUSTOM,
            scope = scope,
            wireFormat = fmt,
            label = label,
        )
        // New rules insert at the top (priority 0) — a rule overriding a built-in is
        // useless below it.
        val ruleId = repo.saveThinkingRule(instanceId, rule, id = null)
        return "$instanceId:$ruleId"
    }

    override fun remove(id: String) {
        if (id.startsWith("builtin:")) {
            throw ConfigError.PermissionDenied(
                "Built-in rules are part of the app and cannot be removed. Add a rule above one to override it.",
            )
        }
        val (instanceId, ruleId) = split(id)
            ?: throw ConfigError.InvalidValue("bad rule id: $id")
        repo.deleteThinkingRule(instanceId, ruleId)
    }

    // ---- [T-config-readable] Readable descriptions (mirrors iOS ThinkingRulesCollection) ----

    override val readableSubjectFormat: String get() = "Thinking rule “%1\$@” – %2\$@"

    override fun entityLabel(id: String): String? = ruleOf(id)?.third?.label

    override fun readableForAdd(payload: RedactedConfigValue): List<ReadableLine> {
        val d = (payload.value as? ConfigValue.Obj)?.value ?: return emptyList()
        val label = (d["label"] as? ConfigValue.Str)?.value ?: "?"
        val provider = (d["provider"] as? ConfigValue.Str)?.value
            ?.let { ConfigRefResolver.name(ConfigRefKind.ProviderInstance, it) } ?: ReadableArg.Text("?")
        val lines = mutableListOf(
            ReadableLine("Add thinking rule “%1\$@” to “%2\$@”", listOf(ReadableArg.Text(label), provider)),
        )
        d["wire_format"]?.let { wf ->
            val fmt = runCatching { ThinkingRuleCoding.decodeWireFormat(jsonStringFromConfigValue(wf)) }.getOrNull()
            if (fmt != null) {
                lines.add(ReadableLine("Request format: %@", listOf(ReadableArg.Text(com.openminis.app.ui.settings.wireFormatSummary(fmt)))))
            }
        }
        return lines
    }

    override fun readableForRemove(id: String): List<ReadableLine> {
        val (instanceId, _, rule) = ruleOf(id) ?: return emptyList()
        return listOf(
            ReadableLine(
                "Delete thinking rule “%1\$@” from “%2\$@”",
                listOf(ReadableArg.Text(rule.label), ConfigRefResolver.name(ConfigRefKind.ProviderInstance, instanceId)),
            ),
        )
    }

    private fun scopePhrase(v: ConfigValue): ReadableArg {
        val s = (v as? ConfigValue.Str)?.value ?: return ConfigReadableRenderer.genericPhrase(v)
        return if (s.equals("all", ignoreCase = true) || s.isBlank()) ReadableArg.Key("All models") else ReadableArg.Text(s)
    }

    /** wire_format JSON → the one-line summary the rule list shows. */
    private fun wireFormatPhrase(v: ConfigValue): ReadableArg {
        if (v == ConfigValue.Null || (v is ConfigValue.Str && v.value.isBlank())) return ReadableArg.Key("(none)")
        val fmt = runCatching { ThinkingRuleCoding.decodeWireFormat(jsonStringFromConfigValue(v)) }.getOrNull()
            ?: return ConfigReadableRenderer.genericPhrase(v)
        return ReadableArg.Text(com.openminis.app.ui.settings.wireFormatSummary(fmt))
    }

    // ---- JSON <-> ConfigValue bridge ----

    private fun configValueFromJson(json: String?): ConfigValue {
        if (json.isNullOrBlank()) return ConfigValue.Str("")
        return try {
            val o = JSONObject(json)
            ConfigValue.Obj(o.keys().asSequence().associateWith { k -> ConfigValue.Str(o.get(k).toString()) })
        } catch (_: Exception) {
            ConfigValue.Str(json)
        }
    }

    private fun jsonStringFromConfigValue(v: ConfigValue): String = when (v) {
        is ConfigValue.Str -> v.value
        is ConfigValue.Obj -> JSONObject().apply {
            for ((k, vv) in v.value) {
                when (vv) {
                    is ConfigValue.Str -> put(k, vv.value)
                    is ConfigValue.Int -> put(k, vv.value)
                    is ConfigValue.Obj -> put(k, JSONObject(jsonStringFromConfigValue(vv)))
                    else -> put(k, vv.toString())
                }
            }
        }.toString()
        else -> v.toString()
    }
}
