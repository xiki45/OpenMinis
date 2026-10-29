package com.openminis.app.ui.chat

import java.util.concurrent.atomic.AtomicLong

/**
 * [T-android-stop-late-write] Which agent turn is live right now, so a write
 * that arrives after Stop can be recognised and dropped.
 *
 * Field report (2026-10-02): after Stop, a shell pill kept its red Stop square
 * and the tool bar kept saying "Live" although the command was already dead.
 * SIGKILL makes proot print "terminated with signal 9" on stderr; the command's
 * still-open reader delivered that line to executeShellCommand's lineCallback,
 * which re-published the turn-local block snapshot (status RUNNING) through
 * updateAssistantMessage(isStreaming = true). That re-created a streaming
 * overlay entry for a message whose turn was over, and since no stream-end
 * path would ever drain it, the overlay painted RUNNING over the cancelled
 * message for good.
 *
 * Every turn now carries a generation number. Cancel bumps it, and a turn that
 * ends clears it only if it is still its own (a resume may already have
 * started the next one). The overlay accepts a streaming write only for the
 * live turn's current assistant id, and a shell line is dropped once its
 * turn's generation is gone.
 *
 * Thread-safe: shell readers call [isLive] from their own threads.
 */
internal class LiveTurnGuard {
    private data class Live(val generation: Long, val assistantId: String?)

    private val generation = AtomicLong(0)

    @Volatile private var live: Live? = null

    /** A turn starts; returns its token. Any earlier turn stops being live. */
    @Synchronized
    fun begin(): Long {
        val gen = generation.incrementAndGet()
        live = Live(gen, null)
        return gen
    }

    /** The turn [gen] writes into [assistantId] from now on (first bubble, or after a seal). */
    @Synchronized
    fun markAssistant(gen: Long, assistantId: String) {
        if (live?.generation == gen) live = Live(gen, assistantId)
    }

    /** The turn [gen] ended by itself. Compare-and-clear: a newer turn stays live. */
    @Synchronized
    fun end(gen: Long) {
        if (live?.generation == gen) live = null
    }

    /**
     * Stop: the live turn, if any, is over from this instant. Returns the
     * assistant id it was writing, for logging.
     */
    @Synchronized
    fun cancel(): String? {
        val was = live?.assistantId
        generation.incrementAndGet()
        live = null
        return was
    }

    fun isLive(gen: Long): Boolean = live?.generation == gen

    /** The live turn's token, or null when no turn is running. */
    fun liveGeneration(): Long? = live?.generation

    /** The bubble the live turn is writing, or null when no turn is running. */
    fun liveAssistantId(): String? = live?.assistantId

    /** Whether a streaming (overlay) write for [assistantId] belongs to the live turn. */
    fun acceptsStreamingWrite(assistantId: String): Boolean {
        val l = live ?: return false
        return l.assistantId == assistantId
    }
}

/**
 * [T-android-stop-late-write] Pure rules for healing tool blocks that claim to
 * be in flight when no turn is running them, and for the per-tool Stop.
 */
internal object InFlightToolHeal {

    /** The statuses that draw a spinner and a Stop square. */
    fun isInFlight(status: ToolBlockStatus?): Boolean =
        status == ToolBlockStatus.RUNNING ||
            status == ToolBlockStatus.STREAMING ||
            status == ToolBlockStatus.PENDING

    private fun isTerminal(status: ToolBlockStatus?): Boolean =
        status != null && !isInFlight(status)

    data class Healed(
        val messages: List<ChatMessage>,
        /** (messageId, blockId, toolName) of every tool_use block that was flipped. */
        val toolUses: List<Triple<String, String, String>>,
        val blockCount: Int,
    )

    /**
     * Flip every in-flight block to CANCELLED in every assistant message except
     * those in [keep] (the bubble a running turn is still writing, and any the
     * caller heals itself).
     *
     * The old cleanup looked only at the LAST assistant message. After a resume
     * the stuck block sits in an earlier one, so it was never repaired.
     * Blocks for which [ownLifecycle] is true are left alone: a background sub
     * agent's block is legitimately RUNNING after its parent turn ended, and
     * OrphanedRunningToolGate already judges it against its job.
     * Returns the same list instance when nothing changed.
     */
    fun sweep(
        messages: List<ChatMessage>,
        keep: Set<String>,
        ownLifecycle: (AssistantBlock) -> Boolean = { false },
    ): Healed {
        var out: MutableList<ChatMessage>? = null
        val toolUses = mutableListOf<Triple<String, String, String>>()
        var blocks = 0
        fun stale(b: AssistantBlock) = isInFlight(b.toolStatus) && !ownLifecycle(b)
        for ((i, m) in messages.withIndex()) {
            if (m.role != "assistant" || m.id in keep) continue
            if (m.toolBlocks.none { stale(it) }) continue
            val fixed = m.toolBlocks.map { b ->
                if (!stale(b)) b else {
                    blocks++
                    if (b.kind == "tool_use") toolUses += Triple(m.id, b.id, b.toolName)
                    b.copy(toolStatus = ToolBlockStatus.CANCELLED)
                }
            }
            if (out == null) out = messages.toMutableList()
            out[i] = m.copy(toolBlocks = fixed, isStreaming = false, isAwaitingModelResponse = false)
        }
        return Healed(out ?: messages, toolUses, blocks)
    }

    /**
     * Fold an overlay's blocks into the canonical ones without letting the
     * overlay undo a finished status: a block the canonical message already
     * holds as SUCCESS / FAILED / CANCELLED / TIMEOUT keeps that status even if
     * the overlay still says RUNNING. That regression is exactly how a later
     * cancel's flushAllStreamingDeltas copied a stale RUNNING back into history.
     */
    fun mergeOverlayBlocks(
        canonical: List<AssistantBlock>,
        overlay: List<AssistantBlock>,
    ): List<AssistantBlock> {
        if (canonical.isEmpty()) return overlay
        val done = HashMap<String, ToolBlockStatus>()
        for (b in canonical) if (isTerminal(b.toolStatus)) done[b.id] = b.toolStatus!!
        if (done.isEmpty()) return overlay
        return overlay.map { b ->
            val kept = done[b.id]
            if (kept != null && isInFlight(b.toolStatus)) b.copy(toolStatus = kept) else b
        }
    }

    enum class StopAction {
        /** The block is in flight in the running turn: stop that turn (today's Stop). */
        STOP_TURN,
        /** A leftover from a turn that is over: cancel just this block, leave any stream alone. */
        CANCEL_STALE_BLOCK,
        /** Nothing in flight to stop. */
        NONE,
    }

    /**
     * What a tool's own Stop does.
     *
     * The pill's red square used to call the session-wide cancelStream(), so a
     * stuck pill from an earlier turn stopped whatever reply was live when it
     * was tapped. Now only a block of the running turn can stop the turn.
     *
     * @param liveBlocks the running turn's blocks (its overlay, else its
     *   canonical message); empty when no turn is running.
     * @param blockStatus the tapped block's current displayed status.
     */
    fun stopAction(
        blockId: String,
        isStreaming: Boolean,
        liveBlocks: List<AssistantBlock>,
        blockStatus: ToolBlockStatus?,
    ): StopAction {
        if (isStreaming && liveBlocks.any { it.id == blockId && isInFlight(it.toolStatus) }) {
            return StopAction.STOP_TURN
        }
        return if (isInFlight(blockStatus)) StopAction.CANCEL_STALE_BLOCK else StopAction.NONE
    }

    /**
     * Which of [toolUseIds] may get a CANCELLED tool_result appended to the
     * stored history ([rowsParts]: each stored row's parts JSON, in order).
     *
     * Only an unanswered call whose tool_use is in the LAST stored row: a
     * result appended after later rows would sit away from its call, and the
     * provider rejects a tool_result that does not follow its tool_use.
     */
    fun appendableCancelResults(rowsParts: List<String>, toolUseIds: Collection<String>): Set<String> {
        if (toolUseIds.isEmpty() || rowsParts.isEmpty()) return emptySet()
        val answered = HashSet<String>()
        val useRow = HashMap<String, Int>()
        for ((row, json) in rowsParts.withIndex()) {
            val arr = runCatching { org.json.JSONArray(json) }.getOrNull() ?: continue
            for (i in 0 until arr.length()) {
                val part = arr.optJSONObject(i) ?: continue
                val id = part.optJSONObject("value")?.optString("toolUseId", "").orEmpty()
                if (id.isEmpty()) continue
                val key = com.openminis.app.provider.ToolPairing.key(id)
                when (part.optString("type")) {
                    "toolResult" -> answered += key
                    "toolUse" -> useRow[key] = row
                }
            }
        }
        val last = rowsParts.lastIndex
        return toolUseIds.filterTo(HashSet()) { id ->
            val key = com.openminis.app.provider.ToolPairing.key(id)
            key !in answered && useRow[key] == last
        }
    }

    /** Set [blockId] to CANCELLED wherever it is in flight; same instance when absent. */
    fun cancelBlock(blocks: List<AssistantBlock>, blockId: String): List<AssistantBlock> {
        if (blocks.none { it.id == blockId && isInFlight(it.toolStatus) }) return blocks
        return blocks.map {
            if (it.id == blockId && isInFlight(it.toolStatus)) it.copy(toolStatus = ToolBlockStatus.CANCELLED) else it
        }
    }
}
