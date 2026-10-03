package com.openminis.app.assist

import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.openminis.app.MainActivity
import com.openminis.app.logging.AppLogger

/**
 * 承接 OEM 私有手势配置的 startService 唤起，把系统手势直达 Minis。
 *
 * 背景：真机（HyperOS）上，长按手势条的唤起由小米私有配置
 * `entity_config_key_voice_assistant`（Settings.Secure 里的 JSON）驱动，
 * 其字段 `pkgName` / `clazzName` 原本指向小米自家的
 * `com.xiaomi.voiceassistant.VoiceService`（一个被系统 startService 的
 * 服务）。我们把该配置改写为指向 Minis 的此服务，让系统以 startService
 * 的方式唤起它，再由它把 Minis 打开到新的 assist 会话。
 *
 * 与 [AssistSessionService]（框架 bind 的 VoiceInteractionSession 路径）
 * 不同：这里走的是最朴素的 startService 路径，系统不会携带任何屏幕
 * 上下文，只需要"打开一个新会话"。
 */
class AssistTriggerService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        AppLogger.info(TAG, "onStartCommand invoked by system startService")
        // [T-assist-screenshot] service 路径无窗口，这里发射截屏时机甚至比 activity 更早。
        // 透传触发 intent：若携带 EXTRA_ATTACH_SCREEN=false 则同样不截图。
        AssistCapture.requestIfEnabled(applicationContext, intent)
        try {
            // 构造指向 MainActivity 的 Intent，action 用 ACTION_ASSIST：
            // MainActivity 的 isAssistEntryIntent 会按 assist 入口打开新会话。
            val launcher = Intent(this, MainActivity::class.java)
                .setAction(Intent.ACTION_ASSIST)
                .addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP,
                )
            // 透传触发源的截图开关判定，避免 MainActivity 侧重复发射时丢失该信号。
            if (intent != null && intent.hasExtra(AssistCapture.EXTRA_ATTACH_SCREEN)) {
                launcher.putExtra(
                    AssistCapture.EXTRA_ATTACH_SCREEN,
                    intent.getBooleanExtra(AssistCapture.EXTRA_ATTACH_SCREEN, true),
                )
            }
            startActivity(launcher)
            AppLogger.info(TAG, "launched MainActivity assist entry")
        } catch (t: Throwable) {
            // 兜底：任何异常都不应让这个被系统 startService 的服务崩溃。
            AppLogger.warning(TAG, "failed to launch MainActivity: ${t.message}")
        } finally {
            stopSelf()
        }
        // 一次性触发服务，无需系统在进程死亡后重启。
        return START_NOT_STICKY
    }

    companion object {
        private const val TAG = "AssistTriggerService"
    }
}
