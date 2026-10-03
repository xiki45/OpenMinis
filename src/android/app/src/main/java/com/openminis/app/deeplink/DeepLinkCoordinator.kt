package com.openminis.app.deeplink

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Holds pending deep-link side-effects that outlive a single navigation event.
 * Mirrors iOS DeepLinkCoordinator.
 */
object DeepLinkCoordinator {

    data class EnvVarCreate(val key: String, val value: String, val note: String)

    private val _pendingEnvVarCreate = MutableStateFlow<EnvVarCreate?>(null)
    val pendingEnvVarCreate: StateFlow<EnvVarCreate?> = _pendingEnvVarCreate.asStateFlow()

    fun setPendingEnvVarCreate(key: String, value: String, note: String = "") {
        _pendingEnvVarCreate.value = EnvVarCreate(key, value, note)
    }

    fun consumePendingEnvVarCreate(): EnvVarCreate? {
        val current = _pendingEnvVarCreate.value
        _pendingEnvVarCreate.value = null
        return current
    }

    /**
     * Optional `?tab=…` hint from `minis://settings/logs?tab=config-audit`.
     * The Logs screen reads this on appear to land on the right
     * segmented-control tab. Cleared by the screen after consumption.
     * Mirrors iOS DeepLinkCoordinator.pendingLogsTab.
     */
    private val _pendingLogsTab = MutableStateFlow<String?>(null)
    val pendingLogsTab: StateFlow<String?> = _pendingLogsTab.asStateFlow()

    fun setPendingLogsTab(tab: String?) { _pendingLogsTab.value = tab }
    fun consumePendingLogsTab(): String? {
        val current = _pendingLogsTab.value
        _pendingLogsTab.value = null
        return current
    }

    /**
     * Pending pinned-shortcut HTML preview: filesystem path + cached title.
     * MainActivity sets this on `minis://preview/html` deep link; ChatScreen
     * reads it on first composition and routes into WebPreviewFullscreen.
     */
    data class HtmlPreview(val sessionId: String, val resourcePath: String, val title: String)

    private val _pendingHtmlPreview = MutableStateFlow<HtmlPreview?>(null)
    val pendingHtmlPreview: StateFlow<HtmlPreview?> = _pendingHtmlPreview.asStateFlow()

    fun setPendingHtmlPreview(sessionId: String, resourcePath: String, title: String) {
        _pendingHtmlPreview.value = HtmlPreview(sessionId, resourcePath, title)
    }

    fun consumePendingHtmlPreview(): HtmlPreview? {
        val current = _pendingHtmlPreview.value
        _pendingHtmlPreview.value = null
        return current
    }

    /**
     * App-icon quick-action that a freshly-opened ChatScreen should auto-
     * trigger on first compose. Mirrors iOS `pendingChatAction` on
     * AIChatViewModel. Set by [com.openminis.app.MainActivity] /
     * [com.openminis.app.ui.navigation.AppNavigation] when the launch
     * intent carries `minis://action/voice_chat` or
     * `minis://action/camera_chat`; consumed exactly once by ChatScreen
     * so re-entering the same chat later doesn't fire the action again.
     */
    enum class ChatAction { START_VOICE, OPEN_CAMERA }

    private val _pendingChatAction = MutableStateFlow<ChatAction?>(null)
    val pendingChatAction: StateFlow<ChatAction?> = _pendingChatAction.asStateFlow()

    fun setPendingChatAction(action: ChatAction) {
        _pendingChatAction.value = action
    }

    fun consumePendingChatAction(): ChatAction? {
        val current = _pendingChatAction.value
        _pendingChatAction.value = null
        return current
    }

    /**
     * System-level Assist: screen context (and optionally a screenshot path)
     * injected from AssistSession (VoiceInteractionSession) or the HyperOS
     * route. Set when the user invokes minis as the default assistant; a
     * freshly-opened ChatScreen consumes it exactly once and sends it as a
     * user message to the agent, attaching the screenshot if present.
     *
     * [screenshotPath] is the absolute path of a cached screenshot the agent
     * should attach so it can respond to what the user was looking at.
     * Mirrors the `pendingChatAction` pattern — the VoiceInteraction flow
     * (App→agent direction) rides the same "deep-link + pending side-effect"
     * mechanism the existing quick-actions use.
     */
    data class PendingAssist(val text: String?, val screenshotPath: String? = null)

    private val _pendingAssist = MutableStateFlow<PendingAssist?>(null)
    val pendingAssist: StateFlow<PendingAssist?> = _pendingAssist.asStateFlow()

    fun setPendingAssist(text: String?, screenshotPath: String? = null) {
        _pendingAssist.value = PendingAssist(text, screenshotPath)
    }

    fun consumePendingAssist(): PendingAssist? {
        val current = _pendingAssist.value
        _pendingAssist.value = null
        return current
    }
}
