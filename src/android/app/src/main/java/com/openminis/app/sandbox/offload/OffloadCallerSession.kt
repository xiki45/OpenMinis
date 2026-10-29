package com.openminis.app.sandbox.offload

import com.openminis.app.sandbox.ExecutionCoordinator

/**
 * [T-offload-caller-session] The chat an offloaded CLI command belongs to.
 * Android port of iOS `ISHExecutionCoordinator.callerSessionId(fsContext:)`
 * as used by minis-model-use and minis-scheduled (7effb6f2f, b99e4da87).
 *
 * A native offload only learns `MINIS_CHAT_SESSION_ID`, the DISPATCH id of the
 * shell that ran it. For a sub agent (helper) that is the helper's own hidden
 * session, but its shell runs with the PARENT's `/var/minis` mounted
 * (T-p2-shared-workspace), exactly as an iOS helper runs under the parent's
 * fsSessionId, where the fs_context lookup therefore names the parent. So the
 * caller is the mounted session: files an offload writes land in the
 * directory the guest (and the parent chat) actually sees, and anything the
 * command creates on the caller's behalf belongs to the chat the user is
 * looking at. For an ordinary chat both ids are the same.
 */
internal object OffloadCallerSession {

    /** [sid] null only when the command came from no chat (interactive terminal). */
    data class Caller(val sid: String?, val source: String)

    fun resolve(
        dispatchSid: String?,
        mountedFor: (String) -> String = ExecutionCoordinator::mountedSessionIdFor,
    ): Caller {
        if (dispatchSid.isNullOrEmpty()) return Caller(null, "none (no chat session)")
        val mounted = mountedFor(dispatchSid)
        return if (mounted != dispatchSid) {
            Caller(mounted, "mounted parent (dispatch=${dispatchSid.take(8)})")
        } else {
            Caller(dispatchSid, "env")
        }
    }
}
