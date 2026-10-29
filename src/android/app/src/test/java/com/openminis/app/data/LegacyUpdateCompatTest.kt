package com.openminis.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-update-channels] Betas are published on OpenMinis/OpenMinis as
 * prereleases tagged `0-beta-build<N>`. Every client already in users' hands
 * reads that repo, ignores the prerelease flag, and must NOT be offered a
 * beta - otherwise every old user is pushed onto the beta channel.
 *
 * The fixture below is the update decision of the shipped clients, copied
 * verbatim from git history. UpdateChecker.kt is byte-identical in all four
 * (md5 ee5c06eaf8d6ed5bd3ef133b132df3d3; last changed in 1cea75e93 on
 * 2026-06-05, before 1.12):
 *   1.12  83c8038c6  "align version with iOS at 1.12, drop preview suffix"
 *   1.13  4bdd7d817  "bump version to 1.13 (versionCode 25)"
 *   1.14  d7f1335e7  "set version to 1.14 (versionCode stays 28)"
 *   1.15  9e4fe13ba  "bump version to 1.15 (versionCode 29)"
 * so one copy covers every distinct historical logic.
 */
class LegacyUpdateCompatTest {

    // ── Verbatim from UpdateChecker.kt @ 1.12-1.15 ──────────────────────────

    private fun normalizeTag(tag: String): String {
        val trimmed = tag.trim().removePrefix("v").removePrefix("V")
        // "0.1-preview" → "0.1"; "0.1.0" → "0.1.0"; "1.2.3-rc1" → "1.2.3"
        val dashIdx = trimmed.indexOf('-')
        return if (dashIdx > 0) trimmed.substring(0, dashIdx) else trimmed
    }

    private fun compareVersions(a: String, b: String): Int {
        val ap = a.split('.', '-')
        val bp = b.split('.', '-')
        val n = maxOf(ap.size, bp.size)
        for (i in 0 until n) {
            val x = ap.getOrNull(i) ?: ""
            val y = bp.getOrNull(i) ?: ""
            val xi = x.toIntOrNull()
            val yi = y.toIntOrNull()
            val c = if (xi != null && yi != null) xi.compareTo(yi) else x.compareTo(y)
            if (c != 0) return c
        }
        return 0
    }

    // The decision part of the old check(): same filters, same `highest`,
    // same upgrade pick, same NoApkAsset rule, over the first 30 releases
    // (`?per_page=30`) in GitHub's order (newest first).
    private data class Rel(val tag: String, val prerelease: Boolean, val hasApk: Boolean, val draft: Boolean = false)

    private sealed class Old {
        data class Update(val tag: String) : Old()
        data class NoApkAsset(val tag: String) : Old()
        object UpToDate : Old()
        object NoRelease : Old()
    }

    private fun oldCheck(releases: List<Rel>, localVersionName: String): Old {
        data class ReleaseInfo(val tagName: String, val versionName: String, val apk: Boolean)
        val candidates = releases.take(30)
            .filter { !it.draft && it.tag.isNotEmpty() }
            .map { ReleaseInfo(it.tag, normalizeTag(it.tag), it.hasApk) }
        if (candidates.isEmpty()) return Old.NoRelease
        val localVer = normalizeTag(localVersionName)
        val highest = candidates.maxWithOrNull(compareBy { compareVersions(it.versionName, "0") }) ?: candidates.first()
        val upgradeCandidate = candidates
            .filter { it.apk }
            .filter { compareVersions(it.versionName, localVer) > 0 }
            .maxWithOrNull(compareBy { compareVersions(it.versionName, "0") })
        if (upgradeCandidate != null) return Old.Update(upgradeCandidate.tagName)
        val highestVsLocal = compareVersions(highest.versionName, localVer)
        if (highestVsLocal > 0 && !highest.apk) return Old.NoApkAsset(highest.tagName)
        return Old.UpToDate
    }

    // ── The repo as it is, and as it will be ────────────────────────────────

    /** OpenMinis/OpenMinis today, newest first: 1.14, 1.13, 1.12, then the previews. */
    private val existing: List<Rel> = listOf(Rel("1.14", false, true), Rel("1.13", false, true), Rel("1.12", false, true)) +
        listOf(22, 20, 19, 18, 17, 15, 11, 10, 9, 8, 7, 6, 5, 4, 3, 2, 1, 16, 14, 13, 12).map { Rel("0.$it-preview", true, true) }

    private fun beta(build: Int, apk: Boolean = true) = Rel("0-beta-build$build", true, apk)

    private val olds = listOf("1.12", "1.13", "1.14", "1.15")

    @Test
    fun `an old client is never offered a beta and never told a beta has no APK`() {
        val betas = (31..40).map { beta(it) } // the 10 the release command keeps
        val layouts = listOf(
            betas + existing,
            betas.take(5) + existing,
            listOf(beta(41, apk = false)) + betas + existing, // a beta without an APK on top
            existing.take(1) + betas + existing.drop(1),
        )
        for (local in olds + "1.16") {
            for (layout in layouts) {
                val r = oldCheck(layout, local)
                assertFalse("$local offered a beta: $r", r is Old.Update && r.tag.startsWith("0-beta"))
                assertFalse("$local NoApkAsset for a beta: $r", r is Old.NoApkAsset && r.tag.startsWith("0-beta"))
            }
        }
        assertEquals(Old.UpToDate, oldCheck(betas + existing, "1.15"))
        assertEquals(Old.UpToDate, oldCheck(listOf(beta(41, apk = false)) + betas + existing, "1.15"))
    }

    @Test
    fun `an old client still gets the next stable, wherever the betas sit`() {
        val stable16 = Rel("1.16", false, true)
        val betas = (31..40).map { beta(it) }
        for (layout in listOf(listOf(stable16) + betas + existing, betas + listOf(stable16) + existing, betas.take(3) + stable16 + betas.drop(3) + existing)) {
            for (local in olds) assertEquals(local, Old.Update("1.16"), oldCheck(layout, local))
            assertEquals(Old.UpToDate, oldCheck(layout, "1.16"))
        }
        // The pruned window (10 betas) keeps the stables inside the old client's 30.
        assertTrue((betas + stable16 + existing).take(30).any { it.tag == "1.16" })
    }

    @Test
    fun `existing stables keep behaving as they always did`() {
        assertEquals(Old.UpToDate, oldCheck(existing, "1.15"))
        assertEquals(Old.UpToDate, oldCheck(existing, "1.14"))
        assertEquals(Old.Update("1.14"), oldCheck(existing, "1.13"))
        // Patch tags work: 1.16.1 over 1.16.
        assertEquals(Old.Update("1.16.1"), oldCheck(listOf(Rel("1.16.1", false, true), Rel("1.16", false, true)) + existing, "1.16"))
    }

    @Test
    fun `why stable tags stay X_Y - old logic takes 1_16_0 as newer than 1_16`() {
        assertTrue(compareVersions(normalizeTag("1.16.0"), normalizeTag("1.16")) > 0)
        assertEquals(0, compareVersions(normalizeTag("1.16"), normalizeTag("1.16")))
        // And why a beta tag must not start with a letter: "beta" > "1" as a string.
        assertTrue(compareVersions(normalizeTag("beta-build31"), normalizeTag("1.15")) > 0)
        assertTrue(compareVersions(normalizeTag("0-beta-build31"), normalizeTag("1.12")) < 0)
    }

    @Test
    fun `the new client agrees - no beta for stable users`() {
        val all = listOf(beta(40), Rel("1.16", false, true)) + existing
        val releases = all.map {
            UpdatePolicy.Release(
                it.tag, it.tag, it.prerelease, it.draft, "",
                if (it.hasApk) listOf(UpdatePolicy.Asset(apkName(it.tag), "u", 1, null)) else emptyList(),
            )
        }
        val stable = releases.filter { UpdatePolicy.eligible(it, UpdatePolicy.Channel.STABLE) }
        assertTrue(stable.none { it.prerelease || it.tag.startsWith("0-") })
        assertEquals(listOf("1.16", "1.14", "1.13", "1.12"), stable.map { it.tag })
    }

    private fun apkName(tag: String) = when {
        tag.startsWith("0-beta-build") -> "MinisApp-1.16-beta.9-build${tag.removePrefix("0-beta-build")}-arm64-v8a.apk"
        else -> "MinisApp-$tag-arm64-v8a.apk"
    }
}
