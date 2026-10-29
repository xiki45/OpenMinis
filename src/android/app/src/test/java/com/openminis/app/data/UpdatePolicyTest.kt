package com.openminis.app.data

import com.openminis.app.data.UpdatePolicy.AutoDecision
import com.openminis.app.data.UpdatePolicy.Channel
import com.openminis.app.data.UpdatePolicy.ThrottleState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** [T-update-channels] The updater's pure decisions. */
class UpdatePolicyTest {

    // ── Versions ────────────────────────────────────────────────────────────

    @Test
    fun `semver order with two-part stable tags and beta pre-releases`() {
        val c = UpdatePolicy::compareVersionNames
        assertTrue(c("1.16", "1.16-beta.3") > 0)
        assertTrue(c("1.16-beta.3", "1.15") > 0)
        assertTrue(c("1.16-beta.10", "1.16-beta.9") > 0)
        assertTrue(c("1.16.1", "1.16") > 0)
        assertEquals(0, c("1.16", "1.16.0"))
        assertEquals(0, c("v1.16", "1.16"))
        assertTrue(c("1.15", "1.16") < 0)
        assertTrue(c("1.9", "1.10") < 0)
    }

    @Test
    fun `build numbers come from the APK file name`() {
        assertEquals(31, UpdatePolicy.buildFromApkName("MinisApp-1.16-beta.3-build31-arm64-v8a.apk"))
        assertEquals(32, UpdatePolicy.buildFromApkName("MinisApp-1.16-build32-arm64-v8a.apk"))
        assertNull(UpdatePolicy.buildFromApkName("MinisApp-1.14-arm64-v8a.apk"))
        assertNull(UpdatePolicy.buildFromApkName("MinisApp-1.16-buildX-arm64-v8a.apk"))
    }

    @Test
    fun `display version carries the build`() {
        assertEquals("1.15 (build 29)", UpdatePolicy.displayVersion("1.15", 29))
        assertEquals("1.16-beta.3 (build 31)", UpdatePolicy.displayVersion("1.16-beta.3", 31))
        assertEquals("1.14", UpdatePolicy.displayVersion("1.14", null))
    }

    // ── APK choice ──────────────────────────────────────────────────────────

    private fun a(name: String) = UpdatePolicy.Asset(name, "https://x/$name", 10, null)

    @Test
    fun `the APK follows the device ABIs, best first, never a foreign ABI`() {
        val assets = listOf(a("MinisApp-1.16-build32-armeabi-v7a.apk"), a("MinisApp-1.16-build32-arm64-v8a.apk"), a("latest.json"))
        assertEquals("MinisApp-1.16-build32-arm64-v8a.apk", UpdatePolicy.chooseApk(assets, listOf("arm64-v8a", "armeabi-v7a"))!!.name)
        assertEquals("MinisApp-1.16-build32-armeabi-v7a.apk", UpdatePolicy.chooseApk(assets, listOf("armeabi-v7a"))!!.name)
        assertNull(UpdatePolicy.chooseApk(assets, listOf("x86_64")))
        // A universal APK names no ABI.
        assertEquals("MinisApp-1.16-build32.apk", UpdatePolicy.chooseApk(listOf(a("MinisApp-1.16-build32.apk")), listOf("x86_64"))!!.name)
    }

    // ── Channels ────────────────────────────────────────────────────────────

    private fun r(tag: String, pre: Boolean = false, apk: String? = "MinisApp-$tag-arm64-v8a.apk", draft: Boolean = false) =
        UpdatePolicy.Release(tag, "Android $tag", pre, draft, "notes $tag", listOfNotNull(apk?.let { a(it) }))

    @Test
    fun `stable never takes a beta or a prerelease, previews never take part`() {
        val e = UpdatePolicy::eligible
        assertFalse(e(r("0-beta-build31", pre = true), Channel.STABLE))
        assertFalse("beta tag even if mis-flagged", e(r("0-beta-build31", pre = false), Channel.STABLE))
        assertFalse("any prerelease", e(r("1.16", pre = true), Channel.STABLE))
        assertTrue(e(r("1.16"), Channel.STABLE))
        assertTrue(e(r("0-beta-build31", pre = true), Channel.BETA))
        assertTrue("beta also sees stables", e(r("1.16"), Channel.BETA))
        for (ch in Channel.entries) {
            assertFalse("legacy preview ($ch)", e(r("0.22-preview", pre = true), ch))
            assertFalse("draft ($ch)", e(r("1.16", draft = true), ch))
        }
    }

    private fun cand(tag: String, apk: String, pre: Boolean = false) =
        UpdatePolicy.toCandidate(r(tag, pre = pre, apk = apk), listOf("arm64-v8a"))!!

    @Test
    fun `versionCode decides, history without a build only counts on stable by name`() {
        val history = cand("1.14", "MinisApp-1.14-arm64-v8a.apk")
        val beta31 = cand("0-beta-build31", "MinisApp-1.16-beta.3-build31-arm64-v8a.apk", pre = true)
        val stable32 = cand("1.16", "MinisApp-1.16-build32-arm64-v8a.apk")
        assertEquals(31, beta31.versionCode)
        assertEquals("the display name comes from the APK, not the tag", "1.16-beta.3", beta31.versionName)
        assertNull(history.versionCode)

        // Running 1.15 (29).
        assertEquals(stable32, UpdatePolicy.pickUpdate(listOf(history, stable32), Channel.STABLE, "1.15", 29))
        assertEquals(stable32, UpdatePolicy.pickUpdate(listOf(history, beta31, stable32), Channel.BETA, "1.15", 29))
        assertEquals(beta31, UpdatePolicy.pickUpdate(listOf(history, beta31), Channel.BETA, "1.15", 29))
        // A newer-by-name release without a build: stable only.
        val history16 = cand("1.16", "MinisApp-1.16-arm64-v8a.apk")
        assertEquals(history16, UpdatePolicy.pickUpdate(listOf(history16), Channel.STABLE, "1.15", 29))
        assertNull(UpdatePolicy.pickUpdate(listOf(history16), Channel.BETA, "1.15", 29))
        // Up to date.
        assertNull(UpdatePolicy.pickUpdate(listOf(history, stable32), Channel.STABLE, "1.16", 32))
        // From beta 1.16-beta.3 (31) back on stable: 1.16 build 32 is the way out, no downgrade.
        assertEquals(stable32, UpdatePolicy.pickUpdate(listOf(history, stable32), Channel.STABLE, "1.16-beta.3", 31))
        assertNull(UpdatePolicy.pickUpdate(listOf(history), Channel.STABLE, "1.16-beta.3", 31))
        // A stable hotfix with a higher build beats an older beta for beta users.
        val beta33 = cand("0-beta-build33", "MinisApp-1.17-beta.1-build33-arm64-v8a.apk", pre = true)
        val stable34 = cand("1.16.1", "MinisApp-1.16.1-build34-arm64-v8a.apk")
        assertEquals(stable34, UpdatePolicy.pickUpdate(listOf(beta33, stable34), Channel.BETA, "1.16", 32))
    }

    @Test
    fun `beta names and builds parse from the APK name`() {
        assertEquals("1.16-beta.3", UpdatePolicy.versionFromApkName("MinisApp-1.16-beta.3-build31-arm64-v8a.apk"))
        assertEquals("1.16", UpdatePolicy.versionFromApkName("MinisApp-1.16-build32-arm64-v8a.apk"))
        assertNull(UpdatePolicy.versionFromApkName("MinisApp-1.14-arm64-v8a.apk"))
        assertTrue(UpdatePolicy.isBetaTag("0-beta-build31"))
        assertFalse(UpdatePolicy.isBetaTag("1.16"))
    }

    @Test
    fun `the file name is the protocol - version, build and ABI`() {
        val p = UpdatePolicy::parseApkName
        assertEquals(UpdatePolicy.ApkName("1.16", 32, "arm64-v8a"), p("MinisApp-1.16-build32-arm64-v8a.apk"))
        assertEquals(UpdatePolicy.ApkName("1.16-beta.3", 31, "arm64-v8a"), p("MinisApp-1.16-beta.3-build31-arm64-v8a.apk"))
        assertEquals(UpdatePolicy.ApkName("1.16.1", 40, "armeabi-v7a"), p("MinisApp-1.16.1-build40-armeabi-v7a.apk"))
        assertEquals(UpdatePolicy.ApkName("1.16", 32, "x86_64"), p("MinisApp-1.16-build32-x86_64.apk"))
        // The pre-build history is outside the protocol.
        assertNull(p("MinisApp-1.14-arm64-v8a.apk"))
        assertNull(p("MinisApp-0.22-preview-arm64-v8a.apk"))
        // Malformed.
        for (bad in listOf("MinisApp-1.16-buildX-arm64-v8a.apk", "MinisApp--build32-arm64-v8a.apk", "MinisApp-1.16-build32-.apk",
            "Other-1.16-build32-arm64-v8a.apk", "MinisApp-1.16-build32-arm64-v8a.zip", "MinisApp-1.16-build-arm64-v8a.apk", "")) {
            assertNull(bad, p(bad))
        }
    }

    @Test
    fun `GitHub's asset digest gives the sha256, anything else skips the check`() {
        val hex = "38141df32c73ea36a3d8d0fb0754f575457a5b95ec3dd9c3c674abfdefbc581b"
        assertEquals(hex, UpdatePolicy.parseDigest("sha256:$hex"))
        assertEquals(hex, UpdatePolicy.parseDigest("SHA256:${hex.uppercase()}"))
        assertNull(UpdatePolicy.parseDigest(null))
        assertNull(UpdatePolicy.parseDigest(""))
        assertNull(UpdatePolicy.parseDigest("md5:abcd"))
        assertNull(UpdatePolicy.parseDigest("sha256:1234"))
        assertNull(UpdatePolicy.parseDigest("sha256:" + "z".repeat(64)))
        assertNull(UpdatePolicy.parseDigest(hex))
    }

    @Test
    fun `the installer only gets this app at a newer build`() {
        val r = UpdatePolicy::archiveRefusal
        assertNull(r("com.openminis.app", 32L, "com.openminis.app", 29L))
        assertEquals("package", r("com.evil.app", 32L, "com.openminis.app", 29L))
        assertEquals("not-newer", r("com.openminis.app", 29L, "com.openminis.app", 29L))
        assertEquals("not-newer", r("com.openminis.app", 28L, "com.openminis.app", 29L))
        assertEquals("unreadable", r(null, null, "com.openminis.app", 29L))
    }

    @Test
    fun `a build-numbered APK beats a legacy one for the same ABI`() {
        val assets = listOf(a("MinisApp-1.16-arm64-v8a.apk"), a("MinisApp-1.16-build32-arm64-v8a.apk"))
        assertEquals("MinisApp-1.16-build32-arm64-v8a.apk", UpdatePolicy.chooseApk(assets, listOf("arm64-v8a"))!!.name)
    }

    // ── Throttle ────────────────────────────────────────────────────────────

    private val H = 60 * 60 * 1000L

    private fun d(
        now: Long,
        success: Long? = null,
        attempt: Long? = null,
        cacheCh: Channel? = Channel.STABLE,
        cacheCode: Int? = 29,
        ch: Channel = Channel.STABLE,
        code: Int = 29,
        auto: Boolean = true,
        user: Boolean = true,
        inFlight: Boolean = false,
    ) = UpdatePolicy.autoDecision(now, ThrottleState(success, attempt, cacheCh, cacheCode), ch, code, auto, user, inFlight)

    @Test
    fun `one check per interval - 24 h stable, 6 h beta, by the current channel`() {
        assertEquals(AutoDecision.CHECK, d(now = 100 * H, cacheCh = null, cacheCode = null))
        assertEquals(AutoDecision.FRESH, d(now = 100 * H + 23 * H, success = 100 * H, attempt = 100 * H))
        assertEquals(AutoDecision.CHECK, d(now = 100 * H + 24 * H, success = 100 * H, attempt = 100 * H))
        assertEquals(AutoDecision.FRESH, d(now = 100 * H + 5 * H, success = 100 * H, cacheCh = Channel.BETA, ch = Channel.BETA))
        assertEquals(AutoDecision.CHECK, d(now = 100 * H + 6 * H, success = 100 * H, cacheCh = Channel.BETA, ch = Channel.BETA))
    }

    @Test
    fun `off, building without auto checks, or already running - no request`() {
        assertEquals(AutoDecision.DISABLED, d(now = 0, user = false))
        assertEquals(AutoDecision.DISABLED, d(now = 0, auto = false))
        assertEquals(AutoDecision.IN_FLIGHT, d(now = 0, inFlight = true))
    }

    @Test
    fun `a failure does not count as a check and backs off 15 min`() {
        val s = 100 * H
        assertEquals(AutoDecision.BACKING_OFF, d(now = s + 25 * H + 10 * 60_000, success = s, attempt = s + 25 * H))
        assertEquals(AutoDecision.CHECK, d(now = s + 25 * H + 15 * 60_000, success = s, attempt = s + 25 * H))
        // Never succeeded, failed 1 min ago.
        assertEquals(AutoDecision.BACKING_OFF, d(now = 61_000, attempt = 1_000, cacheCh = Channel.STABLE))
    }

    @Test
    fun `a clock moved back counts as expired`() {
        assertEquals(AutoDecision.CHECK, d(now = 50 * H, success = 100 * H, attempt = 100 * H))
    }

    @Test
    fun `a channel switch or a new installed build invalidates the cache whatever the interval`() {
        assertEquals(AutoDecision.INVALIDATE_AND_CHECK, d(now = 101 * H, success = 100 * H, ch = Channel.BETA))
        assertEquals(AutoDecision.INVALIDATE_AND_CHECK, d(now = 101 * H, success = 100 * H, code = 30))
    }

    // ── Badges ──────────────────────────────────────────────────────────────

    @Test
    fun `badge - newer, not skipped, not downloaded, auto checks on`() {
        val b = UpdatePolicy::hasUpdateBadge
        assertTrue(b(true, true, 32, 29, null, null))
        assertFalse("installed it", b(true, true, 32, 32, null, null))
        assertFalse("skipped", b(true, true, 32, 29, 32, null))
        assertTrue("skipped an older one", b(true, true, 33, 29, 32, null))
        assertFalse("downloaded", b(true, true, 32, 29, null, 32))
        assertFalse("auto off hides it", b(true, false, 32, 29, null, null))
        assertFalse("store build", b(false, true, 32, 29, null, null))
        assertFalse("nothing known", b(true, true, null, 29, null, null))
    }

    @Test
    fun `the home badge is the same badge on the beta channel only`() {
        assertTrue(UpdatePolicy.hasHomeBadge(true, Channel.BETA))
        assertFalse(UpdatePolicy.hasHomeBadge(true, Channel.STABLE))
        assertFalse(UpdatePolicy.hasHomeBadge(false, Channel.BETA))
    }

    @Test
    fun `channel ids parse with stable as the default`() {
        assertEquals(Channel.BETA, Channel.parse("beta"))
        assertEquals(Channel.STABLE, Channel.parse("stable"))
        assertEquals(Channel.STABLE, Channel.parse(null))
        assertEquals(Channel.STABLE, Channel.parse("nightly"))
    }
}
