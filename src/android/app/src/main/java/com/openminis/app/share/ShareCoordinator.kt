package com.openminis.app.share

import android.content.Context
import com.openminis.app.logging.AppLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Mirrors iOS Shared/ShareCoordinator.swift. Singleton bridge between
 * ShareReceiverActivity (producer, lives in a different Activity instance
 * before re-launching MainActivity) and ChatScreen (consumer, lives inside
 * MainActivity). Holds the [PendingShare] in memory with a 30s TTL —
 * long enough for the launch flow to land in a chat session, short enough
 * that a stale share doesn't surprise the user weeks later.
 */
object ShareCoordinator {
    private const val TAG = "ShareCoordinator"
    // [T-android-share-buffer-merge] 30s → 300s, aligned with
    // LAUNCH_MAX_AGE_MS below and iOS a51e7255. The old 30s window expired
    // whenever the user lingered on the session list after sharing, so a
    // perfectly good share was discarded before they ever opened a chat.
    private const val BUFFER_TTL_MS = 300_000L
    private const val LAUNCH_MAX_AGE_MS = 5L * 60 * 1000

    /**
     * [T-android-share-routing] [targetSessionId] is the chat this share is
     * addressed to (mirrors iOS PendingShareBuffer.targetSessionId). Null
     * until the UI routes it ([addressTo]); an unaddressed share can be
     * consumed by nobody. It used to be drained by whichever chat composed
     * next - an unrelated or busy one included - and when the list was
     * showing nothing drained it at all.
     */
    private data class Buffered(
        val share: PendingShare,
        val bufferedAtMs: Long,
        val targetSessionId: String? = null,
    )

    @Volatile private var buffer: Buffered? = null

    private val _bufferVersion = MutableStateFlow(0)
    /**
     * Increments every time a new share is buffered. ChatScreen observes
     * this via collectAsState so a warm-start (user already inside a
     * session) injects too. Cold starts pick up the buffer on first
     * composition since the version is non-zero by then.
     */
    val bufferVersion: StateFlow<Int> = _bufferVersion.asStateFlow()

    /**
     * Called from MainActivity.onCreate / onNewIntent when the share
     * receiver re-launched MainActivity with the `shared_content` extra.
     * Loads from prefs, drops if older than [LAUNCH_MAX_AGE_MS] (5 min,
     * mirrors iOS launch check), stores in the in-memory buffer, clears
     * prefs.
     */
    @Synchronized
    fun processPendingShare(context: Context) {
        val pending = SharedShareStore.loadPendingShare(context) ?: run {
            AppLogger.info(TAG, "[Share] processPendingShare: no pending share")
            return
        }
        val ageMs = System.currentTimeMillis() - pending.timestampMs
        if (ageMs > LAUNCH_MAX_AGE_MS) {
            AppLogger.info(TAG, "[Share] processPendingShare: stale (age=${ageMs}ms), discarding")
            SharedShareStore.clearPendingShare(context)
            // [T-android-share-buffer-merge] Preserve files still referenced by a
            // live in-memory buffer. Discarding THIS stale record must not delete
            // an earlier share's attachments that are still waiting to be
            // injected — with a 300s buffer TTL that overlap is entirely
            // reachable.
            SharedShareStore.cleanSharedFiles(context, keep = liveBufferFileNames())
            return
        }
        SharedShareStore.clearPendingShare(context)
        // [T-android-share-buffer-merge] Append into an unconsumed buffer
        // instead of replacing it (mirrors iOS a51e7255). The old
        // single-slot `buffer = Buffered(...)` silently destroyed a share
        // that had been loaded but not yet injected into the chat — the
        // exact "shared two screenshots, only the second arrived" report.
        val now = System.currentTimeMillis()
        val existing = buffer
        val mergedShare = if (existing != null && now - existing.bufferedAtMs <= BUFFER_TTL_MS) {
            val seen = LinkedHashMap<Pair<PendingShare.Item.Kind, String>, PendingShare.Item>()
            for (item in existing.share.items + pending.items) {
                seen[item.kind to item.value] = item
            }
            AppLogger.info(
                TAG,
                "[Share] processPendingShare: merging ${existing.share.items.size} + " +
                    "${pending.items.size} -> ${seen.size} item(s)",
            )
            PendingShare(seen.values.toList(), pending.timestampMs)
        } else {
            AppLogger.info(TAG, "[Share] processPendingShare: buffering ${pending.items.size} item(s)")
            pending
        }
        // bufferedAt renews on merge so the combined buffer gets a full TTL.
        // [T-android-share-routing] Every new share is routed afresh (target
        // cleared), merged items included: the chat an earlier share was
        // addressed to may have been closed before it took it, and the user
        // is now looking at something else.
        buffer = Buffered(mergedShare, now)
        _bufferVersion.value = _bufferVersion.value + 1
        // [XSessionDiag] Hypothesis 2: this buffer is process-global, is NOT
        // addressed to any session, and MERGES rather than replaces — dedup is by
        // exact (kind, value), so near-duplicate fragments of the same text all
        // survive and concatenate. That is the shape of the reported corrupted
        // input (same sentence restarted, interleaved with its own translation).
        // Log every item's digest so a later report can be matched character-for-
        // character against what the model received. Fires once per share, not in
        // a loop over anything hot.
        logBufferState("buffered", mergedShare, now, bufferedAtMs = now)
    }

    /**
     * [XSessionDiag] Dump the buffer's contents with per-item digests.
     *
     * Truncates each value to [DIAG_VALUE_PREVIEW] chars: a share can carry a
     * whole article, and the diagnostic question is "which fragments merged
     * together", which the head of each item answers. Length is logged in full so
     * truncation never hides the real size.
     */
    private fun logBufferState(
        phase: String,
        share: PendingShare,
        nowMs: Long,
        bufferedAtMs: Long,
    ) {
        val items = share.items.mapIndexed { i, item ->
            val v = item.value
            val preview = v.take(DIAG_VALUE_PREVIEW).replace("\n", "\\n")
            "#$i(${item.kind.name} len=${v.length} \"$preview\"" +
                if (v.length > DIAG_VALUE_PREVIEW) "…\")" else "\")"
        }
        // ttlRemaining is measured from bufferedAtMs, matching consumeBuffer's own
        // expiry test — using share.timestampMs here would report a different
        // number than the one that actually governs the drop.
        AppLogger.info(
            TAG,
            "[XSessionDiag] share/$phase: items=${share.items.size} " +
                "shareTimestampMs=${share.timestampMs} bufferedAtMs=$bufferedAtMs now=$nowMs " +
                "ageMs=${nowMs - bufferedAtMs} ttlRemainingMs=${BUFFER_TTL_MS - (nowMs - bufferedAtMs)} " +
                "-> ${items.joinToString(" | ")}",
        )
    }

    /** Head-of-value length used by [logBufferState]; diagnostic only. */
    private const val DIAG_VALUE_PREVIEW = 100

    /**
     * Attachment file names referenced by the live in-memory buffer, if any.
     * Used to protect them from a directory-wide cleanup triggered by an
     * unrelated stale record. Empty when nothing is buffered.
     */
    private fun liveBufferFileNames(): Set<String> =
        buffer?.share?.items
            ?.filter { it.kind == PendingShare.Item.Kind.ATTACHMENT }
            ?.map { it.value }
            ?.toSet()
            ?: emptySet()

    /**
     * Consume the buffer (one-shot). Returns null if empty or expired.
     *
     * [T-android-share-buffer-merge] An expired buffer used to vanish with
     * only a log line — the user shared something, opened the app, and
     * nothing happened with no explanation. Now the discard surfaces a
     * toast. Mirrors iOS a51e7255's ShareFeedbackToast.
     */
    /**
     * [T-android-share-routing] True while a share is buffered but not yet
     * addressed to a chat - the UI's cue to route it ([addressTo]).
     */
    fun hasUnaddressedShare(): Boolean = buffer?.let { it.targetSessionId == null } == true

    /**
     * [T-android-share-routing] Address the buffered share to [sessionId] (the
     * visible chat, or a new draft opened for it). Only an unaddressed share
     * is addressed, so two routers racing (both chat routes composed during a
     * transition) cannot re-target it. Bumps [bufferVersion] so the target's
     * ChatScreen re-checks. Returns whether this call addressed it.
     */
    @Synchronized
    fun addressTo(sessionId: String): Boolean {
        val buf = buffer ?: return false
        if (buf.targetSessionId != null) return false
        buffer = buf.copy(targetSessionId = sessionId)
        AppLogger.info(TAG, "[Share] addressed ${buf.share.items.size} item(s) to session=${sessionId.take(16)}")
        _bufferVersion.value = _bufferVersion.value + 1
        return true
    }

    /**
     * [T-android-share-routing] Consume the share if it is addressed to
     * [sessionId]. Anything else - no share, unaddressed, or addressed to
     * another chat - returns null and leaves the buffer untouched.
     */
    @Synchronized
    fun consumeBuffer(context: Context, sessionId: String): PendingShare? {
        val buf = buffer ?: return null
        if (buf.targetSessionId != sessionId) return null
        val diagSessionId: String? = sessionId
        buffer = null
        val nowMs = System.currentTimeMillis()
        val ageMs = nowMs - buf.bufferedAtMs
        if (ageMs > BUFFER_TTL_MS) {
            AppLogger.info(TAG, "[Share] consumeBuffer: expired (age=${ageMs}ms)")
            AppLogger.info(
                TAG,
                "[XSessionDiag] share/consume-expired: " +
                    "consumer=${diagSessionId?.take(8) ?: "unknown"} " +
                    "items=${buf.share.items.size} ageMs=$ageMs ttlMs=$BUFFER_TTL_MS",
            )
            SharedShareStore.cleanSharedFiles(context)
            notifyExpired(context)
            return null
        }
        AppLogger.info(TAG, "[Share] consumeBuffer: ${buf.share.items.size} item(s)")
        // [XSessionDiag] The consumer is now always the addressed session
        // ([T-android-share-routing]); logged to keep the repro trail.
        logBufferState("consume", buf.share, nowMs, buf.bufferedAtMs)
        AppLogger.info(
            TAG,
            "[XSessionDiag] share/consumed-by: consumer=${diagSessionId?.take(8) ?: "unknown"} " +
                "items=${buf.share.items.size} ageMs=$ageMs",
        )
        return buf.share
    }

    /**
     * Surface the expiry to the user. Posted on the main looper because
     * consumeBuffer is reachable from a composition/IO context, and Toast
     * requires a Looper-backed thread.
     */
    private fun notifyExpired(context: Context) {
        runCatching {
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                runCatching {
                    android.widget.Toast.makeText(
                        context.applicationContext,
                        context.getString(com.openminis.app.R.string.share_expired_toast),
                        android.widget.Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }
    }
}
