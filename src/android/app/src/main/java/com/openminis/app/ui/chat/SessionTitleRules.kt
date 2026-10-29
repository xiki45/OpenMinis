package com.openminis.app.ui.chat

/** [T-android-chat-title-follows-store] How a stored session title is shown and when auto-title may write. */
internal object SessionTitleRules {

    /** What the top bar shows for a stored title: the title, or [untitled] when there is none. */
    fun display(stored: String?, untitled: String): String = stored?.takeIf { it.isNotBlank() } ?: untitled

    /**
     * Whether a title generated automatically (after the first reply) may be
     * written over [stored]: only while the session is still untitled. A
     * title the user typed or regenerated in the meantime stays.
     */
    fun autoTitleMayReplace(stored: String?, untitled: String): Boolean =
        stored.isNullOrBlank() || stored == untitled
}
