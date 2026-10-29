package com.openminis.app.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * [UI-261023-07] Chats with a new reply the user has not seen: a red dot on
 * the row's avatar and, while their group is closed, on the group tile.
 *
 * Mirrors iOS `SessionBadgeState.unread` (SessionBadgeStore.swift): a turn
 * that ends with a reply while the user is not looking at that chat marks it
 * unread; opening the chat clears it. Like iOS it is a transient hint, kept
 * in memory only - never persisted, never synced - so losing it on relaunch
 * is fine and there is no wire format to agree on.
 *
 * Kept apart from [SessionBadgeStore] on purpose: that store's queue head is
 * the row's bottom corner badge and it is persisted, both wrong for this.
 */
object SessionUnreadStore {
    private val _ids = MutableStateFlow<Set<String>>(emptySet())

    /** Session ids carrying the unread dot. */
    val ids: StateFlow<Set<String>> = _ids.asStateFlow()

    /**
     * Chat screens in the foreground, each reporting the id it shows. A
     * supplier rather than a fixed id: a draft chat gets its real id with the
     * first message, and the turn that ends is reported under the real one.
     */
    private val viewers = LinkedHashMap<Any, () -> String>()

    /** True while a resumed chat screen shows [sessionId]. */
    fun isViewing(sessionId: String): Boolean = synchronized(this) {
        viewers.values.any { it() == sessionId }
    }

    /** A chat screen came to the foreground: it is being read, so it is not unread. */
    fun startViewing(token: Any, sessionId: () -> String) {
        synchronized(this) { viewers[token] = sessionId }
        markRead(sessionId())
    }

    fun stopViewing(token: Any) {
        synchronized(this) { viewers.remove(token) }
    }

    /**
     * A turn of [sessionId] ended with a reply. Marks it unread unless the
     * user is looking at it - they saw the reply arrive.
     */
    fun markUnread(sessionId: String) {
        if (sessionId.isEmpty() || isViewing(sessionId)) return
        _ids.update { it + sessionId }
    }

    fun markRead(sessionId: String) {
        if (sessionId.isEmpty()) return
        _ids.update { it - sessionId }
    }

    private inline fun MutableStateFlow<Set<String>>.update(f: (Set<String>) -> Set<String>) {
        synchronized(this@SessionUnreadStore) {
            val next = f(value)
            if (next != value) value = next
        }
    }
}
