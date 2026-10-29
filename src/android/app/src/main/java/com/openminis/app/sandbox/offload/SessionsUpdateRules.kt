package com.openminis.app.sandbox.offload

/**
 * [T-sessions-cli-update] The `minis-sessions-cli update / groups / list
 * --group` contract, ported one-to-one from iOS `SessionsUpdateRules`
 * (SessionsOffloadBridge.swift), so the two platforms validate, match
 * groups and report changes identically.
 */
object SessionsUpdateRules {

    /**
     * The session icon keys: the session's category, the 16 tiles of the Edit
     * Session sheet (SessionListScreen.allCategories, iOS
     * SessionEditSheet.categories). "" = the default icon. Matched after
     * trimming and case-insensitively (src/shared/cli/sessions-update.md 2.1).
     */
    val ICON_NAMES: List<String> = listOf(
        "code", "writing", "research", "analysis", "creative", "chat",
        "math", "translation", "health", "finance", "travel", "education",
        "design", "productivity", "support", "other",
    )

    const val INVALID_ARGUMENTS = "invalid_arguments"
    const val INVALID_ICON = "invalid_icon"
    const val SESSION_NOT_FOUND = "session_not_found"
    const val NOT_ALLOWED = "not_allowed"
    const val TITLE_AUTO_FAILED = "title_auto_failed"

    data class Request(
        val title: String? = null,
        val titleAuto: Boolean = false,
        val group: String? = null,
        val icon: String? = null,
    )

    data class Failure(val code: String, val message: String)

    /**
     * Argument errors, checked before the session is looked up, in this
     * order: no field, --title with --title-auto, empty --title, bad --icon.
     */
    fun validate(r: Request): Failure? {
        if (r.title == null && !r.titleAuto && r.group == null && r.icon == null) {
            return Failure(INVALID_ARGUMENTS, "update needs at least one of --title, --title-auto, --group, --icon.")
        }
        if (r.title != null && r.titleAuto) {
            return Failure(INVALID_ARGUMENTS, "--title and --title-auto cannot be used together.")
        }
        if (r.title != null && normalizedTitle(r.title).isEmpty()) {
            return Failure(INVALID_ARGUMENTS, "--title cannot be empty. Use --title-auto to regenerate the title automatically.")
        }
        if (r.icon != null && normalizedIcon(r.icon) == null) {
            return Failure(INVALID_ICON, "Unknown icon '${r.icon}'. Valid icons: ${ICON_NAMES.joinToString(", ")}, or \"\" for the default icon.")
        }
        return null
    }

    /**
     * The icon key for an `--icon` value: "" for blank (the default icon), the
     * key for a known one in any case and with surrounding spaces, null when
     * it is not an icon key.
     */
    fun normalizedIcon(raw: String): String? {
        val v = raw.trim().lowercase()
        return if (v.isEmpty() || v in ICON_NAMES) v else null
    }

    /** Titles and group names are trimmed, as the edit sheet and the group picker do. */
    fun normalizedTitle(s: String): String = s.trim()
    fun normalizedGroup(s: String): String = s.trim()

    /** Group names match case-sensitively and exactly (after trimming). */
    fun groupNameMatches(stored: String, wanted: String): Boolean = normalizedGroup(stored) == wanted

    data class FolderRef(val id: String, val name: String, val createdAt: Long)

    /**
     * The group [name] refers to, or null when none has that name. Names may
     * repeat by design; the one with the newest member session wins, then any
     * group with members beats an empty one, then the oldest group.
     * [lastActivity]: group id -> newest member's updated_at (absent / null =
     * no members).
     */
    fun pickFolder(name: String, among: List<FolderRef>, lastActivity: Map<String, Long?>): String? {
        val matches = among.filter { groupNameMatches(it.name, name) }
        if (matches.size <= 1) return matches.firstOrNull()?.id
        return matches.sortedWith(
            compareByDescending<FolderRef> { lastActivity[it.id] != null }
                .thenByDescending { lastActivity[it.id] ?: Long.MIN_VALUE }
                .thenBy { it.createdAt },
        ).first().id
    }

    data class Snapshot(val title: String, val group: String, val icon: String)

    /** Fields whose value differs, in the fixed order title, group, icon. */
    fun changedFields(before: Snapshot, after: Snapshot): List<String> = buildList {
        if (before.title != after.title) add("title")
        if (before.group != after.group) add("group")
        if (before.icon != after.icon) add("icon")
    }

    sealed class Outcome {
        data class Done(val after: Snapshot, val changed: List<String>) : Outcome()
        data class Failed(val code: String, val message: String) : Outcome()
    }
}
