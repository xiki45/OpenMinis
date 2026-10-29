package com.openminis.app.backup

import com.openminis.app.data.model.ProviderConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * The `data/provider_config.json` a backup carries: how this device's
 * [ProviderConfig] is written into a package.
 *
 * [B01 cross-platform read] [BackupFormat.json] leaves out every field equal
 * to its default, and iOS decodes several of those as REQUIRED. A real Pixel
 * package failed on vphone with `keyNotFound 'isEnabled'`: the whole file
 * unreadable, no provider restored, and behind it the same for
 * `modelEntries[].isHidden` / `isCustom` and the top-level `sessionBindings`;
 * groups without `strategy` were then dropped one by one. So the backup
 * writes those fields even at their default, plus `sessionBindings` (Android
 * keeps no session bindings in its config; iOS requires the key).
 *
 * Only these fields are added, not every default: a blanket `encodeDefaults`
 * would also put Android-only fields and values in front of iOS for the first
 * time, each one a new chance of a type mismatch there.
 *
 * `fallbackStrategy` is written as iOS spells it: iOS's `FallbackStrategy` has
 * `limited` and `always`, Android's `default` is iOS's `limited`, and a value
 * iOS does not know fails its decode just like a missing one. Android reads
 * `limited` back as `default`.
 *
 * Backups only: the device's own config (ProviderRepository) is persisted
 * elsewhere, with its own encoder, and does not change.
 */
object BackupProviderWire {

    /** Fields iOS requires per array element, written even at their default. */
    private val REQUIRED_FIELDS = mapOf(
        "instances" to listOf("isEnabled", "appendV1Suffix"),
        "modelEntries" to listOf("isHidden", "isCustom"),
        "modelGroups" to listOf("strategy", "fallbackStrategy"),
    )

    /** Android's name for the default fallback strategy, and iOS's. */
    private const val ANDROID_DEFAULT_FALLBACK = "default"
    private const val IOS_DEFAULT_FALLBACK = "limited"

    private val withDefaults = Json(BackupFormat.json) { encodeDefaults = true }

    fun encode(config: ProviderConfig): String {
        val slim = BackupFormat.json.encodeToJsonElement(ProviderConfig.serializer(), config).jsonObject
        // The same config with its defaults, so a missing field's value is read
        // from the model rather than restated here.
        val full = withDefaults.encodeToJsonElement(ProviderConfig.serializer(), config).jsonObject

        val out = LinkedHashMap<String, JsonElement>(slim)
        for ((key, fields) in REQUIRED_FIELDS) {
            val fullItems = full[key] as? JsonArray ?: JsonArray(emptyList())
            val slimItems = slim[key] as? JsonArray ?: JsonArray(emptyList())
            out[key] = JsonArray(
                slimItems.mapIndexed { index, item ->
                    val obj = item as? JsonObject ?: return@mapIndexed item
                    val withFields = withFields(obj, fullItems.getOrNull(index) as? JsonObject, fields)
                    if (key == "modelGroups") iosFallback(withFields) else withFields
                },
            )
        }
        if ("sessionBindings" !in out) out["sessionBindings"] = JsonObject(emptyMap())
        return BackupFormat.json.encodeToString(JsonObject.serializer(), JsonObject(out))
    }

    private fun withFields(obj: JsonObject, full: JsonObject?, fields: List<String>): JsonObject {
        if (full == null || fields.all { it in obj }) return obj
        val merged = LinkedHashMap<String, JsonElement>(obj)
        for (field in fields) {
            if (field !in merged) full[field]?.let { merged[field] = it }
        }
        return JsonObject(merged)
    }

    private fun iosFallback(group: JsonObject): JsonObject {
        val value = (group["fallbackStrategy"] as? JsonPrimitive)?.content
        if (value != ANDROID_DEFAULT_FALLBACK) return group
        return JsonObject(group + ("fallbackStrategy" to JsonPrimitive(IOS_DEFAULT_FALLBACK)))
    }
}
