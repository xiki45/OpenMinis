package com.openminis.app.data

/**
 * [T-update-channels] Pure decisions of the in-app updater: version order,
 * which release a channel may take, which APK fits this device, when
 * an automatic check may run, and whether a badge shows. No Android, no I/O -
 * [UpdateChecker] and [UpdateStateStore] feed it and act on the answers.
 *
 * Distribution rules (spec, final revision - one repo, OpenMinis/OpenMinis):
 *  - stable: tag `X.Y` (patch `X.Y.Z`, never `X.Y.0`), not a prerelease;
 *  - beta: a prerelease tagged `0-beta-build<N>`, titled
 *    `Android 1.16-beta.3 (build 31)`. Clients 1.12-1.15 ignore the
 *    prerelease flag and cut a tag at its first `-`, so they read `0`: lower
 *    than any 1.x, never an update and never their "highest" release;
 *  - the FILENAME is the protocol (no latest.json): an APK named
 *    `MinisApp-<version>-build<N>-<abi>.apk` gives the version name, the
 *    build number and the ABI; GitHub's asset `digest` gives the sha256, the
 *    release's `prerelease` the channel, its `body` the changelog;
 *  - versionCode is one build counter shared by both channels and decides
 *    which of two releases is newer;
 *  - releases from before build numbers (1.12-1.14) take part only in the
 *    stable channel, by version name; `0.xx-preview` releases never.
 */
object UpdatePolicy {

    enum class Channel(val id: String) {
        STABLE("stable"),
        BETA("beta"),
        ;

        companion object {
            fun parse(raw: String?): Channel = entries.firstOrNull { it.id.equals(raw?.trim(), ignoreCase = true) } ?: STABLE
        }
    }

    /** Minimum time between successful automatic checks, by the current channel. */
    const val STABLE_INTERVAL_MS = 24L * 60 * 60 * 1000
    const val BETA_INTERVAL_MS = 6L * 60 * 60 * 1000

    /** After a failed attempt, the next automatic one waits at least this long. */
    const val RETRY_AFTER_FAILURE_MS = 15L * 60 * 1000

    fun intervalMs(channel: Channel): Long = if (channel == Channel.BETA) BETA_INTERVAL_MS else STABLE_INTERVAL_MS

    // ── Versions ────────────────────────────────────────────────────────────

    /**
     * `X.Y[.Z...][-pre]` with an optional leading `v`. Missing numeric parts
     * are 0, so `1.16` == `1.16.0`. A pre-release sorts below its release
     * (`1.16-beta.3` < `1.16`); pre-release labels compare part by part,
     * numerically where both are numbers (`beta.10` > `beta.9`).
     */
    fun compareVersionNames(a: String, b: String): Int {
        val (an, ap) = split(a)
        val (bn, bp) = split(b)
        for (i in 0 until maxOf(an.size, bn.size)) {
            val c = (an.getOrNull(i) ?: 0).compareTo(bn.getOrNull(i) ?: 0)
            if (c != 0) return c
        }
        if (ap == null && bp == null) return 0
        if (ap == null) return 1
        if (bp == null) return -1
        val ax = ap.split('.')
        val bx = bp.split('.')
        for (i in 0 until maxOf(ax.size, bx.size)) {
            val x = ax.getOrNull(i) ?: return -1
            val y = bx.getOrNull(i) ?: return 1
            val xi = x.toIntOrNull()
            val yi = y.toIntOrNull()
            val c = if (xi != null && yi != null) xi.compareTo(yi) else x.compareTo(y)
            if (c != 0) return c
        }
        return 0
    }

    private fun split(v: String): Pair<List<Int>, String?> {
        val t = v.trim().removePrefix("v").removePrefix("V")
        val dash = t.indexOf('-')
        val core = if (dash >= 0) t.substring(0, dash) else t
        val pre = if (dash >= 0) t.substring(dash + 1).ifEmpty { null } else null
        return core.split('.').map { it.toIntOrNull() ?: 0 } to pre
    }

    /** A beta version name (`1.16-beta.3`) or beta tag (`0-beta-build31`). */
    fun isBetaName(v: String): Boolean = v.contains("-beta", ignoreCase = true)

    /** The beta tag shape old clients read as `0`. */
    fun isBetaTag(tag: String): Boolean = tag.trim().startsWith("0-beta", ignoreCase = true)

    /** The `0.xx-preview` releases kept on RELEASE_REPO: never offered, never compared. */
    fun isLegacyPreview(tag: String): Boolean = tag.trim().endsWith("-preview", ignoreCase = true)

    /** What an APK's file name says about it. */
    data class ApkName(val version: String, val build: Int, val abi: String)

    /** The release protocol: `^MinisApp-(?<ver>.+)-build(?<build>\d+)-(?<abi>[\w-]+)\.apk$`. */
    private val APK_NAME = Regex("^MinisApp-(.+)-build(\\d+)-([\\w-]+)\\.apk$")

    /**
     * `MinisApp-1.16-beta.3-build31-arm64-v8a.apk` -> (1.16-beta.3, 31,
     * arm64-v8a). Null for a name outside the protocol - including the
     * pre-build history (`MinisApp-1.14-arm64-v8a.apk`), whose build is
     * unknown and which counts by version name on the stable channel only.
     */
    fun parseApkName(name: String): ApkName? {
        val m = APK_NAME.matchEntire(name.trim()) ?: return null
        val build = m.groupValues[2].toIntOrNull() ?: return null
        val ver = m.groupValues[1].takeIf { it.isNotBlank() } ?: return null
        return ApkName(ver, build, m.groupValues[3])
    }

    fun buildFromApkName(name: String): Int? = parseApkName(name)?.build

    fun versionFromApkName(name: String): String? = parseApkName(name)?.version

    /**
     * An asset's `digest` as a lower-case sha256 hex string: `sha256:<64 hex>`
     * -> `<hex>`. Null when missing or in another form - verification is then
     * skipped, not failed.
     */
    fun parseDigest(digest: String?): String? {
        val d = digest?.trim() ?: return null
        if (!d.startsWith("sha256:", ignoreCase = true)) return null
        val hex = d.substring(7).lowercase()
        return hex.takeIf { it.length == 64 && it.all { c -> c in '0'..'9' || c in 'a'..'f' } }
    }

    /**
     * Whether a downloaded APK may be handed to the installer: it is this app
     * (same package) and a newer build than the one installed. Checked on the
     * archive itself (PackageManager.getPackageArchiveInfo), whatever its name
     * or the release said. Returns the reason for a refusal, null when fine.
     */
    fun archiveRefusal(archivePackage: String?, archiveCode: Long?, ourPackage: String, installedCode: Long): String? = when {
        archivePackage == null || archiveCode == null -> "unreadable"
        archivePackage != ourPackage -> "package"
        archiveCode <= installedCode -> "not-newer"
        else -> null
    }

    // ── Releases and APKs ───────────────────────────────────────────────────

    data class Asset(val name: String, val url: String, val size: Long, val sha256: String?)

    /** One release as read from the release list. */
    data class Release(
        val tag: String,
        val name: String,
        val prerelease: Boolean,
        val draft: Boolean,
        val body: String,
        val assets: List<Asset>,
        val htmlUrl: String = "",
    )

    /** A release this device could install. */
    data class Candidate(
        val tag: String,
        val prerelease: Boolean,
        val versionName: String,
        val versionCode: Int?,
        val apk: Asset,
        val changelog: String,
        val releaseName: String,
        val htmlUrl: String,
    )

    /** ABIs named in our APK file names. */
    private val KNOWN_ABIS = listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")

    /**
     * The APK for this device: the first of [supportedAbis] (best first, as
     * Build.SUPPORTED_ABIS lists them) that an APK name carries; else an APK
     * naming no ABI at all (universal); else null - an APK built for another
     * ABI must not be offered.
     */
    fun chooseApk(assets: List<Asset>, supportedAbis: List<String>): Asset? {
        val apks = assets.filter { it.name.endsWith(".apk", ignoreCase = true) }
        // An APK on the protocol carries its ABI; a name with a build number
        // beats a legacy name for the same ABI.
        fun abiOf(a: Asset): String? = parseApkName(a.name)?.abi
            ?: KNOWN_ABIS.firstOrNull { a.name.contains("-$it", ignoreCase = true) }
        for (abi in supportedAbis) {
            apks.filter { abiOf(it).equals(abi, ignoreCase = true) }
                .maxByOrNull { if (parseApkName(it.name) != null) 1 else 0 }
                ?.let { return it }
        }
        return apks.firstOrNull { abiOf(it) == null }
    }

    /**
     * Whether [release] may be offered to [channel] at all:
     *  - drafts and the legacy `-preview` releases never;
     *  - stable: only a non-prerelease that is not a beta by tag;
     *  - beta: everything else (betas and stables; the build number then
     *    decides, which leaves out the pre-build history - see [isNewer]).
     */
    fun eligible(release: Release, channel: Channel): Boolean {
        if (release.draft || isLegacyPreview(release.tag)) return false
        return when (channel) {
            Channel.STABLE -> !release.prerelease && !isBetaTag(release.tag) && !isBetaName(release.tag)
            Channel.BETA -> true
        }
    }

    /**
     * [release] as a candidate for this device, or null (no APK for its ABIs).
     * Version name and build come from the APK name; a legacy name (no build)
     * keeps the tag as its version and no build number.
     */
    fun toCandidate(release: Release, supportedAbis: List<String>): Candidate? {
        val apk = chooseApk(release.assets, supportedAbis) ?: return null
        val parsed = parseApkName(apk.name)
        return Candidate(
            tag = release.tag,
            prerelease = release.prerelease,
            versionName = parsed?.version ?: release.tag.trim().removePrefix("v"),
            versionCode = parsed?.build,
            apk = apk,
            changelog = release.body,
            releaseName = release.name.ifBlank { release.tag },
            htmlUrl = release.htmlUrl,
        )
    }

    /**
     * Whether [c] is newer than the running build. versionCode decides when
     * the candidate has one; a candidate without a build number (pre-build
     * history) is compared by name, and only on the stable channel.
     */
    fun isNewer(c: Candidate, channel: Channel, localName: String, localCode: Int): Boolean = when {
        c.versionCode != null -> c.versionCode > localCode
        channel == Channel.STABLE -> compareVersionNames(c.versionName, localName) > 0
        else -> false
    }

    /** Order of two candidates: build number when both have one, else version name. */
    fun compareCandidates(a: Candidate, b: Candidate): Int =
        if (a.versionCode != null && b.versionCode != null) a.versionCode.compareTo(b.versionCode)
        else compareVersionNames(a.versionName, b.versionName)

    /** The best newer candidate, or null when the device is up to date. */
    fun pickUpdate(candidates: List<Candidate>, channel: Channel, localName: String, localCode: Int): Candidate? =
        candidates.filter { isNewer(it, channel, localName, localCode) }.maxWithOrNull(::compareCandidates)

    // ── Automatic checks ────────────────────────────────────────────────────

    /** The persisted facts the throttle needs. Times are wall-clock ms. */
    data class ThrottleState(
        val lastSuccessAt: Long?,
        val lastAttemptAt: Long?,
        /** Channel and local versionCode the cached result belongs to. */
        val cacheChannel: Channel?,
        val cacheVersionCode: Int?,
    )

    enum class AutoDecision {
        /** No check: the build or the user turned automatic checks off. */
        DISABLED,
        /** No check: one is already running. */
        IN_FLIGHT,
        /** No check: the cache is fresh for this channel. */
        FRESH,
        /** No check: the last attempt failed less than 15 min ago. */
        BACKING_OFF,
        /** Check now. */
        CHECK,
        /** Clear the cache (other channel / other installed build), then check now. */
        INVALIDATE_AND_CHECK,
    }

    /**
     * Whether an automatic check runs now. A channel switch or a new installed
     * build invalidates the cache whatever the interval; a time in the future
     * (the clock moved back) counts as expired; a failure only blocks for
     * [RETRY_AFTER_FAILURE_MS], and it never moves [ThrottleState.lastSuccessAt].
     */
    fun autoDecision(
        now: Long,
        state: ThrottleState,
        channel: Channel,
        localCode: Int,
        buildAllowsAuto: Boolean,
        userEnabled: Boolean,
        inFlight: Boolean,
    ): AutoDecision {
        if (!buildAllowsAuto || !userEnabled) return AutoDecision.DISABLED
        if (inFlight) return AutoDecision.IN_FLIGHT
        val cacheValid = state.cacheChannel == channel && state.cacheVersionCode == localCode
        if (!cacheValid && (state.cacheChannel != null || state.cacheVersionCode != null)) return AutoDecision.INVALIDATE_AND_CHECK
        val success = state.lastSuccessAt
        val expired = !cacheValid || success == null || now < success || now - success >= intervalMs(channel)
        if (!expired) return AutoDecision.FRESH
        val attempt = state.lastAttemptAt
        val failedRecently = attempt != null && (success == null || attempt > success) &&
            now >= attempt && now - attempt < RETRY_AFTER_FAILURE_MS
        return if (failedRecently) AutoDecision.BACKING_OFF else AutoDecision.CHECK
    }

    // ── Badges ──────────────────────────────────────────────────────────────

    /**
     * The update badge (About row, About's check row, and for beta the home
     * settings icon): a newer build is known and the user has neither skipped
     * nor downloaded it. Hidden on a build without automatic checks and while
     * the user has them off; the cache stays. Purely local - no request.
     */
    fun hasUpdateBadge(
        buildAllowsAuto: Boolean,
        userEnabled: Boolean,
        availableCode: Int?,
        localCode: Int,
        skippedCode: Int?,
        downloadedCode: Int?,
    ): Boolean {
        if (!buildAllowsAuto || !userEnabled || availableCode == null) return false
        return availableCode > localCode && availableCode != skippedCode && availableCode != downloadedCode
    }

    /** The home settings-icon badge: the same badge, on the beta channel only. */
    fun hasHomeBadge(hasUpdateBadge: Boolean, channel: Channel): Boolean = hasUpdateBadge && channel == Channel.BETA

    /** `1.15 (build 29)` */
    fun displayVersion(name: String, code: Int?): String = if (code != null) "$name (build $code)" else name
}
