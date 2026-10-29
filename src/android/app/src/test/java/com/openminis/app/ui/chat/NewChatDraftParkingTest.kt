package com.openminis.app.ui.chat

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * [OpenMinis#433] Text typed in a NEW chat and never sent was lost when the
 * user went back and tapped New Chat again. The draft's view model was still
 * cached in [ChatViewModelStore], but every New Chat minted a fresh
 * `__new__<uuid>`, so nothing could ever reach it again.
 *
 * Aligned with iOS (d96f8c71d + 885696d42, NewChatDraftParkingTests.swift):
 * a new chat left with unsent text or attachments is parked for 15 minutes,
 * and the next New Chat for the same slot reuses its draft id instead of
 * minting one. Android keys the slot by model group AND folder (iOS: group
 * only), because the folder is encoded in the draft id and reusing the id
 * would carry it along.
 *
 * Driven through the REAL store object (see Review0923ViewModelStoreRealTest
 * for why that works on the JVM). No ChatViewModel is built, so the
 * view-model half of "is this still a draft" is covered by the pure
 * [ChatViewModelStore.isUnsentDraft] and by source guards.
 */
class NewChatDraftParkingTest {

    private var now = 1_000_000L
    private val created = mutableListOf<String>()

    @Before
    fun setUp() {
        ChatViewModelStore.clearParkedDraftsForTest()
        ChatViewModelStore.parkedDraftClock = { now }
    }

    @After
    fun tearDown() {
        ChatViewModelStore.parkedDraftClock = { System.currentTimeMillis() }
        ChatViewModelStore.setActiveSession(null)
        created.forEach { ChatViewModelStore.release(it) }
        ChatViewModelStore.clearParkedDraftsForTest()
    }

    /** New Chat as the list FAB / folder menu / chat top bar call it. */
    private fun newChat(groupId: String? = null, folderId: String? = null): String =
        ChatViewModelStore.newChatDraftId(groupId, folderId).also { created += it }

    /** ChatScreen composing the draft: allocates its store. */
    private fun show(id: String) {
        ChatViewModelStore.ownerFor(id)
        ChatViewModelStore.unparkDraft(id)
    }

    /** ChatScreen leaving: what its dispose reports. */
    private fun leave(id: String, unsent: Boolean) = ChatViewModelStore.parkDraftIfUnsent(id, unsent)

    private fun typedAndLeft(groupId: String? = null, folderId: String? = null): String {
        val id = newChat(groupId, folderId)
        show(id)
        leave(id, unsent = true)
        return id
    }

    @Test
    fun `a new chat left with unsent text comes back on the next New Chat`() {
        val a = typedAndLeft()
        assertEquals(a, newChat())
    }

    @Test
    fun `a parked draft backs one screen only`() {
        val a = typedAndLeft()
        assertEquals(a, newChat())
        assertNotEquals("taken once, then a fresh draft", a, newChat())
    }

    @Test
    fun `a draft emptied before leaving does not come back`() {
        val a = typedAndLeft()
        assertEquals(a, newChat())
        show(a)
        leave(a, unsent = false)
        assertNotEquals(a, newChat())
    }

    @Test
    fun `an empty new chat leaving does not clear another chat's draft`() {
        val b = newChat()
        show(b)
        val a = typedAndLeft()
        assertNotEquals(a, b)
        leave(b, unsent = false)
        assertEquals(a, newChat())
    }

    @Test
    fun `a parked draft survives just under 15 minutes`() {
        val a = typedAndLeft()
        now += 14 * 60_000L
        assertEquals(a, newChat())
    }

    @Test
    fun `a parked draft expires after 15 minutes and its view model is released`() {
        val a = typedAndLeft()
        val store = ChatViewModelStore.ownerFor(a).viewModelStore
        // ownerFor above re-touched the store; it is still parked.
        now += ChatViewModelStore.PARKED_DRAFT_LIFETIME_MS + 1
        assertNotEquals(a, newChat())
        assertFalse("expired draft's store is dropped", ChatViewModelStore.isLiveStore(a, store))
    }

    @Test
    fun `parking again restarts the lifetime`() {
        val a = typedAndLeft()
        now += 10 * 60_000L
        assertEquals(a, newChat())
        show(a)
        leave(a, unsent = true)
        now += 10 * 60_000L
        assertEquals(a, newChat())
    }

    @Test
    fun `slots are per model group and per folder`() {
        val plain = typedAndLeft()
        val grp = typedAndLeft(groupId = "g1")
        val fld = typedAndLeft(folderId = "f1")
        val both = typedAndLeft(groupId = "g1", folderId = "f1")
        assertEquals(4, setOf(plain, grp, fld, both).size)
        assertEquals(both, newChat(groupId = "g1", folderId = "f1"))
        assertEquals(fld, newChat(folderId = "f1"))
        assertEquals(grp, newChat(groupId = "g1"))
        assertEquals(plain, newChat())
    }

    @Test
    fun `a folder draft does not surface in a plain New Chat`() {
        val inFolder = typedAndLeft(folderId = "f1")
        assertNotEquals(inFolder, newChat())
        assertNotEquals(inFolder, newChat(folderId = "f2"))
        assertEquals(inFolder, newChat(folderId = "f1"))
    }

    @Test
    fun `the draft id keeps the encoding ChatViewModel parses`() {
        val id = newChat(groupId = "g1", folderId = "f1")
        assertTrue(id.startsWith("__new__"))
        assertEquals("g1", id.substringAfter("__grp__", "").substringBefore("__fld__"))
        assertEquals("f1", id.substringAfter("__fld__", "").substringBefore("__grp__"))
        val plain = newChat()
        assertFalse(plain.contains("__grp__") || plain.contains("__fld__"))
    }

    @Test
    fun `a draft evicted from the cache is not handed out`() {
        val a = typedAndLeft()
        repeat(ChatViewModelStore.MAX_CACHED_NORMAL + 1) {
            val other = "i433-other-${java.util.UUID.randomUUID()}".also { created += it }
            ChatViewModelStore.ownerFor(other)
        }
        assertNotEquals(a, newChat())
    }

    @Test
    fun `a draft released under memory pressure is not handed out`() {
        val a = typedAndLeft()
        ChatViewModelStore.handleMemoryPressure()
        assertNotEquals(a, newChat())
    }

    @Test
    fun `a sent draft is not parked`() {
        val a = newChat()
        show(a)
        ChatViewModelStore.rename(a, "i433-real-${java.util.UUID.randomUUID()}".also { created += it })
        leave(a, unsent = true)
        assertNotEquals(a, newChat())
    }

    @Test
    fun `a parked draft promoted later is not handed out`() {
        val a = typedAndLeft()
        ChatViewModelStore.rename(a, "i433-real-${java.util.UUID.randomUUID()}".also { created += it })
        assertNotEquals(a, newChat())
    }

    @Test
    fun `back on screen unparks it`() {
        val a = typedAndLeft()
        show(a)
        assertNotEquals(a, newChat())
    }

    @Test
    fun `a draft opened by a share, shortcut or deep link is never parked`() {
        val shared = newDraftSessionIdForTest()
        ChatViewModelStore.ownerFor(shared)
        leave(shared, unsent = true)
        assertNotEquals(shared, newChat())
    }

    @Test
    fun `a non-UI draft leaving empty does not clear the user's parked draft`() {
        val a = typedAndLeft()
        val shared = newDraftSessionIdForTest()
        ChatViewModelStore.ownerFor(shared)
        leave(shared, unsent = false)
        assertEquals(a, newChat())
    }

    @Test
    fun `what counts as an unsent draft`() {
        fun unsent(isDraft: Boolean = true, real: String = "", text: String = "", att: Boolean = false, busy: Boolean = false) =
            ChatViewModelStore.isUnsentDraft(isDraft, real, text, att, busy)
        assertTrue("non-blank text", unsent(text = "hi"))
        assertTrue("an attachment alone", unsent(att = true))
        assertTrue("a [Pasted#N] placeholder is text", unsent(text = "[Pasted#1]"))
        assertFalse("blank", unsent(text = " \n\t "))
        assertFalse("an existing session", unsent(isDraft = false, text = "hi"))
        assertFalse("a draft that already has a row", unsent(real = "abc", text = "hi"))
        assertFalse("running", unsent(text = "hi", busy = true))
    }

    // ---- source guards: the wiring the JVM cannot run ----

    private fun src(path: String): String {
        val f = File("src/main/java/com/openminis/app/$path")
        assertTrue("missing source: ${f.absolutePath}", f.exists())
        return f.readText()
    }

    @Test
    fun `the view model reports hasUnsentDraft through the shared rule`() {
        val vm = src("ui/chat/ChatViewModel.kt")
        val body = vm.substringAfter("val hasUnsentDraft: Boolean").take(400)
        assertTrue(body.contains("ChatViewModelStore.isUnsentDraft("))
        assertTrue(body.contains("hasWorkInFlight()"))
    }

    @Test
    fun `ChatScreen parks on leave and unparks on enter`() {
        val screen = src("ui/chat/ChatScreen.kt")
        val effect = screen.substringAfter("ChatViewModelStore.setActiveSession(sessionId)")
            .substringBefore("// Hang-detector quiet-period reset")
        assertTrue(effect.contains("ChatViewModelStore.unparkDraft(sessionId)"))
        val dispose = effect.substringAfter("onDispose {")
        assertTrue(dispose.contains("ChatViewModelStore.parkDraftIfUnsent(sessionId, viewModel.hasUnsentDraft)"))
    }

    @Test
    fun `only the New Chat buttons reuse a parked draft`() {
        val list = src("ui/sessions/SessionListViewModel.kt")
        assertTrue(
            list.substringAfter("fun createNewSession(").take(1500)
                .contains("ChatViewModelStore.newChatDraftId(groupId, folderId)"),
        )
        val split = src("ui/navigation/ChatSplitScaffold.kt")
        assertTrue(
            split.substringAfter("val openNewDraft: () -> Unit = {").take(300)
                .contains("ChatViewModelStore.newChatDraftId()"),
        )
        // Shares, quick actions, deep links, launch modes and the Move-to
        // sheet mint their own ids and must never pick up (or clear) a
        // parked draft.
        listOf(
            "MainActivity.kt",
            "ui/navigation/AppNavigation.kt",
            "ui/chat/MoveToSessionSheet.kt",
        ).forEach { assertFalse(it, src(it).contains("newChatDraftId")) }
        assertTrue(
            "the share router keeps minting a fresh id",
            split.contains("val draft = newDraftSessionId()"),
        )
    }

    private fun newDraftSessionIdForTest(): String =
        com.openminis.app.ui.navigation.newDraftSessionId().also { created += it }
}
