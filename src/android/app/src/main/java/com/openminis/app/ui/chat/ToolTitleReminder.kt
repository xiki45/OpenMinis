package com.openminis.app.ui.chat

import org.json.JSONObject

/**
 * [T-tool-title-reminder] Gentle one-time nudge for a missing `tool_title`.
 * Mirrors iOS `ToolTitleReminder` (AIChatViewModel+ConcurrentTools.swift),
 * with the identical text and marker, so a session synced between platforms is
 * recognised as already reminded on either side.
 *
 * Some models skip `tool_title` even though the schema marks it required. The
 * tool still runs, but its card can only show the raw command. A short, soft
 * `<system-reminder>` is appended to the model-facing result of a titled tool
 * that came back without a title — on the 1st, 11th, 21st… such call. Counting
 * and throttling live in [com.openminis.app.agent.ToolLoopDetector.recordToolTitle]
 * (the loop detector's lock, warning buckets and reset lifecycle); this object
 * only holds the text and the display-side helpers. The UI never shows it: the
 * live card is filled from the tool's own output, and the reload path strips it
 * with [stripForDisplay].
 */
object ToolTitleReminder {
    /** Unique tag, so detection/stripping never touch any other reminder. */
    const val MARKER = "[minis:tool-title-reminder]"

    const val TEXT = "<system-reminder>$MARKER This is a soft reminder. " +
        "This tool call has no `tool_title`; please include a short one next time.</system-reminder>"

    /** True when the call has no usable title: absent, not a string, or blank. */
    fun isMissingTitle(args: JSONObject): Boolean {
        val v = args.opt("tool_title") as? String ?: return true
        return v.isBlank()
    }

    /** Only tools whose schema asks for a title; MCP tools etc. are never "missing" one. */
    fun declaresTitle(parameterNames: Collection<String>): Boolean = "tool_title" in parameterNames

    /** Append the note after the real output. */
    fun appending(content: String): String = if (content.isEmpty()) TEXT else content + "\n\n" + TEXT

    private val displayRe = Regex("""\n*<system-reminder>\[minis:tool-title-reminder\][\s\S]*?</system-reminder>""")

    /** The tool output as the user should see it: only this note removed. */
    fun stripForDisplay(text: String): String =
        if (!text.contains(MARKER)) text else displayRe.replace(text, "")
}
