package com.openminis.app.backup

import android.content.Context
import android.content.SharedPreferences
import com.openminis.app.ProductionSources
import com.openminis.app.backup.remote.RcloneRemoteStore
import com.openminis.app.backup.remote.RcloneRemoteStore.SecretEdit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * [T-android-backup-dest-edit] Editing a saved backup destination: blank
 * password keeps the secret, anonymous clears it, a failed connection test
 * changes nothing, renames move the secret and keep the switch, and history
 * records follow the new name.
 */
@RunWith(RobolectricTestRunner::class)
class RcloneRemoteEditTest {

    private val ctx: Context get() = RuntimeEnvironment.getApplication()
    private lateinit var prefs: SharedPreferences
    private lateinit var secrets: SharedPreferences
    private lateinit var store: RcloneRemoteStore

    @Before
    fun setUp() {
        prefs = ctx.getSharedPreferences("edit-test-remotes", Context.MODE_PRIVATE).also { it.edit().clear().commit() }
        secrets = ctx.getSharedPreferences("edit-test-secrets", Context.MODE_PRIVATE).also { it.edit().clear().commit() }
        store = RcloneRemoteStore(ctx, prefs, secrets)
        store.add(
            name = "nas",
            backend = "webdav",
            params = mapOf("url" to "https://nas.local/dav", "user" to "me"),
            secret = "old-pw",
            path = "backups",
        )
    }

    private fun secretOf(name: String): String? = secrets.getString(name, null)

    @Test
    fun `a blank password keeps the stored secret`() {
        var probed: String? = "unset"
        store.update(
            name = "nas",
            newParams = mapOf("url" to "https://nas2.local/dav", "user" to "me"),
            secret = SecretEdit.Keep,
            verify = { _, s -> probed = s },
        )
        assertEquals("the probe connects with the kept password", "old-pw", probed)
        assertEquals("old-pw", secretOf("nas"))
        assertEquals("https://nas2.local/dav", store.remote("nas")!!.params["url"])
        // An empty Replace (field typed then cleared) is the same as blank.
        store.update(name = "nas", secret = SecretEdit.Replace(""))
        assertEquals("old-pw", secretOf("nas"))
    }

    @Test
    fun `anonymous clears the secret and a new password replaces it`() {
        store.update(name = "nas", secret = SecretEdit.Replace("new-pw"))
        assertEquals("new-pw", secretOf("nas"))
        var probed: String? = "unset"
        store.update(name = "nas", secret = SecretEdit.Clear, verify = { _, s -> probed = s })
        assertNull(probed)
        assertNull(secretOf("nas"))
        assertFalse(store.hasSecret("nas"))
    }

    @Test
    fun `a failed connection test leaves params and secret unchanged`() {
        val before = store.remote("nas")
        try {
            store.update(
                name = "nas",
                newParams = mapOf("url" to "https://wrong.local/dav", "user" to "other"),
                secret = SecretEdit.Replace("wrong-pw"),
                allowInsecureTLS = true,
                verify = { _, _ -> throw IllegalStateException("401 Unauthorized") },
            )
            fail("the failed probe must propagate")
        } catch (e: IllegalStateException) {
            assertEquals("401 Unauthorized", e.message)
        }
        assertEquals(before, store.remote("nas"))
        assertEquals("old-pw", secretOf("nas"))
    }

    @Test
    fun `the probe sees the candidate, not the stored remote`() {
        store.update(
            name = "nas",
            newParams = mapOf("url" to " https://nas2.local/dav ", "user" to ""),
            allowInsecureTLS = true,
            verify = { c, _ ->
                assertEquals("https://nas2.local/dav", c.params["url"])
                assertFalse("a cleared optional field drops its key", c.params.containsKey("user"))
                assertTrue(c.allowInsecureTLS)
                // Nothing written yet while the probe runs.
                assertEquals("https://nas.local/dav", store.remote("nas")!!.params["url"])
            },
        )
        assertTrue(store.remote("nas")!!.allowInsecureTLS)
    }

    @Test
    fun `a rename onto an existing name fails and changes nothing`() {
        store.add("other", "smb", mapOf("host" to "h"), "pw2", "")
        val before = store.remotes
        for (bad in listOf("other", "has space", "dot.name", "")) {
            try {
                store.update(name = "nas", newName = bad)
                fail("rename to '$bad' must be refused")
            } catch (_: RcloneRemoteStore.StoreException) {
            }
        }
        assertEquals(before, store.remotes)
        assertEquals("old-pw", secretOf("nas"))
        assertEquals("pw2", secretOf("other"))
    }

    @Test
    fun `a rename moves the secret and keeps enabled and the rest`() {
        store.setEnabled("nas", false)
        val before = store.remote("nas")!!
        val renamed = store.update(name = "nas", newName = " home-nas ")
        assertEquals("home-nas", renamed.name)
        assertNull(store.remote("nas"))
        assertEquals(before.copy(name = "home-nas"), store.remote("home-nas"))
        assertFalse(store.remote("home-nas")!!.enabled)
        assertEquals("old-pw", secretOf("home-nas"))
        assertNull("no credential left under the old name", secretOf("nas"))
        // Position in the list is kept, so the destinations list doesn't reorder.
        assertEquals(0, store.remotes.indexOfFirst { it.name == "home-nas" })
    }

    @Test
    fun `a local folder can be renamed with spaces but keeps its tree uri`() {
        store.addLocalFolder("Documents", "content://tree/primary%3ADocuments", "Documents")
        val r = store.update(name = "Documents", newName = "Phone Documents")
        assertEquals("Phone Documents", r.name)
        assertEquals("content://tree/primary%3ADocuments", r.params[RcloneRemoteStore.PARAM_TREE_URI])
    }

    @Test
    fun `the folder is stored the way each backend expects`() {
        assertEquals("a/b", store.update(name = "nas", newPath = "/a/b/").path)
        store.add("box", "sftp", mapOf("host" to "h", "user" to "u"), "pw", "")
        assertEquals("/srv/backup", store.update(name = "box", newPath = "/srv/backup/").path)
        assertEquals("/", store.update(name = "box", newPath = "/").path)
    }

    @Test
    fun `secret resolution and param merge rules`() {
        assertEquals("a", RcloneRemoteStore.resolveSecret("a", SecretEdit.Keep))
        assertNull(RcloneRemoteStore.resolveSecret("a", SecretEdit.Clear))
        assertEquals("b", RcloneRemoteStore.resolveSecret("a", SecretEdit.Replace("b")))
        assertEquals("a", RcloneRemoteStore.resolveSecret("a", SecretEdit.Replace("")))
        assertEquals(
            mapOf("host" to "h2", "provider" to "Minio"),
            RcloneRemoteStore.mergedParams(
                mapOf("host" to "h", "user" to "u", "provider" to "Minio"),
                mapOf("host" to " h2", "user" to " "),
            ),
        )
    }

    @Test
    fun `an s3 secret is used verbatim and a password is obscured`() {
        val obscure: (String) -> String? = { "OBSCURED($it)" }
        val s3 = RcloneRemoteStore.buildConfigParams("s3", mapOf("endpoint" to "e"), "key", obscure)
        assertEquals("key", s3["secret_access_key"])
        val dav = RcloneRemoteStore.buildConfigParams("webdav", mapOf("url" to "u"), "pw", obscure)
        assertEquals("OBSCURED(pw)", dav["pass"])
        // The edit probe goes through the same builder as the live sync.
        val src = ProductionSources.read("backup/remote/RcloneRemoteStore.kt")
        val probe = src.substringAfter("fun probeConnection(").substringBefore("fun dropRcloneSection(")
        assertTrue(probe.contains("buildConfigParams("))
        assertTrue("probe section is dropped even on failure", probe.contains("} finally {"))
    }

    @Test
    fun `history records follow a rename`() {
        val history = BackupHistory.get(ctx)
        history.clear()
        history.upsert(
            BackupHistory.Record(
                backupId = "b1",
                startedAt = System.currentTimeMillis(),
                status = BackupHistory.Status.SUCCEEDED,
                destinations = listOf(
                    BackupHistory.DestinationOutcome("nas", succeeded = true),
                    BackupHistory.DestinationOutcome("other", succeeded = false),
                ),
            ),
        )
        history.renameDestination("nas", "home-nas")
        assertEquals(listOf("home-nas", "other"), history.records().single().destinations.map { it.name })
        history.clear()
    }
}
