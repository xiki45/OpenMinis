package com.openminis.app.speech

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import com.openminis.app.logging.AppLogger

/**
 * [T-readaloud-focus] The single audio-focus holder for read-aloud, shared by
 * the system-engine and provider-TTS paths of every [ReadAloudPlayer].
 *
 * ## Why
 * The system engine never requested focus, so other apps' music/video was not
 * ducked while a reply was read with the device voice (the "can't hear it over
 * a video" report). The provider path requested and abandoned focus PER
 * SENTENCE, so music pumped back up between sentences, and nothing listened
 * for focus loss — a phone call did not stop it.
 *
 * ## Model
 * - Owners: each player is an owner. Focus is requested when the first owner
 *   [acquire]s (right before audio starts — never during synthesis, which
 *   would duck the user's music over silence) and abandoned when the last
 *   owner is gone. Owners are a set, so a duplicate acquire/release never
 *   unbalances anything.
 * - Grace: after an utterance [releaseSoon] keeps the owner for a while; the
 *   next sentence starting inside that window cancels the release, so a run of
 *   sentences holds focus continuously. The window depends on what comes next:
 *   [SYNTH_WAIT_MS] when more sentences are already queued (the next one is
 *   being synthesized — provider TTS measured 3-4 s per sentence on device,
 *   which a short grace turned into a release/re-request per sentence), and
 *   [GRACE_MS] when the queue is empty (end of a run, or a streaming reply
 *   waiting on the model). A gap longer than the window lets the user's music
 *   back up and focus is re-requested when speech resumes.
 * - Immediate release: [releaseNow] for stop / mute / dictation / dispose.
 * - Loss: LOSS and LOSS_TRANSIENT stop every owner (neither engine can pause
 *   mid-utterance) and release; LOSS_TRANSIENT_CAN_DUCK (a navigation prompt)
 *   is ignored and reading continues.
 * - Watchdog: if no new acquire happens for [maxHoldMs], focus is abandoned
 *   anyway. This bounds the worst case — an utterance that never completes —
 *   to minutes of ducked music instead of forever.
 *
 * Main-thread confined, like [ReadAloudPlayer]; focus callbacks are delivered
 * on the main looper.
 */
class ReadAloudAudioFocus internal constructor(
    private val gateway: Gateway,
    private val scheduler: Scheduler,
    private val graceMs: Long = GRACE_MS,
    private val maxHoldMs: Long = MAX_HOLD_MS,
    private val log: (String) -> Unit = { AppLogger.info(TAG, it) },
) {

    /** The platform focus API, behind an interface so tests can fake it. */
    interface Gateway {
        /** Focus-change callback target; set once by the holder. */
        var onFocusChange: (Int) -> Unit

        /** @return an AudioManager.AUDIOFOCUS_REQUEST_* code. */
        fun request(): Int
        fun abandon()
    }

    /** Delayed execution; returns a canceller. */
    fun interface Scheduler {
        fun schedule(delayMs: Long, block: () -> Unit): () -> Unit
    }

    private class Owner(var onLost: () -> Unit) {
        var cancelGrace: (() -> Unit)? = null
    }

    private val owners = LinkedHashMap<Any, Owner>()
    private var cancelWatchdog: (() -> Unit)? = null

    /** True while the platform has granted us focus and we have not abandoned it. */
    var isHeld: Boolean = false
        private set

    val ownerCount: Int get() = owners.size

    init {
        gateway.onFocusChange = ::onFocusChange
    }

    /** Audio is about to start for [owner]; [onLost] is called if focus is taken away. */
    fun acquire(owner: Any, onLost: () -> Unit) {
        val entry = owners.getOrPut(owner) { Owner(onLost) }
        entry.onLost = onLost
        entry.cancelGrace?.invoke()
        entry.cancelGrace = null
        armWatchdog()
        if (isHeld) return
        val rc = gateway.request()
        isHeld = rc == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        log("request MAY_DUCK -> ${if (isHeld) "granted" else "not granted ($rc), playing anyway"} owners=${owners.size}")
    }

    /**
     * An utterance ended; drop [owner] unless another one starts within
     * [delayMs] ([graceMs] by default; [SYNTH_WAIT_MS] when more is queued).
     */
    fun releaseSoon(owner: Any, delayMs: Long = graceMs) {
        val entry = owners[owner] ?: return
        entry.cancelGrace?.invoke()
        entry.cancelGrace = scheduler.schedule(delayMs) {
            if (owners[owner] === entry) {
                entry.cancelGrace = null
                remove(owner, "idle ${delayMs}ms")
            }
        }
    }

    /** Stop, mute, dictation, dispose: drop [owner] now. Idempotent. */
    fun releaseNow(owner: Any) {
        val entry = owners[owner] ?: return
        entry.cancelGrace?.invoke()
        remove(owner, "released")
    }

    private fun remove(owner: Any, reason: String) {
        owners.remove(owner)
        if (owners.isEmpty()) abandon(reason)
    }

    private fun abandon(reason: String) {
        cancelWatchdog?.invoke()
        cancelWatchdog = null
        owners.values.forEach { it.cancelGrace?.invoke() }
        owners.clear()
        if (!isHeld) return
        isHeld = false
        gateway.abandon()
        log("abandon ($reason)")
    }

    private fun armWatchdog() {
        cancelWatchdog?.invoke()
        cancelWatchdog = scheduler.schedule(maxHoldMs) {
            cancelWatchdog = null
            log("watchdog: no speech started for ${maxHoldMs}ms with focus held — forcing release")
            abandon("watchdog")
        }
    }

    private fun onFocusChange(change: Int) {
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                log("focus lost ($change) — stopping read-aloud")
                // Snapshot: onLost -> player.stop() -> releaseNow mutates owners.
                val lost = owners.values.map { it.onLost }
                lost.forEach { runCatching { it() } }
                abandon("lost $change")
            }
            // A short prompt (navigation, notification): keep reading.
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> log("transient duck request — continuing")
            else -> Unit
        }
    }

    private class PlatformGateway(context: Context) : Gateway {
        private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        override var onFocusChange: (Int) -> Unit = {}

        // One request object for the holder's lifetime: abandonAudioFocusRequest
        // matches by request, so abandon always targets what was requested.
        private val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(ATTRIBUTES)
            .setOnAudioFocusChangeListener({ onFocusChange(it) }, Handler(Looper.getMainLooper()))
            .build()

        override fun request(): Int = audioManager.requestAudioFocus(request)
        override fun abandon() {
            audioManager.abandonAudioFocusRequest(request)
        }
    }

    companion object {
        private const val TAG = "ReadAloudFocus"

        /** Queue empty: bridges streaming gaps; longer gaps release (see class KDoc). */
        const val GRACE_MS = 2_500L

        /**
         * More sentences queued: covers provider synthesis of the next one
         * (measured 3-4 s for Gemini TTS on a Pixel 4a). Bounded so a stalled
         * request does not keep the user's music ducked over silence for long.
         */
        const val SYNTH_WAIT_MS = 8_000L

        /** Worst-case bound on holding focus without any speech starting. */
        const val MAX_HOLD_MS = 5 * 60_000L

        /** Read-aloud playback attributes, shared by the focus request and MediaPlayer. */
        val ATTRIBUTES: AudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

        @Volatile
        private var shared: ReadAloudAudioFocus? = null

        /** Process-wide holder: every player shares one focus request. */
        fun shared(context: Context): ReadAloudAudioFocus =
            shared ?: synchronized(this) {
                shared ?: run {
                    val main = Handler(Looper.getMainLooper())
                    ReadAloudAudioFocus(
                        gateway = PlatformGateway(context.applicationContext),
                        scheduler = { delayMs, block ->
                            val r = Runnable(block)
                            main.postDelayed(r, delayMs)
                            ({ main.removeCallbacks(r) })
                        },
                    )
                }.also { shared = it }
            }
    }
}
