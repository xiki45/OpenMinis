package com.openminis.app.assist

import android.app.assist.AssistContent
import android.app.assist.AssistStructure
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
 *
 * [T-assist-screenshot] 截图来源：系统以 SHOW_WITH_SCREENSHOT 展示会话时，
 * [onHandleScreenshot] 会收到当前屏幕位图；[handoff] 里经
 * [AssistCapture.saveFrameworkShot] 落盘后，路径一并放进 pendingAssist，
 * 让新对话首条消息同时附带该图（HyperOS hook/startService 路线则改由
 * [AssistCapture] 用无障碍服务自行截屏）。
 */
class AssistSession(context: android.content.Context) :
    VoiceInteractionSession(context) {

    private val TAG = "AssistSession"

    // [T-assist-screenshot] 标准路线：等待 onHandleScreenshot 的屏幕位图到达。
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var frameworkShot: Bitmap? = null
    private var showWithScreenshot = false
    private var handedOff = false
    private val SCREENSHOT_WAIT_MS = 350L

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        // [T-assist-screenshot] 系统以 SHOW_WITH_SCREENSHOT 展示会话时，会随后
        // 经 onHandleScreenshot 推送屏幕位图；据此决定是否在 handoff 前等待截图。
        showWithScreenshot = (showFlags and VoiceInteractionSession.SHOW_WITH_SCREENSHOT) != 0
        AppLogger.info(TAG, "session shown")
    }

    override fun onHandleScreenshot(screenshot: Bitmap?) {
        super.onHandleScreenshot(screenshot)
        // [T-assist-screenshot] 标准框架路线：接收系统推送的当前屏幕位图。
        if (screenshot != null) frameworkShot = screenshot
    }

    override fun onHandleAssist(
        state: Bundle?,
        structure: AssistStructure?,
        content: AssistContent?,
    ) {
        super.onHandleAssist(state, structure, content)
        AppLogger.info(TAG, "onHandleAssist called")

        val ctx = AssistContext.flatten(structure, content)
        if (ctx.isBlank()) AppLogger.warning(TAG, "no usable assist context")

        // [T-assist-screenshot] 不再立即 finish：若系统承诺推送截图且尚未到达，
        // 短暂等待一帧，随后统一 handoff（含/不含截图）。
        val waitMs = if (showWithScreenshot && frameworkShot == null) SCREENSHOT_WAIT_MS else 0L
        mainHandler.postDelayed({ handoff(ctx) }, waitMs)
    }

    /** [T-assist-screenshot] 统一交接点：落盘截图（如有）→ 暂存 pendingAssist → 拉起 chat。 */
    private fun handoff(ctx: String) {
        if (handedOff) return
        handedOff = true
        var shotPath: String? = null
        val shot = frameworkShot
        if (shot != null && AssistCapture.isEnabled(context)) {
            shotPath = AssistCapture.saveFrameworkShot(context, shot)?.absolutePath
        }
        shot?.recycle()
        frameworkShot = null
        if (ctx.isBlank() && shotPath == null) {
            AppLogger.warning(TAG, "no assist context and no screenshot; nothing to hand off")
            finish()
            return
        }
        DeepLinkCoordinator.setPendingAssist(ctx.takeIf { it.isNotBlank() }, shotPath)
        launchMinisChat()
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
