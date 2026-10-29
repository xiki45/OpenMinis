package com.openminis.app.mcp.oauth

import com.openminis.app.mcp.oauth.MCPOAuthSignInLogic.ClientRecord
import com.openminis.app.mcp.oauth.MCPOAuthSignInLogic.Reuse
import com.openminis.app.mcp.oauth.MCPOAuthSignInLogic.TokenFailure
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-mcp-oauth-signin-android] The browser sign-in's decisions, the cases of
 * iOS MCPOAuthSignInTests (Standalone) and Windows mcp_oauth.rs.
 */
class McpOAuthSignInLogicTest {

    private val redirect = MCPOAuthController.DEFAULT_REDIRECT_URI

    @Test
    fun `a registration is reused only while it can work`() {
        val rec = ClientRecord("c1", redirect, 100.0)
        assertEquals(Reuse.Cached, MCPOAuthSignInLogic.reuse(rec, redirect, 200.0))
        assertTrue(MCPOAuthSignInLogic.reuse(null, redirect, 200.0) is Reuse.RegisterAgain)
        assertEquals(
            Reuse.RegisterAgain("registered for another redirect URI"),
            MCPOAuthSignInLogic.reuse(rec, "http://localhost:1/cb", 200.0),
        )
        // An expired secret registers again; 0 / null never expires.
        assertEquals(
            Reuse.RegisterAgain("client secret expired"),
            MCPOAuthSignInLogic.reuse(rec.copy(secretExpiresAt = 150.0), redirect, 200.0),
        )
        assertEquals(Reuse.Cached, MCPOAuthSignInLogic.reuse(rec.copy(secretExpiresAt = 0.0), redirect, 200.0))
        assertEquals(Reuse.Cached, MCPOAuthSignInLogic.reuse(rec.copy(secretExpiresAt = 300.0), redirect, 200.0))
    }

    @Test
    fun `v2 record wins, v1 client id reads as a default-redirect registration`() {
        val v2 = JSONObject().put("https://as", ClientRecord("new", "http://x/cb", 5.0, 9.0).toJson())
        val v1 = JSONObject().put("https://as", "old").put("https://other", "v1only")
        assertEquals(ClientRecord("new", "http://x/cb", 5.0, 9.0), MCPOAuthSignInLogic.record("https://as", v2, v1, redirect))
        assertEquals(ClientRecord("v1only", redirect, 0.0), MCPOAuthSignInLogic.record("https://other", v2, v1, redirect))
        assertNull(MCPOAuthSignInLogic.record("https://none", v2, v1, redirect))
        // A malformed v2 entry falls back to v1.
        val bad = JSONObject().put("https://as", JSONObject().put("clientId", ""))
        assertEquals("old", MCPOAuthSignInLogic.record("https://as", bad, v1, redirect)?.clientId)
    }

    @Test
    fun `issuers with a client id come from both cache generations`() {
        val v2 = JSONObject().put("b", ClientRecord("cid", redirect, 1.0).toJson()).put("c", ClientRecord("x", redirect, 1.0).toJson())
        val v1 = JSONObject().put("a", "cid")
        assertEquals(listOf("a", "b"), MCPOAuthSignInLogic.issuers("cid", v2, v1))
        assertEquals(emptyList<String>(), MCPOAuthSignInLogic.issuers("", v2, v1))
    }

    @Test
    fun `token failures are sorted like iOS`() {
        assertEquals(TokenFailure.ClientRejected, MCPOAuthSignInLogic.tokenFailure("""{"error":"invalid_client"}""", 401))
        assertEquals(TokenFailure.ClientRejected, MCPOAuthSignInLogic.tokenFailure("""{"error":"unauthorized_client"}""", 400))
        assertEquals(TokenFailure.GrantRejected, MCPOAuthSignInLogic.tokenFailure("""{"error":"invalid_grant"}""", 400))
        assertEquals(TokenFailure.Other("nope"), MCPOAuthSignInLogic.tokenFailure("""{"error":"x","error_description":"nope"}""", 400))
        assertEquals(TokenFailure.Other("server_error"), MCPOAuthSignInLogic.tokenFailure("""{"error":"server_error"}""", 500))
        assertEquals(TokenFailure.Other("gateway down"), MCPOAuthSignInLogic.tokenFailure("gateway down", 502))
        assertEquals(TokenFailure.Other("HTTP 503"), MCPOAuthSignInLogic.tokenFailure("", 503))
    }

    @Test
    fun `a rejected client is registered again once, and only a dynamic one`() {
        assertTrue(MCPOAuthSignInLogic.registersAgain(0, dynamic = true))
        assertFalse(MCPOAuthSignInLogic.registersAgain(1, dynamic = true))
        assertFalse(MCPOAuthSignInLogic.registersAgain(0, dynamic = false))
    }

    @Test
    fun `issuer check passes a missing iss and ignores a trailing slash`() {
        assertTrue(MCPOAuthSignInLogic.issuerMatches("https://as", null))
        assertTrue(MCPOAuthSignInLogic.issuerMatches(null, "https://as"))
        assertTrue(MCPOAuthSignInLogic.issuerMatches("https://as/", "https://as"))
        assertFalse(MCPOAuthSignInLogic.issuerMatches("https://as", "https://evil"))
    }

    @Test
    fun `entries open as the mode they were saved in`() {
        assertEquals(MCPOAuthSignInLogic.AuthKind.NONE, MCPOAuthSignInLogic.authKind(null))
        assertEquals(MCPOAuthSignInLogic.AuthKind.BROWSER, MCPOAuthSignInLogic.authKind(MCPOAuthConfig(mode = "dynamic")))
        assertEquals(MCPOAuthSignInLogic.AuthKind.MANUAL, MCPOAuthSignInLogic.authKind(MCPOAuthConfig(mode = "static")))
        // A value a newer build invented opens as the manual form.
        assertEquals(MCPOAuthSignInLogic.AuthKind.MANUAL, MCPOAuthSignInLogic.authKind(MCPOAuthConfig(mode = "future")))
    }

    @Test
    fun `the browser block is saved before and after the first sign-in`() {
        assertEquals(MCPOAuthConfig(mode = "dynamic"), MCPOAuthSignInLogic.browserBlock(null))
        val found = MCPOAuthConfig(mode = "dynamic", clientId = "c", authorizationEndpoint = "a", tokenEndpoint = "t", redirectUri = redirect)
        assertEquals(found, MCPOAuthSignInLogic.browserBlock(found))
        assertTrue(MCPOAuthSignInLogic.canSignIn("srv", "https://x", busy = false))
        assertFalse(MCPOAuthSignInLogic.canSignIn(" ", "https://x", busy = false))
        assertFalse(MCPOAuthSignInLogic.canSignIn("srv", "", busy = false))
        assertFalse(MCPOAuthSignInLogic.canSignIn("srv", "https://x", busy = true))
    }

    @Test
    fun `oauth block uses the iOS keys and still reads the old Android ones`() {
        val cfg = MCPOAuthConfig(
            mode = "dynamic", clientId = "cid", authorizationEndpoint = "https://as/authorize",
            tokenEndpoint = "https://as/token", scopes = "a b", redirectUri = redirect,
        )
        val json = cfg.toJson()
        // iOS MCPStore / minis-mcp-cli keys.
        assertEquals("cid", json.getString("clientId"))
        assertEquals("https://as/authorize", json.getString("authorizationEndpoint"))
        assertEquals("https://as/token", json.getString("tokenEndpoint"))
        assertEquals(redirect, json.getString("redirectURI"))
        assertEquals(cfg, MCPOAuthConfig.fromJson(json))
        // What an iOS (or `minis-mcp-cli add --oauth-*`) entry looks like.
        val ios = JSONObject("""{"mode":"static","clientId":"i","authorizationEndpoint":"https://a","tokenEndpoint":"https://t","redirectURI":"http://localhost:9/cb"}""")
        assertEquals(MCPOAuthConfig("static", "i", "https://a", "https://t", null, "http://localhost:9/cb"), MCPOAuthConfig.fromJson(ios))
        // What an earlier Android build wrote.
        val old = JSONObject("""{"mode":"static","client_id":"o","authorization_endpoint":"https://a","token_endpoint":"https://t"}""")
        assertEquals(MCPOAuthConfig("static", "o", "https://a", "https://t"), MCPOAuthConfig.fromJson(old))
        // A bare browser block survives; a bare manual one is noise.
        assertEquals(MCPOAuthConfig(mode = "dynamic"), MCPOAuthConfig.fromJson(JSONObject().put("mode", "dynamic")))
        assertNull(MCPOAuthConfig.fromJson(JSONObject().put("mode", "static")))
    }
}
