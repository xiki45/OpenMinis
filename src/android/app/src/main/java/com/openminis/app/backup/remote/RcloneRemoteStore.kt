package com.openminis.app.backup.remote

import android.content.Context
import android.content.SharedPreferences
import com.openminis.app.logging.AppLogger
import com.openminis.app.util.EncryptedPrefsFactory
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * User-configured rclone remotes (SMB / WebDAV / S3 / …) usable as backup
 * destinations. Mirrors `src/ios/Agent/Backup/Remote/RcloneRemoteStore.swift`.
 *
 * ## Where the credentials live
 *
 * rclone's own config file stores passwords "obscured", which is reversible by
 * design (`rclone reveal` undoes it) — so it is NOT protection. Writing a
 * user's NAS password into a file in the app's data dir would be the same
 * mistake §5.4 calls out for backup packages.
 *
 * So the split matches iOS exactly:
 *   - **EncryptedSharedPreferences** (Keystore-backed) holds the secrets —
 *     Android's equivalent of the iOS Keychain.
 *   - **Plain SharedPreferences** holds everything non-secret: name, backend
 *     type, host, share, user, path.
 *   - rclone's in-memory config is populated per launch via `config/create`,
 *     with its config file pointed at a throwaway path so rclone itself
 *     persists nothing.
 *
 * That keeps exactly one copy of each secret, in the place the platform
 * provides for it, and means deleting a remote actually removes the credential.
 */
class RcloneRemoteStore internal constructor(
    private val context: Context,
    prefsOverride: SharedPreferences?,
    secretsOverride: SharedPreferences?,
) {

    constructor(context: Context) : this(context, null, null)

    /** A configured remote, minus its secret. */
    @Serializable
    data class Remote(
        /** rclone remote name — also the secret-store key. */
        val name: String,
        /** rclone backend type: "smb", "webdav", "s3", "sftp", … */
        val backend: String,
        /** Non-secret backend parameters (host, url, user, share, region…). */
        val params: Map<String, String> = emptyMap(),
        /** Directory inside the remote that backups are written to. */
        val path: String = "",
        val createdAt: Long = 0,
        /**
         * Whether new backups are delivered here.
         *
         * Disabling is NOT deleting: the server stays configured, with its
         * credential, so a user who wants to skip one destination for a while
         * doesn't have to re-enter an address and password to bring it back.
         * Defaults true so remotes stored before this field existed keep working.
         */
        val enabled: Boolean = true,
        /**
         * Accept a self-signed / untrusted TLS certificate for this server.
         *
         * Off by default: turning it on means an attacker who can intercept the
         * connection could impersonate the server, and a backup carries
         * everything the user has. It exists because a home NAS serving HTTPS
         * with its own certificate is completely ordinary, and without this the
         * only alternatives are "cannot use the app" or "downgrade to plain
         * HTTP", the second of which is strictly worse.
         *
         * The default also makes the decode tolerant: remotes stored before
         * this field existed have no key in their JSON and read back as false,
         * so adding it cannot make an existing destination vanish.
         */
        val allowInsecureTLS: Boolean = false,
    ) {
        /** `remote:` as rclone expects it. */
        val fsSpec: String get() = "$name:"

        /**
         * Join [child] under this remote's backup directory. Mirrors
         * `Remote.join` on iOS.
         *
         * Naive `"$path/$child"` breaks when the user picked the SERVER ROOT as
         * the destination: [path] is "" and the join yields "/child" — a
         * LEADING-SLASH path that rclone's WebDAV backend resolves against the
         * server root, ESCAPING the folder baked into the fs URL. Trimming
         * slashes on both sides keeps every produced path fs-relative.
         *
         * SFTP keeps its leading slash: there, `/srv/backup` and `srv/backup`
         * are DIFFERENT locations (absolute vs relative to the login user's
         * home), so trimming it rewrites the destination the user chose.
         */
        fun join(child: String): String {
            if (RcloneBackendCatalog.usesAbsolutePaths(backend)) {
                // `/` is the filesystem ROOT, not an empty path: dropping its
                // trailing slash would leave "" and silently retarget the
                // package at the login user's home.
                if (path == "/") return "/$child"
                val base = path.removeSuffix("/")
                return if (base.isEmpty()) child else "$base/$child"
            }
            val base = path.trim('/')
            return if (base.isEmpty()) child else "$base/$child"
        }

        /** This remote's backup directory as an fs-relative listing target. */
        val listRoot: String
            get() = if (RcloneBackendCatalog.usesAbsolutePaths(backend)) {
                if (path == "/") "/" else path.removeSuffix("/")
            } else {
                path.trim('/')
            }
    }

    class StoreException(message: String) : Exception(message)

    // Overridable so unit tests can run the edit rules against plain prefs:
    // the Keystore behind the encrypted store does not exist under Robolectric.
    private val prefs by lazy {
        prefsOverride ?: context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
    private val secrets by lazy {
        secretsOverride ?: EncryptedPrefsFactory.safeCreate(context, SECRETS_PREFS_NAME)
    }

    var remotes: List<Remote>
        get() = runCatching {
            prefs.getString(KEY_REMOTES, null)?.let { JSON.decodeFromString(REMOTE_LIST, it) }
        }.getOrNull() ?: emptyList()
        private set(value) {
            prefs.edit().putString(KEY_REMOTES, JSON.encodeToString(REMOTE_LIST, value)).apply()
        }

    fun remote(name: String): Remote? = remotes.firstOrNull { it.name == name }

    /** Remotes that new backups should actually be delivered to. */
    val enabledRemotes: List<Remote> get() = remotes.filter { it.enabled }

    // MARK: - Local folder destinations

    /**
     * [T-android-backup-local-folder] Register a SAF-picked folder on this
     * device as a backup destination.
     *
     * Local folders deliberately reuse the SAME [Remote] record and the same
     * saved list as rclone servers, rather than getting a parallel store. The
     * destination list, its enable/disable switch, the "is anything configured"
     * gate on the Start button and the history's per-destination outcomes all
     * key off that one list — a second list would have had to be threaded
     * through every one of them, and any spot that was missed would silently
     * treat a local folder as "no destination configured".
     *
     * The tree URI lives in [Remote.path] because that is the field every
     * caller already treats as "where in the destination the package goes".
     * [BACKEND_LOCAL] is not an rclone backend and is filtered out of
     * [syncToRclone]; delivery for it is a plain DocumentFile write.
     */
    fun addLocalFolder(name: String, treeUri: String, displayPath: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) throw StoreException("Choose a name for this folder.")
        if (remote(trimmed) != null) {
            throw StoreException("A destination named \"$trimmed\" already exists.")
        }
        if (remotes.any { it.backend == BACKEND_LOCAL && it.params[PARAM_TREE_URI] == treeUri }) {
            throw StoreException("That folder is already a backup destination.")
        }
        remotes = remotes + Remote(
            name = trimmed,
            backend = BACKEND_LOCAL,
            params = mapOf(PARAM_TREE_URI to treeUri),
            // Shown in the row subtitle. The raw tree URI is unreadable
            // ("content://com.android.externalstorage.documents/tree/primary%3ADocuments"),
            // so the human form is stored alongside it rather than re-derived
            // for display on every recomposition.
            path = displayPath,
            createdAt = System.currentTimeMillis(),
        )
        AppLogger.info(TAG, "[Rclone] local folder destination added: $trimmed -> $displayPath")
    }

    // MARK: - Registration

    /** Add a remote. [secret] goes to the encrypted store; everything else to prefs. */
    fun add(
        name: String,
        backend: String,
        params: Map<String, String>,
        secret: String?,
        path: String,
        allowInsecureTLS: Boolean = false,
    ) {
        val trimmed = name.trim()
        if (trimmed.isEmpty() || !trimmed.all { it.isLetterOrDigit() || it == '-' || it == '_' }) {
            throw StoreException("Choose a name using letters, numbers, - or _.")
        }
        if (remote(trimmed) != null) {
            throw StoreException("A destination named \"$trimmed\" already exists.")
        }
        if (!secret.isNullOrEmpty()) storeSecret(trimmed, secret)
        remotes = remotes + Remote(
            name = trimmed, backend = backend, params = params,
            path = path, createdAt = System.currentTimeMillis(),
            allowInsecureTLS = allowInsecureTLS,
        )
        AppLogger.info(TAG, "[Rclone] remote added: $trimmed ($backend)")
    }

    /** Remove a remote and its credential. */
    fun remove(name: String) {
        remotes = remotes.filterNot { it.name == name }
        secrets.edit().remove(name).apply()
        AppLogger.info(TAG, "[Rclone] remote removed: $name")
    }

    /** Flip a remote on or off without touching its config or credential. */
    fun setEnabled(name: String, on: Boolean) {
        val all = remotes
        if (all.none { it.name == name }) return
        remotes = all.map { if (it.name == name) it.copy(enabled = on) else it }
        AppLogger.info(TAG, "[Rclone] remote '$name' ${if (on) "enabled" else "disabled"}")
    }

    // MARK: - Editing

    /** What an edit does to the stored secret. */
    sealed interface SecretEdit {
        /** Leave the stored secret as it is — the password field was left blank. */
        data object Keep : SecretEdit
        /** Remove it: the server is switched to anonymous access. */
        data object Clear : SecretEdit
        data class Replace(val value: String) : SecretEdit
    }

    /**
     * [T-android-backup-dest-edit] Change an existing destination in place.
     * Mirrors `RcloneRemoteStore.update` on iOS: rename, re-point the folder,
     * and edit the connection (params, secret, TLS opt-in). Every argument is
     * optional; null means "unchanged".
     *
     * ## Verify before anything is written
     *
     * [verify] receives the edited remote and the secret it would use, and is
     * expected to throw when that configuration cannot connect. It runs BEFORE
     * a single byte is persisted, so a failed test leaves the old params and
     * the old secret exactly as they were. iOS applies first and rolls the
     * params back on failure, which cannot restore the secret (it is not read
     * back) — a wrong new password replaced a working one. Testing the
     * candidate first removes the need for a rollback at all.
     *
     * ## Rename
     *
     * The secret is filed under the remote's name, so a rename moves it — new
     * key written before the old one is dropped, so a failure in between leaves
     * a duplicate credential rather than a remote with none. `enabled`,
     * `createdAt` and everything else ride along unchanged on the copied record.
     *
     * The caller syncs rclone afterwards ([syncToRclone]); the changed inputs
     * give the remote a new fingerprint, so its section is recreated.
     *
     * @return the remote as stored after the edit.
     * @throws StoreException for an unknown remote, an invalid or taken name.
     */
    fun update(
        name: String,
        newName: String? = null,
        newPath: String? = null,
        newParams: Map<String, String>? = null,
        secret: SecretEdit = SecretEdit.Keep,
        allowInsecureTLS: Boolean? = null,
        verify: ((candidate: Remote, secret: String?) -> Unit)? = null,
    ): Remote {
        val all = remotes
        val i = all.indexOfFirst { it.name == name }
        if (i < 0) throw StoreException("That destination no longer exists.")
        val before = all[i]

        val targetName = newName?.trim()?.takeIf { it != name }
        if (targetName != null) {
            validateRename(targetName, local = isLocalFolder(before.backend))
            if (all.any { it.name == targetName }) {
                throw StoreException("A destination named \"$targetName\" already exists.")
            }
        }

        var updated = before
        if (targetName != null) updated = updated.copy(name = targetName)
        if (newPath != null) updated = updated.copy(path = normalizedPath(before.backend, newPath))
        if (newParams != null) updated = updated.copy(params = mergedParams(before.params, newParams))
        if (allowInsecureTLS != null) updated = updated.copy(allowInsecureTLS = allowInsecureTLS)

        val currentSecret = loadSecret(name)
        val nextSecret = resolveSecret(currentSecret, secret)
        verify?.invoke(updated, nextSecret)

        // Verified (or nothing to verify): commit. Secret first, then the
        // record, so the record never names a credential that isn't there yet.
        val edit = secrets.edit()
        if (targetName != null) edit.remove(name)
        if (nextSecret.isNullOrEmpty()) edit.remove(updated.name) else edit.putString(updated.name, nextSecret)
        edit.apply()
        remotes = all.toMutableList().also { it[i] = updated }
        if (targetName != null) synchronized(syncLock) { syncedFingerprints.remove(name) }
        AppLogger.info(TAG, "[Rclone] remote updated: $name -> ${updated.name}:/${updated.path}")
        return updated
    }

    private fun validateRename(newName: String, local: Boolean) {
        if (newName.isEmpty()) throw StoreException("Choose a name for this destination.")
        // A local folder's name never reaches rclone, and the add path derives
        // it from the folder (spaces included), so only servers are held to
        // rclone's remote-name alphabet.
        if (!local && !newName.all { it.isLetterOrDigit() || it == '-' || it == '_' }) {
            throw StoreException("Choose a name using letters, numbers, - or _.")
        }
    }

    /** Whether a secret is stored for [name]. Never returns the secret itself. */
    fun hasSecret(name: String): Boolean = !loadSecret(name).isNullOrEmpty()

    // MARK: - Secrets

    private fun storeSecret(name: String, secret: String) {
        secrets.edit().putString(name, secret).apply()
    }

    private fun loadSecret(name: String): String? = secrets.getString(name, null)

    /**
     * Which parameter carries the secret, per backend. rclone names these
     * differently and there is no generic "password" key, so the mapping is
     * explicit rather than guessed.
     */
    private fun secretKey(backend: String): String = secretKeyFor(backend)

    // MARK: - Handing config to rclone

    /**
     * Push every configured remote into rclone's in-memory config.
     *
     * Runs per launch, off the main thread. rclone is told to use a config path
     * under cacheDir so it never writes credentials to a file we would then
     * have to protect — the encrypted store stays the only copy.
     *
     * [T-restore-list-sync-race] Every server screen calls this right before
     * it lists, deletes or downloads, and two of them can overlap (a quick
     * second folder tap, a backup run while browsing). rclone's config/create
     * is not atomic per remote: it deletes the section, then sets `type`, then
     * each parameter in turn (fs/config/config.go CreateRemote). A listing that
     * lands in that window sees the remote without its password, and SMB
     * answers "The attempted logon is invalid" for correct credentials - the
     * same race iOS fixed in f465c10b1. So the whole sync runs under one
     * process-wide lock, and a remote whose inputs are unchanged since its
     * last successful create is not recreated at all: once synced, repeated
     * calls never touch a section another thread may be using.
     */
    fun syncToRclone() = synchronized(syncLock) {
        val configPath = File(context.cacheDir, "rclone-ephemeral.conf").absolutePath
        runCatching { RcloneBridge.rpc("config/setpath", mapOf("path" to configPath)) }

        // Global TLS relaxation is all-or-nothing (WebDAV and S3 expose no
        // per-remote option), so it goes on only when a configured server
        // actually asks for it — one NAS with a self-signed certificate must
        // not quietly lower the bar for every other destination the user has.
        RcloneBridge.setInsecureTLS(remotes.any { it.allowInsecureTLS })

        // A local-folder destination has no rclone backend to create — feeding
        // `type = "local-folder"` to config/create would make rclone reject it
        // and log a failure for a destination that is working fine.
        for (r in remotes.filterNot { it.backend == BACKEND_LOCAL }) {
            val params = buildConfigParams(
                backend = r.backend,
                params = r.params,
                secret = loadSecret(r.name),
                allowInsecureTLS = r.allowInsecureTLS,
                obscure = ::obscureViaRclone,
            ).toMutableMap<String, Any?>()
            val fp = configFingerprint(r.backend, r.params, loadSecret(r.name), r.allowInsecureTLS)
            if (syncedFingerprints[r.name] == fp) continue
            syncedFingerprints.remove(r.name)
            runCatching {
                RcloneBridge.rpc(
                    "config/create",
                    mapOf(
                        "name" to r.name,
                        "type" to r.backend,
                        "parameters" to params,
                        // Don't let rclone try to run an interactive OAuth flow.
                        "opt" to mapOf("nonInteractive" to true),
                    ),
                )
            }.onSuccess {
                syncedFingerprints[r.name] = fp
            }.onFailure {
                AppLogger.error(TAG, "[Rclone] config/create failed for '${r.name}': ${it.message}")
            }
        }
        AppLogger.info(TAG, "[Rclone] synced ${remotes.size} remote(s) into rclone config")
    }

    /**
     * [T-android-backup-dest-edit] Prove [candidate] connects, without touching
     * the remote's live rclone section or anything stored. Throws the rclone
     * error when it does not.
     *
     * The candidate is registered under a throwaway section name, listed, and
     * the section deleted again. The name contains a `.`, which [add] and
     * [update] never accept, so it cannot collide with a real destination.
     * Recreating the REAL section instead would have to be undone on failure,
     * and a backup running at the same moment would upload with the untested
     * settings.
     *
     * The global TLS relaxation is widened only for the duration of the probe
     * when the candidate asks for it, then put back to what the stored remotes
     * need — a failed "trust this certificate" attempt must not leave
     * verification off for every other server.
     */
    fun probeConnection(candidate: Remote, secret: String?) {
        val probe = candidate.copy(name = "${candidate.name}$PROBE_SUFFIX")
        try {
            RcloneBridge.setInsecureTLS(candidate.allowInsecureTLS || remotes.any { it.allowInsecureTLS })
            RcloneBridge.rpc(
                "config/create",
                mapOf(
                    "name" to probe.name,
                    "type" to probe.backend,
                    "parameters" to buildConfigParams(
                        backend = probe.backend,
                        params = probe.params,
                        secret = secret,
                        obscure = ::obscureViaRclone,
                        allowInsecureTLS = probe.allowInsecureTLS,
                    ),
                    "opt" to mapOf("nonInteractive" to true),
                ),
            )
            RcloneBrowser.listDirectories(probe, probe.listRoot)
        } finally {
            dropRcloneSection(probe.name)
            RcloneBridge.setInsecureTLS(remotes.any { it.allowInsecureTLS })
        }
    }

    /**
     * Delete one section from rclone's in-memory config. Used for the probe and
     * for the old name after a rename, which would otherwise linger with the
     * credential until the process exits. Best effort: a section that is
     * already gone is the desired end state.
     */
    fun dropRcloneSection(name: String) {
        runCatching { RcloneBridge.rpc("config/delete", mapOf("name" to name)) }
        synchronized(syncLock) { syncedFingerprints.remove(name) }
    }

    /** `core/obscure`, or null when the RPC is unavailable/fails. */
    private fun obscureViaRclone(clear: String): String? = runCatching {
        RcloneBridge.rpc("core/obscure", mapOf("clear" to clear))
            .optString("obscured").takeIf { it.isNotEmpty() }
    }.getOrNull()

    companion object {
        private const val TAG = "Rclone"

        /** [T-restore-list-sync-race] One rclone config writer at a time, process-wide. */
        private val syncLock = Any()

        /**
         * [T-restore-list-sync-race] Remote name -> [configFingerprint] of the
         * inputs of its last successful config/create in this process. rclone's
         * config lives in this process too, so a new process starts empty and
         * creates everything once.
         */
        private val syncedFingerprints = HashMap<String, String>()

        /**
         * Identity of everything a remote's rclone section is built from, taken
         * from the CLEAR inputs: the obscured password differs on every call
         * (random IV), so it cannot tell whether anything changed.
         */
        internal fun configFingerprint(
            backend: String,
            params: Map<String, String>,
            secret: String?,
            allowInsecureTLS: Boolean,
        ): String {
            val canonical = buildString {
                append(backend).append('\u0000')
                for ((k, v) in params.toSortedMap()) append(k).append('=').append(v).append('\u0000')
                append(if (secret == null) "-" else "+" + secret).append('\u0000')
                append(allowInsecureTLS)
            }
            val digest = java.security.MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { "%02x".format(it) }
        }

        /**
         * [T-android-backup-local-folder] Reserved [Remote.backend] marking a
         * destination that is a folder on this device, reached through SAF
         * rather than rclone. Hyphenated so it can never collide with a real
         * rclone backend name (those are bare lowercase words like "smb").
         */
        const val BACKEND_LOCAL = "local-folder"

        /** Key under [Remote.params] holding the persisted SAF tree URI. */
        const val PARAM_TREE_URI = "treeUri"

        /** Whether [backend] names a local folder rather than an rclone remote. */
        fun isLocalFolder(backend: String): Boolean = backend == BACKEND_LOCAL

        /**
         * [T-android-backup-dest-edit] Merge an edit form's values into the
         * stored params. Merge rather than replace: the form sends only the
         * fields it shows, and a remote may carry keys with no editor. A field
         * cleared in the form removes its key, so an optional value can be
         * taken away. Values are trimmed — a pasted host with a trailing space
         * is the classic "correct address, cannot connect".
         */
        fun mergedParams(old: Map<String, String>, edits: Map<String, String>): Map<String, String> {
            val out = old.toMutableMap()
            for ((k, v) in edits) {
                val t = v.trim()
                if (t.isEmpty()) out.remove(k) else out[k] = t
            }
            return out
        }

        /** The secret an edit leaves in place: kept, cleared or replaced. */
        fun resolveSecret(current: String?, edit: SecretEdit): String? = when (edit) {
            SecretEdit.Keep -> current
            SecretEdit.Clear -> null
            is SecretEdit.Replace -> edit.value.ifEmpty { current }
        }

        /**
         * Store a re-pointed folder the way the browser produces it. SFTP keeps
         * a leading `/` (absolute vs home-relative are different places, see
         * [Remote.join]); every other backend stores it fs-relative.
         */
        fun normalizedPath(backend: String, path: String): String {
            if (isLocalFolder(backend)) return path
            val trimmed = path.trim()
            if (RcloneBackendCatalog.usesAbsolutePaths(backend)) {
                return if (trimmed.length > 1 && trimmed.endsWith("/")) trimmed.dropLast(1) else trimmed
            }
            return trimmed.trim('/')
        }
        /** See [probeConnection]: `.` is outside the destination-name alphabet. */
        internal const val PROBE_SUFFIX = ".edit-probe"
        private const val PREFS_NAME = "backup_rclone_remotes"
        private const val SECRETS_PREFS_NAME = "backup_rclone_secrets"
        private const val KEY_REMOTES = "remotes"

        /**
         * Which parameter carries the secret, per backend. rclone names these
         * differently and there is no generic "password" key, so the mapping
         * is explicit rather than guessed.
         */
        fun secretKeyFor(backend: String): String = when (backend) {
            "s3" -> "secret_access_key"
            else -> "pass"
        }

        /**
         * Whether this backend's secret is a PASSWORD rclone will de-obscure,
         * as opposed to a key it uses literally.
         *
         * Obscuring is rclone's expected on-the-wire form for password fields
         * and it reveals them again on use — but `secret_access_key` is
         * consumed verbatim. Obscuring it makes the client sign every S3
         * request with the obscured blob, and all of them fail with
         * SignatureDoesNotMatch (iOS 21f08d85f, found against a real MinIO).
         */
        fun secretNeedsObscuring(backend: String): Boolean = backend != "s3"

        /**
         * Build the `config/create` parameters for one remote.
         *
         * Pure so the rules below can be tested without an rclone bridge:
         * [obscure] is the `core/obscure` call, returning null when it fails.
         *
         * This is the ONLY place that decides obscure-vs-verbatim. The
         * add-time connection test builds its config from the plaintext the
         * user just typed, so when the two disagree an S3 destination tests
         * fine at add time and fails on every later list/upload — exactly the
         * bug iOS hit. Both callers go through here.
         */
        fun buildConfigParams(
            backend: String,
            params: Map<String, String>,
            secret: String?,
            obscure: (String) -> String?,
            allowInsecureTLS: Boolean = false,
        ): Map<String, Any?> {
            val out = params.toMutableMap<String, Any?>()
            out["type"] = backend

            // FTP is the one backend here with a real per-remote switch, so use
            // it rather than relying only on the global InsecureSkipVerify —
            // this keeps FTP correct even when the global flag is off because
            // no other destination asked for it.
            if (backend == "ftp" && allowInsecureTLS) out["no_check_certificate"] = "true"

            // SMB is configured WITHOUT a share, so the share becomes the
            // first level of the folder browser — what Finder and Windows
            // Explorer do when they enumerate shares after connecting. A
            // `share` stored by an older build is DROPPED rather than
            // migrated: a share-less fs reaches every share the account can
            // see, so the stored path still resolves through the browser,
            // while keeping the key would pin that remote to the one share it
            // was created with.
            if (backend == "smb") out.remove("share")

            // Blank credentials mean GUEST, and that has to be said
            // explicitly: rclone's smb `user` option defaults to the OS login
            // name when the key is absent, so an omitted username would
            // silently try to authenticate as some unrelated account instead
            // of connecting as a guest.
            if (backend == "smb" && (params["user"] ?: "").isBlank()) out["user"] = ""

            val hasSecret = !secret.isNullOrEmpty()

            // Anonymous access needs an OBSCURED EMPTY password, not a missing
            // one. rclone marks `pass` as IsPassword and de-obscures whatever
            // it finds — including the option's own "" default — so a bare ""
            // or an absent key fails with "decrypt password: input too short"
            // before any connection is attempted.
            //
            // Measured against rclone v1.73.3, this bites per backend: FTP
            // fails outright on a bare "" (and reaches the server's own
            // "Login incorrect" once obscured), while WebDAV tolerates it and
            // gets as far as a 401. `obscure("")` is the single form that
            // works for BOTH, so it is applied uniformly rather than
            // per-backend — one rule, no backend-specific exceptions to keep
            // in sync as rclone's option defaults change.
            //
            // S3 is excluded: its secret is consumed verbatim, so an anonymous
            // bucket wants NO key rather than an obscured blank.
            if (backend != "s3" && !hasSecret && (out["pass"] as? String).isNullOrEmpty()) {
                obscure("")?.let { out["pass"] = it }
                return out
            }

            // An empty stored secret is never written as a parameter.
            if (!hasSecret) {
                out.remove(secretKeyFor(backend))
                return out
            }

            out[secretKeyFor(backend)] =
                if (secretNeedsObscuring(backend)) obscure(secret!!) ?: secret else secret
            return out
        }
        /**
         * Whether a failed connection looks like a TLS trust problem.
         *
         * Used to decide whether to OFFER the self-signed opt-in, so it errs
         * toward not offering: matching too eagerly would put a "don't verify
         * certificates" control in front of users whose real problem is a wrong
         * password. The strings are what Go's crypto/tls and x509 actually
         * surface through rclone — rclone passes the underlying error text
         * through rather than normalising it, so there is no error code to key
         * off instead.
         *
         * Pure, so the matching can be tested without a server.
         */
        fun looksLikeCertificateFailure(message: String?): Boolean {
            val m = message?.lowercase() ?: return false
            return m.contains("x509") ||
                m.contains("certificate") ||
                m.contains("tls: failed to verify") ||
                m.contains("unknown authority") ||
                m.contains("self-signed") ||
                m.contains("self signed")
        }

        private val JSON = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        private val REMOTE_LIST = ListSerializer(Remote.serializer())
    }
}
