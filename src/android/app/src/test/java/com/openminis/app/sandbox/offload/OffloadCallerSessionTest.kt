package com.openminis.app.sandbox.offload

import com.openminis.app.ProductionSources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-offload-caller-session] minis-model-use and minis-scheduled attribute
 * their work to the chat whose /var/minis the calling shell sees, which for a
 * sub agent is its PARENT (iOS 7effb6f2f, b99e4da87: the fs_context of a
 * helper's shell names the parent).
 */
class OffloadCallerSessionTest {

    private val parentOf = mapOf("helper-1" to "parent-A")
    private fun mounted(sid: String) = parentOf[sid] ?: sid

    @Test
    fun `a sub agent's command belongs to its parent chat`() {
        val c = OffloadCallerSession.resolve("helper-1", ::mounted)
        assertEquals("parent-A", c.sid)
        assertTrue(c.source, c.source.startsWith("mounted parent"))
    }

    @Test
    fun `an ordinary chat's command belongs to that chat`() {
        val c = OffloadCallerSession.resolve("chat-B", ::mounted)
        assertEquals("chat-B", c.sid)
        assertEquals("env", c.source)
    }

    @Test
    fun `a helper whose mapping is gone falls back to its own id`() {
        // ExecutionCoordinator drops the mapping when the helper terminates,
        // e.g. for a nohup'd process that outlives it. Same fallback as iOS's
        // "unknown fs_context" case: never a null or a guessed chat.
        val c = OffloadCallerSession.resolve("helper-gone", ::mounted)
        assertEquals("helper-gone", c.sid)
        assertEquals("env", c.source)
    }

    @Test
    fun `no chat means no caller`() {
        assertNull(OffloadCallerSession.resolve(null, ::mounted).sid)
        assertNull(OffloadCallerSession.resolve("", ::mounted).sid)
    }

    // ── Wiring ───────────────────────────────────────────────────────────

    private val modelUse = ProductionSources.read("sandbox/offload/ModelUseOffloadHandler.kt")
    private val scheduled = ProductionSources.read("sandbox/offload/ScheduledTaskOffloadHandler.kt")

    @Test
    fun `model-use resolves the caller once and uses it everywhere`() {
        val run = modelUse.substringAfter("private fun cmdRun(")
        assertTrue(run.contains("val caller = OffloadCallerSession.resolve(request.sessionId)"))
        assertEquals(
            "request.sessionId is read only by the resolver",
            1, Regex("""request\.sessionId""").findAll(modelUse).count(),
        )
        // Provider session header, passthrough, image routing, and the write path.
        assertTrue(modelUse.contains("sessionId = callerSid, overrides = entry.overrides,"))
        assertEquals(2, Regex("""sessionId = callerSid,\n""").findAll(modelUse).count())
        assertTrue(modelUse.contains("val sessionId = callerSid\n"))
        assertTrue(modelUse.contains("[ModelUse] run caller="))
    }

    @Test
    fun `scheduled create is filed under the resolved caller`() {
        assertTrue(scheduled.contains("val caller = OffloadCallerSession.resolve(request.sessionId)"))
        assertTrue(scheduled.contains("handleCreate(args, caller.sid)"))
        assertFalse(scheduled.contains("handleCreate(args, request.sessionId)"))
        assertTrue(scheduled.contains("create caller="))
    }

    @Test
    fun `photo export writes where the calling shell can read it`() {
        val photos = ProductionSources.read("sandbox/offload/PhotosOffloadHandler.kt")
        assertTrue(photos.contains("val caller = OffloadCallerSession.resolve(request.sessionId)"))
        assertTrue(photos.contains("handleExport(args, caller.sid)"))
        assertFalse(photos.contains("handleExport(args, request.sessionId)"))
    }

    @Test
    fun `no offload handler builds a per-session host dir from the raw dispatch id`() {
        // Every host path under minis-sessions/<sid>/ must come from a resolved
        // caller: the raw MINIS_CHAT_SESSION_ID is a helper's own session.
        val dir = java.io.File(ProductionSources.mainRoot(), "sandbox/offload")
        val offenders = dir.listFiles { f -> f.extension == "kt" }!!.filter { f ->
            val src = f.readText()
            Regex("""(handleExport|sessionScopedHostFile)\([^)]*request\.sessionId""").containsMatchIn(src) ||
                src.contains("minis-sessions/\${request.sessionId")
        }.map { it.name }
        assertEquals(emptyList<String>(), offenders)
    }
}
