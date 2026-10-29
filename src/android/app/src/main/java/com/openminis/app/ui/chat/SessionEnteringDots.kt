package com.openminis.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.openminis.app.ui.components.decorativePhase
import com.openminis.app.ui.components.decorativePingPong
import com.openminis.app.ui.components.rememberDecorativeTick

/**
 * [T-android-session-entering-dots] Whether the chat area shows the entry
 * dots: a real session whose rows have not been published yet. Mirrors the
 * iOS gate in AIChatView (`(isLoadingSession || enteringUnloaded) &&
 * messages.isEmpty`), where entering means "not loaded yet":
 * - a new chat has nothing to load;
 * - once loadSession has finished, an empty list is an empty session, and a
 *   later reload with rows on screen shows nothing (the iOS user report of a
 *   grey "…" over content that read as a glitch);
 * - safe mode skips loadSession, which then never reports loaded, so the
 *   dots would never leave.
 */
internal fun showSessionEnteringIndicator(
    isDraft: Boolean,
    sessionLoaded: Boolean,
    messagesEmpty: Boolean,
    safeMode: Boolean,
): Boolean = !isDraft && !sessionLoaded && messagesEmpty && !safeMode

/**
 * [T-android-session-entering-dots] The three-dot "…" shown while entering a
 * session, the Android form of iOS SessionLoadingCard / LoadingDotsView: three
 * accent dots breathing between 0.55x / 0.3 alpha and 1x / 0.95 alpha, 0.5 s
 * each way, each one 0.16 s behind the previous.
 *
 * Driven by the shared decorative tick and applied through graphicsLayer only,
 * like [BouncingDots], so it costs a layer update per tick rather than a
 * recomposition per frame.
 */
@Composable
internal fun SessionEnteringDots(modifier: Modifier = Modifier) {
    val tick = rememberDecorativeTick()
    val color = MaterialTheme.colorScheme.primary
    Box(modifier = modifier.height(24.dp), contentAlignment = Alignment.Center) {
        Row(horizontalArrangement = Arrangement.spacedBy(DOT_SIZE * 0.7f)) {
            repeat(3) { i ->
                Box(
                    modifier = Modifier
                        .size(DOT_SIZE)
                        .graphicsLayer {
                            val p = SessionEnteringDots.level(tick.value, i)
                            val scale = SessionEnteringDots.MIN_SCALE + (1f - SessionEnteringDots.MIN_SCALE) * p
                            scaleX = scale
                            scaleY = scale
                            alpha = SessionEnteringDots.MIN_ALPHA +
                                (SessionEnteringDots.MAX_ALPHA - SessionEnteringDots.MIN_ALPHA) * p
                        }
                        .background(color, CircleShape),
                )
            }
        }
    }
}

private val DOT_SIZE = 8.dp

internal object SessionEnteringDots {
    /** One full grow-and-shrink: iOS easeInOut(0.5).repeatForever(autoreverses). */
    const val PERIOD_MS = 1_000
    const val STAGGER_MS = 160
    const val MIN_SCALE = 0.55f
    const val MIN_ALPHA = 0.3f
    const val MAX_ALPHA = 0.95f

    /** Delay before the dots fade in, so a session that opens at once never flashes them. */
    const val ENTER_DELAY_MS = 120
    const val ENTER_MS = 200

    /** iOS `.animation(.easeOut(duration: 0.28))` on the card's exit. */
    const val EXIT_MS = 280

    /** 0..1 eased level of dot [index] at [nanos]. */
    fun level(nanos: Long, index: Int): Float {
        val x = decorativePingPong(decorativePhase(nanos, PERIOD_MS, index * STAGGER_MS))
        return x * x * (3f - 2f * x) // smoothstep, the ease-in-out shape
    }
}
