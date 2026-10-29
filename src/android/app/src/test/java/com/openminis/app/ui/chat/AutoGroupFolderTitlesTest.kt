package com.openminis.app.ui.chat

import androidx.room.Room
import com.openminis.app.ProductionSources
import com.openminis.app.data.db.AppDatabase
import com.openminis.app.data.db.ChatSessionEntity
import com.openminis.app.data.db.FolderMemberTitle
import com.openminis.app.data.repository.ChatRepository
import com.openminis.app.ui.chat.FolderOfferFormat.Recent
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * [T-autogroup-folder-titles] The auto-grouping offer in the title prompt
 * carries up to three recent chat titles per group (iOS 72350576a). Vectors
 * 1-4 are iOS AutoGroupFolderTitlesTests as ported to Windows
 * (minis-agent title.rs `folder_lines_carry_recent_titles_as_on_ios`).
 */
@RunWith(RobolectricTestRunner::class)
class AutoGroupFolderTitlesTest {

    private fun r(at: Long, vararg titles: String) = Recent(at, titles.toList())

    @Test
    fun `format, truncation, newlines, quotes and de-duplication`() {
        val long = "x".repeat(50)
        val recent = mapOf(
            "w" to r(10, "修复登录页", "周报草稿", "修复登录页", "API 评审", "第四个不显示"),
            "p" to r(5, "Line one\nline two", long, "Say \"hi\"", "  "),
        )
        val (out, n) = FolderOfferFormat.lines(listOf("w", "p"), listOf("\"工作\" — 公司项目相关", "\"Personal\""), recent)
        assertEquals("\"工作\" — 公司项目相关 (e.g. \"修复登录页\", \"周报草稿\", \"API 评审\")", out[0])
        val cut = "x".repeat(39) + "…"
        assertEquals("\"Personal\" (e.g. \"Line one line two\", \"$cut\", \"Say 'hi'\")", out[1])
        assertEquals(2, n)
    }

    @Test
    fun `groups without a titled chat keep their plain line`() {
        val (out, n) = FolderOfferFormat.lines(
            listOf("a", "b", "c"),
            listOf("\"Empty\" — nothing yet", "\"Bare\"", "\"Full\""),
            mapOf("c" to r(1, "Only one")),
        )
        assertEquals(listOf("\"Empty\" — nothing yet", "\"Bare\"", "\"Full\" (e.g. \"Only one\")"), out)
        assertEquals(1, n)
    }

    @Test
    fun `no titles anywhere - the old lines byte for byte`() {
        val plain = listOf("\"Work\" — Office", "\"Home\"", "\"Misc\"")
        assertEquals(plain to 0, FolderOfferFormat.lines(listOf("a", "b", "c"), plain, emptyMap()))
        // Titles that clean to nothing do not count either.
        assertEquals(plain to 0, FolderOfferFormat.lines(listOf("a", "b", "c"), plain, mapOf("a" to r(1, " ", "\n"))))
    }

    @Test
    fun `only the 20 most recently active groups get titles, ties keep the offer order`() {
        val ids = (0 until 25).map { "f$it" }
        val names = (0 until 25).map { "\"G$it\"" }
        val recent = (0 until 25).associate { "f$it" to r(it.toLong(), "T$it") }
        val (out, n) = FolderOfferFormat.lines(ids, names, recent)
        assertEquals(20, n)
        assertEquals((5 until 25).toList(), out.withIndex().filter { "(e.g." in it.value }.map { it.index })
        assertEquals(25, out.size)

        val tie = (0 until 22).map { "t$it" }
        val (out2, _) = FolderOfferFormat.lines(tie, tie, tie.associateWith { r(1, "x") })
        assertEquals(listOf(20, 21), out2.withIndex().filter { "(e.g." !in it.value }.map { it.index })
    }

    @Test
    fun `nothing in a title can close the quoted example or the group list`() {
        assertEquals("a') (b, c", FolderOfferFormat.clean("a\"] [b; c"))
        assertEquals("'curly'", FolderOfferFormat.clean("“curly”"))
        assertEquals("x y", FolderOfferFormat.clean("x{\\}y"))
        assertEquals("bidi", FolderOfferFormat.clean("bi‮di"))
        assertEquals("a b", FolderOfferFormat.clean("a  b"))
        // CJK brackets are ordinary title text here: they cannot close an ASCII quote.
        assertEquals("「草稿」", FolderOfferFormat.clean("「草稿」"))
        // The cut counts code points, so an emoji is never split.
        val emoji = "😀".repeat(45)
        val c = FolderOfferFormat.clean(emoji)
        assertEquals(40, c.codePointCount(0, c.length))
        assertTrue(c.endsWith("…"))
    }

    @Test
    fun `rows group newest first, at most perFolder each, activity from the newest`() {
        val rows = listOf(
            FolderMemberTitle("a", "A1", 30), FolderMemberTitle("a", "A2", 20), FolderMemberTitle("a", "A3", 10),
            FolderMemberTitle("b", "B1", 5),
        )
        val g = FolderOfferFormat.group(rows, perFolder = 2)
        assertEquals(Recent(30, listOf("A1", "A2")), g["a"])
        assertEquals(Recent(5, listOf("B1")), g["b"])
    }

    // ── the query, on a real Room database ─────────────────────────────────

    private lateinit var db: AppDatabase
    private lateinit var repo: ChatRepository

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(org.robolectric.RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        repo = ChatRepository(db.chatDao())
    }

    @After
    fun tearDown() = db.close()

    private fun session(id: String, folder: String?, title: String?, at: Long, parent: String? = null) =
        ChatSessionEntity(
            id = id, title = title, modelId = "m", createdAt = at, updatedAt = at,
            folderId = folder, parentSessionId = parent,
        )

    @Test
    fun `the query reads titled top-level members newest first`() = runBlocking {
        val dao = db.chatDao()
        dao.insertSession(session("s1", "f1", "Old", 1))
        dao.insertSession(session("s2", "f1", "New", 3))
        dao.insertSession(session("s3", "f1", "  ", 4)) // blank
        dao.insertSession(session("s4", "f1", null, 5)) // untitled
        dao.insertSession(session("s5", "f1", "Child", 6, parent = "s2")) // sub agent
        dao.insertSession(session("s6", null, "Loose", 7)) // not filed
        dao.insertSession(session("s7", "f2", "Other", 2))
        val recent = repo.recentSessionTitlesByFolder()
        assertEquals(Recent(3, listOf("New", "Old")), recent["f1"])
        assertEquals(Recent(2, listOf("Other")), recent["f2"])
        assertEquals(setOf("f1", "f2"), recent.keys)
    }

    @Test
    fun `production wiring`() {
        val vm = ProductionSources.read("ui/chat/ChatViewModel.kt")
        val gen = vm.substringAfter("val autoGroupOn = com.openminis.app.ui.settings").substringBefore("Conversation:\\n")
        // One query, and only when there is a group to offer (none when the toggle is off).
        assertEquals(1, gen.split("chatRepository.recentSessionTitlesByFolder()").size - 1)
        assertTrue(gen.indexOf("if (!autoGroupOn) emptyList() else {") < gen.indexOf("recentSessionTitlesByFolder()"))
        assertTrue(gen.contains("if (offered.isEmpty()) plainLines else {"))
        assertTrue(gen.contains("FolderOfferFormat.lines(offered.map { it.first.id }, plainLines, recent)"))
        // The note only when some group has titles; without it the sentence is unchanged.
        assertTrue(gen.contains("append(\"]. \")\n                        if (groupTitleExamples) append(FolderOfferFormat.EXAMPLES_NOTE)\n                        append(\"If this conversation clearly belongs to one of these groups, \")"))
        // The log carries counts only.
        val log = gen.substringAfter("[TitleGen] Folder offer:").substringBefore("\n")
        assertTrue(log.contains("folders=\${offered.size} withTitles=\$withTitles"))
        assertFalse(log.contains("recent") || log.contains("lines"))
        assertEquals(
            "The quoted titles after \"e.g.\" are recent chats already in that group; they only show the group's topic and are not titles for this conversation. ",
            FolderOfferFormat.EXAMPLES_NOTE,
        )
    }
}
