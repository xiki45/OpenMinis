package com.openminis.app.assist

import android.content.Intent
import android.service.voice.VoiceInteractionService
import com.openminis.app.logging.AppLogger

/**
 * Minis 系统默认助理入口。
 *
 * 系统在"设置 → 默认应用 → 数字助理"里把本服务选为默认助理后，长按 Home /
 * 上滑手势会 bind 到这里并调 onShowSession。系统据此判定 minis 是"默认助理"。
 *
 * 关键点（与 minis 现有系统集成风格一致）：
 *  1. `BIND_VOICE_INTERACTION` 权限声明在 manifest 上，系统据此识别。
 *  2. "能处理 assist（屏幕上下文）"通过 res/xml/voice_interaction_service.xml 的
 *     android:supportsAssist="true" 声明——系统据此才会调
 *     AssistSession.onHandleAssist()，否则拿不到当前屏幕。（注意：
 *     VoiceInteractionService 并无 supportsAssist/setSupportsAssist 运行时方法，
 *     该能力只能通过 XML meta-data 声明。）
 *  3. 语音识别复用系统 [android.speech.SpeechRecognizer]，不强制自建 ASR。
 */
class AssistService : VoiceInteractionService() {

    override fun onReady() {
        super.onReady()
        AppLogger.info(TAG, "AssistService ready")
    }

    /**
     * 引导用户去系统设置里把 minis 设为默认助理。
     * 返回 true 表示系统已跳转；minis 无法静默接管，需用户确认。
     */
    fun launchDefaultAssistSettings(): Boolean {
        return try {
            // 标准做法：跳系统"默认助理"设置项
            val intent = Intent(
                android.provider.Settings.ACTION_VOICE_INPUT_SETTINGS,
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
            true
        } catch (t: Throwable) {
            AppLogger.warning(TAG, "open assist settings failed: ${t.message}")
            false
        }
    }

    companion object {
        private const val TAG = "AssistService"
    }
}
