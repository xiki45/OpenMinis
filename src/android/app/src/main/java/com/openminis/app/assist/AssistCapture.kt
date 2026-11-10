package com.openminis.app.assist

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import com.openminis.app.accessibility.AccessibilityRecoveryManager
import com.openminis.app.accessibility.MinisAccessibilityService
import com.openminis.app.logging.AppLogger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import java.io.File

/**
 * [T-assist-screenshot] 协调"助理唤起时附带当前屏幕"的截图能力。
 *
 * 两条路线：
 *  - 标准框架路线（默认助理，角色解析的 ROM）：系统把屏幕位图经
 *    VoiceInteractionSession.onHandleScreenshot 推进来，[saveFrameworkShot] 落盘。
 *  - HyperOS 路线（hook/startService 改道拉起，不走助理渠道）：入口在窗口
 *    上屏前调 [requestIfEnabled]，用无障碍服务截一帧用户正在看的屏幕，
 *    结果经 [awaitPendingShot] 交给 ChatScreen 注入。
 *
 * 任何失败（服务未连、安全界面、超时）都静默降级为无图会话。
 */
object AssistCapture {
    private const val TAG = "AssistCapture"
    private const val PREF_NAME = "assist_prefs"
    private const val KEY_ATTACH_SCREEN = "assist_attach_screen"
    private const val CAPTURE_TIMEOUT_MS = 1_500L
    private const val AWAIT_TIMEOUT_MS = 3_000L
    /**
     * [T-assist-capture-settle] 等待无障碍服务"连上"的总预算。
     *
     * 以前是 1200ms 且只判 `getInstance() != null` —— 但 `_instance` 是在
     * `onCreate()` 里赋的，早于 `onServiceConnected()`：实例存在 ≠ 可截图。
     * 实测在刚连上的同一秒调 `takeScreenshot` 会稳定失败
     * `TAKE_SCREENSHOT_FAILED error code=1`。窗口上屏只需 200-400ms，
     * 所以这个预算必须小：拿不到就放弃，绝不能在窗口起来之后补拍一张错的。
     */
    private const val SERVICE_WAIT_MS = 450L

    /** 连接建立后还要等这么久才允许 takeScreenshot（避开 error code=1）。 */
    private const val CONNECT_SETTLE_MS = 150L

    /** 请求到落盘超过这个时长，说明窗口早已上屏，这张图不是"唤起时的屏幕"，丢掉。 */
    private const val MAX_SHOT_AGE_MS = 800L
    private const val SHOT_DIR = "assist_shots"
    private const val MAX_EDGE = 1600
    private const val KEEP_SHOTS = 3
    private const val REQUEST_DEDUPE_MS = 2_000L

    /**
     * 配套模块 [minis-assist-hook](https://github.com/xiki45/minis-assist-hook) v2.1+
     * 拉起时附带的触发源判定：长按电源键（快速提问）= false，双击小白条等手势 = true。
     * 无该 extra（旧模块 / 标准框架路线 / startService 路线）时默认截图。
     */
    const val EXTRA_ATTACH_SCREEN = "com.openminis.hook.attach_screen"

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_ATTACH_SCREEN, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ATTACH_SCREEN, enabled).apply()
    }

    @Volatile private var pending: CompletableDeferred<File?>? = null
    @Volatile private var lastRequestAt = 0L

    /** HyperOS 路线：尽早发射一次无障碍截屏；返回是否真的发射了。
     *  [trigger] 为唤起 intent：配套模块可经 [EXTRA_ATTACH_SCREEN]=false
     *  声明本次触发源不需要截图（如长按电源键）。无该 extra 时默认截图。 */
    fun requestIfEnabled(context: Context, trigger: android.content.Intent? = null): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        if (!isEnabled(context)) return false
        // [T-assist-screenshot] 触发源判定：配套模块已声明本次不截图则直接跳过。
        if (trigger != null && trigger.hasExtra(EXTRA_ATTACH_SCREEN)
            && !trigger.getBooleanExtra(EXTRA_ATTACH_SCREEN, true)
        ) {
            AppLogger.info(TAG, "trigger source opted out of screenshot (EXTRA_ATTACH_SCREEN=false)")
            return false
        }
        // 用单调时钟：墙上时钟回跳会让去重窗口失效（review 的 minor 项一起修掉）。
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastRequestAt < REQUEST_DEDUPE_MS) return false  // 冷/热启动双入口与手势双击去重
        // [T-assist-screenshot] 判定放宽为「settings 认账 或 服务实例在手」：
        // HyperOS 上 root 重写 enabled_accessibility_services 后，服务可能仍以
        // 旧绑定存活（可正常 takeScreenshot），但 settings 读取与真实授权状态
        // 分叉（实测：settings 查不到、截图却一直可用；反向亦然）。以实例为准
        // 兜底，避免误杀可用路径；两者皆无才真正跳过。
        val inSettings = isA11yServiceEnabled(context)
        val hasInstance = MinisAccessibilityService.getInstance() != null
        if (!inSettings && !hasInstance) {
            AppLogger.warning(TAG, "a11y service not enabled and no live instance; skip assist screenshot")
            return false
        }
        if (!inSettings && hasInstance) {
            AppLogger.warning(TAG, "a11y missing in settings but live instance exists; proceeding")
        }
        lastRequestAt = now
        val appContext = context.applicationContext
        val deferred = CompletableDeferred<File?>()
        pending = deferred
        Thread({
            var out: File? = null
            try {
                val requestedAt = now
                val deadline = requestedAt + SERVICE_WAIT_MS
                // 等"真正连上"，而不是只等实例出现：_instance 在 onCreate() 就有了，
                // 那时 takeScreenshot 必然失败（error code=1）。
                var svc = MinisAccessibilityService.getInstance()
                while ((svc == null || !svc.isConnected())
                    && android.os.SystemClock.elapsedRealtime() < deadline
                ) {
                    Thread.sleep(50)
                    svc = MinisAccessibilityService.getInstance()
                }
                if (svc == null || !svc.isConnected()) {
                    AppLogger.warning(
                        TAG,
                        "a11y not connected within ${SERVICE_WAIT_MS}ms; skip (no post-window frame)",
                    )
                } else if (!awaitSettled(svc, deadline)) {
                    AppLogger.warning(TAG, "a11y connected too late to be the invocation screen; skip")
                } else if (MinisAccessibilityService.getInstance() !== svc) {
                    // 等待期间服务被销毁/重建（保活重绑、系统回收）：这一帧不可信
                    AppLogger.warning(TAG, "a11y instance replaced mid-capture; skip")
                } else {
                    val shot = svc.captureScreenshot(timeoutMs = CAPTURE_TIMEOUT_MS)
                    val bmp = shot.bitmap
                    val age = android.os.SystemClock.elapsedRealtime() - requestedAt
                    if (bmp == null) {
                        AppLogger.warning(TAG, "assist screenshot failed: ${shot.errorCode} ${shot.errorMessage}")
                    } else if (age > MAX_SHOT_AGE_MS) {
                        // 窗口早已上屏：这张图拍到的是 Minis 自己或用户已经切走的画面
                        bmp.recycle()
                        AppLogger.warning(TAG, "late frame (${age}ms > ${MAX_SHOT_AGE_MS}ms); dropped")
                    } else {
                        out = saveBitmap(appContext, bmp)
                        bmp.recycle()
                        if (out == null) AppLogger.warning(TAG, "save assist screenshot failed")
                    }
                }
            } catch (t: Throwable) {
                AppLogger.warning(TAG, "assist capture error: ${t.message}")
            } finally {
                deferred.complete(out)
            }
        }, "assist-capture").start()
        AppLogger.info(TAG, "assist screenshot capture started")
        return true
    }

    /**
     * [T-assist-capture-settle] 等连接稳定 [CONNECT_SETTLE_MS]；若为此刻意等待会超出
     * 总预算 [SERVICE_WAIT_MS]，则直接放弃（宁可没图，也不要一张窗口起来之后的图）。
     */
    private fun awaitSettled(svc: MinisAccessibilityService, deadline: Long): Boolean {
        val stableFor = android.os.SystemClock.elapsedRealtime() - svc.connectedAtElapsedMs()
        if (stableFor >= CONNECT_SETTLE_MS) return true
        val wait = CONNECT_SETTLE_MS - stableFor
        if (android.os.SystemClock.elapsedRealtime() + wait > deadline) return false
        Thread.sleep(wait)
        return true
    }

    /**
     * 系统设置里是否启用了本应用的无障碍服务（与 SystemPermissionsScreen 同一判定口径）。
     *
     * [T-assist-a11y-name-form] 必须同时接受两种写法：框架/系统设置里保存的通常是
     * 缩写形式 `pkg/.Class`（实测本机就是 `com.openminis.app/.accessibility.MinisAccessibilityService`），
     * 而 `Class.name` 拼出来的是全限定形式 `pkg/pkg.Class`。只比全限定形式会让这个判定
     * **永远为 false** —— 每次唤起都打一条 "a11y missing in settings"，且在冷启动
     * （无障碍实例还没连上）时直接跳过截图，哪怕服务其实是启用的。
     * 与 AccessibilityRecoveryManager.isRevokedIn 的匹配规则保持一致。
     */
    private fun isA11yServiceEnabled(context: Context): Boolean {
        return try {
            val enabled = android.provider.Settings.Secure.getString(
                context.contentResolver,
                android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ) ?: return false
            // 复用同一套"全限定 / 缩写"双写法匹配（AccessibilityRecoveryManager 有单测）。
            !AccessibilityRecoveryManager.isRevokedIn(
                enabled,
                context.packageName,
                MinisAccessibilityService::class.java.name,
            )
        } catch (_: Throwable) {
            false
        }
    }

    /** ChatScreen 消费端等待在途捕获（有界），无捕获立刻返回 null；一次性。 */
    suspend fun awaitPendingShot(timeoutMs: Long = AWAIT_TIMEOUT_MS): File? {
        val d = pending ?: return null
        pending = null  // 一次性消费：防止二次合成/相邻会话重复拿到同一张图
        return try {
            withTimeout(timeoutMs) { d.await() }
        } catch (_: Throwable) {
            null
        }
    }

    /** 标准框架路线：落盘 onHandleScreenshot 推送的位图。 */
    fun saveFrameworkShot(context: Context, bitmap: Bitmap): File? =
        saveBitmap(context.applicationContext, bitmap)

    private fun saveBitmap(context: Context, src: Bitmap): File? {
        return try {
            val dir = File(context.cacheDir, SHOT_DIR).apply { mkdirs() }
            // 只保留最近 KEEP_SHOTS 张，避免缓存累积。
            dir.listFiles()?.sortedByDescending { it.lastModified() }
                ?.drop(KEEP_SHOTS)?.forEach { it.delete() }
            // 长边缩到 MAX_EDGE，控制多模态 token / 带宽开销。
            val scale = MAX_EDGE.toFloat() / maxOf(src.width, src.height)
            val bmp = if (scale >= 1f) src
                else Bitmap.createScaledBitmap(
                    src,
                    (src.width * scale).toInt().coerceAtLeast(1),
                    (src.height * scale).toInt().coerceAtLeast(1),
                    true,
                )
            val file = File(dir, "assist-shot-${System.currentTimeMillis()}.jpg")
            file.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 88, it) }
            if (bmp !== src) bmp.recycle()
            file
        } catch (t: Throwable) {
            AppLogger.warning(TAG, "saveBitmap failed: ${t.message}")
            null
        }
    }
}
