package com.openminis.app.mcp.oauth

import org.json.JSONObject

/**
 * [T-android-mcp-oauth] Non-secret OAuth configuration for an MCP server,
 * round-tripped inside the server entry in servers.json. The client SECRET and
 * issued tokens never live here — those go to [MCPOAuthStore]
 * (EncryptedSharedPreferences). Mirrors iOS `MCPOAuthConfig`.
 */
data class MCPOAuthConfig(
    /** "static" = user-supplied client credentials (the manual form).
     *  "dynamic" = browser sign-in: discovery + dynamic client registration
     *  fill the rest in ([MCPOAuthDiscovery], iOS #432). */
    val mode: String = MODE_STATIC,
    val clientId: String = "",
    val authorizationEndpoint: String = "",
    val tokenEndpoint: String = "",
    /** Space-separated scopes, e.g. "openid email https://.../calendar". */
    val scopes: String? = null,
    /** Custom redirect URI; defaults to the loopback callback when null. */
    val redirectUri: String? = null,
) {
    val isConfigured: Boolean
        get() = clientId.isNotBlank() &&
            authorizationEndpoint.isNotBlank() &&
            tokenEndpoint.isNotBlank()

    /** A browser sign-in entry (saved even before its first sign-in, so the
     *  form reopens in that mode - iOS #432). */
    val isDynamic: Boolean get() = mode == MODE_DYNAMIC

    /**
     * The entry's `oauth` object. [T-mcp-oauth-signin-android] Written with the
     * keys iOS and the in-guest `minis-mcp-cli add --oauth-*` use (camelCase,
     * `redirectURI`), so a servers.json carried between platforms by a backup
     * keeps its sign-in settings. The snake_case keys earlier Android builds
     * wrote ride along, so a downgrade still finds them.
     */
    fun toJson(): JSONObject = JSONObject().apply {
        put("mode", mode)
        put("clientId", clientId)
        put("authorizationEndpoint", authorizationEndpoint)
        put("tokenEndpoint", tokenEndpoint)
        scopes?.takeIf { it.isNotBlank() }?.let { put("scopes", it) }
        redirectUri?.takeIf { it.isNotBlank() }?.let { put("redirectURI", it) }
        put("client_id", clientId)
        put("authorization_endpoint", authorizationEndpoint)
        put("token_endpoint", tokenEndpoint)
        redirectUri?.takeIf { it.isNotBlank() }?.let { put("redirect_uri", it) }
    }

    companion object {
        const val MODE_STATIC = "static"
        const val MODE_DYNAMIC = "dynamic"

        /** Parse from the `oauth` object inside a server entry. Returns null
         *  when the object is absent, or is a manual block with nothing in it.
         *  Reads the iOS / CLI keys first, then the snake_case ones older
         *  Android builds wrote. */
        fun fromJson(obj: JSONObject?): MCPOAuthConfig? {
            if (obj == null) return null
            fun str(camel: String, snake: String): String =
                obj.optString(camel, "").ifBlank { obj.optString(snake, "") }
            val config = MCPOAuthConfig(
                mode = obj.optString("mode", MODE_STATIC).ifBlank { MODE_STATIC },
                clientId = str("clientId", "client_id"),
                authorizationEndpoint = str("authorizationEndpoint", "authorization_endpoint"),
                tokenEndpoint = str("tokenEndpoint", "token_endpoint"),
                scopes = obj.optString("scopes", "").ifBlank { null },
                redirectUri = str("redirectURI", "redirect_uri").ifBlank { null },
            )
            // A browser-mode block is kept even when empty: it is how the entry
            // says "sign in with the browser" before the first sign-in. A manual
            // block with no client and no endpoints is noise, not configuration.
            if (config.isDynamic) return config
            return if (config.clientId.isBlank() &&
                config.authorizationEndpoint.isBlank() &&
                config.tokenEndpoint.isBlank()
            ) {
                null
            } else {
                config
            }
        }
    }
}
