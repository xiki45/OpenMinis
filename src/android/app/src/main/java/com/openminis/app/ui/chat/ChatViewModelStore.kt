package com.openminis.app.ui.chat

import android.util.Log
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner

/**
 * Process-level cache of ChatViewModels keyed by sessionId. Mirrors iOS
 * `ViewModelCache` — a session's agent loop keeps running even if the user
 * leaves the chat screen, and the row in the sessions list shows a spinning
 * indicator while streaming is in flight.
 *
 * Without this, scoping the ChatViewModel to a NavBackStackEntry means
 * `popBackStack()` would cancel `viewModelScope` and kill the streaming job.
 */
object ChatViewModelStore {

    private const val TAG = "ChatVMStore"

    /**
     * One ViewModelStore per canonical sessionId. Each store contains at most
     * one ChatViewModel (the one created by our factory). When we want to drop
     * a session's VM, we call `clear()` on its store which triggers
     * `onCleared`.
     */
    private val stores = LinkedHashMap<String, ViewModelStore>()

    /**
     * [T-android-vm-store-leak] How many chat sessions keep a live
     * ChatViewModel at once.
     *
     * This cache had no bound and only one eviction path — [release], called
     * solely from SessionDeleter, i.e. when the user DELETES a session. Merely
     * navigating away never dropped anything, so every session opened in a
     * process lifetime stayed resident with its agent history and rendered
     * rows. A device log over ~3 hours showed exactly that: ten allocations,
     * ZERO releases, and a session last used at 00:5x still emitting frames at
     * 03:0x. Heap went 29MB (5%) -> 436MB (85%) of a 512MB cap and RSS 386MB ->
     * 3.3GB, after which nearly every allocation hit a blocking GC — ~230 GCs a
     * minute, 1.4s stalls, 149-frame drops, and the phone running hot.
     *
     * 4 keeps the warm-resume behaviour this cache exists for (the previous
     * chat, and the couple before it, still resume instantly) while bounding
     * the worst case. Anything actively streaming is never evicted regardless
     * of this number, so raising or lowering it cannot break a running agent.
     *
     * [T-android-vm-store-dual-pool] This bound now lives in
     * [MAX_CACHED_NORMAL] and [MAX_CACHED_CHILD], one per pool.
     */

    /**
     * [T-android-vm-store-dual-pool] Which cache pool a session belongs to.
     *
     * Sub agent sessions and the user's own chats compete for very different
     * reasons, and sharing one LRU made them evict each other. Measured on a
     * Pixel 4a with the sub agent cap (then a global 3) saturated: three children
     * allocated inside 55ms, and the third evicted the FIRST one — a child that
     * had existed for 55 milliseconds — while also pushing the user's own
     * sessions out of the cache:
     *
     *     allocate child 709994c9 (total=5)
     *     evict d655a80b (lru, remaining=4)     <- a normal session
     *     allocate child 1663bdae (total=5)
     *     evict e0358fc1 (lru, remaining=4)     <- another normal session
     *     allocate child 44a70eb6 (total=5)
     *     evict 709994c9 (lru, remaining=4)     <- the child from 55ms ago
     *
     * Separate pools with separate caps remove the interference in both
     * directions. Mirrors iOS `ViewModelCache` (ChatLifecycleSupport.swift).
     */
    enum class PoolKind { NORMAL, CHILD }

    /**
     * Pool membership, STICKY: a session's pool is decided when its store is
     * first created and never changes afterwards.
     *
     * Stickiness is what makes the classification trustworthy, because on
     * Android the same entry point serves two very different callers.
     * `HeadlessChatRunner.viewModelFor` both CREATES a child's view model
     * (ChatViewModel.createChildViewModel) and re-acquires it for READ-ONLY
     * display (AgentTranscriptScreen). Without stickiness, opening a finished
     * sub agent's transcript would re-register it, and any later change to how
     * that screen asks for the VM could silently reclassify a live child as a
     * normal session — putting it back in the pool this change exists to keep
     * it out of.
     *
     * [T-android-vm-store-child-tag-survives-evict] Entries are removed on
     * [release], but a CHILD tag deliberately OUTLIVES eviction (see
     * [dropStoreKeepingChildTag]). Stickiness used to last only as long as the
     * store: LRU trim / memory pressure removed the tag with it, and every
     * read-only re-acquire of a finished sub agent (AgentTranscriptScreen, the
     * delegate sheet's image poll, the Stop glyph) then re-created it in the
     * NORMAL pool and evicted one of the user's own warm chats. Only CHILD
     * tags are retained (NORMAL is the default anyway), so the map is bounded
     * by the number of sub agents run in this process — a few bytes each.
     */
    private val poolKinds = mutableMapOf<String, PoolKind>()

    /**
     * Cap for the normal pool.
     *
     * [T-new-limits] Was 4 — already below the 5 session slots it sat next to.
     * Now 8: the 6 agent loops SessionConcurrencyManager admits
     * (MAX_CONCURRENT) plus the chat on screen and the one the user just left.
     * Running sessions are never evicted, so with 4 every idle chat — the one
     * the user was reading included — was thrown out the moment a fifth loop
     * started, and switching between two chats while six agents ran reloaded
     * each from the database every time.
     *
     * Not higher: an idle cached view model keeps its whole transcript and
     * agent history in the Java heap. A text chat is a few MB, but a chat with
     * image history holds the encoded images too (75.8 MB for 100 images in
     * the Pixel 4a load test, Java peak 221–327 MB of a 512 MB heap), and only
     * the idle entries above the running ones are what this number buys.
     * handleMemoryPressure still releases every idle entry on onTrimMemory.
     */
    internal const val MAX_CACHED_NORMAL = 8

    /**
     * Cap for the child pool.
     *
     * Sized from Android's own numbers rather than copied from iOS (which uses
     * 10): when this was set the sub agent cap was a global 3, so 6 held two
     * full generations of concurrent jobs plus their read-back, at the ~1MB per
     * child session measured previously ~6MB. Larger would mostly cache
     * transcripts nobody reopens.
     *
     * [T-new-limits] The cap is now 3 PER PARENT conversation
     * (AgentJobRegistry.MAX_CONCURRENT_CHILD_JOBS_PER_PARENT), so up to 6
     * parents × 3 = 18 children can be live at once. Left at 6 on purpose: it
     * is a SOFT cap — a child whose job is still active is never evicted
     * (ChatViewModel.hasWorkInFlight counts its own PENDING/RUNNING job, which
     * also covers the start-up gap before its first send), and neither is a
     * transcript the user is reading ([holdWhileViewed]). So live children all
     * stay and the pool only runs over until they finish; the bound limits
     * cached FINISHED transcripts, which are cheap to reload from the database.
     */
    internal const val MAX_CACHED_CHILD = 6

    /**
     * Draft → canonical mapping. When a draft ("__new__...") session is
     * persisted, we add `draftKey -> realId` here so lookups via the old key
     * (from a ChatScreen whose `sessionId` parameter is still the draft)
     * continue to hit the same live store.
     */
    private val aliases = mutableMapOf<String, String>()

    /**
     * [T-android-split-draft-highlight] Bumped every time [aliases] changes.
     *
     * [aliases] is a plain map, so writing it schedules no recomposition — and
     * the two-pane list resolves its highlight THROUGH that map. When a draft
     * was promoted on first send the pane key deliberately stayed
     * `__new__<uuid>` (see [rename]: aliasing rather than re-keying is what
     * keeps the streaming ViewModel alive), so the highlight went on resolving
     * to the draft id, matched no persisted row, and the running session sat
     * unhighlighted in the list even after it had a title and a group.
     *
     * Exposing the generation as observable state gives Compose something to
     * subscribe to: readers key on it, the write invalidates them, and the
     * lookup re-runs against the now-populated alias.
     */
    private val aliasGeneration = androidx.compose.runtime.mutableIntStateOf(0)

    private fun resolveKey(sessionId: String): String =
        aliases[sessionId] ?: sessionId

    /**
     * [T-android-tablet-split] Public view of [resolveKey]: the PERSISTED id a
     * (possibly draft) session id now stands for.
     *
     * The two-pane list highlights whichever session the detail pane is
     * showing. When the detail holds a draft, the pane's key stays
     * `__new__<uuid>` even after the first send promotes it — [rename] aliases
     * the key rather than renaming it, precisely so the running screen and its
     * ViewModel are not disturbed mid-stream. Without this lookup the list
     * would go on highlighting nothing after the draft became a real row,
     * because no persisted row ever matches a `__new__` id.
     *
     * Returns the input unchanged when there is no alias, so a plain persisted
     * id and an un-promoted draft both behave sensibly.
     */
    @Synchronized
    fun resolvePersistedId(sessionId: String): String = resolveKey(sessionId)

    /**
     * [T-sub-agents-v1] The already-created ChatViewModel for [sessionId], or
     * null — never constructs one.
     *
     * For callers off the main thread that only want to observe a session that
     * is already live. `ownerFor` + ViewModelProvider would CREATE one, and
     * doing that off the main thread can produce a second instance for the same
     * session under a race.
     */
    @Synchronized
    fun existing(sessionId: String): ChatViewModel? {
        // Object monitor, not a block lock on the map: every writer (ownerFor,
        // trim, release, rename) holds the object monitor, and ownerFor
        // re-inserts on EVERY call, so a reader on a different lock could walk
        // the LinkedHashMap mid-mutation.
        val store = stores[resolveKey(sessionId)] ?: return null
        return viewModelIn(store)
    }

    /**
     * The ChatViewModel already living in [store], or null — never constructs
     * one. ViewModelStore has no public getter; ask a provider with a factory
     * that refuses to build, so a miss returns null instead of a new VM.
     */
    private fun viewModelIn(store: ViewModelStore): ChatViewModel? = runCatching {
        androidx.lifecycle.ViewModelProvider(
            store,
            object : androidx.lifecycle.ViewModelProvider.Factory {
                override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T =
                    throw IllegalStateException("no existing view model")
            },
        )[ChatViewModel::class.java]
    }.getOrNull()

    /**
     * [T-android-vm-evict-orphan] Whether [store] is still the live store for
     * [sessionId].
     *
     * `HeadlessChatRunner` caches a `ViewModelProvider` per session, bound to
     * the store this object handed out. Before the cache had a bound, that
     * store was only ever cleared on session delete, which also told the
     * runner to forget it. LRU trim and memory-pressure release clear stores
     * without any such notice, so the runner's provider went on pointing at a
     * cleared store: its next lookup built a NEW ChatViewModel inside a store
     * this cache no longer tracks — never evicted, invisible to [existing],
     * and a second instance next to the one `ChatScreen` gets from [ownerFor].
     * That is the split-state bug the shared store was introduced to fix. The
     * runner now asks this before trusting its cache.
     */
    @Synchronized
    fun isLiveStore(sessionId: String, store: ViewModelStore): Boolean =
        stores[resolveKey(sessionId)] === store

    /**
     * [T-android-vm-evict-busy] Every cached session that eviction must leave
     * alone because clearing it would destroy work in flight.
     *
     * Two sources, unioned:
     *  - `SessionActivityTracker.activeSessions` — the original signal, set by
     *    the stream job once it holds a concurrency slot.
     *  - the view model's own [ChatViewModel.hasWorkInFlight] — covers the gap
     *    before the slot is acquired (the send is queued behind other
     *    sessions, `viewModelScope` is alive, the tracker says idle) and a
     *    parent whose background sub agents are still running or queued.
     */
    private fun busyKeys(): Set<String> {
        val tracked = runCatching {
            com.openminis.app.service.SessionActivityTracker.activeSessions.value
                .map { resolveKey(it) }
        }.getOrDefault(emptyList())
        val inFlight = stores.entries
            .filter { (_, store) -> runCatching { viewModelIn(store)?.hasWorkInFlight() == true }.getOrDefault(false) }
            .map { it.key }
        return (tracked + inFlight + pendingSends.keys + viewers.keys).toSet()
    }

    /**
     * [T-new-limits] Sessions a screen other than ChatScreen is showing, keyed
     * by store key, refcounted. ChatScreen claims [setActiveSession]; the sub
     * agent transcript screen does not (it must not become the "current
     * session" config reads target), so without this a finished child the
     * user was reading could be evicted by any other child starting — with
     * 6 parents × 3 children that is a routine event, not an edge case.
     */
    private val viewers = java.util.concurrent.ConcurrentHashMap<String, Int>()

    /** Keep [sessionId]'s store from eviction until the returned call runs. */
    fun holdWhileViewed(sessionId: String): () -> Unit {
        val key = resolveKey(sessionId)
        viewers.merge(key, 1, Int::plus)
        var released = false
        return {
            if (!released) {
                released = true
                viewers.computeIfPresent(key) { _, n -> if (n <= 1) null else n - 1 }
            }
        }
    }

    /**
     * [T-android-vm-evict-pending-send] Sessions a caller has taken a view
     * model for and is about to send into, keyed by store key, refcounted.
     *
     * Between creating a view model and its first send there is a gap where it
     * looks idle to every other busy signal: HeadlessChatRunner waits there
     * for the model binding to resolve, and the view model is not streaming
     * yet. Starting 8 sessions at once through that path let each new one
     * evict the previous (the NORMAL pool holds 4); the evicted view models'
     * load was cancelled, no model ever resolved, the prompt was never sent,
     * and the list kept 4 empty "New Chat" rows.
     */
    private val pendingSends = java.util.concurrent.ConcurrentHashMap<String, Int>()

    /** Keep [sessionId]'s store from eviction while [block] prepares a send. */
    suspend fun <T> holdingForSend(sessionId: String, block: suspend () -> T): T {
        val key = resolveKey(sessionId)
        pendingSends.merge(key, 1, Int::plus)
        try {
            return block()
        } finally {
            pendingSends.computeIfPresent(key) { _, n -> if (n <= 1) null else n - 1 }
        }
    }

    /**
     * [T-android-split-draft-highlight] Compose-aware [resolvePersistedId].
     *
     * Reading [aliasGeneration] inside a composition subscribes the caller to
     * alias changes, so a draft that gets promoted mid-stream re-resolves to
     * its real id and the list highlight follows it. Call this from
     * composables; [resolvePersistedId] remains for non-Compose callers.
     */
    @androidx.compose.runtime.Composable
    fun rememberPersistedId(sessionId: String): String {
        val generation = aliasGeneration.intValue
        return androidx.compose.runtime.remember(sessionId, generation) {
            resolveKey(sessionId)
        }
    }

    /**
     * [T-android-vm-store-dual-pool] [kind] classifies a session on FIRST
     * creation only; see [poolKinds] for why that is sticky.
     *
     * Defaulting to NORMAL keeps every existing call site correct without
     * changes: `ChatScreen` (the user's own chat) and the two debug RPC
     * entry points all open normal sessions. Only
     * `HeadlessChatRunner.viewModelFor` passes CHILD, and it is the single
     * path a sub agent's view model is ever built through.
     */
    @Synchronized
    @JvmOverloads
    fun ownerFor(sessionId: String, kind: PoolKind = PoolKind.NORMAL): ViewModelStoreOwner {
        val key = resolveKey(sessionId)
        val existing = stores.remove(key)
        val store = if (existing != null) {
            // Re-insert so LinkedHashMap's iteration order is least-recently-
            // USED, not merely insertion order: a long-lived session the user
            // keeps returning to must not be evicted ahead of one they opened
            // once and abandoned.
            existing
        } else {
            // First sight of this session: this is the only moment its pool is
            // decided. putIfAbsent rather than put, so a later call with a
            // different `kind` cannot reclassify a live session.
            poolKinds.putIfAbsent(key, kind)
            Log.d(
                TAG,
                "allocate store for $key kind=${poolKinds[key]} " +
                    "(total=${stores.size + 1}, pool=${poolSize(poolKinds[key]) + 1})",
            )
            ViewModelStore()
        }
        stores[key] = store
        trimToCapacity(poolKinds[key] ?: PoolKind.NORMAL)
        return object : ViewModelStoreOwner {
            override val viewModelStore: ViewModelStore = store
        }
    }

    /**
     * [T-android-vm-store-leak] Evict least-recently-used sessions past
     * [MAX_CACHED_NORMAL] / [MAX_CACHED_CHILD].
     *
     * Two categories are never evicted, and both matter:
     *
     *  - anything in `SessionActivityTracker.activeSessions` — a session whose
     *    agent loop is still running. Clearing its store cancels
     *    `viewModelScope` and kills the stream mid-flight, which is the very
     *    thing this cache was built to prevent. This is why the cap is a
     *    soft one: if the user genuinely has five agents in flight, all five
     *    stay, and the cache simply runs over until they finish.
     *  - the session currently on screen, which would otherwise be torn down
     *    underneath the composition that is reading it.
     */
    /** Number of cached stores currently belonging to [kind]. */
    private fun poolSize(kind: PoolKind?): Int {
        if (kind == null) return 0
        return stores.keys.count { (poolKinds[it] ?: PoolKind.NORMAL) == kind }
    }

    /**
     * [T-android-vm-store-dual-pool] Trim ONE pool, independently of the other.
     *
     * Only the pool that just grew is considered, and only its own members are
     * eligible for eviction, so a burst of sub agents can no longer push the
     * user's chats out of the cache (or each other — a running child is never
     * evicted, so even 6 parents × 3 children over MAX_CACHED_CHILD = 6 only
     * makes the pool run over until they finish).
     *
     * Both carve-outs from the single-pool version still apply, in both pools,
     * for unchanged reasons: a session whose agent loop is running would have
     * its `viewModelScope` cancelled and its stream killed, and the on-screen
     * session would be torn down underneath the composition reading it. This is
     * still therefore a SOFT cap — if every member of a pool is busy, the pool
     * runs over rather than breaking a live run.
     */
    private fun trimToCapacity(kind: PoolKind) {
        val cap = when (kind) {
            PoolKind.NORMAL -> MAX_CACHED_NORMAL
            PoolKind.CHILD -> MAX_CACHED_CHILD
        }
        val inPool = stores.keys.filter { (poolKinds[it] ?: PoolKind.NORMAL) == kind }
        if (inPool.size <= cap) return
        val busy = busyKeys()
        val onScreen = activeSessionIdInternal?.let { resolveKey(it) }
        // [T-android-vm-evict-busy] The store this call just inserted is the
        // LAST key, and its ChatViewModel does not exist yet — `ownerFor`
        // returns the owner and the caller builds the VM afterwards. So it
        // looks idle to every busy signal there is, and being the oldest
        // untouched entry it was the first thing evicted: measured on a
        // Pixel 4a, a 6th session opened while five slots were held allocated
        // its store and cleared it 2ms later, killing the send. Never evict
        // the key this insertion is for; the next call trims it normally once
        // it has a view model to answer for itself.
        val justInserted = inPool.lastOrNull()
        // Oldest first; LinkedHashMap keeps that order for us.
        val evictable = inPool.filter { it != onScreen && it != justInserted && it !in busy }
        var overflow = inPool.size - cap
        for (key in evictable) {
            if (overflow <= 0) break
            dropStoreKeepingChildTag(key)
            overflow--
            Log.d(
                TAG,
                "evict store for $key kind=$kind (lru, remaining=${stores.size}, " +
                    "pool=${poolSize(kind)})",
            )
        }
    }

    /**
     * [T-android-trimmemory-vmstore] Release every cached session the system
     * can safely take back, in response to real memory pressure.
     *
     * [trimToCapacity] is a SOFT cap keyed on count: it only ever evicts down
     * to each pool's cap, and only when a new session is added. Neither
     * condition holds when the pressure comes from outside this cache — the
     * user can sit on four legitimately-cached heavy sessions while the device
     * runs out of memory, and nothing here would give anything back. This is
     * the last line of defence for that case, driven by `onTrimMemory`.
     *
     * The two exemptions from [trimToCapacity] are preserved exactly, and for
     * the same reasons — releasing either would be worse than the memory it
     * reclaims:
     *   - a session whose agent loop is running ([SessionActivityTracker]):
     *     clearing it cancels `viewModelScope` and kills the stream mid-flight;
     *   - the session currently on screen: it would be torn down underneath
     *     the composition reading it.
     *
     * Returns the number of stores actually released, so the caller can log
     * whether the signal did anything.
     */
    @Synchronized
    fun handleMemoryPressure(): Int {
        if (stores.isEmpty()) return 0
        val busy = busyKeys()
        val onScreen = activeSessionIdInternal?.let { resolveKey(it) }
        val evictable = stores.keys.filter { it != onScreen && it !in busy }
        for (key in evictable) {
            dropStoreKeepingChildTag(key)
        }
        if (evictable.isNotEmpty()) {
            Log.d(
                TAG,
                "memory pressure: released ${evictable.size} store(s), " +
                    "remaining=${stores.size} (kept onScreen=${onScreen != null} busy=${busy.size})",
            )
        }
        return evictable.size
    }

    /**
     * [T-android-vm-store-child-tag-survives-evict] Evict [key]'s store (LRU
     * trim or memory pressure) without forgetting that it is a sub agent.
     * A NORMAL tag is dropped because NORMAL is the default; a CHILD tag is
     * kept so that re-opening the evicted child — through any entry point,
     * including ones that pass the NORMAL default — lands it back in the
     * CHILD pool instead of evicting a user chat. [release] is still the one
     * place a tag is forgotten for good.
     */
    private fun dropStoreKeepingChildTag(key: String) {
        stores.remove(key)?.clear()
        if (poolKinds[key] != PoolKind.CHILD) poolKinds.remove(key)
        // [OpenMinis#433] A parked draft whose view model is gone has nothing
        // left to hand back.
        forgetDraft(key)
    }

    /**
     * Drop the cached VM for this session (cancels `viewModelScope`, triggers
     * `ChatViewModel.onCleared`). Call when the session is deleted. Also
     * clears any draft alias pointing at this canonical id.
     */
    @Synchronized
    fun release(sessionId: String) {
        val key = resolveKey(sessionId)
        aliases.entries.removeAll { it.value == key }
        aliasGeneration.intValue++
        // [T-android-vm-store-child-tag-survives-evict] Forget the tag even
        // when the store was already evicted: a CHILD tag outlives eviction.
        poolKinds.remove(key)
        forgetDraft(key)
        stores.remove(key)?.let {
            it.clear()
            Log.d(TAG, "release store for $key (remaining=${stores.size})")
        }
    }

    /**
     * Mark `fromSessionId` (a draft key) as an alias for `toSessionId` (the
     * real, persisted id). The live store stays under the real id; future
     * `ownerFor(draftKey)` lookups resolve to the same store so a ChatScreen
     * still rendering with the draft route continues to see the running VM.
     */
    /**
     * T311: id of the chat the user has on screen right now. Set by
     * `ChatScreen`'s lifecycle hook on enter, cleared on dispose.
     * `minis-config session.*` reads this so reads/writes target the
     * "current session" the same way iOS `AIChatViewModel.activeSessionId`
     * does. `null` = no chat is foregrounded → reads return empty / writes
     * throw `No active session`. Resolves through `aliases` so a draft id
     * still maps to the persisted row.
     */
    @Volatile
    private var activeSessionIdInternal: String? = null

    val activeSessionId: String?
        get() = activeSessionIdInternal?.let { resolveKey(it) }

    @Synchronized
    fun setActiveSession(sessionId: String?) {
        activeSessionIdInternal = sessionId
    }

    /**
     * [T-android-active-session-cas] Clear the on-screen id only if it still
     * belongs to [sessionId]. A screen being disposed must not wipe the id a
     * newer screen already claimed (CHAT -> CHAT navigation runs the incoming
     * screen's enter before the outgoing screen's dispose). Compared through
     * the alias map so a promoted draft still recognises its own claim.
     */
    @Synchronized
    fun clearActiveSession(sessionId: String) {
        val current = activeSessionIdInternal ?: return
        if (resolveKey(current) != resolveKey(sessionId)) return
        activeSessionIdInternal = null
    }

    @Synchronized
    fun rename(fromSessionId: String, toSessionId: String) {
        if (fromSessionId == toSessionId) return
        val store = stores.remove(fromSessionId)
        if (store != null) {
            stores[toSessionId] = store
        }
        // [T-android-vm-store-dual-pool] Carry pool membership across the
        // re-key. Without this a renamed session loses its tag and silently
        // falls back to NORMAL on the next lookup — which for a promoted draft
        // that happens to be a child would put it in the pool this change
        // exists to keep it out of.
        poolKinds.remove(fromSessionId)?.let { poolKinds[toSessionId] = it }
        // [OpenMinis#433] Sent: no longer a draft that New Chat may reuse.
        forgetDraft(fromSessionId)
        aliases[fromSessionId] = toSessionId
        // Invalidate anything resolving through the alias map — see
        // [aliasGeneration].
        aliasGeneration.intValue++
        Log.d(TAG, "rename store $fromSessionId -> $toSessionId (alias kept)")
    }

    // ─── Unsent new chats [OpenMinis#433] ───────────────────────────────

    /**
     * [OpenMinis#433] How long a new chat left with unsent text stays
     * available to the next New Chat, counted from the moment it was left.
     * Same value as iOS `ParkedDrafts.lifetime`.
     */
    internal const val PARKED_DRAFT_LIFETIME_MS = 15 * 60 * 1000L

    /** The clock parking reads. Replaced in tests; nothing ever waits on it. */
    @Volatile
    internal var parkedDraftClock: () -> Long = { System.currentTimeMillis() }

    private data class ParkedDraft(val draftId: String, val parkedAtMs: Long)

    /**
     * [OpenMinis#433] New chats the user left with something typed or attached
     * but never sent, one per slot, keyed by [draftSlotKey].
     *
     * Text typed in a new chat was lost on Back + New Chat: the draft's view
     * model stayed in [stores] with the text in it, but every New Chat minted a
     * fresh `__new__<uuid>`, so nothing could reach it again until LRU threw it
     * out. Parking records which draft id to hand back; the view model itself
     * never moves. Memory only, and deliberately not a session row (a row per
     * abandoned draft would fill the list with empty chats). Mirrors iOS
     * `ViewModelCache.parkedNewChats` (d96f8c71d, 885696d42).
     *
     * No timer: expired slots are dropped by whichever park / take / unpark
     * call comes next.
     */
    private val parkedDrafts = mutableMapOf<String, ParkedDraft>()

    /**
     * Draft ids minted by [newChatDraftId], i.e. by a New Chat button. Only
     * these are ever parked. Drafts opened by a share, a quick action, a deep
     * link, the launch-mode preference or the Move-to sheet carry content or
     * intent of their own: they must not pick up an old draft (they never call
     * [newChatDraftId]) and leaving them must neither park nor clear a slot.
     */
    private val newChatDraftIds = mutableSetOf<String>()

    /**
     * The slot a draft id parks in: its model group AND its folder, both read
     * from the id the same way ChatViewModel reads them.
     *
     * iOS keys by model group only, so text typed in "New Chat in Group X"
     * reappears in the next plain New Chat and is filed outside X once sent.
     * Android reuses the draft id itself, and the folder is encoded in it, so
     * a group-only key would carry X into the plain New Chat instead — either
     * way the chat would land somewhere the user did not open it. Matching on
     * both keeps each draft where it was started.
     */
    private fun draftSlotKey(groupId: String?, folderId: String?): String =
        "grp=${groupId.orEmpty()}|fld=${folderId.orEmpty()}"

    private fun draftSlotKey(draftId: String): String = draftSlotKey(
        draftId.substringAfter("__grp__", "").substringBefore("__fld__"),
        draftId.substringAfter("__fld__", "").substringBefore("__grp__"),
    )

    /**
     * [OpenMinis#433] What counts as an unsent draft worth keeping: a draft
     * that has no session row yet and is not running, holding non-blank text
     * (a `[Pasted#N]` placeholder counts — its buffer lives on the same view
     * model) or an attachment. Same rule as iOS `hasUnsentDraft` +
     * `parkNewChatIfUnsent`; Android has no remote sessions to exclude.
     */
    internal fun isUnsentDraft(
        isDraft: Boolean,
        realSessionId: String,
        text: String,
        hasAttachments: Boolean,
        busy: Boolean,
    ): Boolean = isDraft && realSessionId.isEmpty() && !busy && (text.isNotBlank() || hasAttachments)

    /**
     * [OpenMinis#433] The draft id a New Chat button should open: the unsent
     * draft parked for this model group and folder if there is one, otherwise
     * a fresh `__new__<uuid>[__grp__<g>][__fld__<f>]`.
     *
     * A parked draft is handed out once (its slot is emptied), and only while
     * its view model is still cached, never promoted, and still holding
     * something; anything else falls through to a fresh id.
     */
    @Synchronized
    fun newChatDraftId(groupId: String? = null, folderId: String? = null): String {
        val key = draftSlotKey(groupId, folderId)
        purgeExpiredDrafts(parkedDraftClock())
        parkedDrafts.remove(key)?.let { parked ->
            if (isResumableDraft(parked.draftId)) {
                Log.d(TAG, "newChatDraftId: resuming parked draft ${parked.draftId} slot=$key")
                return parked.draftId
            }
        }
        var id = "__new__${java.util.UUID.randomUUID()}"
        if (groupId != null) id += "__grp__$groupId"
        if (folderId != null) id += "__fld__$folderId"
        newChatDraftIds += id
        return id
    }

    private fun isResumableDraft(draftId: String): Boolean {
        if (aliases.containsKey(draftId)) return false
        val store = stores[draftId] ?: return false
        // A view model that has since lost its text (or started running) has
        // nothing to resume. Without one (only in JVM tests: a parked draft
        // was reported by its own view model) trust the verdict at parking.
        return viewModelIn(store)?.hasUnsentDraft ?: true
    }

    /**
     * [OpenMinis#433] ChatScreen is leaving [sessionId]. Park it when it is a
     * New Chat draft with unsent content; otherwise empty its slot if the slot
     * holds this very draft, so text the user cleared before leaving does not
     * come back — and another chat's parked draft is left alone.
     */
    @Synchronized
    fun parkDraftIfUnsent(sessionId: String, hasUnsentDraft: Boolean) {
        if (sessionId !in newChatDraftIds) return
        val now = parkedDraftClock()
        purgeExpiredDrafts(now)
        val key = draftSlotKey(sessionId)
        val keep = hasUnsentDraft && stores.containsKey(sessionId) && !aliases.containsKey(sessionId)
        if (keep) {
            parkedDrafts[key] = ParkedDraft(sessionId, now)
            Log.d(TAG, "parkDraft $sessionId slot=$key keptFor=${PARKED_DRAFT_LIFETIME_MS / 1000}s")
        } else if (parkedDrafts[key]?.draftId == sessionId) {
            parkedDrafts.remove(key)
        }
    }

    /** [OpenMinis#433] [sessionId] is on screen again: no New Chat may take it. */
    @Synchronized
    fun unparkDraft(sessionId: String) {
        purgeExpiredDrafts(parkedDraftClock())
        parkedDrafts.entries.removeAll { it.value.draftId == sessionId }
    }

    /**
     * Drop every slot parked longer than [PARKED_DRAFT_LIFETIME_MS] ago, and
     * release its view model (with its attachments) unless something still
     * needs it, the way iOS lets the parked VM go.
     */
    private fun purgeExpiredDrafts(now: Long) {
        val expired = parkedDrafts.filterValues { now - it.parkedAtMs > PARKED_DRAFT_LIFETIME_MS }
        if (expired.isEmpty()) return
        expired.keys.forEach { parkedDrafts.remove(it) }
        val busy = busyKeys()
        val onScreen = activeSessionIdInternal?.let { resolveKey(it) }
        for (draft in expired.values) {
            val id = draft.draftId
            if (id != onScreen && id !in busy && !aliases.containsKey(id)) dropStoreKeepingChildTag(id)
        }
        Log.d(TAG, "purgeExpiredDrafts: dropped ${expired.size} parked draft(s)")
    }

    /** Forget [draftId] as a parkable New Chat draft (evicted, deleted or sent). */
    private fun forgetDraft(draftId: String) {
        if (newChatDraftIds.remove(draftId)) {
            parkedDrafts.entries.removeAll { it.value.draftId == draftId }
        }
    }

    @Synchronized
    internal fun clearParkedDraftsForTest() {
        parkedDrafts.clear()
        newChatDraftIds.clear()
    }

    /**
     * One-shot stash for the "Move to…" capsule flow. The source session
     * writes (inputText + attachments) here, navigates to the target,
     * and the target's ChatScreen drains it via [consumePendingTransfer].
     * Mirrors iOS `ViewModelCache.pendingTransfer`. Volatile + simple
     * read/write — only ever touched from the main thread.
     */
    data class PendingTransfer(
        val inputText: String,
        val attachments: List<InputAttachment>,
        /**
         * [T-android-moveto-stash-binding] Session this content was moved TO.
         * Only that session may drain the stash. Previously absent, so
         * whichever ChatScreen composed first ate the content — if the
         * navigation to the target didn't land (or the user backed out and
         * opened something else), the moved text/attachments surfaced in an
         * unrelated session. Mirrors iOS 6c3093c8 (GH OpenMinis#120).
         */
        val targetId: String,
        /** Wall-clock stash time; drives the [STASH_TTL_MS] staleness drop. */
        val stashedAtMs: Long = System.currentTimeMillis(),
    )

    /**
     * [T-android-moveto-stash-binding] A stash older than this is considered
     * abandoned and dropped rather than injected. Without it an unclaimed
     * stash sat forever and could ambush a session opened much later.
     */
    private const val STASH_TTL_MS = 300_000L

    @Volatile
    private var pendingTransfer: PendingTransfer? = null

    fun stashPendingTransfer(transfer: PendingTransfer) {
        pendingTransfer = transfer
        Log.d(
            TAG,
            "stashPendingTransfer: target=${transfer.targetId} " +
                "text=${transfer.inputText.length}ch attachments=${transfer.attachments.size}",
        )
    }

    /**
     * Drain the pending-transfer slot exactly once, and only for the session
     * it was addressed to.
     *
     * [sessionId] is the draining screen's session. A mismatch leaves the
     * stash in place so the real target can still claim it when it opens.
     * An expired stash is dropped outright.
     */
    fun consumePendingTransfer(sessionId: String): PendingTransfer? {
        val t = pendingTransfer ?: return null
        if (System.currentTimeMillis() - t.stashedAtMs > STASH_TTL_MS) {
            pendingTransfer = null
            Log.d(TAG, "consumePendingTransfer: dropping stale stash (target=${t.targetId})")
            return null
        }
        // Compare through the draft→canonical alias map, the same way ownerFor /
        // release / activeSessionId do. Today MoveToSessionSheet only offers
        // PERSISTED sessions and rename() only fires for drafts, so raw ids
        // would already match — but resolving makes this correct by
        // construction instead of relying on that invariant, so a future
        // "move into a new chat" target can't strand the transfer.
        if (resolveKey(t.targetId) != resolveKey(sessionId)) {
            // Not ours — leave it for the intended target.
            Log.d(
                TAG,
                "consumePendingTransfer: session=$sessionId is not target=${t.targetId}, leaving stash",
            )
            return null
        }
        pendingTransfer = null
        Log.d(
            TAG,
            "consumePendingTransfer: target=${t.targetId} " +
                "text=${t.inputText.length}ch attachments=${t.attachments.size}",
        )
        return t
    }
}
