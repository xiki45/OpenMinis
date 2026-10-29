package com.openminis.app.quota

import android.content.Context
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.openminis.app.auth.OAuthManager
import com.openminis.app.data.model.ProviderCredential
import com.openminis.app.data.model.ProviderInstance
import com.openminis.app.data.model.ProviderType
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.logging.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

// [T-provider-quota] Owns upstream quota state: resolves provider instances to
// quota subjects, runs adapter fetches under the refresh / capability gates
// with per-subject single flight, merges snapshots, persists a secret-free
// cache locally, and schedules foreground refreshes. Port of iOS QuotaStore.
//
// Rate rules:
// - [T-quota-attention-refresh] attention decides the interval. A provider is
//   WATCHED while a view showing its quota is on screen: the chat's nav bar
//   dot (the current model), a picker section header or Model Group member
//   pill scrolled into view, the provider page, the quota sheet. Coming into
//   view refreshes it if due, and while watched it keeps the normal interval
//   below. Everything else is refreshed at most once an hour;
// - normal refresh: at most every 10 minutes per subject (one subject = one
//   credential = one canonical pool set);
// - no automatic request for a subject is ever sooner than 5 minutes after the
//   previous one, failures included (backoff starts at 5 min);
// - forced refresh (the user's button) bypasses the 10-minute cache but not
//   Retry-After, the single-flight lock, or a 30 s debounce;
// - capability: a failed probe (404 / unreadable answer) blocks queries for
//   that provider type + base URL for 24 h, unless the user re-checks;
// - foreground only: on start (after a 3 s settle) and then on a 60 s tick,
//   every eligible subject that is due is refreshed. Nothing runs in the
//   background.
//
// Never logs or persists a credential, an Authorization header or a raw
// response body.

private const val TAG = "Quota"

/** What the detail section shows for one instance. */
sealed class QuotaInstanceDisplay {
    /** Hidden: disabled instance or no credential (the credential section already asks for one). */
    data object Hidden : QuotaInstanceDisplay()
    data class Unsupported(val reason: QuotaSubject.UnsupportedReason) : QuotaInstanceDisplay()
    data object Loading : QuotaInstanceDisplay()

    /** Queried; upstream reported no pool for this credential. */
    data class Empty(val subjectId: QuotaSubjectID) : QuotaInstanceDisplay()

    /** Queried and failed, nothing cached to show. */
    data class Failed(val error: QuotaError) : QuotaInstanceDisplay()
    data class Pools(val subjectId: QuotaSubjectID) : QuotaInstanceDisplay()
}

sealed class QuotaRefreshResult {
    data object Refreshed : QuotaRefreshResult()
    data class Failed(val error: QuotaError) : QuotaRefreshResult()
    data class Blocked(val decision: QuotaRefreshDecision) : QuotaRefreshResult()
    data object Unsupported : QuotaRefreshResult()
    data class NotConfigured(val reason: QuotaSubject.NotConfiguredReason) : QuotaRefreshResult()
    data object CapabilityFailed : QuotaRefreshResult()
}

/** Everything the UI reads. Immutable: every change publishes a new value. */
data class QuotaState(
    val subjects: Map<QuotaSubjectID, QuotaSubject> = emptyMap(),
    val pools: Map<QuotaPoolKey, QuotaPool> = emptyMap(),
    val bindings: List<QuotaPoolBinding> = emptyList(),
    val capabilities: Map<QuotaCapabilityKey, QuotaCapabilityRecord> = emptyMap(),
    val summaries: Map<String, QuotaSnapshotSummary> = emptyMap(),
    val instanceSubjects: Map<String, QuotaSubjectID> = emptyMap(),
    /** Not persisted: why the last refresh of an instance did not reach a subject. */
    val instanceNotes: Map<String, QuotaSubject.NotConfiguredReason> = emptyMap(),
    /** Instances whose subject is being fetched right now. */
    val refreshingInstanceIds: Set<String> = emptySet(),
) {
    /** Each canonical pool of a subject exactly once, stable order. */
    fun poolsOf(subject: QuotaSubjectID): List<QuotaPool> =
        pools.values.filter { it.subjectId == subject }
            .sortedWith(compareBy<QuotaPool> { kindOrder(it.kind) }.thenBy { it.id.rawValue })

    fun bindingsOf(subject: QuotaSubjectID): List<QuotaPoolBinding> =
        bindings.filter { pools[it.poolKey]?.subjectId == subject }

    fun isRefreshing(instanceId: String): Boolean = instanceId in refreshingInstanceIds

    /** Other instances resolving to the same account (for the "don't add them up" note). */
    fun sharingInstanceIds(instanceId: String): List<String> {
        val sid = instanceSubjects[instanceId] ?: return emptyList()
        val s = subjects[sid] ?: return emptyList()
        return s.instanceIds.filter { it != instanceId }.sorted()
    }

    fun poolStatus(pool: QuotaPool, now: Long = System.currentTimeMillis()): PoolStatus =
        QuotaStatusRules.poolStatus(pool, now, QuotaStore.freshnessPolicy, QuotaStore.thresholds)

    /**
     * The model's view over every pool that applies to it.
     * [T-quota-no-manual-mapping] Upstream bindings only.
     */
    fun modelView(instanceId: String, modelId: String, now: Long = System.currentTimeMillis()): ModelQuotaView =
        QuotaModelResolver.view(
            instanceId = instanceId, modelId = modelId, subjectId = instanceSubjects[instanceId],
            pools = pools, bindings = bindings, manualBindings = emptyList(), now = now,
            freshness = QuotaStore.freshnessPolicy, thresholds = QuotaStore.thresholds,
        )

    /** [T-quota-tag-level] The account's level over ALL its pools (picker header tag). */
    fun subjectLevel(instanceId: String, now: Long = System.currentTimeMillis()): QuotaLevel {
        val sid = instanceSubjects[instanceId] ?: return QuotaLevel.normal
        return QuotaLevelRules.level(poolsOf(sid).map { poolStatus(it, now) to it })
    }

    /** The pool that best represents the instance's account in one short line. */
    fun headline(instanceId: String, now: Long = System.currentTimeMillis()): Pair<QuotaPool, PoolStatus>? {
        val sid = instanceSubjects[instanceId] ?: return null
        return QuotaHeadline.pick(poolsOf(sid), now, QuotaStore.freshnessPolicy, QuotaStore.thresholds)
    }

    /**
     * [T-quota-relay-probe] What the last probe of this relay found, for the
     * details: "Sub2API · /v1/usage", or why nothing was found. null for
     * non-relays and relays not probed yet.
     */
    data class RelayDetection(val text: String, val found: Boolean, val at: Long)

    fun relayDetection(d: QuotaInstanceDescriptor): RelayDetection? {
        if (QuotaVendorDetector.vendor(d) != QuotaVendor.customRelay) return null
        val rec = capabilities[d.capabilityKey] ?: return null
        return when (rec.status) {
            QuotaCapabilityRecord.Status.supported -> {
                val kind = RelayKind.fromAdapterId(rec.adapterId) ?: return null
                RelayDetection("${kind.displayName} · ${kind.path}", true, rec.probedAt)
            }
            QuotaCapabilityRecord.Status.failed, QuotaCapabilityRecord.Status.unsupported ->
                RelayDetection(rec.detail.orEmpty(), false, rec.probedAt)
        }
    }

    fun display(d: QuotaInstanceDescriptor): QuotaInstanceDisplay {
        val cap = QuotaAdapterRegistry.adapter(d).capability(d)
        if (!cap.active) {
            return QuotaInstanceDisplay.Unsupported(
                cap.unsupportedReason ?: QuotaSubject.UnsupportedReason.providerTypeUnsupported,
            )
        }
        if (!d.isEnabled) return QuotaInstanceDisplay.Hidden
        if (instanceNotes[d.instanceId] != null) return QuotaInstanceDisplay.Hidden
        val sid = instanceSubjects[d.instanceId]
        val rec = capabilities[d.capabilityKey]
        if (rec?.status == QuotaCapabilityRecord.Status.failed && (sid == null || poolsOf(sid).isEmpty())) {
            return QuotaInstanceDisplay.Unsupported(QuotaSubject.UnsupportedReason.capabilityProbeFailed)
        }
        if (sid == null) return QuotaInstanceDisplay.Loading
        val subject = subjects[sid] ?: return QuotaInstanceDisplay.Loading
        if (poolsOf(sid).isNotEmpty()) return QuotaInstanceDisplay.Pools(sid)
        if (isRefreshing(d.instanceId) || subject.lastRefresh == null) return QuotaInstanceDisplay.Loading
        subject.lastRefresh.error?.let { return QuotaInstanceDisplay.Failed(it) }
        return QuotaInstanceDisplay.Empty(sid)
    }

    companion object {
        fun kindOrder(k: QuotaPool.Kind): Int = when (k) {
            QuotaPool.Kind.prepaidBalance -> 0
            QuotaPool.Kind.spendCap -> 1
            QuotaPool.Kind.usageWindow -> 2
            QuotaPool.Kind.allowance -> 3
            QuotaPool.Kind.rateLimit -> 4
        }
    }
}

object QuotaStore {
    val freshnessPolicy = QuotaFreshnessPolicy()
    val thresholds = QuotaThresholds()

    private const val FOREGROUND_TICK_MS = 60_000L
    private const val POST_FOREGROUND_SETTLE_MS = 3_000L

    /** Credential change: re-resolve instances seen within this window. */
    private const val RECENT_USE_WINDOW_MS = 30 * 60_000L

    private val _state = MutableStateFlow(QuotaState())
    val state: StateFlow<QuotaState> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val singleFlight = QuotaSingleFlight<QuotaSubjectID, QuotaRefreshResult>(scope)

    private var appContext: Context? = null
    private var repository: ProviderRepository? = null
    private val lock = Any()
    private val lastViewed = HashMap<String, Long>()

    /** [T-quota-attention-refresh] Instance id -> on-screen views showing its quota right now. */
    private val watchCounts = HashMap<String, Int>()

    @Volatile private var loaded = false
    @Volatile private var started = false
    private var persistJob: Job? = null
    private var foregroundJob: Job? = null

    // MARK: - Lifecycle

    /** Called once at launch. Refreshes run only while the app is in the foreground. */
    fun start(context: Context, repository: ProviderRepository) {
        synchronized(lock) {
            if (started) return
            started = true
            appContext = context.applicationContext
            this.repository = repository
        }
        scope.launch(Dispatchers.Main) {
            ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) = becameActive()
                override fun onStop(owner: LifecycleOwner) = resignedActive()
            })
        }
        // The cache file is read off the main thread (start runs in
        // Application.onCreate); every mutation path calls ensureLoaded first.
        scope.launch(Dispatchers.IO) {
            ensureLoaded()
            AppLogger.info(TAG, "[Quota] started - subjects=${_state.value.subjects.size} pools=${_state.value.pools.size}")
            repository.awaitConfigLoaded()
            pruneDeletedInstances()
        }
    }

    private fun becameActive() {
        foregroundJob?.cancel()
        foregroundJob = scope.launch {
            delay(POST_FOREGROUND_SETTLE_MS)
            refreshDue("didBecomeActive")
            while (isActive) {
                delay(FOREGROUND_TICK_MS)
                refreshDue("tick")
            }
        }
    }

    private fun resignedActive() {
        foregroundJob?.cancel()
        foregroundJob = null
        // onStop arrives on the main thread: write the file off it.
        scope.launch(Dispatchers.IO) { flushPersist() }
    }

    /**
     * A key was added, changed or removed: forget "no credential" notes so the
     * section can reappear, and re-resolve instances the user looked at
     * recently (a new key is a new subject, so its gate is open).
     */
    fun credentialsChanged() {
        if (!started) return
        _state.update { it.copy(instanceNotes = emptyMap()) }
        val now = System.currentTimeMillis()
        val recent = synchronized(lock) { lastViewed.filter { now - it.value < RECENT_USE_WINDOW_MS }.keys.toList() }
        if (recent.isEmpty()) return
        scope.launch {
            for (id in recent) refresh(id, forced = false, reason = "credentialChanged")
        }
    }

    // MARK: - Attention

    fun noteViewed(instanceId: String) {
        synchronized(lock) { lastViewed[instanceId] = System.currentTimeMillis() }
    }

    /**
     * [T-quota-attention-refresh] A view showing this instance's quota came on
     * screen: from now on it refreshes on the watched interval, and right away
     * when due. Balanced by [endWatching].
     */
    fun beginWatching(instanceId: String, reason: String) {
        synchronized(lock) { watchCounts[instanceId] = (watchCounts[instanceId] ?: 0) + 1 }
        refreshIfDue(instanceId, reason)
    }

    fun endWatching(instanceId: String) {
        synchronized(lock) {
            val n = watchCounts[instanceId] ?: return
            if (n <= 1) watchCounts.remove(instanceId) else watchCounts[instanceId] = n - 1
        }
        noteViewed(instanceId)
    }

    fun isWatched(instanceId: String): Boolean = synchronized(lock) { watchCounts.containsKey(instanceId) }

    /**
     * Mark the instance as in use and refresh only when its subject is due. A
     * subject known to be within its gate is skipped without a credential read,
     * so showing a quota costs nothing when the data is current.
     */
    fun refreshIfDue(instanceId: String, reason: String) {
        if (!started) return
        // No ensureLoaded() here: this runs on the main thread from a
        // composition effect. Before the cache is loaded the subject is
        // unknown, so the launched refresh (which loads it) applies the gate.
        noteViewed(instanceId)
        val instance = instance(instanceId) ?: return
        if (!instance.isEnabled) return
        val d = descriptor(instance)
        if (!QuotaAdapterRegistry.adapter(d).capability(d).active) return
        val st = _state.value
        val sid = st.instanceSubjects[instanceId]
        if (sid != null && !QuotaRefreshGate.decide(
                st.subjects[sid]?.lastRefresh, System.currentTimeMillis(), forced = false, policy = policy(sid.vendor),
            ).isAllowed
        ) return
        scope.launch { refresh(instanceId, forced = false, reason = reason) }
    }

    // MARK: - Refresh

    /**
     * Refresh the instance's subject. `forced` = the user's refresh / re-check
     * button: bypasses the 10-minute cache and the daily capability limit,
     * never Retry-After, the in-flight lock, or the 30 s debounce.
     * `attended == false` (the scheduler, for a provider not on screen) adds
     * the unattended hour to the gate.
     */
    suspend fun refresh(instanceId: String, forced: Boolean, reason: String, attended: Boolean = true): QuotaRefreshResult =
        // Callers include UI coroutines on the main thread (the refresh
        // buttons); the credential read (EncryptedSharedPreferences), the
        // cache load and response parsing must not run there.
        withContext(Dispatchers.Default) { refreshOffMain(instanceId, forced, reason, attended) }

    private suspend fun refreshOffMain(instanceId: String, forced: Boolean, reason: String, attended: Boolean): QuotaRefreshResult {
        ensureLoaded()
        // Before the provider config has loaded every instance looks deleted;
        // detaching then would drop a live instance's cached quota.
        if (repository?.configLoaded?.value != true) {
            return QuotaRefreshResult.NotConfigured(QuotaSubject.NotConfiguredReason.instanceDisabled)
        }
        val instance = instance(instanceId) ?: run {
            detach(instanceId)
            return QuotaRefreshResult.NotConfigured(QuotaSubject.NotConfiguredReason.instanceDisabled)
        }
        val d = descriptor(instance)
        val adapter = QuotaAdapterRegistry.adapter(d)
        if (!adapter.capability(d).active) return QuotaRefreshResult.Unsupported
        if (!instance.isEnabled) return note(instanceId, QuotaSubject.NotConfiguredReason.instanceDisabled)

        // [T-quota-claude-subscription] An OAuth subscription is keyed by its
        // instance: the access token rotates on every refresh, so hashing it
        // would move the data to a new subject each time. Its token is only
        // fetched (possibly refreshed) once the gates below allow a request.
        val subjectId: QuotaSubjectID
        val credentialSource: suspend () -> String?
        val ctx = appContext
        when {
            adapter.vendor == QuotaVendor.claudeSubscription && ctx != null -> {
                subjectId = subscriptionSubject(adapter.vendor, instanceId)
                attach(instanceId, subjectId, "OAuth")
                credentialSource = {
                    runCatching { com.openminis.app.auth.ClaudeOAuthManager(ctx, instanceId).validAccessToken() }.getOrNull()
                }
            }
            adapter.vendor == QuotaVendor.codexSubscription && ctx != null -> {
                subjectId = subscriptionSubject(adapter.vendor, instanceId)
                attach(instanceId, subjectId, "OAuth")
                credentialSource = {
                    runCatching { com.openminis.app.auth.OpenAIOAuthManager(ctx, instanceId).validAccessToken() }.getOrNull()
                }
            }
            adapter.vendor == QuotaVendor.kimiCode && d.isOAuth && ctx != null -> {
                // [ISS-031] Same token the Kimi Code chat requests use
                // (ProviderFactory, ProviderType.kimiCode): a pasted bearer
                // token first, else the device-flow token (refreshed when due).
                // Keyed by instance: the device-flow token rotates. Never logged.
                subjectId = subscriptionSubject(adapter.vendor, instanceId)
                attach(instanceId, subjectId, "OAuth")
                credentialSource = {
                    OAuthManager.forInstance(ctx, instance)?.loadManualBearerToken()?.trim()?.takeIf { it.isNotEmpty() }
                        ?: runCatching { com.openminis.app.auth.KimiOAuthManager(ctx, instanceId).validAccessToken() }.getOrNull()
                }
            }
            else -> {
                val credential = credential(instance)
                    ?: return note(instanceId, QuotaSubject.NotConfiguredReason.missingCredential)
                subjectId = QuotaSubjectID(adapter.vendor, QuotaAccountKey.derive(credential))
                attach(instanceId, subjectId, QuotaAccountKey.mask(credential))
                credentialSource = { credential }
            }
        }
        clearNote(instanceId)
        val policy = policy(subjectId.vendor)

        val now = System.currentTimeMillis()
        val capKey = d.capabilityKey
        val rec = _state.value.capabilities[capKey]
        if (rec != null && rec.status == QuotaCapabilityRecord.Status.failed &&
            !QuotaCapabilityGate.shouldProbe(rec, now, userRequested = forced, policy = policy)
        ) {
            AppLogger.info(TAG, "[Quota] skip $subjectId - capability $capKey failed at ${rec.probedAt} (${rec.detail ?: "-"}), next probe after 24h or a user re-check")
            return QuotaRefreshResult.CapabilityFailed
        }
        if (singleFlight.isRunning(subjectId)) {
            AppLogger.info(TAG, "[Quota] join in-flight fetch $subjectId reason=$reason")
        } else {
            val decision = QuotaRefreshGate.decide(
                _state.value.subjects[subjectId]?.lastRefresh, now, forced, policy, attended,
            )
            if (!decision.isAllowed) return QuotaRefreshResult.Blocked(decision)
        }
        return singleFlight.run(subjectId) {
            val token = credentialSource()
            if (token == null) {
                AppLogger.warning(TAG, "[Quota] $subjectId: no usable credential (OAuth signed out or refresh failed)")
                note(instanceId, QuotaSubject.NotConfiguredReason.missingCredential)
            } else {
                performFetch(adapter, d, subjectId, token, forced, reason)
            }
        }
    }

    private suspend fun performFetch(
        adapter: ProviderQuotaAdapter,
        d: QuotaInstanceDescriptor,
        subjectId: QuotaSubjectID,
        credential: String,
        forced: Boolean,
        reason: String,
    ): QuotaRefreshResult {
        val instanceIds = _state.value.subjects[subjectId]?.instanceIds ?: setOf(d.instanceId)
        _state.update { it.copy(refreshingInstanceIds = it.refreshingInstanceIds + instanceIds) }
        try {
            val started = System.currentTimeMillis()
            AppLogger.info(TAG, "[Quota] 🔄 fetch ${adapter.adapterId} subject=$subjectId forced=$forced reason=$reason")
            // [T-quota-relay-probe] Reuse the type found by a probe less than a
            // day old; otherwise (none yet, stale, or last probe failed) probe.
            val relayHint = if (adapter.vendor == QuotaVendor.customRelay) {
                _state.value.capabilities[d.capabilityKey]?.takeIf {
                    it.status == QuotaCapabilityRecord.Status.supported &&
                        !QuotaCapabilityGate.shouldProbe(it, started, userRequested = false, policy = policy(QuotaVendor.customRelay))
                }?.let { RelayKind.fromAdapterId(it.adapterId) }
            } else {
                null
            }
            // [ISS-031] DEV_TOOLS builds only (BuildConfig.DEV_TOOLS is a
            // compile-time false in release, so this branch is not in a
            // release APK): point ONE instance's query at a fake server.
            val endpointOverride = if (com.openminis.app.BuildConfig.DEV_TOOLS) debugEndpointOverride(d.instanceId) else null
            if (endpointOverride != null) {
                AppLogger.warning(TAG, "[Quota] ⚠️ DEBUG endpoint override for ${adapter.adapterId} instance=${d.instanceId} → $endpointOverride")
            }
            val ctx = QuotaFetchContext(
                d, subjectId, credential, QuotaOkHttpTransport(com.openminis.app.network.NetworkRuntime.providerTag(d.instanceId)), started, extraHeaders(adapter.vendor, d), relayHint,
                endpointOverride = endpointOverride,
            )
            val outcome = try {
                adapter.fetch(ctx)
            } catch (e: QuotaFetchError) {
                return recordFailure(e, adapter.adapterId, subjectId, d.capabilityKey, forced)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                val fe = QuotaFetchError(QuotaError.Kind.network, e.javaClass.simpleName)
                return recordFailure(fe, adapter.adapterId, subjectId, d.capabilityKey, forced)
            }

            val now = System.currentTimeMillis()
            val policy = policy(subjectId.vendor)
            _state.update { st ->
                val pools = st.pools.toMutableMap()
                val bindings = st.bindings.toMutableList()
                QuotaMerge.ingest(outcome.snapshot, outcome.isComplete, pools, bindings)
                val subject = (st.subjects[subjectId] ?: newSubject(subjectId)).let { s ->
                    s.copy(
                        lastRefresh = QuotaRefreshGate.recordSuccess(s.lastRefresh, now, forced, policy),
                        state = QuotaSubject.State.Available,
                        plan = outcome.snapshot.plan ?: s.plan,
                    )
                }
                val snap = outcome.snapshot
                val summaryKey = "$subjectId|${snap.provenance.source.name}"
                val capKey = d.capabilityKey
                val rec = st.capabilities[capKey]
                val relayKind = outcome.relayKind
                val probedRelay = relayKind != null && ctx.relayKindHint == null
                val capabilities = if (rec?.status != QuotaCapabilityRecord.Status.supported || probedRelay ||
                    QuotaCapabilityGate.shouldProbe(rec, now, userRequested = false, policy = policy)
                ) {
                    st.capabilities + (
                        capKey to QuotaCapabilityRecord(
                            key = capKey, adapterId = relayKind?.adapterId ?: adapter.adapterId,
                            status = QuotaCapabilityRecord.Status.supported,
                            source = snap.provenance.source, confidence = snap.provenance.confidence,
                            probedAt = now,
                            detail = relayKind?.let { "${it.displayName} · ${it.path}" },
                        )
                        )
                } else {
                    st.capabilities
                }
                st.copy(
                    pools = pools,
                    bindings = bindings,
                    subjects = st.subjects + (subjectId to subject),
                    summaries = st.summaries + (
                        summaryKey to QuotaSnapshotSummary(
                            subjectId, snap.provenance.source, adapter.adapterId, snap.capturedAt,
                            snap.pools.map { it.id },
                        )
                        ),
                    capabilities = capabilities,
                )
            }
            outcome.relayKind?.takeIf { ctx.relayKindHint == null }?.let { kind ->
                AppLogger.info(TAG, "[Quota] 🔎 relay ${d.capabilityKey} detected as ${kind.displayName} (${kind.path})")
            }
            for (e in outcome.partialErrors) {
                AppLogger.warning(TAG, "[Quota] partial failure $subjectId: ${e.kind.name} ${e.detail}")
            }
            // [ISS-031] Field names + types only (no values), once per adapter
            // per launch: lets an installed build confirm an unverified shape.
            outcome.responseShape?.let { shape ->
                if (loggedResponseShapes.add(adapter.adapterId)) {
                    AppLogger.info(TAG, "[Quota] 🧩 ${adapter.adapterId} response shape: $shape")
                }
            }
            AppLogger.info(
                TAG,
                "[Quota] ✅ $subjectId pools=${outcome.snapshot.pools.size} complete=${outcome.isComplete} " +
                    "in ${"%.2f".format((now - started) / 1000.0)}s",
            )
            schedulePersist()
            return QuotaRefreshResult.Refreshed
        } finally {
            _state.update { it.copy(refreshingInstanceIds = it.refreshingInstanceIds - instanceIds) }
        }
    }

    /** [ISS-031] Adapters whose response shape was already logged this launch. */
    private val loggedResponseShapes: MutableSet<String> = java.util.Collections.synchronizedSet(HashSet())

    /**
     * [ISS-031] DEV_TOOLS builds only: `<cacheDir>/quota-debug-endpoints.json`,
     * `{"<instanceId>": "<http(s) URL>"}`, points ONE instance's query at a fake
     * server (on-device verification of the Kimi Code `/usages` parser). Keyed
     * by instance, never by vendor, so a real account's token on the same
     * device never goes to the fake server. No file = no override. Callers
     * gate on BuildConfig.DEV_TOOLS.
     */
    private fun debugEndpointOverride(instanceId: String): String? {
        val dir = appContext?.cacheDir ?: return null
        val file = File(dir, "quota-debug-endpoints.json")
        if (!file.isFile) return null
        val map = runCatching { QuotaHttp.json.parseToJsonElement(file.readText()) as? kotlinx.serialization.json.JsonObject }
            .getOrNull() ?: return null
        val raw = (map[instanceId] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
        val uri = runCatching { java.net.URI(raw) }.getOrNull() ?: return null
        if (uri.scheme != "http" && uri.scheme != "https") return null
        if (uri.host.isNullOrEmpty()) return null
        return raw
    }

    /** Identity headers an undocumented usage endpoint expects. Never logged. */
    private fun extraHeaders(vendor: QuotaVendor, d: QuotaInstanceDescriptor): Map<String, String> = when (vendor) {
        // The usage endpoint answers the official CLI; send its fingerprint.
        QuotaVendor.claudeSubscription -> com.openminis.app.auth.ClaudeCliMimicryHeaders.ALL.toMap()
        // Same identity the Codex chat requests send (OpenAIProvider).
        QuotaVendor.codexSubscription -> buildMap {
            val version = com.openminis.app.provider.openai.OpenAIProvider.CODEX_CLIENT_VERSION
            put("User-Agent", "codex_cli_rs/$version (Android; arm64)")
            put("Originator", "codex_cli_rs")
            appContext?.let { c ->
                com.openminis.app.auth.OpenAIOAuthManager(c, d.instanceId).accountId?.let { put("ChatGPT-Account-Id", it) }
            }
        }
        else -> emptyMap()
    }

    private fun recordFailure(
        fe: QuotaFetchError,
        adapterId: String,
        subjectId: QuotaSubjectID,
        capKey: QuotaCapabilityKey,
        forced: Boolean,
    ): QuotaRefreshResult {
        val now = System.currentTimeMillis()
        val err = fe.persisted(now)
        var record: QuotaRefreshRecord? = null
        _state.update { st ->
            val s = st.subjects[subjectId] ?: newSubject(subjectId)
            val lastRefresh = QuotaRefreshGate.recordFailure(s.lastRefresh, err, now, forced, policy(subjectId.vendor))
            record = lastRefresh
            val subject = s.copy(
                lastRefresh = lastRefresh,
                state = if (st.poolsOf(subjectId).isEmpty()) QuotaSubject.State.Failed else QuotaSubject.State.Available,
            )
            // An endpoint that does not exist or answers something unreadable is
            // a capability failure for this provider type + base URL: stop until
            // the next daily probe or a user re-check. Auth failures are the
            // key's problem, not the endpoint's.
            val capabilities = if (QuotaCapabilityRules.failureDisablesEndpoint(fe.kind, subjectId.vendor)) {
                st.capabilities + (
                    capKey to QuotaCapabilityRecord(
                        key = capKey, adapterId = adapterId, status = QuotaCapabilityRecord.Status.failed,
                        probedAt = now,
                        detail = fe.httpStatus?.let { "HTTP $it · ${fe.detail}" } ?: fe.detail,
                    )
                    )
            } else {
                st.capabilities
            }
            st.copy(subjects = st.subjects + (subjectId to subject), capabilities = capabilities)
        }
        AppLogger.warning(
            TAG,
            "[Quota] ❌ $subjectId ${fe.kind.name} ${fe.detail} next=${record?.nextAllowedAt ?: "-"} " +
                "failures=${record?.consecutiveFailures ?: 0}",
        )
        schedulePersist()
        return QuotaRefreshResult.Failed(err)
    }

    private fun note(instanceId: String, reason: QuotaSubject.NotConfiguredReason): QuotaRefreshResult {
        _state.update { st ->
            if (st.instanceNotes[instanceId] == reason) st else st.copy(instanceNotes = st.instanceNotes + (instanceId to reason))
        }
        return QuotaRefreshResult.NotConfigured(reason)
    }

    private fun clearNote(instanceId: String) {
        _state.update { st ->
            if (instanceId in st.instanceNotes) st.copy(instanceNotes = st.instanceNotes - instanceId) else st
        }
    }

    private fun newSubject(id: QuotaSubjectID) = QuotaSubject(id = id)

    /**
     * Point the instance at [subjectId]; when its key changed, leave the old
     * subject and drop it (with its pools) once no instance uses it.
     */
    private fun attach(instanceId: String, subjectId: QuotaSubjectID, maskedKey: String) {
        var changed = false
        _state.update { st0 ->
            var st = st0
            val old = st.instanceSubjects[instanceId]
            if (old != null && old != subjectId) st = removeInstance(st, instanceId, old)
            val subject = st.subjects[subjectId] ?: newSubject(subjectId)
            val updated = if (instanceId !in subject.instanceIds || subject.accountLabel != maskedKey) {
                subject.copy(instanceIds = subject.instanceIds + instanceId, accountLabel = maskedKey)
            } else {
                subject
            }
            val next = st.copy(
                subjects = st.subjects + (subjectId to updated),
                instanceSubjects = st.instanceSubjects + (instanceId to subjectId),
            )
            changed = next != st0
            next
        }
        if (changed) schedulePersist()
    }

    private fun detach(instanceId: String) {
        var changed = false
        _state.update { st ->
            val old = st.instanceSubjects[instanceId] ?: return@update st
            changed = true
            removeInstance(st.copy(instanceSubjects = st.instanceSubjects - instanceId), instanceId, old)
        }
        if (changed) schedulePersist()
    }

    private fun removeInstance(st: QuotaState, instanceId: String, sid: QuotaSubjectID): QuotaState {
        val s = st.subjects[sid] ?: return st
        val remaining = s.instanceIds - instanceId
        if (remaining.isNotEmpty()) return st.copy(subjects = st.subjects + (sid to s.copy(instanceIds = remaining)))
        val gone = st.pools.values.filter { it.subjectId == sid }.map { it.id }.toSet()
        AppLogger.info(TAG, "[Quota] dropped subject $sid (no instance left)")
        return st.copy(
            subjects = st.subjects - sid,
            pools = st.pools - gone,
            bindings = st.bindings.filterNot { it.poolKey in gone },
            summaries = st.summaries.filterValues { it.subjectId != sid },
        )
    }

    /** Instances deleted since the cache was written leave nothing behind. */
    private fun pruneDeletedInstances() {
        val repo = repository ?: return
        val live = repo.config.value.instances.map { it.id }.toSet()
        _state.value.instanceSubjects.keys.filter { it !in live }.forEach(::detach)
    }

    // MARK: - Passive observation (response headers)

    fun subscriptionSubject(vendor: QuotaVendor, instanceId: String) = QuotaSubjectID(vendor, "instance:$instanceId")

    /** Diagnostic: instances whose first observed header set was logged verbatim. */
    private val loggedObservationInstances = HashSet<String>()

    /**
     * A reply from an OAuth Claude instance carried
     * `anthropic-ratelimit-unified-*` headers (or a Codex reply `x-codex-*`):
     * merge them in as the same pools / windows the active query writes, and
     * count them as a refresh so the scheduler does not query again within
     * the 5-minute window.
     */
    fun observeSubscriptionHeaders(instanceId: String, vendor: QuotaVendor, headers: Map<String, String>) {
        if (!started) return
        ensureLoaded()
        val instance = instance(instanceId) ?: return
        if (!instance.isEnabled || QuotaVendorDetector.vendor(descriptor(instance)) != vendor) return
        val firstTime = synchronized(lock) { loggedObservationInstances.add(instanceId) }
        if (firstTime) {
            // Header names and values only (usage fractions, reset times,
            // status); no credential is ever part of these response headers.
            val line = headers.entries.sortedBy { it.key }.joinToString(" ") { "${it.key}=${it.value}" }
            AppLogger.info(TAG, "[Quota][SubscriptionHeaders] first observation vendor=${vendor.name} instance=${instanceId.take(8)} $line")
        }
        val subjectId = subscriptionSubject(vendor, instanceId)
        val now = System.currentTimeMillis()
        val snapshot = when (vendor) {
            QuotaVendor.claudeSubscription -> ClaudeSubscriptionQuotaAdapter.parseHeaders(headers, subjectId, now = now)
            QuotaVendor.codexSubscription -> CodexSubscriptionQuotaAdapter.parseHeaders(headers, subjectId, now = now)
            else -> null
        } ?: return
        attach(instanceId, subjectId, "OAuth")
        val policy = policy(vendor)
        _state.update { st ->
            val pools = st.pools.toMutableMap()
            val bindings = st.bindings.toMutableList()
            QuotaMerge.ingest(snapshot, authoritative = false, pools = pools, bindings = bindings)
            val s = st.subjects[subjectId] ?: newSubject(subjectId)
            val subject = s.copy(
                lastRefresh = QuotaRefreshGate.recordObservation(s.lastRefresh, now, policy),
                state = QuotaSubject.State.Available,
            )
            st.copy(
                pools = pools,
                bindings = bindings,
                subjects = st.subjects + (subjectId to subject),
                summaries = st.summaries + (
                    "$subjectId|${snapshot.provenance.source.name}" to QuotaSnapshotSummary(
                        subjectId, snapshot.provenance.source, snapshot.provenance.adapterId, now, snapshot.pools.map { it.id },
                    )
                    ),
            )
        }
        schedulePersist()
    }

    // MARK: - Scheduling

    /**
     * Refresh every eligible instance whose subject is due: on the watched
     * interval when on screen, else on the unattended hour. Instances sharing
     * a subject collapse into one request. At most two fetches run at once.
     */
    private suspend fun refreshDue(reason: String) {
        val repo = repository ?: return
        val now = System.currentTimeMillis()
        val st = _state.value
        val candidates = repo.config.value.instances.filter { instance ->
            if (!instance.isEnabled) return@filter false
            // Known to have no credential: nothing to send until a key is added.
            if (st.instanceNotes[instance.id] != null) return@filter false
            val d = descriptor(instance)
            if (!QuotaAdapterRegistry.adapter(d).capability(d).active) return@filter false
            // Skip without a credential read when the known subject is not due.
            val sid = st.instanceSubjects[instance.id]
            !(
                sid != null && !QuotaRefreshGate.decide(
                    st.subjects[sid]?.lastRefresh, now, forced = false, policy = policy(sid.vendor),
                    attended = isWatched(instance.id),
                ).isAllowed
                )
        }
        if (candidates.isEmpty()) return
        AppLogger.info(TAG, "[Quota] ⏱️ $reason: ${candidates.size} due (watched ${candidates.count { isWatched(it.id) }})")
        for (batch in candidates.chunked(2)) {
            coroutineScope {
                batch.map { instance ->
                    val attended = isWatched(instance.id)
                    async { refresh(instance.id, forced = false, reason = reason, attended = attended) }
                }.awaitAll()
            }
        }
    }

    // MARK: - Persistence

    private fun storeFile(): File? = appContext?.let { File(File(it.noBackupFilesDir, "quota"), "store.json") }

    private fun ensureLoaded() {
        if (loaded) return
        synchronized(lock) {
            if (loaded) return
            val file = storeFile() ?: return
            loaded = true
            if (!file.exists()) return
            val text = runCatching { file.readText() }.getOrNull() ?: return
            val decoded = QuotaStoreFile.decode(text)
            if (decoded == null) {
                AppLogger.warning(TAG, "[Quota] store.json unreadable (${text.length} bytes) - starting empty")
                return
            }
            _state.value = QuotaState(
                subjects = decoded.subjects.associateBy { it.id },
                pools = decoded.pools.associateBy { it.id },
                bindings = decoded.bindings,
                capabilities = decoded.capabilities.associateBy { it.key },
                summaries = decoded.summaries.associateBy { "${it.subjectId}|${it.source.name}" },
                instanceSubjects = decoded.instanceSubjects,
            )
        }
    }

    private fun schedulePersist() {
        synchronized(lock) {
            persistJob?.cancel()
            persistJob = scope.launch {
                delay(2_000)
                flushPersist()
            }
        }
    }

    private fun flushPersist() {
        synchronized(lock) {
            persistJob?.cancel()
            persistJob = null
        }
        if (!loaded) return
        val target = storeFile() ?: return
        val st = _state.value
        val file = QuotaStoreFile(
            subjects = st.subjects.values.toList(),
            pools = st.pools.values.toList(),
            bindings = st.bindings,
            capabilities = st.capabilities.values.toList(),
            summaries = st.summaries.values.toList(),
            instanceSubjects = st.instanceSubjects,
        )
        try {
            target.parentFile?.mkdirs()
            val tmp = File(target.parentFile, "store.json.tmp")
            tmp.writeText(QuotaStoreFile.encode(file))
            if (!tmp.renameTo(target)) {
                target.delete()
                tmp.renameTo(target)
            }
        } catch (e: Exception) {
            AppLogger.error(TAG, "[Quota] persist failed: ${e.javaClass.simpleName}")
        }
    }

    // MARK: - Instance -> descriptor / credential

    /** Rate policy per vendor (subscriptions refresh on a 5-minute window). */
    fun policy(vendor: QuotaVendor): QuotaRefreshPolicy = QuotaRefreshPolicy.forVendor(vendor)

    private fun instance(id: String): ProviderInstance? = repository?.config?.value?.instances?.find { it.id == id }

    fun descriptor(instance: ProviderInstance): QuotaInstanceDescriptor = QuotaInstanceDescriptor(
        instanceId = instance.id,
        label = instance.label,
        providerKind = when (instance.providerType) {
            ProviderType.openAI -> QuotaInstanceDescriptor.ProviderKind.openAI
            ProviderType.anthropic -> QuotaInstanceDescriptor.ProviderKind.anthropic
            ProviderType.gemini -> QuotaInstanceDescriptor.ProviderKind.gemini
            ProviderType.antigravity -> QuotaInstanceDescriptor.ProviderKind.antigravity
            ProviderType.openRouter -> QuotaInstanceDescriptor.ProviderKind.openRouter
            ProviderType.openAIResponses -> QuotaInstanceDescriptor.ProviderKind.openAIResponses
            ProviderType.xAI -> QuotaInstanceDescriptor.ProviderKind.xAI
            ProviderType.kimiCode -> QuotaInstanceDescriptor.ProviderKind.kimiCode
            ProviderType.githubCopilot -> QuotaInstanceDescriptor.ProviderKind.githubCopilot
            ProviderType.unsupported -> QuotaInstanceDescriptor.ProviderKind.unsupported
        },
        isOAuth = instance.credentialType == ProviderCredential.oauth,
        isEnabled = instance.isEnabled,
        customBaseURL = instance.customBaseURL?.trim()?.takeIf { it.isNotEmpty() },
        isOpenCodeChannel = isOpenCodeChannel(instance),
    )

    /**
     * [ISS-031] The app's own OpenCode detection (the one that gates the
     * `x-opencode-session` header), limited like iOS's
     * `ProviderInstance.isOpenCodeChannel` to OpenAI-protocol provider types:
     * OpenCode speaks only that protocol, so a relay hostname can never pull,
     * say, an Anthropic or Gemini instance into this vendor.
     */
    fun isOpenCodeChannel(instance: ProviderInstance): Boolean = when (instance.providerType) {
        ProviderType.openAI, ProviderType.openAIResponses, ProviderType.openRouter, ProviderType.xAI,
        ProviderType.kimiCode, ProviderType.githubCopilot ->
            com.openminis.app.provider.OpenCodeSessionHeader.matches(instance.customBaseURL)
        ProviderType.anthropic, ProviderType.gemini, ProviderType.antigravity, ProviderType.unsupported -> false
    }

    /**
     * The bearer credential for an active balance query: the API key, else a
     * manually pasted token.
     */
    private fun credential(instance: ProviderInstance): String? {
        val repo = repository ?: return null
        repo.loadApiKey(instance.id)?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        val ctx = appContext ?: return null
        return OAuthManager.forInstance(ctx, instance)?.loadManualBearerToken()?.trim()?.takeIf { it.isNotEmpty() }
    }
}

/**
 * [T-quota-claude-subscription] Entry point for the providers: called on the
 * network thread for every response of an OAuth Claude / Codex instance.
 * Filters on the calling thread and allocates nothing unless the response
 * carries usage headers; only then hands them to the store.
 */
object QuotaObservationSink {
    fun recordAnthropicResponse(instanceId: String?, headers: okhttp3.Headers) {
        instanceId ?: return
        var found: HashMap<String, String>? = null
        for (i in 0 until headers.size) {
            val key = headers.name(i).lowercase()
            if (!key.startsWith("anthropic-ratelimit-")) continue
            (found ?: HashMap<String, String>().also { found = it })[key] = headers.value(i)
        }
        val h = found ?: return
        if (h.keys.none { it.startsWith(ClaudeSubscriptionQuotaAdapter.HEADER_PREFIX) }) return
        observe(instanceId, QuotaVendor.claudeSubscription, h)
    }

    /** [T-quota-codex-subscription] Codex replies (OpenAIProvider streaming path, errors included). */
    fun recordCodexResponse(instanceId: String?, headers: okhttp3.Headers) {
        instanceId ?: return
        var found: HashMap<String, String>? = null
        for (i in 0 until headers.size) {
            val key = headers.name(i).lowercase()
            if (!key.startsWith(CodexSubscriptionQuotaAdapter.HEADER_PREFIX)) continue
            (found ?: HashMap<String, String>().also { found = it })[key] = headers.value(i)
        }
        val h = found ?: return
        if (h.keys.none { it.endsWith("-used-percent") }) return
        observe(instanceId, QuotaVendor.codexSubscription, h)
    }

    /**
     * [T-quota-subscription-crossreview] The sink runs inside the chat
     * request, before the reply body is read (iOS hands off to the main actor
     * instead, so its bookkeeping can never touch the request). Quota
     * bookkeeping must never fail a chat: anything it throws is logged and
     * dropped here.
     */
    private fun observe(instanceId: String, vendor: QuotaVendor, headers: Map<String, String>) {
        try {
            QuotaStore.observeSubscriptionHeaders(instanceId, vendor, headers)
        } catch (e: Exception) {
            AppLogger.warning(TAG, "[Quota] header observation dropped vendor=${vendor.name}: ${e.javaClass.simpleName} ${e.message}")
        }
    }
}
