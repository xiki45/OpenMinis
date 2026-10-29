package com.openminis.app.auth

import com.openminis.app.ProductionSources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * [T-oauth-callback-double-resume] Port of iOS 401ff748c: the loopback OAuth
 * server ends the flow exactly once, and a denied / failed redirect
 * (`?error=access_denied`) ends it at once with the provider's reason instead
 * of being ignored until the user gives up.
 *
 * The server is driven over a real loopback socket, like the iOS test.
 */
class OAuthCallbackServerTest {

    // --- request parsing ---

    @Test
    fun `code redirect parses code and state`() {
        val r = OAuthCallbackServer.parseRequest("GET /callback?code=abc%2B1&state=s1 HTTP/1.1")
        assertEquals(OAuthCallbackServer.CallbackRequest.Code("abc+1", "s1"), r)
    }

    @Test
    fun `error redirect parses error and decoded description`() {
        val r = OAuthCallbackServer.parseRequest(
            "GET /callback?error=access_denied&error_description=The+user+denied%20access&state=s1 HTTP/1.1",
        )
        assertEquals(
            OAuthCallbackServer.CallbackRequest.Error(OAuthCallbackError("access_denied", "The user denied access")),
            r,
        )
    }

    @Test
    fun `error without description, blank description and empty error`() {
        assertEquals(
            OAuthCallbackServer.CallbackRequest.Error(OAuthCallbackError("server_error", null)),
            OAuthCallbackServer.parseRequest("GET /callback?error=server_error&error_description= HTTP/1.1"),
        )
        assertEquals(
            OAuthCallbackServer.CallbackRequest.Error(OAuthCallbackError("unknown_error", null)),
            OAuthCallbackServer.parseRequest("GET /callback?error= HTTP/1.1"),
        )
    }

    @Test
    fun `a code wins over an error, and an empty code is not a code`() {
        assertEquals(
            OAuthCallbackServer.CallbackRequest.Code("c", null),
            OAuthCallbackServer.parseRequest("GET /callback?code=c&error=x HTTP/1.1"),
        )
        assertEquals(
            OAuthCallbackServer.CallbackRequest.Error(OAuthCallbackError("x", null)),
            OAuthCallbackServer.parseRequest("GET /callback?code=&error=x HTTP/1.1"),
        )
    }

    @Test
    fun `favicon, bare path and garbage are not the redirect`() {
        assertEquals(OAuthCallbackServer.CallbackRequest.Other, OAuthCallbackServer.parseRequest("GET /favicon.ico HTTP/1.1"))
        assertEquals(OAuthCallbackServer.CallbackRequest.Other, OAuthCallbackServer.parseRequest("GET /callback HTTP/1.1"))
        assertEquals(OAuthCallbackServer.CallbackRequest.Other, OAuthCallbackServer.parseRequest("GET"))
        assertEquals(OAuthCallbackServer.CallbackRequest.Other, OAuthCallbackServer.parseRequest("GET /cb?%zz=1 HTTP/1.1"))
    }

    @Test
    fun `error page matches iOS and escapes the value`() {
        val page = OAuthCallbackServer.errorPage("<b>x</b>")
        assertTrue(page.startsWith("HTTP/1.1 400 Bad Request\r\n"))
        assertTrue(page.contains("<h1>Authorization Failed</h1><p>&lt;b&gt;x&lt;/b&gt;</p>"))
        val body = page.substringAfter("\r\n\r\n")
        assertTrue(page.contains("Content-Length: ${body.toByteArray(Charsets.UTF_8).size}\r\n"))
        assertFalse(page.contains("Return to Minis"))
        // With the package, the same way back as the success page.
        val back = OAuthCallbackServer.errorPage("access_denied", "com.openminis.app")
        assertTrue(back.contains(OAuthCallbackServer.returnToAppUrl("com.openminis.app")))
        val backBody = back.substringAfter("\r\n\r\n")
        assertTrue(back.contains("Content-Length: ${backBody.toByteArray(Charsets.UTF_8).size}\r\n"))
    }

    // --- the server, over loopback ---

    private class Outcomes {
        val codes = AtomicInteger()
        val errors = AtomicInteger()
        val cancels = AtomicInteger()
        @Volatile var lastError: OAuthCallbackError? = null
        @Volatile var lastCode: String? = null
        val any = CountDownLatch(1)
        val total get() = codes.get() + errors.get() + cancels.get()
    }

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private fun newServer(port: Int, o: Outcomes): OAuthCallbackServer =
        OAuthCallbackServer(
            port,
            onError = { e -> o.lastError = e; o.errors.incrementAndGet(); o.any.countDown() },
        ) { code, _ -> o.lastCode = code; o.codes.incrementAndGet(); o.any.countDown() }.also {
            it.onExternalCancel = { o.cancels.incrementAndGet(); o.any.countDown() }
        }

    /** GET [path]; retries the connect while the server thread is still binding. */
    private fun get(port: Int, path: String): String {
        val deadline = System.currentTimeMillis() + 3_000
        while (true) {
            try {
                Socket("127.0.0.1", port).use { s ->
                    s.soTimeout = 3_000
                    s.getOutputStream().write("GET $path HTTP/1.1\r\nHost: localhost\r\n\r\n".toByteArray())
                    return s.getInputStream().readBytes().toString(Charsets.UTF_8)
                }
            } catch (e: java.net.ConnectException) {
                if (System.currentTimeMillis() > deadline) throw e
                Thread.sleep(20)
            }
        }
    }

    @Test
    fun `error callback finishes the flow once with the reason, a later stop does not cancel`() {
        val port = freePort()
        val o = Outcomes()
        val server = newServer(port, o).apply { start() }
        val reply = get(port, "/callback?error=access_denied&error_description=User%20said%20no")
        assertTrue(reply, reply.startsWith("HTTP/1.1 400") && reply.contains("Authorization Failed") && reply.contains("access_denied"))
        assertTrue(o.any.await(3, TimeUnit.SECONDS))
        server.stop() // the flow's own cleanup
        server.stop()
        Thread.sleep(100)
        assertEquals(1, o.errors.get())
        assertEquals(0, o.cancels.get())
        assertEquals(1, o.total)
        assertEquals(OAuthCallbackError("access_denied", "User said no"), o.lastError)
    }

    @Test
    fun `success callback finishes once, stop afterwards does not cancel`() {
        val port = freePort()
        val o = Outcomes()
        val server = newServer(port, o).apply { start() }
        assertTrue(get(port, "/callback?code=abc&state=s1").startsWith("HTTP/1.1 200"))
        assertTrue(o.any.await(3, TimeUnit.SECONDS))
        server.stop()
        Thread.sleep(100)
        assertEquals(1, o.codes.get())
        assertEquals(1, o.total)
        assertEquals("abc", o.lastCode)
    }

    @Test
    fun `stop while waiting cancels exactly once`() {
        val port = freePort()
        val o = Outcomes()
        val server = newServer(port, o).apply { start() }
        get(port, "/favicon.ico") // make sure it is listening
        server.stop()
        server.stop()
        assertEquals(1, o.cancels.get())
        assertEquals(1, o.total)
    }

    @Test
    fun `a stray request is answered 404 and the flow keeps waiting for the redirect`() {
        val port = freePort()
        val o = Outcomes()
        val server = newServer(port, o).apply { start() }
        assertTrue(get(port, "/favicon.ico").startsWith("HTTP/1.1 404"))
        assertTrue(get(port, "/callback").startsWith("HTTP/1.1 404"))
        assertEquals(0, o.total)
        get(port, "/callback?code=late")
        assertTrue(o.any.await(3, TimeUnit.SECONDS))
        server.stop()
        assertEquals(1, o.codes.get())
        assertEquals(1, o.total)
    }

    @Test
    fun `stop racing a callback still yields exactly one outcome`() {
        repeat(40) { i ->
            val port = freePort()
            val o = Outcomes()
            val server = newServer(port, o).apply { start() }
            get(port, "/favicon.ico")
            val path = if (i % 2 == 0) "/callback?code=c$i" else "/callback?error=access_denied"
            val t = Thread { runCatching { get(port, path) } }
            t.start()
            server.stop()
            t.join(3_000)
            server.stop()
            Thread.sleep(20)
            assertEquals("iteration $i: codes=${o.codes} errors=${o.errors} cancels=${o.cancels}", 1, o.total)
        }
    }

    @Test
    fun `every OAuth flow passes onError and surfaces it`() {
        for (path in listOf(
            "auth/ClaudeOAuthManager.kt",
            "auth/OpenAIOAuthManager.kt",
            "auth/XAIOAuthManager.kt",
            "auth/OpenRouterOAuthManager.kt",
            "mcp/oauth/MCPOAuthController.kt",
        )) {
            val src = ProductionSources.read(path)
            assertTrue("$path resumes the waiter with the callback error",
                src.contains("onError = { err -> if (cont.isActive) cont.resumeWithException(err.toException(context)) }"))
        }
        val mcp = ProductionSources.read("mcp/oauth/MCPOAuthController.kt")
        assertTrue("MCP turns it into Result.Failed", mcp.contains("catch (e: com.openminis.app.auth.OAuthCallbackException)"))
        val base = ProductionSources.read("auth/OAuthManager.kt")
        assertTrue(base.contains("onComplete(false) }"))
    }
}
