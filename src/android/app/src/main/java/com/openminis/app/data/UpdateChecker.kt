package com.openminis.app.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.openminis.app.BuildConfig
import com.openminis.app.logging.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.io.File
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/**
 * Checks GitHub releases for a build newer than the running one and
 * coordinates download → install. iOS has no equivalent (sideloading is not
 * permitted) so this is Android-only.
 *
 * [T-update-channels] One repo, [BuildConfig.RELEASE_REPO]; two channels:
 *  - stable: non-prerelease releases only (tags `X.Y`);
 *  - beta: everything, including the prereleases tagged `0-beta-build<N>`.
 * versionCode (the shared build counter, from the APK name's `-build<N>-` -
 * the file name is the protocol, there is no latest.json) decides what is
 * newer; see [UpdatePolicy] for every rule and
 * [UpdateStateStore] for the cache, throttle and badge this writes.
 */
object UpdateChecker {

    private const val TAG = "UpdateChecker"
    private const val DOWNLOAD_FILENAME = "minis-update.apk"
    /**
     * Sub-directory of `filesDir` where we stage downloaded update APKs. We
     * moved off `cacheDir/shared/` (the original location) so the OS can't
     * evict a freshly-downloaded APK between the moment we hand the user off
     * to "install unknown apps" settings and the moment they return — the
     * eviction was a contributing factor to the "re-download after grant"
     * bug. See [PendingUpdateStore]. Exposed via `file_provider_paths.xml`
     * `<files-path name="updates" path="updates/" />`.
     */
    private const val UPDATES_DIR = "updates"

    /** Releases per page; pages are fetched until one comes back short. */
    private const val PER_PAGE = 100
    private const val MAX_PAGES = 5

    sealed class CheckResult {
        data class UpdateAvailable(
            val tagName: String,
            val versionName: String,
            val versionCode: Int?,
            val releaseName: String,
            val changelog: String,
            val apkUrl: String,
            val apkSizeBytes: Long,
            val sha256: String?,
            val releaseUrl: String,
        ) : CheckResult()
        data object UpToDate : CheckResult()
        // The repo has zero usable releases (or 404'd entirely).
        data object NoReleaseAvailable : CheckResult()
        // A newer release exists but carries no APK for this device. Distinct
        // from NoReleaseAvailable so the UI can say "newer release exists,
        // but it didn't ship an APK" instead of misleading "no release yet".
        data class NoApkAsset(val tagName: String) : CheckResult()
        data class Error(val message: String) : CheckResult()
        // GitHub returned 403 / 451 — usually a geo-block or rate-limit in CN
        // without a VPN. UI surfaces a hint with a clickable Releases link.
        data object Forbidden : CheckResult()
        // DNS / connect / read timeout — network unreachable. UI nudges the
        // user to check connectivity and retry.
        data object NetworkUnreachable : CheckResult()
    }

    sealed class DownloadResult {
        data class Success(val file: File) : DownloadResult()
        data class Error(val message: String) : DownloadResult()
    }

    private val baseClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    // [FEAT-261021-16] Follows the default route in Settings > Network; the
    // same client object when nothing is configured.
    private val client: OkHttpClient get() = com.openminis.app.network.NetworkHttp.client(com.openminis.app.network.NetworkHttp.DEFAULT_TAG, baseClient)

    /** One check at a time; an automatic check never starts while any is running. */
    private val inFlight = java.util.concurrent.atomic.AtomicBoolean(false)

    val isChecking: Boolean get() = inFlight.get()

    private fun UpdateStateStore.Available.toResult() = CheckResult.UpdateAvailable(
        tagName = tag, versionName = versionName, versionCode = versionCode, releaseName = releaseName,
        changelog = changelog, apkUrl = apkUrl, apkSizeBytes = apkSize, sha256 = sha256, releaseUrl = releaseUrl,
    )

    private class HttpFailure(val result: CheckResult) : Exception()

    /**
     * Check the current channel now, whatever the throttle says (a manual
     * check, or an automatic one [UpdateAutoChecker] decided to run), and
     * write the outcome into [UpdateStateStore]: a 200 or a 304 is a success,
     * anything else only stamps the attempt.
     *
     * All network work happens on [Dispatchers.IO]; safe to call from any
     * coroutine scope.
     */
    suspend fun check(context: Context, reason: String = "manual"): CheckResult = withContext(Dispatchers.IO) {
        UpdateStateStore.init(context)
        if (!inFlight.compareAndSet(false, true)) {
            AppLogger.info(TAG, "[UpdateCheck] $reason: a check is already running")
            // Wait for it rather than firing a second request.
            while (inFlight.get()) kotlinx.coroutines.delay(100)
            return@withContext UpdateStateStore.available(context)?.toResult() ?: CheckResult.UpToDate
        }
        try {
            runCheck(context, reason)
        } finally {
            inFlight.set(false)
        }
    }

    private fun runCheck(context: Context, reason: String): CheckResult {
        val channel = UpdateStateStore.channel.value
        val localCode = BuildConfig.VERSION_CODE
        val localName = BuildConfig.VERSION_NAME
        val now = System.currentTimeMillis()
        UpdateStateStore.beginAttempt(context, now, channel, localCode)
        val etag = UpdateStateStore.etag(context)
        AppLogger.info(
            TAG,
            "[UpdateCheck] request reason=$reason channel=${channel.id} local=$localName ($localCode) repo=${BuildConfig.RELEASE_REPO} etag=${etag != null}",
        )
        return try {
            val page1 = fetchPage(1, etag)
            if (page1 == null) {
                // 304: nothing changed since the cached result.
                AppLogger.info(TAG, "[UpdateCheck] 304 not modified")
                UpdateStateStore.markSuccess(context, now, etag, null, notModified = true)
                return UpdateStateStore.available(context)?.toResult() ?: CheckResult.UpToDate
            }
            val releases = page1.second.toMutableList()
            var page = 1
            var lastSize = page1.second.size
            while (lastSize >= PER_PAGE && page < MAX_PAGES) {
                page++
                val more = fetchPage(page, null)?.second ?: break
                releases += more
                lastSize = more.size
            }
            decide(context, now, page1.first, releases, channel, localName, localCode)
        } catch (f: HttpFailure) {
            f.result
        } catch (e: UnknownHostException) {
            AppLogger.error(TAG, "check failed: UnknownHostException: ${e.message}")
            CheckResult.NetworkUnreachable
        } catch (e: ConnectException) {
            AppLogger.error(TAG, "check failed: ConnectException: ${e.message}")
            CheckResult.NetworkUnreachable
        } catch (e: SocketTimeoutException) {
            AppLogger.error(TAG, "check failed: SocketTimeoutException: ${e.message}")
            CheckResult.NetworkUnreachable
        } catch (e: IOException) {
            // Catch-all for okhttp connection plumbing (e.g.
            // "failed to connect", SSL handshake errors). Most of these in
            // the CN-no-VPN scenario are effectively "can't reach github".
            AppLogger.error(TAG, "check failed: ${e.javaClass.simpleName}: ${e.message}")
            CheckResult.NetworkUnreachable
        } catch (e: Exception) {
            AppLogger.error(TAG, "check failed: ${e.javaClass.simpleName}: ${e.message}")
            CheckResult.Error(e.message ?: e.javaClass.simpleName)
        }
    }

    /**
     * One page of the release list, newest first: (ETag, releases), or null
     * for a 304. A 404 is an empty list.
     */
    private fun fetchPage(page: Int, etag: String?): Pair<String?, List<UpdatePolicy.Release>>? {
        val url = "https://api.github.com/repos/${BuildConfig.RELEASE_REPO}/releases?per_page=$PER_PAGE&page=$page"
        val b = Request.Builder()
            .url(url)
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
        if (etag != null) b.header("If-None-Match", etag)
        client.newCall(b.build()).execute().use { resp ->
            AppLogger.info(TAG, "GET releases page=$page HTTP ${resp.code}")
            if (resp.code == 304) return null
            if (resp.code == 404) return null to emptyList()
            // 403 = rate-limit or geo-blocked. 451 = legal block. Both map to
            // the same "open Releases in browser" hint.
            if (resp.code == 403 || resp.code == 451) {
                AppLogger.warning(TAG, "GitHub API ${resp.code} — geo-block or rate-limit")
                throw HttpFailure(CheckResult.Forbidden)
            }
            if (!resp.isSuccessful) {
                AppLogger.warning(TAG, "GitHub API ${resp.code}")
                throw HttpFailure(CheckResult.Error("GitHub API ${resp.code}"))
            }
            val body = resp.body?.string() ?: throw HttpFailure(CheckResult.Error("empty body"))
            val arr = runCatching { JSONArray(body) }.getOrNull() ?: throw HttpFailure(CheckResult.Error("bad JSON"))
            return resp.header("ETag") to parseReleases(arr)
        }
    }

    internal fun parseReleases(arr: JSONArray): List<UpdatePolicy.Release> {
        val out = mutableListOf<UpdatePolicy.Release>()
        for (i in 0 until arr.length()) {
            val r = arr.optJSONObject(i) ?: continue
            val tag = r.optString("tag_name")
            if (tag.isEmpty()) continue
            val assets = mutableListOf<UpdatePolicy.Asset>()
            val ja = r.optJSONArray("assets")
            for (j in 0 until (ja?.length() ?: 0)) {
                val a = ja!!.optJSONObject(j) ?: continue
                val url = a.optString("browser_download_url").ifEmpty { null } ?: continue
                // GitHub reports "digest": "sha256:<hex>" for every asset; absent -> no check.
                val sha = UpdatePolicy.parseDigest(a.optString("digest", "").ifEmpty { null })
                assets += UpdatePolicy.Asset(a.optString("name"), url, a.optLong("size", 0), sha)
            }
            out += UpdatePolicy.Release(
                tag = tag,
                name = r.optString("name").ifEmpty { tag },
                prerelease = r.optBoolean("prerelease", false),
                draft = r.optBoolean("draft", false),
                body = r.optString("body", ""),
                assets = assets,
                htmlUrl = r.optString("html_url", ""),
            )
        }
        return out
    }

    private fun decide(
        context: Context,
        now: Long,
        etag: String?,
        releases: List<UpdatePolicy.Release>,
        channel: UpdatePolicy.Channel,
        localName: String,
        localCode: Int,
    ): CheckResult {
        val abis = Build.SUPPORTED_ABIS.toList()
        val eligible = releases.filter { UpdatePolicy.eligible(it, channel) }
        AppLogger.info(TAG, "[UpdateCheck] releases=${releases.size} eligible=${eligible.size} channel=${channel.id}")
        if (eligible.isEmpty()) {
            UpdateStateStore.markSuccess(context, now, etag, null, notModified = false)
            return CheckResult.NoReleaseAvailable
        }
        val candidates = eligible.mapNotNull { UpdatePolicy.toCandidate(it, abis) }
        val chosen = UpdatePolicy.pickUpdate(candidates, channel, localName, localCode)
        if (chosen != null) {
            val available = UpdateStateStore.Available(
                tag = chosen.tag, versionName = chosen.versionName, versionCode = chosen.versionCode,
                apkUrl = chosen.apk.url, apkSize = chosen.apk.size, sha256 = chosen.apk.sha256,
                changelog = chosen.changelog, releaseName = chosen.releaseName, releaseUrl = chosen.htmlUrl,
            )
            UpdateStateStore.markSuccess(context, now, etag, available, notModified = false)
            AppLogger.info(
                TAG,
                "[UpdateCheck] update available: ${UpdatePolicy.displayVersion(localName, localCode)} -> " +
                    "${UpdatePolicy.displayVersion(chosen.versionName, chosen.versionCode)} tag=${chosen.tag} apk=${chosen.apk.name}",
            )
            return available.toResult()
        }
        UpdateStateStore.markSuccess(context, now, etag, null, notModified = false)
        // A newer stable by name that ships no APK for this device.
        val noApk = eligible.firstOrNull { r ->
            !r.prerelease && UpdatePolicy.compareVersionNames(r.tag, localName) > 0 &&
                UpdatePolicy.chooseApk(r.assets, abis) == null
        }
        if (noApk != null && channel == UpdatePolicy.Channel.STABLE) {
            AppLogger.info(TAG, "[UpdateCheck] ${noApk.tag} is newer but has no APK for $abis")
            return CheckResult.NoApkAsset(noApk.tag)
        }
        AppLogger.info(TAG, "[UpdateCheck] up to date: ${UpdatePolicy.displayVersion(localName, localCode)} channel=${channel.id}")
        return CheckResult.UpToDate
    }

    /** Public so UI can deep-link users to manual download when GitHub is blocked. */
    const val RELEASES_URL: String = "https://github.com/OpenMinis/OpenMinis/releases"

    /**
     * Stream the APK from [url] into `${cacheDir}/shared/minis-update.apk`,
     * surfacing progress (0..1) through [onProgress] roughly every 64 KiB.
     * Returns the on-disk [File] on success so the caller can hand it to
     * [installApk]. The path is intentionally inside `shared/` because that's
     * the only sub-directory of cacheDir already exposed by FileProvider in
     * `file_provider_paths.xml`.
     */
    suspend fun download(
        context: Context,
        url: String,
        versionName: String? = null,
        versionCode: Int? = null,
        // [T-update-channels] GitHub's asset digest; checked when present, skipped when not.
        expectedSha256: String? = null,
        onProgress: (Float) -> Unit = {},
    ): DownloadResult = withContext(Dispatchers.IO) {
        try {
            // Stage under filesDir (NOT cacheDir) so the OS doesn't evict
            // the APK mid-flow while the user is in system Settings granting
            // install permission — that eviction caused the "re-download
            // after grant" regression (T-android-update-resume-33637).
            val outDir = File(context.filesDir, UPDATES_DIR).apply { mkdirs() }
            // Filename keyed by version so a partial old-version download
            // can't accidentally satisfy a check for a newer version.
            val safeName = versionName
                ?.replace(Regex("[^A-Za-z0-9._-]"), "_")
                ?.takeIf { it.isNotEmpty() }
                ?.let { "minis-$it.apk" }
                ?: DOWNLOAD_FILENAME
            val outFile = File(outDir, safeName)
            // A previous, possibly-aborted download could leave a stale APK
            // behind that the installer would happily try to consume. Wipe it.
            if (outFile.exists()) outFile.delete()

            val req = Request.Builder().url(url).build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return@withContext DownloadResult.Error("HTTP ${resp.code}")
                }
                val body = resp.body ?: return@withContext DownloadResult.Error("empty body")
                val total = body.contentLength().takeIf { it > 0 } ?: -1L
                body.byteStream().use { input ->
                    outFile.outputStream().use { output ->
                        val buf = ByteArray(64 * 1024)
                        var read: Int
                        var totalRead = 0L
                        var lastReported = -1
                        while (input.read(buf).also { read = it } != -1) {
                            output.write(buf, 0, read)
                            totalRead += read
                            if (total > 0) {
                                val pct = ((totalRead * 100) / total).toInt()
                                if (pct != lastReported) {
                                    lastReported = pct
                                    onProgress(pct / 100f)
                                }
                            }
                        }
                    }
                }
            }
            AppLogger.info(TAG, "Downloaded ${outFile.length()} bytes to ${outFile.absolutePath}")
            // Persist so a subsequent Activity recreate (e.g. after the user
            // returns from "install unknown apps" settings) can resume the
            // install without re-downloading. sha256 computed best-effort;
            // verify() falls back to size-only when null.
            val sha = runCatching { PendingUpdateStore.sha256(outFile) }
                .onFailure { AppLogger.warning(TAG, "sha256 compute failed: ${it.message}") }
                .getOrNull()
            if (expectedSha256 != null && !expectedSha256.equals(sha, ignoreCase = true)) {
                AppLogger.error(TAG, "[UpdateCheck] sha256 mismatch - discarding the download")
                outFile.delete()
                return@withContext DownloadResult.Error(context.getString(com.openminis.app.R.string.check_update_verify_failed))
            }
            if (versionName != null) {
                PendingUpdateStore.setPending(
                    context,
                    PendingUpdateStore.PendingUpdate(
                        targetVersionName = versionName,
                        targetVersionCode = versionCode,
                        apkPath = outFile.absolutePath,
                        apkSize = outFile.length(),
                        sha256 = sha,
                        downloadedAtMs = System.currentTimeMillis(),
                    ),
                )
            }
            DownloadResult.Success(outFile)
        } catch (e: Exception) {
            AppLogger.error(TAG, "download failed: ${e.javaClass.simpleName}: ${e.message}")
            DownloadResult.Error(e.message ?: e.javaClass.simpleName)
        }
    }

    /**
     * Whether the OS will allow this app to launch a package-installer
     * intent. On Android 8+ the user must grant "install unknown apps" per
     * source-app; older releases inherit the system-wide setting.
     */
    fun canInstall(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }
    }

    /**
     * Send the user to the system "install unknown apps" preferences page
     * for this package. Caller should re-check [canInstall] after the user
     * returns.
     */
    fun openInstallPermissionSettings(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
            data = Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    /**
     * Hand [apk] to the system package installer via FileProvider.
     * Caller must ensure [canInstall] before calling, otherwise the system
     * silently bounces back to the launcher. Returns false on any
     * launch failure so callers can surface an error instead of closing
     * the dialog with no visible feedback.
     */
    /**
     * If a pending APK from a previous download is still on disk and intact,
     * returns the [File]. The caller is responsible for checking
     * [canInstall] and firing [installApk]. Returns null when nothing pending
     * or when the cached file failed integrity checks — in the latter case
     * the pending record is cleared so the UI falls through to a fresh
     * download.
     */
    fun resumablePendingFile(context: Context): File? {
        val pending = PendingUpdateStore.getPending(context) ?: return null
        // Only resume if the persisted target is still newer than the running
        // build — protects against the case where the user updated by some
        // other means since the download.
        // [T-update-channels] The build number decides when the download
        // recorded one; older records fall back to the version names.
        val stale = pending.targetVersionCode?.let { it <= BuildConfig.VERSION_CODE }
            ?: (UpdatePolicy.compareVersionNames(pending.targetVersionName, BuildConfig.VERSION_NAME) <= 0)
        if (stale) {
            AppLogger.info(TAG, "pending target ${pending.targetVersionName} <= local; clearing")
            PendingUpdateStore.clearPending(context)
            return null
        }
        val file = PendingUpdateStore.verify(pending)
        if (file == null) {
            AppLogger.info(TAG, "pending APK failed integrity; clearing")
            PendingUpdateStore.clearPending(context)
            return null
        }
        return file
    }

    /**
     * [T-update-channels] Why [apk] must not be installed, read from the
     * archive itself: another package, or not newer than what is installed.
     * Null when it may go to the installer. The name, the release and the
     * digest all said it was right; this is the check that cannot be fooled
     * by a mislabelled asset.
     */
    fun archiveRefusal(context: Context, apk: File): String? {
        val info = runCatching {
            if (Build.VERSION.SDK_INT >= 33) {
                context.packageManager.getPackageArchiveInfo(apk.absolutePath, android.content.pm.PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageArchiveInfo(apk.absolutePath, 0)
            }
        }.getOrNull()
        val code = info?.let { if (Build.VERSION.SDK_INT >= 28) it.longVersionCode else @Suppress("DEPRECATION") it.versionCode.toLong() }
        val refusal = UpdatePolicy.archiveRefusal(info?.packageName, code, context.packageName, BuildConfig.VERSION_CODE.toLong())
        AppLogger.info(TAG, "[UpdateCheck] archive package=${info?.packageName} versionCode=$code refusal=$refusal")
        return refusal
    }

    fun installApk(context: Context, apk: File): Boolean {
        if (archiveRefusal(context, apk) != null) {
            // Never install it; drop it so a resume does not try again.
            PendingUpdateStore.clearPending(context)
            apk.delete()
            return false
        }
        return try {
            val authority = "${context.packageName}.fileprovider"
            val uri = FileProvider.getUriForFile(context, authority, apk)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            AppLogger.info(TAG, "installApk launched apk=${apk.absolutePath} size=${apk.length()}")
            // Once the installer is in flight we don't want a subsequent
            // resume to re-fire the intent (would double-prompt). Clear the
            // pending record now; if the user backs out, the next "Check for
            // Updates" tap will re-discover and re-download.
            PendingUpdateStore.clearPending(context)
            true
        } catch (e: Exception) {
            AppLogger.error(TAG, "installApk failed: ${e.javaClass.simpleName}: ${e.message}")
            false
        }
    }
}
