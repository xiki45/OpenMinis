package com.openminis.app.auth

import android.content.Context
import android.content.SharedPreferences
import com.openminis.app.util.EncryptedPrefsFactory
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * [T-codex-account-id-refresh] GH #447 follow-ups to the account id claim fix
 * ([CodexAccountIdClaimTest]): a refresh keeps the id_token an old sign-in
 * needs for its backfill, a refresh that brings a new id_token updates the
 * account id, a backfilled value is written back, and the access_token is
 * read when the id_token has no claim. All tokens here are fake.
 */
@RunWith(RobolectricTestRunner::class)
class CodexAccountIdRefreshTest {

    private val ctx: Context get() = RuntimeEnvironment.getApplication()
    private lateinit var prefs: SharedPreferences
    private val instanceId = "codex-test"

    private fun jwt(payload: String) =
        "eyJhbGciOiJub25lIn0." + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(payload.toByteArray()) + ".sig"

    private val nested = jwt("""{"https://api.openai.com/auth":{"chatgpt_account_id":"acct-nested","chatgpt_plan_type":"team"}}""")
    private val topLevel = jwt("""{"chatgpt_account_id":"acct-top","chatgpt_plan_type":"plus"}""")
    private val neither = jwt("""{"email":"a@b.c"}""")

    @Before
    fun setUp() {
        // The OAuth store, as a plain prefs file: AndroidKeyStore does not exist on the JVM.
        EncryptedPrefsFactory.resetCacheForTest()
        prefs = ctx.getSharedPreferences("oauth_prefs_test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        EncryptedPrefsFactory.cached("oauth_prefs") { prefs }
    }

    @After
    fun tearDown() = EncryptedPrefsFactory.resetCacheForTest()

    private fun storeTokens(json: JSONObject) {
        prefs.edit().putString("oauth_tokens_$instanceId", json.toString()).commit()
    }

    @Test
    fun `a refresh without an id_token keeps the stored one, and the refresh token`() {
        val stored = JSONObject().put("access_token", "old").put("refresh_token", "rt-1").put("id_token", nested)
        val merged = OAuthManager.refreshedTokens(stored, JSONObject().put("access_token", "new"), usedRefreshToken = "rt-1")
        assertEquals("new", merged.getString("access_token"))
        assertEquals("rt-1", merged.getString("refresh_token"))
        assertEquals(nested, merged.getString("id_token"))
    }

    @Test
    fun `a refresh with an id_token replaces the stored one`() {
        val stored = JSONObject().put("access_token", "old").put("refresh_token", "rt-1").put("id_token", nested)
        val response = JSONObject().put("access_token", "new").put("refresh_token", "rt-2").put("id_token", topLevel)
        val merged = OAuthManager.refreshedTokens(stored, response, usedRefreshToken = "rt-1")
        assertEquals("rt-2", merged.getString("refresh_token"))
        assertEquals(topLevel, merged.getString("id_token"))
        val noIdBefore = OAuthManager.refreshedTokens(JSONObject().put("access_token", "old"), JSONObject().put("access_token", "new"), "rt-1")
        assertFalse("nothing to keep", noIdBefore.has("id_token"))
    }

    @Test
    fun `the access_token's claim is used when the id_token has none`() {
        val fromAccess = OpenAIOAuthManager.claimsFromTokens(JSONObject().put("access_token", nested).put("id_token", neither))!!
        assertEquals("acct-nested", fromAccess.accountId)
        assertEquals("team", fromAccess.planType)
        assertEquals("access_token", fromAccess.source)
        val idWins = OpenAIOAuthManager.claimsFromTokens(JSONObject().put("access_token", topLevel).put("id_token", nested))!!
        assertEquals("acct-nested", idWins.accountId)
        assertEquals("id_token", idWins.source)
        assertNull("opaque access token, no id token", OpenAIOAuthManager.claimsFromTokens(JSONObject().put("access_token", "opaque")))
    }

    @Test
    fun `an old sign-in's account id is backfilled and written back`() {
        storeTokens(JSONObject().put("access_token", "opaque").put("refresh_token", "rt-1").put("id_token", nested))
        val manager = OpenAIOAuthManager(ctx, instanceId)
        assertEquals("acct-nested", manager.accountId)
        assertEquals("written back", "acct-nested", prefs.getString("oauth_account_id_$instanceId", null))
        assertEquals("team", manager.planType)
        assertEquals("team", prefs.getString("oauth_plan_type_$instanceId", null))
        // Once written back it no longer depends on the token keeping its id_token.
        storeTokens(JSONObject().put("access_token", "opaque"))
        assertEquals("acct-nested", OpenAIOAuthManager(ctx, instanceId).accountId)
    }

    @Test
    fun `an old sign-in with no id_token is backfilled from the access_token`() {
        storeTokens(JSONObject().put("access_token", topLevel))
        assertEquals("acct-top", OpenAIOAuthManager(ctx, instanceId).accountId)
    }

    @Test
    fun `a refreshed token with a new id_token updates the stored account id`() = runBlocking {
        prefs.edit().putString("oauth_account_id_$instanceId", "acct-old").putString("oauth_plan_type_$instanceId", "plus").commit()
        val manager = OpenAIOAuthManager(ctx, instanceId)
        manager.recordAccountClaims(JSONObject().put("access_token", "opaque").put("id_token", nested))
        assertEquals("acct-nested", manager.accountId)
        assertEquals("team", manager.planType)
        // A token without the claim leaves what was stored.
        manager.recordAccountClaims(JSONObject().put("access_token", "opaque").put("id_token", neither))
        assertEquals("acct-nested", manager.accountId)
    }
}
