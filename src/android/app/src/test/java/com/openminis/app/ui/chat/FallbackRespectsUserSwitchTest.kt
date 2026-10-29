package com.openminis.app.ui.chat

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-fallback-respects-user-switch] A group fallback inside a running turn must
 * not overwrite a model the user picked while that turn ran.
 *
 * Before the fix the fallback branch of runAgentLoop unconditionally wrote
 * `this@ChatViewModel.currentProvider = next` and persisted
 * `{"type":"entry","entryId":B}`, so a user who switched to model C mid-turn
 * found the session bound to fallback member B afterwards.
 *
 * The loop is a private suspend member that needs a Context, Room and live
 * providers, so the decision is a pure companion predicate (tested directly)
 * and its wiring is pinned as source facts.
 */
class FallbackRespectsUserSwitchTest {

    private val vmSrc by lazy {
        File("src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt")
            .also { assertTrue("missing ChatViewModel.kt", it.exists()) }.readText()
    }

    @Test
    fun `fallback may rebind only while the class-level provider is unchanged`() {
        val a = Any()
        val c = Any()
        assertTrue(ChatViewModel.fallbackMayRebindSession(a, a))
        assertFalse("user switched to another provider mid-turn", ChatViewModel.fallbackMayRebindSession(c, a))
        assertTrue(ChatViewModel.fallbackMayRebindSession(null, null))
        assertFalse(ChatViewModel.fallbackMayRebindSession(c, null))
    }

    @Test
    fun `the class-level write and persistBinding sit behind the user-switch guard`() {
        val loop = vmSrc.substringAfter("private suspend fun runAgentLoop(")
        assertTrue(loop.contains("var classProviderAtSend = this@ChatViewModel.currentProvider"))
        // [T-b13-empty-fallback] The session side moved into
        // adoptLoopFallbackBinding, shared by the error and empty-turn paths.
        val fn = vmSrc.substringAfter("private fun adoptLoopFallbackBinding(").substringBefore("\n    }\n")
        val guard = fn.indexOf("val userSwitchedMidTurn = !fallbackMayRebindSession(currentProvider, classProviderAtSend)")
        val write = fn.indexOf("            currentProvider = next")
        val persist = fn.indexOf("persistBinding(\"\"\"{\"type\":\"entry\",\"entryId\":\"\${newEntry.id}\"}\"\"\")")
        assertTrue("guard not found", guard >= 0)
        assertTrue("class-level write must follow the guard", write > guard)
        assertTrue("persistBinding must follow the guard", persist > guard)
        assertTrue(fn.contains("return !userSwitchedMidTurn"))
        // Our own fallback moves the baseline, so a second fallback in the same
        // turn is still allowed to rebind: both call sites move it when the
        // binding moved.
        val call = "if (adoptLoopFallbackBinding(nextCandidate, classProviderAtSend, isRealModelChange)) {\n" +
            "                            classProviderAtSend = next"
        assertTrue("error-path call site", loop.contains(call))
        assertTrue(
            "empty-turn call site",
            loop.contains("if (adoptLoopFallbackBinding(emptyNext, classProviderAtSend, isRealModelChange)) {\n" +
                "                            classProviderAtSend = next"),
        )
    }
}
