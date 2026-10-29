package com.openminis.app.speech

import android.app.Application
import android.media.AudioManager
import com.openminis.app.ProductionSources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [T-readaloud-focus] One MAY_DUCK focus holder for both read-aloud engines,
 * held for a whole run of sentences, released on every exit path.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ReadAloudAudioFocusTest {

    private class FakeGateway(var grant: Int = AudioManager.AUDIOFOCUS_REQUEST_GRANTED) :
        ReadAloudAudioFocus.Gateway {
        override var onFocusChange: (Int) -> Unit = {}
        var requests = 0
        var abandons = 0
        override fun request(): Int { requests++; return grant }
        override fun abandon() { abandons++ }
    }

    /** Manual clock: tasks run only when [advance] passes their due time. */
    private class FakeScheduler : ReadAloudAudioFocus.Scheduler {
        private class Task(val due: Long, val block: () -> Unit) { var cancelled = false }
        private val tasks = mutableListOf<Task>()
        var now = 0L
        override fun schedule(delayMs: Long, block: () -> Unit): () -> Unit {
            val t = Task(now + delayMs, block)
            tasks += t
            return { t.cancelled = true }
        }
        fun advance(ms: Long) {
            val until = now + ms
            while (true) {
                val next = tasks.filter { !it.cancelled && it.due <= until }.minByOrNull { it.due } ?: break
                tasks.remove(next)
                now = next.due
                next.block()
            }
            now = until
        }
    }

    private val gw = FakeGateway()
    private val clock = FakeScheduler()
    private val focus = ReadAloudAudioFocus(gw, clock, graceMs = 2_500, maxHoldMs = 300_000, log = {})
    private val a = Any()
    private val b = Any()

    @Test
    fun `a run of sentences is one request and one abandon`() {
        repeat(5) {
            focus.acquire(a) {}
            clock.advance(1_000)       // sentence plays
            focus.releaseSoon(a)
            clock.advance(800)         // inter-sentence gap shorter than the grace
        }
        assertEquals("no re-request between sentences", 1, gw.requests)
        assertEquals("no release between sentences", 0, gw.abandons)
        assertTrue(focus.isHeld)
        clock.advance(2_500)           // queue drained
        assertEquals(1, gw.abandons)
        assertFalse(focus.isHeld)
        assertEquals(0, focus.ownerCount)
    }

    @Test
    fun `a gap longer than the grace releases and the next sentence re-requests`() {
        focus.acquire(a) {}
        focus.releaseSoon(a)
        clock.advance(2_600)           // slow synthesis of the next sentence
        assertEquals(1, gw.abandons)
        focus.acquire(a) {}
        assertEquals(2, gw.requests)
        assertTrue(focus.isHeld)
    }

    @Test
    fun `slow provider synthesis of a queued sentence keeps focus - the device finding`() {
        // Measured on a Pixel 4a with Gemini TTS: 3-4 s between sentences. With
        // only the 2.5 s grace every sentence released and re-requested focus.
        repeat(6) {
            focus.acquire(a) {}
            clock.advance(5_000)                                   // sentence plays
            focus.releaseSoon(a, ReadAloudAudioFocus.SYNTH_WAIT_MS) // next one is queued
            clock.advance(4_000)                                   // synthesis
        }
        assertEquals(1, gw.requests)
        assertEquals(0, gw.abandons)
        focus.releaseSoon(a, ReadAloudAudioFocus.GRACE_MS)         // queue drained
        clock.advance(2_500)
        assertEquals(1, gw.abandons)
    }

    @Test
    fun `a stalled synthesis still releases after the bounded wait`() {
        focus.acquire(a) {}
        focus.releaseSoon(a, ReadAloudAudioFocus.SYNTH_WAIT_MS)
        clock.advance(7_999)
        assertTrue(focus.isHeld)
        clock.advance(1)
        assertFalse(focus.isHeld)
        assertEquals(1, gw.abandons)
    }

    @Test
    fun `two players - the first finishing does not release the other's focus`() {
        focus.acquire(a) {}
        focus.acquire(b) {}
        assertEquals(1, gw.requests)
        focus.releaseNow(a)
        assertEquals(0, gw.abandons)
        assertTrue(focus.isHeld)
        focus.releaseSoon(b)
        clock.advance(2_500)
        assertEquals(1, gw.abandons)
    }

    @Test
    fun `releases are idempotent and unknown owners are ignored`() {
        focus.releaseNow(a)
        focus.releaseSoon(a)
        clock.advance(10_000)
        assertEquals(0, gw.abandons)
        focus.acquire(a) {}
        focus.acquire(a) {}            // duplicate acquire: still one owner
        assertEquals(1, focus.ownerCount)
        focus.releaseNow(a)
        focus.releaseNow(a)
        focus.releaseSoon(a)
        clock.advance(10_000)
        assertEquals("abandoned exactly once", 1, gw.abandons)
    }

    @Test
    fun `stop during the grace releases immediately and the pending grace is inert`() {
        focus.acquire(a) {}
        focus.releaseSoon(a)
        focus.releaseNow(a)
        assertEquals(1, gw.abandons)
        focus.acquire(a) {}            // a new run starts
        clock.advance(2_500)           // the OLD grace must not drop the new run
        assertTrue(focus.isHeld)
        assertEquals(1, gw.abandons)
    }

    @Test
    fun `permanent and transient loss stop every owner and abandon`() {
        for (change in intArrayOf(AudioManager.AUDIOFOCUS_LOSS, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)) {
            var stoppedA = 0
            var stoppedB = 0
            // Like ReadAloudPlayer: onLost -> stop() -> releaseNow (re-entrant).
            focus.acquire(a) { stoppedA++; focus.releaseNow(a) }
            focus.acquire(b) { stoppedB++ }
            val before = gw.abandons
            gw.onFocusChange(change)
            assertEquals(1, stoppedA)
            assertEquals(1, stoppedB)
            assertEquals("abandoned once on $change", before + 1, gw.abandons)
            assertFalse(focus.isHeld)
            assertEquals(0, focus.ownerCount)
        }
    }

    @Test
    fun `a duck request keeps reading`() {
        var stopped = false
        focus.acquire(a) { stopped = true }
        gw.onFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)
        assertFalse(stopped)
        assertTrue(focus.isHeld)
        assertEquals(0, gw.abandons)
    }

    @Test
    fun `watchdog bounds a run whose utterance never ends`() {
        focus.acquire(a) {}            // the sentence never reports completion
        clock.advance(299_000)
        assertTrue(focus.isHeld)
        clock.advance(1_000)
        assertFalse(focus.isHeld)
        assertEquals(1, gw.abandons)
        assertEquals(0, focus.ownerCount)
    }

    @Test
    fun `each new sentence re-arms the watchdog`() {
        repeat(10) {
            focus.acquire(a) {}
            clock.advance(200_000)
        }
        assertTrue("a long but live run is not cut", focus.isHeld)
        assertEquals(0, gw.abandons)
    }

    @Test
    fun `a denied request is not abandoned and is retried next sentence`() {
        gw.grant = AudioManager.AUDIOFOCUS_REQUEST_FAILED
        focus.acquire(a) {}
        assertFalse(focus.isHeld)
        focus.releaseNow(a)
        assertEquals(0, gw.abandons)
        gw.grant = AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        focus.acquire(a) {}
        assertEquals(2, gw.requests)
        assertTrue(focus.isHeld)
    }

    @Test
    fun `player wiring - both engines share the holder and every exit releases`() {
        val src = ProductionSources.read("speech/ReadAloudPlayer.kt")
        assertFalse("no per-sentence focus calls left",
            src.contains("requestAudioFocus") || src.contains("abandonAudioFocus"))
        assertTrue(src.contains("ReadAloudAudioFocus.shared(appContext)"))
        val system = src.substring(src.indexOf("private suspend fun speakViaSystem"))
            .substringBefore("system.speak(text)")
        assertTrue("system engine acquires before speaking", system.contains("focus.acquire(this, onFocusLost)"))
        val provider = src.substring(src.indexOf("private suspend fun playBytes")).substringBefore("mp.start()")
        assertTrue("provider acquires before start", provider.contains("focus.acquire(this@ReadAloudPlayer, onFocusLost)"))
        val worker = src.substring(src.indexOf("for (text in queue)")).substringBefore("pending.decrementAndGet()")
        assertTrue("every utterance releases in finally",
            worker.contains("finally") && worker.contains("focus.releaseSoon("))
        assertTrue("a queued next sentence gets the synthesis wait, an empty queue the grace",
            worker.contains("pending.get() > 1") &&
                worker.contains("ReadAloudAudioFocus.SYNTH_WAIT_MS else ReadAloudAudioFocus.GRACE_MS"))
        for (fn in listOf("fun stop()", "fun suspendForCapture()")) {
            val body = src.substring(src.indexOf(fn)).substringBefore("\n    }")
            assertTrue("$fn releases immediately", body.contains("focus.releaseNow(this)"))
        }
        val shutdown = src.substring(src.indexOf("fun shutdown()")).substringBefore("\n    }")
        assertTrue("dispose goes through stop()", shutdown.contains("stop()"))
        val lost = src.substring(src.indexOf("private val onFocusLost")).substringBefore("\n    }")
        assertTrue("focus loss stops", lost.contains("stop()"))
    }

    @Test
    fun `android-speak keeps its own engine without focus`() {
        val src = ProductionSources.read("sandbox/offload/SpeakOffloadHandler.kt")
        assertFalse(src.contains("ReadAloudAudioFocus") || src.contains("requestAudioFocus"))
    }
}
