package com.openminis.app.ui.chat

import com.openminis.app.ProductionSources
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-model-refusal] Every entry that starts a new agent run clears the
 * pending Resume before it claims the stream, as iOS send() / retry() /
 * resume() do (canResume = false). retryLast() did not: after a refusal (which
 * sets canResume) a successful Retry left the Resume banner under the reply.
 */
class RerunClearsCanResumeTest {

    private val vm = ProductionSources.read("ui/chat/ChatViewModel.kt")

    /**
     * The body of [signature] up to where it claims the stream, plus the two
     * lines right after the claim (resumeQueueAfterCancel clears canResume on
     * the line after `_isStreaming.value = true`, still before any await).
     */
    private fun untilStreamClaim(signature: String): String {
        val start = vm.indexOf(signature)
        assertTrue("missing $signature", start >= 0)
        val claim = vm.indexOf("_isStreaming.value = true", start)
        assertTrue("$signature never claims the stream", claim > start)
        var end = claim
        repeat(3) { end = vm.indexOf('\n', end + 1) }
        return vm.substring(start, end)
    }

    @Test
    fun `every run entry clears canResume before claiming the stream`() {
        for (sig in listOf(
            "    fun retryLast() {",
            "    fun retryFromMessage(messageId: String) {",
            "    fun rerunFromToolBlock(assistantMessageId: String, blockId: String): Boolean {",
            "    private fun sendMessage(\n",
            "    private fun resumeQueueAfterCancel() {",
            "    fun resume() {",
        )) {
            val prefix = untilStreamClaim(sig)
            // Only this function's own body: no other `fun` between.
            assertTrue("$sig: the stream claim found belongs to another function",
                !prefix.substring(sig.length).contains("\n    fun ") &&
                    !prefix.substring(sig.length).contains("\n    private fun "))
            assertTrue("$sig must set _canResume.value = false when it starts the run",
                prefix.contains("_canResume.value = false"))
        }
    }
}
