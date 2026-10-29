package com.openminis.app.ui.chat

import androidx.room.Room
import com.openminis.app.ProductionSources
import com.openminis.app.data.db.AppDatabase
import com.openminis.app.data.db.SessionTitleRow
import com.openminis.app.data.repository.ChatRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CopyOnWriteArrayList

/**
 * [T-android-chat-title-follows-store] After Regenerate Title (or a rename) in
 * the session list, an already-created chat's top bar showed the old title.
 * The chat now follows the stored title.
 */
@RunWith(RobolectricTestRunner::class)
class ChatTitleFollowsStoreTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: ChatRepository
    private val scope = CoroutineScope(Dispatchers.IO)

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(org.robolectric.RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        repo = ChatRepository(db.chatDao())
    }

    @After
    fun tearDown() {
        scope.cancel()
        db.close()
    }

    private fun awaitSize(list: List<*>, size: Int) = runBlocking {
        withTimeout(5_000) { while (list.size < size) delay(20) }
    }

    @Test
    fun `whoever writes the title, the observer of that session sees it - and only real changes`() = runBlocking {
        val session = repo.createSession(modelId = "m", title = "Old title")
        val other = repo.createSession(modelId = "m", title = "Another chat")
        val seen = CopyOnWriteArrayList<SessionTitleRow>()
        scope.launch { repo.observeSessionTitle(session.id).collect { seen += it } }
        awaitSize(seen, 1)
        assertEquals(SessionTitleRow("Old title", null), seen[0])

        // What the session list's Regenerate Title does (SessionListViewModel).
        repo.updateSessionTitleAndCategory(session.id, "Regenerated title", "coding")
        awaitSize(seen, 2)
        assertEquals(SessionTitleRow("Regenerated title", "coding"), seen[1])

        // A manual rename from the list.
        repo.updateSessionTitle(session.id, "Renamed by hand")
        awaitSize(seen, 3)
        assertEquals(SessionTitleRow("Renamed by hand", "coding"), seen[2])

        // Another session changing, or the same value written again, is not a change here.
        repo.updateSessionTitle(other.id, "Other renamed")
        repo.updateSessionTitle(session.id, "Renamed by hand")
        delay(400)
        assertEquals(3, seen.size)
    }

    @Test
    fun `display and the late-auto-title rule`() {
        val untitled = ChatViewModel.UNTITLED_SESSION_TITLE
        assertEquals("Trip plan", SessionTitleRules.display("Trip plan", untitled))
        assertEquals(untitled, SessionTitleRules.display(null, untitled))
        assertEquals(untitled, SessionTitleRules.display("  ", untitled))

        assertTrue("still untitled: auto title may land", SessionTitleRules.autoTitleMayReplace(null, untitled))
        assertTrue(SessionTitleRules.autoTitleMayReplace("", untitled))
        assertTrue(SessionTitleRules.autoTitleMayReplace(untitled, untitled))
        assertFalse("renamed or regenerated meanwhile: it stays", SessionTitleRules.autoTitleMayReplace("Renamed by hand", untitled))
    }

    @Test
    fun `the chat starts following once the row exists, and a late auto title re-reads the store`() {
        val vm = ProductionSources.read("ui/chat/ChatViewModel.kt")
        val follow = vm.substringAfter("private fun followStoredTitle(sid: String) {").substringBefore("\n    }\n")
        assertTrue(follow.contains("chatRepository.observeSessionTitle(sid).collect { row ->"))
        assertTrue(follow.contains("_sessionTitle.value = SessionTitleRules.display(row.title, UNTITLED_SESSION_TITLE)"))
        assertTrue(follow.contains("_sessionCategory.value = row.category"))
        assertTrue("one observer per ViewModel", follow.contains("storedTitleJob?.cancel()"))
        // A stored session: from loadSession. A draft: when ensureSession creates its row.
        assertTrue(vm.contains("followStoredTitle(sessionId)"))
        assertTrue(vm.contains("realSessionId = session.id\n        followStoredTitle(session.id)"))
        assertEquals(2, Regex("""followStoredTitle\((sessionId|session\.id)\)""").findAll(vm).count())
        // The auto title checks the STORED title right before it writes.
        val write = vm.indexOf("chatRepository.updateSessionTitleAndCategory(sid, title, category)\n                    withContext(Dispatchers.Main) {")
        val guard = vm.indexOf("if (!SessionTitleRules.autoTitleMayReplace(storedNow, UNTITLED_SESSION_TITLE)) {")
        assertTrue(guard in 1 until write && write - guard < 500)
        // The list's regenerate still writes through the repository the chat observes.
        // [T-sessions-cli-update] Regenerate Title moved to SessionTitleRegenerator.
        val list = ProductionSources.read("ui/sessions/SessionTitleRegenerator.kt")
        assertTrue(list.contains("chatRepository.updateSessionTitleAndCategory(id, title, category)"))
    }
}
