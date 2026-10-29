package com.openminis.app.data

import android.content.Context
import android.content.SharedPreferences

/**
 * [T-speed-tier] The speed the user asked for. Wire values are what the
 * Responses API takes in `service_tier`; STANDARD omits the key entirely.
 *
 * FAST is `"priority"`, not `"fast"`: "Fast" is only the UI name (openai/codex
 * `ServiceTier::Fast` serializes to "priority"), and a literal "fast" is
 * rejected with HTTP 400 (CLIProxyAPI issue #5924).
 */
enum class SpeedTier(val storageValue: String, val wireValue: String?) {
    STANDARD("off", null),
    FAST("fast", "priority"),
    ULTRAFAST("ultrafast", "ultrafast"),
    ;

    companion object {
        fun fromStorage(value: String?): SpeedTier? = entries.firstOrNull { it.storageValue == value }
    }
}

/**
 * [T-codex-fast-mode] App-level persisted speed choice (was a Fast Mode
 * boolean mirroring iOS UserDefaults key `codexFastModeEnabled`, commit
 * fb671083). Durable and shared across sessions: switching to another chat
 * reads the same choice.
 *
 * The provider layer has no Context, and the choice is read at REQUEST-BUILD
 * time so a change applies to the very next request of an ongoing session —
 * including offload / title-gen calls that never pass through the
 * ChatViewModel. To make that possible, [prime] captures the application
 * context once at app startup (MinisApp.onCreate) and warms a volatile cache;
 * the context-free [tier] / [isEnabled] are then safe from any request builder.
 *
 * [T-speed-tier] Storage went from a boolean to three states:
 *  - the tier lives under [KEY_TIER] as "off" / "fast" / "ultrafast";
 *  - an install that only has the legacy boolean [KEY_LEGACY_ENABLED] is
 *    migrated in [prime] (true → FAST, false/absent → STANDARD) and the result
 *    written to [KEY_TIER], so the migration runs once;
 *  - every write keeps the legacy boolean in step (true for FAST and
 *    ULTRAFAST), so a downgrade to a build that only reads the boolean still
 *    sees Fast Mode as the user left it instead of silently turning it off.
 * An unreadable [KEY_TIER] value (a future build's new tier) falls back to the
 * legacy boolean for the same reason.
 */
object FastModePrefs {
    private const val PREFS = "minis_fast_mode_prefs"
    private const val KEY_LEGACY_ENABLED = "codexFastModeEnabled"
    private const val KEY_TIER = "codexSpeedTier"
    /** [T-android-ultrafast-align] iOS `catalogUltrafastKey`. */
    private const val KEY_CATALOG_ULTRAFAST = "codexCatalogUltrafastModels"

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var cachedTier: SpeedTier = SpeedTier.STANDARD

    @Volatile
    private var cachedCatalogUltrafast: List<String> = emptyList()

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * The migration rule, kept pure for unit tests. [storedTier] is the raw
     * [KEY_TIER] value (null when absent), [legacyEnabled] the legacy boolean
     * (null when absent).
     */
    internal fun resolveStoredTier(storedTier: String?, legacyEnabled: Boolean?): SpeedTier =
        SpeedTier.fromStorage(storedTier)
            ?: if (legacyEnabled == true) SpeedTier.FAST else SpeedTier.STANDARD

    /** Capture the app context, migrate the legacy boolean once, warm the cache. Called from MinisApp.onCreate. */
    fun prime(context: Context) {
        appContext = context.applicationContext
        val p = prefs(context)
        val stored = p.getString(KEY_TIER, null)
        val legacy = if (p.contains(KEY_LEGACY_ENABLED)) p.getBoolean(KEY_LEGACY_ENABLED, false) else null
        val tier = resolveStoredTier(stored, legacy)
        cachedTier = tier
        cachedCatalogUltrafast = p.getString(KEY_CATALOG_ULTRAFAST, null).orEmpty()
            .split(',').filter { it.isNotBlank() }
        if (stored == null) {
            p.edit().putString(KEY_TIER, tier.storageValue).apply()
        }
    }

    /**
     * Context-free read for request builders: the tier the USER chose. The
     * tier actually sent also depends on the model (see [SpeedTiers.effective]).
     * STANDARD before [prime] has ever run — matches a fresh install, and prime
     * runs before any request can be built.
     */
    fun tier(): SpeedTier = cachedTier

    /** True for FAST and ULTRAFAST: the user asked for a faster tier than standard. */
    fun isEnabled(): Boolean = cachedTier != SpeedTier.STANDARD

    fun setTier(context: Context, tier: SpeedTier) {
        cachedTier = tier
        prefs(context).edit()
            .putString(KEY_TIER, tier.storageValue)
            .putBoolean(KEY_LEGACY_ENABLED, tier != SpeedTier.STANDARD)
            .apply()
    }

    /**
     * [T-android-ultrafast-align] Lowercased ids whose Codex catalogue entry
     * declares an "ultrafast" tier, as of the last discovery (iOS
     * `catalogUltrafastKey`). Persisted so a request made before this launch's
     * discovery still knows it; empty until a discovery has run.
     */
    fun catalogUltrafastIds(): List<String> = cachedCatalogUltrafast

    /** Written by every Codex discovery (network or cache hit): the whole list, replacing the last one. */
    fun recordCatalogUltrafast(context: Context?, ids: List<String>) {
        val lowered = ids.map { it.lowercase() }.distinct()
        cachedCatalogUltrafast = lowered
        val ctx = context ?: appContext ?: return
        prefs(ctx).edit().putString(KEY_CATALOG_ULTRAFAST, lowered.joinToString(",")).apply()
    }

    /**
     * [T-android-xai-priority] Set ONLY the in-memory cache, with no Context and
     * no persistence. Exists so JVM unit tests can exercise the real read path
     * that request builders consult — there is no Robolectric in this module,
     * so the Context-taking setter is unusable there. Not for production use: a
     * write here is lost on restart.
     */
    @androidx.annotation.VisibleForTesting
    internal fun setCachedEnabledForTest(enabled: Boolean) {
        cachedTier = if (enabled) SpeedTier.FAST else SpeedTier.STANDARD
    }

    @androidx.annotation.VisibleForTesting
    internal fun setCachedTierForTest(tier: SpeedTier) {
        cachedTier = tier
    }
}

/**
 * [T-speed-tier] Which tiers a model may use, and the tier a request actually
 * sends.
 */
object SpeedTiers {
    /**
     * FALLBACK Ultrafast whitelist, matched as a lowercased id prefix (iOS
     * `ultrafastModelPrefixes`).
     *
     * [T-android-ultrafast-align] GPT-6 Astra only. OpenAI's Codex speed docs
     * list Ultrafast as "GPT-6 Astra only" (Pro and eligible Enterprise/Edu
     * plans); gpt-6.1-sol was on this list from DevDay coverage alone and is not
     * offered. An account without the entitlement still gets a 200 and is served
     * at Standard; the echoed service_tier cannot tell the two apart.
     *
     * Callers must also be on the Responses path (Codex OAuth or a
     * useResponsesAPI instance); xAI never gets Ultrafast (Priority only).
     */
    internal val ULTRAFAST_MODEL_PREFIXES = listOf("gpt-6-astra")

    /** The whitelist check alone (by id). Prefer the [LLMModel] overload. */
    fun supportsUltrafast(modelId: String): Boolean {
        val id = modelId.lowercase()
        return ULTRAFAST_MODEL_PREFIXES.any { id.startsWith(it) }
    }

    /**
     * Whether the Codex catalogue declares "ultrafast" for [model]: its own
     * catalogue entry ([LLMModel.serviceTiers]), or the account-wide list the
     * last discovery recorded ([catalogIds], iOS `catalogUltrafastKey`), which
     * also covers the same id reached through another instance.
     */
    fun catalogDeclaresUltrafast(
        model: com.openminis.app.data.model.LLMModel,
        catalogIds: Collection<String> = FastModePrefs.catalogUltrafastIds(),
    ): Boolean =
        model.serviceTiers?.any { it == SpeedTier.ULTRAFAST.wireValue } == true ||
            model.id.lowercase() in catalogIds

    /**
     * Whether [model] may use Ultrafast: the catalogue declares it, OR the id is
     * whitelisted (iOS `supportsUltrafast`).
     *
     * [T-android-ultrafast-align] User decision, replacing the catalogue-first
     * rule of T-ultrafast-catalog-tiers (where a catalogue that described the
     * model without "ultrafast" was final): the whitelist applies even then.
     * The account's catalogue today lists only "priority" for every model, and
     * Astra is still to offer Ultrafast.
     */
    fun supportsUltrafast(
        model: com.openminis.app.data.model.LLMModel,
        catalogIds: Collection<String> = FastModePrefs.catalogUltrafastIds(),
    ): Boolean = catalogDeclaresUltrafast(model, catalogIds) || supportsUltrafast(model.id)

    /**
     * The tier to send (and to show) for [chosen] on [model]. Ultrafast on a
     * model that may not use it (see [supportsUltrafast]), or on a path that
     * cannot carry it ([ultrafastPathAllowed] false), falls back to FAST. The
     * user's stored choice is left alone, so moving back to an allowed model
     * restores Ultrafast.
     */
    fun effective(
        chosen: SpeedTier,
        model: com.openminis.app.data.model.LLMModel,
        ultrafastPathAllowed: Boolean = true,
        catalogIds: Collection<String> = FastModePrefs.catalogUltrafastIds(),
    ): SpeedTier =
        if (chosen == SpeedTier.ULTRAFAST && !(ultrafastPathAllowed && supportsUltrafast(model, catalogIds))) {
            SpeedTier.FAST
        } else {
            chosen
        }

    /**
     * [T-android-ultrafast-align] Port of iOS
     * `SpeedTierSettings.ultrafastUnsupportedNote`: a log line for a request
     * where Ultrafast was chosen but is not what the model / account supports,
     * or null when there is nothing to report (another tier chosen, Ultrafast
     * declared by the catalogue, or a non-gpt id - xAI has its own "priority"
     * path, so a line there would be wrong). Two cases:
     *  - the model is not offered Ultrafast: the request goes out as Fast
     *    ("priority");
     *  - the model is offered only by the whitelist and the catalogue does not
     *    declare it: "ultrafast" is sent, but an account without the
     *    entitlement gets a 200 served at Standard, and the echo cannot tell.
     * The caller logs it under "[SpeedTier]", once per request.
     */
    fun ultrafastUnsupportedNote(
        chosen: SpeedTier,
        model: com.openminis.app.data.model.LLMModel,
        catalogIds: Collection<String> = FastModePrefs.catalogUltrafastIds(),
    ): String? {
        if (chosen != SpeedTier.ULTRAFAST || !model.id.contains("gpt", ignoreCase = true)) return null
        val declared = catalogIds.toList()
        val sent = effective(chosen, model, catalogIds = catalogIds).wireValue ?: "none"
        if (!supportsUltrafast(model, catalogIds)) {
            return "Ultrafast not supported for model=${model.id} (whitelist=$ULTRAFAST_MODEL_PREFIXES, " +
                "catalogue declares=$declared); sending service_tier=$sent"
        }
        if (!catalogDeclaresUltrafast(model, catalogIds)) {
            return "Ultrafast for model=${model.id} comes from the fallback whitelist only; the account catalogue " +
                "declares it for $declared - an account without the entitlement is likely served at Standard " +
                "(service_tier=$sent)"
        }
        return null
    }

    /**
     * The tier the chat UI shows for [chosen] on the active model:
     *  - [fastEligible] false → STANDARD (no speed control, no badge);
     *  - Ultrafast chosen but [ultrafastEligible] false → FAST, the same
     *    fallback [effective] applies to the request.
     */
    fun displayed(chosen: SpeedTier, fastEligible: Boolean, ultrafastEligible: Boolean): SpeedTier = when {
        !fastEligible -> SpeedTier.STANDARD
        chosen == SpeedTier.ULTRAFAST && !ultrafastEligible -> SpeedTier.FAST
        else -> chosen
    }
}
