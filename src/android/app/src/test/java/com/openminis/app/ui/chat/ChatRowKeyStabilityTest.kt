package com.openminis.app.ui.chat

import com.openminis.app.ProductionSources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-row-keys-stable] User report: while re-reading the previous
 * reply, the list jumped ten-plus lines the moment the new turn's first
 * thinking / tool output arrived (and again when the turn ended).
 *
 * Cause: the previous reply was split one-row-per-paragraph or merged into
 * ~2000-char chunks depending on whether a NEWER assistant message existed.
 * The streaming pipeline builds its frozen prefix from msgs.take(splitIdx),
 * which cuts the new turn's placeholder off, so the answer flipped between
 * the full build and the prefix build - same mdblock keys, different text.
 * The rule is now "rows depend only on the message": streaming or seen live
 * on this screen -> split; cold history -> merged.
 */
class ChatRowKeyStabilityTest {

    private fun user(id: String, text: String) = ChatMessage(id = id, role = "user", content = text)

    private fun assistant(id: String, text: String, streaming: Boolean = false) = ChatMessage(
        id = id, role = "assistant", content = text, isStreaming = streaming,
        toolBlocks = listOf(AssistantBlock(id = "$id-t", kind = "text", content = text)),
    )

    private fun placeholder(id: String) = ChatMessage(
        id = id, role = "assistant", content = "", isStreaming = true, isAwaitingModelResponse = true,
    )

    /** 8 paragraphs of ~400 chars: merging packs them into fewer, larger rows. */
    private val longReply = (1..8).joinToString("\n\n") { n ->
        "Paragraph $n. " + "The plane trees along the avenue change with every season. ".repeat(7)
    }

    /** (key, rawText) of the markdown rows belonging to [messageId]. */
    private fun rowsOf(items: List<FlatChatItem>, messageId: String) =
        items.filterIsInstance<FlatChatItem.AssistantMarkdownBlock>()
            .filter { it.messageId == messageId }
            .map { it.key to it.rawText }

    @Test
    fun `a merged and a split build of the same reply really differ (the jump's precondition)`() {
        val msgs = listOf(user("u1", "write"), assistant("a1", longReply))
        val merged = rowsOf(buildFlatChatItems(msgs), "a1")
        val split = rowsOf(buildFlatChatItems(msgs, liveMessageIds = setOf("a1")), "a1")
        assertTrue("merging packs 8 paragraphs into fewer rows", merged.size < split.size)
        // Same key, different text: the anchor survives but points elsewhere.
        assertEquals(merged[1].first, split[1].first)
        assertNotEquals(merged[1].second, split[1].second)
    }

    @Test
    fun `the previous reply keeps identical rows through a whole new turn`() {
        val tracker = LiveMessageTracker()
        // Turn 1 streams on this screen.
        val streaming1 = listOf(user("u1", "write"), assistant("a1", longReply, streaming = true))
        val live = { msgs: List<ChatMessage>, stream: Set<String> -> tracker.update(msgs, stream) }
        val reference = rowsOf(buildFlatChatItems(streaming1, liveMessageIds = live(streaming1, setOf("a1"))), "a1")

        val done1 = listOf(user("u1", "write"), assistant("a1", longReply))
        // Turn 1 ended; turn 2 sent; its placeholder appended (full build).
        val sent = done1 + user("u2", "next") + placeholder("a2")
        // First output of turn 2: frozen prefix cut at the placeholder.
        val prefix = sent.take(3)
        val ended = done1 + user("u2", "next") + assistant("a2", "short answer")

        for ((label, items) in listOf(
            "turn 1 ended" to buildFlatChatItems(done1, liveMessageIds = live(done1, emptySet())),
            "turn 2 placeholder" to buildFlatChatItems(sent, liveMessageIds = live(sent, emptySet())),
            "turn 2 first output (prefix)" to buildFlatChatItems(prefix, liveMessageIds = live(sent, setOf("a2"))),
            "turn 2 ended" to buildFlatChatItems(ended, liveMessageIds = live(ended, emptySet())),
        )) {
            assertEquals("previous reply rows changed at: $label", reference, rowsOf(items, "a1"))
        }
    }

    @Test
    fun `rows of a message never depend on the messages after it`() {
        val msgs = listOf(
            user("u1", "a"), assistant("a1", longReply),
            user("u2", "b"), assistant("a2", longReply.replace("plane", "ginkgo")),
            user("u3", "c"), placeholder("a3"),
        )
        for (live in listOf(emptySet(), setOf("a2"), setOf("a1", "a2", "a3"))) {
            val full = buildFlatChatItems(msgs, liveMessageIds = live)
            for (cut in 1..msgs.size) {
                val prefix = buildFlatChatItems(msgs.take(cut), liveMessageIds = live)
                for (m in msgs.take(cut).filter { it.role == "assistant" }) {
                    assertEquals("live=$live cut=$cut msg=${m.id}", rowsOf(full, m.id), rowsOf(prefix, m.id))
                }
            }
        }
    }

    @Test
    fun `cold history is merged and the newest reply seeded at open stays split`() {
        val tracker = LiveMessageTracker()
        val history = listOf(user("u1", "a"), assistant("a1", longReply), user("u2", "b"), assistant("a2", longReply))
        val live = tracker.update(history, emptySet())
        assertEquals(setOf("a2"), live)
        val items = buildFlatChatItems(history, liveMessageIds = live)
        assertTrue(rowsOf(items, "a1").size < rowsOf(items, "a2").size)
    }

    @Test
    fun `the tracker records the placeholder and streaming ids and never forgets`() {
        val tracker = LiveMessageTracker()
        tracker.update(listOf(user("u1", "a")), emptySet())
        assertEquals(setOf("p"), tracker.update(listOf(user("u1", "a"), placeholder("p")), emptySet()))
        assertEquals(setOf("p", "x"), tracker.update(listOf(user("u1", "a")), setOf("x")))
        assertTrue("p" in tracker.update(emptyList(), emptySet()))
    }

    @Test
    fun `the screen wires one live snapshot into both builds, and no look-ahead is left`() {
        val flat = ProductionSources.read("ui/chat/ChatFlatItems.kt")
        assertFalse(flat.contains("isLastAssistantTurn"))
        assertTrue(flat.contains("val keepSplit = message.isStreaming || message.id in liveMessageIds"))
        val screen = ProductionSources.read("ui/chat/ChatScreen.kt")
        assertTrue(screen.contains("val liveIds = liveMessageTracker.update(msgs, stream.keys)"))
        assertTrue(screen.contains("buildFlatChatItems(msgs.take(splitIdx), sessionId, liveMessageIds = liveIds)"))
        assertTrue(screen.contains("seedKeys = frozenKeys, liveMessageIds = liveIds)"))
        assertTrue(ProductionSources.read("ui/chat/HelperUi.kt").contains("liveMessageTracker.update(merged, streaming.keys)"))
    }

    @Test
    fun `a reserve change offsets a scrolled-up reader and only real sends snap to the bottom`() {
        val screen = ProductionSources.read("ui/chat/ChatScreen.kt")
        val reserve = screen.substringAfter("[T-android-reserve-anchor]").substringBefore("// T174:")
        assertTrue(reserve.contains("if (userScrolledAway && !isNearBottom.value)"))
        assertTrue(reserve.contains("(listState.firstVisibleItemScrollOffset + delta).coerceAtLeast(0)"))
        assertTrue(reserve.contains("Snapshot.withoutReadObservation"))
        val snap = screen.substringAfter("LaunchedEffect(messages.size) {").substringBefore("USER-SEND-SNAP")
        assertTrue(snap.contains("if (!lastMsg.rendersAsUserBubble()) return@LaunchedEffect"))
        assertFalse(snap.contains("lastMsg.role != \"user\""))
    }

    @Test
    fun `callbacks and scheduled fires do not count as user sends`() {
        val callback = user("c", com.openminis.app.agent.jobs.AgentCallback(com.openminis.app.agent.jobs.AgentCallback.Kind.FINISHED, "J", "C", "t", "done", body = "r").xml)
        assertFalse(callback.rendersAsUserBubble())
        assertTrue(user("u", "hello").rendersAsUserBubble())
    }
}
