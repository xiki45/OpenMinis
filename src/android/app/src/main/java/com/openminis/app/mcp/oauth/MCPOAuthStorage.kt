package com.openminis.app.mcp.oauth

import android.content.Context
import org.json.JSONObject

/**
 * [T-mcp-oauth-signin-android] What the browser sign-in reads and writes, as
 * two seams so [MCPOAuthDiscovery] and [MCPOAuthController.signIn] run in unit
 * tests against fakes (the real stores need EncryptedSharedPreferences).
 */
interface McpOAuthSecrets {
    fun clientSecret(server: String): String?
    fun setClientSecret(server: String, secret: String?)
    fun tokens(server: String): MCPOAuthStore.StoredTokens?
    fun setTokens(server: String, tokens: MCPOAuthStore.StoredTokens)
    fun signOut(server: String)
    fun purge(server: String)
    fun registrationSecret(issuer: String): String?
    fun setRegistrationSecret(issuer: String, secret: String?)
}

/** The app's secrets: [MCPOAuthStore] (EncryptedSharedPreferences). */
class StoreMcpOAuthSecrets(private val context: Context) : McpOAuthSecrets {
    override fun clientSecret(server: String) = MCPOAuthStore.clientSecret(context, server)
    override fun setClientSecret(server: String, secret: String?) =
        MCPOAuthStore.setClientSecret(context, server, secret?.trim()?.ifEmpty { null })
    override fun tokens(server: String) = MCPOAuthStore.tokens(context, server)
    override fun setTokens(server: String, tokens: MCPOAuthStore.StoredTokens) =
        MCPOAuthStore.setTokens(context, server, tokens)
    override fun signOut(server: String) = MCPOAuthStore.signOut(context, server)
    override fun purge(server: String) = MCPOAuthStore.purge(context, server)
    override fun registrationSecret(issuer: String) = MCPOAuthStore.registrationSecret(context, issuer)
    override fun setRegistrationSecret(issuer: String, secret: String?) =
        MCPOAuthStore.setRegistrationSecret(context, issuer, secret)
}

/**
 * The dynamic-registration cache, by authorization-server issuer. A
 * `client_id` from an unauthenticated `/register` is a public identifier, not
 * a secret, so it lives in plain SharedPreferences (iOS: UserDefaults); a
 * registration's `client_secret` goes to [McpOAuthSecrets]. v1 (issuer ->
 * client_id) is still read; v2 keeps the redirect URI and the secret's expiry.
 */
interface McpDcrCache {
    fun v1(): JSONObject?
    fun v2(): JSONObject?
    fun setV1(map: JSONObject)
    fun setV2(map: JSONObject)
}

class PrefsMcpDcrCache(context: Context) : McpDcrCache {
    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    private fun read(key: String): JSONObject? =
        prefs.getString(key, null)?.let { runCatching { JSONObject(it) }.getOrNull() }

    override fun v1() = read(KEY_V1)
    override fun v2() = read(KEY_V2)
    override fun setV1(map: JSONObject) = prefs.edit().putString(KEY_V1, map.toString()).apply()
    override fun setV2(map: JSONObject) = prefs.edit().putString(KEY_V2, map.toString()).apply()

    companion object {
        private const val FILE = "mcp_oauth_dcr"

        /** Same names as the iOS UserDefaults keys. */
        const val KEY_V1 = "mcp.oauth.dcr.clients.v1"
        const val KEY_V2 = "mcp.oauth.dcr.clients.v2"
    }
}
