package com.openminis.app.mcp.oauth

import org.json.JSONObject

/** In-memory [McpOAuthSecrets] for tests (the real one needs EncryptedSharedPreferences). */
class FakeMcpOAuthSecrets : McpOAuthSecrets {
    val clientSecrets = mutableMapOf<String, String>()
    val tokenMap = mutableMapOf<String, MCPOAuthStore.StoredTokens>()
    val registrationSecrets = mutableMapOf<String, String>()

    override fun clientSecret(server: String) = clientSecrets[server]
    override fun setClientSecret(server: String, secret: String?) {
        if (secret.isNullOrBlank()) clientSecrets.remove(server) else clientSecrets[server] = secret.trim()
    }
    override fun tokens(server: String) = tokenMap[server]
    override fun setTokens(server: String, tokens: MCPOAuthStore.StoredTokens) { tokenMap[server] = tokens }
    override fun signOut(server: String) { tokenMap.remove(server) }
    override fun purge(server: String) { tokenMap.remove(server); clientSecrets.remove(server) }
    override fun registrationSecret(issuer: String) = registrationSecrets[issuer]
    override fun setRegistrationSecret(issuer: String, secret: String?) {
        if (secret.isNullOrBlank()) registrationSecrets.remove(issuer) else registrationSecrets[issuer] = secret.trim()
    }
}

/** In-memory [McpDcrCache]; stores copies, like the prefs-backed one. */
class FakeMcpDcrCache : McpDcrCache {
    var v1Raw: String? = null
    var v2Raw: String? = null
    override fun v1() = v1Raw?.let { JSONObject(it) }
    override fun v2() = v2Raw?.let { JSONObject(it) }
    override fun setV1(map: JSONObject) { v1Raw = map.toString() }
    override fun setV2(map: JSONObject) { v2Raw = map.toString() }
}
