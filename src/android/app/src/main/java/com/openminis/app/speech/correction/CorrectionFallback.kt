package com.openminis.app.speech.correction

import android.util.Log
import com.openminis.app.data.model.FallbackStrategy
import com.openminis.app.data.model.LLMError
import com.openminis.app.data.model.ModelEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.cancellation.CancellationException

/**
 * A model a correction may use, the group it came from ("sub" / "primary"), and
 * that group's fallback strategy - which decides what a failure on it does.
 */
data class CorrectionModel(
    val entry: ModelEntry,
    val groupKind: String,
    val fallbackStrategy: FallbackStrategy = FallbackStrategy.default,
)

/**
 * [T-voice-correction-fallback] Try a correction on each model in turn until one
 * answers. Port of iOS `CorrectionFallback` (Providers/Voice/CorrectionStrategy.swift).
 *
 * Before this a correction called ONE model - the sub group's first available
 * member, else the primary group's - and on its first error returned the
 * original text. A user log (iOS, 2026-10-15) shows the cost: every manual
 * correction failed with Anthropic's "credit balance is too low" while the chat
 * on the same device moved to the next model of the group (`🔀ROUTE … fallbackable
 * error`) and title generation retried on its next candidate.
 *
 * Reuses the chat's pieces rather than a parallel mechanism (owner decision,
 * 2026-10-15): the candidates are the groups' available members in router
 * order ([com.openminis.app.ui.chat.TitleCandidates.expandGroup]), and what a
 * failure does is the agent loop's rule for the failed model's group - see
 * [decide].
 *
 * Time: each CALL has its own [VoiceCorrectionConfig.CORRECTION_BUDGET_MS]
 * (15 s), not the whole chain. A call still running then is abandoned and the
 * next model is tried, whatever the strategy - a hung model must not cost the
 * correction. The chain is bounded by [MAX_ATTEMPTS] models.
 */
object CorrectionFallback {
    private const val TAG = "CorrectionFallback"

    /** Models tried per correction, as iOS. */
    const val MAX_ATTEMPTS = 3

    /** The chat's same-model retry ladder (1 s, 2 s, 4 s). */
    val SAME_MODEL_RETRY_DELAYS_MS: List<Long> =
        com.openminis.app.ui.chat.ChatViewModel.AUTO_RETRY_DELAYS_SEC.map { it * 1000L }

    /** Sub members first, then primary members not already listed. */
    fun order(
        sub: List<ModelEntry>,
        primary: List<ModelEntry>,
        subStrategy: FallbackStrategy = FallbackStrategy.default,
        primaryStrategy: FallbackStrategy = FallbackStrategy.default,
    ): List<CorrectionModel> {
        val seen = HashSet<String>()
        val out = mutableListOf<CorrectionModel>()
        for (e in sub) if (seen.add(e.id)) out += CorrectionModel(e, "sub", subStrategy)
        for (e in primary) if (seen.add(e.id)) out += CorrectionModel(e, "primary", primaryStrategy)
        return out
    }

    /** What a failed call leads to. */
    enum class Action { RETRY_SAME, NEXT_MODEL, STOP }

    /**
     * The agent loop's rule (ChatViewModel's stream-failure branch), applied to
     * one correction call:
     * - `Always Fall Back`: any error moves to the next model at once.
     * - Otherwise a network error or a transient error (incl. a 5xx carried as
     *   one) is first retried on the same model along the chat's ladder; then,
     *   and for every other error, the next model is tried only for a rate
     *   limit, a 5xx or an [LLMError.isFallbackable] error (rejected key,
     *   provider rejection such as an empty balance, certificate) - anything
     *   else stops with the original text, as the chat stops and shows it.
     * A call that ran past its budget is not decided here: it always moves on.
     */
    fun decide(error: Throwable, strategy: FallbackStrategy, retriesSoFar: Int, maxRetries: Int): Action {
        if (strategy == FallbackStrategy.always) return Action.NEXT_MODEL
        val err = error as? LLMError
        val transient = err is LLMError.NetworkError || err is LLMError.TransientError
        if (transient && retriesSoFar < maxRetries) return Action.RETRY_SAME
        val worthSwitching = err is LLMError.RateLimited || err?.isHttpServerError == true || err?.isFallbackable == true
        return if (worthSwitching) Action.NEXT_MODEL else Action.STOP
    }

    /** How the chat's group fallback would treat [t] - for the log line. */
    fun routeClass(t: Throwable): String {
        val err = t as? LLMError ?: return "other"
        return when {
            err.isFallbackable || err.isHttpServerError -> "fallbackable"
            err.isRetryable -> "retryable"
            else -> "other"
        }
    }

    /** One failed attempt, for the final error and the log. */
    data class Attempt(val model: CorrectionModel, val error: Throwable)

    private sealed class Call<out R> {
        class Answered<R>(val value: R) : Call<R>()
        class Failed(val error: Throwable) : Call<Nothing>()
        object TimedOut : Call<Nothing>()
    }

    /**
     * One call bounded by [budgetMs]. The provider's HTTP call is BLOCKING
     * inside the coroutine, so a timeout around it alone does not cut it short
     * (measured: a 5 s budget returned after 9.5 s). The call runs detached and
     * only the await is timed; a call that overran is cancelled and its result
     * discarded.
     */
    private suspend fun <R> bounded(budgetMs: Long, block: suspend () -> R): Call<R> {
        val work = CoroutineScope(Dispatchers.IO).async { block() }
        try {
            val call = withTimeoutOrNull(budgetMs) {
                try {
                    Call.Answered(work.await())
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    Call.Failed(e)
                }
            }
            if (call == null) work.cancel()
            return call ?: Call.TimedOut
        } catch (e: CancellationException) {
            work.cancel()
            throw e
        }
    }

    /**
     * Run [attempt] on [models] (at most [maxAttempts]) and return the first
     * answer, the model that gave it and its 1-based position. When the chain
     * ends without one, throws [CorrectionError.AllModelsFailed] with each
     * failure; [CorrectionError.NoModelAvailable] when there was nothing to try.
     */
    suspend fun <R> run(
        models: List<CorrectionModel>,
        maxAttempts: Int = MAX_ATTEMPTS,
        callBudgetMs: Long = VoiceCorrectionConfig.CORRECTION_BUDGET_MS,
        retryDelaysMs: List<Long> = SAME_MODEL_RETRY_DELAYS_MS,
        attempt: suspend (CorrectionModel) -> R,
    ): Triple<R, CorrectionModel, Int> {
        val tries = models.take(maxAttempts)
        if (tries.isEmpty()) throw CorrectionError.NoModelAvailable
        val failures = mutableListOf<Attempt>()
        models@ for ((index, model) in tries.withIndex()) {
            val n = "${index + 1}/${tries.size}"
            val id = model.entry.model.id
            val more = index + 1 < tries.size
            var retries = 0
            while (true) {
                log(
                    "[VoiceCorrection][Correct] attempt $n model=$id group=${model.groupKind} " +
                        "strategy=${model.fallbackStrategy}" + (if (retries > 0) " retry=$retries" else ""),
                )
                when (val call = bounded(callBudgetMs) { attempt(model) }) {
                    is Call.Answered -> {
                        if (index > 0) {
                            log("[VoiceCorrection][Correct] succeeded on attempt $n model=$id after ${failures.size} failure(s)")
                        }
                        return Triple(call.value, model, index + 1)
                    }
                    is Call.TimedOut -> {
                        failures += Attempt(model, CorrectionError.ModelTimedOut(callBudgetMs))
                        log(
                            "[VoiceCorrection][Correct] attempt $n TIMEOUT model=$id after ${callBudgetMs}ms - " +
                                if (more) "switching to the next model" else "no model left",
                        )
                        continue@models
                    }
                    is Call.Failed -> {
                        val e = call.error
                        // A provider may surface our own cancellation as an error.
                        if (!currentCoroutineContext().isActive) throw CancellationException("correction cancelled")
                        val action = decide(e, model.fallbackStrategy, retries, retryDelaysMs.size)
                        val what = when {
                            action == Action.RETRY_SAME -> "retrying the same model in ${retryDelaysMs[retries]}ms"
                            action == Action.STOP -> "stopping (group strategy ${model.fallbackStrategy} does not fall back on this error)"
                            more -> "switching to the next model"
                            else -> "no model left"
                        }
                        log(
                            "[VoiceCorrection][Correct] attempt $n FAILED model=$id group=${model.groupKind} " +
                                "class=${routeClass(e)} error=${e.javaClass.simpleName}: ${e.message?.take(160)} - $what",
                        )
                        when (action) {
                            Action.RETRY_SAME -> {
                                delay(retryDelaysMs[retries])
                                retries++
                            }
                            Action.NEXT_MODEL -> {
                                failures += Attempt(model, e)
                                continue@models
                            }
                            Action.STOP -> {
                                failures += Attempt(model, e)
                                throw CorrectionError.AllModelsFailed(failures)
                            }
                        }
                    }
                }
            }
        }
        throw CorrectionError.AllModelsFailed(failures)
    }

    private fun log(line: String) {
        Log.i(TAG, line)
    }
}

/**
 * [T-voice-correction-fallback] The short reason the voice panel shows after a
 * failed correction, as a string resource. Reuses the localized labels and the
 * classifier the sub-agent cards use ([com.openminis.app.agent.jobs.HelperRunner.errorKind]):
 * "credit balance is too low" reads as quota exhausted, a 429 as rate limited,
 * a model that ran past its budget as timed out.
 * Null when the cause is not recognisable - the panel then shows only "failed",
 * because a wrong reason is worse than none.
 */
object CorrectionFailureReason {
    fun res(suggestion: CorrectionSuggestion): Int? {
        val reason = suggestion.rejectedReason ?: return null
        return when {
            reason.startsWith("llm_error:") ->
                com.openminis.app.agent.jobs.HelperRunner.errorKind(suggestion.failureDetail ?: reason)
                    ?.takeIf { it != com.openminis.app.agent.jobs.HelperRunner.ErrorKind.CANCELLED }
                    ?.res
            else -> null
        }
    }
}
