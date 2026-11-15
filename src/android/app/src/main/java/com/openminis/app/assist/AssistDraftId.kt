package com.openminis.app.assist

internal const val ASSIST_DRAFT_PREFIX = "__new__assist-"

/** Draft id for one assist gesture: the hook re-delivers the same [triggerId]. */
internal fun assistDraftIdFor(triggerId: Long): String = "$ASSIST_DRAFT_PREFIX$triggerId"

/**
 * Route this assist entry should land on, or null when nothing names one
 * (caller mints a fresh draft).
 *
 * Identity is resolved here, before composition, so recomposition / Activity
 * re-create never mints another draft — a second draft remounts ChatScreen and
 * churns SessionActivityTracker into startForegroundService()/stopService().
 *
 * @param duplicate this delivery repeats a gesture already handled; reuse
 * whatever this Activity was showing instead of minting.
 * @param triggerId hook gesture id; when present the route must not depend on
 * saved state (the id survives process death, saved state may not).
 */
internal fun resolveAssistDraftSessionId(
    triggerId: Long,
    carriedSessionId: String?,
    duplicate: Boolean,
): String? = when {
    // Same gesture already handled: stay where it is (draft or already-promoted chat).
    duplicate && carriedSessionId != null -> carriedSessionId
    // New gesture with a hook id: deterministic draft, identical on every re-delivery.
    triggerId > 0L -> assistDraftIdFor(triggerId)
    else -> null
}
