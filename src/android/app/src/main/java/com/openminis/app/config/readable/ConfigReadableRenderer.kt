package com.openminis.app.config.readable

import com.openminis.app.config.ConfigAccess
import com.openminis.app.config.ConfigCollection
import com.openminis.app.config.ConfigField
import com.openminis.app.config.ConfigRegistry
import com.openminis.app.config.ConfigSchema
import com.openminis.app.config.ConfigValue
import com.openminis.app.logging.AppLogger
import java.util.Locale

// [T-config-readable] App-side half of the readable descriptions: id → name
// resolution against the live stores, sentence assembly per formatter, and the
// launch-time template validation. The pure pieces live in ConfigReadable.kt.
// Port of iOS ConfigReadableRenderer.swift.
//
// Every sentence produced here is USER-FACING ONLY (confirmation dialog + Logs
// → Config Changes). Nothing here may be returned to the CLI or the model.

/** Names for ids, backed by the live stores; swappable in tests. */
interface ReadableNameSource {
    /** "Claude Sonnet 5.5 (Anthropic(53))", or null when no entry has this id. */
    fun modelEntryLabel(id: String): String?
    /** The instance label, its provider type's name when blank, or null. */
    fun instanceLabel(id: String): String?
    fun groupName(id: String): String?
    fun thinkingRuleLabel(instanceId: String, ruleId: String): String?
    /** Names of the groups that list this model entry. */
    fun groupsContaining(entryId: String): List<String>
    fun modelCountOf(instanceId: String): Int
    /** The sub agent's name; a default body, so name sources written before it still compile. */
    fun subAgentName(id: String): String? = null
}

private object NoNames : ReadableNameSource {
    override fun modelEntryLabel(id: String): String? = null
    override fun instanceLabel(id: String): String? = null
    override fun groupName(id: String): String? = null
    override fun thinkingRuleLabel(instanceId: String, ruleId: String): String? = null
    override fun groupsContaining(entryId: String): List<String> = emptyList()
    override fun modelCountOf(instanceId: String): Int = 0
}

object ConfigRefResolver {
    @Volatile var names: ReadableNameSource = NoNames

    /**
     * The user-facing name of a record, frozen as literal text. Unknown ids
     * read "unknown model 1234abcd…" rather than disappearing.
     */
    fun name(kind: ConfigRefKind, id: String): ReadableArg = when (kind) {
        ConfigRefKind.ModelEntry -> {
            val label = names.modelEntryLabel(id)
            when {
                label != null -> ReadableArg.Text(label)
                // A composite "<instance>/<model>" still names the model even
                // after the entry is gone.
                id.contains('/') -> {
                    val inst = id.substringBefore('/')
                    val model = id.substringAfter('/')
                    val instLabel = names.instanceLabel(inst)
                    unknown("unknown model %@", if (instLabel != null) "$model ($instLabel)" else model)
                }
                else -> unknown("unknown model %@", short(id))
            }
        }
        ConfigRefKind.Group ->
            names.groupName(id)?.let { ReadableArg.Text(it) } ?: unknown("unknown group %@", short(id))
        ConfigRefKind.ProviderInstance ->
            names.instanceLabel(id)?.let { ReadableArg.Text(it) } ?: unknown("unknown provider %@", short(id))
        is ConfigRefKind.ThinkingRule ->
            names.thinkingRuleLabel(kind.instanceId, id)?.let { ReadableArg.Text(it) }
                ?: unknown("unknown rule %@", short(id))
        ConfigRefKind.ModelSource -> when {
            id.startsWith("entry:") -> name(ConfigRefKind.ModelEntry, id.removePrefix("entry:"))
            id.startsWith("group:") -> ReadableArg.Line(
                ReadableLine("group “%@”", listOf(name(ConfigRefKind.Group, id.removePrefix("group:")))),
            )
            else -> ReadableArg.Text(id)
        }
        ConfigRefKind.SubAgent ->
            names.subAgentName(id)?.let { ReadableArg.Text(it) } ?: unknown("unknown sub agent %@", short(id))
    }

    private fun unknown(key: String, text: String) = ReadableArg.Line(ReadableLine(key, listOf(ReadableArg.Text(text))))

    fun short(id: String): String = if (id.length > 12) id.take(8) + "…" else id
}

object ConfigReadableRenderer {
    private const val TAG = "ConfigReadable"

    /** First element of a model list = the group's primary model. */
    const val primaryFirstKey = "primary is now %@"
    const val plainFirstKey = "first is now %@"

    /**
     * Key → format in the in-app UI language (null = no translation), and
     * that language's tag. Installed by ConfigBuiltins with the app context;
     * the defaults leave every key in English (JVM tests).
     */
    @Volatile var localizerFactory: () -> (String) -> String? = { { null } }
    @Volatile var currentLanguage: () -> String = { Locale.getDefault().toLanguageTag() }
    @Volatile var currentLocale: () -> Locale = { Locale.getDefault() }

    fun summary(lines: List<ReadableLine>, subject: String?): ReadableSummary {
        val localize = localizerFactory()
        val text = lines.joinToString("\n") { ReadableCore.render(it, localize) }
        return ReadableSummary(locale = currentLanguage(), text = text, lines = lines, subject = subject)
    }

    // -- Field writes (set / .append / .remove) --

    /**
     * Sentences for one field write. `.append` / `.remove` arrive as the whole
     * old and new arrays, so the list diff describes them naturally. Never
     * fails: anything unexpected falls back to the generic sentence.
     */
    fun forFieldWrite(field: ConfigField, old: RedactedConfigValue, new: RedactedConfigValue): ReadableSummary {
        val spec = field.readable
        val label = spec.fieldLabel ?: field.displayName
        var phrase: ReadableArg = ReadableArg.Key(label)
        var subject: String? = null
        val split = ConfigRegistry.splitCollectionPath(field.path)
        if (split != null) {
            val (base, id, _) = split
            val coll = runCatching { ConfigRegistry.get().collection(base) }.getOrNull()
            val entity = coll?.let { runCatching { it.entityLabel(id) }.getOrNull() }
            if (coll != null && entity != null) {
                phrase = ReadableArg.Line(
                    ReadableLine(coll.readableSubjectFormat, listOf(ReadableArg.Text(entity), ReadableArg.Key(label))),
                )
                subject = "$base:$id"
            }
        }
        val lines = try {
            sentences(spec.formatter, phrase, old.value, new.value)
        } catch (t: Throwable) {
            AppLogger.warning(TAG, "[ConfigReadable] fallback path=${field.path} reason=${t.message}")
            null
        } ?: listOf(genericLine(phrase, old.value, new.value))
        if (spec.isGeneric) {
            AppLogger.info(TAG, "[ConfigReadable] generic sentence for ${field.path} (${spec.genericReason})")
        }
        return summary(lines, subject)
    }

    private fun sentences(
        f: ConfigValueFormatter,
        phrase: ReadableArg,
        old: ConfigValue,
        new: ConfigValue,
    ): List<ReadableLine>? = when (f) {
        ConfigValueFormatter.Generic -> null
        ConfigValueFormatter.Secret -> {
            val s = (new as? ConfigValue.Str)?.value
            if (s != null && s.startsWith("\$\$") && s.length > 2) {
                listOf(
                    ReadableLine(
                        "%1\$@: now uses the environment variable $%2\$@",
                        listOf(phrase, ReadableArg.Text(s.drop(2))),
                    ),
                )
            } else {
                listOf(ReadableLine("%@: updated (value hidden)", listOf(phrase)))
            }
        }
        ConfigValueFormatter.LongText -> {
            val o = stringOrEmpty(old)
            val n = stringOrEmpty(new)
            when {
                o == null || n == null -> null
                o == n -> listOf(ReadableLine("%@: unchanged", listOf(phrase)))
                n.isEmpty() -> listOf(ReadableLine("%@: cleared", listOf(phrase)))
                else -> {
                    val (added, removed) = ReadableCore.lineDelta(o, n)
                    listOf(
                        ReadableLine(
                            "%1\$@: edited (+%2\$@ / −%3\$@ lines, %4\$@ characters now)",
                            listOf(
                                phrase, ReadableArg.Text("$added"), ReadableArg.Text("$removed"),
                                ReadableArg.Text("${n.codePointCount(0, n.length)}"),
                            ),
                        ),
                    )
                }
            }
        }
        is ConfigValueFormatter.RefList -> {
            val firstKey = if (f.kind == ConfigRefKind.ModelEntry) primaryFirstKey else plainFirstKey
            listLine(phrase, strings(old), strings(new), f.ordered, firstKey) { ConfigRefResolver.name(f.kind, it) }
                ?.let { listOf(it) }
        }
        is ConfigValueFormatter.StringList -> {
            val split = f.splitText
            fun items(v: ConfigValue): List<String>? =
                if (split != null && v is ConfigValue.Str) split(v.value) else strings(v)
            listLine(phrase, items(old), items(new), f.ordered, plainFirstKey) { raw ->
                f.labels[raw]?.let { ReadableArg.Key(it) } ?: ReadableArg.Text(raw)
            }?.let { listOf(it) }
        }
        is ConfigValueFormatter.Sentence -> f.make(old, new)
        else -> {
            val o = phraseFor(f, old)
            val n = phraseFor(f, new)
            if (o == null || n == null) null else listOf(changeLine(phrase, o, n))
        }
    }

    /** "<field>: <old> → <new>", or "<field>: <value> (unchanged)". */
    fun changeLine(phrase: ReadableArg, old: ReadableArg, new: ReadableArg): ReadableLine =
        if (old == new) {
            ReadableLine("%1\$@: %2\$@ (unchanged)", listOf(phrase, new))
        } else {
            ReadableLine("%1\$@: %2\$@ → %3\$@", listOf(phrase, old, new))
        }

    fun genericLine(phrase: ReadableArg, old: ConfigValue, new: ConfigValue): ReadableLine =
        changeLine(phrase, genericPhrase(old), genericPhrase(new))

    /** Raw-value phrase for the fallback sentence. */
    fun genericPhrase(v: ConfigValue): ReadableArg = when (v) {
        ConfigValue.Null -> ReadableArg.Key("(none)")
        is ConfigValue.Bool -> ReadableArg.Key(if (v.value) "On" else "Off")
        is ConfigValue.Int -> ReadableArg.Text(v.value.toString())
        is ConfigValue.Double -> ReadableArg.Text(ReadableCore.plainNumber(v.value))
        is ConfigValue.Str ->
            if (v.value.isEmpty()) ReadableArg.Key("(empty)") else ReadableArg.Text(ReadableCore.quoted(v.value, 40))
        is ConfigValue.Arr -> ReadableArg.Line(ReadableLine("%@ items", listOf(ReadableArg.Text("${v.value.size}"))))
        is ConfigValue.Obj -> ReadableArg.Key("(settings object)")
    }

    /**
     * One value → phrase for the value formatters; null when the value does
     * not have the shape the formatter expects.
     */
    fun phraseFor(f: ConfigValueFormatter, v: ConfigValue): ReadableArg? = when (f) {
        is ConfigValueFormatter.Bool -> (v as? ConfigValue.Bool)?.let { ReadableArg.Key(if (it.value) f.on else f.off) }
        is ConfigValueFormatter.EnumLabels -> (v as? ConfigValue.Str)?.value?.let { s ->
            f.labels[s]?.let { ReadableArg.Key(it) }
                ?: if (s.isEmpty()) ReadableArg.Key("(empty)") else ReadableArg.Text(s)
        }
        is ConfigValueFormatter.Number -> {
            val d = when (v) {
                is ConfigValue.Int -> v.value.toDouble()
                is ConfigValue.Double -> v.value
                else -> null
            }
            when {
                d == null -> null
                d == 0.0 && f.zero != null -> ReadableArg.Key(f.zero)
                else -> when (f.unit) {
                    NumberUnit.PLAIN -> ReadableArg.Text(ReadableCore.plainNumber(d))
                    NumberUnit.TOKENS -> ReadableArg.Line(
                        ReadableLine("%@ tokens", listOf(ReadableArg.Text(ReadableCore.compactCount(d.toInt())))),
                    )
                    NumberUnit.SECONDS -> ReadableArg.Line(
                        ReadableLine("%@ s", listOf(ReadableArg.Text(ReadableCore.plainNumber(d)))),
                    )
                    NumberUnit.POINTS -> ReadableArg.Line(
                        ReadableLine("%@ pt", listOf(ReadableArg.Text(ReadableCore.plainNumber(d)))),
                    )
                    NumberUnit.BYTES -> ReadableArg.Text(byteCount(d.toLong()))
                }
            }
        }
        is ConfigValueFormatter.Text -> (v as? ConfigValue.Str)?.value?.let { s ->
            if (s.isEmpty()) ReadableArg.Key(f.empty) else ReadableArg.Text(ReadableCore.quoted(s, f.preview))
        }
        is ConfigValueFormatter.Url -> (v as? ConfigValue.Str)?.value?.let { s ->
            if (s.isEmpty()) ReadableArg.Key(f.empty) else ReadableArg.Text(ReadableCore.safeURL(s))
        }
        ConfigValueFormatter.Image -> (v as? ConfigValue.Str)?.value?.let { s -> imagePhrase(s) }
        is ConfigValueFormatter.Ref -> (v as? ConfigValue.Str)?.value?.let { s ->
            if (s.isEmpty()) ReadableArg.Key(f.empty) else ConfigRefResolver.name(f.kind, s)
        }
        is ConfigValueFormatter.Custom -> f.make(v)
        ConfigValueFormatter.Generic, ConfigValueFormatter.Secret, ConfigValueFormatter.LongText,
        is ConfigValueFormatter.RefList, is ConfigValueFormatter.StringList, is ConfigValueFormatter.Sentence,
        -> null
    }

    /**
     * The Soul icon. Android also accepts bare base64 and minis:// / /var/minis
     * paths as an image source (iOS only data URIs), so anything that is not a
     * single short glyph reads "Custom image" — never the payload or a path.
     */
    private fun imagePhrase(s: String): ReadableArg = when {
        s.isEmpty() -> ReadableArg.Key("Default icon")
        s == "<image>" || RedactedConfigValue.isInlineImage(s) -> ReadableArg.Key("Custom image")
        s.codePointCount(0, s.length) <= 8 && s.none { it == '/' || it == ':' } -> ReadableArg.Text(s)
        else -> ReadableArg.Key("Custom image")
    }

    /** 1536 → "1.5 KB", 10_000_000 → "10 MB" (decimal units, like iOS .file). */
    private fun byteCount(n: Long): String {
        if (n < 1000) return "$n bytes"
        val units = listOf("KB", "MB", "GB", "TB")
        var v = n.toDouble() / 1000
        var i = 0
        while (v >= 1000 && i < units.lastIndex) { v /= 1000; i++ }
        return "${ReadableCore.plainNumber(if (v >= 100) Math.rint(v) else Math.rint(v * 10) / 10)} ${units[i]}"
    }

    // -- Lists --

    fun listLine(
        phrase: ReadableArg,
        old: List<String>?,
        new: List<String>?,
        ordered: Boolean,
        firstKey: String,
        name: (String) -> ReadableArg,
    ): ReadableLine? {
        if (old == null || new == null) return null
        val d = ReadableCore.listDiff(old, new, ordered)
        if (d.isEmpty) return ReadableLine("%@: unchanged", listOf(phrase))
        val clauses = ArrayList<ReadableArg>()
        d.newFirst?.let { clauses.add(ReadableArg.Line(ReadableLine(firstKey, listOf(name(it))))) }
        if (d.added.isNotEmpty()) clauses.add(ReadableArg.Line(ReadableLine("added %@", listOf(nameList(d.added, name)))))
        if (d.removed.isNotEmpty()) clauses.add(ReadableArg.Line(ReadableLine("removed %@", listOf(nameList(d.removed, name)))))
        if (d.reordered) {
            clauses.add(ReadableArg.Key("order changed"))
        } else if (ordered && d.keptCount > 0 && clauses.isNotEmpty()) {
            clauses.add(ReadableArg.Key("rest of the order unchanged"))
        }
        if (new.isEmpty()) clauses.add(ReadableArg.Key("now empty"))
        return ReadableLine("%1\$@: %2\$@", listOf(phrase, ReadableArg.Clauses(clauses)))
    }

    /**
     * Names merged by what they read as (two ids that name the same thing
     * count together), "×N" for repeats, the first few then "+N more".
     */
    fun nameList(items: List<ReadableCore.ListItem>, name: (String) -> ReadableArg): ReadableArg {
        val counts = LinkedHashMap<ReadableArg, Int>()
        for (item in items) {
            val n = name(item.id)
            counts[n] = (counts[n] ?: 0) + item.count
        }
        val rendered = counts.map { (n, c) ->
            if (c > 1) ReadableArg.Line(ReadableLine("%1\$@ ×%2\$@", listOf(n, ReadableArg.Text("$c")))) else n
        }
        val cap = ReadableCore.MAX_LISTED_NAMES
        if (rendered.size <= cap) return ReadableArg.Items(rendered)
        return ReadableArg.Line(
            ReadableLine(
                "%1\$@ and %2\$@ more",
                listOf(ReadableArg.Items(rendered.take(cap)), ReadableArg.Text("${rendered.size - cap}")),
            ),
        )
    }

    private fun strings(v: ConfigValue): List<String>? = when (v) {
        ConfigValue.Null -> emptyList()
        is ConfigValue.Arr -> v.value.map { (it as? ConfigValue.Str)?.value ?: it.displayString }
        else -> null
    }

    private fun stringOrEmpty(v: ConfigValue): String? = when (v) {
        ConfigValue.Null -> ""
        is ConfigValue.Str -> v.value
        else -> null
    }

    // -- Collection add / remove --

    fun forCollectionAdd(c: ConfigCollection, payload: RedactedConfigValue): ReadableSummary {
        val lines = runCatching { c.readableForAdd(payload) }.getOrElse {
            AppLogger.warning(TAG, "[ConfigReadable] fallback path=${c.basePath}.append reason=${it.message}")
            emptyList()
        }.ifEmpty { listOf(ReadableLine("%@: new entry added", listOf(ReadableArg.Key(c.displayName)))) }
        return summary(lines, "${c.basePath}:new")
    }

    /** Rendered BEFORE the removal, so the record is still named. */
    fun forCollectionRemove(c: ConfigCollection, childId: String): ReadableSummary {
        val lines = runCatching { c.readableForRemove(childId) }.getOrElse {
            AppLogger.warning(TAG, "[ConfigReadable] fallback path=${c.basePath}.remove reason=${it.message}")
            emptyList()
        }.ifEmpty {
            val label = runCatching { c.entityLabel(childId) }.getOrNull() ?: childId
            listOf(ReadableLine("%1\$@: removed %2\$@", listOf(ReadableArg.Key(c.displayName), ReadableArg.Text(label))))
        }
        return summary(lines, "${c.basePath}:$childId")
    }

    // -- Launch validation --

    /**
     * Logs every writable field still on the generic template and every enum
     * label table that misses a case of its schema — the strong reminder
     * when a new setting lands. iOS additionally asserts in DEBUG; here a
     * gap is an error log plus a unit test (ConfigReadableKeysTest), so a
     * debug build never crashes at launch over a missing label.
     */
    fun validateRegistry(fields: List<ConfigField>): List<String> {
        var writable = 0
        val generic = ArrayList<String>()
        val gaps = ArrayList<String>()
        for (f in fields) {
            if (f.access != ConfigAccess.READWRITE) continue
            writable++
            val spec = f.readable
            if (spec.isGeneric) generic.add("${f.path} (${spec.genericReason})")
            val labels = (spec.formatter as? ConfigValueFormatter.EnumLabels)?.labels ?: continue
            for (c in enumCases(f.valueSchema)) if (c !in labels) gaps.add("${f.path}: '$c'")
        }
        AppLogger.info(
            TAG,
            "[ConfigReadable] validated $writable writable fields; generic=${generic.size}" +
                if (generic.isEmpty()) "" else " " + generic.joinToString(", "),
        )
        if (gaps.isNotEmpty()) AppLogger.error(TAG, "[ConfigReadable] enum label gaps: ${gaps.joinToString("; ")}")
        return gaps
    }

    private fun enumCases(s: ConfigSchema): List<String> = when (s) {
        is ConfigSchema.StrEnum -> s.cases
        is ConfigSchema.Optional -> enumCases(s.inner)
        else -> emptyList()
    }
}

/** [T-config-readable] Raw thinking token → the label keys iOS uses. */
object ReadableThinkingLabels {
    val levels: Map<String, String> = mapOf(
        "off" to "Off", "low" to "Low", "medium" to "Med", "high" to "High",
        "xhigh" to "XHigh", "max" to "Max", "ultra" to "Ultra",
    )

    /** "" = not set (sessions start with thinking off / the model default). */
    val withNotSet: Map<String, String> = levels + ("" to "Not set")
}

/** [ReadableNameSource] over the live provider config. */
class ProviderRepositoryNames(
    private val repo: com.openminis.app.data.repository.ProviderRepository,
) : ReadableNameSource {
    private val cfg get() = repo.config.value

    override fun modelEntryLabel(id: String): String? {
        val e = cfg.modelEntries.firstOrNull { it.id == id } ?: return null
        val model = e.model.displayName.ifEmpty { e.baseModel.id }
        val label = instanceLabel(e.providerInstanceId) ?: return model
        return "$model ($label)"
    }

    override fun instanceLabel(id: String): String? {
        val inst = cfg.instances.firstOrNull { it.id == id } ?: return null
        return inst.label.trim().ifEmpty { inst.providerType.displayName }
    }

    override fun groupName(id: String): String? = cfg.modelGroups.firstOrNull { it.id == id }?.name

    override fun thinkingRuleLabel(instanceId: String, ruleId: String): String? {
        val idx = repo.thinkingRuleIds(instanceId).indexOf(ruleId)
        if (idx < 0) return null
        return repo.thinkingRules(instanceId).getOrNull(idx)?.label
    }

    override fun groupsContaining(entryId: String): List<String> =
        cfg.modelGroups.filter { entryId in it.memberEntryIds }.map { it.name }

    override fun modelCountOf(instanceId: String): Int = cfg.modelEntries.count { it.providerInstanceId == instanceId }

    override fun subAgentName(id: String): String? = repo.subAgent(id)?.name
}
