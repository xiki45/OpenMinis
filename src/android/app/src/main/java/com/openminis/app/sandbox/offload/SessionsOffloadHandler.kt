package com.openminis.app.sandbox.offload

import com.openminis.app.data.repository.ChatRepository
import com.openminis.app.logging.AppLogger
import com.openminis.app.sandbox.NativeOffloadHandler
import com.openminis.app.sandbox.NativeOffloadRequest
import com.openminis.app.sandbox.NativeOffloadResult
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * T188 — `minis-sessions-cli` offload handler. Lets the in-shell agent
 * query historical chat sessions and messages without round-tripping
 * back through the LLM. Four subcommands:
 *
 *   list       List recent sessions, optionally filtered by id, keyword,
 *              and date range. Default if no subcommand is given.
 *   search     Search message content across sessions; --keywords
 *              required.
 *   messages   Read a paginated transcript of one session; --id required.
 *   status     [T-subagent-termination] Current state of one session; --id
 *              required. Same fields as iOS `status`, including how a sub
 *              agent run ended (termination_reason, last_tool_status, …).
 *   update     [T-sessions-cli-update] Change a session's title, group and/or
 *              icon through the same repository calls the session list's
 *              Edit sheet and group menu use (the UI refreshes from Room).
 *   groups     [T-sessions-cli-update] Every group with its session count.
 *
 * Mirrors iOS canonical CLI surface: argv shape, JSON envelope, exit
 * codes, error messages all line up. The on-device db (Room) is the
 * same shape as iOS (sqlite tables `sessions`/`messages` with matching
 * column names), so query semantics translate one-to-one.
 *
 * Output flags `--compact` / `-q` / `--quiet` are honored uniformly via
 * [OffloadOutput.formatBody], same as every other android-* / minis-*
 * tool.
 */
class SessionsOffloadHandler(
    private val repo: ChatRepository,
    /** The authoritative is-running signal (iOS: SessionActivityTracker.isActive). */
    private val isRunning: (String) -> Boolean = { com.openminis.app.service.SessionActivityTracker.isActive(it) },
    /** A live sub agent job still runs in this session (registry), for `interrupted` resolution. */
    private val hasLiveJob: (String) -> Boolean = { sid ->
        com.openminis.app.agent.jobs.AgentJobRegistry.list().any { it.runSessionId == sid && it.isActive }
    },
    /**
     * [T-sessions-cli-update] `update --title-auto`: the session list's
     * Regenerate Title (SessionTitleRegenerator); true when a title was written.
     */
    private val titleRegenerator: (suspend (String) -> Boolean)? = null,
) : NativeOffloadHandler {

    override fun handle(request: NativeOffloadRequest): NativeOffloadResult {
        // argv[0] is the program name ("minis-sessions-cli"); subcommand
        // and options follow. Drop argv[0] before parsing so positional[0]
        // is the subcommand name. `full` is declared boolean so
        // `--full <token>` never greedily consumes the next token as a value
        // (e.g. `--full` placed before the subcommand). Same for `--tools`.
        val args = OffloadArgs(request.argv.drop(1), booleanFlags = setOf("full", "tools", "title-auto"))
        if (args.hasFlag("h", "help")) {
            return NativeOffloadResult(0, OffloadOutput.formatBody(HELP_TEXT, args) + "\n")
        }

        val sub = args.positional.firstOrNull() ?: "list"
        return try {
            when (sub) {
                "list" -> cmdList(args)
                "search" -> cmdSearch(args)
                "messages" -> cmdMessages(args)
                "status" -> cmdStatus(args)
                "update" -> cmdUpdate(args)
                "groups" -> cmdGroups(args)
                else -> {
                    val err = errorEnvelope(
                        sub,
                        "INVALID_ARGS",
                        "Unknown command '$sub'. Valid: list, search, messages, status, update, groups. " +
                            "Use --help for details.",
                    )
                    NativeOffloadResult(
                        EXIT_INVALID_ARGS,
                        OffloadOutput.formatBody(err.toString(2), args) + "\n" + HELP_TEXT,
                    )
                }
            }
        } catch (e: Throwable) {
            // Last-ditch: any unexpected exception (sql parse error, OOM,
            // …) becomes an INTERNAL envelope rather than a crash so the
            // shell stays alive and the agent can read the failure.
            AppLogger.warning(TAG, "uncaught: ${e.message}")
            val err = errorEnvelope(sub, "INTERNAL", e.message ?: "unknown error")
            NativeOffloadResult(
                1,
                OffloadOutput.formatBody(err.toString(2), args) + "\n",
            )
        }
    }

    private fun cmdList(args: OffloadArgs): NativeOffloadResult {
        val ids = parseIds(args)
        val kws = parseKeywords(args)
        val startMs = parseDate(args.get("start"))
        val endMs = parseEndDate(args.get("end"))
        val limit = parseLimit(args)
        // [T-sessions-cli-update] --group <name>: exact, case-sensitive name
        // (every group with that name - names are not unique); "" = ungrouped.
        val groupFilter = args.get("group")?.let(SessionsUpdateRules::normalizedGroup)

        val (metas, folderNames) = runBlocking {
            val folders = repo.listFolders()
            val folderIds = groupFilter?.let { g ->
                if (g.isEmpty()) emptyList() else folders.filter { SessionsUpdateRules.groupNameMatches(it.name, g) }.map { it.id }
            }
            val rows = if (folderIds != null && groupFilter!!.isNotEmpty() && folderIds.isEmpty()) emptyList()
            else repo.querySessionsMeta(ids, kws, limit, startMs, endMs, folderIds)
            rows to folders.associate { it.id to it.name }
        }

        val sessions = JSONArray()
        for (m in metas) {
            val s = JSONObject()
                .put("session_id", m.id)
                .put("started_at", DATE_FMT.format(Date(m.startedAt)))
                .put("last_active", DATE_FMT.format(Date(m.lastActive)))
                .put("message_count", m.messageCount)
            // Optional fields — only emit when non-null so the JSON
            // matches iOS's `(optional)` shape rather than carrying
            // null literals through to the agent prompt.
            // [T-sessions-cli-update] title / group / icon are always present
            // ("" = untitled / ungrouped / default icon).
            s.put("title", m.title.orEmpty())
            s.put("group", m.folderId?.let { folderNames[it] }.orEmpty())
            s.put("icon", m.category.orEmpty())
            m.preview?.let { if (it.isNotBlank()) s.put("preview", it) }
            m.source?.let { if (it.isNotBlank()) s.put("source", it) }
            sessions.put(s)
        }
        val data = JSONObject()
            .put("count", sessions.length())
            .put("sessions", sessions)
        return emit("list", data, args)
    }

    private fun cmdSearch(args: OffloadArgs): NativeOffloadResult {
        val kws = parseKeywords(args).orEmpty()
        if (kws.isEmpty()) {
            val err = errorEnvelope(
                "search",
                "INVALID_ARGS",
                "--keywords is required for search. " +
                    "Example: minis-sessions-cli search --keywords \"API error\"",
            )
            return NativeOffloadResult(
                EXIT_INVALID_ARGS,
                OffloadOutput.formatBody(err.toString(2), args) + "\n" + HELP_TEXT,
            )
        }
        val ids = parseIds(args)
        val startMs = parseDate(args.get("start"))
        val endMs = parseEndDate(args.get("end"))
        val limit = parseLimit(args)

        val matches = runBlocking { repo.searchMessages(ids, kws, limit, startMs, endMs) }

        val msgs = JSONArray()
        for (m in matches) {
            msgs.put(
                JSONObject()
                    .put("session_id", m.sessionId)
                    .put("message_id", m.messageId)
                    .put("role", m.role)
                    .put("created_at", DATE_FMT.format(Date(m.createdAt)))
                    .put("snippet", m.snippet),
            )
        }
        val data = JSONObject()
            .put("count", msgs.length())
            .put("messages", msgs)
        return emit("search", data, args)
    }

    private fun cmdMessages(args: OffloadArgs): NativeOffloadResult {
        val sessionId = args.get("id")?.takeIf { it.isNotBlank() }
            ?: run {
                val err = errorEnvelope(
                    "messages",
                    "INVALID_ARGS",
                    "--id <session_id> is required. Use 'list' first to find session IDs.",
                )
                return NativeOffloadResult(
                    EXIT_INVALID_ARGS,
                    OffloadOutput.formatBody(err.toString(2), args) + "\n" + HELP_TEXT,
                )
            }
        val limit = parseLimit(args)
        val offset = (args.getInt("offset") ?: 0).coerceAtLeast(0)
        // [T-android-sessions-cli-full] --full lifts the per-message text cap
        // from the default 600 to 50000 chars (iOS parity). Previously the flag
        // was silently ignored — every message stayed truncated at 600 no
        // matter what the caller passed, gutting transcript exports.
        val full = args.hasFlag("full")
        val maxChars = if (full) ChatRepository.MESSAGE_TEXT_MAX_FULL else ChatRepository.MESSAGE_TEXT_MAX
        // [T-android-sessions-cli-messages-daterange] GH#200 (iOS 8f3189a73).
        // HELP_TEXT documents --start / --end and `list` / `search` honour
        // them, but `messages` parsed neither — the CLI accepted the flags and
        // silently returned the whole session, which reads as the filter
        // working and matching everything.
        val startMs = parseDate(args.get("start"))
        val endMs = parseEndDate(args.get("end"))
        // [T-android-sessions-cli-tool-calls] Opt-in, so the default output is
        // unchanged (iOS 7af22b59f).
        val tools = args.hasFlag("tools")

        val (page, total) = runBlocking {
            // Single coroutine block so the two reads see a consistent
            // snapshot — ordering matters less than ensuring we don't
            // surface a `total` that disagrees with the slice we
            // returned (e.g. another session interleaving inserts mid-
            // call). Room serializes via the suspending dispatcher so
            // these run sequentially in the same coroutine.
            // Both reads take the SAME date range on purpose: an unfiltered
            // count next to a filtered page would make `total` describe the
            // whole session while the slice covers only the matches, so
            // `hasMore` would lie. iOS 8f3189a73 calls this out explicitly.
            repo.loadMessagePage(sessionId, offset, limit, maxChars, startMs, endMs, includeTools = tools) to
                repo.messageCountInRange(sessionId, startMs, endMs)
        }

        val msgs = JSONArray()
        for (m in page) {
            val obj = JSONObject()
                .put("message_id", m.messageId)
                .put("role", m.role)
                .put("created_at", DATE_FMT.format(Date(m.createdAt)))
                .put("text", m.text)
            // Only present when the stored text exceeded the cap, so normal
            // messages serialize byte-identically to before (iOS parity).
            if (m.truncated) obj.put("truncated", true)
            if (tools) {
                obj.put("tool_calls", toolCallsJson(m.toolUses, maxChars))
                obj.put("tool_results", toolResultsJson(m.toolResults, maxChars))
            }
            msgs.put(obj)
        }
        val data = JSONObject()
            .put("session_id", sessionId)
            .put("offset", offset)
            .put("limit", limit)
            .put("full", full)
            .put("max_chars", maxChars)
            .put("total", total)
            .put("count", msgs.length())
            .put("messages", msgs)
        // Only when on, so the default envelope keeps exactly its old keys.
        if (tools) data.put("tools", true)
        return emit("messages", data, args)
    }

    /**
     * [T-subagent-termination] `status --id <session_id>`: is it running, what
     * did it last say and do, and — for a sub agent session — how did its run
     * end. Port of iOS `getSessionStatus` with its 2026-10-04 fixes:
     * last_message / last_tool come from the stored transcript (iOS returned ""
     * for any session whose view model was not cached), and the termination
     * record is merged in. A record still `running` with nothing alive behind
     * it is reported (and stored) as `interrupted`.
     */
    private fun cmdStatus(args: OffloadArgs): NativeOffloadResult {
        val sessionId = (args.get("id") ?: args.positional.getOrNull(1))?.takeIf { it.isNotBlank() }
            ?: run {
                val err = errorEnvelope("status", "INVALID_ARGS", "--id <session_id> is required for status.")
                return NativeOffloadResult(
                    EXIT_INVALID_ARGS,
                    OffloadOutput.formatBody(err.toString(2), args) + "\n" + HELP_TEXT,
                )
            }
        val (session, rows) = runBlocking { repo.getSession(sessionId) to repo.loadMessages(sessionId) }
        if (session == null) {
            val err = errorEnvelope("status", "session_not_found", "No session with id $sessionId.")
            err.getJSONObject("data").put("session_id", sessionId)
            return NativeOffloadResult(1, OffloadOutput.formatBody(err.toString(2), args) + "\n")
        }
        val running = isRunning(sessionId)
        val termination = com.openminis.app.agent.jobs.SubAgentTerminationStore.shared
            .resolved(sessionId, isLive = running || hasLiveJob(sessionId))
        val group = runBlocking { session.folderId?.let { repo.getFolder(it)?.name } }.orEmpty()
        return emit("status", statusData(session, rows, running, termination, group), args)
    }

    /**
     * [T-sessions-cli-update] `update --id <id> [--title t | --title-auto] [--group g] [--icon i]`.
     * Same contract and order of steps as iOS SessionsOffloadBridge.updateSession:
     *
     *  1. Argument errors ([SessionsUpdateRules.validate]) before any lookup.
     *  2. session_not_found / not_allowed (a sub agent's hidden session).
     *  3. --title-auto first, through the list's own Regenerate Title
     *     ([titleRegenerator]); if it fails nothing else is written.
     *  4. Title / icon in one write - the Edit sheet's own repository call -
     *     skipped when nothing differs, so a no-op does not re-sort the list.
     *  5. Group: exact (trimmed) name, created when missing, "" ungroups -
     *     the group picker's createFolder / setFolderForSessions.
     *  6. `changed`: the fields whose value differs, in the order title, group, icon.
     *
     * Every write goes through ChatRepository, so the list and an open chat
     * refresh from Room as after an edit in the app.
     */
    private fun cmdUpdate(args: OffloadArgs): NativeOffloadResult {
        for (f in listOf("id", "title", "group", "icon")) {
            if (args.hasFlag(f) && args.get(f) == null) return updateFail(args, SessionsUpdateRules.INVALID_ARGUMENTS, "--$f needs a value.")
        }
        val id = args.get("id")?.trim()?.takeIf { it.isNotEmpty() }
            ?: return updateFail(args, SessionsUpdateRules.INVALID_ARGUMENTS,
                "--id <session_id> is required for update. Use 'list' first to find a session ID.")
        val req = SessionsUpdateRules.Request(
            title = args.get("title"),
            titleAuto = args.hasFlag("title-auto"),
            group = args.get("group"),
            icon = args.get("icon"),
        )
        SessionsUpdateRules.validate(req)?.let { return updateFail(args, it.code, it.message) }

        val outcome = runBlocking { performUpdate(id, req) }
        return when (outcome) {
            is SessionsUpdateRules.Outcome.Failed -> updateFail(args, outcome.code, outcome.message)
            is SessionsUpdateRules.Outcome.Done -> {
                AppLogger.info(TAG, "[SessionsCLI] update ${id.take(8)} changed=${outcome.changed}")
                emit("update", JSONObject()
                    .put("id", id)
                    .put("title", outcome.after.title)
                    .put("group", outcome.after.group)
                    .put("icon", outcome.after.icon)
                    .put("changed", JSONArray(outcome.changed)), args, timestamp = true)
            }
        }
    }

    private suspend fun snapshot(session: com.openminis.app.data.db.ChatSessionEntity) = SessionsUpdateRules.Snapshot(
        title = session.title.orEmpty(),
        group = session.folderId?.let { repo.getFolder(it)?.name }.orEmpty(),
        icon = session.category.orEmpty(),
    )

    private suspend fun performUpdate(id: String, req: SessionsUpdateRules.Request): SessionsUpdateRules.Outcome {
        val fail = SessionsUpdateRules.Outcome::Failed
        val session = repo.getSession(id)
            ?: return fail(SessionsUpdateRules.SESSION_NOT_FOUND, "No session with id $id.")
        if (session.isChild) {
            return fail(SessionsUpdateRules.NOT_ALLOWED,
                "$id is a sub agent's hidden session; edit its parent ${session.parentSessionId.orEmpty()} instead.")
        }
        val before = snapshot(session)

        // 1. --title-auto first: on failure nothing else has been written.
        if (req.titleAuto) {
            val regen = titleRegenerator
                ?: return fail(SessionsUpdateRules.TITLE_AUTO_FAILED, "Title regeneration is not available.")
            val ok = runCatching { kotlinx.coroutines.withTimeoutOrNull(TITLE_AUTO_TIMEOUT_MS) { regen(id) } }.getOrElse {
                return fail(SessionsUpdateRules.TITLE_AUTO_FAILED, it.message ?: it.javaClass.simpleName)
            } ?: return fail(SessionsUpdateRules.TITLE_AUTO_FAILED, "Title regeneration timed out after ${TITLE_AUTO_TIMEOUT_MS / 1000} s.")
            if (!ok || repo.getSession(id)?.title.isNullOrBlank()) {
                return fail(SessionsUpdateRules.TITLE_AUTO_FAILED,
                    "No title was generated: the session needs at least one user message and one reply.")
            }
        }

        // 2. Title / icon: one write, skipped when nothing differs.
        if (req.title != null || req.icon != null) {
            val current = repo.getSession(id) ?: return fail(SessionsUpdateRules.SESSION_NOT_FOUND, "Session $id was deleted during the update.")
            val newTitle = req.title?.let(SessionsUpdateRules::normalizedTitle) ?: current.title
            val newCategory = if (req.icon != null) SessionsUpdateRules.normalizedIcon(req.icon)!!.ifEmpty { null } else current.category
            if (newTitle != current.title || newCategory != current.category) {
                when {
                    newTitle != null && newCategory != null -> repo.updateSessionTitleAndCategory(id, newTitle, newCategory)
                    newTitle != null && newTitle != current.title -> {
                        repo.updateSessionTitleAndCategory(id, newTitle, null)
                        if (newCategory != current.category) repo.updateSessionCategory(id, newCategory)
                    }
                    else -> repo.updateSessionCategory(id, newCategory)
                }
            }
        }

        // 3. Group: exact name, created when missing, "" ungroups.
        if (req.group != null) {
            val name = SessionsUpdateRules.normalizedGroup(req.group)
            val folderId = if (name.isEmpty()) null else {
                val folders = repo.listFolders()
                val matches = folders.filter { SessionsUpdateRules.groupNameMatches(it.name, name) }
                val activity = if (matches.size > 1) matches.associate { f ->
                    f.id to repo.sessionIdsInFolder(f.id).mapNotNull { repo.getSession(it)?.updatedAt }.maxOrNull()
                } else emptyMap()
                SessionsUpdateRules.pickFolder(name, folders.map { SessionsUpdateRules.FolderRef(it.id, it.name, it.createdAt) }, activity)
                    ?: repo.createFolder(name).id.also { AppLogger.info(TAG, "[SessionsCLI] update ${id.take(8)} created group") }
            }
            val current = repo.getSession(id)
            if (current != null && current.folderId != folderId) repo.setFolderForSessions(folderId, listOf(id))
        }

        val final = repo.getSession(id) ?: return fail(SessionsUpdateRules.SESSION_NOT_FOUND, "Session $id was deleted during the update.")
        val after = snapshot(final)
        return SessionsUpdateRules.Outcome.Done(after, SessionsUpdateRules.changedFields(before, after))
    }

    /**
     * iOS's error envelope (noff_json_error): `{ok:false, tool, action,
     * error:{code, message}}`; `-q` prints just `error`. Usage errors
     * (invalid_arguments, invalid_icon) exit 2, errors about the session 1.
     */
    private fun updateFail(args: OffloadArgs, code: String, msg: String): NativeOffloadResult {
        val error = JSONObject().put("code", code).put("message", msg)
        // An extra key the shared spec allows: the keys an agent can retry with.
        if (code == SessionsUpdateRules.INVALID_ICON) error.put("valid_icons", JSONArray(SessionsUpdateRules.ICON_NAMES))
        val err = JSONObject()
            .put("ok", false)
            .put("tool", TOOL_NAME)
            .put("action", "update")
            .put("error", error)
            .put("timestamp", isoNow())
        val exit = if (code == SessionsUpdateRules.INVALID_ARGUMENTS || code == SessionsUpdateRules.INVALID_ICON) EXIT_INVALID_ARGS else 1
        return NativeOffloadResult(exit, OffloadOutput.formatBody(err.toString(2), args) + "\n")
    }

    /**
     * [T-sessions-cli-update] `groups`: every group as `{name, session_count}`,
     * same-named groups separately. Order as the home list shows them:
     * pinned groups first, then groups by their newest member's activity,
     * then empty groups. `session_count` counts home-list sessions only (no
     * hidden child sessions).
     */
    private fun cmdGroups(args: OffloadArgs): NativeOffloadResult {
        val rows = runBlocking {
            repo.listFolders().map { f ->
                val members = repo.sessionIdsInFolder(f.id).mapNotNull { repo.getSession(it) }.filter { !it.isChild }
                Triple(f, members.size, members.maxOfOrNull { it.updatedAt })
            }
        }.sortedWith(
            compareByDescending<Triple<com.openminis.app.data.db.FolderEntity, Int, Long?>> { it.first.pinnedAt != null }
                .thenByDescending { it.first.pinnedAt ?: 0L }
                .thenByDescending { it.third != null }
                .thenByDescending { it.third ?: 0L },
        )
        val arr = JSONArray()
        for ((f, count, _) in rows) arr.put(JSONObject().put("name", f.name).put("session_count", count))
        return emit("groups", JSONObject().put("count", arr.length()).put("groups", arr), args, timestamp = true)
    }

    // ─── arg parsing helpers ──────────────────────────────────────────

    private fun parseIds(args: OffloadArgs): List<String>? {
        val raw = args.get("ids") ?: return null
        val parts = raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        return parts.takeIf { it.isNotEmpty() }
    }

    private fun parseKeywords(args: OffloadArgs): List<String>? {
        val raw = args.get("keywords") ?: return null
        // Split on any whitespace (the user may quote multi-word keywords
        // at the shell, in which case we get one big token containing a
        // space — collapse runs of whitespace into a single delimiter).
        val parts = raw.split(Regex("\\s+")).filter { it.isNotEmpty() }
        return parts.takeIf { it.isNotEmpty() }
    }

    private fun parseDate(raw: String?): Long? {
        if (raw.isNullOrBlank()) return null
        return runCatching {
            val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            sdf.timeZone = TimeZone.getDefault()
            sdf.isLenient = false
            sdf.parse(raw)?.time
        }.getOrNull()
    }

    /**
     * Inclusive end-of-day: `--end 2025-03-31` includes everything up to
     * and including 2025-03-31 23:59:59.999. Adding (1 day - 1 ms) is
     * safer than (24h - 1ms) across DST transitions on devices that
     * don't run UTC. Mirrors iOS SessionsOffload.m L141-145.
     */
    private fun parseEndDate(raw: String?): Long? {
        val ms = parseDate(raw) ?: return null
        val cal = Calendar.getInstance().apply {
            timeInMillis = ms
            add(Calendar.DAY_OF_YEAR, 1)
        }
        return cal.timeInMillis - 1
    }

    private fun parseLimit(args: OffloadArgs): Int {
        val raw = args.getInt("limit") ?: return DEFAULT_LIMIT
        return raw.coerceIn(1, MAX_LIMIT)
    }

    // ─── envelope helpers ─────────────────────────────────────────────

    /**
     * [T-sessions-cli-update] `timestamp` (ISO 8601, local offset, as iOS
     * noff_format_date) is set for the commands specified together with iOS
     * (update, groups); the older commands keep their exact previous keys.
     */
    private fun emit(action: String, data: JSONObject, args: OffloadArgs, timestamp: Boolean = false): NativeOffloadResult {
        val envelope = JSONObject()
            .put("ok", true)
            .put("tool", TOOL_NAME)
            .put("action", action)
            .put("data", data)
        if (timestamp) envelope.put("timestamp", isoNow())
        return NativeOffloadResult(
            0,
            OffloadOutput.formatBody(envelope.toString(2), args) + "\n",
        )
    }

    private fun errorEnvelope(action: String, code: String, msg: String): JSONObject {
        return JSONObject()
            .put("ok", false)
            .put("tool", TOOL_NAME)
            .put("action", action)
            .put("data", JSONObject().put("error", code).put("message", msg))
    }

    companion object {
        private const val TAG = "SessionsOffload"
        private const val TOOL_NAME = "minis-sessions-cli"
        private const val DEFAULT_LIMIT = 50
        private const val MAX_LIMIT = 100

        // iOS NOFF_EXIT_INVALID_ARGS — the shell convention is exit 2
        // for invalid CLI args, distinct from exit 1 for runtime errors.
        private const val EXIT_INVALID_ARGS = 2

        /** [T-sessions-cli-update] `--title-auto` gives up after this (shared spec 2.2). */
        private const val TITLE_AUTO_TIMEOUT_MS = 60_000L

        private fun isoNow(): String =
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).format(Date())

        private val DATE_FMT = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).apply {
            timeZone = TimeZone.getDefault()
        }

        /**
         * [T-subagent-termination] The `status` data object, pure for tests.
         * Keys match iOS: session_id, title, model_name, is_running,
         * last_message, last_tool, updated_at, plus — when known —
         * last_tool_status, last_tool_result, parent_session_id and the
         * termination fields (termination_reason, termination_detail,
         * last_tool_name, partial_output_available, closing_attempted,
         * delivery_state, delivery_detail, ended_at).
         *
         * last_message is the last assistant text of the stored transcript;
         * when there is none (a run that ended on a tool result) it falls back
         * to the session's recorded last activity (the list preview), and
         * last_tool_result carries what the last tool returned.
         */
        internal fun statusData(
            session: com.openminis.app.data.db.ChatSessionEntity,
            rows: List<com.openminis.app.data.db.MessageEntity>,
            isRunning: Boolean,
            termination: com.openminis.app.agent.jobs.SubAgentTermination?,
            /** [T-sessions-cli-update] Name of the session's group, "" = ungrouped. */
            group: String = "",
        ): JSONObject {
            val (lastAssistant, tool) = com.openminis.app.agent.jobs.SubAgentTerminationRules.lastActivity(rows)
            val lastMessage = lastAssistant.ifEmpty { session.lastMessage.orEmpty() }
            val data = JSONObject()
                .put("session_id", session.id)
                .put("title", session.title.orEmpty())
                .put("model_name", session.modelId)
                .put("is_running", isRunning)
                .put("last_message", lastMessage)
                .put("last_tool", tool?.name.orEmpty())
                .put("updated_at", DATE_FMT.format(Date(session.updatedAt)))
                .put("group", group)
                .put("icon", session.category.orEmpty())
            tool?.let {
                if (it.status.isNotEmpty()) data.put("last_tool_status", it.status)
                if (it.summary.isNotEmpty()) data.put("last_tool_result", it.summary)
            }
            session.parentSessionId?.let { data.put("parent_session_id", it) }
            termination?.putPayload(data)
            return data
        }

        /**
         * [T-android-sessions-cli-tool-calls] `tool_calls` for one message:
         * `{tool_use_id, name, input}`, input being the stored input JSON as a
         * string (so the field has one type even when cut mid-JSON), capped at
         * the same [maxChars] as `text` with `input_truncated` when cut.
         */
        internal fun toolCallsJson(uses: List<com.openminis.app.data.repository.OffloadToolUse>, maxChars: Int): JSONArray {
            val out = JSONArray()
            for (tu in uses) {
                val call = JSONObject()
                    .put("tool_use_id", tu.toolUseId)
                    .put("name", tu.name)
                    .put("input", tu.input.take(maxChars))
                if (tu.input.length > maxChars) call.put("input_truncated", true)
                out.put(call)
            }
            return out
        }

        /** [T-android-sessions-cli-tool-calls] `tool_results`: `{tool_use_id, success, output}`, same cap. */
        internal fun toolResultsJson(results: List<com.openminis.app.data.repository.OffloadToolResult>, maxChars: Int): JSONArray {
            val out = JSONArray()
            for (tr in results) {
                val res = JSONObject()
                    .put("tool_use_id", tr.toolUseId)
                    .put("success", tr.success)
                    .put("output", tr.output.take(maxChars))
                if (tr.output.length > maxChars) res.put("output_truncated", true)
                out.put(res)
            }
            return out
        }

        private const val HELP_TEXT = """minis-sessions-cli - Query historical chat sessions and messages

USAGE:
  minis-sessions-cli <command> [options]

COMMANDS:
  list      List recent sessions (default: 50, max: 100)
  search    Search message content across sessions (requires --keywords)
  messages  Read messages from a specific session (requires --id)
  status    Get current status of a session (requires --id; sub agents:
            termination_reason, last_tool_status, last_tool_result,
            delivery_state, ...)
  update    Change a session's title, group and/or icon (requires --id)
  groups    List every group: [{name, session_count}]

OPTIONS:
  --keywords <words>    Space-separated keywords (AND logic, required for search)
  --ids <id1,id2,...>   Filter by comma-separated session IDs (list/search)
  --id <session_id>     Session ID to read messages from (messages/status)
  --full                (messages only) Return full message text up to 50000 chars
  --tools               (messages only) Also return each message's tool calls:
                        tool_calls [{tool_use_id, name, input}] with the full
                        input JSON (e.g. the exact shell command), and
                        tool_results [{tool_use_id, success, output}].
                        Same per-field cap as text (600, or 50000 with --full).
  --offset <n>          Skip first n messages, 0-based (default: 0)
  --start <YYYY-MM-DD>  Filter results after this date (inclusive)
  --end <YYYY-MM-DD>    Filter results before this date (inclusive, end of day)
  --limit <n>           Max results (default: 50, max: 100)
  --group <name>        (list only) Only sessions in this group (exact name);
                        "" = only ungrouped sessions
  --help, -h            Show this help message
  --compact             Minimize JSON output
  -q, --quiet           Output only data field

UPDATE OPTIONS:
  --id <session_id>     Session to change (required)
  --title <text>        New title (must not be empty)
  --title-auto          Regenerate the title automatically, like the app's
                        "Regenerate Title" (may also set the icon). Not with --title.
  --group <name>        Move into the group with exactly this name
                        (case-sensitive); created if missing. "" = ungroup.
  --icon <icon>         One of: code writing research analysis creative chat
                        math translation health finance travel education
                        design productivity support other. "" = default icon.
  At least one of --title / --title-auto / --group / --icon is required;
  fields not given are left unchanged. data.changed lists the fields whose
  value actually changed.
  Errors: invalid_arguments, invalid_icon, session_not_found,
          not_allowed (sub agent sessions), title_auto_failed.

OUTPUT (update):
  id, title, group, icon, changed (the fields whose value changed).

OUTPUT (groups):
  count, groups [{name, session_count}].

OUTPUT (list):
  Each session includes: session_id, title, group ("" = ungrouped), icon
  ("" = default), preview (first user message, 60 chars), source,
  started_at, last_active, message_count.

OUTPUT (search):
  Each message includes: session_id, message_id, role, created_at,
  snippet (up to 600 chars centered on keyword match).

OUTPUT (status):
  session_id, title, model_name, is_running, last_message, last_tool,
  updated_at, group, icon; when known: last_tool_status (success|failed|cancelled|running),
  last_tool_result (one-line summary), parent_session_id. Sub agent sessions
  also carry termination_reason (running|completed|timeout|tool_error|
  cancelled|interrupted|model_error), termination_detail, last_tool_name,
  partial_output_available, closing_attempted, delivery_state
  (inline|pending|delivered|queued|failed), delivery_detail, ended_at.

OUTPUT (messages):
  Each message includes: message_id, role, created_at, text (up to 600 chars
  by default; up to 50000 with --full). Messages longer than the cap carry
  "truncated": true. Response also includes: session_id, offset, limit, full,
  max_chars, total (total message count). With --tools each message also
  has tool_calls / tool_results (input_truncated / output_truncated when cut)
  and the response carries "tools": true.

WORKFLOW:
  1. Use 'list' or 'list --keywords <topic>' to find relevant sessions
  2. Use 'search --keywords <terms>' to find specific messages
  3. Use 'messages --id <session_id>' to read a conversation
  4. Use --offset to paginate through long conversations

EXAMPLES:
  minis-sessions-cli list
  minis-sessions-cli list --limit 10
  minis-sessions-cli list --keywords python flask
  minis-sessions-cli list --start 2025-01-01 --end 2025-03-31
  minis-sessions-cli search --keywords "API error" --limit 20
  minis-sessions-cli search --keywords deploy --ids abc123,def456
  minis-sessions-cli messages --id <session_id>
  minis-sessions-cli messages --id <session_id> --full
  minis-sessions-cli messages --id <session_id> --tools --full
  minis-sessions-cli messages --id <session_id> --offset 20 --limit 10
  minis-sessions-cli status --id <session_id>
  minis-sessions-cli list --group "Project A"
  minis-sessions-cli groups
  minis-sessions-cli update --id <session_id> --title "新标题" --group "项目A" --icon "code"
  minis-sessions-cli update --id <session_id> --group ""
  minis-sessions-cli update --id <session_id> --icon ""
"""
    }
}
