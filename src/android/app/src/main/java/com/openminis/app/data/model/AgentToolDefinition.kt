package com.openminis.app.data.model

import org.json.JSONArray
import org.json.JSONObject

/**
 * Provider-agnostic tool definition. Each tool registers with this structure,
 * and providers convert it to their native format (Anthropic input_schema,
 * Gemini function_declarations, OpenAI function calling).
 */
data class AgentToolDefinition(
    val name: String,
    val description: String,
    val parameters: Map<String, AgentToolParam>,
    val required: List<String> = emptyList(),
    val propertyOrdering: List<String>? = null,
) {
    /**
     * [T-port-nested-json-string] The arguments with every nested-schema
     * parameter that arrived as JSON-in-a-string decoded to the array / object
     * its schema declares. Some OpenAI-compatible stacks (DeepSeek V4 Flash via
     * SenseNova and Bailian, 5 of 7 user_ask calls on a Pixel 4a) send
     * `"questions":"[{...}]"` instead of a nested array. Returns the same
     * instance when nothing changed; a string that does not parse is left
     * as is, so the tool's own parser can tell the model exactly what is wrong.
     */
    fun coerceNestedJsonStrings(args: JSONObject): JSONObject {
        var out: JSONObject? = null
        for ((key, p) in parameters) {
            val node = p.schema ?: continue
            if (!args.has(key)) continue
            val v = args.opt(key)
            val c = node.coerce(v)
            if (c !== v) {
                if (out == null) out = JSONObject(args.toString())
                out.put(key, c)
            }
        }
        return out ?: args
    }

    /** Anthropic format: {name, description, input_schema: {type:object, properties, required}} */
    fun toAnthropicJson(): JSONObject {
        val props = JSONObject()
        for ((key, param) in parameters) {
            props.put(key, param.toJson())
        }
        val schema = JSONObject().apply {
            put("type", "object")
            put("properties", props)
            if (required.isNotEmpty()) put("required", JSONArray(required))
        }
        return JSONObject().apply {
            put("name", name)
            put("description", description)
            put("input_schema", schema)
        }
    }

    /** Gemini format: {name, description, parameters: {type:OBJECT, properties, required}} */
    fun toGeminiJson(): JSONObject {
        val props = JSONObject()
        for ((key, param) in parameters) {
            props.put(key, param.toGeminiJson())
        }
        val params = JSONObject().apply {
            put("type", "OBJECT")
            put("properties", props)
            if (required.isNotEmpty()) put("required", JSONArray(required))
            if (propertyOrdering != null) put("propertyOrdering", JSONArray(propertyOrdering))
        }
        return JSONObject().apply {
            put("name", name)
            put("description", description)
            put("parameters", params)
        }
    }

    /** OpenAI format: {type:function, function: {name, description, parameters: {type:object, ...}}} */
    fun toOpenAIJson(): JSONObject {
        val props = JSONObject()
        for ((key, param) in parameters) {
            props.put(key, param.toJson())
        }
        val params = JSONObject().apply {
            put("type", "object")
            put("properties", props)
            if (required.isNotEmpty()) put("required", JSONArray(required))
        }
        return JSONObject().apply {
            put("type", "function")
            put("function", JSONObject().apply {
                put("name", name)
                put("description", description)
                put("parameters", params)
            })
        }
    }
}

data class AgentToolParam(
    val type: String,
    val description: String,
    val enumValues: List<String>? = null,
    /**
     * [T-port-nested-schema] Nested schema for a structured parameter (port of
     * iOS 5bbab0250). The flat scalar fields cannot express `user_ask.questions`
     * - an array of question objects, each with an array of option objects - so
     * such a parameter used to travel as a JSON string inside the JSON (as
     * browser_use `cookies` still does), which is fragile for a model to
     * produce. When set, [type] should be "array" (or "object") and every
     * provider renders the node in its own dialect. Scalars leave it null, and
     * their output stays byte-identical to before.
     */
    val schema: AgentSchemaNode? = null,
) {
    /** Standard JSON Schema: Anthropic input_schema, OpenAI Chat and Responses. */
    fun toJson(): JSONObject {
        schema?.let { return it.toJsonSchema(gemini = false).apply { put("description", description) } }
        return JSONObject().apply {
            put("type", type)
            put("description", description)
            if (enumValues != null) put("enum", JSONArray(enumValues))
        }
    }

    /** Gemini dialect (upper-case types). */
    fun toGeminiJson(): JSONObject {
        schema?.let { return it.toJsonSchema(gemini = true).apply { put("description", description) } }
        return JSONObject().apply {
            put("type", type.uppercase())
            put("description", description)
            if (enumValues != null) put("enum", JSONArray(enumValues))
        }
    }
}

/**
 * [T-port-nested-schema] A provider-neutral JSON Schema subset (iOS
 * `AgentSchemaNode`): string with enum, integer, boolean, array with item
 * bounds, object with properties + required.
 *
 * Bounds and nested `required` lists are advisory - not every provider honours
 * them - so a tool that uses a nested schema must re-validate its input in its
 * own parser. Unlike iOS (whose Anthropic SDK types cannot nest objects
 * outside array items) Android builds raw JSON, so every provider receives the
 * full tree, objects at any depth included.
 */
sealed class AgentSchemaNode {
    abstract val description: String?

    data class Str(override val description: String? = null, val enumValues: List<String>? = null) : AgentSchemaNode()
    data class Integer(override val description: String? = null) : AgentSchemaNode()
    data class Bool(override val description: String? = null) : AgentSchemaNode()
    data class Arr(
        override val description: String? = null,
        val items: AgentSchemaNode,
        val minItems: Int? = null,
        val maxItems: Int? = null,
    ) : AgentSchemaNode()
    data class Obj(
        override val description: String? = null,
        /** Ordered: rendered in this order, the order the model reads them in. */
        val properties: List<Pair<String, AgentSchemaNode>>,
        val required: List<String> = emptyList(),
    ) : AgentSchemaNode()

    /**
     * Standard JSON Schema, or with [gemini] the Gemini dialect used by its
     * scalar path on iOS: upper-case types, a string enum folded into the
     * description as "(values: a, b)", and no array bounds.
     */
    fun toJsonSchema(gemini: Boolean): JSONObject {
        fun t(s: String) = if (gemini) s.uppercase() else s
        val o = JSONObject()
        when (this) {
            is Str -> {
                o.put("type", t("string"))
                if (gemini && !enumValues.isNullOrEmpty()) {
                    o.put("description", "${description ?: ""} (values: ${enumValues.joinToString(", ")})")
                } else {
                    description?.let { o.put("description", it) }
                    enumValues?.let { o.put("enum", JSONArray(it)) }
                }
            }
            is Integer -> {
                o.put("type", t("integer"))
                description?.let { o.put("description", it) }
            }
            is Bool -> {
                o.put("type", t("boolean"))
                description?.let { o.put("description", it) }
            }
            is Arr -> {
                o.put("type", t("array"))
                description?.let { o.put("description", it) }
                o.put("items", items.toJsonSchema(gemini))
                if (!gemini) {
                    minItems?.let { o.put("minItems", it) }
                    maxItems?.let { o.put("maxItems", it) }
                }
            }
            is Obj -> {
                o.put("type", t("object"))
                description?.let { o.put("description", it) }
                val props = JSONObject()
                for ((name, node) in properties) props.put(name, node.toJsonSchema(gemini))
                o.put("properties", props)
                if (required.isNotEmpty()) o.put("required", JSONArray(required))
            }
        }
        return o
    }

    /**
     * [T-port-nested-json-string] [value] decoded where it is JSON-in-a-string
     * for an array or object node, recursively; the same instance when nothing
     * changed (scalars, already-native values, strings that do not parse).
     */
    fun coerce(value: Any?): Any? = when (this) {
        is Arr -> {
            val arr = when (value) {
                is JSONArray -> value
                is String -> parseJsonOrNull(value) as? JSONArray
                else -> null
            }
            if (arr == null) value else {
                var changed = arr !== value
                val mapped = (0 until arr.length()).map { i ->
                    val item = arr.opt(i)
                    items.coerce(item).also { if (it !== item) changed = true }
                }
                if (changed) JSONArray(mapped) else value
            }
        }
        is Obj -> {
            val obj = when (value) {
                is JSONObject -> value
                is String -> parseJsonOrNull(value) as? JSONObject
                else -> null
            }
            if (obj == null) value else {
                var out: JSONObject? = if (obj !== value) obj else null
                for ((name, node) in properties) {
                    if (!obj.has(name)) continue
                    val v = obj.opt(name)
                    val c = node.coerce(v)
                    if (c !== v) {
                        if (out == null) out = JSONObject(obj.toString())
                        out.put(name, c)
                    }
                }
                out ?: value
            }
        }
        else -> value
    }

    private fun parseJsonOrNull(s: String): Any? {
        val t = s.trim()
        return when {
            t.startsWith("[") -> runCatching { JSONArray(t) }.getOrNull()
            t.startsWith("{") -> runCatching { JSONObject(t) }.getOrNull()
            else -> null
        }
    }

    /** Every description, name and enum value, for the context-size estimate. */
    val estimationText: String
        get() = when (this) {
            is Str -> listOfNotNull(description, enumValues?.joinToString(" ")).joinToString(" ")
            is Integer -> description ?: ""
            is Bool -> description ?: ""
            is Arr -> (description ?: "") + " " + items.estimationText
            is Obj -> (description ?: "") + " " +
                properties.joinToString(" ") { (name, node) -> name + " " + node.estimationText }
        }
}
