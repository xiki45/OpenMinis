package com.openminis.app.data.repository

/**
 * [T-preview-scheduled-tag-leak] The app-authored envelopes that can sit in a
 * message's text, and how a one-line preview (session list, restore rebuild)
 * gets rid of them. Pure, so every variant is unit-tested.
 *
 * - `<system-reminder>…</system-reminder>`: runtime nudges in user-role rows.
 * - `<user-attached-files>…</user-attached-files>`: the attached-file listing
 *   appended to a user turn for the model.
 * - `[attached image: …]` / `[image omitted to save context …]`: attachment
 *   captions written for the model.
 * - `<scheduled_task …>…</scheduled_task>`: a scheduled fire
 *   (ScheduledTaskMarker), previewed as one readable line instead.
 * - `<agent_callback …>`: handled by AgentCallback.parse / its preview line.
 *
 * The strippers ignore case and tolerate a tag that was never closed (a row
 * cut short, a hand-typed fragment): an unclosed opener drops everything
 * after it, never leaving the raw tag on screen. A stray closing tag is
 * dropped too.
 */
internal object InternalTags {

    private val SYSTEM_REMINDER = Regex(
        """<system-reminder\b[^>]*>.*?(?:</system-reminder\s*>|\z)""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
    private val ATTACHED_FILES = Regex(
        """<user-attached-files\b[^>]*>.*?(?:</user-attached-files\s*>|\z)""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
    private val STRAY_CLOSERS = Regex(
        """</(?:system-reminder|user-attached-files|scheduled_task)\s*>""",
        RegexOption.IGNORE_CASE,
    )
    private val ATTACHMENT_MARKERS = listOf(
        Regex("""\[attached [A-Za-z]+:[^\]]*]"""),
        Regex("""\[image omitted to save context[^\]]*]"""),
    )
    private val SCHEDULED_OPENER = Regex("""^\s*<scheduled_task\b""", RegexOption.IGNORE_CASE)

    fun stripSystemReminders(raw: String): String =
        SYSTEM_REMINDER.replace(raw, "").trim()

    /** Every envelope a preview must not show, plus the attachment captions. */
    fun stripForPreview(raw: String): String {
        var s = SYSTEM_REMINDER.replace(raw, "")
        s = ATTACHED_FILES.replace(s, "")
        s = STRAY_CLOSERS.replace(s, "")
        if (s.contains('[')) for (re in ATTACHMENT_MARKERS) s = re.replace(s, "")
        return s.trim()
    }

    /**
     * The preview line of a scheduled fire's row, or null when [text] is not
     * one. iOS previews a fire (an agent callback of kind "scheduled") as
     * "Scheduled task · <title> · <status>"; Android's envelope carries no
     * status, so it is "<noun> · <label>", the prompt standing in for a blank
     * label. An envelope too damaged to parse still reads as the noun alone,
     * never as the raw tag.
     */
    fun scheduledPreviewLine(text: String, noun: String, clean: (String) -> String): String? {
        val bare = stripSystemReminders(text)
        if (!SCHEDULED_OPENER.containsMatchIn(bare)) return null
        val marker = com.openminis.app.scheduled.ScheduledTaskMarker.parse(bare)
            ?: return noun
        val name = marker.label.trim().ifEmpty { clean(marker.prompt) }.trim()
        return if (name.isEmpty()) noun else "$noun · $name"
    }
}
