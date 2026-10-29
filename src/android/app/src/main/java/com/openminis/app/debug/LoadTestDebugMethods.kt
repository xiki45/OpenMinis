package com.openminis.app.debug

import android.app.ActivityManager
import android.content.Context
import com.openminis.app.BuildConfig
import com.openminis.app.agent.jobs.AgentJobRegistry
import com.openminis.app.sandbox.GlobalShellThrottle
import com.openminis.app.service.SessionConcurrencyManager
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicIntegerArray

/**
 * [T-loadtest-limits] DEV_TOOLS-only support for on-device load tests:
 *
 * - `debug.limits.get` / `debug.limits.set`: read or override, in memory, the
 *   concurrency caps admission uses - agent loop sessions (default 6), sub
 *   agent jobs PER PARENT conversation (3; [T-new-limits], the key is still
 *   `childJobs`, `childJobsPerParent` is accepted as an alias) and the shell
 *   process budget (16). Absent = unchanged,
 *   null / "default" = back to the default. A process restart always resets
 *   them, and release builds neither run the debug server nor accept the
 *   override (each setter checks BuildConfig.DEV_TOOLS too).
 * - `debug.loadtest.snapshot`: one cheap sample of the app process - Java and
 *   native heap, total PSS, thread count, the system's availMem / threshold /
 *   lowMemory, onTrimMemory counts per level since start, and the slot / queue
 *   counters - for the host-side sampler.
 */
internal object LoadTestDebugMethods {

    /** onTrimMemory calls per level (index = level, capped at 80). Recorded by MinisApp. */
    private val trimCounts = AtomicIntegerArray(81)

    fun noteTrim(level: Int) {
        trimCounts.incrementAndGet(level.coerceIn(0, 80))
    }

    private fun requireDevTools() {
        if (!BuildConfig.DEV_TOOLS) throw RPCException(-32601, "load-test methods exist only in debug builds")
    }

    fun limitsGet(): JSONObject {
        requireDevTools()
        return JSONObject()
            .put("sessions", SessionConcurrencyManager.maxConcurrent)
            .put("sessionsDefault", SessionConcurrencyManager.MAX_CONCURRENT)
            // [T-new-limits] Per parent conversation since the cap stopped being
            // global; the key keeps its name for existing load-test scripts.
            .put("childJobs", AgentJobRegistry.maxConcurrentChildJobsPerParent)
            .put("childJobsDefault", AgentJobRegistry.MAX_CONCURRENT_CHILD_JOBS_PER_PARENT)
            .put("childJobsScope", "per_parent_session")
            .put("queuedDelegationsPerParent", AgentJobRegistry.MAX_QUEUED_DELEGATIONS_PER_PARENT)
            .put("shellProcessBudget", GlobalShellThrottle.processBudget)
            .put("shellProcessBudgetDefault", GlobalShellThrottle.PROCESS_BUDGET)
    }

    /** null for "absent" (unchanged); Int? inside for a value or a reset. */
    private fun param(params: JSONObject, key: String): Pair<Boolean, Int?> {
        if (!params.has(key)) return false to null
        if (params.isNull(key)) return true to null
        val raw = params.opt(key)
        if (raw is String && raw.equals("default", ignoreCase = true)) return true to null
        return true to params.getInt(key)
    }

    fun limitsSet(params: JSONObject): JSONObject {
        requireDevTools()
        val (s, sv) = param(params, "sessions")
        if (s) SessionConcurrencyManager.debugSetMaxConcurrent(sv)
        // `childJobsPerParent` wins when both are given; `childJobs` is the
        // original key and still works.
        val (cp, cpv) = param(params, "childJobsPerParent")
        val (c, cv) = if (cp) cp to cpv else param(params, "childJobs")
        if (c) AgentJobRegistry.debugSetMaxConcurrentChildJobs(cv)
        val (b, bv) = param(params, "shellProcessBudget")
        if (b) GlobalShellThrottle.debugSetProcessBudget(bv)
        return limitsGet()
    }

    /**
     * [T-loadtest-seed] Create a titled `loadtest-*` session, optionally with
     * synthetic history, for load tests. A preset title means no title request
     * goes to the user's (real, billed) title sub-model; synthetic rows build a
     * 200-500 message history in seconds instead of hundreds of model turns.
     *
     * Params: title (must start with "loadtest-"), modelId, turns (user +
     * assistant pairs, default 0), chars (per text message, default 400),
     * toolEvery (every Nth turn is a shell tool call + result instead of plain
     * text, 0 = never), toolOutputChars (default 4000).
     */
    suspend fun seedSession(context: Context, params: JSONObject): JSONObject {
        requireDevTools()
        val title = params.optString("title")
        if (!title.startsWith("loadtest-")) throw RPCException(-32602, "title must start with 'loadtest-'")
        val modelId = params.optString("modelId").ifEmpty { throw RPCException(-32602, "modelId required") }
        val turns = params.optInt("turns", 0).coerceIn(0, 5000)
        val chars = params.optInt("chars", 400).coerceIn(1, 200_000)
        val toolEvery = params.optInt("toolEvery", 0).coerceAtLeast(0)
        val toolOutputChars = params.optInt("toolOutputChars", 4000).coerceIn(1, 500_000)
        val repo = (context.applicationContext as com.openminis.app.MinisApp).chatRepository
        val session = repo.createSession(modelId = modelId, title = title)
        val esc = com.openminis.app.ui.chat.ChatPartsJson::escapeJson
        fun filler(i: Int, n: Int): String {
            val unit = "loadtest filler $i lorem ipsum dolor sit amet, consectetur adipiscing elit. "
            return unit.repeat(n / unit.length + 1).take(n)
        }
        var rows = 0
        for (i in 0 until turns) {
            repo.appendMessage(session.id, "user", """[{"type":"text","value":${esc(filler(i, chars))}}]""")
            rows++
            if (toolEvery > 0 && i % toolEvery == toolEvery - 1) {
                val id = "toolu_seed_${i}"
                val input = """{"tool_title":"seed $i","command":"echo seed $i"}"""
                repo.appendMessage(
                    session.id, "assistant",
                    """[{"type":"toolUse","value":{"toolUseId":${esc(id)},"name":"shell_execute","input":${esc(input)},"description":"seed $i","pageURL":"","imageFilePath":"","thoughtSignature":null}}]""",
                )
                repo.appendMessage(
                    session.id, "user",
                    """[{"type":"toolResult","value":{"toolUseId":${esc(id)},"name":"shell_execute","output":${esc(filler(i, toolOutputChars))},"success":true}}]""",
                )
                rows += 2
            }
            repo.appendMessage(session.id, "assistant", """[{"type":"text","value":${esc(filler(i, chars))}}]""")
            rows++
        }
        return JSONObject().put("sessionId", session.id).put("title", title).put("rows", rows)
    }

    fun snapshot(context: Context): JSONObject {
        requireDevTools()
        val rt = Runtime.getRuntime()
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val dmi = android.os.Debug.MemoryInfo().also { android.os.Debug.getMemoryInfo(it) }
        val threads = runCatching {
            java.io.File("/proc/self/status").readLines().firstOrNull { it.startsWith("Threads:") }
                ?.substringAfter(':')?.trim()?.toInt()
        }.getOrNull() ?: -1
        val trims = JSONObject()
        for (i in 0 until trimCounts.length()) {
            val n = trimCounts.get(i)
            if (n > 0) trims.put(i.toString(), n)
        }
        return JSONObject()
            .put("tMs", System.currentTimeMillis())
            .put("javaHeapUsedKb", (rt.totalMemory() - rt.freeMemory()) / 1024)
            .put("javaHeapMaxKb", rt.maxMemory() / 1024)
            .put("nativeHeapAllocKb", android.os.Debug.getNativeHeapAllocatedSize() / 1024)
            .put("totalPssKb", dmi.totalPss)
            .put("dalvikPssKb", dmi.dalvikPss)
            .put("nativePssKb", dmi.nativePss)
            .put("otherPssKb", dmi.otherPss)
            .put("threads", threads)
            .put("availMemKb", mi.availMem / 1024)
            .put("thresholdKb", mi.threshold / 1024)
            .put("lowMemory", mi.lowMemory)
            .put("trims", trims)
            .put("runningSessions", JSONArray(SessionConcurrencyManager.runningSessions.value.toList()))
            .put("suspendedSessions", JSONArray(SessionConcurrencyManager.suspendedSessions.value))
            .put("childJobsActive", AgentJobRegistry.totalRunningChildJobCount)
            .put("childJobsActiveByParent", JSONObject(AgentJobRegistry.runningChildJobCountsByParent() as Map<*, *>))
            .put("limits", limitsGet())
    }
}
