package com.openminis.app.data

import android.content.Context
import android.content.SharedPreferences
import com.openminis.app.BuildConfig
import com.openminis.app.data.UpdatePolicy.Channel
import com.openminis.app.logging.AppLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * [T-update-channels] What the updater remembers between launches: the user's
 * channel and auto-check switch, the throttle times, the last result (the
 * "available" update, kept per channel and per installed build), the ETag of
 * the release list, and which version the user skipped or downloaded.
 *
 * It also owns the one shared badge state, [hasUpdateBadge], computed locally
 * from those fields ([UpdatePolicy.hasUpdateBadge]) - no request is ever made
 * to show or hide a badge. The beta-only home badge is derived from it and
 * [channel] by the UI ([UpdatePolicy.hasHomeBadge]), not stored.
 */
object UpdateStateStore {

    private const val TAG = "UpdateState"
    private const val PREFS = "update_state"

    private const val K_CHANNEL = "channel"
    private const val K_AUTO = "autoCheckEnabled"
    private const val K_SUCCESS = "lastCheckSuccessAt"
    private const val K_ATTEMPT = "lastAttemptAt"
    private const val K_CACHE_CHANNEL = "cacheChannel"
    private const val K_CACHE_CODE = "cacheVersionCode"
    private const val K_ETAG = "etag"
    private const val K_AV_NAME = "availableVersionName"
    private const val K_AV_CODE = "availableVersionCode"
    private const val K_AV_TAG = "availableTag"
    private const val K_AV_URL = "apkUrl"
    private const val K_AV_SIZE = "apkSize"
    private const val K_AV_SHA = "apkSha256"
    private const val K_AV_LOG = "changelog"
    private const val K_AV_TITLE = "releaseName"
    private const val K_AV_PAGE = "releaseUrl"
    private const val K_SKIPPED = "skippedVersionCode"
    private const val K_DOWNLOADED = "downloadedVersionCode"

    /** An update the last successful check found. */
    data class Available(
        val tag: String,
        val versionName: String,
        val versionCode: Int?,
        val apkUrl: String,
        val apkSize: Long,
        val sha256: String?,
        val changelog: String,
        val releaseName: String,
        val releaseUrl: String,
    )

    @Volatile private var prefs: SharedPreferences? = null

    private val _channel = MutableStateFlow(Channel.parse(BuildConfig.UPDATE_CHANNEL))
    val channel: StateFlow<Channel> = _channel.asStateFlow()

    private val _autoEnabled = MutableStateFlow(true)
    val autoEnabled: StateFlow<Boolean> = _autoEnabled.asStateFlow()

    private val _hasUpdateBadge = MutableStateFlow(false)

    /** The update badge everywhere it shows. Always false on a build without automatic checks. */
    val hasUpdateBadge: StateFlow<Boolean> = _hasUpdateBadge.asStateFlow()

    /** Idempotent; MinisApp.onCreate calls it, every other entry point too. */
    fun init(context: Context) {
        if (prefs != null) return
        synchronized(this) {
            if (prefs != null) return
            val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            prefs = p
            _channel.value = Channel.parse(p.getString(K_CHANNEL, BuildConfig.UPDATE_CHANNEL))
            _autoEnabled.value = p.getBoolean(K_AUTO, true)
            refreshBadge()
        }
    }

    private fun p(context: Context): SharedPreferences {
        init(context)
        return prefs!!
    }

    // ── Settings ────────────────────────────────────────────────────────────

    /**
     * Switch channel. The cached result belonged to the old channel: drop it,
     * with the ETag and both throttle times, so the next check (immediate,
     * see [UpdateAutoChecker.onChannelChanged]) is not held back by either.
     */
    fun setChannel(context: Context, ch: Channel) {
        if (_channel.value == ch) return
        p(context).edit().putString(K_CHANNEL, ch.id).apply()
        _channel.value = ch
        clearCache(context, "channel -> ${ch.id}", clearAttempt = true)
        AppLogger.info(TAG, "[UpdateCheck] channel=${ch.id}")
    }

    /** Off hides the badge and stops requests; the cache stays. */
    fun setAutoEnabled(context: Context, on: Boolean) {
        p(context).edit().putBoolean(K_AUTO, on).apply()
        _autoEnabled.value = on
        refreshBadge()
        AppLogger.info(TAG, "[UpdateCheck] autoCheck=$on")
    }

    // ── Throttle ────────────────────────────────────────────────────────────

    fun throttleState(context: Context): UpdatePolicy.ThrottleState {
        val p = p(context)
        return UpdatePolicy.ThrottleState(
            lastSuccessAt = p.getLong(K_SUCCESS, -1L).takeIf { it >= 0 },
            lastAttemptAt = p.getLong(K_ATTEMPT, -1L).takeIf { it >= 0 },
            cacheChannel = p.getString(K_CACHE_CHANNEL, null)?.let { Channel.parse(it) },
            cacheVersionCode = p.getInt(K_CACHE_CODE, -1).takeIf { it >= 0 },
        )
    }

    /**
     * A check is starting. Stamps the attempt and binds the cache to the
     * current channel and installed build - so an invalidation happens once,
     * and a failure right after it backs off instead of invalidating again on
     * every foreground.
     */
    fun beginAttempt(context: Context, now: Long, ch: Channel, localCode: Int) {
        val s = throttleState(context)
        val bound = s.cacheChannel != null || s.cacheVersionCode != null
        if (bound && (s.cacheChannel != ch || s.cacheVersionCode != localCode)) {
            clearCache(context, "cache was for ${s.cacheChannel?.id}/${s.cacheVersionCode}", clearAttempt = false)
        }
        p(context).edit()
            .putLong(K_ATTEMPT, now)
            .putString(K_CACHE_CHANNEL, ch.id)
            .putInt(K_CACHE_CODE, localCode)
            .apply()
    }

    /** ETag of page 1 of the release list, valid for the bound channel/build. */
    fun etag(context: Context): String? = p(context).getString(K_ETAG, null)

    /** A check succeeded (200 or 304). [available] null = up to date; ignored for a 304. */
    fun markSuccess(context: Context, now: Long, etag: String?, available: Available?, notModified: Boolean) {
        val e = p(context).edit().putLong(K_SUCCESS, now)
        if (!notModified) {
            if (etag != null) e.putString(K_ETAG, etag) else e.remove(K_ETAG)
            writeAvailable(e, available)
        }
        e.apply()
        refreshBadge()
    }

    private fun writeAvailable(e: SharedPreferences.Editor, a: Available?) {
        if (a == null) {
            listOf(K_AV_NAME, K_AV_CODE, K_AV_TAG, K_AV_URL, K_AV_SIZE, K_AV_SHA, K_AV_LOG, K_AV_TITLE, K_AV_PAGE).forEach { e.remove(it) }
            return
        }
        e.putString(K_AV_NAME, a.versionName)
        if (a.versionCode != null) e.putInt(K_AV_CODE, a.versionCode) else e.remove(K_AV_CODE)
        e.putString(K_AV_TAG, a.tag)
        e.putString(K_AV_URL, a.apkUrl)
        e.putLong(K_AV_SIZE, a.apkSize)
        if (a.sha256 != null) e.putString(K_AV_SHA, a.sha256) else e.remove(K_AV_SHA)
        e.putString(K_AV_LOG, a.changelog)
        e.putString(K_AV_TITLE, a.releaseName)
        e.putString(K_AV_PAGE, a.releaseUrl)
    }

    fun available(context: Context): Available? {
        val p = p(context)
        val url = p.getString(K_AV_URL, null) ?: return null
        return Available(
            tag = p.getString(K_AV_TAG, "") ?: "",
            versionName = p.getString(K_AV_NAME, "") ?: "",
            versionCode = p.getInt(K_AV_CODE, -1).takeIf { it >= 0 },
            apkUrl = url,
            apkSize = p.getLong(K_AV_SIZE, 0L),
            sha256 = p.getString(K_AV_SHA, null),
            changelog = p.getString(K_AV_LOG, "") ?: "",
            releaseName = p.getString(K_AV_TITLE, "") ?: "",
            releaseUrl = p.getString(K_AV_PAGE, "") ?: "",
        )
    }

    private fun clearCache(context: Context, why: String, clearAttempt: Boolean) {
        val e = p(context).edit().remove(K_SUCCESS).remove(K_ETAG).remove(K_CACHE_CHANNEL).remove(K_CACHE_CODE)
        if (clearAttempt) e.remove(K_ATTEMPT)
        writeAvailable(e, null)
        e.apply()
        refreshBadge()
        AppLogger.info(TAG, "[UpdateCheck] cache cleared ($why)")
    }

    // ── Badge ───────────────────────────────────────────────────────────────

    /** "Skip this version": the badge stays off for this build number. */
    fun skip(context: Context, versionCode: Int?) {
        if (versionCode == null) return
        p(context).edit().putInt(K_SKIPPED, versionCode).apply()
        refreshBadge()
        AppLogger.info(TAG, "[UpdateCheck] skipped build $versionCode")
    }

    /** A download started: the badge has done its job for this build. */
    fun markDownloaded(context: Context, versionCode: Int?) {
        if (versionCode == null) return
        p(context).edit().putInt(K_DOWNLOADED, versionCode).apply()
        refreshBadge()
    }

    private fun refreshBadge() {
        val p = prefs ?: return
        _hasUpdateBadge.value = UpdatePolicy.hasUpdateBadge(
            buildAllowsAuto = BuildConfig.AUTO_UPDATE_CHECK,
            userEnabled = _autoEnabled.value,
            availableCode = p.getInt(K_AV_CODE, -1).takeIf { it >= 0 },
            localCode = BuildConfig.VERSION_CODE,
            skippedCode = p.getInt(K_SKIPPED, -1).takeIf { it >= 0 },
            downloadedCode = p.getInt(K_DOWNLOADED, -1).takeIf { it >= 0 },
        )
    }
}
