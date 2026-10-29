package com.openminis.app.sandbox

import android.util.Log

/**
 * [T-shell-stall-yes-head] Starts agent shell processes with SIGPIPE unblocked.
 *
 * ART blocks SIGPIPE in every app thread (Runtime::BlockSignals), and
 * ProcessBuilder's child keeps the spawning thread's signal mask across
 * execve, through proot, into every guest process. Nothing on the way unblocks
 * it (busybox sh does not), so a writer whose reader had exited got EPIPE
 * instead of being killed. Most tools then exit on the error, but busybox
 * `yes STRING | head -c N` never notices it and spins forever: head exits,
 * yes keeps holding the merged stderr pipe open, and the shell_execute call
 * hangs until its timeout (15 min by default). Seen on a Pixel 4a as
 * "yes ... | head -c 50000 stuck for 6+ minutes"; reproduced on stock
 * Alpine 3.21 busybox by blocking SIGPIPE in the parent.
 *
 * The interactive terminal never had this problem: PtyBridge.forkExec clears
 * the mask in its own child. ProcessBuilder offers no such hook, so the mask
 * is cleared on the SPAWNING thread for the duration of start() and restored
 * right after. start() does not suspend, so the whole window stays on one
 * thread; the parent side only reads the exec-failure pipe, so no write of
 * this thread can raise SIGPIPE in the window. SIGQUIT / SIGUSR1 stay blocked
 * on purpose: they are process-directed, and an unblocked thread could steal
 * one meant for ART's signal catcher.
 */
internal object SpawnSignalMask {
    private const val TAG = "SpawnSignalMask"

    /** Bit set returned by [Native.unblockSigpipe]. */
    const val WAS_BLOCKED = 1
    const val DISPOSITION_IGNORED = 2

    internal interface Native {
        /** Bit set ([WAS_BLOCKED], [DISPOSITION_IGNORED]) or -errno. */
        fun unblockSigpipe(): Int

        /** 0 or -errno. */
        fun blockSigpipe(): Int
    }

    private object PtyNative : Native {
        override fun unblockSigpipe(): Int = PtyBridge.unblockSigpipe()
        override fun blockSigpipe(): Int = PtyBridge.blockSigpipe()
    }

    /** Swapped out in tests; the real one needs libpty_bridge. */
    @Volatile
    internal var native: Native = PtyNative

    @Volatile
    private var ignoredLogged = false

    /** Start [pb] with SIGPIPE unblocked in the child. */
    fun start(pb: ProcessBuilder): Process = withSigpipeUnblocked { pb.start() }

    /**
     * Run [block] with SIGPIPE unblocked on this thread, then restore the
     * previous mask. A native failure never stops the spawn: the command
     * then runs as it always did.
     */
    fun <T> withSigpipeUnblocked(block: () -> T): T {
        val state = try {
            native.unblockSigpipe()
        } catch (t: Throwable) {
            // UnsatisfiedLinkError / ExceptionInInitializerError: no native lib.
            Log.w(TAG, "SIGPIPE unblock unavailable: ${t.javaClass.simpleName}: ${t.message}")
            -1
        }
        if (state < 0) Log.w(TAG, "SIGPIPE unblock failed ($state); spawning with the inherited mask")
        if (state >= 0 && state and DISPOSITION_IGNORED != 0 && !ignoredLogged) {
            ignoredLogged = true
            Log.w(TAG, "SIGPIPE is SIG_IGN in this process; children inherit it and `yes | head` can still hang")
        }
        try {
            return block()
        } finally {
            if (state >= 0 && state and WAS_BLOCKED != 0) {
                val rc = runCatching { native.blockSigpipe() }.getOrElse { -1 }
                if (rc != 0) Log.e(TAG, "SIGPIPE re-block failed ($rc)")
            }
        }
    }
}
