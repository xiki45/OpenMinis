//
//  AIChatViewModel+SystemPrompt.swift
//  Minis - chat view model (Agent/Chat)
//
//  AIChatViewModel, system prompt: the base system prompt (time, memory
//  status, environment fragments) and ensureContextFixedTokens, which measures
//  its fixed token cost for the send-time capacity check. The cached time
//  string it reuses is stored in AIChatViewModel.swift.
//
//  Map of the AIChatViewModel files: AIChatViewModel.swift.
//

import AVFoundation
import Combine
import CryptoKit
import Foundation
import ImageIO
import SQLite3
import UIKit
import os.log

extension AIChatViewModel {
    private var approximateTimeString: String {
        let now = Date()
        if now.timeIntervalSince(_cachedTimeDate) > 3600 {
            let fmt = DateFormatter()
            fmt.dateFormat = "yyyy-MM-dd HH:00"
            fmt.timeZone = .current
            _cachedTimeString = fmt.string(from: now)
            _cachedTimeDate = now
        }
        return _cachedTimeString
    }

    /// [T-ctx-measure-outbound] The send-time capacity check runs before any
    /// agent loop has built this turn's system prompt, and a transcript written
    /// before calibration pairs existed seeds no fixed share. Without one the
    /// check would judge history alone and under-read by the whole system
    /// prompt + tool schemas. The base prompt and default tools are the bulk of
    /// it; the loop replaces this with the exact figure (skills, MCP and memory
    /// fragments included) before its first request.
    func ensureContextFixedTokens() {
        guard contextFixedTokens == 0 else { return }
        contextFixedTokens = ContextSizeMeter.estimateFixedTokens(systemPrompt: baseSystemPrompt,
                                                                  tools: makeAgentTools())
    }

    // MARK: - [T-system-prompt-prose] App-owned prompt blocks
    //
    // The user-editable STATIC PROSE lives in SystemPromptProseStore; the tool
    // bullet list and the dynamic time/languages line stay app-owned here and
    // are spliced back around the prose (see spliceToolBullets). Keeping them
    // out of the editable text is what preserves the prompt/schema-drift rule:
    // every registered tool is advertised regardless of what the prose says.

    /// The app-owned `Available tools:` bullet list (shell / files / optional
    /// browser / optional sub-agent + roster / memory). Mirrors the pre-existing
    /// bullet wording byte-for-byte; gated exactly as before so the prompt never
    /// claims a tool the schema does not carry.
    private func toolBulletsBlock() -> String {
        "- shell_execute: Run any shell command. Each invocation is an isolated process with stdout/stderr captured. "
            + "Prefer this for most tasks — it is a real Linux environment with persistent filesystem. "
            + "Common tools (python3, pip, curl, wget, git, ssh, etc.) can be installed via apk add; Python packages via pip install. "
            + "Use `which <cmd>` to check if a tool is already installed before running apk add — many packages persist across sessions. "
            + "When you need to wait before checking results (e.g. polling, waiting for a process), use the `delay` parameter instead of `sleep` in the command — "
            + "delay blocks the agent flow without occupying the iSH shell, so other concurrent tasks can use it during the wait. This avoids resource contention. "
            // [T-agent-prompt-no-phantom-checkins] Device log 2026-07-17: the agent
            // promised "I'll check every 1-2 minutes" after dispatching a long task,
            // then went silent — it assumed some scheduler would wake it. Recurred
            // 2026-07-20 in a variant the old wording didn't name: one status check,
            // then "let's keep waiting" and the turn ended. Rewritten borrowing
            // Hermes Agent's execution-discipline phrasing (act immediately instead
            // of describing intentions / keep working until complete / never end a
            // turn with a promise of future action), plus an HONEST off-ramp —
            // Minis has no in-app scheduler (see 'Scheduled tasks'), so the only
            // truthful alternatives are poll-now or tell-the-user-nothing-runs.
            + "Execution discipline for long-running or dispatched work: make tool calls immediately instead of describing intentions, and keep working until the task is complete. "
            + "Within a turn, `delay` is your wait mechanism — to follow up on something still running, chain delay-then-check calls at a task-appropriate interval until you have the result or hit a sensible retry cap. For a check that should happen AFTER this turn ends, register it by running `minis-scheduled create …` via shell_execute (see the CLI list) — it fires a new turn later, best effort while the app is alive. "
            + "NEVER end a turn with a promise of future action: 'I'll keep monitoring', 'will sync the result later', and ending right after a single still-running status check with 'let's keep waiting' are all the same violation — once your turn ends, NOTHING runs until the user's next message. "
            + "If polling to completion is genuinely not worth blocking the turn, close honestly instead: either register a `minis-scheduled` follow-up (say so, with its id) or state that you will only learn the outcome when the user next messages; for anything that must fire even if the app is killed, point them to an Apple Shortcuts automation per 'Scheduled tasks' later in this prompt.\n"
            + "- file_read: Read file contents (faster than cat).\n"
            + "- file_write: Create new files or overwrite existing files (faster than echo/tee).\n"
            + "- file_edit: Edit existing files with exact string replacement (old_string → new_string). Preferred over file_write for modifications — always file_read first.\n"
            // [T-tools-master-switch] Settings › Tools off ⇒ neither bullet:
            // the tool is not in the schema, so the prompt must not claim it.
            + (!Self.toolEnabled(.browser) ? "" :
               "- browser_use: Web browsing (navigate, screenshot, click, type, get_text, scroll, scroll_and_collect, get_readable, get_backbone, fetch, etc.). "
               + "Starts with a desktop Safari user agent. Use screenshot to see the page.\n")
            // [T-p1-delegate-task] Only the parent tier sees helpers; the bullet
            // ships in the same build as the tool (the 2026-07-17/20 lesson:
            // a capability the prompt does not describe honestly gets misused).
            + ((isHelper || !Self.toolEnabled(.agents)) ? "" :
               "- subagent_task: Delegate a self-contained task to a sub agent that runs its own tool loop in an isolated hidden session, and inspect or stop the ones you started. Full contract in the tool schema. Two things it does not say: the `<agent_callback>` result and progress messages are written by the system, not typed by the user; and while a sub agent runs the user sees it in the tool bar and can watch or stop it.\n")
            // [T-sub-agents-v1] The roster the model chooses from, same gate as
            // the bullet above. Generated from the same list the tool schema's
            // enumValues use, so a name can never appear in one and not the other.
            + ((isHelper || !Self.toolEnabled(.agents)) ? "" : Self.subAgentRosterSection())
            + "- memory_write: Save a memory entry to today's daily log (YYYY-MM-DD.md). Use proactively to note user preferences, project patterns, and important context.\n"
            + "- memory_get: Recall memories with keyword search. Check memory at the start of new topics to leverage past knowledge.\n"
            // [T-user-ask-prompt-consolidated] The only user_ask text in the
            // system prompt, and only when the tool is in this run (switch on,
            // not a sub agent). Everything else travels with the tool itself.
            + UserAskPromptText.systemPromptText(exposed: userAskExposed)
    }

    /// The dynamic time / device-languages line — regenerated per request, so it
    /// is deliberately NOT part of the editable prose.
    private func timeLanguagesBlock() -> String {
        "Current time (approximate): \(approximateTimeString) (\(TimeZone.current.identifier)). "
            + "Device languages: \((UserDefaults.standard.object(forKey: "AppleLanguages") as? [String] ?? Locale.preferredLanguages).joined(separator: ", ")).\n\n"
    }

    /// Compose the editable prose with the app-owned tool bullets spliced in at
    /// the `Available tools:` header and the dynamic time block dropped in just
    /// before `Shared directory`. Falls back to prepending the bullets when a
    /// user edit removed the header, so the drift rule still holds.
    private static func spliceToolBullets(prose: String, bullets: String, timeBlock: String) -> String {
        let anchor = "Available tools:"
        var result: String
        if let idx = prose.range(of: anchor) {
            let headEnd = idx.upperBound
            result = prose[..<headEnd] + "\n" + bullets + prose[headEnd...]
        } else {
            result = bullets + "\n\n" + prose
        }
        // Drop the time/languages line immediately before the shared-directory
        // listing, matching the original ordering.
        let shared = "Shared directory /var/minis/"
        if let sidx = result.range(of: shared) {
            result = result[..<sidx.lowerBound] + timeBlock + result[sidx.lowerBound...]
        } else {
            result += "\n" + timeBlock
        }
        return result
    }

    /// [T-system-prompt-prose] App-owned dynamic notes the static prose cannot
    /// carry (they depend on the browser / delegation switches). Kept OUT of the
    /// user-editable prose block and appended after it, so editing the prose
    /// never drops a live capability notice. Mirrors Android `minisUrlNote` /
    /// `scheduledDelegateNote` / `modelUseDelegateNote` in
    /// ChatViewModel.buildSystemPrompt().
    private var minisUrlNote: String {
        guard Self.toolEnabled(.browser) else {
            return "\n\nIMPORTANT: minis:// URLs are app-internal — they are NOT web URLs; minis:// action URLs (open_terminal, views, settings) are app deep links, use Markdown links in chat. "
        }
        return "\n\nIMPORTANT: minis:// URLs are app-internal — they are NOT web URLs. Do NOT pass minis:// action URLs (open_terminal, views, settings) to browser_use — those are app deep links, use Markdown links in chat instead. "
            + "However, minis:// resource URLs CAN be opened in browser_use with navigate. All directories under /var/minis/ are accessible: workspace, attachments, offloads, shared, etc. "
            + " (same action-vs-resource rule as the minis:// section above: only /var/minis resource URLs may go to browser_use)"
    }

    /// The two sub-agent delegation pointers, gated exactly as the tool bullet
    /// (Settings › Agents on, parent tier only).
    private var delegationNotes: String {
        guard Self.toolEnabled(.agents) && !isHelper else { return "" }
        return "\n\n(Do NOT run minis-scheduled to delegate work you could hand to subagent_task.) For delegating a whole task that needs tools and multiple rounds, use the subagent_task tool instead. "
    }

    /// The live model-count sentence — an inline `\(…)` interpolation in the
    /// prose literal, so the editable static text cannot carry it.
    private var modelCountNote: String {
        "\n\nYou have \(ProviderConfigStore.shared.resolvedAgentLoopEntries.count) model(s) available. "
    }

    var baseSystemPrompt: String {
        // [T-soul-md] Layer 1 is rendered by SystemPromptBuilder, which
        // owns the "You are <name>, a capable AI assistant running on an
        // iOS device ..." identity sentence (parametric on SOUL.md's
        // `name` field) and optionally appends a clearly-labeled
        // Personality section from SOUL.md's body. The original wording
        // is preserved inside SystemPromptBuilder.identityTemplate so we
        // don't regress model behavior that depended on it.
        (helperConfig != nil ? helperIdentitySection : SystemPromptBuilder.identitySection())
            // [T-system-prompt-prose] The STATIC PROSE block (runtime guidance,
            // shared-directory listing, minis:// scheme, file-creation guidelines,
            // tool-call style, tone, Native Apple framework commands, env-var rules,
            // scheduled-tasks) is user-editable via Settings → System Prompt and
            // loaded from SystemPromptProseStore (SYSTEM_PROMPT.md). The tool
            // bullet list is app-owned and spliced back in at the
            // `Available tools:` anchor so it is never lost regardless of the
            // prose the user writes.
            + Self.spliceToolBullets(
                prose: SystemPromptProseStore.load(),
                bullets: toolBulletsBlock(),
                timeBlock: timeLanguagesBlock()
            )
            + minisUrlNote
            + delegationNotes
            + modelCountNote
    }

    /// [T-memory-toggle-gates-injection-and-tools-ios]
    /// One-paragraph addendum that tells the model the current memory
    /// gate state. Appended to baseSystemPrompt at every assemble site.
    ///
    /// When memory is OFF, the earlier "Available tools" and "Memory system"
    /// sections in baseSystemPrompt still describe memory_get / memory_write
    /// as if they existed. We don't surgically rewrite those (a giant
    /// string concat that's already on the type-checker's edge); instead
    /// we land an authoritative override at the end of the prompt so the
    /// model knows the tools won't be registered, the memory files won't
    /// be injected, and what to tell the user if they ask about memory.
    var memoryStatusFragment: String {
        if memoryEnabled {
            return "\n\nMemory status: ENABLED for this session. GLOBAL.md and recent daily logs have been injected above (if non-empty), and memory_get / memory_write are available in the tool list."
        } else {
            return "\n\nMemory status: DISABLED for this session. "
                + "The user has turned off memory for this conversation. "
                + "Despite any earlier mentions in this prompt:\n"
                + "- GLOBAL.md and daily logs have NOT been injected — you do not have access to past memories.\n"
                + "- The memory_get and memory_write tools are NOT registered — do not attempt to call them; they will not appear in your tool list.\n"
                + "- If the user asks you to recall memories, save a memory, or wonders why earlier memories aren't visible, tell them memory is currently disabled for this session and they can re-enable it via the /memory slash command or in Settings.\n"
                + "- This toggle is per-session. Other sessions may still have memory enabled.\n"
                + "- SOUL.md (your persona / identity) is unaffected and remains active above."
        }
    }
}
