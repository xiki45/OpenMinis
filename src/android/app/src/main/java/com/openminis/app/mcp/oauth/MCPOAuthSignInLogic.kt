// ios-source: src/ios/Agent/Session/MCPOAuthDiscovery.swift @ 678fee08a (MCPOAuthSignInLogic)
package com.openminis.app.mcp.oauth

import org.json.JSONObject

/**
 * [T-mcp-oauth-signin-android] The decisions of the browser sign-in that need
 * no UI, network or storage, so they are unit-tested on their own. Port of iOS
 * `MCPOAuthSignInLogic` (GitHub #432: one sign-in action, a registration cache
 * that knows when it is stale, and failures that are named).
 */
object MCPOAuthSignInLogic {

    // -- Registered-client cache --

    /**
     * One dynamic registration, kept per authorization server (issuer). v1
     * kept the `client_id` alone; the redirect URI it was registered with and
     * the secret's expiry decide whether it can still be used.
     */
    data class ClientRecord(
        val clientId: String,
        val redirectUri: String,
        /** Epoch seconds; 0 for a registration carried over from v1. */
        val registeredAt: Double,
        /** RFC 7591 `client_secret_expires_at`; null or 0 = does not expire. */
        val secretExpiresAt: Double? = null,
    ) {
        /** Same keys as the iOS v2 cache entry. */
        fun toJson(): JSONObject = JSONObject().apply {
            put("clientId", clientId)
            put("redirectURI", redirectUri)
            put("registeredAt", registeredAt)
            secretExpiresAt?.let { put("secretExpiresAt", it) }
        }

        companion object {
            fun fromJson(obj: Any?): ClientRecord? {
                val o = obj as? JSONObject ?: return null
                val id = o.optString("clientId", "")
                if (id.isEmpty() || !o.has("redirectURI")) return null
                return ClientRecord(
                    clientId = id,
                    redirectUri = o.optString("redirectURI", ""),
                    registeredAt = o.optDouble("registeredAt", 0.0).takeIf { !it.isNaN() } ?: 0.0,
                    secretExpiresAt = if (o.has("secretExpiresAt")) o.optDouble("secretExpiresAt").takeIf { !it.isNaN() } else null,
                )
            }
        }
    }

    /**
     * The registration known for [issuer]: the v2 record, else the v1
     * `client_id` read as a registration made with the default redirect URI
     * (the only one v1 builds ever registered).
     */
    fun record(issuer: String, v2: JSONObject?, v1: JSONObject?, defaultRedirectUri: String): ClientRecord? {
        ClientRecord.fromJson(v2?.opt(issuer))?.let { return it }
        val id = v1?.optString(issuer, "").orEmpty()
        if (id.isNotEmpty()) return ClientRecord(id, defaultRedirectUri, 0.0)
        return null
    }

    sealed class Reuse {
        /** The cached registration can serve this sign-in. */
        object Cached : Reuse()
        data class RegisterAgain(val reason: String) : Reuse()
    }

    /**
     * Whether a known registration can serve this sign-in. A redirect URI other
     * than the registered one is refused by the authorization server (exact
     * match), and an expired secret fails the token exchange, so both register
     * again instead of failing later.
     */
    fun reuse(record: ClientRecord?, redirectUri: String, now: Double): Reuse {
        if (record == null) return Reuse.RegisterAgain("no registration")
        if (record.redirectUri != redirectUri) return Reuse.RegisterAgain("registered for another redirect URI")
        val expires = record.secretExpiresAt
        if (expires != null && expires > 0 && expires <= now) return Reuse.RegisterAgain("client secret expired")
        return Reuse.Cached
    }

    /** The issuers whose registration is [clientId] - what "Reset Sign-In"
     *  forgets for a server entry, which names its client and not its issuer. */
    fun issuers(withClientId: String, v2: JSONObject?, v1: JSONObject?): List<String> {
        if (withClientId.isEmpty()) return emptyList()
        val out = sortedSetOf<String>()
        v2?.keys()?.forEach { k -> if (ClientRecord.fromJson(v2.opt(k))?.clientId == withClientId) out.add(k) }
        v1?.keys()?.forEach { k -> if (v1.optString(k, "") == withClientId) out.add(k) }
        return out.toList()
    }

    // -- Failures --

    /** What a failed token request means for the next step (the official
     *  TypeScript SDK's `auth()` makes the same split). */
    sealed class TokenFailure {
        /** `invalid_client` / `unauthorized_client`: the server no longer knows
         *  this client. A dynamic registration is forgotten and made again; a
         *  Client ID the user typed is theirs to correct. */
        object ClientRejected : TokenFailure()

        /** `invalid_grant`: the code or refresh token is no good. Tokens go,
         *  the registration stays, the user signs in again. */
        object GrantRejected : TokenFailure()
        data class Other(val detail: String) : TokenFailure()
    }

    fun tokenFailure(body: String, status: Int): TokenFailure {
        val obj = runCatching { JSONObject(body) }.getOrNull()
        val code = obj?.optString("error", "").orEmpty()
        val detail = obj?.optString("error_description", "").orEmpty()
        return when (code) {
            "invalid_client", "unauthorized_client" -> TokenFailure.ClientRejected
            "invalid_grant" -> TokenFailure.GrantRejected
            else -> when {
                detail.isNotEmpty() -> TokenFailure.Other(detail)
                code.isNotEmpty() -> TokenFailure.Other(code)
                else -> {
                    val text = body.take(200)
                    TokenFailure.Other(text.ifEmpty { "HTTP $status" })
                }
            }
        }
    }

    /** A rejected client is registered again once per sign-in, and only when
     *  the registration was ours to make. */
    fun registersAgain(afterClientRejectedOnAttempt: Int, dynamic: Boolean): Boolean =
        dynamic && afterClientRejectedOnAttempt == 0

    /** RFC 9207: an authorization response that names its issuer must name the
     *  one the sign-in was started with. A response without `iss` passes. */
    fun issuerMatches(expected: String?, received: String?): Boolean {
        if (received.isNullOrEmpty() || expected.isNullOrEmpty()) return true
        return expected.trimEnd('/') == received.trimEnd('/')
    }

    // -- The form --

    enum class AuthKind { NONE, BROWSER, MANUAL }

    /** Which authorization a saved entry opens as. `mode` "dynamic" is a
     *  browser sign-in; anything else - "static", a value a newer build
     *  invented, none at all - is the manual form, which shows every field. */
    fun authKind(oauth: MCPOAuthConfig?): AuthKind = when {
        oauth == null -> AuthKind.NONE
        oauth.isDynamic -> AuthKind.BROWSER
        else -> AuthKind.MANUAL
    }

    /** The `oauth` block a browser-mode entry is saved with: what the last
     *  sign-in discovered, or the bare mode before the first one. Saving the
     *  form never drops the block (#432: it did, and the entry reopened as
     *  "None" with its sign-in gone from the sandbox's view). */
    fun browserBlock(discovered: MCPOAuthConfig?): MCPOAuthConfig =
        discovered?.copy(mode = MCPOAuthConfig.MODE_DYNAMIC) ?: MCPOAuthConfig(mode = MCPOAuthConfig.MODE_DYNAMIC)

    /** The sign-in button needs a name (the key everything is stored under)
     *  and a URL, and nothing already running. */
    fun canSignIn(name: String, url: String, busy: Boolean): Boolean =
        !busy && name.isNotBlank() && url.isNotBlank()
}
