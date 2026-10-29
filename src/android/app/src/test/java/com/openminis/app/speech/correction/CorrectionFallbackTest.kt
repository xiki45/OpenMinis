package com.openminis.app.speech.correction

import com.openminis.app.ProductionSources
import com.openminis.app.R
import com.openminis.app.data.model.FallbackStrategy
import com.openminis.app.data.model.LLMError
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.ModelEntry
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.coroutines.cancellation.CancellationException

/**
 * [T-voice-correction-fallback] A user log (iOS, 2026-10-15): every manual
 * correction failed with Anthropic's "credit balance is too low" and returned
 * the original text, while the chat on the same device moved to the next model
 * of the group. Android had the same single call: the sub group's first
 * available member, else the primary's, and give up on its first error.
 */
class CorrectionFallbackTest {

    private fun entry(id: String) = ModelEntry(
        providerInstanceId = "inst-$id",
        baseModel = LLMModel(id = id, displayName = id, provider = "test"),
    )

    private fun model(id: String, kind: String = "sub", strategy: FallbackStrategy = FallbackStrategy.default) =
        CorrectionModel(entry(id), kind, strategy)

    private fun always(id: String) = model(id, strategy = FallbackStrategy.always)

    private val network = LLMError.NetworkError(java.io.IOException("connection reset"))

    private val creditTooLow = LLMError.ProviderError(
        "[invalid_request_error] Your credit balance is too low to access the Anthropic API. " +
            "Please go to Plans & Billing to upgrade or purchase credits.",
        httpStatus = 400,
    )

    /** A strategy over fake models: [answers] maps a model id to its reply or error. */
    private fun strategy(
        models: List<CorrectionModel>,
        calls: MutableList<String>,
        budgetMs: Long = 15_000,
        answers: suspend (String) -> String,
    ) = LlmCorrectionStrategy(
        candidates = { models },
        callModel = { m, _ ->
            synchronized(calls) { calls += m.entry.model.id }
            answers(m.entry.model.id)
        },
        callBudgetMs = budgetMs,
        // The chat's ladder has three steps; no real waiting in a unit test.
        retryDelaysMs = listOf(0L, 0L, 0L),
    )

    private suspend fun failsWith(s: LlmCorrectionStrategy): CorrectionError.AllModelsFailed {
        try {
            s.correct("x", emptyList(), ConversationContext.EMPTY, "zh")
        } catch (e: CorrectionError.AllModelsFailed) {
            return e
        }
        fail("expected AllModelsFailed")
        throw AssertionError()
    }

    private fun engine(strategy: CorrectionStrategy) =
        VoiceCorrectionEngine(emptyList(), strategy, null, { it.chunked(2) }) { false }

    // ── the user's case ──────────────────────────────────────────────────

    @Test
    fun `a model out of credit falls back to the next one`() = runBlocking {
        val calls = mutableListOf<String>()
        val s = strategy(listOf(model("claude"), model("deepseek")), calls) { id ->
            if (id == "claude") throw creditTooLow else "我在北京"
        }
        val outcome = s.correct("我在背景", emptyList(), ConversationContext.EMPTY, "zh")
        assertEquals("我在北京", outcome.correctedText)
        assertEquals(listOf("claude", "deepseek"), calls)
        assertEquals("sub+fallback2", outcome.modelGroupUsed)
    }

    @Test
    fun `through the engine the fallback answer is applied`() = runBlocking {
        val calls = mutableListOf<String>()
        val s = strategy(listOf(model("claude"), model("deepseek", "primary")), calls) { id ->
            if (id == "claude") throw creditTooLow else "我在北京"
        }
        val suggestion = engine(s).correct("我在背景")
        assertTrue(suggestion.hasChange)
        assertEquals("我在北京", suggestion.corrected)
        assertNull(suggestion.rejectedReason)
    }

    @Test
    fun `when every model fails the original is kept and the reason is named`() = runBlocking {
        val calls = mutableListOf<String>()
        val s = strategy(listOf(model("a"), model("b")), calls) { throw creditTooLow }
        val suggestion = engine(s).correct("我在背景")
        assertFalse(suggestion.hasChange)
        assertEquals("我在背景", suggestion.corrected)
        assertEquals("llm_error:ProviderError", suggestion.rejectedReason)
        assertTrue(suggestion.failureDetail!!.contains("credit balance is too low"))
        assertEquals(R.string.agent_error_quota_exhausted, CorrectionFailureReason.res(suggestion))
        assertEquals(listOf("a", "b"), calls)
    }

    // ── what a failure does: the group's fallback strategy ──────────────

    @Test
    fun `Always Fall Back moves on at once, whatever the error`() = runBlocking {
        val calls = mutableListOf<String>()
        val s = strategy(listOf(always("net"), always("blank"), always("ok")), calls) { id ->
            when (id) {
                "net" -> throw network
                "blank" -> "  "
                else -> "好"
            }
        }
        assertEquals("好", s.correct("x", emptyList(), ConversationContext.EMPTY, "zh").correctedText)
        // No same-model retry of the network error, no stop on the empty answer.
        assertEquals(listOf("net", "blank", "ok"), calls)
    }

    @Test
    fun `default strategy retries a network error on the same model, then stops`() = runBlocking {
        val calls = mutableListOf<String>()
        val e = failsWith(strategy(listOf(model("a"), model("b")), calls) { throw network })
        // 1 call + the chat's 3 retries; a network error is not fallbackable.
        assertEquals(listOf("a", "a", "a", "a"), calls)
        assertEquals(1, e.attempts.size)
    }

    @Test
    fun `default strategy recovers when a same-model retry succeeds`() = runBlocking {
        val calls = mutableListOf<String>()
        var n = 0
        val s = strategy(listOf(model("a"), model("b")), calls) { if (++n == 1) throw network else "好" }
        val outcome = s.correct("x", emptyList(), ConversationContext.EMPTY, "zh")
        assertEquals("好", outcome.correctedText)
        assertEquals(listOf("a", "a"), calls)
        assertEquals("sub", outcome.modelGroupUsed)
    }

    @Test
    fun `default strategy retries a 5xx, then falls back`() = runBlocking {
        val calls = mutableListOf<String>()
        val s = strategy(listOf(model("a"), model("b")), calls) { id ->
            if (id == "a") throw LLMError.TransientError("overloaded", httpStatus = 503) else "好"
        }
        assertEquals("好", s.correct("x", emptyList(), ConversationContext.EMPTY, "zh").correctedText)
        assertEquals(listOf("a", "a", "a", "a", "b"), calls)
    }

    @Test
    fun `default strategy falls back at once on a rate limit or a rejected key`() = runBlocking {
        for (err in listOf(LLMError.RateLimited(), LLMError.InvalidApiKey(), creditTooLow)) {
            val calls = mutableListOf<String>()
            val s = strategy(listOf(model("a"), model("b")), calls) { id -> if (id == "a") throw err else "好" }
            assertEquals("好", s.correct("x", emptyList(), ConversationContext.EMPTY, "zh").correctedText)
            assertEquals("${err.javaClass.simpleName} falls back without a retry", listOf("a", "b"), calls)
        }
    }

    @Test
    fun `default strategy stops on an error the chat does not fall back on`() = runBlocking {
        val calls = mutableListOf<String>()
        val e = failsWith(strategy(listOf(model("blank"), model("ok")), calls) { id -> if (id == "blank") "  " else "好" })
        assertEquals(listOf("blank"), calls)
        assertTrue(e.attempts.single().error is CorrectionError.EmptyResponse)
    }

    @Test
    fun `the strategy of the failed model's own group decides`() = runBlocking {
        val calls = mutableListOf<String>()
        // Sub group on Always, primary on default: the sub member's network
        // error moves on at once, the primary member's is retried and stops.
        failsWith(strategy(listOf(always("s"), model("p", "primary")), calls) { throw network })
        assertEquals(listOf("s", "p", "p", "p", "p"), calls)
    }

    @Test
    fun `decide mirrors the chat's rule`() {
        val d = CorrectionFallback::decide
        val def = FallbackStrategy.default
        assertEquals(CorrectionFallback.Action.NEXT_MODEL, d(network, FallbackStrategy.always, 0, 3))
        assertEquals(CorrectionFallback.Action.NEXT_MODEL, d(CorrectionError.EmptyResponse, FallbackStrategy.always, 0, 3))
        assertEquals(CorrectionFallback.Action.RETRY_SAME, d(network, def, 2, 3))
        assertEquals(CorrectionFallback.Action.STOP, d(network, def, 3, 3))
        assertEquals(CorrectionFallback.Action.RETRY_SAME, d(LLMError.TransientError("x", 502), def, 0, 3))
        assertEquals(CorrectionFallback.Action.NEXT_MODEL, d(LLMError.TransientError("x", 502), def, 3, 3))
        assertEquals(CorrectionFallback.Action.NEXT_MODEL, d(creditTooLow, def, 0, 3))
        assertEquals(CorrectionFallback.Action.NEXT_MODEL, d(LLMError.ProviderError("[503] gone"), def, 0, 3))
        assertEquals(CorrectionFallback.Action.STOP, d(IllegalStateException("no credential"), def, 0, 3))
    }

    @Test
    fun `the chain retry ladder is the chat's`() {
        assertEquals(listOf(1000L, 2000L, 4000L), CorrectionFallback.SAME_MODEL_RETRY_DELAYS_MS)
    }

    // ── time: 15 s per call, not per correction ──────────────────────────

    @Test
    fun `a call past its budget moves on to the next model, whatever the strategy`() = runBlocking {
        val calls = mutableListOf<String>()
        val s = strategy(listOf(model("hung"), model("ok")), calls, budgetMs = 200) { id ->
            if (id == "hung") delay(60_000)
            "好"
        }
        val t0 = System.currentTimeMillis()
        val outcome = s.correct("x", emptyList(), ConversationContext.EMPTY, "zh")
        assertEquals("好", outcome.correctedText)
        assertEquals("sub+fallback2", outcome.modelGroupUsed)
        assertTrue(System.currentTimeMillis() - t0 < 5_000)
    }

    @Test
    fun `a blocking call that ignores cancellation is still cut at the budget`() = runBlocking {
        val calls = mutableListOf<String>()
        val s = strategy(listOf(model("blocking"), model("ok")), calls, budgetMs = 200) { id ->
            @Suppress("BlockingMethodInNonBlockingContext")
            if (id == "blocking") Thread.sleep(3_000)
            "好"
        }
        val t0 = System.currentTimeMillis()
        assertEquals("好", s.correct("x", emptyList(), ConversationContext.EMPTY, "zh").correctedText)
        assertTrue("took ${System.currentTimeMillis() - t0}ms", System.currentTimeMillis() - t0 < 2_000)
    }

    @Test
    fun `the budget is per call, so the chain may run past one budget`() = runBlocking {
        val calls = mutableListOf<String>()
        // Each call takes 300 ms under a 400 ms budget; the first two fail.
        val s = strategy(listOf(model("a"), model("b"), model("c")), calls, budgetMs = 400) { id ->
            delay(300)
            if (id == "c") "好" else throw creditTooLow
        }
        val t0 = System.currentTimeMillis()
        assertEquals("好", s.correct("x", emptyList(), ConversationContext.EMPTY, "zh").correctedText)
        assertTrue(System.currentTimeMillis() - t0 >= 800)
        assertEquals(15_000L, VoiceCorrectionConfig.CORRECTION_BUDGET_MS)
    }

    @Test
    fun `every model timing out reads as timed out`() = runBlocking {
        val s = strategy(listOf(model("a"), model("b")), mutableListOf(), budgetMs = 100) { delay(60_000); "unused" }
        val suggestion = engine(s).correct("我在背景")
        assertEquals("llm_error:ModelTimedOut", suggestion.rejectedReason)
        assertEquals(R.string.agent_error_timed_out, CorrectionFailureReason.res(suggestion))
    }

    @Test
    fun `at most three models are tried`() = runBlocking {
        val calls = mutableListOf<String>()
        val s = strategy((1..5).map { model("m$it") }, calls) { throw creditTooLow }
        runCatching { s.correct("x", emptyList(), ConversationContext.EMPTY, "zh") }
        assertEquals(CorrectionFallback.MAX_ATTEMPTS, calls.size)
        assertEquals(3, CorrectionFallback.MAX_ATTEMPTS)
    }

    @Test
    fun `cancellation stops at once`() = runBlocking {
        val calls = mutableListOf<String>()
        try {
            CorrectionFallback.run(listOf(model("a"), model("b"))) { m ->
                calls += m.entry.model.id
                throw CancellationException("budget")
            }
            fail("expected cancellation")
        } catch (_: CancellationException) {
        }
        assertEquals(listOf("a"), calls)
    }

    @Test
    fun `no candidate means no model available`() = runBlocking {
        val suggestion = engine(strategy(emptyList(), mutableListOf()) { "unused" }).correct("我在背景")
        assertEquals("llm_error:NoModelAvailable", suggestion.rejectedReason)
        // Not a recognisable outage: the panel says only "failed".
        assertNull(CorrectionFailureReason.res(suggestion))
    }

    // ── order ────────────────────────────────────────────────────────────

    @Test
    fun `sub members first, then primary members not already listed`() {
        val a = entry("a")
        val b = entry("b")
        val c = entry("c")
        val ordered = CorrectionFallback.order(
            sub = listOf(a, b),
            primary = listOf(b, c),
            subStrategy = FallbackStrategy.always,
        )
        assertEquals(listOf("a", "b", "c"), ordered.map { it.entry.model.id })
        assertEquals(listOf("sub", "sub", "primary"), ordered.map { it.groupKind })
        assertEquals(
            listOf(FallbackStrategy.always, FallbackStrategy.always, FallbackStrategy.default),
            ordered.map { it.fallbackStrategy },
        )
    }

    @Test
    fun `the voice panel shows the reason`() {
        val src = ProductionSources.read("ui/chat/voice/InlineVoiceInputPanel.kt")
        assertTrue(src.contains("CorrectionFailureReason.res(suggestion)"))
    }

    @Test
    fun `the voice panel drops a correction for text that is no longer on screen`() {
        // [T-voice-correction-stale-result] With per-model budgets a result can
        // land minutes later; applied blindly it overwrote newer dictation or
        // put a sent message back in the composer.
        val src = ProductionSources.read("ui/chat/voice/InlineVoiceInputPanel.kt")
        val run = src.substringAfter("fun runCorrection() {").substringBefore("\n    fun stopCapture()")
        val guard = "if (transcript.trim() != text || VoiceSendGate.generation != startGeneration) {"
        assertTrue(run.contains("val startGeneration = VoiceSendGate.generation"))
        assertTrue(run.indexOf(guard) in 0 until run.indexOf("setTranscript(suggestion.corrected)"))
        assertTrue("before the result toasts too", run.indexOf(guard) < run.indexOf("R.string.voice_correction_none_needed"))
    }
}
