package com.openminis.app.ui.chat

import com.openminis.app.data.model.ProviderCredential
import com.openminis.app.data.model.ProviderInstance
import com.openminis.app.data.model.ProviderType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** An open chat picks up a provider edited in Settings before its next turn. */
class ProviderEditsTest {

    private fun inst(id: String, responses: Boolean = false) = ProviderInstance(
        id = id,
        label = id,
        providerType = ProviderType.openAI,
        credentialType = ProviderCredential.apiKey,
        useResponsesAPI = responses,
    )

    @Test
    fun `switching the API format is an edit`() {
        val before = mapOf("a" to inst("a"), "b" to inst("b"))
        val now = mapOf("a" to inst("a", responses = true), "b" to inst("b"))
        assertEquals(setOf("a"), editedProviderInstanceIds(before, now))
    }

    @Test
    fun `adding or removing a provider is not an edit`() {
        val before = mapOf("a" to inst("a"))
        assertTrue(editedProviderInstanceIds(before, mapOf("a" to inst("a"), "c" to inst("c"))).isEmpty())
        assertTrue(editedProviderInstanceIds(before, emptyMap()).isEmpty())
    }

    @Test
    fun `send, retry and resume check for an edit before taking the provider`() {
        val src = File("src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt").readText()
        assertEquals(3, Regex("\\n\\s*rebuildProviderIfEdited\\(\\)\\n").findAll(src).count())
    }
}
