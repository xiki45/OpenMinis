package com.openminis.app.ui.chat

/**
 * [T-android-fallback-pulse-replay] Should the red "model switched" pulse play?
 *
 * The chat title bar flashes red three times when a model-group fallback
 * swaps the session onto a different model. The signal behind it,
 * `ChatViewModel.fallbackTrigger`, is a monotonic counter that is never reset,
 * and ChatViewModel instances are reused across visits to a session
 * (ChatViewModelStore caches up to 4 normal / 6 child VMs). A session that fell
 * back during an earlier turn therefore presents a non-zero counter the instant
 * its screen is composed again.
 *
 * Compose's `LaunchedEffect(key)` fires on first composition as well as on
 * change, so a "has it ever been non-zero" test is not enough — it replays the
 * pulse on every re-entry into a session that had already recovered onto a
 * working model, which users read as "it is switching models again" even though
 * the persisted binding restored the resolved model correctly and nothing was
 * happening. iOS avoids this because `.onChange(of:)` ignores the initial
 * value; [shouldPulse] reproduces that semantic explicitly.
 *
 * @param current the counter's value now.
 * @param baseline its value when this screen last began observing it.
 */
internal fun shouldPulse(current: Int, baseline: Int): Boolean = current > baseline

/**
 * [T-navbar-failed-tint-residue] What the pulse drives: Compose's Animatable in
 * production ([asPulseAlpha]), a fake in tests.
 */
internal interface PulseAlpha {
    val value: Float
    suspend fun animateTo(target: Float)
    suspend fun snapTo(target: Float)
}

/** Red flashes per fallback (iOS: three). */
internal const val FALLBACK_PULSE_COUNT = 3

/** Half of one flash, ms: fade in, then the same back out. */
internal const val FALLBACK_PULSE_HALF_MS = 350

/**
 * [T-navbar-failed-tint-residue] One run of the title-bar pulse, started by
 * the screen whenever its view model or `fallbackTrigger` changes.
 *
 * The red box behind the title is `0.35 * alpha`, and nothing but this pulse
 * ever writes alpha. A run that is cancelled mid-flash - its key changed
 * because the screen was re-bound to another chat's view model, which happens
 * in place in the tablet two-pane layout - leaves alpha wherever the fade had
 * got to. The old code then started a run that saw no new fallback and
 * returned at once, so that value stayed: a pink box behind the title for
 * good, carried into every chat opened in the same pane, and immune to taps.
 * Every run therefore starts by snapping alpha back to 0, whether or not it
 * goes on to pulse, so the tint can never outlive the run that set it.
 */
internal suspend fun runFallbackPulse(current: Int, baseline: Int, alpha: PulseAlpha) {
    if (alpha.value != 0f) alpha.snapTo(0f)
    if (!shouldPulse(current, baseline)) return
    repeat(FALLBACK_PULSE_COUNT) {
        alpha.animateTo(1f)
        alpha.animateTo(0f)
    }
}

/** [T-navbar-failed-tint-residue] Adapts Compose's Animatable to [PulseAlpha]. */
internal fun androidx.compose.animation.core.Animatable<Float, androidx.compose.animation.core.AnimationVector1D>.asPulseAlpha(): PulseAlpha {
    val anim = this
    return object : PulseAlpha {
        override val value: Float get() = anim.value
        override suspend fun animateTo(target: Float) {
            anim.animateTo(target, animationSpec = androidx.compose.animation.core.tween(FALLBACK_PULSE_HALF_MS))
        }
        override suspend fun snapTo(target: Float) {
            anim.snapTo(target)
        }
    }
}
