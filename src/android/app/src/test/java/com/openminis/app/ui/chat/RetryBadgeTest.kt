package com.openminis.app.ui.chat

import com.openminis.app.ProductionSources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [T-android-retry-badge] An automatic retry shows a yellow "↻ n/3" badge in
 * front of "<name> is thinking…" instead of a red "<error> — retrying (n/3)…"
 * bar; tapping it lists this generation's retries, which live in memory only.
 */
class RetryBadgeTest {

    private val vm = ProductionSources.read("ui/chat/ChatViewModel.kt")

    @Test
    fun `badge text is the attempt of the ladder, none when not retrying`() {
        assertEquals("1/3", RetryLog.badgeText(1, 3))
        assertEquals("3/3", RetryLog.badgeText(3, 3))
        assertNull(RetryLog.badgeText(0, 3))
    }

    @Test
    fun `the log keeps every retry and marks a model or endpoint change`() {
        var log = RetryLog.append(emptyList(), 1, 3, 1_000, "timeout", "Claude Sonnet 5", "Anthropic", 1)
        assertFalse("the first retry has nothing to compare with", log.single().switchedModel)
        log = RetryLog.append(log, 2, 3, 2_000, "502", "Claude Sonnet 5", "Anthropic", 2)
        assertFalse(log[1].switchedModel)
        log = RetryLog.append(log, 1, 3, 9_000, "reset", "DeepSeek V4", "DeepSeek", 1)
        assertTrue("after a fallback the next ladder is on another model", log[2].switchedModel)
        log = RetryLog.append(log, 2, 3, 10_000, "reset", "DeepSeek V4", "DeepSeek (mirror)", 2)
        assertTrue("same model through another endpoint counts too", log[3].switchedModel)
        assertEquals(listOf(1, 2, 1, 2), log.map { it.attempt })
        assertEquals(RetryRecord(2, 3, 2_000, "502", "Claude Sonnet 5", "Anthropic", 2, false), log[1])
    }

    @Test
    fun `a retry records itself and shows thinking, not a red bar`() {
        val retry = vm.substringAfter("if (isTransient && retryAttempt < AUTO_RETRY_DELAYS_SEC.size) {")
            .substringBefore("continue  // retry on same provider")
        assertFalse("no transient red bar", retry.contains("setTransientInlineError("))
        assertFalse(vm.contains("private fun setTransientInlineError("))
        assertTrue(retry.contains("_retryLog.value = RetryLog.append("))
        assertTrue(retry.contains("model = currentProvider.model.displayName,"))
        assertTrue(retry.contains("delaySec = delaySec,"))
        assertTrue("keeps the typing row up", retry.contains("isAwaitingModelResponse = true,"))
        assertTrue("the badge's state is the existing counter", retry.contains("_autoRetryAttempt.value = retryAttempt"))
    }

    @Test
    fun `the log is per generation and in memory`() {
        val reset = vm.substringAfter("private val retryLogReset = viewModelScope.launch {").substringBefore("\n    }\n")
        assertTrue(reset.contains("if (!streaming && _retryLog.value.isNotEmpty()) _retryLog.value = emptyList()"))
        assertFalse("never persisted", Regex("""_retryLog[^\n]*(chatRepository|dao\.)""").containsMatchIn(vm))
    }

    @Test
    fun `the chat shows the badge in the typing row and opens the sheet`() {
        val screen = ProductionSources.read("ui/chat/ChatScreen.kt")
        assertTrue(screen.contains("val retryAttempt by viewModel.autoRetryAttempt.collectAsState()"))
        assertTrue(screen.contains("onRetryBadgeClick = { retrySheetSnapshot = viewModel.retryLog.value },"))
        assertTrue(screen.contains("RetryLogSheet(records = liveRetries.ifEmpty { snapshot }, onDismiss = { retrySheetSnapshot = null })"))
        val ind = ProductionSources.read("ui/chat/ChatIndicators.kt")
        assertTrue(ind.contains("if (retryAttempt > 0 && onRetryBadgeClick != null) {"))
        assertTrue("the sub-agent transcript keeps the plain indicator",
            ProductionSources.read("ui/chat/HelperUi.kt").contains("is FlatChatItem.AssistantTyping -> TypingIndicator()"))
    }

    @Test
    fun `the retry rollback keeps the message awaiting, so the badge row stays up`() {
        // [T-retry-badge-awaiting] With text already on screen the typing row
        // (and its badge) shows only while isAwaitingModelResponse is set.
        val vm = ProductionSources.read("ui/chat/ChatViewModel.kt")
        val rollback = vm.substringAfter("// [T-retry-badge-awaiting]").take(900)
        assertTrue(rollback.contains("assistantId, accumulatedText + turnTextSb.toString(), true, allToolBlocks,\n                                    isAwaitingModelResponse = true,"))
        val flat = ProductionSources.read("ui/chat/ChatFlatItems.kt")
        assertTrue(flat.contains("if (message.isStreaming && (!hasVisibleContent || message.isAwaitingModelResponse)) {"))
    }

    @Test
    fun `strings in English and both Chinese variants`() {
        val res = File(ProductionSources.mainRoot()!!, "../../../../res")
        val keys = listOf("retry_badge_a11y", "retry_sheet_title", "retry_sheet_subtitle", "retry_sheet_empty", "retry_record_title",
            "retry_record_error", "retry_record_model", "retry_record_endpoint", "retry_record_delay", "retry_record_switched")
        for (dir in listOf("values", "values-zh", "values-zh-rTW")) {
            val text = File(res, "$dir/strings_retry_badge.xml").readText()
            for (k in keys) assertTrue("$dir lacks $k", text.contains("name=\"$k\""))
        }
    }
}
