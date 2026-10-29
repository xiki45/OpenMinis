package com.openminis.app.data.repository

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.sql.Connection
import java.sql.DriverManager

/**
 * [T-search-multi-keyword] Session search with several keywords (port of iOS
 * b09df3824; the same rules as Windows minis-core/src/search.rs).
 *
 *  - whitespace separates keywords; a session holding ANY of them is a result;
 *  - `%` and `_` in a keyword match themselves, not as LIKE wildcards;
 *  - every keyword is highlighted, overlapping or touching hits as one range.
 *
 * The generated SQL runs for real against SQLite, as in SessionSearchSqlTest.
 */
class SessionSearchKeywordsTest {

    // ── Keywords ───────────────────────────────────────────────────────────

    @Test fun `whitespace of any kind separates keywords`() {
        assertEquals(listOf("apple", "banana"), SessionSearch.searchTerms("apple banana"))
        assertEquals(listOf("a", "b", "c", "d"), SessionSearch.searchTerms("  a\tb\n\nc\u3000d  "))
        assertEquals(emptyList<String>(), SessionSearch.searchTerms(" \t\n\u3000"))
        assertEquals(emptyList<String>(), SessionSearch.searchTerms(""))
    }

    @Test fun `a keyword repeated in another case is kept once, in first spelling`() {
        // Windows minis-storage text.rs search_helpers.
        assertEquals(listOf("gpt", "claude"), SessionSearch.searchTerms("gpt  GPT\tclaude"))
        assertEquals(listOf("GPT"), SessionSearch.searchTerms("GPT gpt Gpt"))
    }

    @Test fun `keywords past ten are ignored`() {
        val terms = SessionSearch.searchTerms((1..14).joinToString(" ") { "k$it" })
        assertEquals((1..10).map { "k$it" }, terms)
        // Duplicates do not use up the budget.
        assertEquals(10, SessionSearch.searchTerms("x X " + (1..12).joinToString(" ") { "k$it" }).size)
    }

    @Test fun `LIKE wildcards in a keyword are escaped`() {
        // Windows minis-storage text.rs search_helpers.
        assertEquals("%100\\%\\_a\\\\b%", SessionSearch.likePattern("100%_a\\b"))
        assertEquals("%plain%", SessionSearch.likePattern("plain"))
    }

    // ── Highlight ranges ───────────────────────────────────────────────────

    @Test fun `every keyword is highlighted, in text order`() {
        assertEquals(listOf(0..4, 10..15), SessionSearch.hitRanges("apple and banana", listOf("banana", "apple")))
        assertEquals(listOf(0..2, 8..12), SessionSearch.hitRanges("GPT and gpt-5", listOf("gpt", "gpt-5")))
    }

    @Test fun `overlapping and touching hits are one range`() {
        assertEquals(listOf(0..4), SessionSearch.hitRanges("GPT-5", listOf("gpt", "gpt-5")))
        assertEquals(listOf(0..5), SessionSearch.hitRanges("abcdef", listOf("abc", "def")))
        assertEquals(listOf(0..3), SessionSearch.hitRanges("aaaa", listOf("aa")))
    }

    @Test fun `CJK and emoji ranges are char ranges of the original text`() {
        // Windows: byte ranges (0,6),(12,18) for the same input.
        assertEquals(listOf(0..1, 4..5), SessionSearch.hitRanges("中文搜索中文", listOf("中文")))
        val emoji = "fix 🐛 then 🐛"
        val ranges = SessionSearch.hitRanges(emoji, listOf("🐛"))
        assertEquals(listOf("🐛", "🐛"), ranges.map { emoji.substring(it.first, it.last + 1) })
    }

    @Test fun `no keyword, no range`() {
        assertEquals(emptyList<IntRange>(), SessionSearch.hitRanges("text", emptyList()))
        assertEquals(emptyList<IntRange>(), SessionSearch.hitRanges("text", listOf("zzz")))
        assertEquals(emptyList<IntRange>(), SessionSearch.hitRanges("", listOf("a")))
    }

    // ── Hit line ───────────────────────────────────────────────────────────

    @Test fun `the hit line is the first keyword in the order typed that the part holds`() {
        val parts = "[" + text("line one has banana\nline two has apple") + "]"
        assertEquals("line two has apple", SessionSearch.searchSnippet(parts, listOf("apple", "banana")))
        assertEquals("line one has banana", SessionSearch.searchSnippet(parts, listOf("banana", "apple")))
        // A keyword absent from the part is skipped.
        assertEquals("line one has banana", SessionSearch.searchSnippet(parts, listOf("cherry", "banana")))
        assertNull(SessionSearch.searchSnippet(parts, listOf("cherry")))
    }

    @Test fun `visible text is still tried before tool inputs and outputs`() {
        val parts = "[" + toolResult("t", "output has apple") + "," + text("text has banana") + "]"
        assertEquals("text has banana", SessionSearch.searchSnippet(parts, listOf("apple", "banana")))
    }

    // ── SQL ────────────────────────────────────────────────────────────────

    private lateinit var db: Connection
    private var t0 = 1000L

    @Before fun open() {
        db = DriverManager.getConnection("jdbc:sqlite::memory:")
        db.createStatement().use {
            it.execute("CREATE TABLE sessions (id TEXT, title TEXT, model_id TEXT, created_at INTEGER, updated_at INTEGER)")
            it.execute("CREATE TABLE messages (session_id TEXT, sort_order INTEGER, parts_json TEXT)")
        }
        session("text", "Chat one", listOf(listOf(text("I like apple pie"))))
        session("output", "Chat two", listOf(listOf(toolResult("t1", "banana count: 3"))))
        session("input", "Chat three", listOf(listOf(toolUse("t2", """{"command":"grep cherry notes"}"""))))
        session("title", "Durian notes", listOf(listOf(text("nothing relevant"))))
        session("both", "Chat five", listOf(listOf(text("apple")), listOf(text("then banana"))))
        session("reversed", "Chat six", listOf(listOf(text("banana before apple"))))
        session("none", "Chat seven", listOf(listOf(text("grapes only"))))
        session("opaque", "Chat eight", listOf(listOf(toolUse("call_apple_banana", """{"command":"ls"}""", sig = "Durianx"))))
        session("pct", "Coverage", listOf(listOf(text("we are 100% sure"))))
        session("thousand", "Inventory", listOf(listOf(text("1000 items in stock"))))
        session("under", "Names", listOf(listOf(text("the key a_b is set"))))
        session("axb", "Other names", listOf(listOf(text("the key axb is set"))))
        session("pcttitle", "Done 50% of it", listOf(listOf(text("progress"))))
        session("plaintitle", "Done 500 of it", listOf(listOf(text("progress"))))
    }

    @After fun close() = db.close()

    private fun text(v: String) = """{"type":"text","value":${JSONObject.quote(v)}}"""
    private fun toolUse(id: String, input: String, sig: String? = null): String {
        val o = JSONObject().put("toolUseId", id).put("name", "shell_execute").put("input", input)
        if (sig != null) o.put("thoughtSignature", sig)
        return """{"type":"toolUse","value":$o}"""
    }
    private fun toolResult(id: String, output: String) =
        """{"type":"toolResult","value":${JSONObject().put("toolUseId", id).put("output", output).put("success", true)}}"""

    private fun session(id: String, title: String, messages: List<List<String>>) {
        t0 += 1
        db.prepareStatement("INSERT INTO sessions VALUES (?, ?, 'm', ?, ?)").use {
            it.setString(1, id); it.setString(2, title); it.setLong(3, t0); it.setLong(4, t0); it.execute()
        }
        messages.forEachIndexed { i, parts ->
            db.prepareStatement("INSERT INTO messages VALUES (?, ?, ?)").use {
                it.setString(1, id); it.setInt(2, i); it.setString(3, "[" + parts.joinToString(",") + "]"); it.execute()
            }
        }
    }

    /** (id, hit_parts_json) for [sql] with [args] bound in order. */
    private fun query(sql: String, args: List<String>): List<Pair<String, String?>> =
        db.prepareStatement(sql).use { st ->
            args.forEachIndexed { i, a -> st.setString(i + 1, a) }
            st.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getString("id") to rs.getString("hit_parts_json")) } }
        }

    private fun ids(q: String, legacy: Boolean = false): Set<String> {
        val terms = SessionSearch.searchTerms(q)
        val sql = if (legacy) SessionSearch.legacySearchSql(terms.size) else SessionSearch.visibleSearchSql(terms.size)
        return query(sql, SessionSearch.searchArgs(terms).map { it as String }).map { it.first }.toSet()
    }

    /** What the app ran before: the whole entry in one unescaped LIKE. */
    private fun oldIds(q: String): Set<String> =
        query(SessionSearch.visibleSearchSql(1).replace(" ESCAPE '\\'", ""), listOf("%$q%", q)).map { it.first }.toSet()

    @Test fun `a session holding ANY keyword in any visible field is a result`() {
        val found = ids("apple banana cherry durian")
        assertEquals(setOf("text", "output", "input", "title", "both", "reversed"), found)
        // OR, not AND: "text" holds only apple, "output" only banana.
        assertTrue("text" in found && "output" in found)
    }

    @Test fun `the old whole-string pattern found none of those`() {
        assertEquals(emptySet<String>(), oldIds("apple banana cherry durian"))
        // Not even the adjacent pair in the other order.
        assertEquals(emptySet<String>(), oldIds("apple banana"))
        assertEquals(setOf("text", "both", "reversed", "output"), ids("apple banana"))
    }

    @Test fun `one keyword gives what the whole-string search gave`() {
        for (q in listOf("apple", "banana", "Durian", "count")) assertEquals(q, oldIds(q), ids(q))
    }

    @Test fun `hits in opaque fields are still not results with several keywords`() {
        // "opaque" holds apple, banana and Durian only in a tool-call id and a
        // thought signature.
        assertFalse("opaque" in ids("apple banana Durian"))
        assertTrue("the raw-JSON fallback still lists it", "opaque" in ids("apple banana", legacy = true))
    }

    @Test fun `percent and underscore match literally`() {
        assertEquals(setOf("pct"), ids("100%"))
        assertEquals(setOf("under"), ids("a_b"))
        assertEquals(setOf("pcttitle"), ids("50%"))
        // The same in the pre-JSON1 fallback statement.
        assertEquals(setOf("pct"), ids("100%", legacy = true))
        assertEquals(setOf("under"), ids("a_b", legacy = true))
        assertEquals(setOf("pcttitle"), ids("50%", legacy = true))
        // Unescaped, the wildcards matched the wrong sessions.
        assertTrue("thousand" in oldIds("100%"))
        assertTrue("axb" in oldIds("a_b"))
        assertTrue("plaintitle" in oldIds("50%"))
    }

    @Test fun `a backslash in a keyword matches itself`() {
        // Checked on a title: in a message the backslash is stored JSON-escaped
        // ("C:\\temp"), so the raw parts_json prefilter cannot see "C:\temp" -
        // the same on iOS, and before this change.
        session("path", """C:\temp notes""", listOf(listOf(text("x"))))
        session("nopath", "C:temp notes", listOf(listOf(text("x"))))
        assertEquals(setOf("path"), ids("""C:\temp"""))
    }

    @Test fun `the look-back pass binds the session after the patterns and keywords`() {
        session("multi", "Long", listOf(listOf(text("old apple")), listOf(text("unrelated")), listOf(text("new banana"))))
        val terms = listOf("apple", "banana")
        val rows = db.prepareStatement(SessionSearch.olderMatchesSql(visibleOnly = true, termCount = 2)).use { st ->
            (SessionSearch.searchArgs(terms).map { it as String } + "multi").forEachIndexed { i, a -> st.setString(i + 1, a) }
            st.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getString("parts_json")) } }
        }
        assertEquals(2, rows.size)
        assertTrue(rows[0].contains("new banana"))
        assertTrue(rows[1].contains("old apple"))
    }

    @Test fun `an oversized row is cut around the first keyword typed that it holds`() {
        val filler = "x".repeat(SessionSearch.MAX_HIT_JSON_CHARS)
        session("huge", "Big output", listOf(listOf(toolResult("t9", filler + "\nfound the BANANA here\n" + filler))))
        for (legacy in listOf(false, true)) {
            val terms = SessionSearch.searchTerms("apple banana")
            val sql = if (legacy) SessionSearch.legacySearchSql(2) else SessionSearch.visibleSearchSql(2)
            val hit = query(sql, SessionSearch.searchArgs(terms).map { it as String }).single { it.first == "huge" }.second!!
            assertTrue(hit.startsWith(SessionSearch.WINDOW_MARK))
            // The filler is 50x the window, so holding the hit at all means the
            // window was cut around BANANA (apple, typed first, is absent).
            assertTrue("window holds the hit (legacy=$legacy)", hit.contains("BANANA"))
            // The window is raw JSON (its newlines are escaped), read as one line.
            assertEquals(true, SessionSearch.searchSnippet(hit, terms)?.contains("found the BANANA here"))
        }
    }
}
