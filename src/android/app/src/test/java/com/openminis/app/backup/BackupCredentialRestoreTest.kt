package com.openminis.app.backup

import com.openminis.app.ProductionSources
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [T-backup-restore-semantics / B01] Spec row (h): which credentials a restore
 * writes over the device's, from the `credentials` cases of
 * `src/shared/backup-restore/b01_restore_scenarios.json`. Restoring used to
 * keep every credential already present.
 */
class BackupCredentialRestoreTest {

    private val doc: JSONObject by lazy {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "src/shared/backup-restore").isDirectory) dir = dir.parentFile
        val f = File(dir ?: error("src/shared/backup-restore not found"), "src/shared/backup-restore/b01_restore_scenarios.json")
        JSONObject(f.readText())
    }

    // ── (h) credentials ──────────────────────────────────────────────────

    private class FakeStore(
        private val values: MutableMap<BackupCredentialRestore.Slot, String>,
        private val needsReauth: Boolean,
    ) : BackupCredentialRestore.Store {
        override fun supports(slot: BackupCredentialRestore.Slot) = true
        override fun read(slot: BackupCredentialRestore.Slot) = values[slot]
        override fun write(slot: BackupCredentialRestore.Slot, value: String) {
            values[slot] = value
        }
        override fun oauthNeedsReauth() = needsReauth
        fun value(slot: BackupCredentialRestore.Slot) = values[slot]
    }

    private fun slot(wire: String) = when (wire) {
        "apiKey" -> BackupCredentialRestore.Slot.API_KEY
        "manualOAuthToken" -> BackupCredentialRestore.Slot.MANUAL_OAUTH_TOKEN
        "oauthToken" -> BackupCredentialRestore.Slot.OAUTH_TOKEN
        "oauthEmail" -> BackupCredentialRestore.Slot.OAUTH_EMAIL
        "oauthGcpProject" -> BackupCredentialRestore.Slot.OAUTH_GCP_PROJECT
        else -> error("unknown slot $wire")
    }

    @Test
    fun `h - credentials follow the API-key and OAuth rules`() {
        val cases = doc.getJSONObject("credentials").getJSONArray("cases")
        val failures = mutableListOf<String>()
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val s = slot(c.getString("slot"))
            val values = mutableMapOf<BackupCredentialRestore.Slot, String>()
            // An email / GCP project row describes the device's sign-in, so the
            // device has its token (iOS's harness passes it too): the three are
            // one login ([T-backup-oauth-login-bundle]).
            if (s == BackupCredentialRestore.Slot.OAUTH_EMAIL || s == BackupCredentialRestore.Slot.OAUTH_GCP_PROJECT) {
                values[BackupCredentialRestore.Slot.OAUTH_TOKEN] = "{\"access_token\":\"local\"}"
            }
            if (!c.isNull("local")) values[s] = c.getString("local")
            val store = FakeStore(values, c.getBoolean("needsReauth"))
            val wrote = BackupCredentialRestore.restore(mapOf(s to c.getString("backup")), store)
            if (store.value(s) != c.getString("result") || wrote != c.getBoolean("wrote")) {
                failures += "${c.getString("name")}: value=${store.value(s)} wrote=$wrote"
            }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun `h - needsReauth is read once, before the token write lapses it`() {
        var reads = 0
        val values = mutableMapOf(
            BackupCredentialRestore.Slot.OAUTH_TOKEN to "local",
            BackupCredentialRestore.Slot.OAUTH_EMAIL to "me@local",
        )
        var marked = true
        val store = object : BackupCredentialRestore.Store {
            override fun supports(slot: BackupCredentialRestore.Slot) = true
            override fun read(slot: BackupCredentialRestore.Slot) = values[slot]
            override fun write(slot: BackupCredentialRestore.Slot, value: String) {
                values[slot] = value
                if (slot == BackupCredentialRestore.Slot.OAUTH_TOKEN) marked = false
            }
            override fun oauthNeedsReauth(): Boolean { reads++; return marked }
        }
        BackupCredentialRestore.restore(
            linkedMapOf(
                BackupCredentialRestore.Slot.OAUTH_TOKEN to "backup",
                BackupCredentialRestore.Slot.OAUTH_EMAIL to "me@backup",
            ),
            store,
        )
        assertEquals("backup", values[BackupCredentialRestore.Slot.OAUTH_TOKEN])
        assertEquals("me@backup", values[BackupCredentialRestore.Slot.OAUTH_EMAIL])
        assertEquals(1, reads)
    }

    @Test
    fun `h - a signed-out device takes the backup's whole login, not just its token`() {
        // [T-backup-oauth-login-bundle] Logout drops the token but leaves the
        // email and GCP project of the old account behind.
        val values = mutableMapOf(
            BackupCredentialRestore.Slot.OAUTH_EMAIL to "a@old",
            BackupCredentialRestore.Slot.OAUTH_GCP_PROJECT to "project-a",
        )
        val wrote = BackupCredentialRestore.restore(
            mapOf(
                BackupCredentialRestore.Slot.OAUTH_TOKEN to "{\"access_token\":\"b\"}",
                BackupCredentialRestore.Slot.OAUTH_EMAIL to "b@backup",
                BackupCredentialRestore.Slot.OAUTH_GCP_PROJECT to "project-b",
            ),
            FakeStore(values, needsReauth = false),
        )
        assertTrue(wrote)
        assertEquals("{\"access_token\":\"b\"}", values[BackupCredentialRestore.Slot.OAUTH_TOKEN])
        assertEquals("b@backup", values[BackupCredentialRestore.Slot.OAUTH_EMAIL])
        assertEquals("project-b", values[BackupCredentialRestore.Slot.OAUTH_GCP_PROJECT])
    }

    @Test
    fun `h - a valid sign-in keeps its email and project, filling only what is missing`() {
        val values = mutableMapOf(
            BackupCredentialRestore.Slot.OAUTH_TOKEN to "{\"access_token\":\"a\"}",
            BackupCredentialRestore.Slot.OAUTH_EMAIL to "a@device",
        )
        BackupCredentialRestore.restore(
            mapOf(
                BackupCredentialRestore.Slot.OAUTH_TOKEN to "{\"access_token\":\"b\"}",
                BackupCredentialRestore.Slot.OAUTH_EMAIL to "b@backup",
                BackupCredentialRestore.Slot.OAUTH_GCP_PROJECT to "project-b",
            ),
            FakeStore(values, needsReauth = false),
        )
        assertEquals("{\"access_token\":\"a\"}", values[BackupCredentialRestore.Slot.OAUTH_TOKEN])
        assertEquals("a@device", values[BackupCredentialRestore.Slot.OAUTH_EMAIL])
        assertEquals("missing on the device, as iOS", "project-b", values[BackupCredentialRestore.Slot.OAUTH_GCP_PROJECT])
    }

    @Test
    fun `h - the repository restores through the policy and asks the existing reauth mark`() {
        val repo = ProductionSources.read("data/repository/ProviderRepository.kt")
        val body = repo.substringAfter("fun restoreBackupProviderSecret(").substringBefore("\n    fun ")
        assertTrue(body.contains("BackupCredentialRestore.restore("))
        assertTrue(body.contains("OAuthManager.needsReauth(context, instance.id)"))
    }
}
