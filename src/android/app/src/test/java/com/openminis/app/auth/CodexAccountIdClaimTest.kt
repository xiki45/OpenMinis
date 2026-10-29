package com.openminis.app.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * [T-android-codex-account-id-claim] The ChatGPT account id (sent as the
 * Chatgpt-Account-Id header) is read from where an OpenAI id_token has it.
 */
@RunWith(RobolectricTestRunner::class)
class CodexAccountIdClaimTest {

    private fun idToken(payload: String) =
        "eyJhbGciOiJub25lIn0." + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(payload.toByteArray()) + ".sig"

    @Test
    fun `the account id and plan are read from the namespaced auth claim of the id_token`() {
        val claims = OpenAIOAuthManager.claimsFromIdToken(
            idToken("""{"email":"a@b.c","https://api.openai.com/auth":{"chatgpt_account_id":"acct-123","chatgpt_plan_type":"team"}}"""),
        )!!
        assertEquals("acct-123", claims.accountId)
        assertEquals("team", claims.planType)
    }

    @Test
    fun `top-level claims still work, and a token without them gives nulls`() {
        val top = OpenAIOAuthManager.claimsFromIdToken(idToken("""{"chatgpt_account_id":"acct-top","chatgpt_plan_type":"plus"}"""))!!
        assertEquals("acct-top", top.accountId)
        assertEquals("plus", top.planType)
        val none = OpenAIOAuthManager.claimsFromIdToken(idToken("""{"email":"a@b.c"}"""))!!
        assertNull(none.accountId)
        assertNull(none.planType)
        assertNull("not a JWT", OpenAIOAuthManager.claimsFromIdToken("opaque"))
    }
}
