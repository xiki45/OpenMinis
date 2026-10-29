package com.openminis.app.ui.chat

import com.openminis.app.ProductionSources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ISS-036, T-provider-live-check] A running task stops using a provider that
 * was removed or disabled - checked before every request (port of iOS
 * a45ef439b).
 */
class ProviderLiveCheckTest {

    private val src = ProductionSources.read("ui/chat/ChatViewModel.kt")

    @Test
    fun `only a missing or disabled provider instance is a problem`() {
        assertEquals(ProviderAvailability.Problem.REMOVED, ProviderAvailability.problem(instanceExists = false, instanceEnabled = false))
        assertEquals(ProviderAvailability.Problem.REMOVED, ProviderAvailability.problem(instanceExists = false, instanceEnabled = true))
        assertEquals(ProviderAvailability.Problem.DISABLED, ProviderAvailability.problem(instanceExists = true, instanceEnabled = false))
        assertNull(ProviderAvailability.problem(instanceExists = true, instanceEnabled = true))
    }

    @Test
    fun `the check runs at the top of every request pass, before the stream is opened`() {
        val loop = src.substringAfter("while (!collectDone) {").substringBefore("} catch (e: Exception) {")
        val check = loop.indexOf("val liveProblem = currentIdentity?.let")
        val request = loop.indexOf("[T-STALL-DIAG] stream REQUEST-OUT")
        assertTrue(check in 0 until request)
        assertTrue("decided by the provider instance, not the model entry",
            loop.contains("val inst = providerRepository.instance(id.instanceId)"))
    }

    @Test
    fun `a pinned conversation fails with the provider named, a group falls back`() {
        val loop = src.substringAfter("while (!collectDone) {").substringBefore("} catch (e: Exception) {")
        val beforeTry = loop.substringBefore("                try {")
        assertTrue("pinned: thrown outside the try, so no default-group fallback and no retry",
            beforeTry.contains("if (_selectedGroupId.value == null) {") &&
                beforeTry.contains("R.string.provider_live_check_removed_message") &&
                beforeTry.contains("R.string.provider_live_check_disabled_message"))
        val insideTry = loop.substringAfter("                try {")
        assertTrue("group: a fallbackable ProviderError inside the try",
            insideTry.trimStart().startsWith("// [ISS-036]") && insideTry.contains("LLMError.ProviderError("))
    }

    @Test
    fun `the identity follows every provider change in the loop`() {
        assertTrue(src.contains("var currentIdentity = providerIdentity(_activeEntryId.value)"))
        assertTrue("mid-turn model switch",
            src.contains("classProviderAtSend = chosen\n                    currentIdentity = providerIdentity(_activeEntryId.value)"))
        assertTrue("group fallback",
            src.contains("currentProvider = next\n                        currentIdentity = providerIdentity(nextCandidate.entryId)"))
    }
}
