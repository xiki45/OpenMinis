package com.openminis.app.ui.chat.search

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [T-in-chat-search] Source guards on how Search in Chat is wired into the
 * chat screen (iOS ChatSearchEngineTests [5]): the entry points, the rows it
 * searches, and the composer it replaces.
 */
class ChatSearchWiringTest {

    private val main = listOf(File("src/main"), File("app/src/main")).first { it.isDirectory }
    private fun src(rel: String) = File(main, "java/com/openminis/app/ui/chat/$rel").readText()

    private val screen = src("ChatScreen.kt")

    @Test fun `the controller lives on the screen, not in the view model`() {
        assertTrue(screen.contains("val chatSearch = remember(sessionId) {"))
        assertTrue("ChatViewModel holds no search state", !src("ChatViewModel.kt").contains("ChatSearchController"))
    }

    @Test fun `it searches the rows before tool runs are folded`() {
        val feed = screen.indexOf("chatSearch.onRows(builtRows)")
        val fold = screen.indexOf("flatItems = toolFold.fold(")
        assertTrue(feed in 1 until fold)
    }

    @Test fun `entry points - the menu, a user bubble, a tool capsule, a text selection`() {
        assertTrue(screen.contains("chatSearch.activate()\n"))
        assertTrue(screen.contains("onSearchInChat = { chatSearch.activate() },"))
        assertTrue(screen.contains("onSearchInChat = { text -> chatSearch.activate(text) },"))
        assertTrue(src("ChatUserMessageUI.kt").contains("onClick = { showMenu = false; onSearchInChat() },"))
        assertTrue(src("ChatAssistantMessageUI.kt").contains("onClick = { showToolMenu = false; onSearchInChat() },"))
        assertTrue(src("MinisMarkdownTextToolbar.kt").contains("toolbar.searchSelectionInChat()"))
    }

    @Test fun `back closes the bar`() =
        assertTrue(screen.contains("BackHandler(enabled = chatSearch.isActive) { chatSearch.deactivate() }"))

    @Test fun `the composer is hidden but kept in the layout, and a swipe on the bar sends nothing`() {
        assertTrue(screen.contains("if (chatSearch.isActive) {\n                                Modifier\n                                    .alpha(0f)\n                                    .clearAndSetSemantics { }"))
        assertTrue(screen.contains("if (chatSearch.isActive) return@awaitEachGesture"))
        assertTrue(screen.contains("com.openminis.app.ui.chat.search.ChatSearchBar(\n                        controller = chatSearch,"))
    }

    @Test fun `every shipped locale has the search strings file`() {
        val res = File(main, "res")
        val dirs = res.listFiles()!!.filter { it.name.startsWith("values") && File(it, "strings.xml").isFile }
        for (d in dirs) assertTrue("${d.name} lacks strings_chat_search.xml", File(d, "strings_chat_search.xml").isFile)
        val en = File(res, "values/strings_chat_search.xml").readText()
        for (key in listOf("chat_search_in_chat", "chat_search_placeholder", "chat_search_wrapped_first",
            "chat_search_wrapped_last", "chat_search_previous", "chat_search_next", "chat_search_result_of",
            "chat_search_no_results", "chat_search_clear", "chat_search_done")) {
            assertTrue(key, en.contains("name=\"$key\""))
        }
    }
}
