package com.openminis.app.util

import android.content.Context
import android.content.SharedPreferences
import com.openminis.app.ProductionSources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File
import javax.crypto.AEADBadTagException

/**
 * [T-android-keystore-no-cascade] One unreadable encrypted store must cost that
 * store only - never the shared master key, and with it every other credential.
 * AndroidKeyStore does not exist on the JVM, so [EncryptedPrefsFactory.open]
 * is driven through a fake [EncryptedPrefsFactory.Ops].
 */
@RunWith(RobolectricTestRunner::class)
class EncryptedPrefsFactoryTest {

    private val ctx: Context get() = RuntimeEnvironment.getApplication()

    private fun prefs(name: String): SharedPreferences = ctx.getSharedPreferences(name, Context.MODE_PRIVATE)

    /** [buildFailures]: how many build() calls fail per file before one succeeds. */
    private inner class FakeOps(
        val buildFailures: MutableMap<String, Int> = mutableMapOf(),
        var keyWorks: Boolean = true,
    ) : EncryptedPrefsFactory.Ops {
        val builds = mutableListOf<String>()
        val discarded = mutableListOf<String>()
        var masterKeyDeletes = 0
        val adopted = mutableListOf<String>()

        override fun build(fileName: String): SharedPreferences {
            builds += fileName
            val left = buildFailures[fileName] ?: 0
            if (left > 0) {
                buildFailures[fileName] = left - 1
                throw AEADBadTagException()
            }
            return prefs("enc_$fileName")
        }
        override fun masterKeyWorks() = keyWorks
        override fun discardStore(fileName: String) { discarded += fileName }
        override fun deleteMasterKey() { masterKeyDeletes++ }
        override fun plainFallback(fileName: String) = prefs(fileName + EncryptedPrefsFactory.FALLBACK_SUFFIX)
        override fun adoptFallback(fileName: String, into: SharedPreferences) { adopted += fileName }
        override fun log(priority: Int, msg: String, t: Throwable?) {}
    }

    @Before
    fun reset() = EncryptedPrefsFactory.resetCacheForTest()

    @Test
    fun `healthy store opens without discarding anything`() {
        val ops = FakeOps()
        val p = EncryptedPrefsFactory.open(ops, "provider_secrets")
        assertSame(prefs("enc_provider_secrets"), p)
        assertEquals(listOf("provider_secrets"), ops.builds)
        assertTrue(ops.discarded.isEmpty())
        assertEquals(0, ops.masterKeyDeletes)
    }

    @Test
    fun `a one-off keystore failure is retried before anything is deleted`() {
        val ops = FakeOps(mutableMapOf("env_var_values" to 1))
        EncryptedPrefsFactory.open(ops, "env_var_values")
        assertEquals(2, ops.builds.size)
        assertTrue(ops.discarded.isEmpty())
        assertEquals(0, ops.masterKeyDeletes)
    }

    @Test
    fun `an orphaned keyset under a working master key discards only that store`() {
        val ops = FakeOps(mutableMapOf("backup_rclone_secrets" to 2))
        val p = EncryptedPrefsFactory.open(ops, "backup_rclone_secrets")
        assertSame(prefs("enc_backup_rclone_secrets"), p)
        assertEquals(listOf("backup_rclone_secrets"), ops.discarded)
        assertEquals("the shared master key must survive", 0, ops.masterKeyDeletes)
    }

    @Test
    fun `a broken store leaves every other store untouched`() {
        // The Pixel 4a repro: one corrupted keyset used to empty provider_secrets
        // and oauth_prefs at the next cold start.
        val ops = FakeOps(mutableMapOf("backup_rclone_secrets" to 2))
        EncryptedPrefsFactory.open(ops, "backup_rclone_secrets")
        EncryptedPrefsFactory.open(ops, "provider_secrets")
        EncryptedPrefsFactory.open(ops, "oauth_prefs")
        assertEquals(listOf("backup_rclone_secrets"), ops.discarded)
        assertEquals(0, ops.masterKeyDeletes)
    }

    @Test
    fun `a master key that cannot encrypt is recreated`() {
        val ops = FakeOps(mutableMapOf("provider_secrets" to 2), keyWorks = false)
        EncryptedPrefsFactory.open(ops, "provider_secrets")
        assertEquals(1, ops.masterKeyDeletes)
        assertEquals(listOf("provider_secrets"), ops.discarded)
    }

    @Test
    fun `rebuild failing falls back to plain prefs and keeps the master key`() {
        val ops = FakeOps(mutableMapOf("oauth_prefs" to 3))
        val p = EncryptedPrefsFactory.open(ops, "oauth_prefs")
        assertSame(prefs("oauth_prefs_plain_fallback"), p)
        assertEquals(0, ops.masterKeyDeletes)
        assertTrue("a fallback is never adopted into itself", ops.adopted.isEmpty())
    }

    @Test
    fun `values from an earlier plain fallback are adopted on a successful open`() {
        val ops = FakeOps(mutableMapOf("env_var_values" to 2))
        EncryptedPrefsFactory.open(ops, "env_var_values")
        assertEquals(listOf("env_var_values"), ops.adopted)
    }

    @Test
    fun `each store is opened once per process however many callers ask`() {
        var creates = 0
        val first = EncryptedPrefsFactory.cached("oauth_prefs") { creates++; prefs("x") }
        val again = EncryptedPrefsFactory.cached("oauth_prefs") { creates++; prefs("y") }
        assertSame(first, again)
        assertEquals(1, creates)
    }

    @Test
    fun `moveEntries copies every type then clears the plain store`() {
        val plain = prefs("mv_plain").apply {
            edit().clear().putString("s", "v").putBoolean("b", true).putInt("i", 3)
                .putLong("l", 4L).putFloat("f", 1.5f).putStringSet("set", setOf("a")).commit()
        }
        val into = prefs("mv_into").apply { edit().clear().putString("s", "old").commit() }
        assertEquals(6, EncryptedPrefsFactory.moveEntries(plain, into))
        assertEquals("v", into.getString("s", null))
        assertTrue(into.getBoolean("b", false))
        assertEquals(3, into.getInt("i", 0))
        assertEquals(4L, into.getLong("l", 0))
        assertEquals(1.5f, into.getFloat("f", 0f))
        assertEquals(setOf("a"), into.getStringSet("set", null))
        assertTrue(plain.all.isEmpty())
        assertEquals(0, EncryptedPrefsFactory.moveEntries(plain, into))
    }

    @Test
    fun `factory never deletes the master key outside the unusable-key branch`() {
        val src = ProductionSources.read("util/EncryptedPrefsFactory.kt")
        val open = src.substringAfter("internal fun open(").substringBefore("internal fun moveEntries")
        assertEquals(1, Regex("""ops\.deleteMasterKey\(\)""").findAll(open).count())
        assertTrue(open.substringBefore("ops.deleteMasterKey()").contains("} else {"))
        assertFalse("File.delete leaves the process prefs cache stale", src.contains("File(dir, \"\$fileName.xml\").delete()"))
    }

    @Test
    fun `every encrypted store is excluded from system backup and device transfer`() {
        val names = mutableSetOf<String>()
        for (f in ProductionSources.allKotlinFiles()) {
            if (f.name == "EncryptedPrefsFactory.kt") continue
            val src = f.readText()
            for (m in Regex("""safeCreate\(\s*[\w.()]+\s*,\s*("([^"]+)"|(\w+))\s*\)""").findAll(src)) {
                val name = m.groupValues[2].ifEmpty {
                    val ident = m.groupValues[3]
                    Regex("""const val $ident\s*=\s*"([^"]+)"""").find(src)?.groupValues?.get(1)
                        ?: error("${f.name}: cannot resolve store name $ident")
                }
                names += name
            }
        }
        assertTrue("found the stores: $names", names.containsAll(listOf("provider_secrets", "oauth_prefs")))
        val xmlDir = File(ProductionSources.mainRoot(), "../../../../res/xml")
        for (rules in listOf("backup_rules.xml", "data_extraction_rules.xml")) {
            val xml = File(xmlDir, rules).readText()
            for (n in names) {
                assertTrue("$rules misses $n.xml", xml.contains("path=\"$n.xml\""))
                assertTrue("$rules misses ${n}_plain_fallback.xml", xml.contains("path=\"${n}_plain_fallback.xml\""))
            }
        }
        val manifest = File(ProductionSources.mainRoot(), "../../../../AndroidManifest.xml").readText()
        assertTrue(manifest.contains("android:fullBackupContent=\"@xml/backup_rules\""))
        assertTrue(manifest.contains("android:dataExtractionRules=\"@xml/data_extraction_rules\""))
    }
}
