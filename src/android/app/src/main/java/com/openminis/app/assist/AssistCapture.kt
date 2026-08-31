package com.openminis.app.assist

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
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
    private const val SERVICE_WAIT_MS = 1_200L
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
        val now = System.currentTimeMillis()
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
                // 重装/冷启动时服务绑定与 Activity onCreate 存在竞态：
                // 设置里已启用但实例还没连上时，短暂轮询等待。
                var svc = MinisAccessibilityService.getInstance()
                var waited = 0
                while (svc == null && waited < SERVICE_WAIT_MS) {
                    Thread.sleep(100)
                    waited += 100
                    svc = MinisAccessibilityService.getInstance()
                }
                if (svc == null) {
                    AppLogger.warning(TAG, "a11y service not connected after ${SERVICE_WAIT_MS}ms; skip")
                } else {
                    val shot = svc.captureScreenshot(timeoutMs = CAPTURE_TIMEOUT_MS)
                    val bmp = shot.bitmap
                    if (bmp != null) {
                        out = saveBitmap(appContext, bmp)
                        bmp.recycle()
                        if (out == null) AppLogger.warning(TAG, "save assist screenshot failed")
                    } else {
                        AppLogger.warning(TAG, "assist screenshot failed: ${shot.errorCode} ${shot.errorMessage}")
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

    /** 系统设置里是否启用了本应用的无障碍服务（与 SystemPermissionsScreen 同一判定口径）。 */
    private fun isA11yServiceEnabled(context: Context): Boolean {
        return try {
            val expected = "${context.packageName}/${MinisAccessibilityService::class.java.name}"
            val enabled = android.provider.Settings.Secure.getString(
                context.contentResolver,
                android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ) ?: return false
            enabled.split(':').any { it.equals(expected, ignoreCase = true) }
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
