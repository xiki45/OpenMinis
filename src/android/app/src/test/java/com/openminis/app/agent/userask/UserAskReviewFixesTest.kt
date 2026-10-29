package com.openminis.app.agent.userask

import com.openminis.app.IosSources
import com.openminis.app.ProductionSources
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Fixes from the Android beta review of the user_ask port:
 * - [T-user-ask-exposure-guard] a run that does not offer user_ask refuses it like iOS;
 * - [T-user-ask-async-draft-save] draft edits are saved off the main thread,
 *   coalesced, never resurrecting a removed record; terminal states stay synchronous.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class UserAskReviewFixesTest {

    private lateinit var store: UserAskRecordStore
    private val q = UserAskQuestion(UserAskQuestionType.OPTIONS, "Which?", options = listOf(UserAskOption("A"), UserAskOption("B")))
    private val ask = PendingAsk.create("r1", "s1", "tc1", listOf(q), 1_000L, 5)

    @Before fun setUp() {
        val ctx = RuntimeEnvironment.getApplication()
        ctx.getSharedPreferences(UserAskRecordStore.PREFS, 0).edit().clear().commit()
        store = UserAskRecordStore(ctx)
    }

    @After fun tearDown() = store.flushPending()

    // ── async draft saves ──────────────────────────────────────────────────

    @Test fun `a draft edit lands, coalesced, and keeps the record's snapshot`() {
        store.save(UserAskRecord(ask, assistantPartsJson = "[snapshot]"))
        val typed = ask.setAnswer(UserAskAnswer(otherText = "G"), 0)
        val typed2 = ask.setAnswer(UserAskAnswer(otherText = "Green"), 0)
        store.onAskChanged(typed)
        store.onAskChanged(typed2)
        store.flushPending()
        val r = store.record("r1")!!
        assertEquals("Green", r.ask.answers[0].otherText)
        assertEquals("[snapshot]", r.assistantPartsJson)
    }

    @Test fun `a terminal change is on disk synchronously`() {
        store.save(UserAskRecord(ask, "[]"))
        val answered = ask.copy(submitted = setOf(0)).setAnswer(UserAskAnswer(selected = listOf(1)), 0).resolveAnswered(UserAskAnswerSource.APP)!!
        store.onAskChanged(answered)
        // Read straight from a fresh store over the same prefs, no flush.
        val fresh = UserAskRecordStore(RuntimeEnvironment.getApplication())
        assertEquals(UserAskState.ANSWERED, fresh.record("r1")!!.ask.state)
        store.onAskChanged(answered.copy(consumed = true))
        assertNull("consumed = removed, synchronously", fresh.record("r1"))
    }

    @Test fun `a queued draft never resurrects a removed record or overrides a newer save`() {
        store.save(UserAskRecord(ask, "[]"))
        store.onAskChanged(ask.setAnswer(UserAskAnswer(otherText = "late"), 0))
        store.remove("r1")
        store.flushPending()
        assertNull(store.record("r1"))

        store.save(UserAskRecord(ask, "[]"))
        store.onAskChanged(ask.setAnswer(UserAskAnswer(otherText = "old draft"), 0))
        store.save(UserAskRecord(ask.setAnswer(UserAskAnswer(otherText = "restored"), 0), "[]"))
        store.flushPending()
        assertEquals("restored", store.record("r1")!!.ask.answers[0].otherText)
    }

    // ── exposure guard ─────────────────────────────────────────────────────

    @Test fun `a run that does not offer user_ask refuses it with iOS's message`() {
        assertEquals("Error: user_ask is not available in this run.", UserAskPromptText.NOT_AVAILABLE)
        if (IosSources.root != null) {
            val ios = IosSources.declaring("toolOutput = \"${UserAskPromptText.NOT_AVAILABLE}\"")
            assertTrue("iOS refuses inside the user_ask case", ios.contains("guard userAskExposed else {"))
        }
        val vm = ProductionSources.read("ui/chat/ChatViewModel.kt")
        assertTrue(vm.contains("com.openminis.app.agent.userask.UserAskPromptText.TOOL_NAME -> if (!userAskExposedThisRun) {"))
        val guard = vm.substringAfter("UserAskPromptText.TOOL_NAME -> if (!userAskExposedThisRun) {").take(2000)
        assertTrue(guard.contains("ToolExecutionResult(com.openminis.app.agent.userask.UserAskPromptText.NOT_AVAILABLE, false, toolTitle = \"\")"))
        assertTrue(guard.indexOf("} else") < guard.indexOf("UserAskToolRunner.execute("))
    }
}
