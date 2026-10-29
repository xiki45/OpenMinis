package com.openminis.app.network

import com.openminis.app.network.TailscaleLoginFlow.Reason
import com.openminis.app.network.TailscaleLoginFlow.State
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-net-ts-login-browser] One tap opens the sign-in page even when tsnet has
 * not produced the URL yet (the old "tap twice" bug); Running brings the app
 * back; leaving the browser without signing in is a quiet return to the
 * not-signed-in state.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TailscaleLoginFlowTest {

    private val needsLogin = TailscaleStatus(running = true, backendState = "NeedsLogin")
    private val withUrl = needsLogin.copy(authURL = "https://login.tailscale.com/a/abc")
    private val running = TailscaleStatus(running = true, backendState = "Running")

    private class Harness(val scope: TestScope) {
        var current: TailscaleStatus = TailscaleStatus()
        var statusCalls = 0
        var returns = 0
        val published = mutableListOf<TailscaleStatus>()
        val opened = mutableListOf<String>()
        var restarts = 0
        /** What the node reports after a restart (a fresh sign-in URL by default). */
        var afterRestart: () -> TailscaleStatus = {
            TailscaleStatus(running = true, backendState = "NeedsLogin", authURL = "https://login.tailscale.com/a/fresh$restarts")
        }
        val flow = TailscaleLoginFlow(
            scope = scope.backgroundScope,
            status = { statusCalls++; current },
            returnToApp = { returns++ },
            publish = { published += it },
            freshLogin = { restarts++; current = afterRestart() },
            clock = { scope.testScheduler.currentTime },
        )

        init {
            scope.backgroundScope.launch { flow.opens.collect { opened += it } }
            scope.runCurrent()
        }
    }

    @Test
    fun `one tap opens the page once the URL arrives`() = runTest {
        val h = Harness(this)
        h.current = needsLogin
        h.flow.begin()
        runCurrent()
        assertEquals(State.OpeningPage, h.flow.state.value)
        assertTrue("no page before the URL exists", h.opened.isEmpty())
        advanceTimeBy(1_000)
        h.current = withUrl // tsnet's control round trip finished
        advanceTimeBy(500)
        assertEquals(listOf(withUrl.authURL), h.opened)
        assertEquals(State.InBrowser, h.flow.state.value)
        assertEquals(withUrl.authURL, h.flow.lastUrl)
    }

    @Test
    fun `a URL already there opens at once`() = runTest {
        val h = Harness(this)
        h.current = withUrl
        h.flow.begin()
        runCurrent()
        assertEquals(1, h.opened.size)
    }

    @Test
    fun `a second tap while the page is on its way does not open it twice`() = runTest {
        val h = Harness(this)
        h.current = needsLogin
        h.flow.begin()
        runCurrent()
        h.flow.begin()
        h.current = withUrl
        advanceTimeBy(1_000)
        assertEquals(1, h.opened.size)
    }

    @Test
    fun `Running brings the app back and publishes the status`() = runTest {
        val h = Harness(this)
        h.current = withUrl
        h.flow.begin()
        runCurrent()
        h.flow.appHidden() // the Custom Tab covers the app
        advanceTimeBy(5_000)
        assertEquals(0, h.returns)
        h.current = running
        advanceTimeBy(1_500)
        assertEquals(1, h.returns)
        assertEquals(State.Idle, h.flow.state.value)
        assertEquals(running, h.published.last())
        // Back in front after the automatic return: nothing more happens.
        h.flow.appShown()
        advanceTimeBy(10_000)
        assertEquals(1, h.returns)
    }

    @Test
    fun `closing the browser without signing in returns quietly and a new tap works`() = runTest {
        val h = Harness(this)
        h.current = withUrl
        h.flow.begin()
        runCurrent()
        h.flow.appHidden()
        advanceTimeBy(3_000)
        h.flow.appShown()
        runCurrent()
        assertEquals(State.Idle, h.flow.state.value)
        assertEquals(0, h.returns)
        val calls = h.statusCalls
        advanceTimeBy(30_000)
        assertEquals("polling stops after the user came back", calls, h.statusCalls)
        h.flow.begin()
        runCurrent()
        assertEquals(2, h.opened.size)
        assertEquals(State.InBrowser, h.flow.state.value)
    }

    @Test
    fun `coming back before the browser covered the app is not a cancel`() = runTest {
        val h = Harness(this)
        h.current = withUrl
        h.flow.begin()
        runCurrent()
        h.flow.appShown() // ON_START without a preceding ON_STOP
        assertEquals(State.InBrowser, h.flow.state.value)
    }

    @Test
    fun `no URL in time is a clear failure`() = runTest {
        val h = Harness(this)
        h.current = needsLogin
        h.flow.begin()
        advanceTimeBy(21_000)
        assertEquals(State.Failed(Reason.NO_SIGN_IN_PAGE), h.flow.state.value)
        assertTrue(h.opened.isEmpty())
        // A new tap tries again.
        h.current = withUrl
        h.flow.begin()
        runCurrent()
        assertEquals(1, h.opened.size)
    }

    @Test
    fun `no browser leaves the link to copy`() = runTest {
        val h = Harness(this)
        h.current = withUrl
        h.flow.begin()
        runCurrent()
        h.flow.browserUnavailable()
        assertEquals(State.Failed(Reason.NO_BROWSER), h.flow.state.value)
        assertEquals(withUrl.authURL, h.flow.lastUrl)
        h.current = running
        advanceTimeBy(5_000)
        assertEquals("no browser, no return", 0, h.returns)
    }

    @Test
    fun `already signed in opens nothing`() = runTest {
        val h = Harness(this)
        h.current = running
        h.flow.begin()
        runCurrent()
        assertTrue(h.opened.isEmpty())
        assertEquals(State.Idle, h.flow.state.value)
    }

    @Test
    fun `the login log lines never carry the URL`() {
        val src = com.openminis.app.ProductionSources.read("network/TailscaleLoginFlow.kt") +
            com.openminis.app.ProductionSources.read("ui/settings/NetworkSettingsScreen.kt")
        val logLines = src.lines().filter { "[Net][TS] login" in it }
        assertTrue(logLines.size >= 6)
        for (l in logLines) assertFalse(l, "\$url" in l || "authURL" in l || "\$uri" in l)
    }

    // [FIX-261021-11] Pixel 6: the browser said the sign-in succeeded, the
    // node got "register request: http 410: auth path not found" and kept
    // offering that dead URL, and every new tap reopened it.

    @Test
    fun `after a 410 the dead URL is not reopened - the node restarts and the new URL opens`() = runTest {
        val h = Harness(this)
        h.current = withUrl.copy(health = listOf(
            "You are logged out. The last login error was: register request: http 410: auth path not found",
        ))
        assertTrue(h.current.loginError != null)
        h.flow.begin()
        runCurrent()
        assertEquals(1, h.restarts)
        assertEquals(listOf("https://login.tailscale.com/a/fresh1"), h.opened)
        assertEquals(State.InBrowser, h.flow.state.value)
    }

    @Test
    fun `a URL already opened once is never opened again`() = runTest {
        val h = Harness(this)
        h.current = withUrl
        h.flow.begin()
        runCurrent()
        h.flow.appHidden()
        h.flow.appShown() // back without completing; tsnet still offers the same URL
        runCurrent()
        h.flow.begin()
        runCurrent()
        assertEquals(1, h.restarts)
        assertEquals(listOf(withUrl.authURL, "https://login.tailscale.com/a/fresh1"), h.opened)
    }

    @Test
    fun `one restart per tap - a node that keeps the dead URL ends in no sign-in page`() = runTest {
        val h = Harness(this)
        h.current = withUrl
        h.flow.begin()
        runCurrent()
        h.flow.appHidden()
        h.flow.appShown()
        runCurrent()
        h.afterRestart = { withUrl }
        h.flow.begin()
        advanceTimeBy(25_000)
        assertEquals(1, h.restarts)
        assertEquals(listOf(withUrl.authURL), h.opened)
        assertEquals(State.Failed(Reason.NO_SIGN_IN_PAGE), h.flow.state.value)
    }

    @Test
    fun `a fresh first URL opens without a restart`() = runTest {
        val h = Harness(this)
        h.current = withUrl
        h.flow.begin()
        runCurrent()
        assertEquals(0, h.restarts)
    }

    @Test
    fun `health is parsed from the node status`() {
        val st = TailscaleStatus.parse(org.json.JSONObject(
            """{"running":true,"backendState":"NeedsLogin","health":["You are logged out. The last login error was: x"]}""",
        ))
        assertEquals("You are logged out. The last login error was: x", st.loginError)
        assertEquals(null, TailscaleStatus.parse(org.json.JSONObject("""{"running":true}""")).loginError)
    }
}
