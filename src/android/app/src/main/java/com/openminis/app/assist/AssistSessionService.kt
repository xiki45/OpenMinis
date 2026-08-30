package com.openminis.app.assist

import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import com.openminis.app.logging.AppLogger

/**
 * 每发起一次"默认助理"会话（长按 Home / 上滑）会创建本服务，再由它创建
 * 一个 [AssistSession]。系统把 assist 回调（屏幕上下文）投递给那个 session。
 */
class AssistSessionService : VoiceInteractionSessionService() {

    override fun onNewSession(bundle: android.os.Bundle?): VoiceInteractionSession {
        AppLogger.info(TAG, "new session created")
        return AssistSession(this)
    }

    companion object {
        private const val TAG = "AssistSessionService"
    }
}
