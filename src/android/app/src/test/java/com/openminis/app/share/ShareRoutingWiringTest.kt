package com.openminis.app.share

import com.openminis.app.ProductionSources
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-share-routing] User report: sharing a file to Minis while the
 * session list was showing did nothing visible; the file waited for whichever
 * chat was opened next. Reproduced on a Pixel 4a with the default Auto launch
 * mode: the app opens inside a chat (route `chat/{id}`), Back only pops the
 * scaffold's pane navigator, so the list shows under `chat/{id}` and the old
 * `route == sessions` fallback never fired. These pins keep the routing on
 * what is VISIBLE, not on the NavHost route.
 */
class ShareRoutingWiringTest {

    private val scaffold = ProductionSources.read("ui/navigation/ChatSplitScaffold.kt")
    private val nav = ProductionSources.read("ui/navigation/AppNavigation.kt")
    private val screen = ProductionSources.read("ui/chat/ChatScreen.kt")

    @Test fun `the scaffold routes by its visible pane - chat gets it, list or placeholder opens a draft`() {
        val router = scaffold.substringAfter("[T-android-share-routing] Route an incoming share")
        assertTrue(router.contains("?.takeIf { it.pane == ListDetailPaneScaffoldRole.Detail }"))
        assertTrue(router.contains("com.openminis.app.share.ShareCoordinator.addressTo(visibleChat)"))
        assertTrue(router.contains("if (com.openminis.app.share.ShareCoordinator.addressTo(draft)) {"))
        assertTrue("the draft opens through the NavHost", router.contains("onOpenSharedDraft(draft)"))
        assertTrue(scaffold.contains("navController.navigate(Routes.chat(draft)) {"))
        assertTrue(router.contains("shareLifecycle.currentStateFlow.first { it.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED) }"))
    }

    @Test fun `the NavHost fallback leaves scaffold routes alone and waits for RESUMED instead of dropping`() {
        val fallback = nav.substringAfter("Warm-share fallback for screens that do NOT").substringBefore("// Pinned-shortcut cold start")
        assertTrue(fallback.contains("if (route == Routes.SESSION_LIST || route == Routes.CHAT) return@LaunchedEffect"))
        assertTrue(fallback.contains("entry.lifecycle.currentStateFlow.first { it.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED) }"))
        assertFalse("safeNavigate silently drops a non-RESUMED call", fallback.contains("navController.safeNavigate("))
        assertFalse(fallback.contains("current == Routes.SESSION_LIST"))
    }

    @Test fun `a cold-start share opens a new draft before any launch mode is applied`() {
        val startup = nav.substringAfter("val hasPendingShare =").substringBefore("val target: String? = when {")
        assertTrue(startup.contains("if (hasPendingShare) {"))
        assertTrue(startup.contains("if (com.openminis.app.share.ShareCoordinator.addressTo(draft)) {"))
        assertFalse("the old Home-only rule is gone", nav.contains("hasPendingShare && mode == 3"))
    }

    @Test fun `a chat consumes only its own share and says where it went`() {
        val consumer = screen.substringAfter("T51: Share Injection").substringBefore("T311: publish")
        assertTrue(consumer.contains("ShareCoordinator.consumeBuffer(context, sessionId)"))
        assertTrue(consumer.contains("isEmptyChat -> context.getString(com.openminis.app.R.string.share_added_new_chat)"))
        assertTrue(consumer.contains("val isEmptyChat = viewModel.messages.value.isEmpty()"))
        assertTrue(consumer.contains("R.string.share_added_to_chat, title"))
        assertTrue(consumer.contains("R.string.share_added_this_chat"))
    }
}
