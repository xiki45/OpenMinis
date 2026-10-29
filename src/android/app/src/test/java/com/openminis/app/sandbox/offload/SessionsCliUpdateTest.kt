package com.openminis.app.sandbox.offload

import androidx.room.Room
import com.openminis.app.data.db.AppDatabase
import com.openminis.app.data.repository.ChatRepository
import com.openminis.app.sandbox.NativeOffloadRequest
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * [T-sessions-cli-update] `minis-sessions-cli update / groups` and the new
 * `list` / `status` fields, end to end through the real handler on an
 * in-memory Room DB. The cases are the cross-platform list from the task
 * (each field alone and combined, a missing group is created, "" leaves the
 * group, a bad icon, missing arguments, a child session, an unknown id,
 * `list --group`, `groups`, --compact / -q).
 */
@RunWith(RobolectricTestRunner::class)
class SessionsCliUpdateTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: ChatRepository
    private lateinit var cli: SessionsOffloadHandler
    private var regenWorks = true
    private var regenCalls = 0

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(org.robolectric.RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        repo = ChatRepository(db.chatDao())
        cli = SessionsOffloadHandler(repo, isRunning = { false }, hasLiveJob = { false }, titleRegenerator = { id ->
            regenCalls++
            if (regenWorks) repo.updateSessionTitleAndCategory(id, "Auto title", "chat")
            regenWorks
        })
    }

    @After
    fun tearDown() = db.close()

    private fun run(vararg argv: String): Pair<Int, String> {
        val r = cli.handle(NativeOffloadRequest(pid = 1, argv = listOf("minis-sessions-cli") + argv, env = emptyMap(), cwd = "/"))
        return r.exitCode to r.output
    }

    /** The JSON envelope at the start of the output (help text may follow on errors). */
    private fun json(out: String): JSONObject {
        var depth = 0
        for ((i, c) in out.withIndex()) {
            if (c == '{') depth++
            if (c == '}' && --depth == 0) return JSONObject(out.substring(0, i + 1))
        }
        error("no JSON in: $out")
    }

    private fun session(title: String? = "Old title", parent: String? = null) =
        runBlocking { repo.createSession(modelId = "m", title = title, parentSessionId = parent).id }

    private fun data(out: String) = json(out).getJSONObject("data")

    /** iOS's error envelope: {ok:false, tool, action, error:{code, message}}. */
    private fun errCode(out: String) = json(out).also { assertFalse(it.getBoolean("ok")) }.getJSONObject("error").getString("code")

    private fun changed(d: JSONObject) = d.getJSONArray("changed").let { a -> List(a.length()) { a.getString(it) } }

    @Test
    fun `1 title alone`() {
        val id = session()
        val (code, out) = run("update", "--id", id, "--title", "新标题")
        assertEquals(0, code)
        val d = data(out)
        assertEquals("新标题", d.getString("title"))
        assertEquals(listOf("title"), changed(d))
        assertEquals("", d.getString("group"))
        assertEquals("", d.getString("icon"))
        assertEquals("新标题", runBlocking { repo.getSession(id) }!!.title)
    }

    @Test
    fun `2 icon alone, then back to the default`() {
        val id = session()
        assertEquals("code", data(run("update", "--id", id, "--icon", "code").second).getString("icon"))
        assertEquals("code", runBlocking { repo.getSession(id) }!!.category)
        assertEquals("Old title", runBlocking { repo.getSession(id) }!!.title)
        val d = data(run("update", "--id", id, "--icon", "").second)
        assertEquals("", d.getString("icon"))
        assertEquals("icon", d.getJSONArray("changed").getString(0))
        assertNull(runBlocking { repo.getSession(id) }!!.category)
    }

    @Test
    fun `3 a missing group is created, 4 an existing one is reused by exact name`() {
        val id = session()
        val d = data(run("update", "--id", id, "--group", "项目A").second)
        assertEquals("项目A", d.getString("group"))
        assertEquals(listOf("group"), changed(d))
        val folders = runBlocking { repo.listFolders() }
        assertEquals(1, folders.size)
        val id2 = session()
        run("update", "--id", id2, "--group", "项目A")
        assertEquals(1, runBlocking { repo.listFolders() }.size)
        // Case-sensitive: a different case is a different group.
        run("update", "--id", id2, "--group", "项目a")
        assertEquals(2, runBlocking { repo.listFolders() }.size)
    }

    @Test
    fun `5 empty group leaves the group, 6 combined change`() {
        val id = session()
        run("update", "--id", id, "--group", "Work")
        val d = data(run("update", "--id", id, "--group", "").second)
        assertEquals("", d.getString("group"))
        assertNull(runBlocking { repo.getSession(id) }!!.folderId)
        // The group itself stays (leaving it is not dissolving it).
        assertEquals(1, runBlocking { repo.listFolders() }.size)

        val all = data(run("update", "--id", id, "--title", "T", "--group", "Work", "--icon", "research").second)
        assertEquals("T", all.getString("title"))
        assertEquals("Work", all.getString("group"))
        assertEquals("research", all.getString("icon"))
        // Fixed order, as iOS: title, group, icon.
        assertEquals(listOf("title", "group", "icon"), changed(all))
    }

    @Test
    fun `7 an unchanged value is not reported as changed`() {
        val id = session(title = "Same")
        val d = data(run("update", "--id", id, "--title", "Same").second)
        assertEquals(0, d.getJSONArray("changed").length())
    }

    @Test
    fun `8 bad icon - invalid_icon with the valid names, nothing written`() {
        val id = session()
        for (bad in listOf("rocket", "🚀", "terminal.fill")) {
            val (code, out) = run("update", "--id", id, "--title", "New", "--icon", bad)
            assertEquals(bad, 2, code)
            assertEquals(bad, "invalid_icon", errCode(out))
            assertTrue(json(out).getJSONObject("error").getString("message").contains("productivity"))
            assertEquals(16, json(out).getJSONObject("error").getJSONArray("valid_icons").length())
        }
        // Argument errors come before the lookup (shared case 22).
        assertEquals("invalid_icon", errCode(run("update", "--id", "no-such-id", "--icon", "rocket").second))
        assertEquals("Old title", runBlocking { repo.getSession(id) }!!.title)
    }

    @Test
    fun `9 missing arguments - invalid_arguments`() {
        val id = session()
        for (argv in listOf(
            arrayOf("update", "--title", "x"),          // no --id
            arrayOf("update", "--id", id),              // no field
            arrayOf("update", "--id", id, "--title", ""), // empty title
            arrayOf("update", "--id", id, "--title", "   "), // blank title
            arrayOf("update", "--id", id, "--title"),   // value missing
            arrayOf("update", "--id", id, "--title", "x", "--title-auto"), // both
        )) {
            val (code, out) = run(*argv)
            assertEquals(argv.joinToString(" "), 2, code)
            assertEquals(argv.joinToString(" "), "invalid_arguments", errCode(out))
        }
        assertEquals("Old title", runBlocking { repo.getSession(id) }!!.title)
    }

    @Test
    fun `10 child session - not_allowed, 11 unknown id - session_not_found`() {
        val parent = session()
        val child = session(parent = parent)
        val (c1, o1) = run("update", "--id", child, "--title", "x")
        assertEquals(1, c1)
        assertEquals("not_allowed", errCode(o1))
        assertEquals("Old title", runBlocking { repo.getSession(child) }!!.title)
        val (c2, o2) = run("update", "--id", "no-such-id", "--title", "x")
        assertEquals(1, c2)
        assertEquals("session_not_found", errCode(o2))
    }

    @Test
    fun `12 list carries title group icon and filters by group`() {
        val a = session(title = "A")
        val b = session(title = null)
        run("update", "--id", a, "--group", "Work", "--icon", "code")
        val all = data(run("list").second).getJSONArray("sessions")
        val byId = List(all.length()) { all.getJSONObject(it) }.associateBy { it.getString("session_id") }
        assertEquals("Work", byId[a]!!.getString("group"))
        assertEquals("code", byId[a]!!.getString("icon"))
        assertEquals("", byId[b]!!.getString("group"))
        assertEquals("", byId[b]!!.getString("icon"))
        assertEquals("", byId[b]!!.getString("title"))

        val work = data(run("list", "--group", "Work").second)
        assertEquals(1, work.getInt("count"))
        assertEquals(a, work.getJSONArray("sessions").getJSONObject(0).getString("session_id"))
        val ungrouped = data(run("list", "--group", "").second)
        assertEquals(listOf(b), List(ungrouped.getInt("count")) { ungrouped.getJSONArray("sessions").getJSONObject(it).getString("session_id") })
        assertEquals(0, data(run("list", "--group", "Nope").second).getInt("count"))
    }

    @Test
    fun `13 groups lists every group with its session count`() {
        val a = session()
        val b = session()
        run("update", "--id", a, "--group", "Work")
        run("update", "--id", b, "--group", "Work")
        run("update", "--id", b, "--group", "Home")
        val g = data(run("groups").second)
        assertEquals(2, g.getInt("count"))
        val counts = List(g.getInt("count")) { g.getJSONArray("groups").getJSONObject(it) }
            .associate { it.getString("name") to it.getInt("session_count") }
        assertEquals(mapOf("Work" to 1, "Home" to 1), counts)
    }

    @Test
    fun `14 status carries group and icon`() {
        val id = session()
        run("update", "--id", id, "--group", "Work", "--icon", "math")
        val d = data(run("status", "--id", id).second)
        assertEquals("Work", d.getString("group"))
        assertEquals("math", d.getString("icon"))
    }

    @Test
    fun `15 compact and quiet shape the update output like every other command`() {
        val id = session()
        val compact = run("update", "--id", id, "--title", "C", "--compact").second.trim()
        assertFalse(compact.contains("\n"))
        assertTrue(JSONObject(compact).getBoolean("ok"))
        val quiet = JSONObject(run("update", "--id", id, "--title", "Q", "-q").second.trim())
        assertEquals("Q", quiet.getString("title"))
        assertFalse(quiet.has("ok"))
    }

    @Test
    fun `icon names are the session list's categories`() {
        val ui = java.io.File("src/main/java/com/openminis/app/ui/sessions/SessionListScreen.kt").readText()
        val grid = ui.substringAfter("private val allCategories = listOf(").substringBefore(")")
        val names = Regex("\"([A-Za-z]+)\"").findAll(grid).map { it.groupValues[1].lowercase() }.toList()
        assertEquals(SessionsUpdateRules.ICON_NAMES, names)
    }

    @Test
    fun `16 title-auto runs Regenerate Title first, its failure writes nothing`() {
        val id = session(title = null)
        val d = data(run("update", "--id", id, "--title-auto", "--group", "Work").second)
        assertEquals("Auto title", d.getString("title"))
        assertEquals("chat", d.getString("icon"))
        assertEquals(listOf("title", "group", "icon"), changed(d))
        assertEquals(1, regenCalls)

        regenWorks = false
        val other = session(title = null)
        val (code, out) = run("update", "--id", other, "--title-auto", "--group", "Home", "--icon", "math")
        assertEquals(1, code)
        assertEquals("title_auto_failed", errCode(out))
        val s = runBlocking { repo.getSession(other) }!!
        assertNull(s.folderId)
        assertNull(s.category)
    }

    @Test
    fun `17 group names are trimmed and duplicate names pick the most recently active group`() {
        val id = session()
        val d = data(run("update", "--id", id, "--group", "  Work  ").second)
        assertEquals("Work", d.getString("group"))
        assertEquals("", data(run("update", "--id", id, "--group", "   ").second).getString("group"))

        val rules = SessionsUpdateRules
        val refs = listOf(
            SessionsUpdateRules.FolderRef("old-empty", "W", createdAt = 1),
            SessionsUpdateRules.FolderRef("busy", "W", createdAt = 5),
            SessionsUpdateRules.FolderRef("quiet", "W", createdAt = 2),
            SessionsUpdateRules.FolderRef("other", "X", createdAt = 0),
        )
        assertEquals("busy", rules.pickFolder("W", refs, mapOf("busy" to 100L, "quiet" to 50L)))
        assertEquals("quiet", rules.pickFolder("W", refs, mapOf("quiet" to 50L)))
        assertEquals("old-empty", rules.pickFolder("W", refs, emptyMap()))
        assertEquals("other", rules.pickFolder("X", refs, emptyMap()))
        assertNull(rules.pickFolder("w", refs, emptyMap()))
    }

    @Test
    fun `18 a no-op update writes nothing and keeps the list order`() {
        val id = session(title = "Same")
        val before = runBlocking { repo.getSession(id) }!!.updatedAt
        Thread.sleep(5)
        assertEquals(0, data(run("update", "--id", id, "--title", "Same", "--icon", "").second).getJSONArray("changed").length())
        assertEquals(before, runBlocking { repo.getSession(id) }!!.updatedAt)
    }

    @Test
    fun `19 quiet prints just the error object on failure`() {
        val out = run("update", "--id", "nope", "--title", "x", "-q", "--compact").second.trim()
        val e = JSONObject(out)
        assertEquals("session_not_found", e.getString("code"))
        assertTrue(e.has("message"))
    }

    @Test
    fun `20 icon keys are trimmed and case-insensitive (shared case 29)`() {
        val id = session()
        val d = data(run("update", "--id", id, "--icon", " Code ").second)
        assertEquals("code", d.getString("icon"))
        assertEquals("code", runBlocking { repo.getSession(id) }!!.category)
        assertEquals("", data(run("update", "--id", id, "--icon", "  ").second).getString("icon"))
    }

    @Test
    fun `21 groups - home list order, empty groups last, child sessions not counted, timestamp`() {
        val a = session()
        val b = session()
        run("update", "--id", a, "--group", "Older")
        run("update", "--id", b, "--group", "Newer")
        runBlocking {
            repo.createFolder("Empty")
            repo.createSession(modelId = "m", title = "child", parentSessionId = a).let { repo.setFolderForSessions(repo.listFolders().first { it.name == "Older" }.id, listOf(it.id)) }
            db.chatDao().updateSessionTitle(b, "touch", System.currentTimeMillis() + 10_000)
        }
        val out = run("groups").second
        val g = data(out).getJSONArray("groups")
        assertEquals(listOf("Newer", "Older", "Empty"), List(g.length()) { g.getJSONObject(it).getString("name") })
        assertEquals(1, g.getJSONObject(1).getInt("session_count"))
        assertEquals(0, g.getJSONObject(2).getInt("session_count"))
        assertTrue(json(out).getString("timestamp").matches(Regex("""\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d([+-]\d\d:\d\d|Z)""")))
    }

    @Test
    fun `22 an option is not a value (shared case 20)`() {
        val id = session()
        val (code, out) = run("update", "--id", id, "--title", "--group", "GA")
        assertEquals(2, code)
        assertEquals("invalid_arguments", errCode(out))
        assertTrue(runBlocking { repo.listFolders() }.isEmpty())
    }

    @Test
    fun `23 unknown command names update and groups (shared case 28)`() {
        val (code, out) = run("frobnicate")
        assertEquals(2, code)
        assertTrue(out.contains("update") && out.contains("groups"))
    }
}
