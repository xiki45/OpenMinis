package com.openminis.app.assist

import android.app.assist.AssistContent
import android.app.assist.AssistStructure
import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import com.openminis.app.deeplink.DeepLinkCoordinator
import com.openminis.app.logging.AppLogger

/**
 * 一次"默认助理"会话。系统唤起默认助理后，把当前屏幕的 assist 数据
 * （[AssistStructure] + [AssistContent]）投递给这里的 [onHandleAssist]。
 *
 * 职责：把屏幕上下文变成 minis agent 的输入。
 *
 * 注入通路（复用 minis 现成的"深链 + 副作用暂存"模式，与
 * DeepLinkCoordinator.pendingChatAction 完全同构，改动最小）：
 *
 *   1. [onHandleAssist] → [AssistContext.flatten] 展平屏幕为文本
 *   2. 把文本放进 [DeepLinkCoordinator.pendingAssist]
 *   3. startActivity 打开 MainActivity（minis://assist 深链 → 进 chat 会话）
 *   4. ChatScreen 首次 compose 消费 pendingAssist → ChatViewModel.sendMessage(ctx)
 *
 * 这样 agent 就能看到"用户此刻在看什么"，直接给出情境化响应。
 */
class AssistSession(context: android.content.Context) :
    VoiceInteractionSession(context) {

    private val TAG = "AssistSession"

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        AppLogger.info(TAG, "session shown")
    }

    override fun onHandleAssist(
        state: Bundle?,
        structure: AssistStructure?,
        content: AssistContent?,
    ) {
        super.onHandleAssist(state, structure, content)
        AppLogger.info(TAG, "onHandleAssist called")

        val ctx = AssistContext.flatten(structure, content)
        if (ctx.isBlank()) {
            AppLogger.warning(TAG, "no usable assist context")
            finish()
            return
        }

        // 1) 暂存为待注入的 assist 消息
        DeepLinkCoordinator.setPendingAssist(ctx)

        // 2) 打开 minis 主界面进入 chat（复用现有深链导航）
        launchMinisChat()

        // 3) 关闭 voice session（UI 交接给 minis chat 会话）
        finish()
    }

    private fun launchMinisChat() {
        try {
            val intent = Intent(Intent.ACTION_VIEW)
                .setData(android.net.Uri.parse("minis://assist"))
                .addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP,
                )
            context.startActivity(intent)
        } catch (t: Throwable) {
            AppLogger.warning(TAG, "launch minis chat failed: ${t.message}")
        }
    }
}
