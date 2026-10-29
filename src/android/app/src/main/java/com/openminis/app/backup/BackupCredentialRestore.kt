package com.openminis.app.backup

/**
 * [T-backup-restore-semantics / B01] Which credentials a restore writes over
 * the ones already on this device. Owner decision 2026-10-15, the same on iOS:
 *
 *  - API-key class ([Kind.API_KEY]: `apiKey`, and `manualOAuthToken` - a
 *    pasted bearer that never refreshes, so it has no "expired" state of its
 *    own): the backup's value replaces the device's.
 *  - OAuth class ([Kind.OAUTH]: `oauthToken` and the sign-in's `oauthEmail` /
 *    `oauthGcpProject`): the device's is kept unless the device's sign-in is
 *    already marked as needing a new login. That mark is the app's existing
 *    one ([com.openminis.app.auth.OAuthManager.needsReauth]); it is set only
 *    when the token endpoint REJECTED a refresh, so an unmarked sign-in is not
 *    proven valid - a network failure never marks it.
 *  - Nothing on the device: the backup's value is written (as before).
 *  - [T-backup-oauth-login-bundle] The three OAuth slots are ONE login, as on
 *    iOS (BackupCredentialPolicy.plan, replaceLogin): it is replaced when the
 *    device has no OAuth token or its sign-in needs a new login. Decided per
 *    slot, a signed-out device (logout drops the token but leaves the email
 *    and GCP project) took the backup's token next to its own stale email and
 *    project - Gemini Code Assist then called with one account's token and
 *    another's project.
 *
 * Returns whether anything was written; an identical value counts as kept.
 */
object BackupCredentialRestore {
    enum class Kind { API_KEY, OAUTH }

    enum class Slot(val kind: Kind) {
        API_KEY(Kind.API_KEY),
        MANUAL_OAUTH_TOKEN(Kind.API_KEY),
        OAUTH_TOKEN(Kind.OAUTH),
        OAUTH_EMAIL(Kind.OAUTH),
        OAUTH_GCP_PROJECT(Kind.OAUTH),
    }

    interface Store {
        fun supports(slot: Slot): Boolean
        fun read(slot: Slot): String?
        fun write(slot: Slot, value: String)
        fun oauthNeedsReauth(): Boolean
    }

    fun shouldWrite(slot: Slot, local: String?, backup: String, replaceLogin: Boolean): Boolean {
        if (local.isNullOrEmpty()) return true
        if (local == backup) return false
        return when (slot.kind) {
            Kind.API_KEY -> true
            Kind.OAUTH -> replaceLogin
        }
    }

    fun restore(backup: Map<Slot, String>, store: Store): Boolean {
        // Read the mark ONCE, before anything is written: a new token bundle
        // lapses it, and a written manual bearer suppresses it, either of which
        // would make the rest of an expired sign-in look valid.
        val hasOAuth = backup.keys.any { it.kind == Kind.OAUTH }
        val needsReauth = hasOAuth && store.oauthNeedsReauth()
        // [T-backup-oauth-login-bundle] Also read before any write.
        val replaceLogin = hasOAuth && (needsReauth || store.read(Slot.OAUTH_TOKEN).isNullOrEmpty())
        var wrote = false
        for (slot in Slot.entries) {
            val value = backup[slot] ?: continue
            if (!store.supports(slot)) continue
            if (!shouldWrite(slot, store.read(slot), value, slot.kind == Kind.OAUTH && replaceLogin)) continue
            store.write(slot, value)
            wrote = true
        }
        return wrote
    }
}
