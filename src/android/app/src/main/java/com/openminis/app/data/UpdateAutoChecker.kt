package com.openminis.app.data

import android.content.Context
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.openminis.app.BuildConfig
import com.openminis.app.logging.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * [T-update-channels] Automatic update checks, without WorkManager: each time
 * the whole app comes to the foreground (ProcessLifecycleOwner ON_START), ask
 * [UpdatePolicy.autoDecision] whether a request is due - at most one per 24 h
 * on stable, 6 h on beta, 15 min after a failure, never while one runs, never
 * with the switch off, never on a build without automatic checks (store).
 * Every decision is logged as `[UpdateCheck] auto …`, so a log shows that
 * repeated foregrounds made one request.
 */
object UpdateAutoChecker {

    private const val TAG = "UpdateAuto"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var installed = false

    /** From MinisApp.onCreate (main thread). Idempotent. */
    fun install(context: Context) {
        if (installed) return
        installed = true
        val app = context.applicationContext
        UpdateStateStore.init(app)
        if (!BuildConfig.AUTO_UPDATE_CHECK) {
            AppLogger.info(TAG, "[UpdateCheck] auto checks off for this build (store)")
            return
        }
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) {
                    maybeCheck(app, "foreground")
                }
            },
        )
    }

    /**
     * Run an automatic check if the throttle allows it. Also called when the
     * switch is turned on (checks only if the cache has expired) and after a
     * channel switch (the cache was just cleared, so it checks at once).
     */
    fun maybeCheck(context: Context, reason: String) {
        val app = context.applicationContext
        UpdateStateStore.init(app)
        val decision = UpdatePolicy.autoDecision(
            now = System.currentTimeMillis(),
            state = UpdateStateStore.throttleState(app),
            channel = UpdateStateStore.channel.value,
            localCode = BuildConfig.VERSION_CODE,
            buildAllowsAuto = BuildConfig.AUTO_UPDATE_CHECK,
            userEnabled = UpdateStateStore.autoEnabled.value,
            inFlight = UpdateChecker.isChecking,
        )
        AppLogger.info(TAG, "[UpdateCheck] auto reason=$reason decision=$decision channel=${UpdateStateStore.channel.value.id}")
        if (decision != UpdatePolicy.AutoDecision.CHECK && decision != UpdatePolicy.AutoDecision.INVALIDATE_AND_CHECK) return
        // INVALIDATE: UpdateChecker.check -> beginAttempt clears the cache
        // bound to the old channel / build before it requests.
        scope.launch { UpdateChecker.check(app, reason = "auto-$reason") }
    }
}
