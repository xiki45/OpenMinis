package com.openminis.app.ui.chat

import androidx.lifecycle.ViewModelStore
import com.openminis.app.ProductionSources
import com.openminis.app.service.SessionActivityTracker
import com.openminis.app.service.SessionConcurrencyManager
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * [T-new-limits] The view model cache next to 6 session slots and up to
 * 6 × 3 = 18 sub agents, driven through the REAL ChatViewModelStore (see
 * Review0923ViewModelStoreRealTest for why that works on the JVM).
 *
 * A running session is marked busy the way production does it, through
 * SessionActivityTracker; no ChatViewModel is ever built.
 */
class NewLimitsVmCacheTest {

    private val created = mutableListOf<String>()
    private val activeMarked = mutableListOf<String>()
    private val releases = mutableListOf<() -> Unit>()

    private fun id(prefix: String) = "$prefix-${UUID.randomUUID()}".also { created += it }

    private fun open(sid: String, kind: ChatViewModelStore.PoolKind = ChatViewModelStore.PoolKind.NORMAL): ViewModelStore =
        ChatViewModelStore.ownerFor(sid, kind).viewModelStore

    private fun live(sid: String, store: ViewModelStore) = ChatViewModelStore.isLiveStore(sid, store)

    private fun markRunning(sid: String) {
        SessionActivityTracker.setActive(sid)
        activeMarked += sid
    }

    @After
    fun tearDown() {
        releases.forEach { it() }
        activeMarked.forEach { runCatching { SessionActivityTracker.setInactive(it) } }
        ChatViewModelStore.setActiveSession(null)
        created.forEach { ChatViewModelStore.release(it) }
    }

    @Test
    fun `the normal pool holds more than the session slots`() {
        assertTrue(
            "cap ${ChatViewModelStore.MAX_CACHED_NORMAL} must exceed ${SessionConcurrencyManager.MAX_CONCURRENT} " +
                "running chats plus the one on screen and the one just left",
            ChatViewModelStore.MAX_CACHED_NORMAL >= SessionConcurrencyManager.MAX_CONCURRENT + 2,
        )
    }

    @Test
    fun `six running chats plus switching between two others evicts nothing`() {
        val running = (1..6).map { id("run") }
        val runningStores = running.map { open(it) }
        running.forEach { markRunning(it) }
        val a = id("view-a"); val b = id("view-b")
        val sa = open(a); val sb = open(b)
        repeat(4) {
            ChatViewModelStore.setActiveSession(a); open(a)
            ChatViewModelStore.setActiveSession(b); open(b)
        }
        running.forEachIndexed { i, sid -> assertTrue("running chat $i kept", live(sid, runningStores[i])) }
        assertTrue("the chat being read is kept", live(a, sa))
        assertTrue("the chat just left is kept", live(b, sb))
    }

    @Test
    fun `a ninth chat evicts only an idle off-screen entry, never a running one`() {
        val running = (1..6).map { id("run") }
        val runningStores = running.map { open(it) }
        running.forEach { markRunning(it) }
        val idle = id("idle"); val idleStore = open(idle)
        val onScreen = id("screen"); val screenStore = open(onScreen)
        ChatViewModelStore.setActiveSession(onScreen)
        val ninth = id("ninth"); val ninthStore = open(ninth)
        assertFalse("the idle off-screen chat pays", live(idle, idleStore))
        assertTrue(live(onScreen, screenStore))
        assertTrue(live(ninth, ninthStore))
        running.forEachIndexed { i, sid -> assertTrue("running chat $i kept", live(sid, runningStores[i])) }
    }

    @Test
    fun `eighteen sub agents do not push out the user's chats`() {
        val normals = (1..ChatViewModelStore.MAX_CACHED_NORMAL).map { id("n") }
        val normalStores = normals.map { open(it) }
        repeat(18) { open(id("c"), ChatViewModelStore.PoolKind.CHILD) }
        normals.forEachIndexed { i, sid -> assertTrue("normal $i survives 18 children", live(sid, normalStores[i])) }
    }

    @Test
    fun `running sub agents are never evicted, even at three times the child cap`() {
        val kids = (1..18).map { id("c") }
        val stores = kids.map { sid -> open(sid, ChatViewModelStore.PoolKind.CHILD).also { markRunning(sid) } }
        kids.forEachIndexed { i, sid -> assertTrue("running child $i kept", live(sid, stores[i])) }
    }

    @Test
    fun `a child transcript being read survives a burst of other children`() {
        val viewed = id("c")
        val viewedStore = open(viewed, ChatViewModelStore.PoolKind.CHILD)
        val release = ChatViewModelStore.holdWhileViewed(viewed).also { releases += it }
        repeat(ChatViewModelStore.MAX_CACHED_CHILD + 3) { open(id("c"), ChatViewModelStore.PoolKind.CHILD) }
        assertTrue("on-screen child transcript kept", live(viewed, viewedStore))

        release()
        release()  // idempotent: a double dispose must not underflow another viewer
        repeat(ChatViewModelStore.MAX_CACHED_CHILD + 1) { open(id("c"), ChatViewModelStore.PoolKind.CHILD) }
        assertFalse("once closed it is an ordinary finished child again", live(viewed, viewedStore))
    }

    @Test
    fun `a sub agent's own active job keeps its view model busy, and the transcript holds while viewed`() {
        val vm = ProductionSources.read("ui/chat/ChatViewModel.kt")
        val body = vm.substringAfter("fun hasWorkInFlight(): Boolean {").substringBefore("fun selectGroup(")
        assertTrue(body.contains("helperConfig?.jobId?.let { registry.job(it)?.isActive } == true"))
        val ui = ProductionSources.read("ui/chat/HelperUi.kt")
        val screen = ui.substringAfter("fun AgentTranscriptScreen(").take(1500)
        assertTrue(screen.contains("ChatViewModelStore.holdWhileViewed(sessionId)"))
        assertTrue(screen.contains("onDispose { release() }"))
    }
}
