package com.openminis.app.config.readable

import com.openminis.app.config.ConfigValue
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.text.NumberFormat
import java.util.Locale

// [T-config-readable] Human-readable descriptions of minis-config changes.
// Android port of iOS 70f7871d7 / 6bac054f2 (Shared/Config/Readable/ConfigReadable.swift).
//
// The confirmation dialog and Logs → Config Changes used to show only the raw
// path and JSON old/new values; for a model group's `entries` that is a list
// of "<instanceUUID>/<modelId>" strings nobody can read. Every writable field
// now declares, at registration, how its values read to a person
// ([ConfigReadable]), and the bridge renders one sentence per change BEFORE
// the confirmation dialog (names of things about to be removed are gone after
// the write). The same rendering is persisted with the audit row.
//
// USER-FACING ONLY. The text is for the person approving the change and
// reviewing the log. It never reaches the model: no CLI response, audit
// list/get output or user_message carries it (see ConfigBridge.auditEntryDict).
//
// This file has no Android dependencies so JVM unit tests can cover it.

// -- Declaration (per field) --

/** How a field's change reads to a person. Required for every ConfigField. */
class ConfigReadable private constructor(
    /**
     * Localization key for the field noun ("Default thinking level"). null =
     * the field's displayName, which is then also the key.
     */
    val fieldLabel: String?,
    val formatter: ConfigValueFormatter,
    /**
     * Set only by [generic]: why this field has no dedicated template.
     * Launch validation lists every writable field that has one.
     */
    val genericReason: String?,
) {
    constructor(formatter: ConfigValueFormatter, label: String? = null) : this(label, formatter, null)

    val isGeneric: Boolean get() = genericReason != null

    companion object {
        /**
         * Explicit, greppable opt-out: renders "<field>: <old> → <new>" from
         * the raw values. Every use must say why; validation logs each
         * writable one.
         */
        fun generic(reason: String): ConfigReadable = ConfigReadable(null, ConfigValueFormatter.Generic, reason)

        /** For fields that never reach the write path (read-only / hidden). */
        val notWritable: ConfigReadable = generic("not writable through minis-config")
    }
}

/** What kind of record an id refers to, for id → name resolution. */
sealed class ConfigRefKind {
    object ModelEntry : ConfigRefKind()
    object Group : ConfigRefKind()
    object ProviderInstance : ConfigRefKind()
    data class ThinkingRule(val instanceId: String) : ConfigRefKind()
    /** `entry:<id>` / `group:<id>` session model sources. */
    object ModelSource : ConfigRefKind()
    /** A sub agent definition id (`subagents.order`). */
    object SubAgent : ConfigRefKind()
}

enum class NumberUnit { PLAIN, TOKENS, SECONDS, BYTES, POINTS }

/**
 * How a value reads. All label arguments are localization keys (English
 * source strings, see ConfigReadableStrings).
 */
sealed class ConfigValueFormatter {
    /** Raw-value fallback; only reachable through [ConfigReadable.generic]. */
    object Generic : ConfigValueFormatter()
    data class Bool(val on: String = "On", val off: String = "Off") : ConfigValueFormatter()
    /** Raw enum value → label key. Must cover every case of the schema. */
    data class EnumLabels(val labels: Map<String, String>) : ConfigValueFormatter()
    /** [zero] (a key) replaces 0, e.g. "No limit". */
    data class Number(val unit: NumberUnit = NumberUnit.PLAIN, val zero: String? = null) : ConfigValueFormatter()
    data class Text(val preview: Int = 40, val empty: String = "(empty)") : ConfigValueFormatter()
    /** Long prose: reports added / removed line counts, never the content. */
    object LongText : ConfigValueFormatter()
    /** Query, fragment and user-info are dropped (they can carry tokens). */
    data class Url(val empty: String = "Default") : ConfigValueFormatter()
    object Image : ConfigValueFormatter()
    /** Never renders the value. A `$$VAR` reference names the variable only. */
    object Secret : ConfigValueFormatter()
    data class Ref(val kind: ConfigRefKind, val empty: String = "Not set") : ConfigValueFormatter()
    data class RefList(val kind: ConfigRefKind, val ordered: Boolean) : ConfigValueFormatter()
    /**
     * Plain string lists (DNS servers, modalities); [labels] maps raw → key.
     * [splitText] reads a list stored as one string (Android keeps the
     * sandbox DNS servers as a newline-separated string).
     */
    data class StringList(
        val ordered: Boolean,
        val labels: Map<String, String> = emptyMap(),
        val splitText: ((String) -> List<String>)? = null,
    ) : ConfigValueFormatter()
    /** One value → one phrase; composed into the standard change sentence. */
    class Custom(val make: (ConfigValue) -> ReadableArg) : ConfigValueFormatter()
    /** Whole sentences from (old, new); null falls back to the generic sentence. */
    class Sentence(val make: (old: ConfigValue, new: ConfigValue) -> List<ReadableLine>?) : ConfigValueFormatter()
}

// -- Rendered form (persisted) --

/**
 * One sentence: a localization key (English format string using `%@` /
 * `%n$@` placeholders, as on iOS) plus its arguments. Names are frozen as
 * literal text at write time; labels stay keys so the sentence re-renders in
 * whatever language the UI uses later.
 */
data class ReadableLine(val key: String, val args: List<ReadableArg> = emptyList())

sealed class ReadableArg {
    /** Frozen literal: a group / model / provider name, a number, a URL. */
    data class Text(val value: String) : ReadableArg()
    /** Localization key, resolved at display time. */
    data class Key(val key: String) : ReadableArg()
    data class Line(val line: ReadableLine) : ReadableArg()
    /** Items joined with the localized ", ". */
    data class Items(val items: List<ReadableArg>) : ReadableArg()
    /** Clauses joined with the localized "; ". */
    data class Clauses(val items: List<ReadableArg>) : ReadableArg()
}

/**
 * What the confirmation dialog shows and the audit row persists (`readable`
 * column). The JSON matches the iOS Codable encoding of the same type, so a
 * record reads the same on both platforms.
 */
data class ReadableSummary(
    /** Schema version of this JSON. */
    val v: Int = 1,
    /** UI language [text] was rendered in (informational). */
    val locale: String,
    /** Pre-rendered text; used when [lines] cannot be rendered. */
    val text: String,
    val lines: List<ReadableLine>,
    /**
     * Groups rows about the same record in the batch summary, e.g.
     * "groups:<id>". null = a global setting.
     */
    val subject: String?,
) {
    /** Rendered in the CURRENT UI language from [lines]; [text] if there are none. */
    fun displayText(localize: (String) -> String?): String {
        if (lines.isEmpty()) return text
        return lines.joinToString("\n") { ReadableCore.render(it, localize) }
    }

    fun jsonString(): String = JSONObject().apply {
        put("v", v)
        put("locale", locale)
        put("text", text)
        put("lines", JSONArray().also { arr -> lines.forEach { arr.put(ReadableJson.encode(it)) } })
        if (subject != null) put("subject", subject)
    }.toString()

    companion object {
        fun decode(json: String?): ReadableSummary? {
            if (json.isNullOrEmpty()) return null
            return try {
                val o = JSONObject(json)
                val arr = o.getJSONArray("lines")
                ReadableSummary(
                    v = o.optInt("v", 1),
                    locale = o.optString("locale", ""),
                    text = o.getString("text"),
                    lines = (0 until arr.length()).map { ReadableJson.decodeLine(arr.getJSONObject(it)) },
                    subject = if (o.has("subject") && !o.isNull("subject")) o.getString("subject") else null,
                )
            } catch (_: Exception) {
                null
            }
        }
    }
}

/**
 * Swift's synthesized Codable shape for [ReadableLine] / [ReadableArg]:
 * `{"key":"…","args":[…]}` and `{"text":{"_0":"…"}}`, `{"key":{"_0":"…"}}`,
 * `{"line":{"_0":{…}}}`, `{"list":{"_0":[…]}}`, `{"clauses":{"_0":[…]}}`.
 */
internal object ReadableJson {
    fun encode(line: ReadableLine): JSONObject = JSONObject().apply {
        put("key", line.key)
        put("args", JSONArray().also { arr -> line.args.forEach { arr.put(encode(it)) } })
    }

    private fun encode(arg: ReadableArg): JSONObject {
        val (case, payload) = when (arg) {
            is ReadableArg.Text -> "text" to arg.value
            is ReadableArg.Key -> "key" to arg.key
            is ReadableArg.Line -> "line" to encode(arg.line)
            is ReadableArg.Items -> "list" to JSONArray().also { a -> arg.items.forEach { a.put(encode(it)) } }
            is ReadableArg.Clauses -> "clauses" to JSONArray().also { a -> arg.items.forEach { a.put(encode(it)) } }
        }
        return JSONObject().put(case, JSONObject().put("_0", payload))
    }

    fun decodeLine(o: JSONObject): ReadableLine {
        val args = o.optJSONArray("args") ?: JSONArray()
        return ReadableLine(o.getString("key"), (0 until args.length()).map { decodeArg(args.getJSONObject(it)) })
    }

    private fun decodeArg(o: JSONObject): ReadableArg {
        val case = o.keys().next()
        val body = o.getJSONObject(case)
        return when (case) {
            "text" -> ReadableArg.Text(body.getString("_0"))
            "key" -> ReadableArg.Key(body.getString("_0"))
            "line" -> ReadableArg.Line(decodeLine(body.getJSONObject("_0")))
            "list" -> ReadableArg.Items(decodeArgs(body.getJSONArray("_0")))
            "clauses" -> ReadableArg.Clauses(decodeArgs(body.getJSONArray("_0")))
            else -> throw IllegalArgumentException("unknown ReadableArg case $case")
        }
    }

    private fun decodeArgs(a: JSONArray): List<ReadableArg> =
        (0 until a.length()).map { decodeArg(a.getJSONObject(it)) }
}

// -- Redaction guarantee --

/**
 * A value that has been through the same masking the audit log and the
 * confirmation dialog apply: credential keys (`apiKey`, `oauthToken`, …)
 * collapse to a fixed mask unless they are a `$$VAR` reference, and an inline
 * image data URI collapses to "<image>". The renderer only accepts this type,
 * so an unmasked secret cannot be passed to it by mistake — every factory
 * masks.
 */
class RedactedConfigValue private constructor(val value: ConfigValue) {
    companion object {
        /** A single field's value; [fieldLeaf] is the last path segment. */
        fun field(fieldLeaf: String, value: ConfigValue): RedactedConfigValue {
            var v = value
            if (fieldLeaf in ConfigValue.SECRET_KEYS) {
                val masked = (ConfigValue.Obj(mapOf(fieldLeaf to value)).redactingSecrets() as ConfigValue.Obj).value
                masked[fieldLeaf]?.let { v = it }
            }
            if (v is ConfigValue.Str && isInlineImage(v.value)) v = ConfigValue.Str("<image>")
            return RedactedConfigValue(v)
        }

        /** A collection `add` payload: every nested credential key is masked. */
        fun payload(payload: ConfigValue): RedactedConfigValue =
            RedactedConfigValue(maskImages(payload.redactingSecrets()))

        fun isInlineImage(s: String): Boolean = s.startsWith("data:image/")

        private fun maskImages(v: ConfigValue): ConfigValue = when (v) {
            is ConfigValue.Str -> if (isInlineImage(v.value)) ConfigValue.Str("<image>") else v
            is ConfigValue.Arr -> ConfigValue.Arr(v.value.map(::maskImages))
            is ConfigValue.Obj -> ConfigValue.Obj(v.value.mapValues { maskImages(it.value) })
            else -> v
        }
    }
}

// -- Pure helpers (no app state; covered by JVM tests) --

object ReadableCore {
    /** At most this many names are listed per clause; the rest are counted. */
    const val MAX_LISTED_NAMES = 5

    /** Localized separators ("、" / "；" in Chinese). */
    const val listSeparatorKey = ", "
    const val clauseSeparatorKey = "; "

    data class ListItem(val id: String, val count: Int)

    data class ListDiff(
        /** The new first element, when ordered and it changed. */
        val newFirst: String?,
        val added: List<ListItem>,
        val removed: List<ListItem>,
        /** The elements present in both lists are in a different order. */
        val reordered: Boolean,
        /** How many elements are present in both lists. */
        val keptCount: Int,
    ) {
        val isEmpty: Boolean get() = newFirst == null && added.isEmpty() && removed.isEmpty() && !reordered
    }

    /**
     * Multiset diff of two id lists. Duplicates count: [a, a] → [a] removes
     * one a. Kept elements are compared by relative order only, so an add or
     * remove never reads as a reorder.
     */
    fun listDiff(old: List<String>, new: List<String>, ordered: Boolean): ListDiff {
        val oldCount = old.groupingBy { it }.eachCount()
        val newCount = new.groupingBy { it }.eachCount()

        val added = new.distinct().mapNotNull { id ->
            val d = (newCount[id] ?: 0) - (oldCount[id] ?: 0)
            if (d > 0) ListItem(id, d) else null
        }
        val removed = old.distinct().mapNotNull { id ->
            val d = (oldCount[id] ?: 0) - (newCount[id] ?: 0)
            if (d > 0) ListItem(id, d) else null
        }

        fun kept(list: List<String>): List<String> {
            val budget = HashMap<String, Int>()
            for ((id, n) in oldCount) budget[id] = minOf(n, newCount[id] ?: 0)
            val out = ArrayList<String>()
            for (id in list) {
                val b = budget[id] ?: 0
                if (b > 0) {
                    budget[id] = b - 1
                    out.add(id)
                }
            }
            return out
        }
        val keptOld = kept(old)
        val keptNew = kept(new)

        val first = new.firstOrNull()
        val newFirst = if (ordered && first != null && first != old.firstOrNull()) first else null
        return ListDiff(newFirst, added, removed, reordered = keptOld != keptNew, keptCount = keptNew.size)
    }

    private class Placeholder(val start: Int, val end: Int, val position: Int?)

    /**
     * Parse `%@` / `%n$@` (iOS keys) and `%s` / `%n$s` (Android resources)
     * placeholders; `%%` is literal. null when the format uses any other
     * conversion or mixes sequential and positional placeholders.
     */
    private fun placeholders(format: String): List<Placeholder>? {
        val out = ArrayList<Placeholder>()
        var sequential = false
        var positional = false
        var i = 0
        while (i < format.length) {
            if (format[i] != '%') { i++; continue }
            val start = i
            i++
            if (i >= format.length) return null
            if (format[i] == '%') { i++; continue }
            if (format[i] == '@' || format[i] == 's') {
                sequential = true
                out.add(Placeholder(start, i + 1, null))
                i++
                continue
            }
            val digitsStart = i
            while (i < format.length && format[i].isDigit()) i++
            if (i == digitsStart || i + 1 >= format.length || format[i] != '$' ||
                (format[i + 1] != '@' && format[i + 1] != 's')
            ) return null
            val n = format.substring(digitsStart, i).toIntOrNull() ?: return null
            if (n < 1) return null
            positional = true
            out.add(Placeholder(start, i + 2, n))
            i += 2
        }
        if (sequential && positional) return null
        return out
    }

    /**
     * Number of arguments a format consumes; -1 when the format is unusable
     * here. Mirrors iOS `placeholderCount`, also accepting Android's `s`.
     */
    fun placeholderCount(format: String): Int {
        val ps = placeholders(format) ?: return -1
        if (ps.isEmpty()) return 0
        return if (ps.first().position == null) ps.size else ps.maxOf { it.position!! }
    }

    /** Substitute [args] into [format]; the caller checked [placeholderCount]. */
    private fun substitute(format: String, args: List<String>): String {
        val ps = placeholders(format) ?: return format
        val sb = StringBuilder()
        var last = 0
        var seq = 0
        var i = 0
        while (i < format.length) {
            val p = ps.firstOrNull { it.start == i }
            if (p != null) {
                sb.append(format, last, p.start)
                val idx = p.position?.minus(1) ?: seq++
                sb.append(args.getOrElse(idx) { "" })
                i = p.end
                last = i
                continue
            }
            if (format[i] == '%' && i + 1 < format.length && format[i + 1] == '%') {
                sb.append(format, last, i).append('%')
                i += 2
                last = i
                continue
            }
            i++
        }
        sb.append(format, last, format.length)
        return sb.toString()
    }

    /**
     * Render with [localize] (key → format in the current language; null =
     * no translation). A translation whose placeholders do not match the
     * arguments is ignored in favour of the English key, so a bad
     * translation can never crash formatting or drop an argument.
     */
    fun render(line: ReadableLine, localize: (String) -> String?): String {
        val args = line.args.map { render(it, localize) }
        var format = localize(line.key) ?: line.key
        if (placeholderCount(format) != args.size) format = line.key
        if (placeholderCount(format) != args.size) return (listOf(line.key) + args).joinToString(" ")
        return substitute(format, args)
    }

    fun render(arg: ReadableArg, localize: (String) -> String?): String = when (arg) {
        is ReadableArg.Text -> arg.value
        is ReadableArg.Key -> localize(arg.key) ?: arg.key
        is ReadableArg.Line -> render(arg.line, localize)
        is ReadableArg.Items -> arg.items.joinToString(localize(listSeparatorKey) ?: listSeparatorKey) {
            render(it, localize)
        }
        is ReadableArg.Clauses -> arg.items.joinToString(localize(clauseSeparatorKey) ?: clauseSeparatorKey) {
            render(it, localize)
        }
    }

    /** Quote and shorten free text for a sentence. */
    fun quoted(s: String, preview: Int): String {
        val flat = s.replace("\n", " ")
        val short = if (flat.codePointCount(0, flat.length) > preview) {
            flat.substring(0, flat.offsetByCodePoints(0, preview)) + "…"
        } else {
            flat
        }
        return "“$short”"
    }

    /** Lines added / removed between two texts (multiset of lines). */
    fun lineDelta(old: String, new: String): Pair<Int, Int> {
        val o = if (old.isEmpty()) emptyList() else old.split("\n")
        val n = if (new.isEmpty()) emptyList() else new.split("\n")
        val d = listDiff(o, n, ordered = false)
        return d.added.sumOf { it.count } to d.removed.sumOf { it.count }
    }

    /** Drops user-info, query and fragment: any of them can carry a token. */
    fun safeURL(s: String): String {
        val uri = try { URI(s) } catch (_: Exception) { null }
        if (uri == null || uri.scheme == null || uri.isOpaque) {
            return s.substringBefore('?').substringBefore('#')
        }
        return try {
            URI(uri.scheme, null, uri.host, uri.port, uri.path, null, null).toString()
        } catch (_: Exception) {
            s.substringBefore('?').substringBefore('#')
        }
    }

    /** 64000 → "64K", 131072 → "128K", 1_048_576 → "1M", 1500 → "1,500". */
    fun compactCount(n: Int): String {
        if (n >= 1_000_000 && n % 1_000_000 == 0) return "${n / 1_000_000}M"
        if (n >= 1_048_576 && n % 1_048_576 == 0) return "${n / 1_048_576}M"
        if (n >= 1_000 && n % 1_000 == 0) return "${n / 1_000}K"
        if (n >= 1_024 && n % 1_024 == 0) return "${n / 1_024}K"
        return NumberFormat.getIntegerInstance(Locale.US).format(n)
    }

    /** Up to two decimals, no trailing zeros. */
    fun plainNumber(d: Double): String {
        if (d == Math.rint(d) && Math.abs(d) < 1e15) return d.toLong().toString()
        val f = NumberFormat.getNumberInstance(Locale.US)
        f.minimumFractionDigits = 0
        f.maximumFractionDigits = 2
        f.minimumIntegerDigits = 1
        f.isGroupingUsed = false
        return f.format(d)
    }
}
