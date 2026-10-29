package com.openminis.app.backup

import com.openminis.app.ProductionSources
import com.openminis.app.backup.remote.RcloneRemoteStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-restore-list-sync-race] Overlapping server screens must not recreate a
 * remote's rclone section while another one is listing it (iOS f465c10b1:
 * "The attempted logon is invalid" for correct SMB credentials).
 */
class RcloneConfigSyncRaceTest {

    private fun fp(
        backend: String = "smb",
        params: Map<String, String> = mapOf("host" to "nas", "user" to "me"),
        secret: String? = "pw",
        tls: Boolean = false,
    ) = RcloneRemoteStore.configFingerprint(backend, params, secret, tls)

    @Test
    fun `the fingerprint is stable for the same inputs, in any parameter order`() {
        assertEquals(fp(), fp(params = linkedMapOf("user" to "me", "host" to "nas")))
    }

    @Test
    fun `any input that changes the rclone section changes the fingerprint`() {
        val base = fp()
        assertNotEquals(base, fp(secret = "pw2"))
        assertNotEquals(base, fp(secret = null))
        assertNotEquals(base, fp(secret = ""))
        assertNotEquals(base, fp(params = mapOf("host" to "nas2", "user" to "me")))
        assertNotEquals(base, fp(backend = "webdav"))
        assertNotEquals(base, fp(tls = true))
        // Separators keep "a=b" + "c" apart from "a=bc".
        assertNotEquals(fp(params = mapOf("a" to "b", "c" to "")), fp(params = mapOf("a" to "bc")))
    }

    @Test
    fun `the sync is serialised and skips remotes that are already synced`() {
        val src = ProductionSources.read("backup/remote/RcloneRemoteStore.kt")
        assertTrue(src.contains("fun syncToRclone() = synchronized(syncLock) {"))
        val body = src.substringAfter("fun syncToRclone()").substringBefore("private fun obscureViaRclone")
        assertTrue(body.contains("if (syncedFingerprints[r.name] == fp) continue"))
        assertTrue("recorded only after a successful create",
            body.indexOf("syncedFingerprints[r.name] = fp") > body.indexOf("\"config/create\""))
        assertTrue(body.contains(".onSuccess {"))
    }
}
